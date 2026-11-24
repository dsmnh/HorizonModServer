package com.dsmnh.mobile.modserver;

/**
 * 登记表 §6 下行类型到执行路径的映射。纯逻辑，不依赖游戏侧类，供分发与单测共用。
 * BQ 的 export/import 由 phase 决定；未知类型或非法 phase 一律拒绝并回报原因。
 */
public final class DownOpRouter {
    public enum Action {
        OP_SET,
        TEAM_APPLY,
        EU_JOIN,
        BQ_EXPORT,
        BQ_IMPORT,
        RANKINGS_APPLY,
        ME_QUERY,
        OFFICIAL_WRITE,
        REJECT
    }

    private DownOpRouter() {
    }

    public static Action route(String type, String phase) {
        if (DownOpTypes.OP_SET.equals(type)) {
            return Action.OP_SET;
        }
        if (DownOpTypes.TEAM_APPLY.equals(type)) {
            return Action.TEAM_APPLY;
        }
        if (DownOpTypes.EU_JOIN.equals(type)) {
            return Action.EU_JOIN;
        }
        if (DownOpTypes.RANKINGS_APPLY.equals(type)) {
            return Action.RANKINGS_APPLY;
        }
        if (DownOpTypes.ME_QUERY.equals(type)) {
            return Action.ME_QUERY;
        }
        if (DownOpTypes.OFFICIAL_WRITE.equals(type)) {
            return Action.OFFICIAL_WRITE;
        }
        if (DownOpTypes.BQ_APPLY.equals(type)) {
            if ("export".equals(phase)) {
                return Action.BQ_EXPORT;
            }
            if ("import".equals(phase)) {
                return Action.BQ_IMPORT;
            }
        }
        return Action.REJECT;
    }

    /** 拒绝原因写入 node.op.fail，后端据此入审计日志。 */
    public static String rejectReason(String type, String phase) {
        if (!DownOpTypes.isKnown(type)) {
            return "unsupported type " + type;
        }
        return "invalid bq.apply phase " + phase;
    }
}
