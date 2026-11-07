package kaptainwutax.seedcrackerX.guidance;

import com.seedfinding.mcbiome.biome.Biome;
import com.seedfinding.mcbiome.biome.Biomes;
import com.seedfinding.mcbiome.source.BiomeSource;
import com.seedfinding.mcbiome.source.EndBiomeSource;
import com.seedfinding.mcbiome.source.OverworldBiomeSource;
import com.seedfinding.mccore.rand.ChunkRand;
import com.seedfinding.mccore.state.Dimension;
import com.seedfinding.mccore.util.pos.CPos;
import com.seedfinding.mccore.version.MCVersion;
import com.seedfinding.mcfeature.structure.RegionStructure;
import com.seedfinding.mcfeature.structure.Village;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import kaptainwutax.seedcrackerX.Features;
import kaptainwutax.seedcrackerX.config.Config;
import kaptainwutax.seedcrackerX.util.Compat;
import kaptainwutax.seedcrackerX.xray.OreXrayManager;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.biome.Climate.Sampler;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.Structure.GenerationContext;
import net.minecraft.world.level.levelgen.structure.Structure.GenerationStub;
import net.minecraft.world.level.levelgen.structure.StructureSet.StructureSelectionEntry;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.DimensionOriginStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GuidanceManager {
   private static final Logger LOGGER = LoggerFactory.getLogger("SeedCrackerX-Guidance");
   public static final GuidanceManager INSTANCE = new GuidanceManager();
   public static final int MAX_TARGETS = 3;
   public static final int MAX_DX_TARGETS = 2;
   private static final int MAX_SPREAD_REGIONS = 10;
   private static final int MAX_DENSE_REGIONS = 96;
   private static final int MAX_CANDIDATES = 12000;
   private static final int CACHE_LIMIT = 40000;
   private static final int COLOR_END_CITY = ARGB.color(255, 153, 0, 153);
   private static final int COLOR_STRUCTURE = ARGB.color(255, 255, 215, 0);
   private static final int COLOR_BIOME = ARGB.color(255, 80, 255, 80);
   private static final int COLOR_FK = ARGB.color(255, 170, 0, 255);
   private static final int FK_CHUNK_RADIUS = 10;
   private static final long FK_RESCAN_MS = 15000L;
   private static final long RESCAN_MS = 3000L;
   private static final int MAX_LINE_FILLS = 200;
   private static final int BIOME_ANGLES = 16;
   private static final int BIOME_SEARCH_STEP = 128;
   private static final int BIOME_SEARCH_RADIUS = 3072;
   private GuidanceManager.TargetKind activeKind = null;
   private Collection<Biome> biomeTargets = null;
   private String activeLabel = "";
   private int maxTargets = 3;
   private volatile String fkBlockPath = null;
   private volatile List<GuidanceManager.GuidanceTarget> targets = new ArrayList<>();
   private final AtomicBoolean scanning = new AtomicBoolean(false);
   private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "SeedCrackerX-Guidance-Scan");
      t.setDaemon(true);
      return t;
   });
   private long lastScanMs = 0L;
   private int lastPlayerX = Integer.MIN_VALUE;
   private int lastPlayerZ = Integer.MIN_VALUE;
   private BiomeSource cachedOverworld = null;
   private long cachedOverworldSeed = Long.MIN_VALUE;
   private BiomeSource cachedEnd = null;
   private long cachedEndSeed = Long.MIN_VALUE;
   private RegistryAccess vanillaRegistry = null;
   private long vanillaSeed = Long.MIN_VALUE;
   private String vanillaDim = "";
   private boolean vanillaReady = false;
   private boolean vanillaLocatorReady = false;
   private RandomState vanillaRandomState = null;
   private net.minecraft.world.level.biome.BiomeSource vanillaBiomeSource = null;
   private BiomeResolver vanillaBiomeResolver = null;
   private Sampler vanillaClimateSampler = null;
   private NoiseBasedChunkGenerator vanillaGenerator = null;
   private LevelHeightAccessor vanillaHeight = null;
   private ChunkGeneratorStructureState vanillaStructureState = null;
   private HolderLookup.RegistryLookup<StructureSet> vanillaSetLookup = null;
   private StructureTemplateManager vanillaTemplateManager = null;
   private int vanillaFallbackY = 68;
   private final ConcurrentHashMap<String, Optional<BlockPos>> vanillaCache = new ConcurrentHashMap<>();

   public boolean isActive() {
      return this.activeKind != null;
   }

   public void clear() {
      this.activeKind = null;
      this.biomeTargets = null;
      this.activeLabel = "";
      this.fkBlockPath = null;
      this.targets = new ArrayList<>();
   }

   public void setBlockTarget(String blockPath) {
      if (!OreXrayManager.INSTANCE.isActive()) {
         Compat.sendSystemMessage(Component.literal("[方块指引] 请先使用 /os sz 设置种子。"));
      } else {
         Minecraft mc = Minecraft.getInstance();
         if (mc != null && mc.level != null) {
            OreXrayManager.INSTANCE.warmupResolve(mc.level);
         }

         this.activeKind = GuidanceManager.TargetKind.FK_BLOCK;
         this.biomeTargets = null;
         this.fkBlockPath = blockPath;
         this.activeLabel = blockPath;
         this.maxTargets = 3;
         this.targets = new ArrayList<>();
         this.lastScanMs = 0L;
         Config.get().guidanceEnabled = true;
         Compat.sendSystemMessage(Component.literal("[方块指引] 正在按种子计算最近的 3 个 " + blockPath + "，开启射线指引。输入 /os fk off 关闭。"));
      }
   }

   public void setStructureTarget(GuidanceManager.TargetKind kind, int maxTargets) {
      if (!OreXrayManager.INSTANCE.isActive()) {
         Compat.sendSystemMessage(Component.literal("[指引] 请先使用 /os sz 设置种子。"));
      } else {
         this.activeKind = kind;
         this.biomeTargets = null;
         this.activeLabel = labelFor(kind);
         this.maxTargets = maxTargets;
         this.targets = new ArrayList<>();
         this.lastScanMs = 0L;
         Config.get().guidanceEnabled = true;
         Compat.sendSystemMessage(
            Component.literal("[指引] 已开启 " + this.activeLabel + " 指引（原版数据包定位 + 完整生成校验），屏幕中心将指向最近的 " + maxTargets + " 个目标；坐标均为结构所在维度坐标。输入 /os mdc off 或 /os dx off 关闭。")
         );
      }
   }

   public void setBiomeTarget(GuidanceManager.TargetKind kind, Collection<Biome> fallbackBiomes, String label, int maxTargets) {
      if (!OreXrayManager.INSTANCE.isActive()) {
         Compat.sendSystemMessage(Component.literal("[指引] 请先使用 /os sz 设置种子。"));
      } else {
         this.activeKind = kind;
         this.biomeTargets = fallbackBiomes;
         this.activeLabel = label;
         this.maxTargets = maxTargets;
         this.targets = new ArrayList<>();
         this.lastScanMs = 0L;
         Config.get().guidanceEnabled = true;
         Compat.sendSystemMessage(Component.literal("[指引] 已开启 " + label + " 生物群系指引，屏幕中心将指向最近的 " + maxTargets + " 个目标。输入 /os dx off 关闭。"));
      }
   }

   private List<GuidanceManager.GuidanceTarget> getTargets(Camera camera) {
      if (this.activeKind == null) {
         return this.targets;
      } else {
         Minecraft mc = Minecraft.getInstance();
         if (mc == null || mc.player == null || mc.level == null) {
            return this.targets;
         } else if (!Config.get().guidanceEnabled) {
            return this.targets;
         } else {
            boolean fkMode = this.activeKind == GuidanceManager.TargetKind.FK_BLOCK && this.fkBlockPath != null;
            long now = System.currentTimeMillis();
            BlockPos pp = mc.player.blockPosition();
            int dx = pp.getX() - this.lastPlayerX;
            int dz = pp.getZ() - this.lastPlayerZ;
            long moveSq = fkMode ? 1024L : 64L;
            boolean moved = this.lastPlayerX == Integer.MIN_VALUE || dx * dx + dz * dz > moveSq;
            long rescanMs = fkMode ? 15000L : 3000L;
            boolean due = moved || now - this.lastScanMs >= rescanMs;
            if (due && !this.scanning.get() && this.scanning.compareAndSet(false, true)) {
               this.lastPlayerX = pp.getX();
               this.lastPlayerZ = pp.getZ();
               this.lastScanMs = now;
               long seed = OreXrayManager.INSTANCE.getWorldSeed();
               GuidanceManager.TargetKind kind = this.activeKind;
               Collection<Biome> biomes = this.biomeTargets;
               BlockPos pos = new BlockPos(pp.getX(), pp.getY(), pp.getZ());
               long fSeed = seed;
               int fMax = this.maxTargets;
               String fFkPath = this.fkBlockPath;
               Level fLevel = mc.level;
               RegistryAccess registry = mc.level.registryAccess();
               String dimId = mc.level.dimension().identifier().getPath();
               boolean fPlayerInNether = "the_nether".equals(dimId);
               DimensionType dimType = mc.level.dimensionType();
               int dimMinY = dimType.minY();
               int dimHeight = dimType.height();
               this.scanExecutor.execute(() -> {
                  List<GuidanceManager.GuidanceTarget> result = new ArrayList<>();

                  try {
                     if (fkMode) {
                        for (OreXrayManager.PredictedOre po : OreXrayManager.INSTANCE.predictBlocksAround(fLevel, fFkPath, pos, 10)) {
                           result.add(new GuidanceManager.GuidanceTarget(po.pos(), COLOR_FK));
                        }
                     } else if (biomes != null && !biomes.isEmpty()) {
                        boolean usedVanilla = this.setupVanillaPipeline(registry, fSeed, dimId, dimMinY, dimHeight);
                        this.scanBiomes(pos, fSeed, Config.get().getVersion(), kind, result, usedVanilla);
                     } else {
                        this.scanStructures(pos, fSeed, Config.get().getVersion(), kind, result, registry, dimId, dimMinY, dimHeight, fPlayerInNether);
                     }
                  } catch (Throwable t) {
                     LOGGER.error("[guidance] scan failed", t);
                  }

                  result.sort(Comparator.comparingDouble(t -> t.distanceTo(pos)));

                  while (result.size() > fMax) {
                     result.remove(result.size() - 1);
                  }

                  this.targets = result;
                  this.scanning.set(false);
               });
               return this.targets;
            } else {
               return this.targets;
            }
         }
      }
   }

   /**
    * Structures are now located the way VeinSim finds End Cities: purely from
    * the seed, in the target dimension's own coordinate frame, using a local
    * vanilla data pack registry, the structure placement (grid, frequency,
    * exclusion zone), the weighted structure pick, a full structure generation
    * check and the vanilla /locate position. The old server-registry pipeline
    * and the seedfinding locator only remain as a last-resort fallback.
    */
   private void scanStructures(
      BlockPos playerPos,
      long seed,
      MCVersion version,
      GuidanceManager.TargetKind kind,
      List<GuidanceManager.GuidanceTarget> out,
      RegistryAccess registry,
      String dimId,
      int minY,
      int height,
      boolean playerInNether
   ) {
      int color = kind == GuidanceManager.TargetKind.END_CITY ? COLOR_END_CITY : COLOR_STRUCTURE;
      VanillaStructureLocator.Dim dim = VanillaStructureLocator.dimFor(kind);
      BlockPos origin = dim == null ? playerPos : VanillaStructureLocator.originFor(dim, playerPos, playerInNether);

      VanillaStructureLocator.Result found;
      try {
         found = VanillaStructureLocator.find(kind, seed, origin, Math.max(1, this.maxTargets));
      } catch (Throwable t) {
         LOGGER.warn("[guidance] vanilla structure locator failed", t);
         found = new VanillaStructureLocator.Result(false, List.of());
      }
      if (found.available()) {
         for (BlockPos pos : found.positions()) {
            out.add(new GuidanceManager.GuidanceTarget(pos, color, origin, renderPosFor(pos, dim, playerInNether)));
         }
         return;
      }

      boolean vanillaReady = this.setupVanillaPipeline(registry, seed, dimId, minY, height);
      String expectedDim = switch (kind) {
         case END_CITY -> "the_end";
         case NETHER_FORTRESS, BASTION_REMNANT -> "the_nether";
         default -> "overworld";
      };
      if (vanillaReady && this.vanillaLocatorReady && this.vanillaDim.equals(expectedDim)) {
         for (BlockPos pos : this.locateStructures(kind, playerPos, Math.max(1, this.maxTargets))) {
            out.add(new GuidanceManager.GuidanceTarget(pos, color));
         }
      } else if (!vanillaReady || !this.vanillaLocatorReady) {
         this.scanStructuresFallback(playerPos, seed, version, kind, out, color);
      }
   }

   /** Converts a target dimension coordinate into the player's frame for the HUD arrow. */
   private static BlockPos renderPosFor(BlockPos pos, VanillaStructureLocator.Dim dim, boolean playerInNether) {
      if (dim == null) {
         return pos;
      }
      boolean targetNether = dim == VanillaStructureLocator.Dim.NETHER;
      if (targetNether == playerInNether) {
         return pos;
      }
      return targetNether
         ? new BlockPos(pos.getX() * 8, pos.getY(), pos.getZ() * 8)
         : new BlockPos(pos.getX() / 8, pos.getY(), pos.getZ() / 8);
   }

   /**
    * Locates structures with the 26.3 generation pipeline: registry structure
    * sets, their StructurePlacement (including the new frequency reduction and
    * exclusion zones), the weighted structure pick and a real generation point
    * check. No seedfinding 1.21.3 spacing/salt is involved.
    */
   private List<BlockPos> locateStructures(GuidanceManager.TargetKind kind, BlockPos playerPos, int need) {
      List<BlockPos> out = new ArrayList<>();
      if (!this.vanillaLocatorReady || this.vanillaSetLookup == null) {
         return out;
      }

      Set<String> wanted = new HashSet<>(structureIds(kind));
      if (wanted.isEmpty()) {
         return out;
      }
      this.vanillaFallbackY = this.targetYFor(kind);

      List<StructureSet> sets = new ArrayList<>();
      try {
         this.vanillaSetLookup.listElements().forEach(holder -> {
            StructureSet set = holder.value();
            for (StructureSelectionEntry entry : set.structures()) {
               String id = entry.structure().unwrapKey().map(key -> key.identifier().getPath()).orElse("");
               if (wanted.contains(id)) {
                  sets.add(set);
                  break;
               }
            }
         });
      } catch (Throwable t) {
         LOGGER.warn("[guidance] structure set registry unavailable", t);
         return out;
      }
      if (sets.isEmpty()) {
         return out;
      }

      int pcx = playerPos.getX() >> 4;
      int pcz = playerPos.getZ() >> 4;
      Set<Long> seen = new HashSet<>();

      for (StructureSet set : sets) {
         StructurePlacement placement = set.placement();
         this.scanCandidates(placement, pcx, pcz, cp -> {
            if (out.size() >= need) {
               return false;
            }
            if (!seen.add(cp.pack()) || !this.isStructureChunk26(placement, cp)) {
               return true;
            }
            Optional<BlockPos> pos = this.positionForSet(set, cp, placement, wanted);
            if (pos.isPresent()) {
               out.add(pos.get());
            }
            return out.size() < need;
         });
         if (out.size() >= need) {
            break;
         }
      }
      return out;
   }

   /** Structure ids as registered by 26.3 (villages were split into 5 variants). */
   private static List<String> structureIds(GuidanceManager.TargetKind kind) {
      return switch (kind) {
         case END_CITY -> List.of("end_city");
         case MONUMENT -> List.of("monument");
         case DESERT_PYRAMID -> List.of("desert_pyramid");
         case JUNGLE_PYRAMID -> List.of("jungle_pyramid");
         case SWAMP_HUT -> List.of("swamp_hut");
         case IGLOO -> List.of("igloo");
         case OUTPOST -> List.of("pillager_outpost");
         case SHIPWRECK -> List.of("shipwreck", "shipwreck_beached");
         case BURIED_TREASURE -> List.of("buried_treasure");
         case TRIAL_CHAMBERS -> List.of("trial_chambers");
         case ANCIENT_CITY -> List.of("ancient_city");
         case NETHER_FORTRESS -> List.of("fortress");
         case BASTION_REMNANT -> List.of("bastion_remnant");
         case VILLAGE -> List.of("village_plains", "village_desert", "village_savanna", "village_snowy", "village_taiga");
         default -> List.of();
      };
   }

   /**
    * 26.3 getPotentialStructureChunk takes CHUNK coordinates (it applies
    * floorDiv by spacing internally), so region indices have to be multiplied
    * by spacing. Candidates are emitted ring by ring, like vanilla /locate.
    */
   private void scanCandidates(StructurePlacement placement, int pcx, int pcz, Predicate<ChunkPos> accept) {
      int[] checked = {0};

      if (placement instanceof RandomSpreadStructurePlacement spread) {
         int spacing = Math.max(1, spread.spacing());
         int prx = Math.floorDiv(pcx, spacing);
         int prz = Math.floorDiv(pcz, spacing);
         int maxRing = spacing >= 8 ? MAX_SPREAD_REGIONS : MAX_DENSE_REGIONS;
         long levelSeed = this.vanillaStructureState != null ? this.vanillaStructureState.getLevelSeed() : this.vanillaSeed;

         for (int ring = 0; ring <= maxRing; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
               for (int dz = -ring; dz <= ring; dz++) {
                  if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                     continue;
                  }
                  if (++checked[0] > MAX_CANDIDATES) {
                     return;
                  }
                  ChunkPos cp = spread.getPotentialStructureChunk(levelSeed, (prx + dx) * spacing, (prz + dz) * spacing);
                  if (!accept.test(cp)) {
                     return;
                  }
               }
            }
         }
      } else if (placement instanceof ConcentricRingsStructurePlacement rings && this.vanillaStructureState != null) {
         List<ChunkPos> positions;
         try {
            positions = new ArrayList<>(this.vanillaStructureState.getRingPositionsFor(rings));
         } catch (Throwable t) {
            return;
         }
         positions.sort(Comparator.comparingInt(cp -> (cp.x() - pcx) * (cp.x() - pcx) + (cp.z() - pcz) * (cp.z() - pcz)));
         for (ChunkPos cp : positions) {
            if (++checked[0] > MAX_CANDIDATES || !accept.test(cp)) {
               return;
            }
         }
      } else if (placement instanceof DimensionOriginStructurePlacement) {
         ChunkPos cp = this.vanillaStructureState != null ? this.vanillaStructureState.getDimensionOrigin() : ChunkPos.ZERO;
         accept.test(cp);
      } else {
         for (int ring = 0; ring <= 36; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
               for (int dz = -ring; dz <= ring; dz++) {
                  if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                     continue;
                  }
                  if (++checked[0] > MAX_CANDIDATES) {
                     return;
                  }
                  if (!accept.test(new ChunkPos(pcx + dx, pcz + dz))) {
                     return;
                  }
               }
            }
         }
      }
   }

   /** Full 26.3 placement check (grid + frequency reduction + exclusion zone). */
   private boolean isStructureChunk26(StructurePlacement placement, ChunkPos cp) {
      if (this.vanillaStructureState != null) {
         try {
            return placement.isStructureChunk(this.vanillaStructureState, cp.x(), cp.z());
         } catch (Throwable t) {
            // A synced registry may lack bound tags; keep the lightweight path.
            this.vanillaStructureState = null;
         }
      }
      return placement.applyAdditionalChunkRestrictions(cp.x(), cp.z(), this.vanillaSeed);
   }

   /**
    * Reproduces ChunkGenerator.createStructures' weighted pick for sets with
    * several structures (villages, nether complexes, shipwrecks, ...), so a
    * chunk that generated a different variant is not reported as a hit.
    */
   private Optional<BlockPos> positionForSet(StructureSet set, ChunkPos cp, StructurePlacement placement, Set<String> wanted) {
      List<StructureSelectionEntry> remaining = new ArrayList<>(set.structures());
      WorldgenRandom rand = null;
      if (remaining.size() > 1) {
         rand = new WorldgenRandom(new LegacyRandomSource(0L));
         rand.setLargeFeatureSeed(this.vanillaSeed, cp.x(), cp.z());
      }

      while (!remaining.isEmpty()) {
         int total = 0;
         for (StructureSelectionEntry entry : remaining) {
            total += entry.weight();
         }
         int roll = rand == null ? 0 : rand.nextInt(Math.max(1, total));
         int index = 0;
         for (; index < remaining.size(); index++) {
            roll -= remaining.get(index).weight();
            if (roll < 0) {
               break;
            }
         }
         if (index >= remaining.size()) {
            index = remaining.size() - 1;
         }

         StructureSelectionEntry entry = remaining.get(index);
         String id = entry.structure().unwrapKey().map(key -> key.identifier().getPath()).orElse("");
         Optional<BlockPos> pos = this.generationPoint(id, entry.structure().value(), cp, placement);
         if (pos.isPresent()) {
            return wanted.contains(id) ? pos : Optional.empty();
         }
         remaining.remove(index);
      }
      return Optional.empty();
   }

   private Optional<BlockPos> generationPoint(String id, Structure structure, ChunkPos cp, StructurePlacement placement) {
      String key = this.vanillaSeed + ":" + this.vanillaDim + ":" + id + ":" + cp.x() + ":" + cp.z();
      Optional<BlockPos> cached = this.vanillaCache.get(key);
      if (cached != null) {
         return cached;
      }

      Optional<BlockPos> result;
      try {
         GenerationContext ctx = new GenerationContext(
            this.vanillaRegistry,
            this.vanillaGenerator,
            this.vanillaBiomeSource,
            this.vanillaClimateSampler,
            this.vanillaRandomState,
            this.vanillaTemplateManager,
            this.vanillaSeed,
            cp,
            this.vanillaHeight,
            holder -> structure.biomes().contains(holder)
         );
         result = structure.findValidGenerationPoint(ctx).map(GenerationStub::position);
      } catch (Throwable t) {
         // Jigsaw structures need a StructureTemplateManager, which only the
         // integrated server owns. On remote worlds use the 26.3 biome check
         // plus /locate position instead of silently dropping the target.
         result = this.biomeFallback(structure, cp, placement);
      }

      if (this.vanillaCache.size() > CACHE_LIMIT) {
         this.vanillaCache.clear();
      }
      this.vanillaCache.put(key, result);
      return result;
   }

   private Optional<BlockPos> biomeFallback(Structure structure, ChunkPos cp, StructurePlacement placement) {
      if (this.vanillaBiomeResolver == null) {
         return Optional.empty();
      }
      try {
         int[][] samples = {{8, 8}, {0, 0}, {15, 0}, {0, 15}, {15, 15}};
         for (int[] sample : samples) {
            int bx = cp.getMinBlockX() + sample[0];
            int bz = cp.getMinBlockZ() + sample[1];
            Holder<net.minecraft.world.level.biome.Biome> biome = this.vanillaBiomeResolver.getNoiseBiome(bx >> 2, 16, bz >> 2);
            if (structure.biomes().contains(biome)) {
               BlockPos locate = placement.getLocatePos(cp);
               return Optional.of(new BlockPos(locate.getX(), this.vanillaFallbackY, locate.getZ()));
            }
         }
      } catch (Throwable ignored) {
      }
      return Optional.empty();
   }

   private boolean setupVanillaPipeline(RegistryAccess registry, long seed, String dimId, int minY, int height) {
      if (this.vanillaReady && this.vanillaSeed == seed && this.vanillaDim.equals(dimId) && this.vanillaRegistry == registry) {
         return true;
      }

      this.vanillaReady = false;
      this.vanillaLocatorReady = false;
      this.vanillaStructureState = null;
      this.vanillaTemplateManager = null;
      this.vanillaCache.clear();

      try {
         ResourceKey<NoiseGeneratorSettings> settingsKey;
         net.minecraft.world.level.biome.BiomeSource biomeSource;
         if ("the_end".equals(dimId)) {
            settingsKey = NoiseGeneratorSettings.END;
            biomeSource = TheEndBiomeSource.create(registry.lookupOrThrow(Registries.BIOME));
         } else if ("overworld".equals(dimId)) {
            settingsKey = NoiseGeneratorSettings.OVERWORLD;
            Holder<MultiNoiseBiomeSourceParameterList> preset = registry.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
               .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD);
            biomeSource = MultiNoiseBiomeSource.createFromPreset(preset);
         } else {
            if (!"the_nether".equals(dimId)) {
               return false;
            }

            settingsKey = NoiseGeneratorSettings.NETHER;
            Holder<MultiNoiseBiomeSourceParameterList> netherPreset = registry.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
               .getOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER);
            biomeSource = MultiNoiseBiomeSource.createFromPreset(netherPreset);
         }

         Holder<NoiseGeneratorSettings> settingsHolder = registry.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(settingsKey);
         this.vanillaRandomState = RandomState.create(registry.lookupOrThrow(Registries.NOISE), seed, (NoiseGeneratorSettings)settingsHolder.value());
         this.vanillaBiomeSource = biomeSource;
         this.vanillaBiomeResolver = biomeSource.createCachingResolver(this.vanillaRandomState);
         this.vanillaClimateSampler = this.vanillaRandomState.createClimateSampler(SamplerContext.EMPTY_UNCACHED);
         this.vanillaGenerator = new NoiseBasedChunkGenerator(biomeSource, settingsHolder);
         this.vanillaHeight = LevelHeightAccessor.create(minY, height);
         this.vanillaSetLookup = registry.lookupOrThrow(Registries.STRUCTURE_SET);
         this.vanillaRegistry = registry;
         this.vanillaSeed = seed;
         this.vanillaDim = dimId;
         this.vanillaLocatorReady = true;

         // 26.3 keeps frequency reduction / exclusion zones / ring positions in
         // ChunkGeneratorStructureState. If a synced registry cannot build it,
         // the lightweight 26.3 placement path is used instead.
         try {
            this.vanillaStructureState = this.vanillaGenerator.createState(this.vanillaSetLookup, this.vanillaRandomState, seed);
         } catch (Throwable t) {
            LOGGER.warn("[guidance] full structure state unavailable, using 26.3 lightweight placement", t);
         }

         // Jigsaw structures need real NBT templates. Only the integrated
         // server owns a StructureTemplateManager; remote worlds use the
         // biome-checked /locate fallback in generationPoint().
         try {
            Minecraft mc = Minecraft.getInstance();
            this.vanillaTemplateManager = mc != null && mc.getSingleplayerServer() != null
               ? mc.getSingleplayerServer().getStructureTemplateManager()
               : null;
         } catch (Throwable t) {
            this.vanillaTemplateManager = null;
         }

         this.vanillaReady = true;
         return true;
      } catch (Throwable t) {
         LOGGER.warn("[guidance] vanilla pipeline unavailable for dimension {}, using seedfinding fallback", dimId, t);
         return false;
      }
   }

   private void scanBiomes(
      BlockPos playerPos, long seed, MCVersion version, GuidanceManager.TargetKind kind, List<GuidanceManager.GuidanceTarget> out, boolean vanillaReady
   ) {
      Set<String> vanillaIds = this.vanillaBiomeIds(kind);
      BiomeSource bs = null;
      if (!vanillaReady || !"overworld".equals(this.vanillaDim)) {
         bs = this.biomeSourceFor(Dimension.OVERWORLD, version, seed);
         if (bs == null) {
            return;
         }
      }

      int cx = playerPos.getX();
      int cz = playerPos.getZ();
      Set<Biome> wanted = new HashSet<>(this.biomeTargets);
      List<BlockPos> candidates = new ArrayList<>();

      for (int r = 128; r <= 3072 && candidates.size() < this.maxTargets + 2; r += 128) {
         for (int a = 0; a < 16; a++) {
            double angle = a * 2.0 * Math.PI / 16.0;
            int sx = cx + (int)(r * Math.cos(angle));
            int sz = cz + (int)(r * Math.sin(angle));
            if (this.isWantedBiome(sx, sz, bs, wanted, vanillaIds)) {
               BlockPos bp = new BlockPos(sx, 64, sz);
               if (candidates.stream().noneMatch(p -> p.distManhattan(bp) < 256)) {
                  candidates.add(bp);
               }
            }
         }
      }

      for (BlockPos cand : candidates) {
         BlockPos best = this.refineBiomePoint(cand, playerPos, bs, wanted, vanillaIds);
         if (out.stream().noneMatch(t -> t.pos.distManhattan(best) < 32)) {
            out.add(new GuidanceManager.GuidanceTarget(best, COLOR_BIOME));
         }
      }
   }

   private BlockPos refineBiomePoint(BlockPos cand, BlockPos playerPos, BiomeSource bs, Set<Biome> wanted, Set<String> vanillaIds) {
      BlockPos best = cand;
      double bestDist = this.distSqrXZ(cand.getX(), cand.getZ(), playerPos);

      for (int dx = -96; dx <= 96; dx += 16) {
         for (int dz = -96; dz <= 96; dz += 16) {
            int x = cand.getX() + dx;
            int z = cand.getZ() + dz;
            if (this.isWantedBiome(x, z, bs, wanted, vanillaIds)) {
               double d = this.distSqrXZ(x, z, playerPos);
               if (d < bestDist) {
                  bestDist = d;
                  best = new BlockPos(x, 64, z);
               }
            }
         }
      }

      return best;
   }

   private boolean isWantedBiome(int x, int z, BiomeSource fallback, Set<Biome> wantedFallback, Set<String> vanillaIds) {
      if (this.vanillaReady && this.vanillaBiomeSource != null) {
         try {
            Holder<net.minecraft.world.level.biome.Biome> holder = this.vanillaBiomeResolver.getNoiseBiome(x >> 2, 16, z >> 2);
            String path = holder.unwrapKey().map(k -> k.identifier().getPath()).orElse("");
            return vanillaIds.contains(path);
         } catch (Throwable t) {
            return false;
         }
      } else {
         Biome b = this.safeGetBiome(fallback, x, 64, z);
         return b != null && wantedFallback.stream().anyMatch(w -> w != null && w.getName().equals(b.getName()));
      }
   }

   private Set<String> vanillaBiomeIds(GuidanceManager.TargetKind kind) {
      Set<String> s = new HashSet<>();
      switch (kind) {
         case BIOME_SNOWY:
            add(
               s,
               "snowy_plains",
               "snowy_beach",
               "ice_spikes",
               "snowy_taiga",
               "snowy_slopes",
               "grove",
               "frozen_peaks",
               "jagged_peaks",
               "frozen_ocean",
               "deep_frozen_ocean",
               "frozen_river"
            );
            break;
         case BIOME_MOUNTAIN:
            add(
               s,
               "windswept_hills",
               "windswept_gravelly_hills",
               "windswept_forest",
               "meadow",
               "stony_peaks",
               "jagged_peaks",
               "frozen_peaks",
               "snowy_slopes",
               "grove",
               "savanna_plateau",
               "windswept_savanna",
               "cherry_grove"
            );
            break;
         case BIOME_CLAY:
            add(s, "badlands", "wooded_badlands", "eroded_badlands");
            break;
         case BIOME_MUSHROOM:
            add(s, "mushroom_fields", "mushroom_field_shore");
            break;
         case BIOME_SWAMP:
            add(s, "swamp", "mangrove_swamp");
            break;
         case BIOME_DESERT:
            add(s, "desert");
            break;
         case BIOME_JUNGLE:
            add(s, "jungle", "sparse_jungle", "bamboo_jungle");
            break;
         case BIOME_SAVANNA:
            add(s, "savanna", "savanna_plateau", "windswept_savanna");
            break;
         case BIOME_FOREST:
            add(s, "forest", "birch_forest", "old_growth_birch_forest", "dark_forest", "flower_forest", "cherry_grove");
            break;
         case BIOME_PLAINS:
            add(s, "plains", "sunflower_plains");
            break;
         case BIOME_OCEAN:
            add(
               s,
               "ocean",
               "deep_ocean",
               "warm_ocean",
               "lukewarm_ocean",
               "deep_lukewarm_ocean",
               "cold_ocean",
               "deep_cold_ocean",
               "frozen_ocean",
               "deep_frozen_ocean"
            );
            break;
         case BIOME_RIVER:
            add(s, "river", "frozen_river");
            break;
         case BIOME_TAIGA:
            add(s, "taiga", "snowy_taiga", "old_growth_pine_taiga", "old_growth_spruce_taiga");
            break;
         case BIOME_ICE:
            add(s, "ice_spikes");
      }

      return s;
   }

   private static void add(Set<String> s, String... ids) {
      for (String id : ids) {
         s.add(id);
      }
   }

   private void scanStructuresFallback(
      BlockPos playerPos, long seed, MCVersion version, GuidanceManager.TargetKind kind, List<GuidanceManager.GuidanceTarget> out, int color
   ) {
      RegionStructure<?, ?> feature = this.structureFor(kind, version);
      if (feature != null) {
         int spacing = feature.getSpacing();
         int regionSize = spacing << 4;
         int pRegionX = Math.floorDiv(playerPos.getX(), regionSize);
         int pRegionZ = Math.floorDiv(playerPos.getZ(), regionSize);
         ChunkRand rand = new ChunkRand();
         Dimension dim = feature.getValidDimension();
         BiomeSource bs = this.biomeSourceFor(dim, version, seed);
         int ty = this.targetYFor(kind);

         for (int rx = pRegionX - 6; rx <= pRegionX + 6; rx++) {
            for (int rz = pRegionZ - 6; rz <= pRegionZ + 6; rz++) {
               CPos cp;
               try {
                  cp = feature.getInRegion(seed, rx, rz, rand);
               } catch (Throwable t) {
                  continue;
               }

               if (cp != null && (bs == null || this.canSpawnBiome(feature, cp.getX(), cp.getZ(), bs))) {
                  int bx = (cp.getX() << 4) + 8;
                  int bz = (cp.getZ() << 4) + 8;
                  out.add(new GuidanceManager.GuidanceTarget(new BlockPos(bx, ty, bz), color));
               }
            }
         }
      }
   }

   private int targetYFor(GuidanceManager.TargetKind kind) {
      switch (kind) {
         case END_CITY:
            return 64;
         case MONUMENT:
            return 45;
         case DESERT_PYRAMID:
         case JUNGLE_PYRAMID:
         case SWAMP_HUT:
         case IGLOO:
         case OUTPOST:
         default:
            return 68;
         case SHIPWRECK:
            return 60;
         case BURIED_TREASURE:
            return 55;
         case TRIAL_CHAMBERS:
            return -15;
      }
   }

   private boolean canSpawnBiome(RegionStructure<?, ?> f, int chunkX, int chunkZ, BiomeSource bs) {
      try {
         return f.canSpawn(chunkX, chunkZ, bs);
      } catch (Throwable t) {
         return false;
      }
   }

   private double distSqrXZ(int x, int z, BlockPos p) {
      double dx = x - p.getX();
      double dz = z - p.getZ();
      return dx * dx + dz * dz;
   }

   private Biome safeGetBiome(BiomeSource bs, int x, int y, int z) {
      try {
         return bs.getBiome(x, y, z);
      } catch (Throwable t) {
         return null;
      }
   }

   private BiomeSource biomeSourceFor(Dimension dim, MCVersion version, long seed) {
      try {
         if (dim == Dimension.END) {
            if (this.cachedEnd == null || this.cachedEndSeed != seed) {
               this.cachedEnd = new EndBiomeSource(version, seed);
               this.cachedEndSeed = seed;
            }

            return this.cachedEnd;
         } else {
            if (dim == Dimension.NETHER) {
               return BiomeSource.of(Dimension.NETHER, version, seed);
            }

            if (this.cachedOverworld == null || this.cachedOverworldSeed != seed) {
               this.cachedOverworld = new OverworldBiomeSource(version, seed);
               this.cachedOverworldSeed = seed;
            }

            return this.cachedOverworld;
         }
      } catch (Throwable t) {
         LOGGER.error("[guidance] biome source build failed", t);
         return null;
      }
   }

   private RegionStructure<?, ?> structureFor(GuidanceManager.TargetKind kind, MCVersion version) {
      switch (kind) {
         case END_CITY:
            return Features.END_CITY;
         case MONUMENT:
            return Features.MONUMENT;
         case DESERT_PYRAMID:
            return Features.DESERT_PYRAMID;
         case JUNGLE_PYRAMID:
            return Features.JUNGLE_PYRAMID;
         case SWAMP_HUT:
            return Features.SWAMP_HUT;
         case IGLOO:
            return Features.IGLOO;
         case OUTPOST:
            return Features.PILLAGER_OUTPOST;
         case SHIPWRECK:
            return Features.SHIPWRECK;
         case BURIED_TREASURE:
            return Features.BURIED_TREASURE;
         case TRIAL_CHAMBERS:
            return Features.TRIAL_CHAMBERS;
         case ANCIENT_CITY:
            return null;
         case NETHER_FORTRESS:
            return null;
         case BASTION_REMNANT:
            return null;
         case FK_BLOCK:
         default:
            return null;
         case VILLAGE:
            return new Village(version);
      }
   }

   public static String labelFor(GuidanceManager.TargetKind k) {
      switch (k) {
         case END_CITY:
            return "末地城";
         case MONUMENT:
            return "海底神殿";
         case DESERT_PYRAMID:
            return "沙漠神殿";
         case JUNGLE_PYRAMID:
            return "丛林神庙";
         case SWAMP_HUT:
            return "女巫小屋";
         case IGLOO:
            return "雪屋";
         case OUTPOST:
            return "掠夺者哨塔";
         case SHIPWRECK:
            return "沉船";
         case BURIED_TREASURE:
            return "埋藏的宝藏";
         case TRIAL_CHAMBERS:
            return "试炼密室";
         case ANCIENT_CITY:
            return "古城遗址";
         case NETHER_FORTRESS:
            return "地狱堡垒";
         case BASTION_REMNANT:
            return "猪灵堡垒";
         case FK_BLOCK:
         default:
            return "未知";
         case VILLAGE:
            return "村庄";
         case BIOME_SNOWY:
            return "雪地";
         case BIOME_MOUNTAIN:
            return "山脉";
         case BIOME_CLAY:
            return "黏土山";
         case BIOME_MUSHROOM:
            return "蘑菇岛";
         case BIOME_SWAMP:
            return "沼泽";
         case BIOME_DESERT:
            return "沙漠";
         case BIOME_JUNGLE:
            return "丛林";
         case BIOME_SAVANNA:
            return "草原";
         case BIOME_FOREST:
            return "森林";
         case BIOME_PLAINS:
            return "平原";
         case BIOME_OCEAN:
            return "海洋";
         case BIOME_RIVER:
            return "河流";
         case BIOME_TAIGA:
            return "针叶林";
         case BIOME_ICE:
            return "冰刺之地";
      }
   }

   public static Collection<Biome> biomesForKind(GuidanceManager.TargetKind k) {
      List<Biome> list = new ArrayList<>();
      switch (k) {
         case BIOME_SNOWY:
            addIf(list, Biomes.SNOWY_TUNDRA, Biomes.SNOWY_MOUNTAINS, Biomes.ICE_SPIKES, Biomes.SNOWY_TAIGA, Biomes.SNOWY_TAIGA_HILLS, Biomes.SNOWY_BEACH);
            break;
         case BIOME_MOUNTAIN:
            addIf(
               list,
               Biomes.MOUNTAINS,
               Biomes.WOODED_MOUNTAINS,
               Biomes.GRAVELLY_MOUNTAINS,
               Biomes.EXTREME_HILLS,
               Biomes.EXTREME_HILLS_PLUS,
               Biomes.MOUNTAIN_EDGE,
               Biomes.SAVANNA_ROCK,
               Biomes.SHATTERED_SAVANNA
            );
            break;
         case BIOME_CLAY:
            addIf(
               list, Biomes.BADLANDS, Biomes.BADLANDS_PLATEAU, Biomes.WOODED_BADLANDS_PLATEAU, Biomes.ERODED_BADLANDS, Biomes.MESA_ROCK, Biomes.MESA_CLEAR_ROCK
            );
            break;
         case BIOME_MUSHROOM:
            addIf(list, Biomes.MUSHROOM_FIELDS, Biomes.MUSHROOM_FIELD_SHORE);
            break;
         case BIOME_SWAMP:
            addIf(list, Biomes.SWAMP, Biomes.SWAMP_HILLS);
            break;
         case BIOME_DESERT:
            addIf(list, Biomes.DESERT, Biomes.DESERT_HILLS, Biomes.DESERT_LAKES);
            break;
         case BIOME_JUNGLE:
            addIf(list, Biomes.JUNGLE, Biomes.JUNGLE_HILLS, Biomes.JUNGLE_EDGE, Biomes.BAMBOO_JUNGLE, Biomes.MODIFIED_JUNGLE);
            break;
         case BIOME_SAVANNA:
            addIf(list, Biomes.SAVANNA, Biomes.SAVANNA_PLATEAU, Biomes.SHATTERED_SAVANNA, Biomes.SHATTERED_SAVANNA_PLATEAU);
            break;
         case BIOME_FOREST:
            addIf(list, Biomes.FOREST, Biomes.WOODED_HILLS, Biomes.BIRCH_FOREST, Biomes.TALL_BIRCH_FOREST, Biomes.DARK_FOREST, Biomes.FLOWER_FOREST);
            break;
         case BIOME_PLAINS:
            addIf(list, Biomes.PLAINS, Biomes.SUNFLOWER_PLAINS);
            break;
         case BIOME_OCEAN:
            addIf(
               list,
               Biomes.OCEAN,
               Biomes.DEEP_OCEAN,
               Biomes.WARM_OCEAN,
               Biomes.LUKEWARM_OCEAN,
               Biomes.COLD_OCEAN,
               Biomes.FROZEN_OCEAN,
               Biomes.DEEP_WARM_OCEAN,
               Biomes.DEEP_LUKEWARM_OCEAN,
               Biomes.DEEP_COLD_OCEAN,
               Biomes.DEEP_FROZEN_OCEAN
            );
            break;
         case BIOME_RIVER:
            addIf(list, Biomes.RIVER, Biomes.FROZEN_RIVER);
            break;
         case BIOME_TAIGA:
            addIf(
               list,
               Biomes.TAIGA,
               Biomes.TAIGA_HILLS,
               Biomes.GIANT_TREE_TAIGA,
               Biomes.GIANT_TREE_TAIGA_HILLS,
               Biomes.GIANT_SPRUCE_TAIGA,
               Biomes.GIANT_SPRUCE_TAIGA_HILLS
            );
            break;
         case BIOME_ICE:
            addIf(list, Biomes.ICE_SPIKES);
      }

      return list;
   }

   private static void addIf(List<Biome> list, Biome... biomes) {
      for (Biome b : biomes) {
         if (b != null) {
            list.add(b);
         }
      }
   }

   public void renderHud(GuiGraphicsExtractor gui) {
      if (this.activeKind != null && Config.get().guidanceEnabled) {
         Minecraft mc = Minecraft.getInstance();
         if (mc != null && mc.player != null && mc.level != null) {
            Camera camera = Compat.mainCamera(mc.gameRenderer);
            List<GuidanceManager.GuidanceTarget> list = this.getTargets(camera);
            if (list.isEmpty()) {
               String msg = this.scanning.get() ? "[指引] 正在计算 " + this.activeLabel + " 位置…" : "[指引] 附近未找到 " + this.activeLabel;
               gui.text(mc.font, msg, 8, 8, 16777045);
            } else {
               int screenW = gui.guiWidth();
               int screenH = gui.guiHeight();
               int cx = screenW / 2;
               int cy = screenH / 2;
               Vec3 eye = camera.position();
               Vector3f look = new Vector3f(camera.forwardVector());
               Vector3f up = new Vector3f(camera.upVector());
               Vector3f right = new Vector3f();
               look.cross(up, right);
               right.normalize();
               float fov = ((Integer)mc.options.fov().get()).floatValue();
               float tanHalfFov = (float)Math.tan(Math.toRadians(fov) / 2.0);
               float aspect = (float)screenW / screenH;
               int labelY = 8;

               for (GuidanceManager.GuidanceTarget t : list) {
                  Vec3 rel = new Vec3(t.renderPos.getX() + 0.5 - eye.x, t.renderPos.getY() + 0.5 - eye.y, t.renderPos.getZ() + 0.5 - eye.z);
                  float xView = (float)(rel.x * right.x + rel.y * right.y + rel.z * right.z);
                  float yView = (float)(rel.x * up.x + rel.y * up.y + rel.z * up.z);
                  float zView = (float)(rel.x * look.x + rel.y * look.y + rel.z * look.z);
                  double dist = rel.length();
                  String label = this.activeLabel + String.format(" [%d, %d] %.0fm", t.pos.getX(), t.pos.getZ(), dist);
                  if (zView > 0.1) {
                     float sx = cx + xView / (zView * tanHalfFov * aspect) * (screenW * 0.5F);
                     float sy = cy - yView / (zView * tanHalfFov) * (screenH * 0.5F);
                     this.drawCenteredLine(gui, cx, cy, (int)sx, (int)sy, t.color);
                     gui.text(mc.font, label, (int)sx - mc.font.width(label) / 2, (int)sy - 16, t.color);
                  } else {
                     float dxScr = cx + xView / (Math.abs(xView) + 0.001F) * (screenW * 0.35F);
                     float dyScr = cy - yView / (Math.abs(yView) + 0.001F) * (screenH * 0.35F);
                     gui.text(mc.font, "< " + label, (int)dxScr, (int)dyScr, 16777045);
                  }

                  gui.text(mc.font, label, 8, labelY, t.color);
                  labelY += 12;
               }
            }
         }
      }
   }

   private void drawCenteredLine(GuiGraphicsExtractor gui, int x0, int y0, int x1, int y1, int color) {
      int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
      if (steps != 0) {
         int stride = Math.max(1, steps / 200);

         for (int i = 0; i <= steps; i += stride) {
            int x = x0 + (x1 - x0) * i / steps;
            int y = y0 + (y1 - y0) * i / steps;
            gui.fill(x - 1, y - 1, x + 1, y + 1, color);
         }

         gui.fill(x1 - 1, y1 - 1, x1 + 1, y1 + 1, color);
      }
   }

   public static class GuidanceTarget {
      public final BlockPos pos;
      public final int color;
      /** Coordinate frame origin used for distance sorting (target dimension frame). */
      public final BlockPos origin;
      /** Position used for the on-screen arrow (player dimension frame). */
      public final BlockPos renderPos;

      public GuidanceTarget(BlockPos pos, int color) {
         this(pos, color, null, pos);
      }

      public GuidanceTarget(BlockPos pos, int color, BlockPos origin, BlockPos renderPos) {
         this.pos = pos;
         this.color = color;
         this.origin = origin;
         this.renderPos = renderPos == null ? pos : renderPos;
      }

      public double distanceTo(BlockPos from) {
         BlockPos ref = this.origin == null ? from : this.origin;
         return Math.sqrt(this.pos.distToCenterSqr(ref.getX() + 0.5, ref.getY() + 0.5, ref.getZ() + 0.5));
      }
   }

   public enum TargetKind {
      END_CITY,
      MONUMENT,
      DESERT_PYRAMID,
      JUNGLE_PYRAMID,
      SWAMP_HUT,
      IGLOO,
      OUTPOST,
      SHIPWRECK,
      BURIED_TREASURE,
      TRIAL_CHAMBERS,
      ANCIENT_CITY,
      NETHER_FORTRESS,
      BASTION_REMNANT,
      FK_BLOCK,
      VILLAGE,
      BIOME_SNOWY,
      BIOME_MOUNTAIN,
      BIOME_CLAY,
      BIOME_MUSHROOM,
      BIOME_SWAMP,
      BIOME_DESERT,
      BIOME_JUNGLE,
      BIOME_SAVANNA,
      BIOME_FOREST,
      BIOME_PLAINS,
      BIOME_OCEAN,
      BIOME_RIVER,
      BIOME_TAIGA,
      BIOME_ICE;
   }
}
