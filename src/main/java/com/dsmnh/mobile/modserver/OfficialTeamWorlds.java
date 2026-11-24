package com.dsmnh.mobile.modserver;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.event.world.WorldEvent;

/**
 * 主世界加载/卸载驱动官方队伍上报的代次（契约 1.6.5 §8.8）。
 *
 * <p>GTNHLib 的 {@code TeamDataSaver} 在 {@code WorldEvent.Load}(dim 0) 时清空并重载队伍表，
 * 因此这里只标记「代次加一 + 待上报」，实际采集交给随后的周期任务，
 * 不依赖两个监听器的注册顺序。
 */
public final class OfficialTeamWorlds {

    private final OfficialTeamReporter reporter;

    public OfficialTeamWorlds(OfficialTeamReporter reporter) {
        this.reporter = reporter;
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        if (event.world == null || event.world.isRemote || event.world.provider.dimensionId != 0) {
            return;
        }
        reporter.onWorldLoaded();
        HorizonModServer.LOG.info("主世界已加载，官方队伍投影进入第 {} 代", reporter.gen());
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.world == null || event.world.isRemote || event.world.provider.dimensionId != 0) {
            return;
        }
        reporter.onWorldUnloaded();
    }

    @SubscribeEvent
    public void onWorldSave(WorldEvent.Save event) {
        if (event.world == null || event.world.isRemote || event.world.provider.dimensionId != 0) {
            return;
        }
        // 保存后队伍表可能刚被写盘，只置脏；周期任务会合并成一次上报。
        reporter.markDirty();
    }
}
