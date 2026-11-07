package kaptainwutax.seedcrackerX.guidance;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Stream;
import kaptainwutax.seedcrackerX.elytra.CityCandidate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.VanillaPackResources;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate.Sampler;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureSet.StructureSelectionEntry;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Seed based structure locator re-implemented after VeinSim 3.0.0's
 * StructureFinder (the code path used for End Cities).
 *
 * <p>Everything is built locally from the vanilla data pack instead of the
 * registry the server sent us: a full worldgen registry, a
 * {@link ChunkGeneratorStructureState} (frequency reduction, exclusion zones,
 * concentric rings) and a real {@link StructureTemplateManager} so jigsaw
 * structures such as End Cities can be fully generated and validated even on
 * a multiplayer world. Candidates come from the placement's own grid, the
 * returned coordinate is the vanilla {@code /locate} position
 * ({@link StructurePlacement#getLocatePos(ChunkPos)}) and the Y value is the
 * worldgen surface height (or the structure's fixed depth).</p>
 */
public final class VanillaStructureLocator {
   private static final Logger LOGGER = LoggerFactory.getLogger("SeedCrackerX-VanillaLocator");
   private static final int CANDIDATE_BUDGET = 200000;
   private static final int MAX_NEAREST_RINGS = 256;
   private static final long SEARCH_DEADLINE_MS = 10000L;
   // Offline ship filtering needs many more placement hits than ships, and far
   // origins need more rings. Only the new city API uses this longer deadline;
   // the guidance HUD keeps its original 10s budget.
   private static final long CITY_SEARCH_DEADLINE_MS = 30000L;
   private static final int SHIP_SCAN_FACTOR = 8;
   private static final long RESULT_TTL_MS = 15000L;
   private static final Object REGISTRY_LOCK = new Object();
   private static final Map<String, Ctx> CTX_CACHE = new ConcurrentHashMap<>();
   private static final Map<String, CachedResult> RESULT_CACHE = new ConcurrentHashMap<>();
   private static final Map<String, CachedCityResult> CITY_RESULT_CACHE = new ConcurrentHashMap<>();

   private static volatile HolderLookup.Provider registries;
   private static ResourceManager resourceManager;
   private static RegistryAccess.Frozen loadedAccess;
   private static RegistryAccess fullAccess;
   private static volatile StructureTemplateManager templates;
   private static LevelStorageSource.LevelStorageAccess templateStorage;
   private static volatile boolean templatesUnavailable;
   private static volatile int genFailures;
   private static volatile Field templateNameField;
   private static volatile boolean templateNameFieldResolved;

   private VanillaStructureLocator() {
   }

   /** Target dimension of a guidance target. */
   public enum Dim {
      OVERWORLD,
      NETHER,
      END
   }

   /** {@code available=false} means the vanilla pipeline could not be built. */
   public record Result(boolean available, List<BlockPos> positions) {
   }

   /** End-city candidates that the offline jigsaw check believes have a ship. */
   public record CityResult(boolean available, List<CityCandidate> cities) {
   }

   private record Spec(Dim dim, boolean surface, int fixedY, Set<ResourceKey<Structure>> keys) {
   }

   private record Ctx(
      RandomState randomState,
      BiomeSource biomeSource,
      Sampler sampler,
      ChunkGeneratorStructureState state,
      NoiseBasedChunkGenerator generator,
      LevelHeightAccessor height,
      ResourceKey<Level> levelKey
   ) {
   }

   /**
    * {@code hasShip} is the offline filter (unknown/fallback counts as true so
    * an undetectable ship is never discarded), {@code shipKnown} tells the
    * caller whether the result came from a real jigsaw template inspection.
    * {@code shipPos} is the centre of the actual {@code end_city/ship} piece's
    * bounding box - null when templates were unavailable or the piece could not
    * be resolved. The city's {@code /locate} position is NOT the ship position.
    */
   private record Hit(BlockPos pos, double distance, boolean hasShip, boolean shipKnown, BlockPos shipPos) {
   }

   private record Generated(Holder<Structure> holder, boolean hasShip, boolean shipKnown, BlockPos shipPos) {
   }

   private record CachedResult(long time, List<BlockPos> positions) {
   }

   private record CachedCityResult(long time, List<CityCandidate> cities) {
   }

   /** Structure ids / dimension / depth as VeinSim's StructureKind defines them. */
   private static Spec specFor(GuidanceManager.TargetKind kind) {
      return switch (kind) {
         case END_CITY -> new Spec(Dim.END, true, 0, Set.of(BuiltinStructures.END_CITY));
         case MONUMENT -> new Spec(Dim.OVERWORLD, true, 0, Set.of(BuiltinStructures.OCEAN_MONUMENT));
         case DESERT_PYRAMID -> new Spec(Dim.OVERWORLD, true, 0, Set.of(BuiltinStructures.DESERT_PYRAMID));
         case JUNGLE_PYRAMID -> new Spec(Dim.OVERWORLD, true, 0, Set.of(BuiltinStructures.JUNGLE_TEMPLE));
         case SWAMP_HUT -> new Spec(Dim.OVERWORLD, true, 0, Set.of(BuiltinStructures.SWAMP_HUT));
         case IGLOO -> new Spec(Dim.OVERWORLD, true, 0, Set.of(BuiltinStructures.IGLOO));
         case OUTPOST -> new Spec(Dim.OVERWORLD, true, 0, Set.of(BuiltinStructures.PILLAGER_OUTPOST));
         case SHIPWRECK -> new Spec(Dim.OVERWORLD, true, 0, Set.of(BuiltinStructures.SHIPWRECK, BuiltinStructures.SHIPWRECK_BEACHED));
         case BURIED_TREASURE -> new Spec(Dim.OVERWORLD, true, 0, Set.of(BuiltinStructures.BURIED_TREASURE));
         case TRIAL_CHAMBERS -> new Spec(Dim.OVERWORLD, false, -20, Set.of(BuiltinStructures.TRIAL_CHAMBERS));
         case ANCIENT_CITY -> new Spec(Dim.OVERWORLD, false, -51, Set.of(BuiltinStructures.ANCIENT_CITY));
         case NETHER_FORTRESS -> new Spec(Dim.NETHER, false, 48, Set.of(BuiltinStructures.FORTRESS));
         case BASTION_REMNANT -> new Spec(Dim.NETHER, false, 48, Set.of(BuiltinStructures.BASTION_REMNANT));
         case VILLAGE -> new Spec(
            Dim.OVERWORLD,
            true,
            0,
            Set.of(
               BuiltinStructures.VILLAGE_PLAINS,
               BuiltinStructures.VILLAGE_DESERT,
               BuiltinStructures.VILLAGE_SAVANNA,
               BuiltinStructures.VILLAGE_SNOWY,
               BuiltinStructures.VILLAGE_TAIGA
            )
         );
         default -> null;
      };
   }

   public static Dim dimFor(GuidanceManager.TargetKind kind) {
      Spec spec = specFor(kind);
      return spec == null ? null : spec.dim();
   }

   /**
    * Converts the player's position into the coordinate frame of the target
    * dimension (nether is 1/8 of the overworld); same as VeinSim.
    */
   public static BlockPos originFor(Dim dim, BlockPos playerPos, boolean playerInNether) {
      boolean targetNether = dim == Dim.NETHER;
      if (targetNether == playerInNether) {
         return playerPos;
      }
      return targetNether
         ? new BlockPos(playerPos.getX() / 8, playerPos.getY(), playerPos.getZ() / 8)
         : new BlockPos(playerPos.getX() * 8, playerPos.getY(), playerPos.getZ() * 8);
   }

   /** Returns the nearest {@code need} structures, closest first. Signature is kept for the guidance HUD. */
   public static Result find(GuidanceManager.TargetKind kind, long seed, BlockPos origin, int need) {
      Spec spec = specFor(kind);
      if (spec == null || need <= 0) {
         return new Result(false, List.of());
      }

      // The guidance HUD rescans every few seconds / every few blocks; a full
      // jigsaw generation check is expensive, so reuse it for nearby origins.
      long now = System.currentTimeMillis();
      String cacheKey = kind.name() + "@" + seed + "@" + need + "@" + (origin.getX() >> 7) + "," + (origin.getZ() >> 7);
      CachedResult cached = RESULT_CACHE.get(cacheKey);
      if (cached != null && now - cached.time() < RESULT_TTL_MS) {
         return new Result(true, cached.positions());
      }

      List<Hit> hits;
      try {
         hits = collect(kind, seed, origin, need, false);
      } catch (Throwable t) {
         LOGGER.warn("[guidance] vanilla worldgen pipeline unavailable", t);
         return new Result(false, List.of());
      }

      hits.sort(Comparator.comparingDouble(Hit::distance));
      List<BlockPos> out = new ArrayList<>();
      for (int i = 0; i < Math.min(need, hits.size()); i++) {
         out.add(hits.get(i).pos());
      }
      if (RESULT_CACHE.size() > 64) {
         RESULT_CACHE.clear();
      }
      RESULT_CACHE.put(cacheKey, new CachedResult(now, List.copyOf(out)));
      return new Result(true, out);
   }

   /**
    * Parallel API for the elytra hunt: nearest {@code need} end cities whose
    * generated jigsaw start contains the vanilla {@code end_city/ship} piece.
    * The filtering is only an acceleration - the caller must still verify the
    * item frame on site (custom datapacks / worldgen can invalidate it).
    *
    * <p>Each candidate also carries the ship piece's bounding-box centre
    * ({@link CityCandidate#shipPos()}). This is NOT the city's placement
    * start: measured offsets across three seeds were 35-136 blocks in X/Z and
    * 30-110 blocks in Y. Navigation and entity scans must use that point.</p>
    */
   public static CityResult findEndCitiesWithShip(long seed, BlockPos origin, int need) {
      if (need <= 0) {
         return new CityResult(false, List.of());
      }

      long now = System.currentTimeMillis();
      // V2: candidates now carry the resolved ship position; never reuse a
      // pre-V2 cache entry that only had the city's /locate coordinate.
      String cacheKey = "END_CITY@SHIP@V2@" + seed + "@" + need + "@" + (origin.getX() >> 7) + "," + (origin.getZ() >> 7);
      CachedCityResult cached = CITY_RESULT_CACHE.get(cacheKey);
      if (cached != null && now - cached.time() < RESULT_TTL_MS) {
         return new CityResult(true, cached.cities());
      }

      // Only about half of the end cities spawn a ship: collect() stops as soon
      // as `need` ship-bearing hits are available, instead of `need` raw hits.
      List<Hit> hits;
      try {
         hits = collect(GuidanceManager.TargetKind.END_CITY, seed, origin, need, true);
      } catch (Throwable t) {
         LOGGER.warn("[guidance] end city ship scan failed", t);
         return new CityResult(false, List.of());
      }

      hits.sort(Comparator.comparingDouble(Hit::distance));
      List<CityCandidate> cities = new ArrayList<>();
      for (Hit hit : hits) {
         if (hit.hasShip()) {
            cities.add(new CityCandidate(hit.pos(), hit.shipKnown(), hit.shipPos()));
            if (cities.size() >= need) {
               break;
            }
         }
      }
      long located = cities.stream().filter(CityCandidate::shipLocated).count();
      LOGGER.info("[guidance] ship candidates: {} total, {} with resolved ship position", cities.size(), located);
      if (CITY_RESULT_CACHE.size() > 32) {
         CITY_RESULT_CACHE.clear();
      }
      CITY_RESULT_CACHE.put(cacheKey, new CachedCityResult(now, List.copyOf(cities)));
      return new CityResult(true, cities);
   }

   /** Shared placement enumeration for {@link #find} and {@link #findEndCitiesWithShip}. */
   private static List<Hit> collect(
      GuidanceManager.TargetKind kind, long seed, BlockPos origin, int need, boolean shipOnly
   ) {
      Spec spec = specFor(kind);
      Ctx ctx = context(spec.dim(), seed);
      List<Hit> hits = new ArrayList<>();
      int[] budget = {CANDIDATE_BUDGET};
      long deadline = System.currentTimeMillis() + (shipOnly ? CITY_SEARCH_DEADLINE_MS : SEARCH_DEADLINE_MS);

      for (Holder<StructureSet> setHolder : ctx.state().possibleStructureSets()) {
         StructureSet set = setHolder.value();
         if (!containsAny(set, spec.keys())) {
            continue;
         }
         StructurePlacement placement = set.placement();
         if (placement instanceof ConcentricRingsStructurePlacement rings) {
            collectRingPositions(ctx, spec, rings, origin, hits);
         } else if (placement instanceof RandomSpreadStructurePlacement spread) {
            collectSpreadPositions(ctx, spec, set, spread, origin, need, hits, budget, deadline, shipOnly);
         }
      }
      return hits;
   }

   private static int countShips(List<Hit> hits) {
      int count = 0;
      for (Hit hit : hits) {
         if (hit.hasShip()) {
            count++;
         }
      }
      return count;
   }

   public static void clearCache() {
      CTX_CACHE.clear();
      RESULT_CACHE.clear();
      CITY_RESULT_CACHE.clear();
   }

   /** Only invalidates the elytra hunt's own candidates, leaving the guidance HUD cache intact. */
   public static void clearCityCache() {
      CITY_RESULT_CACHE.clear();
   }

   private static HolderLookup.Provider registries() {
      HolderLookup.Provider local = registries;
      if (local == null) {
         synchronized (REGISTRY_LOCK) {
            local = registries;
            if (local == null) {
               long start = System.currentTimeMillis();
               local = buildRegistries();
               registries = local;
               LOGGER.info("[guidance] built vanilla worldgen registries in {} ms", System.currentTimeMillis() - start);
            }
         }
      }
      return local;
   }

   /** Load the vanilla data pack on the client, independently of the server's datapacks. */
   private static HolderLookup.Provider buildRegistries() {
      VanillaPackResources vanillaPack = ServerPacksSource.createVanillaPackSource();
      ResourceManager resources = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(vanillaPack.fullResources()));
      resourceManager = resources;

      RegistryAccess.Frozen builtIn = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
      List<HolderLookup.RegistryLookup<?>> base = TagLoader.buildUpdatedLookups(
         builtIn, TagLoader.loadTagsForExistingRegistries(resources, builtIn)
      );
      RegistryAccess.Frozen loaded = RegistryDataLoader.load(
         resources, base, RegistryDataLoader.WORLD_REGISTRIES, ForkJoinPool.commonPool()
      ).join();
      loadedAccess = loaded;
      List<Registry.PendingTags<?>> worldgenTags = TagLoader.loadTagsForExistingRegistries(resources, loaded);
      return HolderLookup.Provider.create(
         Stream.concat(base.stream(), TagLoader.buildUpdatedLookups(loaded, worldgenTags).stream())
      );
   }

   private static RegistryAccess fullAccess() {
      registries();
      if (fullAccess == null) {
         RegistryAccess builtIn = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
         fullAccess = new RegistryAccess.ImmutableRegistryAccess(
            Stream.concat(builtIn.registries(), loadedAccess.registries())
         );
      }
      return fullAccess;
   }

   /**
    * A StructureTemplateManager that owns real vanilla structure templates.
    * VeinSim needs a mixin to allow a null LevelStorageAccess; here a temporary
    * level storage is used instead, which also works when the two mods are
    * installed together and keeps the full generation check available.
    */
   private static StructureTemplateManager templates() {
      if (templates == null && !templatesUnavailable) {
         synchronized (REGISTRY_LOCK) {
            if (templates == null && !templatesUnavailable) {
               try {
                  HolderLookup.Provider reg = registries();
                  LevelStorageSource.LevelStorageAccess storage = null;
                  try {
                     Path dir = Files.createTempDirectory("openseed-worldgen-");
                     LevelStorageSource source = LevelStorageSource.createDefault(dir);
                     storage = source.createAccess("openseed");
                     templateStorage = storage;
                     Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        LevelStorageSource.LevelStorageAccess s = templateStorage;
                        if (s != null) {
                           s.safeClose();
                        }
                     }, "SeedCrackerX-TemplateStorage-Cleanup"));
                  } catch (Throwable t) {
                     LOGGER.warn("[guidance] temporary level storage unavailable, trying null storage", t);
                  }
                  templates = new StructureTemplateManager(
                     resourceManager, storage, DataFixers.getDataFixer(), reg.lookupOrThrow(Registries.BLOCK)
                  );
               } catch (Throwable t) {
                  templatesUnavailable = true;
                  LOGGER.warn("[guidance] structure templates unavailable, falling back to placement only", t);
               }
            }
         }
      }
      return templates;
   }

   private static Ctx context(Dim dim, long seed) {
      if (CTX_CACHE.size() > 12) {
         CTX_CACHE.clear();
      }
      return CTX_CACHE.computeIfAbsent(dim.name() + "@" + seed, key -> buildContext(dim, seed));
   }

   private static Ctx buildContext(Dim dim, long seed) {
      HolderLookup.Provider reg = registries();
      ResourceKey<NoiseGeneratorSettings> noiseKey = switch (dim) {
         case OVERWORLD -> NoiseGeneratorSettings.OVERWORLD;
         case NETHER -> NoiseGeneratorSettings.NETHER;
         case END -> NoiseGeneratorSettings.END;
      };
      RandomState randomState = RandomState.create(
         reg.lookupOrThrow(Registries.NOISE), seed, reg.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(noiseKey).value()
      );
      Holder<NoiseGeneratorSettings> settings = reg.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(noiseKey);
      BiomeSource biomeSource = switch (dim) {
         case OVERWORLD -> MultiNoiseBiomeSource.createFromPreset(
            reg.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
               .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD)
         );
         case NETHER -> MultiNoiseBiomeSource.createFromPreset(
            reg.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
               .getOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER)
         );
         case END -> TheEndBiomeSource.create(reg.lookupOrThrow(Registries.BIOME));
      };
      NoiseBasedChunkGenerator generator = new NoiseBasedChunkGenerator(biomeSource, settings);
      ChunkGeneratorStructureState state = ChunkGeneratorStructureState.createForNormal(
         randomState, seed, generator.getOrigin(randomState), biomeSource, reg.lookupOrThrow(Registries.STRUCTURE_SET)
      );
      state.ensureStructuresGenerated();

      LevelHeightAccessor height = switch (dim) {
         case OVERWORLD -> LevelHeightAccessor.create(-64, 384);
         case NETHER, END -> LevelHeightAccessor.create(0, 256);
      };
      ResourceKey<Level> levelKey = switch (dim) {
         case OVERWORLD -> Level.OVERWORLD;
         case NETHER -> Level.NETHER;
         case END -> Level.END;
      };
      Sampler sampler = randomState.createClimateSampler(SamplerContext.EMPTY_UNCACHED);
      return new Ctx(randomState, biomeSource, sampler, state, generator, height, levelKey);
   }

   private static boolean containsAny(StructureSet set, Set<ResourceKey<Structure>> wanted) {
      for (StructureSelectionEntry entry : set.structures()) {
         if (entry.structure().unwrapKey().map(wanted::contains).orElse(false)) {
            return true;
         }
      }
      return false;
   }

   /** Reproduces ChunkGenerator.createStructures' weighted pick, then fully generates the start. */
   private static Generated selectStructure(Ctx ctx, StructureSet set, ChunkPos chunk) {
      List<StructureSelectionEntry> remaining = new ArrayList<>(set.structures());
      if (remaining.size() == 1) {
         return tryGenerate(ctx, remaining.getFirst().structure(), chunk);
      }

      WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
      random.setLargeFeatureSeed(ctx.state().getLevelSeed(), chunk.getMinBlockX() >> 4, chunk.getMinBlockZ() >> 4);
      int totalWeight = 0;
      for (StructureSelectionEntry entry : remaining) {
         totalWeight += entry.weight();
      }

      while (!remaining.isEmpty()) {
         int roll = random.nextInt(Math.max(1, totalWeight));
         int index = 0;
         for (StructureSelectionEntry entry : remaining) {
            roll -= entry.weight();
            if (roll < 0) {
               break;
            }
            index++;
         }
         if (index >= remaining.size()) {
            index = remaining.size() - 1;
         }

         StructureSelectionEntry picked = remaining.get(index);
         Generated generated = tryGenerate(ctx, picked.structure(), chunk);
         if (generated != null) {
            return generated;
         }
         remaining.remove(index);
         totalWeight -= picked.weight();
      }
      return null;
   }

   private static Generated tryGenerate(Ctx ctx, Holder<Structure> holder, ChunkPos chunk) {
      StructureTemplateManager templateManager = templates();
      if (templateManager == null) {
         // Placement + weighted pick only (same fallback as VeinSim). Without
         // real templates we cannot tell whether a ship exists: do not veto.
         return new Generated(holder, true, false, null);
      }
      Structure structure = holder.value();
      boolean endCity = holder.unwrapKey()
         .map(key -> "end_city".equals(key.identifier().getPath()))
         .orElse(false);
      try {
         StructureStart start = structure.generate(
            holder,
            ctx.levelKey(),
            fullAccess(),
            ctx.generator(),
            ctx.biomeSource(),
            ctx.sampler(),
            ctx.randomState(),
            templateManager,
            ctx.state().getLevelSeed(),
            chunk,
            0,
            ctx.height(),
            structure.biomes()::contains
         );
         if (start.isValid()) {
            // Only end cities need the (per-piece) ship inspection; other
            // guidance targets keep the exact old behaviour and cost.
            // Capture the ship piece's bounding box centre while the generated
            // start is still available - this is the only place where the real
            // ship coordinate (not the city's /locate position) can be read.
            boolean hasShip = !endCity || hasShipPiece(start);
            BlockPos shipPos = endCity ? shipPosOf(start) : null;
            return new Generated(holder, hasShip, endCity, shipPos);
         }
      } catch (Throwable t) {
         if (genFailures++ < 3) {
            LOGGER.warn("[guidance] generation check failed at {}", chunk, t);
         }
         return new Generated(holder, true, false, null);
      }
      return null;
   }

   /**
    * Ship detection for 26.3. End cities are NOT jigsaw pool elements: they
    * are {@link TemplateStructurePiece}s whose {@code templateName} is exactly
    * {@code "ship"} for the end ship (verified against the vanilla template
    * {@code data/minecraft/structure/end_city/ship.nbt}). Pool-element and
    * loose string checks remain as fallbacks for other versions/datapacks.
    * Any uncertainty resolves to {@code true} so a real ship is never lost.
    */
   /**
    * Centre of the real end-ship piece's bounding box, in world coordinates.
    * The city's placement start is the tower base; the ship hangs off a tower
    * and is often 20-60 blocks away, so navigation must use this instead.
    * Uses exactly the same identification rules as {@link #hasShipPiece}.
    */
   private static BlockPos shipPosOf(StructureStart start) {
      if (start == null) {
         return null;
      }
      try {
         for (StructurePiece piece : start.getPieces()) {
            if (piece == null) {
               continue;
            }
            String name = templateNameOf(piece).toLowerCase(Locale.ROOT);
            boolean ship = "ship".equals(name)
               || name.endsWith("/ship")
               || name.contains("end_city/ship")
               || name.contains("end_city\\ship");
            if (!ship) {
               String s = describePiece(piece).toLowerCase(Locale.ROOT);
               ship = s.contains("end_city/ship") || s.contains("end_city\\ship")
                  || s.contains("end_ship")
                  || (s.contains("ship") && !s.contains("shipwreck"));
            }
            if (!ship) {
               continue;
            }
            BoundingBox box = piece.getBoundingBox();
            if (box == null) {
               return null;
            }
            BlockPos centre = box.getCenter();
            return new BlockPos(centre.getX(), centre.getY(), centre.getZ());
         }
      } catch (Throwable t) {
         LOGGER.warn("[guidance] ship bounding box unavailable", t);
      }
      return null;
   }

   private static boolean hasShipPiece(StructureStart start) {
      if (start == null) {
         return true;
      }
      try {
         for (StructurePiece piece : start.getPieces()) {
            if (piece == null) {
               continue;
            }
            String name = templateNameOf(piece);
            String normalized = name.toLowerCase(Locale.ROOT);
            if ("ship".equals(normalized)
               || normalized.endsWith("/ship")
               || normalized.contains("end_city/ship")
               || normalized.contains("end_city\\ship")) {
               return true;
            }
            String s = describePiece(piece).toLowerCase(Locale.ROOT);
            if (s.contains("end_city/ship") || s.contains("end_city\\ship") || s.contains("end_ship")) {
               return true;
            }
            if (s.contains("ship") && !s.contains("shipwreck")) {
               return true;
            }
         }
      } catch (Throwable t) {
         return true;
      }
      return false;
   }

   /** Reads {@code TemplateStructurePiece.templateName} (protected in 26.3). */
   private static String templateNameOf(StructurePiece piece) {
      if (!(piece instanceof TemplateStructurePiece)) {
         return "";
      }
      Field field = templateNameField;
      if (!templateNameFieldResolved) {
         synchronized (REGISTRY_LOCK) {
            if (!templateNameFieldResolved) {
               try {
                  Field resolved = TemplateStructurePiece.class.getDeclaredField("templateName");
                  resolved.setAccessible(true);
                  templateNameField = resolved;
               } catch (Throwable t) {
                  LOGGER.warn("[guidance] templateName field unavailable, using string fallback", t);
               }
               templateNameFieldResolved = true;
            }
         }
         field = templateNameField;
      }
      if (field == null) {
         return "";
      }
      try {
         Object value = field.get(piece);
         return value == null ? "" : value.toString();
      } catch (Throwable t) {
         return "";
      }
   }

   private static String describePiece(StructurePiece piece) {
      StringBuilder sb = new StringBuilder();
      try {
         sb.append(piece.toString());
      } catch (Throwable ignored) {
      }
      String templateName = templateNameOf(piece);
      if (!templateName.isEmpty()) {
         sb.append(" templateName=").append(templateName);
      }
      if (piece instanceof PoolElementStructurePiece poolPiece) {
         try {
            StructurePoolElement element = poolPiece.getElement();
            if (element != null) {
               sb.append(' ').append(element);
               if (element instanceof SinglePoolElement single && single.getTemplateLocation() != null) {
                  sb.append(' ').append(single.getTemplateLocation());
               }
            }
         } catch (Throwable ignored) {
            // Reflection-free direct access failed: the toString above is the fallback.
         }
      }
      return sb.toString();
   }

   private static void collectSpreadPositions(
      Ctx ctx,
      Spec spec,
      StructureSet set,
      RandomSpreadStructurePlacement spread,
      BlockPos origin,
      int need,
      List<Hit> out,
      int[] budget,
      long deadline,
      boolean shipOnly
   ) {
      int spacing = Math.max(1, spread.spacing());
      int cellX = Math.floorDiv(origin.getX() >> 4, spacing);
      int cellZ = Math.floorDiv(origin.getZ() >> 4, spacing);
      int firstHitRing = -1;

      for (int ring = 0; ring <= MAX_NEAREST_RINGS; ring++) {
         // One extra ring after the first hit is enough to catch closer cells;
         // keep going when fewer results than requested have been found.
         // For the ship scan "results" means ship-bearing hits, with a hard cap
         // on raw hits so a ship-less region cannot spin until the deadline.
         int useful = shipOnly ? countShips(out) : out.size();
         if (firstHitRing >= 0 && ring > firstHitRing + 1
            && (useful >= need || out.size() >= need * SHIP_SCAN_FACTOR)) {
            return;
         }
         if (budget[0] <= 0 || System.currentTimeMillis() > deadline) {
            return;
         }

         boolean hitThisRing = false;
         for (int[] cell : ringCells(ring)) {
            if (budget[0]-- <= 0) {
               return;
            }
            ChunkPos candidate = spread.getPotentialStructureChunk(
               ctx.state().getLevelSeed(), (cellX + cell[0]) * spacing, (cellZ + cell[1]) * spacing
            );
            int cx = candidate.getMinBlockX() >> 4;
            int cz = candidate.getMinBlockZ() >> 4;
            if (!spread.isStructureChunk(ctx.state(), cx, cz)) {
               continue;
            }
            Generated generated = selectStructure(ctx, set, candidate);
            if (generated == null || !generated.holder().unwrapKey().map(spec.keys()::contains).orElse(false)) {
               continue;
            }
            BlockPos located = spread.getLocatePos(candidate);
            int y = spec.surface()
               ? ctx.generator().getBaseHeight(located.getX(), located.getZ(), Types.WORLD_SURFACE_WG, ctx.height(), ctx.randomState())
               : spec.fixedY();
            BlockPos pos = new BlockPos(located.getX(), y, located.getZ());
            out.add(new Hit(
               pos, horizontalDistance(origin, pos),
               generated.hasShip(), generated.shipKnown(), generated.shipPos()
            ));
            hitThisRing = true;
         }
         if (hitThisRing && firstHitRing < 0) {
            firstHitRing = ring;
         }
      }
   }

   private static List<int[]> ringCells(int ring) {
      List<int[]> cells = new ArrayList<>();
      if (ring == 0) {
         cells.add(new int[]{0, 0});
         return cells;
      }
      for (int x = -ring; x <= ring; x++) {
         cells.add(new int[]{x, -ring});
         cells.add(new int[]{x, ring});
      }
      for (int z = -ring + 1; z <= ring - 1; z++) {
         cells.add(new int[]{-ring, z});
         cells.add(new int[]{ring, z});
      }
      return cells;
   }

   private static void collectRingPositions(
      Ctx ctx, Spec spec, ConcentricRingsStructurePlacement rings, BlockPos origin, List<Hit> out
   ) {
      for (ChunkPos chunk : ctx.state().getRingPositionsFor(rings)) {
         BlockPos located = rings.getLocatePos(chunk);
         BlockPos pos = new BlockPos(located.getX(), spec.fixedY(), located.getZ());
         out.add(new Hit(pos, horizontalDistance(origin, pos), true, false, null));
      }
   }

   private static double horizontalDistance(BlockPos a, BlockPos b) {
      double dx = a.getX() - b.getX();
      double dz = a.getZ() - b.getZ();
      return Math.sqrt(dx * dx + dz * dz);
   }
}
