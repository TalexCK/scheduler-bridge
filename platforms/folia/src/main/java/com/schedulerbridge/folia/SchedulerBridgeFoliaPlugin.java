package com.schedulerbridge.folia;

import com.schedulerbridge.common.SchedulerBridge;
import org.bukkit.plugin.java.JavaPlugin;

public final class SchedulerBridgeFoliaPlugin extends JavaPlugin {
    private SchedulerBridge bridge;

    @Override
    public void onEnable() {
        bridge = SchedulerBridge.create(new FoliaSchedulerAdapter(this));
    }

    public SchedulerBridge bridge() {
        return bridge;
    }
}
