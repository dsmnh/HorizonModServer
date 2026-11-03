package com.dsmnh.mobile.modserver.node;

import java.nio.file.Path;

/** 节点客户端配置：后端地址、自报名与凭证位置（默认端口对齐契约 §1）。 */
public final class NodeConfig {
    public String backendHost = "127.0.0.1";
    public int backendPort = 44443;
    /** 注册时自报名称，仅作列表展示；显示名以后端管理为准。 */
    public String nodeName = "";
    /** 组件标识，HELLO.app 使用。 */
    public String app = "HorizonModServer/0.1.0";
    public Path credentialFile;
    /** 请求超时（毫秒）。 */
    public long requestTimeoutMs = 15_000;
    /** 保活间隔与重连退避（契约 §7.3）。 */
    public int pingIntervalMs = 20_000;
    public int backoffInitialMs = 1_000;
    public int backoffMaxMs = 15_000;

    public static NodeConfig defaults(Path credentialFile) {
        NodeConfig config = new NodeConfig();
        config.credentialFile = credentialFile;
        return config;
    }
}
