package com.dsmnh.mobile.modserver.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** JSON 工具（沿用 AEMobile net/Json.java 形态）。 */
public final class NodeJson {
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private NodeJson() {
    }

    public static byte[] write(Object o) {
        try {
            return MAPPER.writeValueAsBytes(o);
        } catch (Exception e) {
            throw new IllegalStateException("JSON serialize failed", e);
        }
    }

    public static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    public static JsonNode read(byte[] payload) {
        try {
            if (payload == null || payload.length == 0) {
                return object();
            }
            return MAPPER.readTree(payload);
        } catch (Exception e) {
            return null;
        }
    }

    public static String strOf(JsonNode p, String key, String def) {
        JsonNode n = p == null ? null : p.get(key);
        return n == null || n.isNull() ? def : n.asText(def);
    }

    public static long longOf(JsonNode p, String key, long def) {
        JsonNode n = p == null ? null : p.get(key);
        return n == null || !n.canConvertToLong() ? def : n.asLong(def);
    }
}
