package com.schedulerbridge.fabric;

import com.schedulerbridge.common.GameBridgeReporter;
import com.schedulerbridge.common.SchedulerBridge;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import net.fabricmc.api.ModInitializer;

public final class SchedulerBridgeFabricMod implements ModInitializer {
  private GameBridgeReporter reporter;

  @Override
  public void onInitialize() {
    try {
      reporter = GameBridgeReporter.fromEnvironment(System.err::println);
      registerEvent(
          "net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents",
          "SERVER_STARTED",
          "ServerStarted",
          arguments -> {
            reporter.updatePlayerCount(0);
            reporter.start();
          });
      registerEvent(
          "net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents",
          "SERVER_STOPPED",
          "ServerStopped",
          arguments -> reporter.close());
      registerEvent(
          "net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents",
          "JOIN",
          "Join",
          arguments -> reporter.playerJoined());
      registerEvent(
          "net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents",
          "DISCONNECT",
          "Disconnect",
          arguments -> reporter.playerLeft());
    } catch (Exception error) {
      System.err.println(error.getMessage());
    }
  }

  public static SchedulerBridge createBridge(Executor serverExecutor) {
    return SchedulerBridge.create(new FabricSchedulerAdapter(serverExecutor));
  }

  private static void registerEvent(
      String ownerName, String fieldName, String callbackName, Consumer<Object[]> action)
      throws Exception {
    Class<?> owner = Class.forName(ownerName);
    Object event = owner.getField(fieldName).get(null);
    Method register =
        Class.forName("net.fabricmc.fabric.api.event.Event").getMethod("register", Object.class);
    Class<?> callbackType = Class.forName(owner.getName() + "$" + callbackName);
    Object callback =
        Proxy.newProxyInstance(
            callbackType.getClassLoader(),
            new Class<?>[] {callbackType},
            (proxy, method, arguments) -> {
              if (method.getDeclaringClass() == Object.class) {
                if (method.getName().equals("toString")) {
                  return "SchedulerBridgeFabricEventCallback";
                }
                if (method.getName().equals("hashCode")) {
                  return System.identityHashCode(proxy);
                }
                return arguments != null && arguments.length > 0 && proxy == arguments[0];
              }
              action.accept(arguments == null ? new Object[0] : arguments);
              return null;
            });
    register.invoke(event, callback);
  }
}
