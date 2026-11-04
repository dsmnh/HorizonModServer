package com.dsmnh.mobile.modserver;

import com.dsmnh.mobile.modserver.node.NodeClient;
import com.dsmnh.mobile.modserver.node.NodeJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * /dsmnhmobile 命令：节点注册（权限 2，控制台可用）、游戏内出码与队伍域代调用（玩家）。
 * 所有网络调用在后台线程执行，输出经主线程队列回投（企划书 02 主线程保护）。
 */
public final class NodeCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return "dsmnhmobile";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/dsmnhmobile register <码> [名称] | status | bind | recover"
                + " | team <create|leave|disband|info|list|invite|accept|decline|apply|approve|deny|kick|transfer|setrole|joinmode> …";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        // 命令执行记录：既是运维审计（企划书 03），也便于无人值守验证
        HorizonModServer.LOG.info("dsmnhmobile 命令：{}（来自 {}）",
                java.util.Arrays.toString(args), sender.getCommandSenderName());
        if (args.length == 0) {
            sender.addChatMessage(new ChatComponentText(
                    EnumChatFormatting.RED + MainThreadQueue.line(getCommandUsage(sender))));
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if ("register".equals(sub) || "status".equals(sub)) {
            if (!isPrivileged(sender)) {
                deny(sender);
                return;
            }
            if ("register".equals(sub)) {
                register(sender, args);
            } else {
                status(sender);
            }
            return;
        }
        if ("bind".equals(sub) || "recover".equals(sub)) {
            gameCode(sender, sub);
            return;
        }
        if ("team".equals(sub)) {
            team(sender, args);
            return;
        }
        sender.addChatMessage(new ChatComponentText(
                EnumChatFormatting.RED + MainThreadQueue.line(getCommandUsage(sender))));
    }

    // ---------- 节点注册与状态 ----------

    private void register(final ICommandSender sender, String[] args) {
        HorizonModServer.LOG.info("register 分支进入，参数个数 {}", args.length);
        if (args.length < 2) {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED
                    + MainThreadQueue.line("用法：/dsmnhmobile register <注册码> [名称]")));
            return;
        }
        final String code = args[1];
        final String name = args.length > 2 ? args[2] : "";
        async(sender, new NodeCall() {
            @Override
            public void run() throws Exception {
                final long nodeId = HorizonModServer.INSTANCE.register(code, name);
                reply(sender, "本子服已注册为节点 " + nodeId + "，连接已建立");
            }
        });
    }

    private void status(ICommandSender sender) {
        NodeClient node = HorizonModServer.INSTANCE.node();
        String text = node == null
                ? "未连接：尚无节点凭证，先 /dsmnhmobile register <码>"
                : "节点 " + node.nodeId() + (node.isAuthenticated() ? "：已连接后端" : "：连接中/重连中");
        HorizonModServer.LOG.info("{}", text);
        sender.addChatMessage(new ChatComponentText(MainThreadQueue.line(text)));
    }

    // ---------- 游戏内出码 ----------

    private void gameCode(final ICommandSender sender, final String type) {
        final EntityPlayer player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        async(sender, new NodeCall() {
            @Override
            public void run() throws Exception {
                String actor = player.getUniqueID().toString();
                JsonNode b = "bind".equals(type)
                        ? HorizonModServer.INSTANCE.api().bindCode(actor)
                        : HorizonModServer.INSTANCE.api().recoverCode(actor);
                queueChat(player, EnumChatFormatting.GREEN + "你的"
                        + ("bind".equals(type) ? "绑定" : "恢复") + "码（10 分钟内有效，仅一次）："
                        + EnumChatFormatting.YELLOW + b.path("code").asText());
            }
        });
    }

    // ---------- 队伍域 ----------

    private void team(final ICommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED
                    + MainThreadQueue.line("用法：/dsmnhmobile team <create|leave|disband|info|list"
                    + "|invite|accept|decline|apply|approve|deny|kick|transfer|setrole|joinmode> …")));
            return;
        }
        final String action = args[1].toLowerCase(Locale.ROOT);
        if ("list".equals(action)) {
            final EntityPlayer player = requirePlayer(sender);
            if (player == null) {
                return;
            }
            async(sender, new NodeCall() {
                @Override
                public void run() throws Exception {
                    JsonNode teams = HorizonModServer.INSTANCE.api()
                            .team(uuidOf(player).toString(), "team.list", NodeJson.object())
                            .path("teams");
                    StringBuilder sb = new StringBuilder("网络内队伍：");
                    for (JsonNode t : teams) {
                        sb.append(t.path("name").asText())
                                .append("（").append(t.path("teamId").asLong())
                                .append("，").append(t.path("memberCount").asLong()).append("人） ");
                    }
                    queueChat(player, sb.toString());
                }
            });
            return;
        }
        final EntityPlayer player = requirePlayer(sender);
        if (player == null) {
            return;
        }
        final ObjectNode p = NodeJson.object();
        if ("create".equals(action)) {
            if (args.length < 3) {
                usage(sender, "/dsmnhmobile team create <队名>");
                return;
            }
            p.put("name", args[2]);
        } else if ("info".equals(action)) {
            if (args.length >= 3) {
                p.put("teamId", Long.parseLong(args[2]));
            }
        } else if ("invite".equals(action) || "kick".equals(action) || "transfer".equals(action)) {
            if (args.length < 3) {
                usage(sender, "/dsmnhmobile team " + action + " <在线玩家名>");
                return;
            }
            UUID target = onlineUuid(args[2]);
            if (target == null) {
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED
                        + MainThreadQueue.line("找不到在线玩家：" + args[2]
                        + "（阶段 1 仅支持在线玩家）")));
                return;
            }
            p.put("target", target.toString());
        } else if ("accept".equals(action) || "decline".equals(action)) {
            if (args.length < 3) {
                usage(sender, "/dsmnhmobile team " + action + " <邀请ID>");
                return;
            }
            p.put("inviteId", Long.parseLong(args[2]));
        } else if ("apply".equals(action)) {
            if (args.length < 3) {
                usage(sender, "/dsmnhmobile team apply <队伍ID>");
                return;
            }
            p.put("teamId", Long.parseLong(args[2]));
        } else if ("approve".equals(action) || "deny".equals(action)) {
            if (args.length < 3) {
                usage(sender, "/dsmnhmobile team " + action + " <申请ID>");
                return;
            }
            p.put("applyId", Long.parseLong(args[2]));
        } else if ("setrole".equals(action)) {
            if (args.length < 4) {
                usage(sender, "/dsmnhmobile team setrole <在线玩家名> member|vice");
                return;
            }
            UUID target = onlineUuid(args[2]);
            if (target == null) {
                usage(sender, "/dsmnhmobile team setrole <在线玩家名> member|vice");
                return;
            }
            p.put("target", target.toString());
            p.put("role", args[3]);
        } else if ("joinmode".equals(action)) {
            if (args.length < 3) {
                usage(sender, "/dsmnhmobile team joinmode invite|open");
                return;
            }
            p.put("mode", args[2]);
        } else if (!("leave".equals(action) || "disband".equals(action))) {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED
                    + MainThreadQueue.line("未知子命令：team " + action)));
            return;
        }
        final String op = "team." + action;
        async(sender, new NodeCall() {
            @Override
            public void run() throws Exception {
                JsonNode b = HorizonModServer.INSTANCE.api()
                        .team(uuidOf(player).toString(), op, p);
                queueChat(player, "已执行 " + op + (b.isMissingNode() || b.size() == 0
                        ? "" : "：" + b.toString()));
            }
        });
    }

    // ---------- 辅助 ----------

    /**
     * console/RCON 视为特权（Crucible 的 RCON sender 调 canCommandSenderUseCommand 会异常吞没，
     * 不能用作判据）；游戏内玩家仍按权限 2 检查。命令方块不在 1.0 支持范围。
     */
    private boolean isPrivileged(ICommandSender sender) {
        return !(sender instanceof EntityPlayer)
                || sender.canCommandSenderUseCommand(2, getCommandName());
    }

    private interface NodeCall {
        void run() throws Exception;
    }

    /** 网络调用放后台线程；失败信息与输出都回主线程。 */
    private void async(final ICommandSender sender, final NodeCall call) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    call.run();
                } catch (NodeClient.NodeOpException e) {
                    reply(sender, EnumChatFormatting.RED + "后端拒绝：" + e.code);
                } catch (NodeClient.NodeAuthException e) {
                    reply(sender, EnumChatFormatting.RED + "连接问题：" + e.code);
                } catch (Exception e) {
                    reply(sender, EnumChatFormatting.RED + "请求失败：" + e.getMessage());
                }
            }
        }, "dsmnhmobile-cmd").start();
    }

    private void reply(final ICommandSender sender, final String text) {
        // 异步结果同时写日志：RCON 会话在命令同步返回后即关闭，仅回投聊天会丢失
        HorizonModServer.LOG.info("{}", text);
        HorizonModServer.INSTANCE.queue().enqueue(new Runnable() {
            @Override
            public void run() {
                sender.addChatMessage(new ChatComponentText(MainThreadQueue.line(text)));
            }
        });
    }

    private void queueChat(final EntityPlayer player, final String text) {
        HorizonModServer.INSTANCE.queue().enqueueChat(player, text);
    }

    private void usage(ICommandSender sender, String usage) {
        sender.addChatMessage(new ChatComponentText(
                EnumChatFormatting.RED + MainThreadQueue.line("用法：" + usage)));
    }

    private void deny(ICommandSender sender) {
        sender.addChatMessage(new ChatComponentText(
                EnumChatFormatting.RED + MainThreadQueue.line("权限不足")));
    }

    private EntityPlayer requirePlayer(ICommandSender sender) {
        if (sender instanceof EntityPlayer) {
            return (EntityPlayer) sender;
        }
        sender.addChatMessage(new ChatComponentText(
                EnumChatFormatting.RED + MainThreadQueue.line("该命令只能由游戏内玩家执行")));
        return null;
    }

    private UUID uuidOf(EntityPlayer player) {
        return player.getUniqueID();
    }

    /** 阶段 1 仅解析在线玩家（离线 UUID 查询留待后续版本）。 */
    private UUID onlineUuid(String name) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.getConfigurationManager() == null) {
            return null;
        }
        List<?> players = server.getConfigurationManager().playerEntityList;
        for (Object o : new ArrayList<Object>(players)) {
            EntityPlayer p = (EntityPlayer) o;
            if (p.getCommandSenderName().equalsIgnoreCase(name)) {
                return p.getUniqueID();
            }
        }
        return null;
    }

}
