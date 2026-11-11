package com.dsmnh.mobile.modserver.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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

    /** 在线玩家名上报（契约 1.6.0 §8.7）。 */
    public JsonNode playerNamesReport(ArrayNode players) throws IOException {
        ObjectNode p = NodeJson.object();
        p.set("players", players == null ? JsonNodeFactory.instance.arrayNode() : players);
        return client.req("player.names.report", p).path("b");
    }

    /** ME 设备登记与注销（契约 1.5.0 §8.5）。 */
    public JsonNode meRegister(String deviceId, String owner, String kind) throws IOException {
        ObjectNode p = NodeJson.object();
        p.put("deviceId", deviceId);
        p.put("owner", owner);
        p.put("kind", kind);
        return client.req("me.register", p).path("b");
    }

    public JsonNode meUnregister(String deviceId) throws IOException {
        ObjectNode p = NodeJson.object();
        p.put("deviceId", deviceId);
        return client.req("me.unregister", p).path("b");
    }

    /** AE2 查询结果回填（登记表 §4.5）：items 为结果数组，error 可选。 */
    public JsonNode meQueryResult(String queryId, ArrayNode items, String error) throws IOException {
        ObjectNode p = NodeJson.object();
        p.put("queryId", queryId);
        p.set("items", items == null ? JsonNodeFactory.instance.arrayNode() : items);
        if (error != null && !error.isEmpty()) {
            p.put("error", error);
        }
        return client.req("me.query.result", p).path("b");
    }

    /** BQ 快照回传与拉取（契约 1.6.0 §8.4）。 */
    public JsonNode bqSnapshotPut(String snapshotId, String batchId, ObjectNode payload) throws IOException {
        ObjectNode p = NodeJson.object();
        p.put("snapshotId", snapshotId);
        p.put("batchId", batchId);
        p.set("payload", payload == null ? NodeJson.object() : payload);
        return client.req("bq.snapshot.put", p).path("b");
    }

    public JsonNode bqSnapshotGet(String snapshotId) throws IOException {
        ObjectNode p = NodeJson.object();
        p.put("snapshotId", snapshotId);
        return client.req("bq.snapshot.get", p).path("b");
    }
}
