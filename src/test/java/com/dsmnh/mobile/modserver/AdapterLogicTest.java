package com.dsmnh.mobile.modserver;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 下行适配器的纯逻辑部分：命令模板清洗、榜单 objective 名与分数解析。 */
public class AdapterLogicTest {

    @Test
    public void euJoinTemplateUsesDefaultAndRendersBothPlaceholders() {
        assertEquals("/gt global_energy_join Huaxi TeamA",
                EuJoinTemplate.render(null, "Huaxi", "TeamA"));
        assertEquals("energy add Huaxi@TeamA",
                EuJoinTemplate.render("energy add {player}@{team}", "Huaxi", "TeamA"));
    }

    @Test
    public void euJoinTemplateStripsControlCharacters() {
        String rendered = EuJoinTemplate.render("{player} {team}", "Huaxi\nop all", "Team\rA");
        assertEquals("Huaxiop all TeamA", rendered);
    }

    @Test
    public void rankingsObjectiveNameIsSafeAndBounded() {
        assertEquals("playtime", RankingsAdapter.objectiveName("playtime"));
        assertEquals("rank_2026_11", RankingsAdapter.objectiveName("rank 2026/11"));
        assertEquals("horizon_rank", RankingsAdapter.objectiveName(""));
        assertEquals(16, RankingsAdapter.objectiveName("abcdefghijklmnopqrstuvwxyz").length());
    }

    @Test
    public void rankingsScoreParsesLeadingInteger() {
        assertEquals(123, RankingsAdapter.parseScore("123 小时"));
        assertEquals(-7, RankingsAdapter.parseScore("-7"));
        assertEquals(0, RankingsAdapter.parseScore("无数据"));
        assertEquals(Integer.MAX_VALUE, RankingsAdapter.parseScore("9999999999999"));
    }

    @Test
    public void rankingsEntryPrefersOnlineName() {
        Map<String, String> names = new HashMap<String, String>();
        names.put("uuid-1", "Huaxi");
        assertEquals("Huaxi", RankingsAdapter.entryName("uuid-1", names));
        assertEquals("uuid2", RankingsAdapter.entryName("uuid-2", names));
    }
}
