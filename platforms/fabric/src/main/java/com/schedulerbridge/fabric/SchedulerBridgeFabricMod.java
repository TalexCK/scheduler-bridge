package com.schedulerbridge.fabric;

import com.schedulerbridge.common.SchedulerBridge;
import net.fabricmc.api.ModInitializer;

import java.util.concurrent.Executor;

public final class SchedulerBridgeFabricMod implements ModInitializer {
    @Override
    public void onInitialize() {
    }

    public static SchedulerBridge createBridge(Executor serverExecutor) {
        return SchedulerBridge.create(new FabricSchedulerAdapter(serverExecutor));
    }
}
