package com.dsmnh.mobile.modserver;

/**
 * 官方队伍受控写入的判定逻辑（契约 1.6.6 §8.9）。纯函数，不依赖游戏类，
 * 供 {@link OfficialTeamWriter} 与单测共用：节点只做与后端一致的前置判定，
 * 真正的名单变更一律交给 GTNHLib {@code TeamActions}。
 */
public final class OfficialWriteGuard {

    /** 目标玩家不在线：TeamActions 的动作接收 EntityPlayer，不得离线代执行。 */
    public static final String PLAYER_OFFLINE = "PLAYER_OFFLINE";
    /** 发起者在官方队伍中不是 owners/officers。 */
    public static final String NOT_OFFICER = "NOT_OFFICER";
    /** 节点当前世界代次与后端下发的 expectGen 不一致。 */
    public static final String STALE_WORLD = "STALE_WORLD";
    /** 动作未知，或本节点未启用官方队伍模式。 */
    public static final String UNSUPPORTED = "UNSUPPORTED";

    public static final String ACTION_INVITE = "invite";
    public static final String ACTION_ACCEPT = "accept";

    private OfficialWriteGuard() {
    }

    /** 只接受契约登记的两个动作。 */
    public static boolean knownAction(String action) {
        return ACTION_INVITE.equals(action) || ACTION_ACCEPT.equals(action);
    }

    /**
     * 前置判定，返回 null 表示可执行。
     * 代次先行：世界已更换或重载时，投影里的成员与角色都不再可信。
     * 角色要求只对邀请成立——接受入队的人本身就是普通玩家，GTNHLib 只校验是否存在待接受邀请。
     */
    public static String check(String action, boolean officialMode, long expectGen, long currentGen,
            boolean targetOnline, boolean actorOfficer) {
        if (!officialMode || !knownAction(action)) {
            return UNSUPPORTED;
        }
        if (expectGen != currentGen) {
            return STALE_WORLD;
        }
        if (!targetOnline) {
            return PLAYER_OFFLINE;
        }
        if (ACTION_INVITE.equals(action) && !actorOfficer) {
            return NOT_OFFICER;
        }
        return null;
    }

    /** 等待玩家上下线的最长轮询时间（毫秒）：写入只处理在线玩家，不排队等待。 */
    public static final long DEFAULT_TIMEOUT_MS = 0L;
}
