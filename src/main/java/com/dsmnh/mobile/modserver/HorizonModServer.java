package com.dsmnh.mobile.modserver;

import com.dsmnh.mobile.modserver.node.NodeApi;
import com.dsmnh.mobile.modserver.node.NodeJson;
import com.dsmnh.mobile.modserver.node.NodeClient;
import com.dsmnh.mobile.modserver.node.NodeConfig;
import com.dsmnh.mobile.modserver.node.NodeCredentialStore;
import cpw.mods.fml.common.Mod;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ServerChatEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.common.config.Configuration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;

/**
 * Horizon 服务端模组：把本子服接入 Horizon 后端节点平面（协议 1.6.0）。
 * 覆盖节点注册、登记表 §6 六类下行操作、监控与在线玩家名上报；
 * 游戏侧读写统一经 MainThreadQueue 回到主线程。
 */
@Mod(modid = Tags.MODID, name = Tags.MODNAME, version = Tags.VERSION,
        acceptedMinecraftVersions = "[1.7.10]")
public final class HorizonModServer {
    public static final Logger LOG = LogManager.getLogger("dsmnhmobile");
    public static HorizonModServer INSTANCE;

    private MainThreadQueue queue;
    private NodeClient node;
    private String backendHost;
    private int backendPort;
    private String nodeName;
    private String euJoinTemplate;
    private File credentialFile;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        INSTANCE = this;
        Configuration config = new Configuration(event.getSuggestedConfigurationFile());
        backendHost = config.get("node", "backendHost", "127.0.0.1",
                "Horizon 后端地址").getString();
        backendPort = config.get("node", "backendPort", 44443,
                "Horizon 后端节点平面端口").getInt();
        nodeName = config.get("node", "nodeName", "",
                "注册时自报名称（仅展示用）").getString();
        euJoinTemplate = config.get("node", "euJoinTemplate", EuJoinTemplate.DEFAULT,
                "eu.join 命令模板，占位符 {player}/{team}").getString();
        if (config.hasChanged()) {
            config.save();
        }
        credentialFile = new File(event.getModConfigurationDirectory(), "dsmnhmobile-node-credential.json");
        BqAdapter.setBackupDir(event.getModConfigurationDirectory());
        MeAdapter.setStore(new File(event.getModConfigurationDirectory(), "dsmnhmobile-me-devices.json"));
        MeAdapter.load();
        queue = new MainThreadQueue();
        queue.register();
        MinecraftForge.EVENT_BUS.register(this);
        tickMonitor.register();
    }

    private final TickMonitor tickMonitor = new TickMonitor();

    /** metrics 上报循环（5s 一帧，契约 1.5.0 §8.6；节点连接后启动）。 */
    private java.util.concurrent.ScheduledExecutorService metricsExecutor;

    private void startMetricsLoop() {
        if (metricsExecutor != null) return;
        metricsExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dsmnhmobile-metrics");
            t.setDaemon(true);
            return t;
        });
        metricsExecutor.scheduleAtFixedRate(() -> {
            NodeClient current = node;
            if (current == null || !current.isAuthenticated()) return;
            try {
                ObjectNode frame = tickMonitor.snapshot();
                if (frame != null) {
                    current.req("metrics.report", frame);
                }
            } catch (Exception e) {
                LOG.warn("metrics 上报失败：{}", e.toString());
            }
        }, 5, 5, java.util.concurrent.TimeUnit.SECONDS);
        // rankings 上报循环（每小时一次，契约 1.5.0 §8.6）
        final java.util.concurrent.atomic.AtomicInteger rankCounter =
                new java.util.concurrent.atomic.AtomicInteger();
        metricsExecutor.scheduleAtFixedRate(() -> {
            if (rankCounter.incrementAndGet() % 12 != 1) return; // 12×5s = 60s 一次
            NodeClient current = node;
            if (current == null || !current.isAuthenticated()) return;
            // SU 数据读取需主线程——先在主线程聚合，再在后台线程发送
            queue.enqueue(() -> {
                ObjectNode rankings = SuAdapter.rankingsData();
                if (rankings.path("boards").size() == 0) return;
                new Thread(() -> {
                    try {
                        ObjectNode p = NodeJson.object();
                        p.set("boards", rankings.get("boards"));
                        current.req("rankings.report", p);
                    } catch (Exception e) {
                        LOG.warn("rankings 上报失败：{}", e.toString());
                    }
                }, "dsmnhmobile-rank").start();
            });
        }, 30, 60, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * 游戏内公屏 → 后端 chat.relay（契约 1.4.0）：有 chat 订阅时才投递（零闲时开销）。
     * 仅玩家公屏（ServerChatEvent 不含命令与私聊）；判断在事件线程，投递异步，不阻塞聊天。
     */
    @cpw.mods.fml.common.eventhandler.SubscribeEvent(
        priority = cpw.mods.fml.common.eventhandler.EventPriority.LOWEST)
    public void onServerChat(ServerChatEvent event) {
        // 同服装有 HorizonChat 时公屏由它格式化并投递节点平面，事件已被取消，此处只做后备上报
        if (event.isCanceled()) {
            return;
        }
        NodeClient current = node;
        if (current == null || !current.isAuthenticated()) {
            return;
        }
        final String from = event.player.getUniqueID().toString();
        final String text = event.message;
        final String srv = nodeName;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    com.fasterxml.jackson.databind.node.ObjectNode p = NodeJson.object();
                    p.put("channel", "public");
                    p.put("from", from);
                    p.put("text", text);
                    p.put("srv", srv);
                    current.req("chat.relay", p);
                } catch (Exception e) {
                    LOG.warn("chat.relay 投递失败：{}", e.toString());
                }
            }
        }, "dsmnhmobile-relay").start();
    }

    /** 在线玩家集合变化即重新上报，使后端 player_names 与账号/队伍展示保持同步。 */
    @cpw.mods.fml.common.eventhandler.SubscribeEvent
    public void onPlayerLoggedIn(cpw.mods.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent event) {
        reportPlayerNames(null);
    }

    @cpw.mods.fml.common.eventhandler.SubscribeEvent
    public void onPlayerLoggedOut(cpw.mods.fml.common.gameevent.PlayerEvent.PlayerLoggedOutEvent event) {
        reportPlayerNames(event.player.getUniqueID().toString());
    }

    /** 主线程采集玩家名，后台线程上报；excludeUuid 用于排除刚离线的玩家。 */
    private void reportPlayerNames(final String excludeUuid) {
        queue.enqueue(new Runnable() {
            @Override
            public void run() {
                final ArrayNode players = PlayerNames.snapshot(excludeUuid);
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            NodeApi api = api();
                            if (api != null) {
                                api.playerNamesReport(players);
                            }
                        } catch (Exception e) {
                            LOG.warn("玩家名上报失败：{}", e.toString());
                        }
                    }
                }, "dsmnhmobile-names").start();
            }
        });
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new NodeCommand());
    }

    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        NodeCredentialStore store = new NodeCredentialStore(credentialFile.toPath());
        if (store.load() == null) {
            LOG.info("尚无节点凭证：请由服主在后端控制台签发注册码后执行 /dsmnhmobile register <码>");
            return;
        }
        connect();
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        if (metricsExecutor != null) {
            metricsExecutor.shutdownNow();
            metricsExecutor = null;
        }
        if (node != null) {
            node.close();
            node = null;
        }
    }

    /** 注册用的临时连接配置：与持久连接同源（host/port 来自 cfg，缺省端口不参与）。 */
    private NodeConfig configForRegistration() {
        NodeConfig config = NodeConfig.defaults(credentialFile.toPath());
        config.backendHost = backendHost;
        config.backendPort = backendPort;
        config.nodeName = nodeName;
        config.app = "HorizonModServer/" + Tags.VERSION;
        return config;
    }

    /** 已持凭证时建立持久连接（服务启动或注册成功后调用）。 */
    public synchronized void connect() {
        if (node != null) {
            node.close();
        }
        node = new NodeClient(configForRegistration(), new NodeCredentialStore(credentialFile.toPath()));
        node.listener(new NodeClient.Listener() {
            @Override
            public void onConnected(long nodeId) {
                LOG.info("已连接 Horizon 后端（节点 {}）", nodeId);
                reportTeams();
                reportPlayerNames(null);
                startMetricsLoop();
            }

            @Override
            public void onChatMessage(String from, com.fasterxml.jackson.databind.JsonNode event) {
                // chat 流下行：App 公聊→游戏内显示（契约 1.4.0）。主线程投递。
                String text = event.path("text").asText();
                String srv = event.path("srv").asText("");
                queue.enqueue(() -> {
                    net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
                    if (server != null && server.getConfigurationManager() != null) {
                        String display = "§b[Horizon]§r " + (srv.isEmpty() ? "" : "§7[" + srv + "]§r ") + text;
                        server.getConfigurationManager().sendChatMsg(
                                new net.minecraft.util.ChatComponentText(display));
                    }
                });
            }

            @Override
            public void onDisconnected(boolean willRetry) {
                if (willRetry) {
                    LOG.info("与后端断开，按退避自动重连");
                }
            }

            @Override
            public void onRevoked() {
                LOG.warn("节点凭证已失效（被撤销或版本不兼容），不再重连；"
                        + "重新接入请先删除 {} 后用新注册码 register", credentialFile.getName());
            }

            @Override
            public void onDownOp(String opId, String type, JsonNode p) {
                handleDownOp(opId, type, p);
            }
        });
        node.start();
    }

    /** 下行操作执行（登记表 §6）：六类均有执行分支；游戏侧读写统一回主线程。 */
    private void handleDownOp(final String opId, final String type, final JsonNode p) {
        final String phase = p.path("phase").asText("");
        switch (DownOpRouter.route(type, phase)) {
            case TEAM_APPLY: {
                final JsonNode team = p.path("team");
                final JsonNode relations = p.path("relations");
                queue.enqueue(new Runnable() {
                    @Override
                    public void run() {
                        reportOpResult(opId, SuAdapter.applyTeam(team, relations));
                    }
                });
                return;
            }
            case OP_SET: {
                final String player = p.path("player").asText("");
                final boolean grant = p.path("grant").asBoolean(false);
                queue.enqueue(new Runnable() {
                    @Override
                    public void run() {
                        reportOpResult(opId, applyOpSet(player, grant));
                    }
                });
                return;
            }
            case EU_JOIN: {
                queue.enqueue(new Runnable() {
                    @Override
                    public void run() {
                        reportOpResult(opId, applyEuJoin(p));
                    }
                });
                return;
            }
            case RANKINGS_APPLY: {
                final JsonNode boards = p.path("boards");
                queue.enqueue(new Runnable() {
                    @Override
                    public void run() {
                        reportOpResult(opId, RankingsAdapter.applyBoards(boards));
                    }
                });
                return;
            }
            case BQ_EXPORT:
                handleBqExport(opId, p);
                return;
            case BQ_IMPORT:
                handleBqImport(opId, p);
                return;
            case ME_QUERY:
                handleMeQuery(opId, p);
                return;
            default:
                reportOpResult(opId, DownOpRouter.rejectReason(type, phase));
        }
    }

    /** eu.join：按配置模板执行 GTNH 官方无线 EU 命令（契约 §8.2）。 */
    private String applyEuJoin(JsonNode p) {
        String player = p.path("player").asText("");
        String team = p.path("teamName").asText("");
        if (player.isEmpty() || team.isEmpty()) {
            return "eu.join 缺少 player/teamName";
        }
        String command = EuJoinTemplate.render(euJoinTemplate, player, team);
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
        if (server == null) {
            return "server not ready";
        }
        int affected = server.getCommandManager().executeCommand(server, command);
        return affected <= 0 ? "eu.join 命令无效：" + command : null;
    }

    /** BQ 导出阶段：主线程生成快照，后台经 bq.snapshot.put 回传后回报 ack/fail。 */
    private void handleBqExport(final String opId, final JsonNode p) {
        queue.enqueue(new Runnable() {
            @Override
            public void run() {
                final ObjectNode snapshot = BqAdapter.exportSnapshot(p);
                final String snapshotId = p.path("snapshotId").asText("");
                final String batchId = p.path("batchId").asText("");
                if (snapshotId.isEmpty() || batchId.isEmpty()) {
                    reportOpResult(opId, "bq.apply export 缺少 snapshotId/batchId");
                    return;
                }
                nodeCall(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            NodeApi api = api();
                            if (api == null) {
                                reportOpResult(opId, "节点未连接");
                                return;
                            }
                            api.bqSnapshotPut(snapshotId, batchId, snapshot);
                            reportOpResult(opId, null);
                        } catch (Exception e) {
                            reportOpResult(opId, "bq 快照回传失败：" + e);
                        }
                    }
                });
            }
        });
    }

    /** BQ 导入阶段：后台拉取快照，主线程备份并覆盖写回；失败不自动重试（契约 §8.4）。 */
    private void handleBqImport(final String opId, final JsonNode p) {
        final String snapshotId = p.path("snapshotId").asText("");
        if (snapshotId.isEmpty()) {
            reportOpResult(opId, "bq.apply import 缺少 snapshotId");
            return;
        }
        nodeCall(new Runnable() {
            @Override
            public void run() {
                try {
                    NodeApi api = api();
                    if (api == null) {
                        reportOpResult(opId, "节点未连接");
                        return;
                    }
                    final JsonNode payload = api.bqSnapshotGet(snapshotId).path("payload");
                    queue.enqueue(new Runnable() {
                        @Override
                        public void run() {
                            reportOpResult(opId, BqAdapter.importSnapshot(payload));
                        }
                    });
                } catch (Exception e) {
                    reportOpResult(opId, "bq 快照拉取失败：" + e);
                }
            }
        });
    }

    /** me.query：主线程执行 AE2 查询，后台经 me.query.result 回填，随后 ack。 */
    private void handleMeQuery(final String opId, final JsonNode p) {
        final String deviceId = p.path("deviceId").asText("");
        final String queryId = p.path("queryId").asText("");
        if (deviceId.isEmpty() || queryId.isEmpty()) {
            reportOpResult(opId, "me.query 缺少 deviceId/queryId");
            return;
        }
        queue.enqueue(new Runnable() {
            @Override
            public void run() {
                final ObjectNode result = MeAdapter.query(deviceId);
                nodeCall(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            NodeApi api = api();
                            if (api == null) {
                                reportOpResult(opId, "节点未连接");
                                return;
                            }
                            api.meQueryResult(queryId, (ArrayNode) result.get("items"),
                                    result.path("error").asText(null));
                            reportOpResult(opId, null);
                        } catch (Exception e) {
                            reportOpResult(opId, "me.query 结果回填失败：" + e);
                        }
                    }
                });
            }
        });
    }

    /** 阻塞式节点请求统一在后台线程执行，避免占用主线程。 */
    private void nodeCall(Runnable call) {
        new Thread(call, "dsmnhmobile-node-call").start();
    }

    /** 主线程执行 OP 变更；返回 null 表示成功，否则为失败原因。 */
    private String applyOpSet(String player, boolean grant) {
        net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
        if (server == null || server.getConfigurationManager() == null) {
            return "server not ready";
        }
        String name = resolveOnlineName(player);
        if (name == null) {
            return "player not online: " + player;
        }
        // 优先直接调用玩家列表的 addOp/removeOp：命令通道在 Cauldron 下返回 0 却已生效，
        // 无法据此判定结果；直接调用后再用 isOpped 复核。
        Object profile = gameProfile(name);
        String viaApi = profile == null ? "缺少玩家档案" : applyOpDirect(server, profile, grant);
        if (viaApi == null) {
            LOG.info("{} OP：{}", grant ? "已授予" : "已撤销", name);
            return null;
        }
        String command = (grant ? "op " : "deop ") + name;
        int affected = server.getCommandManager().executeCommand(server, command);
        if (affected <= 0) {
            return viaApi + "；命令通道无返回：" + command;
        }
        LOG.info("{} OP：{}（命令通道）", grant ? "已授予" : "已撤销", name);
        return null;
    }

    /** 取在线玩家的游戏档案；离线或档案缺失返回 null。 */
    private Object gameProfile(String name) {
        net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
        for (Object o : new java.util.ArrayList<Object>(
                server.getConfigurationManager().playerEntityList)) {
            net.minecraft.entity.player.EntityPlayer player =
                    (net.minecraft.entity.player.EntityPlayer) o;
            if (player.getCommandSenderName().equals(name)) {
                return player.getGameProfile();
            }
        }
        return null;
    }

    /**
     * 直接调用 ServerConfigurationManager 的 addOp/removeOp 并复核结果。
     * 返回 null 表示成功，否则为失败原因（供命令通道后备使用）。
     */
    private String applyOpDirect(net.minecraft.server.MinecraftServer server, Object profile, boolean grant) {
        Object manager = server.getConfigurationManager();
        Class<?> profileType = profile.getClass();
        try {
            String[] names = grant
                    ? new String[]{"addOp", "func_152605_a"}
                    : new String[]{"removeOp", "func_152610_b"};
            java.lang.reflect.Method target = null;
            for (String name : names) {
                try {
                    target = manager.getClass().getMethod(name, profileType);
                    break;
                } catch (Throwable ignored) {
                    // 换下一个候选名
                }
            }
            if (target == null) {
                return "玩家列表缺少 OP 变更方法";
            }
            target.invoke(manager, profile);
            if (isOpped(manager, profile) != grant) {
                return "OP 变更后状态未同步";
            }
            return null;
        } catch (Throwable t) {
            return "OP 变更失败：" + t;
        }
    }

    /** 以 OP 名单为准判断权限；权限判定方法会叠加白名单与在线标记，不能用作状态复核。 */
    private boolean isOpped(Object manager, Object profile) {
        try {
            Object ops = manager.getClass().getMethod("func_152603_m").invoke(manager);
            Object entry = ops.getClass().getMethod("func_152683_b", Object.class).invoke(ops, profile);
            return entry != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 阶段 1 仅解析在线玩家；离线解析留待玩家名上报落地。 */
    private String resolveOnlineName(String playerUuid) {
        net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
        for (Object o : new java.util.ArrayList<Object>(
                server.getConfigurationManager().playerEntityList)) {
            net.minecraft.entity.player.EntityPlayer player =
                    (net.minecraft.entity.player.EntityPlayer) o;
            if (playerUuid.equals(player.getUniqueID().toString())) {
                return player.getCommandSenderName();
            }
        }
        return null;
    }

    private void reportOpResult(final String opId, final String error) {
        final NodeClient current = node;
        if (current == null) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    com.fasterxml.jackson.databind.node.ObjectNode p = NodeJson.object();
                    p.put("opId", opId);
                    if (error == null) {
                        current.req("node.op.ack", p);
                    } else {
                        p.put("reason", error);
                        current.req("node.op.fail", p);
                    }
                } catch (Exception e) {
                    LOG.warn("回报下行结果失败（后端将按退避重试）：{}", e.toString());
                }
            }
        }, "dsmnhmobile-op-report").start();
    }

    /** 用注册码注册本子服（阻塞调用，须在后台线程执行；成功后自动连接）。 */
    public synchronized long register(String code, String name) throws Exception {
        NodeConfig registerConfig = configForRegistration();
        NodeClient registerClient = new NodeClient(registerConfig,
                new NodeCredentialStore(credentialFile.toPath()));
        registerClient.listener(new NodeClient.Listener() {
            @Override
            public void onRevoked() {
                // 注册连接生命周期短，撤销类错误由 register() 异常路径处理
            }
        });
        try {
            long nodeId = registerClient.register(code, name == null || name.isEmpty()
                    ? nodeName : name);
            connect();
            return nodeId;
        } finally {
            registerClient.close();
        }
    }

    /** 队伍对账上报（契约 1.3.0）：聚合本服 SU 队伍经 team.report 上交。 */
    private void reportTeams() {
        final NodeClient current = node;
        if (current == null) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ObjectNode body = NodeJson.object();
                    body.set("teams", SuAdapter.aggregateTeams(
                            net.minecraft.server.MinecraftServer.getServer()).get("teams"));
                    JsonNode res = current.req("team.report", body);
                } catch (Exception e) {
                }
            }
        }, "dsmnhmobile-report").start();
    }

    public NodeClient node() {
        return node;
    }

    public NodeApi api() {
        return node == null ? null : new NodeApi(node);
    }

    public MainThreadQueue queue() {
        return queue;
    }
}
