package com.dsmnh.mobile.modserver;

import com.fasterxml.jackson.databind.JsonNode;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 群组榜单写入 vanilla scoreboard 侧栏（清单 §9 默认口径，待人类复核）。
 * SU 排行榜只读无写 API，故按 board 的 id/name/rows 建立侧栏 objective。
 * 全部方法在主线程调用（Scoreboard 非线程安全）。
 */
public final class RankingsAdapter {
    private static final Logger LOG = LogManager.getLogger("dsmnhmobile");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+");
    private static final int MAX_NAME = 16;

    private RankingsAdapter() {
    }

    /** 写入完整 boards 快照；返回 null 表示成功，否则为失败原因。 */
    public static String applyBoards(JsonNode boards) {
        if (boards == null || !boards.isArray()) {
            return "boards 缺失或不是数组";
        }
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.worldServers == null || server.worldServers.length == 0) {
            return "server not ready";
        }
        try {
            Class<?> sbType = Class.forName("net.minecraft.scoreboard.Scoreboard");
            Class<?> objType = Class.forName("net.minecraft.scoreboard.ScoreObjective");
            Class<?> scoreType = Class.forName("net.minecraft.scoreboard.Score");
            Class<?> criteriaType = Class.forName("net.minecraft.scoreboard.IScoreObjectiveCriteria");
            Object scoreboard = noArgReturning(server.worldServers[0], sbType);
            Object dummy = dummyCriteria(criteriaType);
            if (scoreboard == null || dummy == null) {
                return "scoreboard 或 dummy criteria 不可用";
            }
            Method getObjective = orNamed(bySignature(sbType, new Class<?>[]{String.class}, objType),
                    sbType, new Class<?>[]{String.class}, "getObjective", "func_96518_b");
            Method addObjective = orNamed(bySignature(sbType, new Class<?>[]{String.class, criteriaType}, objType),
                    sbType, new Class<?>[]{String.class, criteriaType}, "addObjective", "func_96535_a");
            Method setDisplay = orNamed(bySignature(sbType, new Class<?>[]{int.class, objType}, void.class),
                    sbType, new Class<?>[]{int.class, objType}, "setObjectiveInDisplaySlot", "func_96530_a");
            Method getValue = orNamed(bySignature(sbType, new Class<?>[]{String.class, objType}, scoreType),
                    sbType, new Class<?>[]{String.class, objType}, "getValueFromObjective", "func_96529_a");
            Method setPoints = scoreSetter(scoreType);
            Method setDisplayName = orNamed(bySignature(objType, new Class<?>[]{String.class}, void.class),
                    objType, new Class<?>[]{String.class}, "setDisplayName", "func_96681_a");
            StringBuilder missing = new StringBuilder();
            appendMissing(missing, "getObjective", getObjective);
            appendMissing(missing, "addObjective", addObjective);
            appendMissing(missing, "setDisplay", setDisplay);
            appendMissing(missing, "getValue", getValue);
            appendMissing(missing, "setPoints", setPoints);
            if (missing.length() > 0) {
                // 反射查找失败时列出具体缺失项，便于按版本补充候选名
                return "scoreboard 方法签名未匹配：" + missing;
            }
            Map<String, String> names = onlineNames(server);
            boolean first = true;
            for (JsonNode board : boards) {
                String objName = objectiveName(board.path("id").asText(""));
                Object objective = getObjective.invoke(scoreboard, objName);
                if (objective == null) {
                    objective = addObjective.invoke(scoreboard, objName, dummy);
                }
                if (objective == null) {
                    continue;
                }
                String display = board.path("name").asText("");
                if (setDisplayName != null && !display.isEmpty()) {
                    try {
                        setDisplayName.invoke(objective, truncate(display, 32));
                    } catch (Throwable ignored) {
                        // 显示名写入失败不影响分数
                    }
                }
                for (JsonNode row : board.path("rows")) {
                    String uuid = row.path("uuid").asText("");
                    if (uuid.isEmpty()) {
                        continue;
                    }
                    Object score = getValue.invoke(scoreboard, entryName(uuid, names), objective);
                    if (score != null) {
                        setPoints.invoke(score, parseScore(row.path("value").asText("")));
                    }
                }
                if (first) {
                    // 侧栏槽位 1 只显示首个榜单，其余榜单保留 objective 供查阅
                    setDisplay.invoke(scoreboard, 1, objective);
                    first = false;
                }
            }
            return null;
        } catch (Throwable t) {
            LOG.warn("rankings.apply 写入 scoreboard 失败：{}", t.toString());
            return "apply error: " + t;
        }
    }

    /** objective 名长度受限，清洗非字母数字并截断。 */
    public static String objectiveName(String id) {
        String clean = id == null ? "" : id.replaceAll("[^A-Za-z0-9_.-]", "_");
        return truncate(clean.isEmpty() ? "horizon_rank" : clean, MAX_NAME);
    }

    /** SU 榜单值是展示文本，取首个整数作为分数。 */
    public static int parseScore(String value) {
        Matcher m = NUMBER.matcher(value == null ? "" : value);
        if (!m.find()) {
            return 0;
        }
        try {
            long v = Long.parseLong(m.group());
            if (v > Integer.MAX_VALUE) return Integer.MAX_VALUE;
            if (v < Integer.MIN_VALUE) return Integer.MIN_VALUE;
            return (int) v;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 侧栏条目优先用在线玩家名，否则用去除连字符的 UUID。 */
    public static String entryName(String uuid, Map<String, String> names) {
        String name = names == null ? null : names.get(uuid);
        return truncate(name == null || name.isEmpty() ? uuid.replace("-", "") : name, MAX_NAME);
    }

    private static Map<String, String> onlineNames(MinecraftServer server) {
        Map<String, String> out = new HashMap<String, String>();
        if (server.getConfigurationManager() == null) {
            return out;
        }
        for (Object o : new ArrayList<Object>(server.getConfigurationManager().playerEntityList)) {
            EntityPlayer player = (EntityPlayer) o;
            out.put(player.getUniqueID().toString(), player.getCommandSenderName());
        }
        return out;
    }

    private static Object dummyCriteria(Class<?> criteriaType) {
        for (Field f : criteriaType.getFields()) {
            if (!Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            Object value;
            try {
                value = f.get(null);
            } catch (Throwable ignored) {
                // 不可访问的静态字段跳过，继续查找候选 criteria
                continue;
            }
            if (value instanceof Map) {
                Object dummy = ((Map<?, ?>) value).get("dummy");
                if (criteriaType.isInstance(dummy)) {
                    return dummy;
                }
                for (Object candidate : ((Map<?, ?>) value).values()) {
                    if (criteriaType.isInstance(candidate)) {
                        return candidate;
                    }
                }
            }
            if (criteriaType.isInstance(value)) {
                return value;
            }
        }
        return null;
    }

    private static Method scoreSetter(Class<?> scoreType) {
        for (String name : new String[]{"setScorePoints", "func_96647_c"}) {
            try {
                Method m = scoreType.getMethod(name, int.class);
                if (m.getReturnType() == void.class) {
                    return m;
                }
            } catch (Throwable ignored) {
                // 换下一个候选名
            }
        }
        return null;
    }

    private static void appendMissing(StringBuilder out, String label, Method method) {
        if (method == null) {
            if (out.length() > 0) {
                out.append('、');
            }
            out.append(label);
        }
    }

    /** 签名查找失败时退回显式方法名，覆盖不同映射版本的命名差异。 */
    private static Method orNamed(Method found, Class<?> type, Class<?>[] params, String... names) {
        if (found != null) {
            return found;
        }
        for (String name : names) {
            try {
                return type.getMethod(name, params);
            } catch (Throwable ignored) {
                // 换下一个候选名
            }
        }
        return null;
    }

    private static Method bySignature(Class<?> type, Class<?>[] params, Class<?> returnType) {
        for (Method m : type.getMethods()) {
            if (!returnType.isAssignableFrom(m.getReturnType())) {
                continue;
            }
            Class<?>[] actual = m.getParameterTypes();
            if (actual.length != params.length) {
                continue;
            }
            boolean matched = true;
            for (int i = 0; i < actual.length; i++) {
                if (!actual[i].isAssignableFrom(params[i]) && !params[i].isAssignableFrom(actual[i])) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return m;
            }
        }
        return null;
    }

    private static Object noArgReturning(Object target, Class<?> returnType) {
        for (Method m : target.getClass().getMethods()) {
            if (m.getParameterTypes().length == 0 && returnType.isAssignableFrom(m.getReturnType())) {
                try {
                    return m.invoke(target);
                } catch (Throwable ignored) {
                    // 换下一个候选方法
                }
            }
        }
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
