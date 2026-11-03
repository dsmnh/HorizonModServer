package com.dsmnh.mobile.modserver.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 节点凭证持久化：JSON 文件（nodeId/token/kid），原子替换写入。 */
public final class NodeCredentialStore {
    private final Path file;

    public NodeCredentialStore(Path file) {
        this.file = file;
    }

    /** 读取已保存凭证；无凭证或文件损坏返回 null。 */
    public Credential load() {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            JsonNode node = NodeJson.MAPPER.readTree(file.toFile());
            String token = NodeJson.strOf(node, "token", null);
            if (token == null || token.isEmpty()) {
                return null;
            }
            return new Credential(NodeJson.longOf(node, "nodeId", -1), token,
                    NodeJson.strOf(node, "kid", NodeAuth.kidOf(token)));
        } catch (Exception e) {
            return null;
        }
    }

    public void save(long nodeId, String token, String kid) {
        ObjectNode node = NodeJson.object();
        node.put("nodeId", nodeId);
        node.put("token", token);
        node.put("kid", kid);
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            NodeJson.MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), node);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("凭证写入失败：" + file, e);
        }
    }

    public void clear() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 清理失败不影响主流程
        }
    }

    /** 节点凭证：节点 ID 与 token（token 只存本地，永不上网线）。 */
    public static final class Credential {
        public final long nodeId;
        public final String token;
        public final String kid;

        public Credential(long nodeId, String token, String kid) {
            this.nodeId = nodeId;
            this.token = token;
            this.kid = kid;
        }
    }
}
