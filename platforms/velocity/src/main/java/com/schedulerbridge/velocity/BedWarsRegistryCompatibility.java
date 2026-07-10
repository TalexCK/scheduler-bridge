package com.schedulerbridge.velocity;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.ListTag;
import com.viaversion.nbt.tag.StringTag;
import com.viaversion.nbt.tag.Tag;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.ProtocolInfo;
import com.viaversion.viaversion.api.minecraft.RegistryEntry;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.api.protocol.packet.ClientboundPacketType;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.type.Types;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;

final class BedWarsRegistryCompatibility {
  private static final String PROTOCOL_CLASS =
      "com.viaversion.viaversion.protocols.v26_1to26_2.Protocol26_1To26_2";
  private static final String TARGET_REGISTRY = "minecraft:enchantment";
  private static final String TARGET_ENTRY = "classic_kb:detect";
  private final Logger logger;
  private final AtomicBoolean installed = new AtomicBoolean();
  private final AtomicBoolean installationFailureLogged = new AtomicBoolean();
  private final AtomicBoolean rewriteFailureLogged = new AtomicBoolean();

  BedWarsRegistryCompatibility(Logger logger) {
    this.logger = logger;
  }

  synchronized void ensureInstalled() {
    if (installed.get() || !Via.isLoaded()) {
      return;
    }
    try {
      Protocol<?, ?, ?, ?> protocol = findProtocol();
      if (protocol == null) {
        if (installationFailureLogged.compareAndSet(false, true)) {
          logger.warn(
              "Unable to install BedWars registry compatibility: ViaVersion 26.2 protocol is"
                  + " unavailable");
        }
        return;
      }
      appendHandler(protocol);
      installed.set(true);
      logger.info("Installed BedWars 1.21.11 to 26.2 registry compatibility");
    } catch (Exception | LinkageError error) {
      if (installationFailureLogged.compareAndSet(false, true)) {
        logger.warn("Unable to install BedWars registry compatibility: {}", describe(error));
      }
    }
  }

  private Protocol<?, ?, ?, ?> findProtocol() {
    for (Protocol<?, ?, ?, ?> protocol : Via.getManager().getProtocolManager().getProtocols()) {
      if (protocol.getClass().getName().equals(PROTOCOL_CLASS)) {
        return protocol;
      }
    }
    return null;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private void appendHandler(Protocol<?, ?, ?, ?> protocol) {
    Protocol rawProtocol = protocol;
    ClientboundPacketType registryData =
        (ClientboundPacketType)
            rawProtocol
                .getPacketTypesProvider()
                .unmappedClientboundType(State.CONFIGURATION, "REGISTRY_DATA");
    if (registryData == null) {
      throw new IllegalStateException("ViaVersion registry packet is unavailable");
    }
    rawProtocol.appendClientbound(registryData, this::rewritePacket);
  }

  private void rewritePacket(PacketWrapper wrapper) {
    try {
      if (!requiresCompatibility(wrapper.user().getProtocolInfo())) {
        return;
      }
      String registry = wrapper.get(Types.STRING, 0);
      if (!TARGET_REGISTRY.equals(registry) && !"enchantment".equals(registry)) {
        return;
      }
      RegistryEntry[] entries = wrapper.get(Types.REGISTRY_ENTRY_ARRAY, 0);
      RegistryEntry[] rewritten = rewriteEntries(entries);
      if (rewritten != entries) {
        wrapper.set(Types.REGISTRY_ENTRY_ARRAY, 0, rewritten);
      }
    } catch (Exception | LinkageError error) {
      if (rewriteFailureLogged.compareAndSet(false, true)) {
        logger.warn("Unable to rewrite BedWars registry data: {}", describe(error));
      }
    }
  }

  private static boolean requiresCompatibility(ProtocolInfo protocolInfo) {
    ProtocolVersion serverVersion = protocolInfo.serverProtocolVersion();
    ProtocolVersion clientVersion = protocolInfo.protocolVersion();
    ProtocolVersion targetServer = ProtocolVersion.getClosest("1.21.11");
    ProtocolVersion targetClient = ProtocolVersion.getClosest("26.2");
    return serverVersion != null
        && clientVersion != null
        && targetServer != null
        && targetClient != null
        && serverVersion.equalTo(targetServer)
        && clientVersion.equalTo(targetClient);
  }

  static RegistryEntry[] rewriteEntries(RegistryEntry[] entries) {
    if (entries == null) {
      return null;
    }
    for (int index = 0; index < entries.length; index++) {
      RegistryEntry entry = entries[index];
      if (entry == null
          || !TARGET_ENTRY.equals(entry.key())
          || !(entry.tag() instanceof CompoundTag)) {
        continue;
      }
      CompoundTag clientTag = ((CompoundTag) entry.tag()).copy();
      if (!rewriteDirectEntities(clientTag)) {
        return entries;
      }
      RegistryEntry[] rewritten = entries.clone();
      rewritten[index] = new RegistryEntry(entry.key(), clientTag);
      return rewritten;
    }
    return entries;
  }

  private static boolean rewriteDirectEntities(Tag tag) {
    if (tag instanceof CompoundTag) {
      CompoundTag compound = (CompoundTag) tag;
      boolean changed = rewriteDirectEntity(compound.getCompoundTag("direct_entity"));
      for (Tag child : compound.values()) {
        changed |= rewriteDirectEntities(child);
      }
      return changed;
    }
    if (tag instanceof ListTag<?>) {
      boolean changed = false;
      for (Tag child : (ListTag<?>) tag) {
        changed |= rewriteDirectEntities(child);
      }
      return changed;
    }
    return false;
  }

  private static boolean rewriteDirectEntity(CompoundTag directEntity) {
    if (directEntity == null
        || directEntity.contains("entity_type")
        || directEntity.contains("minecraft:entity_type")) {
      return false;
    }
    StringTag type = directEntity.getStringTag("type");
    if (type == null
        || !("player".equals(type.getValue()) || "minecraft:player".equals(type.getValue()))) {
      return false;
    }
    directEntity.remove("type");
    directEntity.put("minecraft:entity_type", type.copy());
    return true;
  }

  private static String describe(Throwable error) {
    String message = error.getMessage();
    return message == null || message.isEmpty()
        ? error.getClass().getSimpleName()
        : error.getClass().getSimpleName() + ": " + message;
  }
}
