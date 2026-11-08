package kaptainwutax.seedcrackerX.mixin;

import com.seedfinding.mcfeature.structure.RegionStructure.Data;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import kaptainwutax.seedcrackerX.SeedCracker;
import kaptainwutax.seedcrackerX.config.Config;
import kaptainwutax.seedcrackerX.config.StructureSave;
import kaptainwutax.seedcrackerX.cracker.DataAddedEvent;
import kaptainwutax.seedcrackerX.cracker.HashedSeedData;
import kaptainwutax.seedcrackerX.cracker.bedrock.BedrockCollector;
import kaptainwutax.seedcrackerX.finder.FinderQueue;
import kaptainwutax.seedcrackerX.finder.ReloadFinders;
import kaptainwutax.seedcrackerX.util.Compat;
import kaptainwutax.seedcrackerX.util.Database;
import kaptainwutax.seedcrackerX.util.Log;
import kaptainwutax.seedcrackerX.xray.OreXrayManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.ClickEvent.RunCommand;
import net.minecraft.network.chat.HoverEvent.ShowText;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
   @Shadow
   private ClientLevel level;
   @Unique
   private static final Set<String> PROMPTED_CONNECTIONS = new HashSet<>();

   @Shadow
   public abstract Connection getConnection();

   @Inject(method = "handleLevelChunkWithLight", at = @At("TAIL"))
   private void onChunkData(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
      int chunkX = packet.x();
      int chunkZ = packet.z();
      FinderQueue.get().onChunkData(this.level, new ChunkPos(chunkX, chunkZ));
      BedrockCollector.get().onChunkData(this.level, new ChunkPos(chunkX, chunkZ));
   }

   @Inject(method = "handleLogin", at = @At("TAIL"))
   public void onGameJoin(ClientboundLoginPacket packet, CallbackInfo ci) {
      BedrockCollector.get().reset();
      this.newDimension(new HashedSeedData(packet.commonPlayerSpawnInfo().seed()), false);
      this.tryDatabase();
      List<Data<?>> preloaded = StructureSave.loadStructures();
      if (!preloaded.isEmpty()) {
         Log.warn("foundRestorableStructures", preloaded.size());
      }
   }

   @Inject(method = "handleRespawn", at = @At("TAIL"))
   public void onPlayerRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
      this.newDimension(new HashedSeedData(packet.commonPlayerSpawnInfo().seed()), true);
      this.tryDatabase();

      try {
         OreXrayManager.INSTANCE.loadSettings();
         OreXrayManager.INSTANCE.clearCache();
      } catch (Throwable var4) {
      }
   }

   @Unique
   private void newDimension(HashedSeedData hashedSeedData, boolean dimensionChange) {
      DimensionType dimension = Minecraft.getInstance().level.dimensionType();
      ReloadFinders.reloadHeight(dimension.minY(), dimension.minY() + dimension.logicalHeight());
      if (SeedCracker.get().getDataStorage().addHashedSeedData(hashedSeedData, DataAddedEvent.POKE_BIOMES) && Config.get().active && dimensionChange) {
         Log.error(Log.translate("fetchedHashedSeed"));
         if (Config.get().debug) {
            Log.error("Hashed seed [" + hashedSeedData.getHashedSeed() + "]");
         }
      }
   }

   @Unique
   private void tryDatabase() {
      String connection = this.getConnection().getRemoteAddress().toString();
      if (!PROMPTED_CONNECTIONS.contains(connection)) {
         String serverIp = this.extractServerIp(connection);
         String serverHost = this.extractServerHost(connection);
         Database.fetchSeedFromBackendAsync(serverIp, serverHost).thenAccept(record -> {
            if (record != null) {
               PROMPTED_CONNECTIONS.add(connection);
               Minecraft.getInstance().execute(() -> this.promptSeed(record, "后端"));
            }
         }).exceptionally(throwable -> null);
      }
   }

   @Unique
   private String extractServerIp(String connection) {
      try {
         if (this.getConnection().getRemoteAddress() instanceof InetSocketAddress inet && inet.getAddress() != null) {
            return inet.getAddress().getHostAddress();
         }
      } catch (Throwable var5) {
      }

      int slash = connection.lastIndexOf(47);
      String afterSlash = slash >= 0 ? connection.substring(slash + 1) : connection;
      int colon = afterSlash.lastIndexOf(58);
      return colon >= 0 ? afterSlash.substring(0, colon) : afterSlash;
   }

   @Unique
   private String extractServerHost(String connection) {
      try {
         if (this.getConnection().getRemoteAddress() instanceof InetSocketAddress inet) {
            String host = inet.getHostName();
            if (host != null && !host.isBlank()) {
               int colon = host.lastIndexOf(58);
               return colon >= 0 ? host.substring(0, colon) : host;
            }
         }
      } catch (Throwable var6) {
      }

      int slash = connection.indexOf(47);
      if (slash > 0) {
         String beforeSlash = connection.substring(0, slash);
         int colon = beforeSlash.lastIndexOf(58);
         return colon >= 0 ? beforeSlash.substring(0, colon) : beforeSlash;
      } else {
         return "";
      }
   }

   @Unique
   private void promptSeed(Database.SeedRecord record, String source) {
      String seedStr = String.valueOf(record.seed);
      String version = record.version != null && !record.version.isBlank() ? record.version : "未知";
      String world = record.dimension != null && !record.dimension.isBlank() ? record.dimension : "未知";
      MutableComponent msg = Component.literal("检测到服务器种子：").withStyle(ChatFormatting.YELLOW);
      msg.append(Component.literal(seedStr).withStyle(ChatFormatting.GREEN));
      msg.append(Component.literal("  版本号：").withStyle(ChatFormatting.AQUA));
      msg.append(Component.literal(version).withStyle(ChatFormatting.WHITE));
      msg.append(Component.literal("  世界：").withStyle(ChatFormatting.AQUA));
      msg.append(Component.literal(world).withStyle(ChatFormatting.WHITE));
      msg.append(Component.literal("\n是否直接设置？").withStyle(ChatFormatting.WHITE));
      String versionArg = record.version != null && !record.version.isBlank() ? " " + record.version : "";
      msg.append(
         Component.literal(" [设置] ")
            .withStyle(
               style -> style.withColor(ChatFormatting.GREEN)
                  .withBold(true)
                  .withClickEvent(new RunCommand("/ov sz " + seedStr + versionArg))
                  .withHoverEvent(new ShowText(Component.literal("点击设置种子并开启矿物透视")))
            )
      );
      msg.append(Component.literal("[不使用]").withStyle(ChatFormatting.RED));
      Minecraft.getInstance().execute(() -> {
         try {
            Compat.sendSystemMessage(msg);
         } catch (Throwable var2x) {
         }
      });
   }
}
