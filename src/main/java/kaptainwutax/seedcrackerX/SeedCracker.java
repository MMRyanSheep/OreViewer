package kaptainwutax.seedcrackerX;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import kaptainwutax.seedcrackerX.api.SeedCrackerAPI;
import kaptainwutax.seedcrackerX.automine.AutoMineManager;
import kaptainwutax.seedcrackerX.buff.BuffManager;
import kaptainwutax.seedcrackerX.config.Config;
import kaptainwutax.seedcrackerX.cracker.storage.DataStorage;
import kaptainwutax.seedcrackerX.finder.FinderQueue;
import kaptainwutax.seedcrackerX.fly.FlyManager;
import kaptainwutax.seedcrackerX.guidance.GuidanceManager;
import kaptainwutax.seedcrackerX.init.ClientCommands;
import kaptainwutax.seedcrackerX.util.ProgressDisplay;
import kaptainwutax.seedcrackerX.xray.OreXrayManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.EndTick;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

public class SeedCracker implements ModInitializer {
   public static final Logger LOGGER = LogUtils.getLogger();
   public static final ArrayList<SeedCrackerAPI> entrypoints = new ArrayList<>();
   private static SeedCracker INSTANCE;
   private final DataStorage dataStorage = new DataStorage();

   public static SeedCracker get() {
      return INSTANCE;
   }

   public void onInitialize() {
      INSTANCE = this;
      Config.load();
      OreXrayManager.INSTANCE.loadSettings();
      BuffManager.INSTANCE.loadFromConfig();
      Features.init(Config.get().getVersion());
      FabricLoader.getInstance()
         .getEntrypointContainers("seedcrackerx", SeedCrackerAPI.class)
         .forEach(entrypoint -> entrypoints.add((SeedCrackerAPI)entrypoint.getEntrypoint()));
      FinderQueue.registerEvents();
      ClientTickEvents.END_CLIENT_TICK.register((EndTick)client -> ProgressDisplay.onClientTick());
      ClientTickEvents.END_CLIENT_TICK.register((EndTick)client -> AutoMineManager.INSTANCE.onClientTick());
      ClientTickEvents.END_CLIENT_TICK.register((EndTick)client -> BuffManager.INSTANCE.tick());
      ClientTickEvents.END_CLIENT_TICK.register((EndTick)client -> FlyManager.tick());
      ClientCommandRegistrationCallback.EVENT
         .register((ClientCommandRegistrationCallback)(dispatcher, registryAccess) -> ClientCommands.registerCommands(dispatcher));
      HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("seedcrackerx", "guidance"), (gui, dt) -> GuidanceManager.INSTANCE.renderHud(gui));
   }

   public DataStorage getDataStorage() {
      return this.dataStorage;
   }

   public void reset() {
      get().getDataStorage().clear();
      FinderQueue.get().finderControl.deleteFinders();
   }
}
