package kaptainwutax.seedcrackerX.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.seedfinding.mccore.version.MCVersion;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kaptainwutax.seedcrackerX.Features;
import kaptainwutax.seedcrackerX.buff.BuffManager;
import kaptainwutax.seedcrackerX.util.FeatureToggle;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Config {
   private static final Logger logger = LoggerFactory.getLogger("config");
   private static final File file = new File(FabricLoader.getInstance().getConfigDir().toFile(), "seedcracker.json");
   private static Config INSTANCE = new Config();
   public FeatureToggle buriedTreasure = new FeatureToggle(true);
   public FeatureToggle desertTemple = new FeatureToggle(true);
   public FeatureToggle endCity = new FeatureToggle(true);
   public FeatureToggle jungleTemple = new FeatureToggle(true);
   public FeatureToggle monument = new FeatureToggle(true);
   public FeatureToggle swampHut = new FeatureToggle(true);
   public FeatureToggle shipwreck = new FeatureToggle(true);
   public FeatureToggle outpost = new FeatureToggle(true);
   public FeatureToggle igloo = new FeatureToggle(true);
   public FeatureToggle trialChambers = new FeatureToggle(true);
   public FeatureToggle endPillars = new FeatureToggle(true);
   public FeatureToggle endGateway = new FeatureToggle(false);
   public FeatureToggle dungeon = new FeatureToggle(true);
   public FeatureToggle emeraldOre = new FeatureToggle(false);
   public FeatureToggle desertWell = new FeatureToggle(false);
   public FeatureToggle warpedFungus = new FeatureToggle(false);
   public FeatureToggle biome = new FeatureToggle(false);
   public Config.RenderType render = Config.RenderType.XRAY;
   public boolean active = false;
   public boolean debug = false;
   public boolean antiXrayBypass = true;
   private MCVersion version = MCVersion.latest();
   public boolean databaseSubmits = false;
   public boolean anonymusSubmits = false;
   public boolean autoMineAutoReturn = true;
   public int autoMineReturnDelay = 5;
   public int autoMineDurabilityThreshold = 20;
   public boolean guidanceEnabled = true;
   public boolean xrayDiamond = true;
   public boolean xrayAncientDebris = true;
   public boolean xrayIron = false;
   public boolean xrayGold = false;
   public boolean xrayCoal = false;
   public boolean xrayRedstone = false;
   public boolean xrayLapis = false;
   public boolean xrayCopper = false;
   public boolean xrayEmerald = false;
   public int xrayRenderDistance = 2;
   public boolean xrayAntiXray = true;
   public boolean autoMineShulkerSave = true;
   public boolean autoMineAttackMobs = true;
   /** 配置迁移标记：旧版把 autoMineShulkerSave 以 false 落盘，升级后需一次性打开。 */
   public int settingsVersion = 0;
   /** 饱食度不足时自动吃背包里的食物（全局功能，与自动挖矿是否开启无关）。 */
   public boolean autoEatEnabled = true;
   /** 饱食度低于等于该值时触发自动进食，范围 1..19。 */
   public int autoEatThreshold = 18;
   public Map<String, int[]> buffSettings = new HashMap<>();
   public long activeSeed = 0L;
   public String activeVersion = "";
   public List<Config.SeedEntry> seedHistory = new ArrayList<>();

   public boolean buffEnabled(BuffManager.BuffType t) {
      int[] arr = this.buffSettings.get(t.name());
      return arr == null ? t.defaultEnabled : arr[0] != 0;
   }

   public int buffLevel(BuffManager.BuffType t) {
      int[] arr = this.buffSettings.get(t.name());
      return arr == null ? t.defaultLevel : arr[1];
   }

   public void setBuff(BuffManager.BuffType t, boolean enabled, int level) {
      this.buffSettings.put(t.name(), new int[]{enabled ? 1 : 0, level});
   }

   public static void save() {
      Gson gson = new GsonBuilder().setPrettyPrinting().create();
      file.getParentFile().mkdirs();

      try (FileWriter writer = new FileWriter(file)) {
         gson.toJson(INSTANCE, writer);
      } catch (IOException e) {
         logger.error("seedcracker couldn't save config", e);
      }
   }

   public static void load() {
      Gson gson = new Gson();
      if (file.exists()) {
         boolean migrate = false;
         try (Reader reader = new FileReader(file)) {
            INSTANCE = (Config)gson.fromJson(reader, Config.class);
         } catch (Exception e) {
            logger.error("seedcracker couldn't load config, deleting it...", e);
            file.delete();
            INSTANCE = new Config();
         }

         if (INSTANCE == null) {
            INSTANCE = new Config();
         }

         INSTANCE.active = false;
         if (INSTANCE.seedHistory == null) {
            INSTANCE.seedHistory = new ArrayList<>();
         }

         if (INSTANCE.activeVersion == null) {
            INSTANCE.activeVersion = "";
         }

         // 旧版本里 autoMineShulkerSave 的默认值是 false，并被写进了 seedcracker.json。
         // 后来代码默认改成 true，但老配置文件里的显式 false 仍会覆盖默认值，导致
         // 「背包满了却完全不存盒、也没有任何提示」。这里做一次性迁移；迁移之后
         // 用户再用 /ov ch 关 关闭就不会被重新打开。
         if (INSTANCE.settingsVersion < 2) {
            INSTANCE.settingsVersion = 2;
            if (!INSTANCE.autoMineShulkerSave) {
               INSTANCE.autoMineShulkerSave = true;
               logger.info("[config] 已将旧配置中关闭的自动存盒迁移为开启（/ov ch 关 可关闭）");
            }
            migrate = true;
         }

         logger.info("[config] autoMineShulkerSave={}, settingsVersion={}", INSTANCE.autoMineShulkerSave, INSTANCE.settingsVersion);
         if (migrate) {
            save();
         }
      } else {
         INSTANCE.settingsVersion = 2;
      }
   }

   public static Config get() {
      return INSTANCE;
   }

   public MCVersion getVersion() {
      return this.version;
   }

   public void setVersion(MCVersion version) {
      if (this.version != version) {
         this.version = version;
         Features.init(version);
      }
   }

   public enum RenderType {
      OFF,
      ON,
      XRAY;
   }

   public static class SeedEntry {
      public long seed;
      public String version;
      public String label;
      public long addedAt;

      public SeedEntry() {
      }

      public SeedEntry(long seed, String version, String label) {
         this.seed = seed;
         this.version = version != null ? version : "";
         this.label = label != null ? label : "";
         this.addedAt = System.currentTimeMillis();
      }
   }
}
