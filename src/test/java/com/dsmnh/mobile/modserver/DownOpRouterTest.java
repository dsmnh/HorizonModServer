package com.dsmnh.mobile.modserver;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 登记表 §6 六类下行类型均须有执行分支；BQ 两阶段由 phase 区分。 */
public class DownOpRouterTest {

    @Test
    public void registeredTypesCoverContractRegistry() {
        Set<String> expected = new LinkedHashSet<String>(Arrays.asList(
                "op.set", "team.apply", "eu.join", "bq.apply", "rankings.apply", "me.query"));
        assertEquals(expected, DownOpTypes.ALL);
        for (String type : expected) {
            assertTrue(DownOpTypes.isKnown(type));
        }
        assertFalse(DownOpTypes.isKnown(null));
        assertFalse(DownOpTypes.isKnown("unknown.type"));
    }

    @Test
    public void eachRegisteredTypeHasAnExecutionBranch() {
        assertEquals(DownOpRouter.Action.OP_SET, DownOpRouter.route("op.set", ""));
        assertEquals(DownOpRouter.Action.TEAM_APPLY, DownOpRouter.route("team.apply", ""));
        assertEquals(DownOpRouter.Action.EU_JOIN, DownOpRouter.route("eu.join", ""));
        assertEquals(DownOpRouter.Action.RANKINGS_APPLY, DownOpRouter.route("rankings.apply", ""));
        assertEquals(DownOpRouter.Action.ME_QUERY, DownOpRouter.route("me.query", ""));
    }

    @Test
    public void bqApplySplitsByPhase() {
        assertEquals(DownOpRouter.Action.BQ_EXPORT, DownOpRouter.route("bq.apply", "export"));
        assertEquals(DownOpRouter.Action.BQ_IMPORT, DownOpRouter.route("bq.apply", "import"));
        assertEquals(DownOpRouter.Action.REJECT, DownOpRouter.route("bq.apply", ""));
        assertEquals(DownOpRouter.Action.REJECT, DownOpRouter.route("bq.apply", "unknown"));
    }

    @Test
    public void unknownTypeAndInvalidPhaseAreRejectedWithReason() {
        assertEquals(DownOpRouter.Action.REJECT, DownOpRouter.route("unknown.type", ""));
        assertTrue(DownOpRouter.rejectReason("unknown.type", "").contains("unsupported type"));
        assertTrue(DownOpRouter.rejectReason("bq.apply", "bad").contains("phase"));
    }
}
