package com.dsmnh.mobile.modserver;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.dsmnh.mobile.modserver.node.NodeJson;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;

/** 在线玩家名快照（契约 1.6.0 §8.7）；只在服务器主线程采集。 */
public final class PlayerNames {
    private PlayerNames() {
    }

    /** 采集 UUID 与当前用户名；服务器未就绪时返回空数组。 */
    public static ArrayNode snapshot() {
        return snapshot(null);
    }

    /** 退出事件传 excludeUuid 以排除刚离线的玩家。 */
    public static ArrayNode snapshot(String excludeUuid) {
        ArrayNode players = NodeJson.object().putArray("players");
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.getConfigurationManager() == null) {
            return players;
        }
        for (Object o : new ArrayList<Object>(server.getConfigurationManager().playerEntityList)) {
            EntityPlayer player = (EntityPlayer) o;
            String uuid = player.getUniqueID().toString();
            if (uuid.equals(excludeUuid)) {
                continue;
            }
            ObjectNode row = players.addObject();
            row.put("uuid", uuid);
            row.put("name", player.getCommandSenderName());
        }
        return players;
    }
}
