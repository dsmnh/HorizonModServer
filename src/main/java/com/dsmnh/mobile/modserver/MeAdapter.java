package com.dsmnh.mobile.modserver;

import com.dsmnh.mobile.modserver.node.NodeJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * ME 设备登记与 AE2 查询（契约 1.5.0 §8.5、1.6.0 下行 me.query）。
 * 本仓库无 AE2 与监控器方块编译依赖，注册表由 NodeApi 与游戏内命令维护。
 * 查询整体在主线程执行：getStorageList() 会触发 ItemList.resetStatus（企划书 09 已知问题）。
 */
public final class MeAdapter {
    private static final Logger LOG = LogManager.getLogger("dsmnhmobile");
    private static final ConcurrentMap<String, Device> DEVICES = new ConcurrentHashMap<String, Device>();
    /** 配置目录下的设备清单文件，由主类在 preInit 设置；为空时仅保存在内存。 */
    private static volatile File storeFile;

    /** 已登记设备：坐标为 AE2 网格方块位置，不主动加载维度或区块。 */
    public static final class Device {
        public final String deviceId;
        public final int dim;
        public final int x;
        public final int y;
        public final int z;
        public final String owner;
        public final String kind;

        Device(String deviceId, int dim, int x, int y, int z, String owner, String kind) {
            this.deviceId = deviceId;
            this.dim = dim;
            this.x = x;
            this.y = y;
            this.z = z;
            this.owner = owner;
            this.kind = kind;
        }
    }

    private MeAdapter() {
    }

    public static void register(String deviceId, int dim, int x, int y, int z,
                                String owner, String kind) {
        if (put(deviceId, dim, x, y, z, owner, kind)) {
            save();
        }
    }

    public static void unregister(String deviceId) {
        if (deviceId != null && DEVICES.remove(deviceId) != null) {
            save();
        }
    }

    /** 登记但不落盘，供 load() 恢复时使用。 */
    private static boolean put(String deviceId, int dim, int x, int y, int z,
                               String owner, String kind) {
        if (deviceId == null || deviceId.isEmpty()) {
            return false;
        }
        DEVICES.put(deviceId, new Device(deviceId, dim, x, y, z,
                owner == null ? "" : owner, kind == null ? "monitor" : kind));
        return true;
    }

    /** 配置目录下的清单文件；为空时设备登记只保存在内存。 */
    public static void setStore(File file) {
        storeFile = file;
    }

    /** 从清单恢复设备；文件缺失或损坏时保持当前内存状态。 */
    public static synchronized void load() {
        File file = storeFile;
        if (file == null || !file.isFile()) {
            return;
        }
        try {
            JsonNode root = NodeJson.MAPPER.readTree(file);
            JsonNode devices = root.path("devices");
            if (!devices.isArray()) {
                return;
            }
            DEVICES.clear();
            for (JsonNode n : devices) {
                put(n.path("deviceId").asText(""), n.path("dim").asInt(0),
                        n.path("x").asInt(0), n.path("y").asInt(0), n.path("z").asInt(0),
                        n.path("owner").asText(""), n.path("kind").asText("monitor"));
            }
            LOG.info("已恢复 {} 个 ME 设备登记", DEVICES.size());
        } catch (Throwable t) {
            LOG.warn("ME 设备清单加载失败：{}", t.toString());
        }
    }

    /** 写回清单；失败只记录日志，不影响内存中的登记。 */
    public static synchronized void save() {
        File file = storeFile;
        if (file == null) {
            return;
        }
        try {
            ObjectNode root = NodeJson.object();
            ArrayNode devices = root.putArray("devices");
            for (Device d : DEVICES.values()) {
                devices.addObject().put("deviceId", d.deviceId).put("dim", d.dim)
                        .put("x", d.x).put("y", d.y).put("z", d.z)
                        .put("owner", d.owner).put("kind", d.kind);
            }
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            Files.write(file.toPath(), NodeJson.write(root));
        } catch (Throwable t) {
            LOG.warn("ME 设备清单保存失败：{}", t.toString());
        }
    }

    public static Device find(String deviceId) {
        return deviceId == null ? null : DEVICES.get(deviceId);
    }

    public static Collection<Device> devices() {
        return DEVICES.values();
    }

    /** 主线程执行 AE2 设备物品查询；返回 {items:[...], error?}，失败不伪装为空结果成功。 */
    public static ObjectNode query(String deviceId) {
        ObjectNode out = NodeJson.object();
        ArrayNode items = out.putArray("items");
        Device device = find(deviceId);
        if (device == null) {
            out.put("error", "设备未登记：" + deviceId);
            return out;
        }
        try {
            Class<?> proxyable = Class.forName("appeng.me.helpers.IGridProxyable");
            World world = DimensionManager.getWorld(device.dim);
            if (world == null) {
                out.put("error", "维度未加载：" + device.dim);
                return out;
            }
            TileEntity tile = world.getTileEntity(device.x, device.y, device.z);
            if (tile == null || !proxyable.isInstance(tile)) {
                out.put("error", "目标坐标不是已加载的 AE2 网格设备");
                return out;
            }
            Object proxy = noArg(tile, "getProxy");
            Object storage = noArg(proxy, "getStorage");
            Object monitor = noArg(storage, "getItemInventory");
            Object list = noArg(monitor, "getStorageList");
            if (!(list instanceof Iterable)) {
                out.put("error", "AE2 物品列表格式未识别");
                return out;
            }
            for (Object stack : (Iterable<?>) list) {
                ObjectNode row = items.addObject();
                Object itemStack = call(stack, "getItemStack");
                if (itemStack instanceof ItemStack) {
                    ItemStack is = (ItemStack) itemStack;
                    row.put("displayName", is.getDisplayName());
                    row.put("meta", is.getItemDamage());
                }
                Object item = call(stack, "getItem");
                row.put("item", item == null ? "" : Item.itemRegistry.getNameForObject(item));
                Object count = call(stack, "getStackSize");
                row.put("count", count instanceof Number ? ((Number) count).longValue() : 0L);
                Object craftable = call(stack, "isCraftable");
                row.put("craftable", Boolean.TRUE.equals(craftable));
            }
        } catch (Throwable t) {
            items.removeAll();
            out.put("error", "AE2 查询未完成：" + t);
            LOG.warn("me.query 执行失败（{}）：{}", deviceId, t.toString());
        }
        return out;
    }

    private static Object noArg(Object target, String method) throws Exception {
        if (target == null) {
            throw new IllegalStateException("AE2 反射链中断：" + method);
        }
        Method m = target.getClass().getMethod(method);
        return m.invoke(target);
    }

    private static Object call(Object target, String method) {
        if (target == null) {
            return null;
        }
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
