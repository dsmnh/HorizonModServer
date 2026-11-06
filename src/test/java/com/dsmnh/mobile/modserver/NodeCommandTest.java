package com.dsmnh.mobile.modserver;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * NodeCommand 子命令 → 契约 op 名映射锁定测试（登记表 §team 域）。
 * 背景：accept/decline/approve/deny/setrole/joinmode 六个子命令曾直接拼 "team."+action，
 * 产生未登记 op（真后端 UNKNOWN_OP）；contractOp() 显式映射后由本测试锁定。
 */
public class NodeCommandTest {

    private static String contractOp(String action) throws Exception {
        Method m = NodeCommand.class.getDeclaredMethod("contractOp", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, action);
    }

    @Test
    public void inviteStyleSubcommandsMapToContractNames() throws Exception {
        assertEquals("team.acceptInvite", contractOp("accept"));
        assertEquals("team.declineInvite", contractOp("decline"));
        assertEquals("team.approveApply", contractOp("approve"));
        assertEquals("team.denyApply", contractOp("deny"));
        assertEquals("team.setRole", contractOp("setrole"));
        assertEquals("team.setJoinMode", contractOp("joinmode"));
    }

    @Test
    public void sameNameSubcommandsPassThrough() throws Exception {
        assertEquals("team.create", contractOp("create"));
        assertEquals("team.disband", contractOp("disband"));
        assertEquals("team.info", contractOp("info"));
        assertEquals("team.list", contractOp("list"));
        assertEquals("team.leave", contractOp("leave"));
        assertEquals("team.invite", contractOp("invite"));
        assertEquals("team.kick", contractOp("kick"));
        assertEquals("team.transfer", contractOp("transfer"));
        assertEquals("team.apply", contractOp("apply"));
    }
}
