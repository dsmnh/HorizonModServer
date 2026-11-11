package com.dsmnh.mobile.modserver;

/** eu.join 命令模板（契约 1.3.0 §8.2）：GTNH 官方命令两参数均为玩家名。 */
public final class EuJoinTemplate {
    public static final String DEFAULT = "/gt global_energy_join {player} {team}";

    private EuJoinTemplate() {
    }

    /** 替换 {player}/{team}；空模板回退默认值，替换值清除控制字符并限长。 */
    public static String render(String template, String player, String team) {
        String base = template == null || template.isEmpty() ? DEFAULT : template;
        return base.replace("{player}", sanitize(player))
                .replace("{team}", sanitize(team));
    }

    /** 命令参数不得含换行与控制字符，避免经命令通道注入。 */
    private static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String clean = value.replaceAll("[\\p{Cntrl}]", "").trim();
        return clean.length() > 64 ? clean.substring(0, 64) : clean;
    }
}
