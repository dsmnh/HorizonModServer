package com.dsmnh.mobile.modserver;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 主线程任务队列：网络回调只入队，由 ServerTickEvent 排空执行；
 * 每 tick 8ms 预算（1.7.10 无 addScheduledTask，沿用 AEMobile 已验证模式，企划书 02/09）。
 */
public final class MainThreadQueue {
    private static final long BUDGET_NS = 8_000_000L;
    private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<Runnable>();

    public void register() {
        FMLCommonHandler.instance().bus().register(this);
    }

    /** 把「向玩家/控制台发消息」等游戏操作排回主线程。 */
    public void enqueue(Runnable task) {
        tasks.add(task);
    }

    public void enqueueChat(final EntityPlayer player, final String text) {
        enqueue(new Runnable() {
            @Override
            public void run() {
                player.addChatMessage(new ChatComponentText(text));
            }
        });
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        long deadline = System.nanoTime() + BUDGET_NS;
        Runnable task;
        while ((task = tasks.poll()) != null) {
            task.run();
            if (System.nanoTime() > deadline) {
                return;
            }
        }
    }

    /** 统一输出前缀与颜色。 */
    public static String line(String text) {
        return EnumChatFormatting.AQUA + "[Horizon]" + EnumChatFormatting.RESET + " " + text;
    }
}
