package com.dsmnh.mobile.modserver;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 队伍来源模式（企划书 GTNH-2.9.1 §2）：按节点固定，禁止自动回退。
 * <ul>
 *   <li>{@code auto}：官方适配器可用时用 OFFICIAL，否则 SU（2.9.0 旧行为）。</li>
 *   <li>{@code official}：只用 GTNHLib 官方队伍；适配器缺失时链路 DISABLED，绝不回退 SU 写入。</li>
 *   <li>{@code su}：沿用 ServerUtilities 队伍（旧配置）。</li>
 * </ul>
 * 这里只做纯决策，便于在无游戏环境的单测中覆盖。
 */
public final class TeamMode {

    public enum Mode {
        /** GTNHLib 官方队伍：只读上报，拒绝 SU 写入。 */
        OFFICIAL,
        /** ServerUtilities 队伍：1.6.4 及以前的旧链路。 */
        SU,
        /** 队伍链路整体不可用（显式官方模式但适配器缺失或损坏）。 */
        DISABLED
    }

    /** 契约 1.6.5 §8.8 能力位。 */
    public static final String CAP_GTNH = "gtnh";

    private TeamMode() {
    }

    /**
     * 解析生效模式。
     *
     * @param configured 配置值（auto/official/su，大小写与空白不敏感；未知值按 auto）
     * @param adapterReady 官方适配器是否已就绪（GTNHLib 已加载且反射入口可用）
     */
    public static Mode resolve(String configured, boolean adapterReady) {
        String value = configured == null ? "" : configured.trim().toLowerCase(java.util.Locale.ROOT);
        if ("official".equals(value)) {
            return adapterReady ? Mode.OFFICIAL : Mode.DISABLED;
        }
        if ("su".equals(value)) {
            return Mode.SU;
        }
        return adapterReady ? Mode.OFFICIAL : Mode.SU;
    }

    /** HELLO.caps 声明：只有官方模式声明 gtnh，其余不声明（空数组=显式降级）。 */
    public static List<String> capabilities(Mode mode) {
        return mode == Mode.OFFICIAL
                ? Collections.unmodifiableList(Arrays.asList(CAP_GTNH))
                : Collections.<String>emptyList();
    }

    /** 官方模式下是否仍需向节点下发 SU 队伍写入（team.apply/eu.join）。 */
    public static boolean allowsSuTeamWrites(Mode mode) {
        return mode == Mode.SU;
    }

    public static String describe(Mode mode) {
        switch (mode) {
            case OFFICIAL:
                return "official（GTNHLib 只读投影）";
            case SU:
                return "su（ServerUtilities 队伍）";
            default:
                return "disabled（官方模式缺少 GTNHLib 适配器，队伍链路停用）";
        }
    }
}
