package com.dsmnh.mobile.modserver;

import com.dsmnh.mobile.modserver.node.NodeApi;
import com.dsmnh.mobile.modserver.node.NodeJson;
import com.dsmnh.mobile.modserver.node.NodeClient;
import com.dsmnh.mobile.modserver.node.NodeConfig;
import com.dsmnh.mobile.modserver.node.NodeCredentialStore;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.common.config.Configuration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;

/**
 * Horizon 服务端模组：把本子服接入 Horizon 后端节点平面（协议 1.1.1）。
 * 阶段 1 范围：节点注册、游戏内出码（绑定/恢复）、队伍域代调用；
 * 不含聊天/ME/TPS（阶段 3/5/6，见企划书 08）。
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
        if (config.hasChanged()) {
            config.save();
        }
        credentialFile = new File(event.getModConfigurationDirectory(), "dsmnhmobile-node-credential.json");
        queue = new MainThreadQueue();
        queue.register();
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

    /** 下行操作执行：op.set 必须在主线程改 OP 列表；ack/fail 经后台线程回报（契约 1.2.0）。 */
    private void handleDownOp(final String opId, final String type, final JsonNode p) {
        if ("team.apply".equals(type)) {
            // SU 写入适配器未实现（只读聚合已就绪）：诚实 fail，后端按退避重试
            reportOpResult(opId, "SU write adapter pending");
            return;
        }
        if ("eu.join".equals(type)) {
            // /gt global_energy_join 的参数格式未经真服验证，不盲执行
            reportOpResult(opId, "eu.join command format requires verification");
            return;
        }
        if (!"op.set".equals(type)) {
            reportOpResult(opId, "unsupported type " + type);
            return;
        }
        final String player = p.path("player").asText("");
        final boolean grant = p.path("grant").asBoolean(false);
        queue.enqueue(new Runnable() {
            @Override
            public void run() {
                String error = applyOpSet(player, grant);
                reportOpResult(opId, error);
            }
        });
    }

    /** 主线程执行 OP 变更；返回 null 表示成功，否则为失败原因。 */
    private String applyOpSet(String player, boolean grant) {
        cpw.mods.fml.common.FMLCommonHandler.instance();
        net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
        if (server == null || server.getConfigurationManager() == null) {
            return "server not ready";
        }
        String name = resolveOnlineName(player);
        if (name == null) {
            return "player not online: " + player;
        }
        // 经控制台命令通道执行（name 来自在线玩家的 UUID 解析，无注入面）；
        // GTNH 映射下 ServerConfigurationManager 无稳定的 addOp/removeOp 源码名
        String command = (grant ? "op " : "deop ") + name;
        int affected = server.getCommandManager().executeCommand(server, command);
        if (affected <= 0) {
            return "command had no effect: " + command;
        }
        LOG.info("{} OP：{}", grant ? "已授予" : "已撤销", name);
        return null;
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
                    LOG.info("队伍对账上报：{}", res.path("b").path("accepted").asBoolean() ? "已受理" : "被拒");
                } catch (Exception e) {
                    LOG.warn("队伍对账上报失败：{}", e.toString());
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
