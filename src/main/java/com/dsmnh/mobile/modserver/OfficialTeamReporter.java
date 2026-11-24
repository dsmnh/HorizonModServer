package com.dsmnh.mobile.modserver;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * 官方队伍上报调度（契约 1.6.5 §8.8）：维护世界代次 gen 与代次内单调序号 seq，
 * 内容未变不重复上报，采集在主线程、序列化与发送在后台线程。
 *
 * <p>本类不依赖任何游戏类型，采集与发送通过 {@link Source}/{@link Sink} 注入，
 * 因此可在不加载 Minecraft 的单测里覆盖代次、序号与去重行为。
 */
public final class OfficialTeamReporter {

    /** 快照采集来源，约定只在服务器主线程调用。 */
    public interface Source {
        /** 世界是否已加载并具备可读的官方队伍数据。 */
        boolean ready();

        /** 世界标识；换图或恢复备份时必须变化。 */
        String worldRef();

        /** 采集全部官方队伍（主线程）。 */
        List<OfficialTeamSnapshot.Team> collect();
    }

    /** 上报通道，约定在后台线程调用。 */
    public interface Sink {
        void send(ObjectNode body);
    }

    private final Source source;
    private final Sink sink;

    /** 世界代次：每次主世界加载加一。 */
    private long gen;
    /** 代次内单调序号：每次实际投递加一。 */
    private long seq;
    private boolean dirty = true;
    private String lastFingerprint;
    private boolean lastReported;

    public OfficialTeamReporter(Source source, Sink sink) {
        this.source = source;
        this.sink = sink;
    }

    /** 主世界加载完成：代次加一、序号重置，并立即排一次上报。 */
    public synchronized void onWorldLoaded() {
        gen += 1;
        seq = 0;
        lastFingerprint = null;
        dirty = true;
    }

    /** 主世界卸载：只标记不完整，投影由后端保留（ready:false 不删投影）。 */
    public synchronized void onWorldUnloaded() {
        lastFingerprint = null;
        dirty = true;
    }

    /** 官方队伍发生变化：只置脏，由周期任务合并成一次上报。 */
    public synchronized void markDirty() {
        dirty = true;
    }

    /**
     * 节点重新连上后端：后端可能重启或换库，投影需要重新建立，
     * 因此清掉去重指纹、强制一次完整上报（代次与序号继续沿用本地值）。
     */
    public synchronized void onConnectionEstablished() {
        lastFingerprint = null;
        lastReported = false;
        dirty = true;
    }

    public synchronized long gen() {
        return gen;
    }

    public synchronized long seq() {
        return seq;
    }

    public synchronized boolean isDirty() {
        return dirty;
    }

    /**
     * 在主线程采集并组装载荷；内容与上次一致且代次未变则返回 null（不投递）。
     * 返回的对象可安全交给后台线程序列化，因为快照只含不可变数据。
     */
    public synchronized OfficialTeamSnapshot take() {
        if (!dirty) {
            return null;
        }
        boolean ready = source.ready();
        List<OfficialTeamSnapshot.Team> teams = ready
                ? source.collect()
                : java.util.Collections.<OfficialTeamSnapshot.Team>emptyList();
        OfficialTeamSnapshot snapshot = new OfficialTeamSnapshot(
                source.worldRef(), gen, seq + 1, ready, teams);
        String fingerprint = snapshot.fingerprint();
        if (fingerprint.equals(lastFingerprint) && ready == lastReported) {
            dirty = false;
            return null;
        }
        seq += 1;
        lastFingerprint = fingerprint;
        lastReported = ready;
        dirty = false;
        return snapshot;
    }

    /** 后台线程调用：序列化并投递。 */
    public void dispatch(OfficialTeamSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        sink.send(snapshot.toJson());
    }
}
