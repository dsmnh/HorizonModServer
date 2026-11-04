package com.dsmnh.mobile.modserver;

import com.dsmnh.mobile.modserver.node.NodeApi;
import com.dsmnh.mobile.modserver.node.NodeClient;
import com.dsmnh.mobile.modserver.node.NodeConfig;
import com.dsmnh.mobile.modserver.node.NodeCredentialStore;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.common.config.Configuration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;

/**
 * Horizon 服务端模组：把本子服接入 Horizon 后端节点平面（协议 1.1.1）。
 * 阶段 1 范围：节点注册、游戏内出码（绑定/恢复）、队伍域代调用；
 * 不含聊天/ME/TPS（阶段 3/5/6，见企划书 08）。
 */
@Mod(modid = Tags.MODID, name = Tags.MODNAME, version = Tags.VERSION,
        acceptedMinecraftVersions = "[1.7.10]")
public final class HorizonModServer {
    public static final Logger LOG = LogManager.getLogger("dsmnhmobile");
    public static HorizonModServer INSTANCE;

    private MainThreadQueue queue;
    private NodeClient node;
    private String backendHost;
    private int backendPort;
    private String nodeName;
    private File credentialFile;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        INSTANCE = this;
        Configuration config = new Configuration(event.getSuggestedConfigurationFile());
        backendHost = config.get("node", "backendHost", "127.0.0.1",
                "Horizon 后端地址").getString();
        backendPort = config.get("node", "backendPort", 44443,
                "Horizon 后端节点平面端口").getInt();
        nodeName = config.get("node", "nodeName", "",
                "注册时自报名称（仅展示用）").getString();
        if (config.hasChanged()) {
            config.save();
        }
        credentialFile = new File(event.getModConfigurationDirectory(), "dsmnhmobile-node-credential.json");
        queue = new MainThreadQueue();
        queue.register();
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new NodeCommand());
    }

    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        NodeCredentialStore store = new NodeCredentialStore(credentialFile.toPath());
        if (store.load() == null) {
            LOG.info("尚无节点凭证：请由服主在后端控制台签发注册码后执行 /dsmnhmobile register <码>");
            return;
        }
        connect();
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        if (node != null) {
            node.close();
            node = null;
        }
    }

    /** 注册用的临时连接配置：与持久连接同源（host/port 来自 cfg，缺省端口不参与）。 */
    private NodeConfig configForRegistration() {
        NodeConfig config = NodeConfig.defaults(credentialFile.toPath());
        config.backendHost = backendHost;
        config.backendPort = backendPort;
        config.nodeName = nodeName;
        config.app = "HorizonModServer/" + Tags.VERSION;
        return config;
    }

    /** 已持凭证时建立持久连接（服务启动或注册成功后调用）。 */
    public synchronized void connect() {
        if (node != null) {
            node.close();
        }
        node = new NodeClient(configForRegistration(), new NodeCredentialStore(credentialFile.toPath()));
        node.listener(new NodeClient.Listener() {
            @Override
            public void onConnected(long nodeId) {
                LOG.info("已连接 Horizon 后端（节点 {}）", nodeId);
            }

            @Override
            public void onDisconnected(boolean willRetry) {
                if (willRetry) {
                    LOG.info("与后端断开，按退避自动重连");
                }
            }

            @Override
            public void onRevoked() {
                LOG.warn("节点凭证已失效（被撤销或版本不兼容），不再重连；"
                        + "重新接入请先删除 {} 后用新注册码 register", credentialFile.getName());
            }
        });
        node.start();
    }

    /** 用注册码注册本子服（阻塞调用，须在后台线程执行；成功后自动连接）。 */
    public synchronized long register(String code, String name) throws Exception {
        NodeConfig registerConfig = configForRegistration();
        NodeClient registerClient = new NodeClient(registerConfig,
                new NodeCredentialStore(credentialFile.toPath()));
        registerClient.listener(new NodeClient.Listener() {
            @Override
            public void onRevoked() {
                // 注册连接生命周期短，撤销类错误由 register() 异常路径处理
            }
        });
        try {
            long nodeId = registerClient.register(code, name == null || name.isEmpty()
                    ? nodeName : name);
            connect();
            return nodeId;
        } finally {
            registerClient.close();
        }
    }

    public NodeClient node() {
        return node;
    }

    public NodeApi api() {
        return node == null ? null : new NodeApi(node);
    }

    public MainThreadQueue queue() {
        return queue;
    }
}
