package com.schedulerbridge.spigot;

import com.schedulerbridge.common.SchedulerBridge;
import org.bukkit.plugin.java.JavaPlugin;

public final class SchedulerBridgeSpigotPlugin extends JavaPlugin {
    private SchedulerBridge bridge;

    @Override
    public void onEnable() {
        bridge = SchedulerBridge.create(new SpigotSchedulerAdapter(this));
    }

    public SchedulerBridge bridge() {
        return bridge;
    }
}
