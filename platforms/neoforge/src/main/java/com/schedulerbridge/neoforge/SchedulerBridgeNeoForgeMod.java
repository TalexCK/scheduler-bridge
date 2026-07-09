package com.schedulerbridge.neoforge;

import com.schedulerbridge.common.SchedulerBridge;
import net.neoforged.fml.common.Mod;

import java.util.concurrent.Executor;

@Mod(SchedulerBridgeNeoForgeMod.MOD_ID)
public final class SchedulerBridgeNeoForgeMod {
    public static final String MOD_ID = "scheduler_bridge";

    public static SchedulerBridge createBridge(Executor serverExecutor) {
        return SchedulerBridge.create(new NeoForgeSchedulerAdapter(serverExecutor));
    }
}
