package com.dsmnh.mobile.modserver.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 节点客户端对拍：注册、鉴权、时钟重签、版本终止、撤销终止、重连退避、请求配对。 */
class NodeClientTest {
    @TempDir
    Path tempDir;

    private FakeBackend backend;
    private NodeClient client;

    @BeforeEach
    void setup() throws Exception {
        backend = new FakeBackend();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        backend.close();
    }

    private NodeClient newClient() {
        NodeConfig config = NodeConfig.defaults(tempDir.resolve("node-credential.json"));
        config.backendHost = "127.0.0.1";
        config.backendPort = backend.port();
        config.backoffInitialMs = 50;
        config.requestTimeoutMs = 3_000;
        return new NodeClient(config, new NodeCredentialStore(config.credentialFile));
    }

    @Test
    void registerPersistsCredential() throws Exception {
        backend.responder = (op, p) -> {
            assertEquals("node.register", op);
            assertEquals("CODE1234", p.path("code").asText());
            ObjectNode b = NodeJson.object();
            b.put("nodeId", 7);
            b.put("token", backend.token);
            b.put("kid", backend.kid);
            return b;
        };
        client = newClient();
        assertEquals(7, client.register("CODE1234", "测试子服"));
        NodeCredentialStore.Credential saved =
                new NodeCredentialStore(tempDir.resolve("node-credential.json")).load();
        assertEquals(backend.token, saved.token);
        assertEquals(7, saved.nodeId);
        // 已有凭证时拒绝重复注册
        assertThrows(IllegalStateException.class, () -> client.register("CODE1234", null));
    }

    @Test
    void registerErrorPropagates() throws Exception {
        backend.reqErrorCode = "NODE_CODE_USED";
        client = newClient();
        NodeClient.NodeOpException e = assertThrows(NodeClient.NodeOpException.class,
                () -> client.register("CODE1234", null));
        assertEquals("NODE_CODE_USED", e.code);
    }

    @Test
    void connectAuthAndOps() throws Exception {
        registerSilently();
        AtomicInteger nodeIdSeen = new AtomicInteger(-1);
        client = newClient();
        client.listener(new NodeClient.Listener() {
            @Override
            public void onConnected(long nodeId) {
                nodeIdSeen.set((int) nodeId);
            }
        });
        backend.responder = (op, p) -> {
            if ("node.bindCode.create".equals(op)) {
                assertEquals("actor-uuid", p.path("actor").asText());
                ObjectNode b = NodeJson.object();
                b.put("code", "ABCD2345");
                b.put("expiresAt", System.currentTimeMillis() / 1000 + 600);
                return b;
            }
            if ("team.create".equals(op)) {
                assertEquals("actor-uuid", p.path("actor").asText());
                assertEquals("my-team", p.path("name").asText());
                ObjectNode b = NodeJson.object();
                b.put("teamId", 42);
                return b;
            }
            return NodeJson.object();
        };
        client.start();
        FakeBackend.await(client::isAuthenticated, 5_000, "应完成鉴权");
        FakeBackend.await(() -> nodeIdSeen.get() == 7, 5_000, "应回调 onConnected(7)");

        NodeApi api = new NodeApi(client);
        assertEquals("ABCD2345", api.bindCode("actor-uuid").path("code").asText());
        assertEquals(42, api.team("actor-uuid", "team.create",
                NodeJson.object().put("name", "my-team")).path("teamId").asLong());
    }

    @Test
    void clockSkewRetriedOnce() throws Exception {
        registerSilently();
        backend.clockFails = 1;
        client = newClient();
        client.start();
        FakeBackend.await(client::isAuthenticated, 5_000, "CLOCK 后应重签成功");
        FakeBackend.await(() -> backend.authCount.get() >= 2, 5_000, "应有两次 AUTH");
    }

    @Test
    void versionMismatchIsTerminal() throws Exception {
        registerSilently();
        backend.versionError = true;
        AtomicInteger revoked = new AtomicInteger();
        client = newClient();
        client.listener(new NodeClient.Listener() {
            @Override
            public void onRevoked() {
                revoked.incrementAndGet();
            }
        });
        client.start();
        FakeBackend.await(() -> revoked.get() == 1, 5_000, "版本不兼容应触发 onRevoked");
        int connections = backend.accepted.get();
        Thread.sleep(150);
        assertEquals(connections, backend.accepted.get(), "版本不兼容不应重连");
    }

    @Test
    void revokedStopsReconnect() throws Exception {
        registerSilently();
        backend.authFailCode = "NODE_REVOKED";
        AtomicInteger revoked = new AtomicInteger();
        client = newClient();
        client.listener(new NodeClient.Listener() {
            @Override
            public void onRevoked() {
                revoked.incrementAndGet();
            }
        });
        client.start();
        FakeBackend.await(() -> revoked.get() == 1, 5_000, "撤销应触发 onRevoked");
        int connections = backend.accepted.get();
        Thread.sleep(150);
        assertEquals(connections, backend.accepted.get(), "撤销后不应重连");
    }

    @Test
    void reconnectsAfterDrop() throws Exception {
        registerSilently();
        backend.dropAfterAuth = true;
        AtomicInteger connected = new AtomicInteger();
        client = newClient();
        client.listener(new NodeClient.Listener() {
            @Override
            public void onConnected(long nodeId) {
                connected.incrementAndGet();
            }
        });
        client.start();
        FakeBackend.await(() -> connected.get() >= 2, 10_000, "断开后应自动重连");
    }

    @Test
    void requestTimesOut() throws Exception {
        registerSilently();
        client = newClient();
        backend.responder = (op, p) -> null; // 不回包
        client.start();
        FakeBackend.await(client::isAuthenticated, 5_000, "应完成鉴权");
        assertThrows(java.io.IOException.class,
                () -> new NodeApi(client).bindCode("actor"));
    }

    /** 经 fake 完成一次注册，使凭证就位。 */
    private void registerSilently() throws Exception {
        backend.responder = (op, p) -> {
            ObjectNode b = NodeJson.object();
            b.put("nodeId", 7);
            b.put("token", backend.token);
            b.put("kid", backend.kid);
            return b;
        };
        try (NodeClient oneShot = newClient()) {
            oneShot.register("CODE1234", "测试子服");
        }
        backend.responder = null;
        AtomicReference<Object> ignored = new AtomicReference<>();
        assertNotNull(new NodeCredentialStore(tempDir.resolve("node-credential.json")).load());
    }
}
