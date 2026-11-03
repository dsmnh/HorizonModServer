package com.dsmnh.mobile.modserver.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** 对拍夹具：按契约实现的极简节点平面后端，供单测验证客户端行为。 */
public final class FakeBackend implements Closeable {
    public interface Responder {
        JsonNode onReq(String op, JsonNode p);
    }

    public final String token = "tok-12345678-abcdefgh-ijkl";
    public final String kid = "tok-1234";
    public volatile boolean versionError;
    /** 首次 AUTH 以 CLOCK 拒绝的次数。 */
    public volatile int clockFails;
    /** AUTH_FAIL 返回的码；空串 = 正常通过。 */
    public volatile String authFailCode = "";
    /** 鉴权通过后立即断开（测重连）。 */
    public volatile boolean dropAfterAuth;
    /** 注入 RES ok:false 的 m（null = 正常回包）。 */
    public volatile String reqErrorCode;
    public final AtomicInteger accepted = new AtomicInteger();
    public final AtomicInteger authCount = new AtomicInteger();
    public Responder responder;

    private final ServerSocket server;
    private volatile boolean running = true;

    public FakeBackend() throws IOException {
        server = new ServerSocket(0);
        Thread acceptor = new Thread(this::acceptLoop, "fake-backend-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public int port() {
        return server.getLocalPort();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = server.accept();
                accepted.incrementAndGet();
                Thread handler = new Thread(() -> handle(socket), "fake-backend-conn");
                handler.setDaemon(true);
                handler.start();
            } catch (IOException e) {
                if (running) {
                    // 接受失败继续下一轮
                }
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            DataInputStream in = new DataInputStream(s.getInputStream());
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            String nonce = null;
            boolean authed = false;
            while (true) {
                NodeProtocol.Frame frame = NodeProtocol.read(in);
                if (frame == null) {
                    return;
                }
                switch (frame.type) {
                    case NodeProtocol.T_HELLO: {
                        if (versionError) {
                            err(out, "VERSION_MISMATCH:server=1");
                            return;
                        }
                        nonce = NodeAuth.randomSid();
                        ObjectNode ack = NodeJson.object();
                        ack.put("v", 1);
                        ack.put("name", "Fake");
                        ack.put("ver", "0.0.0");
                        ack.put("time", System.currentTimeMillis() / 1000);
                        ack.put("nonce", nonce);
                        ack.put("plane", "node");
                        NodeProtocol.write(out, NodeProtocol.T_HELLO_ACK, NodeJson.write(ack));
                        break;
                    }
                    case NodeProtocol.T_AUTH: {
                        authCount.incrementAndGet();
                        JsonNode body = NodeJson.read(frame.payload);
                        long ts = body.path("ts").asLong();
                        String sig = body.path("sig").asText();
                        if (Math.abs(System.currentTimeMillis() / 1000 - ts) > NodeAuth.CLOCK_SKEW_SEC) {
                            ObjectNode fail = NodeJson.object();
                            fail.put("code", "CLOCK");
                            fail.put("time", System.currentTimeMillis() / 1000);
                            NodeProtocol.write(out, NodeProtocol.T_AUTH_FAIL, NodeJson.write(fail));
                            break;
                        }
                        if (clockFails > 0) {
                            clockFails--;
                            ObjectNode fail = NodeJson.object();
                            fail.put("code", "CLOCK");
                            fail.put("time", System.currentTimeMillis() / 1000);
                            NodeProtocol.write(out, NodeProtocol.T_AUTH_FAIL, NodeJson.write(fail));
                            break;
                        }
                        if (!authFailCode.isEmpty()) {
                            ObjectNode fail = NodeJson.object();
                            fail.put("code", authFailCode);
                            fail.put("time", System.currentTimeMillis() / 1000);
                            NodeProtocol.write(out, NodeProtocol.T_AUTH_FAIL, NodeJson.write(fail));
                            return;
                        }
                        if (!sigMatches(token, nonce, ts, sig)) {
                            ObjectNode fail = NodeJson.object();
                            fail.put("code", "AUTH_FAILED");
                            fail.put("time", System.currentTimeMillis() / 1000);
                            NodeProtocol.write(out, NodeProtocol.T_AUTH_FAIL, NodeJson.write(fail));
                            return;
                        }
                        authed = true;
                        NodeProtocol.write(out, NodeProtocol.T_AUTH_OK, null);
                        if (dropAfterAuth) {
                            return;
                        }
                        break;
                    }
                    case NodeProtocol.T_REQ: {
                        JsonNode req = NodeJson.read(frame.payload);
                        ObjectNode res = NodeJson.object();
                        res.put("id", req.path("id").asLong());
                        if (reqErrorCode != null) {
                            res.put("ok", false);
                            res.put("m", reqErrorCode);
                            NodeProtocol.write(out, NodeProtocol.T_RES, NodeJson.write(res));
                            break;
                        }
                        JsonNode result = responder == null ? JsonNodeFactory.instance.objectNode()
                                : responder.onReq(req.path("op").asText(), req.path("p"));
                        if (result == null) {
                            break; // 不回包，用于超时用例
                        }
                        res.put("ok", true);
                        res.set("b", result);
                        NodeProtocol.write(out, NodeProtocol.T_RES, NodeJson.write(res));
                        break;
                    }
                    case NodeProtocol.T_PING:
                        NodeProtocol.write(out, NodeProtocol.T_PONG, null);
                        break;
                    default:
                        break;
                }
                if (authed && !s.isConnected()) {
                    return;
                }
            }
        } catch (IOException ignored) {
            // 连接异常按断开处理
        }
    }

    private boolean sigMatches(String token, String nonce, long ts, String sig) {
        String expected = NodeAuth.sign(token, ts, nonce);
        return expected.equals(sig) && Base64.getUrlDecoder().decode(sig).length == 32;
    }

    private void err(DataOutputStream out, String m) throws IOException {
        ObjectNode node = NodeJson.object();
        node.put("m", m);
        NodeProtocol.write(out, NodeProtocol.T_ERR, NodeJson.write(node));
    }

    @Override
    public void close() {
        running = false;
        try {
            server.close();
        } catch (IOException ignored) {
            // 关闭失败不影响测试收尾
        }
    }

    /** 轮询等待条件成立，超时抛 AssertionError。 */
    public static void await(Supplier<Boolean> condition, long timeoutMs, String message)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.get()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("等待超时：" + message);
    }
}
