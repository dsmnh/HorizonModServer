package com.dsmnh.mobile.modserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.minecraft.server.MinecraftServer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ServerUtilities 适配器（契约 1.3.0/1.5.0）。
 *
 * <p>反射链与 AEMobileChat TeamInfo 同源（Universe.getNullable → getPlayer → hasTeam/team）。
 * 写路径（team.apply）：只做「在线玩家」的成员关系写入与新队伍创建（universe.addTeam 是公开 API）；
 * 离线成员跳过并上报，等待后续对账——避免向 SU 持久化注入未经验证的离线玩家对象。
 * 全部操作在主线程执行（SU 非线程安全）。
 */
public final class SuAdapter {
    private static boolean checked;
    private static boolean available;
    private static Object universe;
    private static Method mGetUniverse;
    private static Method mGetPlayerBySender;
    private static Method mGetPlayerByUuid;
    private static Method mGetTeams;
    private static Method mAddTeam;
    private static Method mGenerateTeamUid;
    private static Method mGetTitle;
    private static Method mSetTitle;
    private static Method mSetStatus;
    private static Method mGetOwner;
    private static Method mGetMembers;
    private static Method mGetName;
    private static Method mMarkDirty;
    private static Field fPlayerTeam;
    private static Field fUniversePlayers;

    private SuAdapter() {}

    public static synchronized boolean available() {
        check();
        return available;
    }

    private static synchronized void check() {
        if (checked) return;
        checked = true;
        try {
            Class<?> universe = Class.forName("serverutils.lib.data.Universe");
            Class<?> forgePlayer = Class.forName("serverutils.lib.data.ForgePlayer");
            Class<?> forgeTeam = Class.forName("serverutils.lib.data.ForgeTeam");
            Class<?> teamType = Class.forName("serverutils.lib.data.TeamType");
            Class<?> status = Class.forName("serverutils.lib.EnumTeamStatus");

            mGetUniverse = universe.getMethod("getNullable");
            mGetPlayerBySender = universe.getMethod("getPlayer", net.minecraft.command.ICommandSender.class);
            mGetPlayerByUuid = universe.getMethod("getPlayer", UUID.class);
            mGetTeams = universe.getMethod("getTeams");
            mAddTeam = universe.getMethod("addTeam", forgeTeam);
            mGenerateTeamUid = universe.getMethod("generateTeamUID", short.class);
            fUniversePlayers = universe.getField("players");
            fPlayerTeam = forgePlayer.getField("team");
            mGetTitle = forgeTeam.getMethod("getTitle");
            mSetTitle = forgeTeam.getMethod("setTitle", String.class);
            mSetStatus = forgeTeam.getMethod("setStatus", forgePlayer, status);
            mGetOwner = forgeTeam.getMethod("getOwner");
            mGetMembers = forgeTeam.getMethod("getMembers");
            mGetName = forgePlayer.getMethod("getName");
            available = true;
            HorizonModServer.LOG.info("已接上 ServerUtilities（读+受限写）");
        } catch (Throwable t) {
            available = false;
            HorizonModServer.LOG.info("未发现 ServerUtilities，队伍同步停用");
        }
    }

    /** 主线程哨兵：SU 操作必须在服务器主线程。 */
    public static boolean onMainThread() {
        return net.minecraft.server.MinecraftServer.getServer().isServerStopped()
                ? false
                : net.minecraft.server.MinecraftServer.getServer().isServerRunning();
    }

    /** 聚合本服 SU 队伍全量（team.report 载荷）。只读。 */
    public static ObjectNode aggregateTeams(MinecraftServer server) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        ArrayNode teams = out.putArray("teams");
        if (!available()) {
            return out;
        }
        try {
            Object u = mGetUniverse.invoke(null);
            if (u == null) return out;
            Object playersMap = fUniversePlayers.get(u);
            if (!(playersMap instanceof Map)) return out;
            Map<?, ?> players = (Map<?, ?>) playersMap;
            Map<String, ObjectNode> byId = new java.util.LinkedHashMap<>();
            for (Object fpObj : players.values()) {
                Object team = fPlayerTeam.get(fpObj);
                if (team == null) continue;
                String teamName = plain(mGetTitle.invoke(team));
                if (teamName.isEmpty()) continue;
                String teamId = String.valueOf(team.hashCode());
                ObjectNode node = byId.get(teamId);
                if (node == null) {
                    node = byId.computeIfAbsent(teamId, k -> JsonNodeFactory.instance.objectNode());
                    node.put("name", teamName);
                    node.putArray("members");
                    node.putArray("allies");
                    node.putArray("enemies");
                }
                ObjectNode member = ((ArrayNode) node.get("members")).addObject();
                member.put("uuid", String.valueOf(mGetName == null
                        ? fpObj.toString() : invokeName(fpObj)));
                member.put("role", roleOf(team, fpObj));
            }
            for (ObjectNode node : byId.values()) {
                teams.add(node);
            }
        } catch (Throwable t) {
            HorizonModServer.LOG.warn("聚合 SU 队伍失败：{}", t.toString());
        }
        return out;
    }

    private static String invokeName(Object fpObj) throws Exception {
        if (mGetName != null) {
            return String.valueOf(mGetName.invoke(fpObj));
        }
        return fpObj.toString();
    }

    private static String roleOf(Object team, Object fpObj) {
        try {
            Object owner = mGetOwner.invoke(team);
            if (fpObj.equals(owner)) return "leader";
            List<?> members = (List<?>) mGetMembers.invoke(team);
            for (Object m : members) {
                if (m.equals(fpObj)) return "member";
            }
        } catch (Throwable ignored) {
        }
        return "member";
    }

    /**
     * team.apply 执行（主线程调用）：按中央结构写入 SU。
     * 返回 null=成功；否则失败原因（离线成员跳过时返回 "offline members skipped: n"）。
     */
    public static String applyTeam(JsonNode team, JsonNode relations) {
        if (!available()) {
            return "ServerUtilities not installed";
        }
        try {
            Object u = mGetUniverse.invoke(null);
            if (u == null) return "universe unavailable";
            String name = team.path("name").asText("");
            if (name.isEmpty()) return "team name empty";

            Object existing = findTeamByName(u, name);
            Object teamObj;
            if (existing != null) {
                teamObj = existing;
            } else {
                short uid = (Short) mGenerateTeamUid.invoke(u, (short) 0);
                Class<?> teamType = Class.forName("serverutils.lib.data.TeamType");
                Object playerType = null;
                for (Object constant : teamType.getEnumConstants()) {
                    if ("PLAYER".equals(String.valueOf(constant))) {
                        playerType = constant;
                    }
                }
                teamObj = Class.forName("serverutils.lib.data.ForgeTeam")
                        .getConstructor(u.getClass(), short.class, String.class, teamType)
                        .newInstance(u, uid, name, playerType);
                mSetTitle.invoke(teamObj, name);
                mAddTeam.invoke(u, teamObj);
            }

            int offline = 0;
            for (JsonNode member : team.path("members")) {
                String uuid = member.path("uuid").asText();
                String role = member.path("role").asText("member");
                Object fp = mGetPlayerByUuid.invoke(u, java.util.UUID.fromString(uuid));
                if (fp == null) {
                    offline += 1;
                    continue;
                }
                fPlayerTeam.set(fp, teamObj);
                Object status = Class.forName("serverutils.lib.EnumTeamStatus")
                        .getMethod("valueOf", String.class)
                        .invoke(null, "leader".equals(role) ? "OWNER" : "vice".equals(role) ? "MOD" : "MEMBER");
                mSetStatus.invoke(teamObj, fp, status);
            }
            if (offline > 0) {
                return "offline members skipped: " + offline;
            }
            markDirty(u);
            return null;
        } catch (Throwable t) {
            return "apply error: " + t;
        }
    }

    private static Object findTeamByName(Object universe, String name) throws Exception {
        for (Object team : (Iterable<?>) mGetTeams.invoke(universe)) {
            String title = plain(mGetTitle.invoke(team));
            if (name.equals(title)) {
                return team;
            }
        }
        return null;
    }

    private static void markDirty(Object universe) {
        try {
            if (mMarkDirty == null) {
                mMarkDirty = universe.getClass().getMethod("markDirty");
            }
            mMarkDirty.invoke(universe);
        } catch (Throwable ignored) {
        }
    }

    private static String plain(Object component) {
        if (component == null) return "";
        if (component instanceof String) return clean((String) component);
        for (String name : new String[] {"getUnformattedText", "func_150260_c"}) {
            try {
                Method m = component.getClass().getMethod(name);
                Object v = m.invoke(component);
                if (v != null) return clean(String.valueOf(v));
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace("&", "").replace("\u00a7", "").trim();
    }
}
