package com.dsmnh.mobile.modserver;

import com.dsmnh.mobile.modserver.node.NodeJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Base64;
import java.util.List;

/**
 * BetterQuesting 队伍进度快照（契约 1.6.0 §8.4）。
 * 反射 PartyManager/LifeDatabase 的 NBT 序列化，快照以 Base64 NBT 存入 payload。
 * 覆盖写回与备份均在主线程执行；API 未确认时输出结构化空快照并带 error，不静默成功。
 */
public final class BqAdapter {
    public static final String SCHEMA = "bq.party.snapshot/1";
    private static final Logger LOG = LogManager.getLogger("dsmnhmobile");
    private static final String PARTY_MANAGER = "betterquesting.questing.party.PartyManager";
    private static final String LIFE_DATABASE = "betterquesting.storage.LifeDatabase";
    private static volatile File backupDir;

    private BqAdapter() {
    }

    /** 配置目录下的备份根目录，由主类在 preInit 设置。 */
    public static void setBackupDir(File dir) {
        backupDir = dir;
    }

    /** 源节点导出（主线程）：返回可直接作为 bq.snapshot.put 载荷的快照。 */
    public static ObjectNode exportSnapshot(JsonNode p) {
        ObjectNode out = NodeJson.object();
        out.put("schema", SCHEMA);
        out.put("teamId", p.path("teamId").asLong(0));
        out.put("batchId", p.path("batchId").asText(""));
        out.put("snapshotId", p.path("snapshotId").asText(""));
        out.put("sourceNode", p.path("sourceNode").asLong(0));
        out.put("targetNode", p.path("targetNode").asLong(0));
        out.put("capturedAt", System.currentTimeMillis() / 1000);
        out.put("verified", false);
        out.put("nbt", "");
        ArrayNode parties = out.putArray("parties");
        ArrayNode coverage = out.putArray("coverage");
        ArrayNode pending = out.putArray("pendingVerification");
        pending.add("teamId 与 BQ Party 的映射");
        pending.add("任务完成状态与奖励领取状态的覆盖");
        pending.add("覆盖前备份完整性");
        try {
            Class.forName(PARTY_MANAGER);
            NBTTagCompound wrapper = capture();
            out.put("nbt", encode(wrapper));
            NBTTagList list = wrapper.getTagList("parties", 10);
            for (int i = 0; i < list.tagCount(); i++) {
                parties.add(list.getCompoundTagAt(i).getInteger("partyID"));
            }
            coverage.add("PartyManager.writeToNBT 全量队伍结构");
            coverage.add("LifeDatabase.writeToNBT 生命数");
            out.put("verified", true);
        } catch (Throwable t) {
            out.put("error", "BQ 导出未完成，待真服验证：" + t);
            LOG.warn("BQ 导出失败，回传结构化空快照：{}", t.toString());
        }
        return out;
    }

    /** 目标节点导入（主线程）：校验快照后备份并合并写回；返回 null 表示成功。 */
    public static String importSnapshot(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            return "snapshot payload 缺失";
        }
        if (!SCHEMA.equals(payload.path("schema").asText(""))) {
            return "snapshot schema 不匹配";
        }
        if (!payload.path("verified").asBoolean(false)) {
            return "源节点快照未验证：" + payload.path("error").asText("BQ API 未确认");
        }
        String error = payload.path("error").asText("");
        if (!error.isEmpty()) {
            return "源节点快照带错误：" + error;
        }
        String nbt = payload.path("nbt").asText("");
        if (nbt.isEmpty()) {
            return "快照 NBT 为空";
        }
        try {
            NBTTagCompound wrapper = decode(nbt);
            String backupError = backup(payload.path("batchId").asText(""));
            if (backupError != null) {
                return backupError;
            }
            writeBack(wrapper);
            LOG.info("BQ 导入已执行；待真服验证：teamId 与 Party 映射、任务完成及奖励领取状态覆盖");
            return null;
        } catch (Throwable t) {
            return "BQ 导入失败：" + t;
        }
    }

    private static NBTTagCompound capture() throws Exception {
        Class<?> pmClass = Class.forName(PARTY_MANAGER);
        Object pm = pmClass.getField("INSTANCE").get(null);
        NBTTagList partyList = new NBTTagList();
        Method writeParties = pmClass.getMethod("writeToNBT", NBTTagList.class, List.class);
        writeParties.invoke(pm, partyList, null);

        Class<?> ldClass = Class.forName(LIFE_DATABASE);
        Object ld = ldClass.getField("INSTANCE").get(null);
        NBTTagCompound lives = new NBTTagCompound();
        Method writeLives = ldClass.getMethod("writeToNBT", NBTTagCompound.class, List.class);
        writeLives.invoke(ld, lives, null);

        NBTTagCompound wrapper = new NBTTagCompound();
        wrapper.setTag("parties", partyList);
        wrapper.setTag("lives", lives);
        wrapper.setInteger("schema", 1);
        return wrapper;
    }

    private static void writeBack(NBTTagCompound wrapper) throws Exception {
        NBTTagList parties = wrapper.getTagList("parties", 10);
        NBTTagCompound lives = wrapper.getCompoundTag("lives");
        Class<?> pmClass = Class.forName(PARTY_MANAGER);
        Object pm = pmClass.getField("INSTANCE").get(null);
        pmClass.getMethod("readFromNBT", NBTTagList.class, boolean.class)
                .invoke(pm, parties, true);
        Class<?> ldClass = Class.forName(LIFE_DATABASE);
        Object ld = ldClass.getField("INSTANCE").get(null);
        ldClass.getMethod("readFromNBT", NBTTagCompound.class, boolean.class)
                .invoke(ld, lives, true);
    }

    /** 覆盖前留存目标服现状；备份失败即拒绝覆盖，避免不可恢复的数据丢失。 */
    private static String backup(String batchId) {
        File root = backupDir;
        if (root == null) {
            return "备份目录未设置，拒绝覆盖";
        }
        try {
            NBTTagCompound current = capture();
            File dir = new File(root, "bq-backups");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return "备份目录创建失败";
            }
            String safe = batchId.replaceAll("[^A-Za-z0-9_-]", "_");
            File file = new File(dir, "bq-backup-" + safe + "-"
                    + System.currentTimeMillis() / 1000 + ".json");
            ObjectNode body = NodeJson.object();
            body.put("schema", SCHEMA);
            body.put("nbt", encode(current));
            Files.write(file.toPath(), NodeJson.write(body));
            return null;
        } catch (Throwable t) {
            return "覆盖前备份失败：" + t;
        }
    }

    private static String encode(NBTTagCompound nbt) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(bos)) {
            CompressedStreamTools.write(nbt, dos);
        }
        return Base64.getEncoder().encodeToString(bos.toByteArray());
    }

    private static NBTTagCompound decode(String encoded) throws Exception {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes))) {
            return CompressedStreamTools.read(dis);
        }
    }
}
