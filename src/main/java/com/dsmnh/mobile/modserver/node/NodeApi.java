package com.dsmnh.mobile.modserver.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;

/** 节点平面常用操作封装：游戏内出码与队伍代调用（p.actor 注入，契约 §8）。 */
public final class NodeApi {
    private final NodeClient client;

    public NodeApi(NodeClient client) {
        this.client = client;
    }

    /** 为在线玩家签发绑定码（10 分钟有效、一次性；新码注销旧码）。 */
    public JsonNode bindCode(String actor) throws IOException {
        ObjectNode p = NodeJson.object();
        p.put("actor", actor);
        return client.req("node.bindCode.create", p).path("b");
    }

    /** 为在线玩家签发恢复码；UUID 未绑定 App 账号时抛 NodeOpException(UUID_NOT_BOUND)。 */
    public JsonNode recoverCode(String actor) throws IOException {
        ObjectNode p = NodeJson.object();
        p.put("actor", actor);
        return client.req("node.recoverCode.create", p).path("b");
    }

    /** 队伍域代调用：op 为登记表 §4.2 操作名，p 为该操作参数（自动补 actor）。 */
    public JsonNode team(String actor, String op, ObjectNode p) throws IOException {
        ObjectNode params = p == null ? NodeJson.object() : p;
        params.put("actor", actor);
        return client.req(op, params).path("b");
    }
}
