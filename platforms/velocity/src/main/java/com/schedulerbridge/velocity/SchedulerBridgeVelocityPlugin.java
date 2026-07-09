package com.schedulerbridge.velocity;

import com.google.inject.Inject;
import com.schedulerbridge.common.SchedulerBridge;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;

@Plugin(
    id = "scheduler_bridge",
    name = "Scheduler Bridge",
    version = "0.1.0-SNAPSHOT",
    description = "Cross-platform scheduler bridge for Minecraft server and mod loaders."
)
public final class SchedulerBridgeVelocityPlugin {
    private final ProxyServer proxyServer;
    private final SchedulerBridge bridge;

    @Inject
    public SchedulerBridgeVelocityPlugin(ProxyServer proxyServer) {
        this.proxyServer = proxyServer;
        this.bridge = SchedulerBridge.create(new VelocitySchedulerAdapter(proxyServer, this));
    }

    public ProxyServer proxyServer() {
        return proxyServer;
    }

    public SchedulerBridge bridge() {
        return bridge;
    }
}
