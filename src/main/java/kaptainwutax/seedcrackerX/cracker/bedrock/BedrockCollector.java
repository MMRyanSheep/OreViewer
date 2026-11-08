package kaptainwutax.seedcrackerX.cracker.bedrock;

import com.seedfinding.mccore.rand.seed.WorldSeed;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import kaptainwutax.seedcrackerX.SeedCracker;
import kaptainwutax.seedcrackerX.automine.AutoMineManager;
import kaptainwutax.seedcrackerX.command.ClientCommand;
import kaptainwutax.seedcrackerX.cracker.HashedSeedData;
import kaptainwutax.seedcrackerX.cracker.storage.DataStorage;
import kaptainwutax.seedcrackerX.finder.FinderQueue;
import kaptainwutax.seedcrackerX.util.Compat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.ClickEvent.RunCommand;
import net.minecraft.network.chat.HoverEvent.ShowText;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;

public final class BedrockCollector {
   private static final BedrockCollector INSTANCE = new BedrockCollector();
   private static final int[] FLOOR_Y = new int[]{1, 2, 3, 4};
   private static final int[] ROOF_Y = new int[]{123, 124, 125, 126};
   private static final int MAX_PER_SURFACE = 100;
   private static final int MIN_SAMPLES_PER_SURFACE = 4;
   private static final double SECONDARY_PASS_LIMIT = 0.05;
   private static final double MANUAL_SECONDARY_PASS_LIMIT = 0.5;
   private static final long PRIMARY_SURVIVOR_LIMIT = 1L;
   private static final long MANUAL_SURVIVOR_LIMIT = 256L;
   private static final int SINGLE_SURFACE_MIN = 48;
   private final ConcurrentMap<Long, BedrockBlock> samples = new ConcurrentHashMap<>();
   private final AtomicInteger chunksScanned = new AtomicInteger();
   private volatile boolean cracking = false;
   private volatile boolean finished = false;
   private volatile BedrockSearcher activeSearcher = null;
   private int failedAttempts = 0;
   private volatile boolean pillarHintShown = false;

   private BedrockCollector() {
   }

   public static BedrockCollector get() {
      return INSTANCE;
   }

   public void reset() {
      BedrockSearcher searcher = this.activeSearcher;
      if (searcher != null) {
         try {
            searcher.cancel();
         } catch (Throwable var3) {
         }
      }

      this.samples.clear();
      this.chunksScanned.set(0);
      this.cracking = false;
      this.finished = false;
      this.activeSearcher = null;
      this.failedAttempts = 0;
      this.pillarHintShown = false;
   }

   public boolean isCracking() {
      return this.cracking;
   }

   public int[] sampleCounts() {
      int floor = 0;
      int roof = 0;

      for (BedrockBlock block : this.samples.values()) {
         if (block.isRoof()) {
            roof++;
         } else {
            floor++;
         }
      }

      return new int[]{floor, roof};
   }

   public void onChunkData(ClientLevel level, ChunkPos chunkPos) {
      if (AutoMineManager.proMode && !this.cracking && !this.finished) {
         if (bedrockSurfaceType(level) != 0) {
            try {
               FinderQueue.SERVICE.submit(() -> this.scanChunk(level, chunkPos));
            } catch (Exception var4) {
            }
         } else {
            this.maybeHintPillars(level);
         }
      }
   }

   private void maybeHintPillars(ClientLevel level) {
      if (!this.pillarHintShown) {
         try {
            if (!level.dimension().identifier().getPath().toLowerCase().contains("end")) {
               return;
            }

            DataStorage ds = SeedCracker.get().getDataStorage();
            List<Integer> pillarSeeds = ds.getTimeMachine().pillarSeeds;
            if (pillarSeeds == null) {
               return;
            }

            this.pillarHintShown = true;
            if (pillarSeeds.isEmpty()) {
               say("末地柱数据与原版生成规则不符（服务器可能改动过末地柱），请改在地狱采集基岩破译", ChatFormatting.YELLOW);
            } else if (ds.getBaseBits() < ds.getWantedBits()) {
               say(String.format("末地柱种子已破译（%s）；还需结构数据 %.1f/32 位：探索末地城、或回主世界靠近村庄/沉船/雪屋等结构，数据足够后会自动继续破译", pillarSeeds, ds.getBaseBits()), ChatFormatting.GOLD);
            } else {
               say("末地柱种子已破译（" + pillarSeeds + "），正在结合结构数据反推世界种子…", ChatFormatting.GOLD);
            }
         } catch (Throwable var4) {
         }
      }
   }

   private static int bedrockSurfaceType(ClientLevel level) {
      try {
         if (level.dimension().identifier().getPath().toLowerCase().contains("nether")) {
            return 1;
         }

         boolean scale8 = level.dimensionType().coordinateScale() == 8.0;
         boolean skylight = level.dimensionType().hasSkyLight();
         return scale8 && !skylight ? 1 : 0;
      } catch (Exception e) {
         return 0;
      }
   }

   private void scanChunk(ClientLevel level, ChunkPos chunkPos) {
      if (AutoMineManager.proMode && !this.cracking && !this.finished) {
         try {
            BlockPos origin = chunkPos.getWorldPosition();
            ChunkAccess chunk = level.getChunk(origin);
            int baseX = origin.getX();
            int baseZ = origin.getZ();

            for (int localX = 0; localX < 16; localX++) {
               for (int localZ = 0; localZ < 16; localZ++) {
                  int worldX = baseX + localX;
                  int worldZ = baseZ + localZ;

                  for (int y : FLOOR_Y) {
                     this.record(chunk, worldX, worldZ, localX, localZ, y);
                  }

                  for (int y : ROOF_Y) {
                     this.record(chunk, worldX, worldZ, localX, localZ, y);
                  }
               }
            }

            this.chunksScanned.incrementAndGet();
            this.maybeAutoStart(false);
         } catch (Exception var15) {
         }
      }
   }

   private void record(ChunkAccess chunk, int worldX, int worldZ, int localX, int localZ, int y) {
      long key = pack(worldX, y, worldZ);
      if (!this.samples.containsKey(key)) {
         boolean bedrock = chunk.getBlockState(new BlockPos(localX, y, localZ)).is(Blocks.BEDROCK);
         this.samples.putIfAbsent(key, new BedrockBlock(worldX, y, worldZ, bedrock ? BlockType.BEDROCK : BlockType.OTHER));
      }
   }

   private static long pack(int x, int y, int z) {
      return (x & 67108863L) << 34 | (z & 67108863L) << 8 | y & 255L;
   }

   public String status() {
      int[] counts = this.sampleCounts();
      String pillar = this.pillarStatus();
      if (this.finished) {
         return "破混淆：已破译完成" + pillar;
      } else {
         return this.cracking
            ? "破混淆：正在破译中…（底层 " + counts[0] + " / 顶层 " + counts[1] + "）" + pillar
            : "破混淆：已扫描 " + this.chunksScanned.get() + " 个区块，底层样本 " + counts[0] + " / 顶层样本 " + counts[1] + "；地狱移动采集基岩，或进末地自动扫描黑曜石柱" + pillar;
      }
   }

   private String pillarStatus() {
      try {
         DataStorage ds = SeedCracker.get().getDataStorage();
         if (ds.getTimeMachine().worldSeeds.size() == 1) {
            return "；已通过末地柱破译出世界种子";
         }

         List<Integer> pillarSeeds = ds.getTimeMachine().pillarSeeds;
         if (pillarSeeds != null && !pillarSeeds.isEmpty()) {
            return String.format("；末地柱种子 %s，结构数据 %.1f/32 位", pillarSeeds, ds.getBaseBits());
         }
      } catch (Throwable var3) {
      }

      return "";
   }

   public synchronized void maybeAutoStart(boolean force) {
      if (!this.cracking && !this.finished) {
         try {
            if (SeedCracker.get().getDataStorage().getTimeMachine().worldSeeds.size() == 1) {
               this.finished = true;
               return;
            }
         } catch (Throwable var19) {
         }

         List<BedrockBlock> selected = this.selectSamples();
         int[] counts = counts(selected);
         boolean hasFloor = counts[0] >= 4;
         boolean hasRoof = counts[1] >= 4;
         if (hasFloor || hasRoof) {
            boolean singleSurface = hasFloor != hasRoof;
            if (!singleSurface || Math.max(counts[0], counts[1]) >= 48) {
               long[] power = BlockFilter.estimatePerSurface(selected);
               double floorFraction = power[0] / 2.8147498E14F;
               double roofFraction = power[1] / 2.8147498E14F;
               long primarySurvivors = Math.min(power[0], power[1]);
               double secondaryPass = singleSurface ? 0.0 : Math.max(floorFraction, roofFraction);
               long survivorLimit = this.failedAttempts == 0 ? 1L : 0L;
               boolean enough = primarySurvivors <= survivorLimit && secondaryPass <= 0.05;
               if (!enough) {
                  if (!force) {
                     return;
                  }

                  if (primarySurvivors > 256L || secondaryPass > 0.5) {
                     say("当前基岩数据还不足以破译：预计候选种子 " + primarySurvivors + " 个，请继续扩大探索范围（地狱顶/底层基岩均可，单表层需要更多样本）", ChatFormatting.YELLOW);
                     return;
                  }
               }

               this.start(selected, force);
            }
         }
      }
   }

   private List<BedrockBlock> selectSamples() {
      List<BedrockBlock> floor = new ArrayList<>();
      List<BedrockBlock> roof = new ArrayList<>();

      for (BedrockBlock block : this.samples.values()) {
         (block.isRoof() ? roof : floor).add(block);
      }

      Comparator<BedrockBlock> strongestFirst = Comparator.comparingDouble(block -> new BlockFilter(block, BedrockMode.NORMAL).acceptanceWidth());
      floor.sort(strongestFirst);
      roof.sort(strongestFirst);
      List<BedrockBlock> selected = new ArrayList<>(200);
      selected.addAll(floor.subList(0, Math.min(100, floor.size())));
      selected.addAll(roof.subList(0, Math.min(100, roof.size())));
      return selected;
   }

   private static int[] counts(List<BedrockBlock> blocks) {
      int floor = 0;
      int roof = 0;

      for (BedrockBlock block : blocks) {
         if (block.isRoof()) {
            roof++;
         } else {
            floor++;
         }
      }

      return new int[]{floor, roof};
   }

   private void start(List<BedrockBlock> selected, boolean forced) {
      this.cracking = true;
      say("正在装载破译插件…", ChatFormatting.GOLD);
      long uploadT0 = System.currentTimeMillis();
      Thread ticker = new Thread(() -> {
         while (this.cracking) {
            try {
               Thread.sleep(5000L);
            } catch (InterruptedException e) {
               return;
            }

            if (!this.cracking) {
               return;
            }

            long sec = (System.currentTimeMillis() - uploadT0) / 1000L;
            say(String.format("破译进度：搜索中，已等待 %d 秒", sec), ChatFormatting.GRAY);
         }
      }, "bedrock-progress");
      ticker.setDaemon(true);
      ticker.start();
      Thread worker = new Thread(() -> {
         try {
            BedrockPluginLoader.install(this.loadBundledPlugin());
            int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
            BedrockSearcher searcher = BedrockPluginLoader.create(selected, threads, BedrockSearcher.Output.STRUCTURE);
            Minecraft.getInstance().execute(() -> {
               if (this.cracking) {
                  this.activeSearcher = searcher;
                  this.runSearch(searcher);
               }
            });
         } catch (Throwable t) {
            Minecraft.getInstance().execute(() -> {
               if (this.cracking) {
                  this.cracking = false;
                  this.activeSearcher = null;
                  this.failedAttempts++;
                  say("破译插件装载失败：" + t, ChatFormatting.RED);
               }
            });
         }
      }, "bedrock-plugin-local");
      worker.setDaemon(true);
      worker.start();
   }

   private byte[] loadBundledPlugin() throws IOException {
      String path = "assets/seedcrackerx/bedrock-plugin.zip";
      InputStream in = BedrockCollector.class.getClassLoader().getResourceAsStream(path);
      if (in == null) {
         in = BedrockCollector.class.getResourceAsStream("/" + path);
      }

      if (in == null) {
         throw new IOException("未找到内置破译插件");
      } else {
         try (InputStream stream = in) {
            return stream.readAllBytes();
         }
      }
   }

   private void runSearch(BedrockSearcher searcher) {
      Set<Long> structureSeeds = ConcurrentHashMap.newKeySet();
      long t0 = System.currentTimeMillis();
      AtomicLong lastPrint = new AtomicLong(0L);
      Thread worker = new Thread(() -> {
         try {
            searcher.search(structureSeed -> {
               structureSeeds.add(structureSeed);
               return false;
            }, done -> {
               long now = System.currentTimeMillis();
               if (now - lastPrint.get() > 3000L) {
                  lastPrint.set(now);
                  double percent = 100.0 * (done >>> 12) / 6.871948E10F;
                  say(String.format("破译进度 %.1f%%", percent), ChatFormatting.GRAY);
               }
            });
            long elapsed = System.currentTimeMillis() - t0;
            Minecraft.getInstance().execute(() -> this.onSearchFinished(structureSeeds, elapsed));
         } catch (Throwable t) {
            Minecraft.getInstance().execute(() -> {
               this.cracking = false;
               this.activeSearcher = null;
               say("破译出错：" + t, ChatFormatting.RED);
            });
         }
      }, "bedrock-crack-coordinator");
      worker.setDaemon(true);
      worker.start();
   }

   private void onSearchFinished(Set<Long> structureSeeds, long elapsedMs) {
      this.cracking = false;
      this.activeSearcher = null;
      if (structureSeeds.isEmpty()) {
         this.failedAttempts++;
         say("本轮未找到匹配种子（耗时 " + elapsedMs / 1000.0 + " 秒），请继续扩大探索范围（地狱顶/底层基岩），采集更多基岩后会自动重试", ChatFormatting.RED);
      } else {
         LinkedHashSet<Long> worldSeeds = this.liftAndVerify(structureSeeds);
         if (worldSeeds.isEmpty()) {
            this.failedAttempts++;
            say("找到了 " + structureSeeds.size() + " 个结构种子候选，但提升世界种子失败，请继续采集后重试", ChatFormatting.RED);
         } else {
            this.finished = true;
            this.announce(worldSeeds, structureSeeds.size(), elapsedMs);
         }
      }
   }

   public void onExternalSeedFound(long seed) {
      this.cracking = false;
      this.finished = true;
      LinkedHashSet<Long> seeds = new LinkedHashSet<>();
      seeds.add(seed);
      this.announce(seeds, 1, 0L);
   }

   private LinkedHashSet<Long> liftAndVerify(Set<Long> structureSeeds) {
      LinkedHashSet<Long> verified = new LinkedHashSet<>();
      HashedSeedData hashedData = SeedCracker.get().getDataStorage().hashedSeedData;
      Long hashedSeed = hashedData == null ? null : hashedData.getHashedSeed();
      if (hashedSeed != null && hashedSeed != 0L) {
         for (long structureSeed : structureSeeds) {
            WorldSeed.fromHash(structureSeed, hashedSeed).forEach(verified::add);
         }
      }

      if (!verified.isEmpty()) {
         return verified;
      }

      LinkedHashSet<Long> lifted = new LinkedHashSet<>();

      for (long structureSeed : structureSeeds) {
         for (long prevSeed : BedrockMath.reverseNextLong(structureSeed)) {
            lifted.add(BedrockMath.nextLong(prevSeed));
         }
      }

      return lifted;
   }

   private void announce(Set<Long> worldSeeds, int structureCount, long elapsedMs) {
      boolean single = worldSeeds.size() == 1;
      say("====================================", ChatFormatting.AQUA);
      say("种子破译完成，是否直接设置？" + (elapsedMs > 0L ? "（服务端耗时 " + String.format("%.1f", elapsedMs / 1000.0) + " 秒）" : "") + " 世界种子如下：", ChatFormatting.GOLD);

      for (long seed : worldSeeds) {
         MutableComponent msg = Component.literal("  种子：" + seed).withStyle(ChatFormatting.GREEN);
         msg.append(
            Component.literal(" [直接设置] ")
               .withStyle(
                  style -> style.withColor(ChatFormatting.AQUA)
                     .withBold(true)
                     .withClickEvent(new RunCommand("/ov sz " + seed))
                     .withHoverEvent(new ShowText(Component.literal("点击直接设置种子 " + seed + "（等价执行 /ov sz " + seed + "）")))
               )
         );
         if (!single) {
            msg.append(Component.literal("（多个候选，逐个尝试）").withStyle(ChatFormatting.GRAY));
         }

         try {
            Compat.sendSystemMessage(msg);
         } catch (Throwable var11) {
         }
      }

      if (!single) {
         say("存在多个世界种子候选（服务器登录种子可能被混淆，无法用哈希校验区分），可依次点击 [直接设置] 并用 /ov zd 验证", ChatFormatting.YELLOW);
      }

      say("也可以复制种子数字手动执行 /ov sz <种子>；/ov pro off 可关闭破混淆采集", ChatFormatting.GRAY);
   }

   private static void say(String message, ChatFormatting color) {
      Minecraft.getInstance().execute(() -> ClientCommand.sendFeedback(message, color));
   }
}
