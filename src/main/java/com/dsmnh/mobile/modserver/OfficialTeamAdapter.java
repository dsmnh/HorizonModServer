package com.dsmnh.mobile.modserver;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * GTNHLib 官方队伍适配器（契约 1.6.5 §8.8）：只读采集，不写官方队伍。
 *
 * <p>与 {@link SuAdapter} 同源的反射接入方式，保证未安装 GTNHLib 时旧配置照常启动：
 * 类加载阶段不触碰任何 GTNH 类型，缺失依赖只让 {@link #available()} 为 false。
 * 采集必须在主线程执行（TeamManager 与 Team 集合非线程安全）；序列化与发送交给后台线程。
 */
public final class OfficialTeamAdapter {
    private static boolean checked;
    private static boolean available;
    private static Method mGetTeamMap;
    private static Method mGetTeamId;
    private static Method mGetTeamName;
    private static Method mGetOwners;
    private static Method mGetOfficers;
    private static Method mGetMembers;

    private OfficialTeamAdapter() {
    }

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
            mGetTeamMap = manager.getMethod("getTeamMap");
            mGetTeamId = team.getMethod("getTeamId");
            mGetTeamName = team.getMethod("getTeamName");
            mGetOwners = team.getMethod("getOwners");
            mGetOfficers = team.getMethod("getOfficers");
            mGetMembers = team.getMethod("getMembers");
            available = true;
            HorizonModServer.LOG.info("已接上 GTNHLib 官方队伍（只读投影）");
        } catch (Throwable t) {
            available = false;
            HorizonModServer.LOG.info("未发现 GTNHLib 官方队伍，官方投影停用");
        }
    }

    /** 主线程哨兵：官方队伍集合只能在服务器主线程读取。 */
    public static boolean onMainThread() {
        MinecraftServer server = MinecraftServer.getServer();
        return server != null && server.isServerRunning();
    }

    /**
     * 采集本世界全部官方队伍（主线程）。返回纯数据快照，不含 gen/seq。
     * 不调用 TeamManager.getTeamByPlayer：缓存未命中时会打 error 日志。
     */
    public static List<OfficialTeamSnapshot.Team> collect() {
        List<OfficialTeamSnapshot.Team> out = new ArrayList<OfficialTeamSnapshot.Team>();
        if (!available()) {
            return out;
        }
        try {
            Object map = mGetTeamMap.invoke(null);
            if (!(map instanceof Map)) {
                return out;
            }
            for (Object team : ((Map<?, ?>) map).values()) {
                if (team == null) {
                    continue;
                }
                String id = String.valueOf(mGetTeamId.invoke(team));
                String name = String.valueOf(mGetTeamName.invoke(team));
                out.add(new OfficialTeamSnapshot.Team(id, name,
                        ids(mGetOwners.invoke(team)), ids(mGetOfficers.invoke(team)),
                        ids(mGetMembers.invoke(team))));
            }
        } catch (Throwable t) {
            HorizonModServer.LOG.warn("官方队伍采集失败：{}", t.toString());
            return new ArrayList<OfficialTeamSnapshot.Team>();
        }
        return out;
    }

    private static List<String> ids(Object set) {
        List<String> out = new ArrayList<String>();
        if (set instanceof Collection) {
            for (Object o : (Collection<?>) set) {
                if (o instanceof UUID) {
                    out.add(o.toString());
                }
            }
        }
        return out;
    }

    /**
     * 世界标识：世界目录名 + 世界种子。同一存档跨重启保持稳定，
     * 换图或恢复他人备份时必然变化，避免后端把两份世界当成同一份投影。
     */
    public static String worldRef(MinecraftServer server) {
        if (server == null) {
            return "";
        }
        try {
            WorldServer overworld = server.worldServerForDimension(0);
            if (overworld == null) {
                return "";
            }
            String dir = overworld.getSaveHandler().getWorldDirectory().getName();
            long seed = overworld.getSeed();
            return "w:" + dir + ":" + Long.toHexString(seed);
        } catch (Throwable t) {
            return "";
        }
    }
}
