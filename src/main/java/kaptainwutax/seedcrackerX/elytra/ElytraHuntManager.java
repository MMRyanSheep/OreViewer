package kaptainwutax.seedcrackerX.elytra;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import kaptainwutax.seedcrackerX.automine.BaritoneBridge;
import kaptainwutax.seedcrackerX.command.ClientCommand;
import kaptainwutax.seedcrackerX.fly.FlyManager;
import kaptainwutax.seedcrackerX.guidance.VanillaStructureLocator;
import kaptainwutax.seedcrackerX.xray.OreXrayManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * State machine for the automatic elytra hunt.
 *
 * <p>Only one signal counts as success: the number of ELYTRA in the player's
 * inventory must exceed the baseline captured at startup. Everything else
 * (attack sent, frame emptied, item entity visible) is just an intermediate
 * observation and may be retried safely.</p>
 *
 * <p>Locating is always done on a single background executor because
 * {@link VanillaStructureLocator#findEndCitiesWithShip} runs a full jigsaw
 * generation check and can take tens of seconds.</p>
 */
public final class ElytraHuntManager {
   public static final ElytraHuntManager INSTANCE = new ElytraHuntManager();
   private static final Logger LOGGER = LoggerFactory.getLogger("SeedCrackerX-Elytra");
   private static final String TAG = "[自动取翼] ";

   private static final int DEFAULT_CANDIDATES = 12;
   private static final int MAX_CANDIDATES_TOTAL = 32;
   private static final double ARRIVE_XZ = 12.0;
   private static final double SLOW_RADIUS = 32.0;
   private static final double SCAN_RADIUS = 96.0;
   private static final long VERIFY_TIMEOUT_MS = 8000L;
   private static final long TRAVEL_TIMEOUT_MS = 120000L;
   private static final long APPROACH_TIMEOUT_MS = 15000L;
   private static final long TAKE_TIMEOUT_MS = 10000L;
   private static final long GLOBAL_TIMEOUT_MS = 1200000L;
   private static final long LOCATE_TIMEOUT_MS = 90000L;
   private static final int ATTACK_RETRIES = 3;
   private static final int ORIGIN_PUSH = 1500;
   private static final int MAX_RESCAN_ROUNDS = 4;
   private static final int MAX_SWEEPS = 3;
   private static final long SCAN_INTERVAL_MS = 1000L;
   private static final long INFO_INTERVAL_MS = 3000L;
   private static final double[][] SWEEP_OFFSETS = {{32, 0}, {0, 32}, {-32, 0}, {0, -32}, {48, 48}};

   public enum Phase {
      IDLE,
      LOCATING,
      TRAVELLING,
      VERIFYING,
      APPROACHING,
      TAKING,
      EQUIPPING,
      DONE,
      ABORTED
   }

   private record LocateResult(long generation, boolean failed, List<CityCandidate> cities) {
   }

   private static final class ScanResult {
      boolean chunkLoaded;
      boolean cityPresent;
      Entity elytraFrame;
      Entity dropped;
      int frames;
      int entities;
   }

   private final FlyController flight = new FlyController();
   private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor(task -> {
      Thread thread = new Thread(task, "SeedCrackerX-Elytra-Locator");
      thread.setDaemon(true);
      return thread;
   });
   private final AtomicBoolean scanning = new AtomicBoolean(false);
   private final Deque<CityCandidate> queue = new ArrayDeque<>();
   private final Set<Long> tried = new HashSet<>();

   private volatile Phase phase = Phase.IDLE;
   private volatile LocateResult pendingResult;
   private volatile long locateGeneration;

   private CityCandidate current;
   private Entity targetFrame;
   private Entity droppedItem;
   private Vec3 sweepTarget;
   private ScanResult lastScan;
   private String lastReason;
   private BlockPos lastOrigin;
   private int sweepCount;
   private int baselineElytra;
   private int candidateCount = DEFAULT_CANDIDATES;
   private int attempts;
   private int rescanRound;
   private int attackAttempts;
   private long phaseStartMs;
   private long globalStartMs;
   private long lastInfoMs;
   private long lastScanMs;
   private long lastAttackMs;
   private boolean pickupOnly;
   private boolean itemPopped;

   private ElytraHuntManager() {
   }

   public Phase getPhase() {
      return this.phase;
   }

   public boolean isRunning() {
      Phase p = this.phase;
      return p != Phase.IDLE && p != Phase.DONE && p != Phase.ABORTED;
   }

   // ------------------------------------------------------------------ start / stop

   /** Called by {@code /os qc}. Performs every precondition check listed in the plan. */
   public void start() {
      Minecraft mc = Minecraft.getInstance();
      if (this.isRunning()) {
         this.info("已在运行中；如需退出请执行 /os qc 停止", ChatFormatting.YELLOW);
         return;
      }
      LocalPlayer player = mc.player;
      ClientLevel level = mc.level;
      if (player == null || level == null) {
         this.fail("尚未进入世界");
         return;
      }
      if (!isEnd(level)) {
         this.fail("不在末地维度，请先抵达末地外岛（本流程不负责折跃门传送）");
         return;
      }
      if (!OreXrayManager.INSTANCE.isActive()) {
         this.fail("尚未设置世界种子，请先用 /os sz <种子> 设置");
         return;
      }
      if (!player.isAlive() || player.isRemoved()) {
         this.fail("玩家当前不可操作");
         return;
      }
      if (!player.getAbilities().mayfly) {
         if (!FlyManager.isEnabled()) {
            FlyManager.setEnabled(true);
         }
         this.info("未检测到飞行能力，已开启强制飞行；若服务器要求请先执行 /fly", ChatFormatting.YELLOW);
      }
      if (player.getInventory().getFreeSlot() < 0) {
         this.info("背包已满，可能无法拾取鞘翅", ChatFormatting.YELLOW);
      }

      BaritoneBridge.ensureIdle();
      this.baselineElytra = countElytra(player);
      if (this.baselineElytra > 0) {
         this.fail("背包/装备栏已有鞘翅，为避免误判请先行存放后再启动");
         return;
      }

      this.queue.clear();
      this.tried.clear();
      this.attempts = 0;
      this.rescanRound = 0;
      this.lastReason = null;
      this.targetFrame = null;
      this.droppedItem = null;
      this.lastScan = null;
      this.flight.resetForNewTarget();
      this.globalStartMs = System.currentTimeMillis();
      this.lastOrigin = player.blockPosition();
      VanillaStructureLocator.clearCityCache();
      this.phase = Phase.LOCATING;
      this.phaseStartMs = this.globalStartMs;
      this.info("开始离线定位带船末地城（候选数 " + this.candidateCount + "）…", ChatFormatting.GREEN);
      this.submitLocating(this.lastOrigin, this.candidateCount);
   }

   public void stop() {
      if (!this.isRunning()) {
         this.info("当前未运行", ChatFormatting.YELLOW);
         return;
      }
      this.abort("用户停止");
   }

   public void setCandidateCount(int count) {
      this.candidateCount = Math.max(1, Math.min(32, count));
      this.info("候选数量已设为 " + this.candidateCount, ChatFormatting.GREEN);
   }

   /** Clear the city cache, push the origin outwards and locate again. */
   public void rescan() {
      Minecraft mc = Minecraft.getInstance();
      if (!this.isRunning()) {
         this.start();
         return;
      }
      LocalPlayer player = mc.player;
      if (player == null) {
         this.abort("玩家不可用");
         return;
      }
      VanillaStructureLocator.clearCityCache();
      this.queue.clear();
      this.rescanRound++;
      this.lastOrigin = pushedOrigin(player, this.rescanRound);
      this.targetFrame = null;
      this.droppedItem = null;
      this.pickupOnly = false;
      this.itemPopped = false;
      this.phase = Phase.LOCATING;
      this.phaseStartMs = System.currentTimeMillis();
      this.info("已清空候选缓存，外推 origin 至 (" + this.lastOrigin.getX() + ", " + this.lastOrigin.getZ() + ") 重新定位", ChatFormatting.GREEN);
      this.submitLocating(this.lastOrigin, Math.min(32, this.candidateCount + this.rescanRound * 4));
   }

   public void debug() {
      Minecraft mc = Minecraft.getInstance();
      LocalPlayer player = mc.player;
      this.info(
         "phase=" + this.phase
            + " 扫描中=" + this.scanning.get()
            + " 候选数=" + this.candidateCount
            + " 队列=" + this.queue.size()
            + " 已尝试=" + this.attempts + "/" + MAX_CANDIDATES_TOTAL,
         ChatFormatting.AQUA
      );
      this.info(
         "当前候选=" + (this.current == null ? "无" : format(this.current.pos()))
            + " 重扫轮次=" + this.rescanRound
            + " 巡航高度=" + this.flight.cruiseY()
            + " 抬升次数=" + this.flight.liftRetries(),
         ChatFormatting.AQUA
      );
      this.info(
         "背包基线=" + this.baselineElytra
            + " 现有鞘翅=" + (player == null ? -1 : countElytra(player))
            + " 攻击次数=" + this.attackAttempts
            + " 目标实体=" + (this.targetFrame == null ? (this.droppedItem == null ? "无" : "掉落物") : "展示框"),
         ChatFormatting.AQUA
      );
      ScanResult s = this.lastScan;
      this.info(
         "区块已加载=" + (s != null && s.chunkLoaded)
            + " 结构存在=" + (s != null && s.cityPresent)
            + " 展示框=" + (s == null ? 0 : s.frames)
            + " 带鞘翅框=" + (s != null && s.elytraFrame != null)
            + " 掉落鞘翅=" + (s != null && s.dropped != null)
            + " 视野内实体=" + (s == null ? 0 : s.entities),
         ChatFormatting.AQUA
      );
      this.info(
         "种子=" + OreXrayManager.INSTANCE.getWorldSeed()
            + " 维度=" + (mc.level == null ? "无" : mc.level.dimension().identifier().getPath())
            + " mayfly=" + (player != null && player.getAbilities().mayfly)
            + " flying=" + (player != null && player.getAbilities().flying)
            + " 上次原因=" + (this.lastReason == null ? "无" : this.lastReason),
         ChatFormatting.AQUA
      );
      if (!this.queue.isEmpty()) {
         StringBuilder sb = new StringBuilder("待飞候选：");
         int n = 0;
         for (CityCandidate c : this.queue) {
            if (n++ >= 5) {
               break;
            }
            sb.append(' ').append(format(c.pos()));
         }
         this.info(sb.toString(), ChatFormatting.AQUA);
      }
   }

   // ------------------------------------------------------------------ tick dispatcher

   /** Registered after {@code FlyManager.tick()} in {@code SeedCracker.onInitialize()}. */
   public void onClientTick() {
      if (!this.isRunning()) {
         return;
      }
      Minecraft mc = Minecraft.getInstance();
      LocalPlayer player = mc.player;
      ClientLevel level = mc.level;
      if (player == null || level == null || !player.isAlive() || player.isRemoved() || !isEnd(level)) {
         this.abort("玩家不可用 / 已死亡 / 离开末地");
         return;
      }
      long now = System.currentTimeMillis();
      if (now - this.globalStartMs > GLOBAL_TIMEOUT_MS) {
         this.abort("单次运行总时限已到");
         return;
      }
      // EQUIPPING already has its elytra; don't re-enter the success path.
      if (this.phase != Phase.EQUIPPING && countElytra(player) > this.baselineElytra) {
         this.succeed(mc, now);
         return;
      }

      switch (this.phase) {
         case LOCATING -> this.tickLocating(mc, player, now);
         case TRAVELLING -> this.tickTravelling(mc, player, now);
         case VERIFYING -> this.tickVerifying(mc, player, level, now);
         case APPROACHING -> this.tickApproaching(mc, player, now);
         case TAKING -> this.tickTaking(mc, player, level, now);
         case EQUIPPING -> this.tickEquipping(mc, player);
         default -> {
         }
      }
   }

   // ------------------------------------------------------------------ LOCATING

   private void submitLocating(BlockPos origin, int need) {
      final long generation = ++this.locateGeneration;
      final long seed = OreXrayManager.INSTANCE.getWorldSeed();
      final BlockPos fixedOrigin = origin;
      final int fixedNeed = need;
      this.pendingResult = null;
      this.scanExecutor.execute(() -> {
         this.scanning.set(true);
         boolean failed = false;
         List<CityCandidate> cities = List.of();
         long start = System.currentTimeMillis();
         try {
            VanillaStructureLocator.CityResult result = VanillaStructureLocator.findEndCitiesWithShip(seed, fixedOrigin, fixedNeed);
            failed = !result.available();
            cities = result.available() ? new ArrayList<>(result.cities()) : List.of();
            LOGGER.info("[elytra] 异步定位耗时 {} ms，返回 {} 个带船候选", System.currentTimeMillis() - start, cities.size());
         } catch (Throwable t) {
            LOGGER.warn("[elytra] 异步定位失败", t);
            failed = true;
         } finally {
            if (generation == this.locateGeneration) {
               this.pendingResult = new LocateResult(generation, failed, cities);
            }
            this.scanning.set(false);
         }
      });
   }

   private void tickLocating(Minecraft mc, LocalPlayer player, long now) {
      LocateResult result = this.pendingResult;
      if (result == null || result.generation() != this.locateGeneration) {
         if (now - this.phaseStartMs > LOCATE_TIMEOUT_MS) {
            this.abort("离线定位超时（原版 worldgen 管线可能不可用）");
         } else {
            this.throttledInfo("正在离线重建原版 worldgen 并校验 jigsaw 带船结构…", now);
         }
         return;
      }
      this.pendingResult = null;
      List<CityCandidate> cities = result.cities();
      int added = 0;
      if (cities != null) {
         for (CityCandidate candidate : cities) {
            if (candidate == null || candidate.pos() == null) {
               continue;
            }
            if (!this.tried.add(candidate.pos().asLong())) {
               continue;
            }
            if (this.queue.size() >= MAX_CANDIDATES_TOTAL) {
               break;
            }
            this.queue.add(candidate);
            added++;
         }
      }
      LOGGER.info(
         "[elytra] 定位完成：耗时 {} ms，返回 {} 个，新增 {} 个，available={}",
         now - this.phaseStartMs, cities == null ? 0 : cities.size(), added, !result.failed()
      );
      if (result.failed()) {
         this.abort("离线定位失败（客户端原版 worldgen 管线不可用）");
         return;
      }
      if (this.queue.isEmpty()) {
         if (!this.pushOriginAndRelocate(player, now)) {
            this.abort("附近无可用末地城");
         }
         return;
      }
      this.info("定位完成，新增 " + added + " 个候选，剩余 " + this.queue.size(), ChatFormatting.GREEN);
      this.nextCandidate(player, now);
   }

   /** Candidate exhaustion: fly further out, which is the real fix for looted near-by cities. */
   private boolean pushOriginAndRelocate(LocalPlayer player, long now) {
      if (this.rescanRound >= MAX_RESCAN_ROUNDS) {
         return false;
      }
      this.rescanRound++;
      this.lastOrigin = pushedOrigin(player, this.rescanRound);
      VanillaStructureLocator.clearCityCache();
      this.phaseStartMs = now;
      this.info(
         "候选耗尽，沿当前方向外推 " + (ORIGIN_PUSH * this.rescanRound) + " 格重新定位（第 " + this.rescanRound + " 轮）",
         ChatFormatting.YELLOW
      );
      this.submitLocating(this.lastOrigin, Math.min(32, this.candidateCount + this.rescanRound * 4));
      return true;
   }

   private static BlockPos pushedOrigin(LocalPlayer player, int round) {
      double dx = 0.0;
      double dz = 0.0;
      if (INSTANCE.current != null) {
         dx = INSTANCE.current.pos().getX() - player.getX();
         dz = INSTANCE.current.pos().getZ() - player.getZ();
      }
      double length = Math.sqrt(dx * dx + dz * dz);
      if (length < 1.0) {
         double angle = round * 1.3;
         dx = Math.cos(angle);
         dz = Math.sin(angle);
         length = 1.0;
      }
      double push = ORIGIN_PUSH * (double)round / length;
      return new BlockPos(
         (int)(player.getX() + dx * push), player.getBlockY(), (int)(player.getZ() + dz * push)
      );
   }

   private void nextCandidate(LocalPlayer player, long now) {
      if (this.attempts >= MAX_CANDIDATES_TOTAL) {
         this.abort("已尝试 " + this.attempts + " 个候选仍未取得鞘翅");
         return;
      }
      if (this.queue.isEmpty()) {
         if (!this.pushOriginAndRelocate(player, now)) {
            this.abort("候选已全部耗尽");
         }
         return;
      }
      this.current = this.queue.poll();
      this.attempts++;
      this.flight.resetForNewTarget();
      this.targetFrame = null;
      this.droppedItem = null;
      this.pickupOnly = false;
      this.itemPopped = false;
      this.sweepTarget = null;
      this.sweepCount = 0;
      this.lastScan = null;
      this.lastScanMs = 0L;
      this.attackAttempts = 0;
      this.lastAttackMs = 0L;
      this.phaseStartMs = now;
      this.phase = Phase.TRAVELLING;
      BlockPos pos = this.current.pos();
      LOGGER.info(
         "[elytra] 换点 #{} -> {} shipKnown={} 剩余 {}",
         this.attempts, pos, this.current.shipKnown(), this.queue.size()
      );
      this.info("飞往候选 #" + this.attempts + " (" + pos.getX() + ", " + pos.getZ() + ")，剩余 " + this.queue.size(), ChatFormatting.AQUA);
   }

   private void nextCandidateWithReason(LocalPlayer player, long now, String reason) {
      this.lastReason = reason;
      LOGGER.info("[elytra] 候选 {} 失败：{}", this.current == null ? "?" : this.current.pos(), reason);
      this.info(reason + "，换下一个候选", ChatFormatting.YELLOW);
      this.nextCandidate(player, now);
   }

   // ------------------------------------------------------------------ TRAVELLING

   private void tickTravelling(Minecraft mc, LocalPlayer player, long now) {
      if (this.current == null) {
         this.nextCandidate(player, now);
         return;
      }
      if (now - this.phaseStartMs > TRAVEL_TIMEOUT_MS) {
         this.nextCandidateWithReason(player, now, "飞行超时");
         return;
      }
      BlockPos pos = this.current.pos();
      Vec3 target = new Vec3(pos.getX() + 0.5, this.flight.cruiseY(), pos.getZ() + 0.5);
      double horiz = Math.hypot(target.x - player.getX(), target.z - player.getZ());
      if (horiz < ARRIVE_XZ && Math.abs(player.getY() - this.flight.cruiseY()) < 20.0) {
         this.flight.hover(mc, player, true);
         this.phaseStartMs = now;
         this.lastScanMs = 0L;
         this.sweepCount = 0;
         this.sweepTarget = null;
         this.lastScan = null;
         this.phase = Phase.VERIFYING;
         this.info("已抵达候选上空，开始区块 / 结构 / 展示框三段校验…", ChatFormatting.AQUA);
         return;
      }
      this.flight.steer(mc, player, target, horiz < SLOW_RADIUS);
      if (this.flight.checkStuck(player)) {
         if (this.flight.lift()) {
            this.info("疑似卡住（撞塔 / 撞岛），抬升巡航高度至 " + this.flight.cruiseY(), ChatFormatting.YELLOW);
         } else {
            this.nextCandidateWithReason(player, now, "多次抬升后仍无法前进");
         }
      }
      this.throttledInfo("飞行中，距目标 " + (int)horiz + " 格，巡航高度 " + this.flight.cruiseY(), now);
   }

   // ------------------------------------------------------------------ VERIFYING

   private void tickVerifying(Minecraft mc, LocalPlayer player, ClientLevel level, long now) {
      if (this.current == null) {
         this.nextCandidate(player, now);
         return;
      }
      if (this.sweepTarget != null) {
         double d = Math.hypot(this.sweepTarget.x - player.getX(), this.sweepTarget.z - player.getZ());
         if (d > ARRIVE_XZ && now - this.phaseStartMs < VERIFY_TIMEOUT_MS) {
            this.flight.steer(mc, player, this.sweepTarget, true);
            return;
         }
         this.sweepTarget = null;
      }
      this.flight.hover(mc, player, true);
      if (now - this.lastScanMs < SCAN_INTERVAL_MS) {
         return;
      }
      this.lastScanMs = now;
      ScanResult scan = this.scan(level, player, this.current.pos());
      this.lastScan = scan;

      if (scan.elytraFrame != null) {
         this.targetFrame = scan.elytraFrame;
         this.droppedItem = null;
         this.pickupOnly = false;
         this.phaseStartMs = now;
         this.phase = Phase.APPROACHING;
         this.info("找到带鞘翅的展示框，开始贴身接近…", ChatFormatting.GREEN);
         return;
      }
      if (scan.dropped != null) {
         this.targetFrame = null;
         this.droppedItem = scan.dropped;
         this.pickupOnly = true;
         this.phaseStartMs = now;
         this.phase = Phase.APPROACHING;
         this.info("发现已掉落但未拾取的鞘翅，前往吸取…", ChatFormatting.GREEN);
         return;
      }
      if (!scan.chunkLoaded) {
         if (now - this.phaseStartMs < VERIFY_TIMEOUT_MS) {
            this.throttledInfo("等待目标区块加载…", now);
            return;
         }
         if (this.sweepCount < MAX_SWEEPS) {
            this.sweepCount++;
            this.sweepTarget = spiralPoint(this.current.pos(), this.sweepCount, this.flight.cruiseY());
            this.phaseStartMs = now;
            this.info("区块未完全加载，绕飞重扫 " + this.sweepCount + "/" + MAX_SWEEPS, ChatFormatting.YELLOW);
            return;
         }
         this.nextCandidateWithReason(player, now, "区块长期未加载");
         return;
      }
      if (!scan.cityPresent) {
         this.nextCandidateWithReason(player, now, "该坐标未发现末地城结构（种子/worldgen 不符或已被拆）");
         return;
      }
      this.nextCandidateWithReason(player, now, "无船或鞘翅已被取走");
   }

   private static Vec3 spiralPoint(BlockPos center, int index, int y) {
      double[] offset = SWEEP_OFFSETS[(index - 1) % SWEEP_OFFSETS.length];
      return new Vec3(center.getX() + offset[0], y, center.getZ() + offset[1]);
   }

   /** Three independent signals so "not loaded" is never mistaken for "looted". */
   private ScanResult scan(ClientLevel level, LocalPlayer player, BlockPos center) {
      ScanResult result = new ScanResult();
      int cx = center.getX() >> 4;
      int cz = center.getZ() >> 4;
      int loaded = 0;
      for (int dx = -1; dx <= 1; dx++) {
         for (int dz = -1; dz <= 1; dz++) {
            try {
               if (level.getChunk(cx + dx, cz + dz, ChunkStatus.FULL, false) != null) {
                  loaded++;
               }
            } catch (Throwable ignored) {
            }
         }
      }
      result.chunkLoaded = loaded >= 5;

      for (Entity entity : level.entitiesForRendering()) {
         if (entity == null || entity.isRemoved() || !entity.isAlive()) {
            continue;
         }
         double hx = entity.getX() - center.getX();
         double hz = entity.getZ() - center.getZ();
         if (hx * hx + hz * hz > SCAN_RADIUS * SCAN_RADIUS) {
            continue;
         }
         result.entities++;
         if (entity.getType() == EntityTypes.SHULKER) {
            result.cityPresent = true;
         }
         if (entity instanceof ItemFrame) {
            result.frames++;
            result.cityPresent = true;
            ItemStack stack = frameItem(entity);
            if (stack != null && stack.getItem() == Items.ELYTRA && result.elytraFrame == null) {
               result.elytraFrame = entity;
            }
         } else if (entity instanceof ItemEntity item && item.getItem().getItem() == Items.ELYTRA && result.dropped == null) {
            result.dropped = entity;
            result.cityPresent = true;
         }
      }

      if (!result.cityPresent) {
         outer:
         for (int x = center.getX() - 48; x <= center.getX() + 48; x += 8) {
            for (int z = center.getZ() - 48; z <= center.getZ() + 48; z += 8) {
               if (level.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null) {
                  continue;
               }
               for (int y = 60; y <= 150; y += 10) {
                  BlockState state = level.getBlockState(new BlockPos(x, y, z));
                  Block block = state.getBlock();
                  if (block == Blocks.PURPUR_BLOCK
                     || block == Blocks.PURPUR_PILLAR
                     || block == Blocks.END_STONE_BRICKS
                     || block == Blocks.STAINED_GLASS.magenta()) {
                     result.cityPresent = true;
                     break outer;
                  }
               }
            }
         }
      }
      return result;
   }

   /** Direct call with a reflective fallback in case the item frame class changed. */
   private static ItemStack frameItem(Entity entity) {
      try {
         if (entity instanceof ItemFrame frame) {
            return frame.getItem();
         }
      } catch (Throwable ignored) {
      }
      try {
         Method method = entity.getClass().getMethod("getItem");
         Object value = method.invoke(entity);
         return value instanceof ItemStack stack ? stack : ItemStack.EMPTY;
      } catch (Throwable t) {
         return ItemStack.EMPTY;
      }
   }

   // ------------------------------------------------------------------ APPROACHING

   private void tickApproaching(Minecraft mc, LocalPlayer player, long now) {
      Entity target = this.pickupOnly ? this.droppedItem : this.targetFrame;
      if (target == null || target.isRemoved() || !target.isAlive()) {
         this.nextCandidateWithReason(player, now, "目标实体已消失");
         return;
      }
      if (now - this.phaseStartMs > APPROACH_TIMEOUT_MS) {
         this.nextCandidateWithReason(player, now, "贴身接近超时");
         return;
      }
      Vec3 hoverPoint = this.pickupOnly
         ? target.position().add(0.0, 0.3, 0.0)
         : hoverPointFor(target, player, 2.2);
      double distance = player.position().distanceTo(hoverPoint);
      double arrival = this.pickupOnly ? 1.5 : 2.5;
      if (distance > arrival) {
         this.flight.steer(mc, player, hoverPoint, true);
         this.throttledInfo("接近目标中，还差 " + String.format("%.1f", distance) + " 格", now);
         return;
      }
      this.flight.hover(mc, player, false);
      if (this.pickupOnly) {
         // Success is judged by the inventory count at the top of the tick.
         this.throttledInfo("正在吸取掉落的鞘翅…", now);
         return;
      }
      FlyController.aimAt(player, target);
      this.phaseStartMs = now;
      this.attackAttempts = 0;
      this.lastAttackMs = 0L;
      this.itemPopped = false;
      this.phase = Phase.TAKING;
      this.info("已就位（朝向误差 0°），开始左键取物", ChatFormatting.AQUA);
   }

   private static Vec3 hoverPointFor(Entity target, LocalPlayer player, double distance) {
      Direction facing = null;
      if (target instanceof ItemFrame frame) {
         try {
            facing = frame.getDirection();
         } catch (Throwable ignored) {
         }
      }
      if (facing == null) {
         double dx = player.getX() - target.getX();
         double dz = player.getZ() - target.getZ();
         double length = Math.max(Math.sqrt(dx * dx + dz * dz), 0.001);
         return new Vec3(
            target.getX() + dx / length * distance,
            target.getY() + 0.5,
            target.getZ() + dz / length * distance
         );
      }
      return new Vec3(
         target.getX() + facing.getStepX() * distance,
         target.getY() + 0.5,
         target.getZ() + facing.getStepZ() * distance
      );
   }

   // ------------------------------------------------------------------ TAKING

   private void tickTaking(Minecraft mc, LocalPlayer player, ClientLevel level, long now) {
      if (now - this.phaseStartMs > TAKE_TIMEOUT_MS) {
         this.nextCandidateWithReason(player, now, "取物超时");
         return;
      }
      Entity frame = this.targetFrame;
      if (frame == null || frame.isRemoved() || !frame.isAlive()) {
         this.nextCandidateWithReason(player, now, "展示框被移除");
         return;
      }

      // Once the frame is empty - or the item entity is already visible - the
      // item has been popped: stop attacking and just fly into the drop.
      if (this.attackAttempts > 0 && (frameItem(frame).isEmpty() || this.findDroppedElytra(level) != null)) {
         this.itemPopped = true;
      }
      if (this.itemPopped) {
         Entity drop = this.findDroppedElytra(level);
         if (drop != null) {
            if (player.distanceToSqr(drop) > 2.25) {
               this.flight.steer(mc, player, drop.position().add(0.0, 0.3, 0.0), true);
               this.throttledInfo("正在吸取掉落的鞘翅…", now);
            } else {
               this.flight.hover(mc, player, false);
            }
            return;
         }
         if (now - this.lastAttackMs > 3000L) {
            this.nextCandidateWithReason(player, now, "鞘翅已弹出但未拾取到");
            return;
         }
         this.flight.hover(mc, player, false);
         return;
      }

      this.flight.hover(mc, player, false);
      FlyController.aimAt(player, frame);
      if (!player.hasLineOfSight(frame)) {
         this.flight.steer(mc, player, hoverPointFor(frame, player, 1.2), true);
         this.throttledInfo("展示框被遮挡，正在调整位置…", now);
         return;
      }
      if (this.attackAttempts >= ATTACK_RETRIES) {
         this.nextCandidateWithReason(player, now, "攻击 " + ATTACK_RETRIES + " 次仍未取得鞘翅");
         return;
      }
      if (player.getAttackStrengthScale(0.0F) >= 0.9F
         && player.distanceToSqr(frame) < 36.0
         && now - this.lastAttackMs >= 1000L
         && mc.gameMode != null) {
         mc.gameMode.attack(player, frame);
         player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
         this.attackAttempts++;
         this.lastAttackMs = now;
         LOGGER.info("[elytra] 左键攻击展示框 #{} at {}", this.attackAttempts, frame.blockPosition());
      }
   }

   private Entity findDroppedElytra(ClientLevel level) {
      for (Entity entity : level.entitiesForRendering()) {
         if (entity instanceof ItemEntity item
            && !item.isRemoved()
            && item.isAlive()
            && item.getItem().getItem() == Items.ELYTRA) {
            return entity;
         }
      }
      return null;
   }

   // ------------------------------------------------------------------ EQUIPPING / finish

   private void succeed(Minecraft mc, long now) {
      this.phase = Phase.EQUIPPING;
      this.phaseStartMs = now;
      this.flight.release(mc);
      long seconds = (now - this.globalStartMs) / 1000L;
      this.info("已取得鞘翅！耗时 " + seconds + " 秒，共尝试 " + this.attempts + " 个候选", ChatFormatting.GREEN);
      LOGGER.info("[elytra] success in {} ms after {} candidates", now - this.globalStartMs, this.attempts);
      this.tickEquipping(mc, mc.player);
   }

   /** Optional and non-blocking: if the container API misbehaves, just tell the player. */
   private void tickEquipping(Minecraft mc, LocalPlayer player) {
      try {
         if (player != null && player.containerMenu instanceof InventoryMenu && mc.gameMode != null) {
            int invSlot = this.findElytraSlot(player);
            if (invSlot >= 0) {
               int menuSlot = invSlot < 9 ? invSlot + 36 : invSlot;
               mc.gameMode.handleContainerInput(0, menuSlot, 0, ContainerInput.QUICK_MOVE, player);
               this.info("已尝试自动装备鞘翅（快捷移动至胸甲槽）", ChatFormatting.GREEN);
            }
         }
      } catch (Throwable t) {
         this.info("自动装备跳过，请手动装备鞘翅", ChatFormatting.YELLOW);
      }
      this.phase = Phase.DONE;
      this.flight.release(mc);
      this.info("流程结束，飞行保持开启（不会掉进虚空）；可 /os qc 停止 释放按键", ChatFormatting.GREEN);
   }

   private int findElytraSlot(LocalPlayer player) {
      Inventory inventory = player.getInventory();
      for (int i = 0; i < 36; i++) {
         ItemStack stack = inventory.getItem(i);
         if (stack != null && stack.getItem() == Items.ELYTRA) {
            return i;
         }
      }
      return -1;
   }

   private void abort(String reason) {
      this.lastReason = reason;
      this.phase = Phase.ABORTED;
      this.flight.release(Minecraft.getInstance());
      this.info("已停止：" + reason, ChatFormatting.RED);
      LOGGER.info("[elytra] aborted: {}", reason);
      this.phase = Phase.IDLE;
   }

   private void fail(String reason) {
      this.lastReason = reason;
      this.info(reason, ChatFormatting.RED);
      this.phase = Phase.ABORTED;
      this.phase = Phase.IDLE;
   }

   // ------------------------------------------------------------------ helpers

   private static boolean isEnd(ClientLevel level) {
      try {
         return level.dimension().identifier().getPath().contains("end");
      } catch (Throwable t) {
         return false;
      }
   }

   private static int countElytra(LocalPlayer player) {
      Inventory inventory = player.getInventory();
      int total = 0;
      for (int i = 0; i < inventory.getContainerSize(); i++) {
         ItemStack stack = inventory.getItem(i);
         if (stack != null && !stack.isEmpty() && stack.getItem() == Items.ELYTRA) {
            total += stack.getCount();
         }
      }
      return total;
   }

   private static String format(BlockPos pos) {
      return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
   }

   private void info(String message, ChatFormatting color) {
      ClientCommand.sendFeedback(TAG + message, color);
      LOGGER.info("[elytra] {}", message);
   }

   private void throttledInfo(String message, long now) {
      if (now - this.lastInfoMs >= INFO_INTERVAL_MS) {
         this.lastInfoMs = now;
         this.info(message, ChatFormatting.GOLD);
      }
   }
}
