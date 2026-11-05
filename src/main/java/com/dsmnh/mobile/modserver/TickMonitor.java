package com.dsmnh.mobile.modserver;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MSPT/TPS 计时（契约 1.5.0 §8.6 metrics.report 的数据源）。
 * 沿用 AEMobile TickStats 的口径：Forge 原生事件、每维度每 tick 2 次 nanoTime、
 * 5 秒滚动窗口（6 个 1 秒桶），服务端不存历史。
 */
public final class TickMonitor {
    private static final long BUDGET_NS = 50_000_000L; // 50ms = 满 tick
    private static final int WINDOW_SEC = 5;

    private long tickStart;
    private long serverTickNs;
    private final Map<Integer, Long> worldTickNs = new ConcurrentHashMap<>();
    private final Map<Integer, long[]> worldBuckets = new ConcurrentHashMap<>();
    private long[] serverBuckets = new long[WINDOW_SEC + 1];
    private int bucketIndex = -1;
    private long bucketSecond = 0;
    private long lastReportAt = 0;

    public void register() {
        FMLRegistration.register(this);
    }

    private static final class FMLRegistration {
        static void register(TickMonitor monitor) {
            cpw.mods.fml.common.FMLCommonHandler.instance().bus().register(monitor);
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            tickStart = System.nanoTime();
            return;
        }
        serverTickNs += System.nanoTime() - tickStart;
        rollBucketIfNeeded();
        serverBuckets[bucketIndex] += serverTickNs;
        serverTickNs = 0;
    }

    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            worldTickNs.put(event.world.provider.dimensionId, System.nanoTime());
            return;
        }
        Integer dim = event.world.provider.dimensionId;
        Long start = worldTickNs.remove(dim);
        if (start != null) {
            rollBucketIfNeeded();
            long[] buckets = worldBuckets.computeIfAbsent(dim, k -> new long[WINDOW_SEC + 1]);
            buckets[bucketIndex] += System.nanoTime() - start;
        }
    }

    private void rollBucketIfNeeded() {
        long second = System.currentTimeMillis() / 1000;
        if (second != bucketSecond) {
            bucketSecond = second;
            bucketIndex = (bucketIndex + 1) % (WINDOW_SEC + 1);
            serverBuckets[bucketIndex] = 0;
            for (long[] buckets : worldBuckets.values()) {
                buckets[bucketIndex] = 0;
            }
        }
    }

    /** 5 秒窗口聚合帧（契约 §8.6 metrics.report 载荷）。返回 null 表示窗口未满。 */
    public ObjectNode snapshot() {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return null;
        }
        long now = System.currentTimeMillis() / 1000;
        long windowStart = bucketSecond - WINDOW_SEC;
        long serverNs = 0;
        int samples = 0;
        for (int i = 0; i <= WINDOW_SEC; i++) {
            long second = bucketSecond - (bucketIndex - i + WINDOW_SEC + 1) % (WINDOW_SEC + 1);
            if (second >= windowStart && second < now) {
                serverNs += serverBuckets[i];
                samples++;
            }
        }
        if (samples == 0) {
            return null;
        }
        double mspt = serverNs / (double) samples / 1_000_000.0;
        double maxMspt = 0;
        for (int i = 0; i <= WINDOW_SEC; i++) {
            long second = bucketSecond - (bucketIndex - i + WINDOW_SEC + 1) % (WINDOW_SEC + 1);
            if (second >= windowStart && second < now) {
                maxMspt = Math.max(maxMspt, serverBuckets[i] / 1_000_000.0);
            }
        }
        double tps = mspt <= 50 ? 20 : Math.min(20, 1000.0 / mspt);

        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("mspt", round(mspt));
        out.put("max", round(maxMspt));
        out.put("tps", round(tps));
        out.put("players", server.getConfigurationManager().playerEntityList.size());
        ArrayNode dims = out.putArray("dims");
        for (WorldServer world : server.worldServers) {
            int dimId = world.provider.dimensionId;
            ObjectNode d = dims.addObject();
            d.put("id", dimId);
            d.put("name", "");
            d.put("players", world.playerEntities.size());
            long[] buckets = worldBuckets.get(dimId);
            if (buckets != null) {
                long wns = 0;
                int ws = 0;
                for (int i = 0; i <= WINDOW_SEC; i++) {
                    long second = bucketSecond - (bucketIndex - i + WINDOW_SEC + 1) % (WINDOW_SEC + 1);
                    if (second >= windowStart && second < now) {
                        wns += buckets[i];
                        ws++;
                    }
                }
                if (ws > 0) {
                    d.put("mspt", round(wns / (double) ws / 1_000_000.0));
                }
            }
        }
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
