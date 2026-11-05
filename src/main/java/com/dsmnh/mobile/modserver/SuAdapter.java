package com.dsmnh.mobile.modserver;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ServerUtilities 只读适配器（反射）：按在线玩家聚合本服 SU 队伍结构，
 * 供 team.report 上报（契约 1.3.0）。移植自 AEMobileChat TeamInfo 的已验证反射链：
 * Universe.getNullable → getPlayer(sender) → hasTeam/team → getTitle/getHighestStatus。
 * 没装 ServerUtilities 时整体停用（available=false）。
 */
public final class SuAdapter {
    private static boolean checked;
    private static boolean available;
    private static Method mGetUniverse;
    private static Method mGetPlayer;
    private static Method mHasTeam;
    private static Field fTeam;
    private static Method mGetTitle;
    private static Method mGetStatus;
    private static Method mStatusName;

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
            Class<?> status = Class.forName("serverutils.lib.EnumTeamStatus");
            mGetUniverse = universe.getMethod("getNullable");
            mGetPlayer = universe.getMethod("getPlayer", net.minecraft.command.ICommandSender.class);
            mHasTeam = forgePlayer.getMethod("hasTeam");
            fTeam = forgePlayer.getField("team");
            mGetTitle = forgeTeam.getMethod("getTitle");
            mGetStatus = forgeTeam.getMethod("getHighestStatus", forgePlayer);
            mStatusName = status.getMethod("name");
            available = true;
            HorizonModServer.LOG.info("已接上 ServerUtilities 团队（只读）");
        } catch (Throwable t) {
            available = false;
            HorizonModServer.LOG.info("未发现 ServerUtilities，队伍同步上报停用");
        }
    }

    /** 聚合本服队伍：按在线玩家的 SU 团队归并（单人队无名时用简介后备，同 AEMobileChat）。 */
    public static ObjectNode aggregateTeams(MinecraftServer server) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        ArrayNode teams = out.putArray("teams");
        if (!available()) {
            return out;
        }
        Map<String, ObjectNode> byName = new LinkedHashMap<>();
        for (EntityPlayerMP player : onlinePlayers(server)) {
            try {
                Object universe = mGetUniverse.invoke(null);
                if (universe == null) return out;
                Object fp = mGetPlayer.invoke(universe, player);
                if (fp == null) continue;
                if (!Boolean.TRUE.equals(mHasTeam.invoke(fp))) continue;
                Object team = fTeam.get(fp);
                if (team == null) continue;
                String name = plain(mGetTitle.invoke(team));
                if (name.isEmpty()) continue;
                String role = "member";
                try {
                    Object status = mGetStatus.invoke(team, fp);
                    if (status != null) {
                        String n = String.valueOf(mStatusName.invoke(status));
                        if ("OWNER".equals(n)) role = "leader";
                        else if ("MOD".equals(n)) role = "vice";
                    }
                } catch (Throwable ignored) {
                    // 角色取不到按普通成员
                }
                ObjectNode node = byName.get(name);
                if (node == null) {
                    node = JsonNodeFactory.instance.objectNode();
                    node.put("name", name);
                    node.putArray("members");
                    node.putArray("allies");
                    node.putArray("enemies");
                    byName.put(name, node);
                }
                ObjectNode member = ((ArrayNode) node.get("members")).addObject();
                member.put("uuid", player.getUniqueID().toString());
                member.put("role", role);
            } catch (Throwable ignored) {
                // 单个玩家读取失败不阻断聚合
            }
        }
        for (ObjectNode node : byName.values()) {
            teams.add(node);
        }
        return out;
    }

    private static List<EntityPlayerMP> onlinePlayers(MinecraftServer server) {
        List<EntityPlayerMP> players = new ArrayList<EntityPlayerMP>();
        if (server != null && server.getConfigurationManager() != null
                && server.getConfigurationManager().playerEntityList != null) {
            for (Object o : new ArrayList<Object>(server.getConfigurationManager().playerEntityList)) {
                players.add((EntityPlayerMP) o);
            }
        }
        return players;
    }

    /** IChatComponent → 纯文本；SRG 名 func_150260_c 必须一起试（AEMobileChat 已验证的坑）。 */
    private static String plain(Object component) {
        if (component == null) return "";
        if (component instanceof String) return clean((String) component);
        for (String name : new String[] {"getUnformattedText", "func_150260_c"}) {
            try {
                Method m = component.getClass().getMethod(name);
                Object v = m.invoke(component);
                if (v != null) return clean(String.valueOf(v));
            } catch (Throwable ignored) {
                // 换下一个名字
            }
        }
        return "";
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace("&", "").replace("\u00a7", "").trim();
    }
}
