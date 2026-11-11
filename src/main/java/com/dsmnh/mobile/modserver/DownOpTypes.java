package com.dsmnh.mobile.modserver;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** 登记表 §6 登记的 6 种下行操作类型；分发守卫与单测共用，避免遗漏执行分支。 */
public final class DownOpTypes {
    public static final String OP_SET = "op.set";
    public static final String TEAM_APPLY = "team.apply";
    public static final String EU_JOIN = "eu.join";
    public static final String BQ_APPLY = "bq.apply";
    public static final String RANKINGS_APPLY = "rankings.apply";
    public static final String ME_QUERY = "me.query";

    public static final Set<String> ALL = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList(
                    OP_SET, TEAM_APPLY, EU_JOIN, BQ_APPLY, RANKINGS_APPLY, ME_QUERY)));

    private DownOpTypes() {
    }

    public static boolean isKnown(String type) {
        return type != null && ALL.contains(type);
    }
}
