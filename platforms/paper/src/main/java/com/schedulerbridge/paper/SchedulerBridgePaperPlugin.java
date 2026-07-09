package com.schedulerbridge.paper;

import com.schedulerbridge.common.SchedulerBridge;
import org.bukkit.plugin.java.JavaPlugin;

public final class SchedulerBridgePaperPlugin extends JavaPlugin {
    private SchedulerBridge bridge;

    @Override
    public void onEnable() {
        bridge = SchedulerBridge.create(new PaperSchedulerAdapter(this));
    }

    public SchedulerBridge bridge() {
        return bridge;
    }
}
