package com.dsmnh.mobile.modserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * 官方队伍受控写入执行器（契约 1.6.6 §8.9）：在主线程调用 GTNHLib {@code TeamActions}，
 * 不直接改写 {@code Team} 名单，也不自行发送合队事件。
 *
 * <p>与只读适配器一样用反射接入，未安装 GTNHLib 时本类不参与任何流程。
 * 所有动作接收 {@code EntityPlayer}，因此只处理在线玩家，离线一律回报 {@code PLAYER_OFFLINE}。
 */
public final class OfficialTeamWriter {

    /** 执行结果：error 为 null 表示成功，merged 仅在发生合队时非空。 */
    public static final class Result {
        public final String error;
        public final ObjectNode merged;

        Result(String error, ObjectNode merged) {
            this.error = error;
            this.merged = merged;
        }
    }

    private static boolean checked;
    private static boolean available;
    private static Method mGetTeamMap;
    private static Method mOnInvite;
    private static Method mOnAccept;
    private static Method mTeamId;
    private static Method mTeamMembers;
    private static Method mTeamOfficers;
    private static Method mTeamOwners;

    private OfficialTeamWriter() {
    }

    /** 是否具备调用官方写入动作的条件（GTNHLib TeamActions 可解析）。 */
    public static synchronized boolean available() {
        check();
        return available;
    }

    private static synchronized void check() {
        if (checked) {
            return;
        }
        checked = true;
        try {
            Class<?> manager = Class.forName("com.gtnewhorizon.gtnhlib.teams.TeamManager");
            Class<?> team = Class.forName("com.gtnewhorizon.gtnhlib.teams.Team");
            Class<?> actions = Class.forName("com.gtnewhorizon.gtnhlib.teams.TeamActions");
            mGetTeamMap = manager.getMethod("getTeamMap");
            mTeamId = team.getMethod("getTeamId");
            mTeamMembers = team.getMethod("getMembers");
            mTeamOfficers = team.getMethod("getOfficers");
            mTeamOwners = team.getMethod("getOwners");
            mOnInvite = actions.getMethod("onInvite", team, EntityPlayer.class, EntityPlayer.class);
            mOnAccept = actions.getMethod("onAccept", team, EntityPlayer.class);
            available = true;
        } catch (Throwable t) {
            available = false;
            HorizonModServer.LOG.info("官方队伍写入动作不可用（GTNHLib TeamActions 缺失）：{}", t.toString());
        }
    }

    /**
     * 执行一次官方写入（主线程）。调用方负责先经 {@link OfficialWriteGuard} 判定；
     * 本方法再取一次实体，避免判定与执行之间玩家离线。
     */
    public static Result execute(String action, String officialTeamId, String actorUuid, String targetUuid) {
        if (!available()) {
            return new Result(OfficialWriteGuard.UNSUPPORTED, null);
        }
        try {
            Object team = findTeam(officialTeamId);
            if (team == null) {
                return new Result(OfficialWriteGuard.STALE_WORLD, null);
            }
            EntityPlayer actor = findPlayer(actorUuid);
            if (actor == null) {
                return new Result(OfficialWriteGuard.PLAYER_OFFLINE, null);
            }
            if (OfficialWriteGuard.ACTION_INVITE.equals(action)) {
                EntityPlayer target = findPlayer(targetUuid);
                if (target == null) {
                    return new Result(OfficialWriteGuard.PLAYER_OFFLINE, null);
                }
                if (!isOfficer(team, actorUuid)) {
                    return new Result(OfficialWriteGuard.NOT_OFFICER, null);
                }
                mOnInvite.invoke(null, team, actor, target);
                return new Result(null, null);
            }
            if (OfficialWriteGuard.ACTION_ACCEPT.equals(action)) {
                ObjectNode merged = null;
                Object old = findTeamOf(actorUuid);
                if (old != null && !officialTeamId.equals(String.valueOf(mTeamId.invoke(old)))) {
                    Collection<?> members = (Collection<?>) mTeamMembers.invoke(old);
                    if (members != null && members.size() == 1) {
                        // TeamActions.onAccept 在原队只剩本人时执行 mergeTeams：把该队任务并入存续队。
                        merged = com.dsmnh.mobile.modserver.node.NodeJson.object();
                        merged.put("consumed", String.valueOf(mTeamId.invoke(old)));
                        merged.put("surviving", officialTeamId);
                        merged.put("memberCount", members.size());
                    }
                }
                mOnAccept.invoke(null, team, actor);
                return new Result(null, merged);
            }
            return new Result(OfficialWriteGuard.UNSUPPORTED, null);
        } catch (Throwable t) {
            HorizonModServer.LOG.warn("官方写入执行失败（action={}）：{}", action, t.toString());
            return new Result(OfficialWriteGuard.UNSUPPORTED, null);
        }
    }

    private static Object findTeam(String officialTeamId) throws Exception {
        Object map = mGetTeamMap.invoke(null);
        if (!(map instanceof Map)) {
            return null;
        }
        for (Object team : ((Map<?, ?>) map).values()) {
            if (team != null && officialTeamId.equals(String.valueOf(mTeamId.invoke(team)))) {
                return team;
            }
        }
        return null;
    }

    private static Object findTeamOf(String playerUuid) throws Exception {
        UUID id = UUID.fromString(playerUuid);
        Object map = mGetTeamMap.invoke(null);
        if (!(map instanceof Map)) {
            return null;
        }
        for (Object team : ((Map<?, ?>) map).values()) {
            if (team == null) {
                continue;
            }
            Collection<?> members = (Collection<?>) mTeamMembers.invoke(team);
            if (members != null && members.contains(id)) {
                return team;
            }
        }
        return null;
    }

    private static boolean isOfficer(Object team, String uuid) throws Exception {
        UUID id = UUID.fromString(uuid);
        for (Method m : new Method[]{mTeamOwners, mTeamOfficers}) {
            Collection<?> set = (Collection<?>) m.invoke(team);
            if (set != null && set.contains(id)) {
                return true;
            }
        }
        return false;
    }

    /** 按 UUID 找在线玩家；离线或参数非法返回 null（官方动作不得代执行）。 */
    public static EntityPlayer findPlayer(String uuid) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.getConfigurationManager() == null || uuid == null || uuid.isEmpty()) {
            return null;
        }
        UUID id;
        try {
            id = UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            return null;
        }
        for (Object o : new ArrayList<Object>(server.getConfigurationManager().playerEntityList)) {
            EntityPlayer player = (EntityPlayer) o;
            if (id.equals(player.getUniqueID())) {
                return player;
            }
        }
        return null;
    }

    /** 下行载荷的动作名；非法值由 {@link OfficialWriteGuard#knownAction} 拦截。 */
    public static String actionOf(JsonNode p) {
        return p.path("action").asText("");
    }
}
