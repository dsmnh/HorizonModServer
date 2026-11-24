package com.dsmnh.mobile.modserver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 官方队伍写入的节点侧前置判定（契约 1.6.6 §8.9）。
 * 关注判定顺序：本地没开官方模式或动作未知时，不应把责任推给在线状态或角色。
 */
public class OfficialWriteGuardTest {

    @Test
    public void knownActionsAreOnlyInviteAndAccept() {
        assertTrue(OfficialWriteGuard.knownAction("invite"));
        assertTrue(OfficialWriteGuard.knownAction("accept"));
        assertFalse(OfficialWriteGuard.knownAction("kick"));
        assertFalse(OfficialWriteGuard.knownAction("transfer"));
        assertFalse(OfficialWriteGuard.knownAction(""));
        assertFalse(OfficialWriteGuard.knownAction(null));
    }

    @Test
    public void unsupportedModeOrActionComesFirst() {
        // 同一世界代次、目标在线、发起者是官员，仍因未启用官方模式被拒
        assertEquals(OfficialWriteGuard.UNSUPPORTED, OfficialWriteGuard.check(
                "invite", false, 7, 7, true, true));
        assertEquals(OfficialWriteGuard.UNSUPPORTED, OfficialWriteGuard.check(
                "kick", true, 7, 7, true, true));
    }

    @Test
    public void staleWorldWinsOverOnlineAndRole() {
        assertEquals(OfficialWriteGuard.STALE_WORLD, OfficialWriteGuard.check(
                "invite", true, 6, 7, false, false));
    }

    @Test
    public void offlinePlayerIsRejected() {
        assertEquals(OfficialWriteGuard.PLAYER_OFFLINE, OfficialWriteGuard.check(
                "invite", true, 7, 7, false, true));
        assertEquals(OfficialWriteGuard.PLAYER_OFFLINE, OfficialWriteGuard.check(
                "accept", true, 7, 7, false, true));
    }

    @Test
    public void onlyInviteRequiresOfficer() {
        assertEquals(OfficialWriteGuard.NOT_OFFICER, OfficialWriteGuard.check(
                "invite", true, 7, 7, true, false));
        // 接受入队的人本身就是普通玩家，GTNHLib 只校验是否存在待接受邀请
        assertNull(OfficialWriteGuard.check("accept", true, 7, 7, true, false));
    }

    @Test
    public void validInvitePasses() {
        assertNull(OfficialWriteGuard.check("invite", true, 7, 7, true, true));
    }
}
