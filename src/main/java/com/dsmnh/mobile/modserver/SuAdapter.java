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
    private static Method mRemoveTeam;
    private static Method mGetTeamByName;
    private static Method mGetUuid;
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
            // 1.6.3 补：中央解散/踢人后要能反向清理本服 SU 队伍与成员（契约 §8.2「解散均由适配器按差异执行」）
            mRemoveTeam = universe.getMethod("removeTeam", forgeTeam);
            mGetTeamByName = universe.getMethod("getTeam", String.class);
            mGetUuid = forgePlayer.getMethod("getId");
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
     * rankings.report 数据源（契约 1.5.0 §8.6）：读取 SU Leaderboard 数据。
     * 反射 ServerUtilitiesLeaderboards.LEADERBOARDS（static Map），
     * 对每个 Leaderboard 遍历 Universe.getPlayers() 调 createValue。
     * 主线程调用（SU 非线程安全）。
     */
    public static ObjectNode rankingsData() {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        ArrayNode boards = out.putArray("boards");
        if (!available()) return out;
        try {
            Class<?> lbClass = Class.forName("serverutils.ServerUtilitiesLeaderboards");
            Field lbField = lbClass.getField("LEADERBOARDS");
            Map<?, ?> leaderboards = (Map<?, ?>) lbField.get(null);
            Object u = mGetUniverse.invoke(null);
            if (u == null) return out;
            Method getPlayers = u.getClass().getMethod("getPlayers");
            // SU 的 getPlayers() 返回 HashMap$Values（Collection），不能强转 List
            Iterable<?> allPlayers = (Iterable<?>) getPlayers.invoke(u);
            for (Map.Entry<?, ?> entry : leaderboards.entrySet()) {
                Object lb = entry.getValue();
                // Leaderboard 不是 ForgeTeam 的子类，标题要用它自己的 getTitle
                String lbName = plain(lb.getClass().getMethod("getTitle").invoke(lb));
                ObjectNode board = boards.addObject();
                board.put("id", entry.getKey().toString());
                board.put("name", lbName);
                ArrayNode rows = board.putArray("rows");
                // createValue 形参与具体榜单类无关，循环外解析一次即可
                Method createValue = lb.getClass().getMethod("createValue",
                        Class.forName("serverutils.lib.data.ForgePlayer"));
                for (Object fpObj : allPlayers) {
                    try {
                        Object value = createValue.invoke(lb, fpObj);
                        String text = plain(value);
                        String uuid = String.valueOf(
                                fpObj.getClass().getMethod("getId").invoke(fpObj));
                        ObjectNode r = rows.addObject();
                        r.put("uuid", uuid);
                        r.put("value", text);
                    } catch (Throwable ignored) {
                        // 单个玩家读取失败不阻断
                    }
                }
            }
        } catch (Throwable t) {
            HorizonModServer.LOG.warn("rankings 数据读取失败：{}", t.toString());
        }
        return out;
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
            // 中央已无该队伍（解散）：载荷 members 为空数组。必须删掉本服 SU 队伍，
            // 否则本服残留的组会一直显示，且下次对账还会把它当成合法队伍。
            if (team.path("members").size() == 0) {
                if (existing == null) {
                    return null;
                }
                mRemoveTeam.invoke(u, existing);
                markDirty(u);
                HorizonModServer.LOG.info("队伍「{}」已按中央状态从本服 SU 删除", name);
                return null;
            }
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

            java.util.Set<String> centralUuids = new java.util.HashSet<String>();
            for (JsonNode member : team.path("members")) {
                centralUuids.add(member.path("uuid").asText());
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
            // 差异移除：SU 里还挂着该队伍、但中央 members 已无此人（被踢/主动退出/转队）。
            // isMember 由 ForgePlayer.team 决定，所以必须把 team 字段换回空队，光改 players 不够。
            Object noneTeam = mGetTeamByName.invoke(u, "");
            Object owner = mGetOwner.invoke(teamObj);
            int removed = 0;
            Object playersMap = fUniversePlayers.get(u);
            if (playersMap instanceof Map) {
                for (Object fpObj : ((Map<?, ?>) playersMap).values()) {
                    Object currentTeam = fPlayerTeam.get(fpObj);
                    if (currentTeam == null || !currentTeam.equals(teamObj)) {
                        continue;
                    }
                    if (fpObj.equals(owner)) {
                        continue;
                    }
                    String uuid = String.valueOf(mGetUuid.invoke(fpObj));
                    if (centralUuids.contains(uuid)) {
                        continue;
                    }
                    fPlayerTeam.set(fpObj, noneTeam);
                    Object none = Class.forName("serverutils.lib.EnumTeamStatus")
                            .getField("NONE").get(null);
                    mSetStatus.invoke(teamObj, fpObj, none);
                    removed += 1;
                }
            }
            if (removed > 0) {
                HorizonModServer.LOG.info("队伍「{}」已按中央状态移除 {} 名成员", name, removed);
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
