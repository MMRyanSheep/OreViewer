package kaptainwutax.seedcrackerX.xray;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import kaptainwutax.seedcrackerX.config.Config;
import kaptainwutax.seedcrackerX.render.Cuboid;
import kaptainwutax.seedcrackerX.util.FeatureToggle;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.core.HolderLookup.RegistryLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.data.worldgen.placement.OrePlacements;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.GenerationStep.Decoration;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.feature.AbstractOreFeature;
import net.minecraft.world.level.levelgen.feature.BlockReplacement;
import net.minecraft.world.level.levelgen.feature.ScatteredOreFeature;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RarityFilter;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OreXrayManager {
   private static final Logger LOGGER = LoggerFactory.getLogger("SeedCrackerX-OreXray");
   private static final AtomicInteger SIM_ERROR_LOGS = new AtomicInteger();
   public static final OreXrayManager INSTANCE = new OreXrayManager();
   public FeatureToggle diamond = new FeatureToggle(true);
   public FeatureToggle ancientDebris = new FeatureToggle(true);
   public FeatureToggle iron = new FeatureToggle(false);
   public FeatureToggle gold = new FeatureToggle(false);
   public FeatureToggle coal = new FeatureToggle(false);
   public FeatureToggle redstone = new FeatureToggle(false);
   public FeatureToggle lapis = new FeatureToggle(false);
   public FeatureToggle copper = new FeatureToggle(false);
   public FeatureToggle emerald = new FeatureToggle(false);
   public int renderDistance = 1;
   public boolean antiXray = true;
   private long worldSeed = 0L;
   private boolean active = false;
   public static final int COLOR_DIAMOND = ARGB.color(0, 200, 255);
   public static final int COLOR_ANCIENT_DEBRIS = ARGB.color(255, 120, 40);
   public static final int COLOR_IRON = ARGB.color(255, 200, 150);
   public static final int COLOR_GOLD = ARGB.color(255, 215, 0);
   public static final int COLOR_COAL = ARGB.color(100, 100, 100);
   public static final int COLOR_REDSTONE = ARGB.color(255, 0, 0);
   public static final int COLOR_LAPIS = ARGB.color(0, 0, 255);
   public static final int COLOR_COPPER = ARGB.color(200, 120, 60);
   public static final int COLOR_EMERALD = ARGB.color(0, 255, 85);
   private static final List<PlacementDef> PLACEMENTS = new ArrayList<>();
   private static final int OVERWORLD_STEP = Decoration.UNDERGROUND_ORES.ordinal();
   private static final int NETHER_STEP = Decoration.UNDERGROUND_DECORATION.ordinal();
   // 26.3: counts / heights / rarity / feature salts all come from the live worldgen registries.
   private Level registryLevel;
   private ResourceKey<Level> registryDimension;
   private WorldGenerationContext heightContext;
   private List<OreSpec> dimensionSpecs = new ArrayList<>();
   private Map<ResourceKey<Biome>, List<OreSpec>> biomeSpecs = new HashMap<>();
   private boolean registryFailed;
   private final List<Cuboid> cachedCuboids = new ArrayList<>();
   private final List<OreXrayManager.PredictedOre> cachedTargets = new ArrayList<>();
   private long lastUpdateChunkX = Long.MAX_VALUE;
   private long lastUpdateChunkZ = Long.MAX_VALUE;
   private long lastUpdateTime = 0L;
   private List<Cuboid> realCache = new ArrayList<>();
   private long realCacheMs = 0L;
   private final Set<String> xrayCmdOres = new HashSet<>();
   private boolean xrayCmdExtend = false;
   private int xrayRadius = 6;
   private static final int FAKE_RUN_MAX = 4;
   private BlockPos lastBasePos = null;

   private static void add(ResourceKey<PlacedFeature> key, int step, Function<OreXrayManager, FeatureToggle> toggle, int color, boolean nether) {
      PLACEMENTS.add(new PlacementDef(key, step, toggle, color, nether));
   }

   private static boolean isNether(Level level) {
      return level.dimension() == Level.NETHER
         || level.dimension().identifier().getPath().toLowerCase().contains("nether")
         || level.dimensionType().coordinateScale() == 8.0 && !level.dimensionType().hasSkyLight();
   }

   /**
    * 26.3: derive every ore placement from the live worldgen registries instead of the
    * hard coded 1.18-era salts/counts/heights. FeatureSorter gives us the exact feature
    * index used by setFeatureSeed, and the placement modifiers give count / height / rarity.
    */
   private void ensureRegistry(Level level) {
      if (level == null) {
         return;
      }

      ensurePlacements();
      boolean sameWorld = this.registryLevel == level
         && this.registryDimension != null
         && this.registryDimension.equals(level.dimension());
      if (sameWorld && (!this.dimensionSpecs.isEmpty() || this.registryFailed)) {
         return;
      }

      this.registryLevel = level;
      this.registryDimension = level.dimension();
      this.registryFailed = false;
      this.dimensionSpecs = new ArrayList<>();
      this.biomeSpecs = new HashMap<>();
      this.heightContext = null;
      this.cachedCuboids.clear();
      this.cachedTargets.clear();
      boolean nether = isNether(level);

      try {
         Provider provider = VanillaRegistries.createWorldLookup();
         RegistryLookup<PlacedFeature> features = provider.lookupOrThrow(Registries.PLACED_FEATURE);
         WorldPreset preset = provider.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value();
         WorldDimensions dimensions = preset.createWorldDimensions();
         ResourceKey<LevelStem> stemKey = nether ? LevelStem.NETHER : (level.dimension() == Level.END ? LevelStem.END : LevelStem.OVERWORLD);
         LevelStem stem = dimensions.get(stemKey).orElseThrow();
         List<Holder<Biome>> biomes = stem.generator().getBiomeSource().possibleBiomes().stream().toList();
         List<FeatureSorter.StepFeatureData> indexer = FeatureSorter.buildFeaturesPerStep(
            biomes, holder -> holder.value().getGenerationSettings().features(), true
         );
         // 26.3 exposes of(LevelAccessor): uses the real (possibly extended) world height,
         // no null ChunkGenerator / mixin trick needed.
         this.heightContext = WorldGenerationContext.of(level);
         Map<ResourceKey<PlacedFeature>, OreSpec> byKey = new HashMap<>();
         Map<PlacedFeature, OreSpec> byFeature = new HashMap<>();

         for (PlacementDef def : PLACEMENTS) {
            if (def.nether() != nether) {
               continue;
            }

            try {
               if (def.step() >= indexer.size()) {
                  LOGGER.warn("[xray] generation step {} missing for {}", def.step(), def.key().identifier());
                  continue;
               }

               PlacedFeature pf = features.getOrThrow(def.key()).value();
               if (!(pf.feature().value() instanceof AbstractOreFeature oreFeature)) {
                  LOGGER.warn("[xray] {} is not an ore feature", def.key().identifier());
                  continue;
               }

               int index = indexer.get(def.step()).indexMapping().applyAsInt(pf);
               if (index < 0) {
                  LOGGER.warn("[xray] no feature index for {}", def.key().identifier());
                  continue;
               }

               IntProvider count = ConstantInt.of(1);
               HeightProvider height = null;
               float rarity = 1.0F;

               for (PlacementModifier modifier : pf.placement()) {
                  if (modifier instanceof CountPlacement countPlacement) {
                     count = countPlacement.count();
                  } else if (modifier instanceof HeightRangePlacement heightRange) {
                     height = heightRange.height();
                  } else if (modifier instanceof RarityFilter rarityFilter) {
                     rarity = rarityFilter.chance();
                  }
               }

               if (height == null) {
                  LOGGER.warn("[xray] no height provider for {}", def.key().identifier());
                  continue;
               }

               OreSpec spec = new OreSpec(
                  def,
                  index,
                  count,
                  height,
                  rarity,
                  oreFeature.discardChanceOnAirExposure(),
                  oreFeature.size(),
                  pf.feature().value() instanceof ScatteredOreFeature,
                  oreFeature.targetStates(),
                  def.toggle().apply(this),
                  def.color()
               );
               this.dimensionSpecs.add(spec);
               byKey.put(def.key(), spec);
               byFeature.put(pf, spec);
            } catch (Throwable t) {
               LOGGER.warn("[xray] cannot resolve {}: {}", def.key().identifier(), t.toString());
            }
         }

         for (Holder<Biome> biome : biomes) {
            ResourceKey<Biome> key = biome.unwrapKey().orElse(null);
            if (key == null) {
               continue;
            }

            List<OreSpec> list = new ArrayList<>();

            for (HolderSet<PlacedFeature> group : biome.value().getGenerationSettings().features()) {
               for (Holder<PlacedFeature> featureHolder : group) {
                  OreSpec spec = byFeature.get(featureHolder.value());
                  if (spec == null) {
                     spec = featureHolder.unwrapKey().map(byKey::get).orElse(null);
                  }

                  if (spec != null && !list.contains(spec)) {
                     list.add(spec);
                  }
               }
            }

            this.biomeSpecs.put(key, list);
         }

         LOGGER.info(
            "[xray] 26.3 矿物注册表就绪：维度={}, 矿脉配置={}, 生物群系={}, 世界高度={}..{}",
            new Object[]{level.dimension().identifier(), this.dimensionSpecs.size(), this.biomeSpecs.size(), level.getMinY(), level.getMaxY()}
         );
      } catch (Throwable t) {
         this.registryFailed = true;
         LOGGER.error("[xray] 构建 26.3 矿物注册表失败，种子矿透不可用", t);
      }
   }

   private List<OreSpec> oresAt(Level level, ChunkAccess chunk, int x, int y, int z) {
      if (chunk != null) {
         try {
            // 26.3 getNoiseBiome takes quart coordinates (not block coordinates).
            Holder<Biome> biome = chunk.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z));
            ResourceKey<Biome> key = biome.unwrapKey().orElse(null);
            if (key != null) {
               List<OreSpec> specs = this.biomeSpecs.get(key);
               if (specs != null) {
                  return specs;
               }
            }
         } catch (Throwable ignored) {
         }
      }

      return this.dimensionSpecs;
   }

   public List<OreXrayManager.PredictedOre> getPredictedOres() {
      return this.cachedTargets;
   }

   public void warmupResolve(Level level) {
      this.ensureRegistry(level);
   }

   public List<OreXrayManager.PredictedOre> predictBlocksAround(Level level, String blockPath, BlockPos center, int chunkRadius) {
      List<OreXrayManager.PredictedOre> out = new ArrayList<>();

      Block target;
      try {
         target = (Block)BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:" + blockPath));
      } catch (Throwable t) {
         return out;
      }

      if (target == null || target == Blocks.AIR) {
         return out;
      }

      this.ensureRegistry(level);
      if (this.dimensionSpecs.isEmpty()) {
         return out;
      }

      Predicate<OreSpec> filter = spec -> {
         for (BlockReplacement replacement : spec.targets) {
            if (replacement.state().getBlock() == target) {
               return true;
            }
         }

         return false;
      };
      OreXrayManager.OreSink sink = (pos, block, color) -> {
         if (block == target) {
            out.add(new OreXrayManager.PredictedOre(pos, block));
         }
      };
      int pcx = center.getX() >> 4;
      int pcz = center.getZ() >> 4;

      for (int cx = pcx - chunkRadius; cx <= pcx + chunkRadius; cx++) {
         for (int cz = pcz - chunkRadius; cz <= pcz + chunkRadius; cz++) {
            this.simulateChunk(level, cx, cz, filter, sink);
         }
      }

      return out;
   }

   public void setSeed(long seed) {
      this.worldSeed = seed;
      this.active = true;
      this.cachedCuboids.clear();
      this.cachedTargets.clear();
      this.lastUpdateChunkX = Long.MAX_VALUE;
      SIM_ERROR_LOGS.set(0);
      LOGGER.info("[xray] ore xray activated, world seed = {}", seed);
   }

   public void saveSettings() {
      Config c = Config.get();
      c.xrayDiamond = this.diamond.get();
      c.xrayAncientDebris = this.ancientDebris.get();
      c.xrayIron = this.iron.get();
      c.xrayGold = this.gold.get();
      c.xrayCoal = this.coal.get();
      c.xrayRedstone = this.redstone.get();
      c.xrayLapis = this.lapis.get();
      c.xrayCopper = this.copper.get();
      c.xrayEmerald = this.emerald.get();
      c.xrayRenderDistance = this.renderDistance;
      c.xrayAntiXray = this.antiXray;
      Config.save();
   }

   public void loadSettings() {
      Config c = Config.get();
      this.diamond.set(c.xrayDiamond);
      this.ancientDebris.set(c.xrayAncientDebris);
      this.iron.set(c.xrayIron);
      this.gold.set(c.xrayGold);
      this.coal.set(c.xrayCoal);
      this.redstone.set(c.xrayRedstone);
      this.lapis.set(c.xrayLapis);
      this.copper.set(c.xrayCopper);
      this.emerald.set(c.xrayEmerald);
      this.renderDistance = c.xrayRenderDistance;
      if (this.antiXray != c.xrayAntiXray) {
         this.antiXray = c.xrayAntiXray;
         this.cachedCuboids.clear();
         this.cachedTargets.clear();
      }

      if (c.activeSeed != 0L) {
         this.worldSeed = c.activeSeed;
         this.active = true;
         LOGGER.info("[xray] 从本地配置恢复种子 {}", c.activeSeed);
      }
   }

   public void clear() {
      this.worldSeed = 0L;
      this.active = false;
      this.cachedCuboids.clear();
      this.cachedTargets.clear();
   }

   public void clearCache() {
      this.cachedCuboids.clear();
      this.cachedTargets.clear();
      this.lastUpdateChunkX = Long.MAX_VALUE;
      // Force a registry rebuild on dimension / world changes.
      this.registryLevel = null;
   }

   public boolean isActive() {
      return this.active && this.worldSeed != 0L;
   }

   public long getWorldSeed() {
      return this.worldSeed;
   }

   public List<Cuboid> getCuboids(Camera camera) {
      if (this.isXrayCmdActive()) {
         Minecraft mc0 = Minecraft.getInstance();
         return mc0 != null && mc0.player != null && mc0.level != null ? this.scanRealOres(mc0.level, mc0.player.blockPosition()) : this.cachedCuboids;
      }

      if (!this.isActive()) {
         this.cachedCuboids.clear();
         this.cachedTargets.clear();
         return this.cachedCuboids;
      }

      Minecraft mc = Minecraft.getInstance();
      if (mc != null && mc.player != null && mc.level != null) {
         Level level = mc.level;
         BlockPos playerPos = mc.player.blockPosition();
         int playerChunkX = playerPos.getX() >> 4;
         int playerChunkZ = playerPos.getZ() >> 4;
         long now = System.currentTimeMillis();
         if (playerChunkX == this.lastUpdateChunkX && playerChunkZ == this.lastUpdateChunkZ && now - this.lastUpdateTime < 2000L) {
            return this.cachedCuboids;
         }

         this.lastUpdateChunkX = playerChunkX;
         this.lastUpdateChunkZ = playerChunkZ;
         this.lastUpdateTime = now;
         this.cachedCuboids.clear();
         this.cachedTargets.clear();
         this.ensureRegistry(level);
         OreXrayManager.OreSink sink = (pos, block, color) -> {
            int xrayChunkDist = Math.max(1, this.renderDistance);
            if (Math.abs((pos.getX() >> 4) - playerChunkX) <= xrayChunkDist && Math.abs((pos.getZ() >> 4) - playerChunkZ) <= xrayChunkDist) {
               this.cachedCuboids.add(new Cuboid(pos, color));
               this.cachedTargets.add(new OreXrayManager.PredictedOre(pos, block));
            }
         };
         Predicate<OreSpec> filter = spec -> spec.toggle.get();
         int dist = Math.max(1, this.renderDistance);
         int chunks = 0;

         for (int cx = playerChunkX - dist; cx <= playerChunkX + dist; cx++) {
            for (int cz = playerChunkZ - dist; cz <= playerChunkZ + dist; cz++) {
               this.simulateChunk(level, cx, cz, filter, sink);
               chunks++;
            }
         }

         LOGGER.info("[xray] 26.3 模拟 {} 个区块，渲染 {} 个矿块", chunks, this.cachedCuboids.size());
         return this.cachedCuboids;
      } else {
         return this.cachedCuboids;
      }
   }

   public boolean isXrayCmdActive() {
      return !this.xrayCmdOres.isEmpty();
   }

   public void setXrayCmdOre(String key, boolean on) {
      if (on) {
         this.xrayCmdOres.add(key);
      } else {
         this.xrayCmdOres.remove(key);
      }

      this.realCacheMs = 0L;
   }

   public void clearXrayCmd() {
      this.xrayCmdOres.clear();
      this.realCacheMs = 0L;
   }

   public Set<String> getXrayCmdOres() {
      return Collections.unmodifiableSet(this.xrayCmdOres);
   }

   public boolean isXrayCmdExtend() {
      return this.xrayCmdExtend;
   }

   public void setXrayCmdExtend(boolean on) {
      this.xrayCmdExtend = on;
      this.realCacheMs = 0L;
   }

   public int getXrayRadius() {
      return this.xrayRadius;
   }

   public void setXrayRadius(int r) {
      this.xrayRadius = r;
      this.realCacheMs = 0L;
   }

   private static Map<String, Block[]> realOreBlocks() {
      Map<String, Block[]> map = new LinkedHashMap<>();
      map.put("钻石", new Block[]{Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE});
      map.put("远古残骸", new Block[]{Blocks.ANCIENT_DEBRIS});
      map.put("铁", new Block[]{Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE});
      map.put("金", new Block[]{Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE});
      map.put("煤", new Block[]{Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE});
      map.put("红石", new Block[]{Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE});
      map.put("青金石", new Block[]{Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE});
      map.put("铜", new Block[]{Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE});
      map.put("绿宝石", new Block[]{Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE});
      return map;
   }

   private static int keyColor(String key) {
      if (key.equals("钻石")) {
         return COLOR_DIAMOND;
      } else if (key.equals("远古残骸")) {
         return COLOR_ANCIENT_DEBRIS;
      } else if (key.equals("铁")) {
         return COLOR_IRON;
      } else if (key.equals("金")) {
         return COLOR_GOLD;
      } else if (key.equals("煤")) {
         return COLOR_COAL;
      } else if (key.equals("红石")) {
         return COLOR_REDSTONE;
      } else if (key.equals("青金石")) {
         return COLOR_LAPIS;
      } else if (key.equals("铜")) {
         return COLOR_COPPER;
      } else {
         return key.equals("绿宝石") ? COLOR_EMERALD : COLOR_DIAMOND;
      }
   }

   private List<Cuboid> scanRealOres(Level level, BlockPos base) {
      long now = System.currentTimeMillis();
      long interval = this.xrayRadius > 0 ? 500L : 2000L;
      boolean moved = this.xrayRadius > 0 && (this.lastBasePos == null || !this.lastBasePos.equals(base));
      if (!moved && now - this.realCacheMs < interval) {
         return this.realCache;
      }

      this.realCacheMs = now;
      this.lastBasePos = base.immutable();
      this.realCache = new ArrayList<>();
      if (!level.dimension().identifier().getPath().toLowerCase().contains("nether")
         && (level.dimensionType().coordinateScale() != 8.0 || level.dimensionType().hasSkyLight())) {
         boolean isNether = false;
      } else {
         boolean isNether = true;
      }

      this.cachedTargets.clear();
      Map<String, Block[]> wanted = realOreBlocks();
      Set<Block> allWanted = new HashSet<>();

      for (Entry<String, Block[]> e : wanted.entrySet()) {
         if (this.xrayCmdOres.contains(e.getKey())) {
            allWanted.addAll(Arrays.asList(e.getValue()));
         }
      }

      if (allWanted.isEmpty()) {
         return this.realCache;
      }

      Set<Long> rendered = new HashSet<>();
      List<BlockPos> found = new ArrayList<>();
      List<Integer> foundColor = new ArrayList<>();
      List<Block> foundBlock = new ArrayList<>();
      if (this.xrayRadius > 0) {
         int rad = this.xrayRadius;
         MutableBlockPos m = new MutableBlockPos();

         for (int ox = -rad; ox <= rad; ox++) {
            for (int oy = -rad; oy <= rad; oy++) {
               for (int oz = -rad; oz <= rad; oz++) {
                  m.set(base.getX() + ox, base.getY() + oy, base.getZ() + oz);
                  if (m.getY() >= level.getMinY() && m.getY() < level.getMaxY()) {
                     BlockState s = level.getBlockState(m);
                     if (allWanted.contains(s.getBlock())) {
                        this.collectOre(m, s, wanted, found, foundColor, foundBlock);
                     }
                  }
               }
            }
         }
      } else {
         int r = Math.max(1, this.renderDistance);
         int baseCx = base.getX() >> 4;
         int baseCz = base.getZ() >> 4;
         int minSec = level.getMinY() >> 4;
         MutableBlockPos m = new MutableBlockPos();

         for (int cx = baseCx - r; cx <= baseCx + r; cx++) {
            for (int cz = baseCz - r; cz <= baseCz + r; cz++) {
               ChunkAccess chunk = level.getChunk(cx, cz, ChunkStatus.FULL, false);
               if (chunk != null) {
                  LevelChunkSection[] sections = chunk.getSections();

                  for (int i = 0; i < sections.length; i++) {
                     LevelChunkSection sec = sections[i];
                     if (sec != null && !sec.hasOnlyAir()) {
                        boolean hit = false;

                        for (Block b : allWanted) {
                           if (sec.getStates().maybeHas(st -> st.is(b))) {
                              hit = true;
                              break;
                           }
                        }

                        if (hit) {
                           int secY = minSec + i << 4;

                           for (int x = 0; x < 16; x++) {
                              for (int y = 0; y < 16; y++) {
                                 for (int z = 0; z < 16; z++) {
                                    BlockState s = sec.getBlockState(x, y, z);
                                    if (allWanted.contains(s.getBlock())) {
                                       m.set(cx << 4 | x, secY | y, cz << 4 | z);
                                       this.collectOre(m, s, wanted, found, foundColor, foundBlock);
                                    }
                                 }
                              }
                           }
                        }
                     }
                  }
               }
            }
         }
      }

      boolean[] fake = this.markFakeVeins(found, foundBlock);
      MutableBlockPos n = new MutableBlockPos();
      int[] dx = new int[]{1, -1, 0, 0, 0, 0};
      int[] dy = new int[]{0, 0, 1, -1, 0, 0};
      int[] dz = new int[]{0, 0, 0, 0, 1, -1};

      for (int i = 0; i < found.size(); i++) {
         if (!fake[i]) {
            BlockPos p = found.get(i);
            int color = foundColor.get(i);
            Block b = foundBlock.get(i);
            if (rendered.add(p.asLong())) {
               this.realCache.add(new Cuboid(p, color));
               this.cachedTargets.add(new OreXrayManager.PredictedOre(p, b));
            }

            if (this.xrayCmdExtend) {
               for (int d = 0; d < 6; d++) {
                  n.set(p.getX() + dx[d], p.getY() + dy[d], p.getZ() + dz[d]);
                  if (!level.getBlockState(n).isAir()) {
                     BlockPos np = n.immutable();
                     if (rendered.add(np.asLong())) {
                        this.realCache.add(new Cuboid(np, color));
                        this.cachedTargets.add(new OreXrayManager.PredictedOre(np, b));
                     }
                  }
               }
            }
         }
      }

      return this.realCache;
   }

   private void collectOre(BlockPos p, BlockState s, Map<String, Block[]> wanted, List<BlockPos> found, List<Integer> foundColor, List<Block> foundBlock) {
      for (Entry<String, Block[]> e : wanted.entrySet()) {
         if (this.xrayCmdOres.contains(e.getKey())) {
            for (Block b : e.getValue()) {
               if (s.is(b)) {
                  found.add(p.immutable());
                  foundColor.add(keyColor(e.getKey()));
                  foundBlock.add(s.getBlock());
                  return;
               }
            }
         }
      }
   }

   private boolean[] markFakeVeins(List<BlockPos> found, List<Block> foundBlock) {
      int n = found.size();
      boolean[] fake = new boolean[n];
      if (n == 0) {
         return fake;
      }

      Map<Long, Integer> idx = new HashMap<>();

      for (int i = 0; i < n; i++) {
         idx.put(found.get(i).asLong(), i);
      }

      int[] dx = new int[]{1, -1, 0, 0, 0, 0};
      int[] dy = new int[]{0, 0, 1, -1, 0, 0};
      int[] dz = new int[]{0, 0, 0, 0, 1, -1};
      boolean[] visited = new boolean[n];

      for (int s = 0; s < n; s++) {
         if (!visited[s]) {
            List<Integer> comp = new ArrayList<>();
            Deque<Integer> stack = new ArrayDeque<>();
            stack.push(s);
            visited[s] = true;

            while (!stack.isEmpty()) {
               int cur = stack.pop();
               comp.add(cur);
               BlockPos p = found.get(cur);
               Block b = foundBlock.get(cur);

               for (int d = 0; d < 6; d++) {
                  Integer ni = idx.get(BlockPos.asLong(p.getX() + dx[d], p.getY() + dy[d], p.getZ() + dz[d]));
                  if (ni != null && !visited[ni] && foundBlock.get(ni) == b) {
                     visited[ni] = true;
                     stack.push(ni);
                  }
               }
            }

            int maxH = 0;
            int maxV = 0;

            for (int ci : comp) {
               BlockPos p = found.get(ci);
               Block b = foundBlock.get(ci);
               int rx = 1 + this.runAxis(idx, foundBlock, b, p, 1, 0, 0) + this.runAxis(idx, foundBlock, b, p, -1, 0, 0);
               int rz = 1 + this.runAxis(idx, foundBlock, b, p, 0, 0, 1) + this.runAxis(idx, foundBlock, b, p, 0, 0, -1);
               int ry = 1 + this.runAxis(idx, foundBlock, b, p, 0, 1, 0) + this.runAxis(idx, foundBlock, b, p, 0, -1, 0);
               maxH = Math.max(maxH, Math.max(rx, rz));
               maxV = Math.max(maxV, ry);
            }

            if (maxH > 4 || maxV > 4) {
               for (int ci : comp) {
                  fake[ci] = true;
               }
            }
         }
      }

      return fake;
   }

   private int runAxis(Map<Long, Integer> idx, List<Block> foundBlock, Block type, BlockPos from, int ax, int ay, int az) {
      int len = 0;
      int x = from.getX();
      int y = from.getY();
      int z = from.getZ();

      while (true) {
         x += ax;
         y += ay;
         z += az;
         Integer ni = idx.get(BlockPos.asLong(x, y, z));
         if (ni == null || foundBlock.get(ni) != type) {
            return len;
         }

         len++;
      }
   }

   /**
    * Mirrors 26.3 ChunkGenerator.applyBiomeDecoration: one WorldgenRandom seeded with
    * setDecorationSeed(worldSeed, chunkX<<4, chunkZ<<4), then setFeatureSeed per feature
    * with the FeatureSorter index. Count / InSquare / height / biome filter are consumed
    * in exactly the same order as the vanilla placement chain.
    */
   private void simulateChunk(Level level, int chunkX, int chunkZ, Predicate<OreSpec> filter, OreXrayManager.OreSink sink) {
      if (this.heightContext == null || this.dimensionSpecs.isEmpty()) {
         return;
      }

      WorldgenRandom rand = new WorldgenRandom(new XoroshiroRandomSource(0L));
      long populationSeed = rand.setDecorationSeed(this.worldSeed, chunkX << 4, chunkZ << 4);
      ChunkAccess centerChunk = level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);

      for (OreSpec spec : this.dimensionSpecs) {
         if (!filter.test(spec)) {
            continue;
         }

         rand.setFeatureSeed(populationSeed, spec.index, spec.def.step());
         int repeat = spec.count.sample(rand);

         for (int i = 0; i < repeat; i++) {
            // RarityFilter replaces CountPlacement for diamond_large etc.
            if (spec.rarity > 1.0F && rand.nextFloat() >= 1.0F / spec.rarity) {
               continue;
            }

            int x = (chunkX << 4) + rand.nextInt(16);
            int z = (chunkZ << 4) + rand.nextInt(16);
            int y = spec.height.sample(rand, this.heightContext);
            BlockPos origin = new BlockPos(x, y, z);

            // BiomeFilter.biome() is the last placement modifier - skip veins whose biome
            // does not carry this feature (emerald only in mountains, nether ores, ...).
            if (!this.oresAt(level, centerChunk, x, y, z).contains(spec)) {
               continue;
            }

            try {
               if (spec.scattered) {
                  this.simulateScattered(level, spec, origin, rand, sink);
               } else {
                  this.simulateOreVein(level, spec, origin, rand, sink);
               }
            } catch (Exception e) {
               if (SIM_ERROR_LOGS.getAndIncrement() < 5) {
                  LOGGER.error("[xray] vein simulation failed for {} at {}", new Object[]{spec.def.key().identifier(), origin, e});
               }
            }
         }
      }
   }

   /** 26.3 placement check: RuleTest / already-ore state + discardChanceOnAirExposure. */
   private void tryPlace(WorldgenRandom rand, OreSpec spec, BlockPos pos, BlockState state, OreXrayManager.OreSink sink) {
      for (BlockReplacement target : spec.targets) {
         boolean isOre = state.getBlock() == target.state().getBlock();
         boolean replaceable = target.target().test(state, pos, rand);
         if (!isOre && !replaceable) {
            continue;
         }

         // Vanilla's canPlaceOre consumes exactly one float here when
         // 0 < discardChanceOnAirExposure < 1 (the air check itself consumes nothing).
         // We deliberately do not run the air check: on anti-xray servers the client
         // terrain is fake, and on normal servers player-dug tunnels would hide real ore.
         float discard = spec.discardOnAirChance;
         if (discard > 0.0F && discard < 1.0F) {
            rand.nextFloat();
         }

         if (this.antiXray || isOre) {
            sink.accept(pos.immutable(), target.state().getBlock(), spec.color);
         }

         break;
      }
   }

   private void simulateOreVein(Level level, OreSpec spec, BlockPos origin, WorldgenRandom rand, OreXrayManager.OreSink sink) {
      int size = spec.size;
      float g = size / 8.0F;
      int yRadius = Mth.ceil((size / 16.0F * 2.0F + 1.0F) / 2.0F);
      float f = rand.nextFloat() * (float) Math.PI;
      double sin = Math.sin(f);
      double cos = Math.cos(f);
      double x1 = origin.getX() + sin * g;
      double x2 = origin.getX() - sin * g;
      double z1 = origin.getZ() + cos * g;
      double z2 = origin.getZ() - cos * g;
      double y1 = origin.getY() + rand.nextInt(3) - 2;
      double y2 = origin.getY() + rand.nextInt(3) - 2;
      int xMin = origin.getX() - Mth.ceil(g) - yRadius;
      int yMin = origin.getY() - 2 - yRadius;
      int zMin = origin.getZ() - Mth.ceil(g) - yRadius;
      int rangeXZ = 2 * (Mth.ceil(g) + yRadius);
      int rangeY = 2 * (2 + yRadius);
      boolean doPlace = false;
      MutableBlockPos gatePos = new MutableBlockPos();
      int worldMinYGate = level.getMinY();
      int worldMaxYGate = level.getMaxY();
      int gateFloor = Math.max(yMin, worldMinYGate);

      for (int lx = 0; lx <= rangeXZ && !doPlace; lx++) {
         for (int lz = 0; lz <= rangeXZ && !doPlace; lz++) {
            int wx = xMin + lx;
            int wz = zMin + lz;
            if (level.getChunk(wx >> 4, wz >> 4, ChunkStatus.FULL, false) != null) {
               int h = level.getHeight(Types.WORLD_SURFACE, wx, wz);
               if (h <= worldMinYGate) {
                  h = worldMinYGate;

                  for (int wy = worldMaxYGate - 1; wy >= gateFloor; wy--) {
                     gatePos.set(wx, wy, wz);
                     BlockState st = level.getBlockState(gatePos);
                     if (!st.isAir() && !st.is(Blocks.WATER)) {
                        h = wy + 1;
                        break;
                     }
                  }
               } else {
                  while (h - 1 >= gateFloor) {
                     gatePos.set(wx, h - 1, wz);
                     if (!level.getBlockState(gatePos).is(Blocks.WATER)) {
                        break;
                     }

                     h--;
                  }
               }

               if (yMin <= h) {
                  doPlace = true;
               }
            }
         }
      }

      if (doPlace) {
         double[] px = new double[size];
         double[] py = new double[size];
         double[] pz = new double[size];
         double[] pr = new double[size];

         for (int j = 0; j < size; j++) {
            double t = (double)j / size;
            px[j] = Mth.lerp(t, x1, x2);
            py[j] = Mth.lerp(t, y1, y2);
            pz[j] = Mth.lerp(t, z1, z2);
            double d3 = rand.nextDouble() * size / 16.0;
            pr[j] = ((Mth.sin((float)((float) Math.PI * t)) + 1.0F) * d3 + 1.0) / 2.0;
         }

         for (int k = 0; k < size - 1; k++) {
            if (!(pr[k] <= 0.0)) {
               for (int l = k + 1; l < size; l++) {
                  if (!(pr[l] <= 0.0)) {
                     double dx = px[k] - px[l];
                     double dy = py[k] - py[l];
                     double dz = pz[k] - pz[l];
                     double dw = pr[k] - pr[l];
                     if (dw * dw > dx * dx + dy * dy + dz * dz) {
                        if (dw > 0.0) {
                           pr[l] = -1.0;
                        } else {
                           pr[k] = -1.0;
                        }
                     }
                  }
               }
            }
         }

         BitSet placed = new BitSet(rangeXZ * rangeXZ * rangeY);
         MutableBlockPos pos = new MutableBlockPos();
         int worldMinY = level.getMinY();
         int worldMaxY = level.getMaxY();

         for (int s = 0; s < size; s++) {
            double r = pr[s];
            if (!(r < 0.0)) {
               int x0 = Math.max(Mth.floor(px[s] - r), xMin);
               int y0 = Math.max(Mth.floor(py[s] - r), yMin);
               int z0 = Math.max(Mth.floor(pz[s] - r), zMin);
               int x1b = Math.max(Mth.floor(px[s] + r), x0);
               int y1b = Math.max(Mth.floor(py[s] + r), y0);
               int z1b = Math.max(Mth.floor(pz[s] + r), z0);

               for (int bx = x0; bx <= x1b; bx++) {
                  double dx = (bx + 0.5 - px[s]) / r;
                  double dx2 = dx * dx;
                  if (!(dx2 >= 1.0)) {
                     for (int by = y0; by <= y1b; by++) {
                        if (by >= worldMinY && by < worldMaxY) {
                           double dy = (by + 0.5 - py[s]) / r;
                           double dxy2 = dx2 + dy * dy;
                           if (!(dxy2 >= 1.0)) {
                              for (int bz = z0; bz <= z1b; bz++) {
                                 double dz = (bz + 0.5 - pz[s]) / r;
                                 if (!(dxy2 + dz * dz >= 1.0)) {
                                    int bit = bx - xMin + (by - yMin) * rangeXZ + (bz - zMin) * rangeXZ * rangeY;
                                    if (!placed.get(bit)) {
                                       placed.set(bit);
                                       if (level.getChunk(bx >> 4, bz >> 4, ChunkStatus.FULL, false) != null) {
                                          pos.set(bx, by, bz);
                                          BlockState state = level.getBlockState(pos);
                                          this.tryPlace(rand, spec, pos, state, sink);
                                       }
                                    }
                                 }
                              }
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private void simulateScattered(Level level, OreSpec spec, BlockPos origin, WorldgenRandom rand, OreXrayManager.OreSink sink) {
      int veinCount = rand.nextInt(spec.size + 1);
      MutableBlockPos pos = new MutableBlockPos();

      for (int i = 0; i < veinCount; i++) {
         int dist = Math.min(i, 7);
         int dx = Math.round((rand.nextFloat() - rand.nextFloat()) * dist);
         int dy = Math.round((rand.nextFloat() - rand.nextFloat()) * dist);
         int dz = Math.round((rand.nextFloat() - rand.nextFloat()) * dist);
         pos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
         if (level.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false) != null) {
            this.tryPlace(rand, spec, pos, level.getBlockState(pos), sink);
         }
      }
   }

   /** Lazily built: OrePlacements' static init requires Minecraft's Bootstrap to be done. */
   private static synchronized void ensurePlacements() {
      if (!PLACEMENTS.isEmpty()) {
         return;
      }

      int ow = OVERWORLD_STEP;
      int ne = NETHER_STEP;
      add(OrePlacements.ORE_DIAMOND, ow, m -> m.diamond, COLOR_DIAMOND, false);
      add(OrePlacements.ORE_DIAMOND_LARGE, ow, m -> m.diamond, COLOR_DIAMOND, false);
      add(OrePlacements.ORE_DIAMOND_MEDIUM, ow, m -> m.diamond, COLOR_DIAMOND, false);
      add(OrePlacements.ORE_DIAMOND_BURIED, ow, m -> m.diamond, COLOR_DIAMOND, false);
      add(OrePlacements.ORE_IRON_UPPER, ow, m -> m.iron, COLOR_IRON, false);
      add(OrePlacements.ORE_IRON_MIDDLE, ow, m -> m.iron, COLOR_IRON, false);
      add(OrePlacements.ORE_IRON_SMALL, ow, m -> m.iron, COLOR_IRON, false);
      add(OrePlacements.ORE_GOLD, ow, m -> m.gold, COLOR_GOLD, false);
      add(OrePlacements.ORE_GOLD_LOWER, ow, m -> m.gold, COLOR_GOLD, false);
      add(OrePlacements.ORE_GOLD_EXTRA, ow, m -> m.gold, COLOR_GOLD, false);
      add(OrePlacements.ORE_COAL_UPPER, ow, m -> m.coal, COLOR_COAL, false);
      add(OrePlacements.ORE_COAL_LOWER, ow, m -> m.coal, COLOR_COAL, false);
      add(OrePlacements.ORE_REDSTONE, ow, m -> m.redstone, COLOR_REDSTONE, false);
      add(OrePlacements.ORE_REDSTONE_LOWER, ow, m -> m.redstone, COLOR_REDSTONE, false);
      add(OrePlacements.ORE_LAPIS, ow, m -> m.lapis, COLOR_LAPIS, false);
      add(OrePlacements.ORE_LAPIS_BURIED, ow, m -> m.lapis, COLOR_LAPIS, false);
      add(OrePlacements.ORE_COPPER, ow, m -> m.copper, COLOR_COPPER, false);
      add(OrePlacements.ORE_COPPER_LARGE, ow, m -> m.copper, COLOR_COPPER, false);
      add(OrePlacements.ORE_EMERALD, ow, m -> m.emerald, COLOR_EMERALD, false);
      add(OrePlacements.ORE_GOLD_NETHER, ne, m -> m.gold, COLOR_GOLD, true);
      add(OrePlacements.ORE_GOLD_DELTAS, ne, m -> m.gold, COLOR_GOLD, true);
      add(OrePlacements.ORE_ANCIENT_DEBRIS_LARGE, ne, m -> m.ancientDebris, COLOR_ANCIENT_DEBRIS, true);
      add(OrePlacements.ORE_ANCIENT_DEBRIS_SMALL, ne, m -> m.ancientDebris, COLOR_ANCIENT_DEBRIS, true);
   }

   // 26.3 uses Mojang's WorldgenRandom / XoroshiroRandomSource directly, so the
   // hand rolled FeatureRandom (and its hard coded feature salts) is gone.

   private interface OreSink {
      void accept(BlockPos var1, Block var2, int var3);
   }

   private record PlacementDef(
      ResourceKey<PlacedFeature> key, int step, Function<OreXrayManager, FeatureToggle> toggle, int color, boolean nether
   ) {
   }

   /** One resolved 26.3 ore placement, ready to be fed into setFeatureSeed. */
   private static final class OreSpec {
      final PlacementDef def;
      final int index;
      final IntProvider count;
      final HeightProvider height;
      final float rarity;
      final float discardOnAirChance;
      final int size;
      final boolean scattered;
      final List<BlockReplacement> targets;
      final FeatureToggle toggle;
      final int color;

      OreSpec(
         PlacementDef def,
         int index,
         IntProvider count,
         HeightProvider height,
         float rarity,
         float discardOnAirChance,
         int size,
         boolean scattered,
         List<BlockReplacement> targets,
         FeatureToggle toggle,
         int color
      ) {
         this.def = def;
         this.index = index;
         this.count = count;
         this.height = height;
         this.rarity = rarity;
         this.discardOnAirChance = discardOnAirChance;
         this.size = size;
         this.scattered = scattered;
         this.targets = targets;
         this.toggle = toggle;
         this.color = color;
      }
   }

   public record PredictedOre(BlockPos pos, Block block) {
   }
}
