package com.dsmnh.mobile.modserver.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 节点平面客户端：HELLO → AUTH（HMAC）→ ACTIVE，保活与断线重连退避（契约 §3/§4/§7.3）。
 * 线程模型：读线程阻塞读帧；写经 outLock 串行化；请求按 id 配对 CompletableFuture。
 */
public final class NodeClient implements Closeable {
    /** 连接事件：onRevoked 后不再重连，需重新注册。 */
    public interface Listener {
        default void onConnected(long nodeId) {}

        default void onDisconnected(boolean willRetry) {}

        default void onRevoked() {}

        /** 节点下行操作（契约 1.2.0）：执行后必须回报 node.op.ack 或 node.op.fail。 */
        default void onDownOp(String opId, String type, JsonNode p) {}
    }

    /** 握手/致命错误：code 为 ERR.m 或 AUTH_FAIL.code。 */
    public static final class NodeAuthException extends IOException {
        public final String code;

        public NodeAuthException(String code) {
            super(code);
            this.code = code;
        }
    }

    /** 业务失败：RES ok:false 的 m（可带 :detail）。 */
    public static final class NodeOpException extends IOException {
        public final String code;

        public NodeOpException(String code) {
            super(code);
            this.code = code;
        }
    }

    private final NodeConfig config;
    private final NodeCredentialStore store;
    private final ConcurrentMap<Long, CompletableFuture<JsonNode>> pending =
            new ConcurrentHashMap<Long, CompletableFuture<JsonNode>>();
    private final AtomicLong idGen = new AtomicLong();
    private final Object outLock = new Object();

    private volatile Listener listener = new Listener() {};
    private volatile Socket socket;
    private volatile DataInputStream in;
    private volatile DataOutputStream out;
    private volatile String nonce;
    private volatile long clockOffset;
    private volatile boolean running;
    private volatile boolean authed;
    private volatile long nodeId = -1;
    private volatile ScheduledExecutorService pingExecutor;
    private volatile Thread connectThread;

    public NodeClient(NodeConfig config, NodeCredentialStore store) {
        this.config = config;
        this.store = store;
        NodeCredentialStore.Credential saved = store.load();
        if (saved != null) {
            this.nodeId = saved.nodeId;
        }
    }

    public void listener(Listener listener) {
        this.listener = listener == null ? new Listener() {} : listener;
    }

    public long nodeId() {
        return nodeId;
    }

    /** 当前连接是否已完成握手鉴权。 */
    public boolean isAuthenticated() {
        return authed;
    }

    /** 注册：预鉴权连接兑换节点凭证并持久化；成功后可 start()。 */
    public synchronized long register(String code, String nodeName) throws IOException {
        NodeCredentialStore.Credential existing = store.load();
        if (existing != null) {
            throw new IllegalStateException("本机已持有节点凭证（节点 " + existing.nodeId + "），"
                    + "恢复节点请使用恢复注册码并联系管理员撤销旧节点");
        }
        try {
            connectSocket();
            hello();
            ObjectNode p = NodeJson.object();
            p.put("code", code);
            if (nodeName != null && !nodeName.isEmpty()) {
                p.put("nodeName", nodeName);
            }
            JsonNode res = reqOnce("node.register", p);
            if (!res.path("ok").asBoolean()) {
                throw new NodeOpException(NodeJson.strOf(res, "m", "UNKNOWN"));
            }
            JsonNode b = res.path("b");
            long newId = b.path("nodeId").asLong(-1);
            String token = b.path("token").asText();
            if (newId < 0 || token.isEmpty()) {
                throw new IOException("注册响应缺少凭证字段");
            }
            store.save(newId, token, NodeAuth.kidOf(token));
            this.nodeId = newId;
            return newId;
        } finally {
            closeSocket();
        }
    }

    /** 启动持久连接与重连循环；已启动时忽略。 */
    public synchronized void start() {
        if (running) {
            return;
        }
        if (store.load() == null) {
            throw new IllegalStateException("尚无节点凭证，先 register()");
        }
        running = true;
        connectThread = new Thread(this::connectLoop, "horizon-node-connect");
        connectThread.setDaemon(true);
        connectThread.start();
    }

    @Override
    public synchronized void close() {
        running = false;
        Thread thread = connectThread;
        if (thread != null) {
            thread.interrupt();
        }
        closeSocket();
        if (pingExecutor != null) {
            pingExecutor.shutdownNow();
            pingExecutor = null;
        }
        for (CompletableFuture<JsonNode> future : pending.values()) {
            future.completeExceptionally(new IOException("客户端已关闭"));
        }
        pending.clear();
    }

    /** 发起请求并等待响应；ok:false 抛 NodeOpException。 */
    public JsonNode req(String op, ObjectNode p) throws IOException {
        long id = idGen.incrementAndGet();
        CompletableFuture<JsonNode> future = new CompletableFuture<JsonNode>();
        pending.put(id, future);
        try {
            ObjectNode request = NodeJson.object();
            request.put("id", id);
            request.put("op", op);
            request.set("p", p == null ? NodeJson.object() : p);
            send(NodeProtocol.T_REQ, NodeJson.write(request));
            JsonNode res = future.get(config.requestTimeoutMs, TimeUnit.MILLISECONDS);
            if (!res.path("ok").asBoolean()) {
                throw new NodeOpException(NodeJson.strOf(res, "m", "UNKNOWN"));
            }
            return res;
        } catch (NodeOpException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("请求 " + op + " 失败", e);
        } finally {
            pending.remove(id);
        }
    }

    // ---------- 连接循环 ----------

    private void connectLoop() {
        long backoff = config.backoffInitialMs;
        while (running) {
            boolean fatal = false;
            try {
                connectSocket();
                hello();
                authWithRetry();
                socket.setSoTimeout(0);
                backoff = config.backoffInitialMs;
                listener.onConnected(nodeId);
                readLoop();
            } catch (NodeAuthException e) {
                fatal = "NODE_REVOKED".equals(e.code) || "SESSION_EXPIRED".equals(e.code)
                        || e.code.startsWith("VERSION_MISMATCH");
                if (!fatal) {
                    log("握手失败：" + e.code);
                }
            } catch (Exception e) {
                log("连接异常：" + e);
            }
            closeSocket();
            if (!running) {
                return;
            }
            if (fatal) {
                listener.onRevoked();
                running = false;
                return;
            }
            listener.onDisconnected(true);
            try {
                TimeUnit.MILLISECONDS.sleep(backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            backoff = Math.min(backoff * 2, config.backoffMaxMs);
        }
    }

    private void connectSocket() throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(config.backendHost, config.backendPort), 10_000);
        s.setSoTimeout(0);
        socket = s;
        in = new DataInputStream(s.getInputStream());
        out = new DataOutputStream(s.getOutputStream());
        s.setSoTimeout(10_000);
    }

    private void hello() throws IOException {
        nonce = null;
        ObjectNode hello = NodeJson.object();
        hello.put("v", NodeProtocol.VERSION);
        hello.put("app", config.app);
        hello.put("plane", "node");
        send(NodeProtocol.T_HELLO, NodeJson.write(hello));
        NodeProtocol.Frame ack = NodeProtocol.read(in);
        if (ack == null) {
            throw new IOException("服务器关闭连接");
        }
        if (ack.type == NodeProtocol.T_ERR) {
            throw new NodeAuthException(NodeJson.strOf(NodeJson.read(ack.payload), "m", "ERR"));
        }
        if (ack.type != NodeProtocol.T_HELLO_ACK) {
            throw new IOException("期望 HELLO_ACK，收到 0x" + Integer.toHexString(ack.type));
        }
        JsonNode body = NodeJson.read(ack.payload);
        if (body.path("v").asInt(-1) != NodeProtocol.VERSION) {
            closeSocket();
            throw new NodeAuthException("VERSION_MISMATCH:server=" + body.path("v").asInt(-1));
        }
        nonce = body.path("nonce").asText(null);
        if (nonce == null || nonce.isEmpty()) {
            throw new IOException("HELLO_ACK 缺少 nonce");
        }
        clockOffset = body.path("time").asLong(0) - System.currentTimeMillis() / 1000;
    }

    private void authWithRetry() throws IOException {
        try {
            authOnce();
            return;
        } catch (NodeAuthException e) {
            if (!"CLOCK".equals(e.code)) {
                throw e;
            }
        }
        // CLOCK：按服务器返回的 time 修正偏移后重签一次（契约 §4.2）
        authOnce();
    }

    private void authOnce() throws IOException {
        NodeCredentialStore.Credential credential = store.load();
        if (credential == null) {
            throw new NodeAuthException("NO_CREDENTIAL");
        }
        long ts = System.currentTimeMillis() / 1000 + clockOffset;
        ObjectNode auth = NodeJson.object();
        auth.put("kid", credential.kid);
        auth.put("ts", ts);
        auth.put("sig", NodeAuth.sign(credential.token, ts, nonce));
        send(NodeProtocol.T_AUTH, NodeJson.write(auth));
        NodeProtocol.Frame reply = NodeProtocol.read(in);
        if (reply == null) {
            throw new IOException("服务器关闭连接");
        }
        if (reply.type == NodeProtocol.T_AUTH_FAIL) {
            JsonNode body = NodeJson.read(reply.payload);
            String code = NodeJson.strOf(body, "code", "AUTH_FAILED");
            if ("CLOCK".equals(code)) {
                long serverTime = body.path("time").asLong(0);
                if (serverTime > 0) {
                    clockOffset = serverTime - System.currentTimeMillis() / 1000;
                }
            }
            throw new NodeAuthException(code);
        }
        if (reply.type != NodeProtocol.T_AUTH_OK) {
            throw new IOException("期望 AUTH_OK，收到 0x" + Integer.toHexString(reply.type));
        }
        nodeId = credential.nodeId;
        authed = true;
        startPing();
        subscribeOps();
    }

    /** chat 流消息监听器（由主类设置，处理游戏内公屏显示）。 */
    private volatile java.util.function.BiConsumer<String, JsonNode> chatListener;

    /** 设置 chat 流消息监听器（回调参数：from uuid, 完整事件节点）。 */
    public void setChatListener(java.util.function.BiConsumer<String, JsonNode> listener) {
        this.chatListener = listener;
    }

    /** 鉴权成功后订阅 ops 流 + chat 流；后端不支持时（UNKNOWN_OP）静默降级。 */
    private void subscribeOps() {
        long id = idGen.incrementAndGet();
        CompletableFuture<JsonNode> future = new CompletableFuture<JsonNode>();
        pending.put(id, future);
        try {
            ObjectNode request = NodeJson.object();
            request.put("id", id);
            request.put("op", "ops");
            request.set("p", NodeJson.object());
            send(NodeProtocol.T_SUB, NodeJson.write(request));
        } catch (Exception e) {
            pending.remove(id);
            log("订阅下行流失败：" + e);
            return;
        }
        future.whenComplete((res, err) -> {
            pending.remove(id);
            if (err != null) {
                log("下行流订阅未确认（后端可能不支持）：err=" + err);
            }
        });
        // chat 流订阅（1.4.0）
        long chatId = idGen.incrementAndGet();
        CompletableFuture<JsonNode> chatFuture = new CompletableFuture<JsonNode>();
        pending.put(chatId, chatFuture);
        try {
            ObjectNode chatReq = NodeJson.object();
            chatReq.put("id", chatId);
            chatReq.put("op", "chat");
            chatReq.set("p", NodeJson.object());
            send(NodeProtocol.T_SUB, NodeJson.write(chatReq));
        } catch (Exception e) {
            pending.remove(chatId);
            log("chat 流订阅失败：" + e);
            return;
        }
        chatFuture.whenComplete((res, err) -> {
            pending.remove(chatId);
            if (err != null) {
                log("chat 流订阅未确认：err=" + err);
            }
        });
    }

    private void readLoop() throws IOException {
        while (running) {
            NodeProtocol.Frame frame = NodeProtocol.read(in);
            if (frame == null) {
                return;
            }
            switch (frame.type) {
                case NodeProtocol.T_RES:
                case NodeProtocol.T_SUB_OK:
                    complete(NodeJson.read(frame.payload));
                    break;
                case NodeProtocol.T_PING:
                    send(NodeProtocol.T_PONG, null);
                    break;
                case NodeProtocol.T_PUSH:
                    dispatchPush(NodeJson.read(frame.payload));
                    break;
                case NodeProtocol.T_ERR:
                    throw new NodeAuthException(NodeJson.strOf(NodeJson.read(frame.payload), "m", "ERR"));
                default:
                    break;
            }
        }
    }

    /** 解析推送帧：t=f 全量与 t=d 增量分派到 ops 或 chat 回调；t=k 心跳忽略。 */
    private void dispatchPush(JsonNode push) {
        JsonNode f = push.path("f");
        String t = f.path("t").asText("");
        if ("f".equals(t)) {
            // 全量帧的 ops 元素不含 k 字段（登记表 §5.2），直接回调
            for (JsonNode op : f.path("ops")) {
                notifyOp(op);
            }
        } else if ("d".equals(t)) {
            for (JsonNode event : f.path("events")) {
                String k = event.path("k").asText();
                if ("op".equals(k)) {
                    notifyOp(event);
                } else if ("msg".equals(k)) {
                    java.util.function.BiConsumer<String, JsonNode> cl = chatListener;
                    if (cl != null) {
                        cl.accept(event.path("from").asText(), event);
                    }
                }
            }
        }
    }

    private void notifyOp(JsonNode event) {
        listener.onDownOp(event.path("opId").asText(), event.path("type").asText(),
                event.path("p"));
    }

    private void complete(JsonNode res) {
        long id = res.path("id").asLong(-1);
        CompletableFuture<JsonNode> future = pending.remove(id);
        if (future != null) {
            future.complete(res);
        }
    }

    private void startPing() {
        if (pingExecutor != null) {
            pingExecutor.shutdownNow();
        }
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "horizon-node-ping");
            t.setDaemon(true);
            return t;
        });
        pingExecutor = executor;
        executor.scheduleAtFixedRate(() -> {
            try {
                send(NodeProtocol.T_PING, null);
            } catch (Exception e) {
                closeSocket();
            }
        }, config.pingIntervalMs, config.pingIntervalMs, TimeUnit.MILLISECONDS);
    }

    /** 预鉴权连接的一次性请求：无读线程，内联读帧直到匹配 RES（注册流程专用）。 */
    private JsonNode reqOnce(String op, ObjectNode p) throws IOException {
        long id = idGen.incrementAndGet();
        ObjectNode request = NodeJson.object();
        request.put("id", id);
        request.put("op", op);
        request.set("p", p);
        send(NodeProtocol.T_REQ, NodeJson.write(request));
        long deadline = System.currentTimeMillis() + config.requestTimeoutMs;
        while (System.currentTimeMillis() < deadline) {
            socket.setSoTimeout((int) Math.max(1, deadline - System.currentTimeMillis()));
            NodeProtocol.Frame frame = NodeProtocol.read(in);
            if (frame == null) {
                throw new IOException("服务器关闭连接");
            }
            if (frame.type == NodeProtocol.T_ERR) {
                throw new NodeAuthException(NodeJson.strOf(NodeJson.read(frame.payload), "m", "ERR"));
            }
            if (frame.type == NodeProtocol.T_RES) {
                JsonNode res = NodeJson.read(frame.payload);
                if (res.path("id").asLong(-1) == id) {
                    return res;
                }
            }
        }
        throw new java.net.SocketTimeoutException("请求 " + op + " 超时");
    }

    private void send(int type, byte[] payload) throws IOException {
        DataOutputStream stream = out;
        if (stream == null) {
            throw new IOException("连接未建立");
        }
        synchronized (outLock) {
            NodeProtocol.write(stream, type, payload);
        }
    }

    private void closeSocket() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // 关闭失败即视为断开
        }
        socket = null;
        in = null;
        out = null;
        authed = false;
    }

    private void log(String message) {
        System.out.println("[horizon-node] " + message);
    }
}
