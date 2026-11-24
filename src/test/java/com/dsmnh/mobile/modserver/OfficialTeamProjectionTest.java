package com.dsmnh.mobile.modserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 官方队伍投影的纯逻辑：模式判定、快照指纹与上报代次/序号（契约 1.6.5 §8.8）。 */
class OfficialTeamProjectionTest {

    // ---------- TeamMode ----------

    @Test
    void officialModeRequiresAdapter() {
        assertEquals(TeamMode.Mode.OFFICIAL, TeamMode.resolve("official", true));
        assertEquals(TeamMode.Mode.DISABLED, TeamMode.resolve("official", false));
    }

    @Test
    void suModeNeverUsesOfficialAdapter() {
        assertEquals(TeamMode.Mode.SU, TeamMode.resolve("su", true));
        assertEquals(TeamMode.Mode.SU, TeamMode.resolve("su", false));
    }

    @Test
    void autoPrefersOfficialThenFallsBackToSu() {
        assertEquals(TeamMode.Mode.OFFICIAL, TeamMode.resolve("auto", true));
        assertEquals(TeamMode.Mode.SU, TeamMode.resolve("auto", false));
        assertEquals(TeamMode.Mode.SU, TeamMode.resolve("", false));
        assertEquals(TeamMode.Mode.OFFICIAL, TeamMode.resolve(null, true));
    }

    @Test
    void onlyOfficialModeDeclaresGtnhCapability() {
        assertEquals(Collections.singletonList("gtnh"),
                TeamMode.capabilities(TeamMode.Mode.OFFICIAL));
        assertTrue(TeamMode.capabilities(TeamMode.Mode.SU).isEmpty());
        assertTrue(TeamMode.capabilities(TeamMode.Mode.DISABLED).isEmpty());
    }

    @Test
    void suWritesOnlyAllowedInSuMode() {
        assertTrue(TeamMode.allowsSuTeamWrites(TeamMode.Mode.SU));
        assertFalse(TeamMode.allowsSuTeamWrites(TeamMode.Mode.OFFICIAL));
        assertFalse(TeamMode.allowsSuTeamWrites(TeamMode.Mode.DISABLED));
    }

    // ---------- 快照 ----------

    @Test
    void fingerprintIgnoresGenAndSeq() {
        OfficialTeamSnapshot a = snapshot(1, 1, true, "t-1", "Alpha");
        OfficialTeamSnapshot b = snapshot(9, 42, true, "t-1", "Alpha");
        assertEquals(a.fingerprint(), b.fingerprint());
    }

    @Test
    void fingerprintTracksMembershipAndReady() {
        OfficialTeamSnapshot a = snapshot(1, 1, true, "t-1", "Alpha");
        OfficialTeamSnapshot renamed = snapshot(1, 1, true, "t-1", "Beta");
        OfficialTeamSnapshot emptied = new OfficialTeamSnapshot("w:x:1", 1, 1, true,
                Collections.<OfficialTeamSnapshot.Team>emptyList());
        OfficialTeamSnapshot notReady = new OfficialTeamSnapshot("w:x:1", 1, 1, false,
                Collections.<OfficialTeamSnapshot.Team>emptyList());

        assertFalse(a.fingerprint().equals(renamed.fingerprint()));
        assertFalse(a.fingerprint().equals(emptied.fingerprint()));
        assertFalse(emptied.fingerprint().equals(notReady.fingerprint()));
    }

    @Test
    void toJsonCarriesContractFields() {
        ObjectNode body = snapshot(3, 7, true, "t-1", "Alpha").toJson();
        assertEquals("w:x:1", body.path("worldRef").asText());
        assertEquals(3, body.path("gen").asLong());
        assertEquals(7, body.path("seq").asLong());
        assertTrue(body.path("ready").asBoolean());
        JsonNode team = body.path("teams").get(0);
        assertEquals("t-1", team.path("officialTeamId").asText());
        assertEquals("Alpha", team.path("name").asText());
        assertEquals("u-owner", team.path("owners").get(0).asText());
        assertEquals("u-officer", team.path("officers").get(0).asText());
        assertEquals("u-owner", team.path("members").get(0).asText());
    }

    @Test
    void multiOwnerAndOverlappingRolesArePreserved() {
        OfficialTeamSnapshot snapshot = new OfficialTeamSnapshot("w:x:1", 1, 1, true,
                Collections.singletonList(new OfficialTeamSnapshot.Team("t-1", "Alpha",
                        Arrays.asList("u-a", "u-b"), Collections.singletonList("u-b"),
                        Arrays.asList("u-a", "u-b", "u-c"))));
        JsonNode team = snapshot.toJson().path("teams").get(0);
        assertEquals(2, team.path("owners").size());
        // 同一 UUID 既是 owner 也在 members：按 GTNHLib 原样保留，不做去重或互斥
        assertEquals("u-b", team.path("members").get(1).asText());
    }

    // ---------- 上报代次与去重 ----------

    @Test
    void firstFlushReportsAndSecondIsQuiet() {
        FakeSource source = new FakeSource();
        List<ObjectNode> sent = new ArrayList<ObjectNode>();
        OfficialTeamReporter reporter = new OfficialTeamReporter(source, sent::add);

        reporter.onWorldLoaded();
        assertNotNull(take(reporter));
        assertNull(take(reporter), "内容未变不应重复上报");
        assertEquals(1, sent.size());
    }

    @Test
    void worldReloadBumpsGenAndResetsSeq() {
        FakeSource source = new FakeSource();
        OfficialTeamReporter reporter = new OfficialTeamReporter(source, body -> { });
        reporter.onWorldLoaded();
        OfficialTeamSnapshot first = take(reporter);
        assertEquals(1, first.gen);
        assertEquals(1, first.seq);

        reporter.onWorldLoaded();
        OfficialTeamSnapshot second = take(reporter);
        assertEquals(2, second.gen, "世界重新加载后代次必须递增");
        assertEquals(1, second.seq, "新代次内序号从 1 重新开始");
    }

    @Test
    void seqIncrementsOnlyOnActualDelivery() {
        FakeSource source = new FakeSource();
        OfficialTeamReporter reporter = new OfficialTeamReporter(source, body -> { });
        reporter.onWorldLoaded();
        take(reporter);

        source.teams = Collections.singletonList(new OfficialTeamSnapshot.Team("t-2", "Beta",
                Collections.singletonList("u-a"), Collections.<String>emptyList(),
                Collections.singletonList("u-a")));
        reporter.markDirty();
        OfficialTeamSnapshot changed = take(reporter);
        assertEquals(2, changed.seq);
        reporter.markDirty();
        assertNull(take(reporter), "置脏但内容相同不应消耗序号");
        source.teams = Collections.singletonList(new OfficialTeamSnapshot.Team("t-3", "Gamma",
                Collections.singletonList("u-a"), Collections.<String>emptyList(),
                Collections.singletonList("u-a")));
        reporter.markDirty();
        assertEquals(3, take(reporter).seq);
    }

    @Test
    void notReadyWorldReportsEmptyButKeepsGen() {
        FakeSource source = new FakeSource();
        source.ready = false;
        OfficialTeamReporter reporter = new OfficialTeamReporter(source, body -> { });
        reporter.onWorldLoaded();
        OfficialTeamSnapshot snapshot = take(reporter);
        assertNotNull(snapshot);
        assertFalse(snapshot.ready);
        assertTrue(snapshot.toJson().path("teams").isEmpty());
        assertEquals(1, snapshot.gen);
    }

    @Test
    void reconnectForcesFullResend() {
        FakeSource source = new FakeSource();
        OfficialTeamReporter reporter = new OfficialTeamReporter(source, body -> { });
        reporter.onWorldLoaded();
        take(reporter);
        assertNull(take(reporter));

        reporter.onConnectionEstablished();
        OfficialTeamSnapshot resent = take(reporter);
        assertNotNull(resent, "重连后必须重发一次完整投影");
        assertEquals(1, resent.gen, "重连不改变世界代次");
        assertEquals(2, resent.seq, "重发消耗一个新序号");
    }

    private static OfficialTeamSnapshot take(OfficialTeamReporter reporter) {
        OfficialTeamSnapshot snapshot = reporter.take();
        if (snapshot != null) {
            reporter.dispatch(snapshot);
        }
        return snapshot;
    }

    private static OfficialTeamSnapshot snapshot(long gen, long seq, boolean ready,
                                                 String id, String name) {
        return new OfficialTeamSnapshot("w:x:1", gen, seq, ready,
                Collections.singletonList(new OfficialTeamSnapshot.Team(id, name,
                        Collections.singletonList("u-owner"),
                        Collections.singletonList("u-officer"),
                        Arrays.asList("u-owner", "u-officer"))));
    }

    private static final class FakeSource implements OfficialTeamReporter.Source {
        boolean ready = true;
        List<OfficialTeamSnapshot.Team> teams = Collections.singletonList(
                new OfficialTeamSnapshot.Team("t-1", "Alpha",
                        Collections.singletonList("u-a"), Collections.<String>emptyList(),
                        Collections.singletonList("u-a")));

        @Override
        public boolean ready() {
            return ready;
        }

        @Override
        public String worldRef() {
            return "w:x:1";
        }

        @Override
        public List<OfficialTeamSnapshot.Team> collect() {
            return teams;
        }
    }
}
