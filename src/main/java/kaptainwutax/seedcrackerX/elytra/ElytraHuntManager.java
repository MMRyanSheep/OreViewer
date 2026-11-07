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
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * State machine for the automatic elytra hunt.
 *
 * <p>Only one signal counts as success: the number of ELYTRA in the player's
 * inventory must exceed the baseline. Everything else (attack sent, frame
 * emptied, item entity visible) is just an intermediate observation and may be
 * retried safely.</p>
 *
 * <p>The hunt is a <b>continuous collector</b>: elytra already in the inventory
 * are not a reason to refuse starting, they are simply recorded as the baseline.
 * After each pickup the baseline is refreshed and the state machine moves on to
 * the next candidate instead of stopping. It keeps going until the configured
 * target count is reached, the inventory is full, or {@code /os qc 停止} is
 * used.</p>
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
   private static final int MAX_QUEUE_SIZE = 32;
   private static final int MAX_TARGET_ELYTRA = 64;
   private static final double ARRIVE_XZ = 12.0;
   private static final double SLOW_RADIUS = 32.0;
   private static final double SCAN_RADIUS = 96.0;
   // An end ship sits around y=70..100 while cruising happens at CRUISE_Y=140.
   // ItemFrame entities are only synced to clients within the server's entity
   // tracking range, so scanning from the cruise altitude always sees zero
   // frames. VERIFYING therefore descends to a point beside the ship first.
   private static final double SHIP_APPROACH_LIFT = 6.0;
   // Measured range of ship bounding-box centres is y=95..171 across three
   // seeds; the city placement start is only y=60..66.
   private static final int DEFAULT_SHIP_Y = 120;
   private static final int MAX_VERIFY_SCANS = 8;
   private static final long VERIFY_APPROACH_TIMEOUT_MS = 20000L;
   // Sweeps may have to dig through an end-ship hull, so give them more room.
   private static final long VERIFY_TIMEOUT_MS = 30000L;
   private static final long TRAVEL_TIMEOUT_MS = 120000L;
   private static final long APPROACH_TIMEOUT_MS = 15000L;
   private static final long TAKE_TIMEOUT_MS = 10000L;
   // Only enforced for a finite target; unlimited collection stops on user input.
   private static final long GLOBAL_TIMEOUT_MS = 1200000L;
   private static final long LOCATE_TIMEOUT_MS = 90000L;
   private static final int ATTACK_RETRIES = 3;
   private static final int ORIGIN_PUSH = 1500;
   // Exploration bias: the central islands (around 0,0) are already looted.
   // Re-locate origins along a monotonically growing radius away from (0,0)
   // instead of following the vector to the last candidate, which frequently
   // pointed straight back into the centre.
   private static final int OUTWARD_MIN_RADIUS = 2048;
   private static final double OUTWARD_ANGLE_SWING = 0.5;
   private static final int OUTWARD_PICK_WINDOW = 8;
   private static final int CENTER_HYSTERESIS = 512;
   // Continuous collection keeps pushing outwards as nearby ships get looted.
   private static final int MAX_RESCAN_ROUNDS = 64;
   private static final int MAX_SWEEPS = 3;
   private static final long SCAN_INTERVAL_MS = 1000L;
   private static final long INFO_INTERVAL_MS = 3000L;
   // A client-side Entity instance is discarded (isRemoved() == true) whenever
   // the server stops tracking it and later re-adds a fresh instance. The frame
   // is still in the world - only the old Java reference is stale. Re-resolve
   // by the recorded anchor instead of jumping to the next candidate.
   private static final long TARGET_REBIND_GRACE_MS = 5000L;
   // A dropped elytra is a real, visible target: chase it with a generous
   // budget instead of an 8-second wall-clock grace. The old timer started the
   // moment ANY phase first saw the drop, so time spent clearing a shulker,
   // descending, or digging through the hull silently consumed it. The first
   // tick of TAKING then immediately declared "cannot pick up" and switched
   // ships. All drop timers below are owned by chaseDrop() and are renewed
   // while the player is still making progress towards the item.
   private static final long DROP_CHASE_TIMEOUT_MS = 45000L;
   /** No-progress window before switching from a straight approach to a dig/route. */
   private static final long DROP_STALL_MS = 2500L;
   /** How long to hover on top of the item waiting for the server-side pickup. */
   private static final long DROP_PICKUP_WAIT_MS = 6000L;
   /** Length of one "dig through the blocker" attempt when the direct path stalls. */
   private static final long DROP_DIG_MS = 8000L;
   private static final double REBIND_RADIUS = 8.0;
   private static final double DROP_RADIUS = 48.0;
   private static final long THREAT_TIMEOUT_MS = 25000L;
   private static final long COMBAT_COOLDOWN_MS = 550L;
   private static final double THREAT_ATTACK_RANGE_SQ = 20.25;
   /** Total time a single candidate may spend detouring / mining / clearing guards. */
   private static final long OBSTACLE_GRACE_MS = 45000L;
   private static final double[][] SWEEP_OFFSETS = {{32, 0}, {0, 32}, {-32, 0}, {0, -32}, {48, 48}};
   // Approach points around the ship: stay outside the hull but well inside
   // entity tracking range. A new angle is used for every re-scan attempt.
   private static final double[][] VERIFY_OFFSETS = {
      {20, 20}, {-20, 20}, {20, -20}, {-20, -20},
      {26, 0}, {-26, 0}, {0, 26}, {0, -26}
   };

   public enum Phase {
      IDLE,
      LOCATING,
      TRAVELLING,
      VERIFYING,
      APPROACHING,
      TAKING,
      DONE,
      ABORTED
   }

   /** Result of re-resolving the live target on the current client tick. */
   private enum TargetStatus {
      FRAME,  // a live item frame still holding an elytra
      DROP,   // the elytra is on the ground, go pick it up
      WAIT,   // stale or empty target, give the server time to re-sync
      LOST    // nothing usable after the grace period
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
      /** First real structure/entity hit, used to correct an imprecise offline ship Y. */
      BlockPos structureHint;
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
   /** Point beside the ship the player must reach before scans are meaningful. */
   private Vec3 verifyPoint;
   private int verifyScans;
   private int verifyOffsetIndex;
   private BlockPos structureHint;
   private ScanResult lastScan;
   private String lastReason;
   private BlockPos lastOrigin;
   private int sweepCount;
   private int baselineElytra;
   private int collected;
   /** 0 = keep collecting until {@code /os qc 停止}. */
   private int targetElytra;
   private boolean equipAttempted;
   private int candidateCount = DEFAULT_CANDIDATES;
   private int attempts;
   private int rescanRound;
   /** Fixed bearing away from (0,0) for this run; small swings cover new sectors. */
   private double outwardAngle;
   /** Player radius when the run started; the base of the linear outward ramp. */
   private int outwardBaseRadius;
   /** Radius of the last pushed origin; guarantees every re-locate moves further out. */
   private int outwardRadius;
   /** Candidates closer to (0,0) than this are treated as already explored. */
   private int minTargetRadius;
   private int attackAttempts;
   private long phaseStartMs;
   private long globalStartMs;
   private long lastInfoMs;
   private long lastScanMs;
   private long lastAttackMs;
   private boolean pickupOnly;
   private boolean itemPopped;
   /** World-space anchor of the target frame; survives client entity re-syncs. */
   private BlockPos targetFramePos;
   private long targetMissingSinceMs;
   /** Start of the current drop chase (TAKING + DROP only); 0 = not chasing yet. */
   private long dropChaseSinceMs;
   private double dropBestDistance;
   private long dropProgressSinceMs;
   private long dropDigUntilMs;
   private long dropCloseSinceMs;
   private String dropIssue;
   private int rebindCount;
   private String targetIssue;
   private long threatSinceMs;
   private long lastCombatMs;
   private long obstacleSinceMs;

   private ElytraHuntManager() {
   }

   public Phase getPhase() {
      return this.phase;
   }

   public boolean isRunning() {
      Phase p = this.phase;
      return p != Phase.IDLE && p != Phase.DONE && p != Phase.ABORTED;
   }

   /** Drop every per-target reference, including the positional anchor. */
   private void clearTarget() {
      this.targetFrame = null;
      this.droppedItem = null;
      this.targetFramePos = null;
      this.targetMissingSinceMs = 0L;
      this.resetDropChase();
      this.rebindCount = 0;
      this.targetIssue = null;
      this.threatSinceMs = 0L;
      this.lastCombatMs = 0L;
      this.obstacleSinceMs = 0L;
      this.pickupOnly = false;
      this.itemPopped = false;
   }

   /** Forget all drop-chase progress (new candidate, or the frame got its elytra back). */
   private void resetDropChase() {
      this.dropChaseSinceMs = 0L;
      this.dropBestDistance = Double.MAX_VALUE;
      this.dropProgressSinceMs = 0L;
      this.dropDigUntilMs = 0L;
      this.dropCloseSinceMs = 0L;
      this.dropIssue = null;
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
      FlyController.resetToolState();
      // Elytra already in the inventory are NOT a reason to refuse: they are
      // the baseline for this run, and only newly picked-up ones count as a haul.
      this.baselineElytra = countElytra(player);
      if (this.baselineElytra > 0) {
         this.info(
            "检测到背包/装备栏已有 " + this.baselineElytra + " 个鞘翅，已记为基线，将继续收集新的鞘翅",
            ChatFormatting.YELLOW
         );
      }
      this.collected = 0;
      this.equipAttempted = false;

      this.queue.clear();
      this.tried.clear();
      this.attempts = 0;
      this.rescanRound = 0;
      this.lastReason = null;
      this.clearTarget();
      this.lastScan = null;
      this.flight.resetForNewTarget();
      this.globalStartMs = System.currentTimeMillis();
      this.outwardAngle = outwardBearing(player);
      this.outwardBaseRadius = (int)Math.hypot(player.getX(), player.getZ());
      this.outwardRadius = this.outwardBaseRadius;
      this.minTargetRadius = 0;
      this.lastOrigin = player.blockPosition();
      VanillaStructureLocator.clearCityCache();
      this.phase = Phase.LOCATING;
      this.phaseStartMs = this.globalStartMs;
      this.info(
         "开始持续收集（目标 " + (this.targetElytra == 0 ? "不限，/os qc 停止 结束" : this.targetElytra + " 个")
            + "），离线定位带船末地城（候选数 " + this.candidateCount + "）…",
         ChatFormatting.GREEN
      );
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

   /** {@code 0} means "collect until stopped"; any other value is the haul target for one run. */
   public void setTargetElytra(int count) {
      this.targetElytra = Math.max(0, Math.min(MAX_TARGET_ELYTRA, count));
      this.info(
         this.targetElytra == 0
            ? "目标数量设为不限（持续收集，/os qc 停止 结束）"
            : "目标数量已设为 " + this.targetElytra + " 个鞘翅",
         ChatFormatting.GREEN
      );
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
      this.clearTarget();
      this.phase = Phase.LOCATING;
      this.phaseStartMs = System.currentTimeMillis();
      this.info(
         "已清空候选缓存，沿远离 (0,0) 的方向外推 origin 至 ("
            + this.lastOrigin.getX() + ", " + this.lastOrigin.getZ() + ")"
            + "，半径 " + this.outwardRadius + " 格，中心门槛 " + this.minTargetRadius + " 格",
         ChatFormatting.GREEN
      );
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
            + " 已尝试=" + this.attempts
            + " 已收集=" + this.collected
            + " 目标=" + (this.targetElytra == 0 ? "不限" : this.targetElytra),
         ChatFormatting.AQUA
      );
      this.info(
         "城坐标=" + (this.current == null ? "无" : format(this.current.pos()))
            + " 船坐标=" + (this.current == null
               ? "无" : (this.current.shipLocated() ? format(this.current.shipPos()) : "离线未解析"))
            + " 扫描中心=" + (this.current == null ? "无" : format(this.current.target()))
            + " 结构提示=" + (this.structureHint == null ? "无" : format(this.structureHint)),
         ChatFormatting.AQUA
      );
      this.info(
         "重扫=" + this.verifyScans + "/" + MAX_VERIFY_SCANS
            + " 外推轮次=" + this.rescanRound
            + " 外推半径=" + this.outwardRadius
            + " 中心门槛=" + this.minTargetRadius
            + " 外推方位=" + (int)Math.toDegrees(this.outwardAngle) + "°"
            + " 巡航高度=" + this.flight.cruiseY()
            + " 玩家高度=" + (player == null ? -1 : (int)player.getY())
            + " 抬升次数=" + this.flight.liftRetries()
            + " 导航=" + this.flight.navState()
            + " 挖掘=" + (this.flight.miningBlock() == null ? "无" : format(this.flight.miningBlock())),
         ChatFormatting.AQUA
      );
      this.info(
         "背包基线=" + this.baselineElytra
            + " 现有鞘翅=" + (player == null ? -1 : countElytra(player))
            + " 攻击次数=" + this.attackAttempts
            + " 目标实体=" + (this.targetFrame == null ? (this.droppedItem == null ? "无" : "掉落物") : "展示框"),
         ChatFormatting.AQUA
      );
      this.info(
         "目标锚点=" + (this.targetFramePos == null ? "无" : format(this.targetFramePos))
            + " 框id=" + (this.targetFrame == null ? "无" : this.targetFrame.getId())
            + " 框已移除=" + (this.targetFrame != null && this.targetFrame.isRemoved())
            + " 重绑=" + this.rebindCount
            + " 掉落物id=" + (this.droppedItem == null ? "无" : this.droppedItem.getId())
            + " 目标问题=" + (this.targetIssue == null ? "无" : this.targetIssue),
         ChatFormatting.AQUA
      );
      this.info(
         "导航=" + this.flight.navState()
            + " " + this.flight.miningDiagnostics()
            + " 清怪计时=" + (this.threatSinceMs == 0L
               ? 0 : (System.currentTimeMillis() - this.threatSinceMs)) + "ms"
            + " 准星=" + (mc.hitResult == null ? "null" : mc.hitResult.getType()),
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
      this.info(
         FlyController.toolDiagnostics(player)
            + " 掉落物距离=" + (player == null || this.droppedItem == null
               ? "无" : String.format("%.1f", player.position().distanceTo(this.droppedItem.position())))
            + " 吸取计时=" + (this.dropChaseSinceMs == 0L
               ? 0 : (System.currentTimeMillis() - this.dropChaseSinceMs)) + "ms"
            + " 最近距离=" + (this.dropBestDistance == Double.MAX_VALUE
               ? "无" : String.format("%.1f", this.dropBestDistance))
            + " 吸取状态=" + (this.dropIssue == null
               ? (this.dropDigUntilMs != 0L ? "挖路" : "直线接近") : this.dropIssue),
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
      if (this.targetElytra > 0 && now - this.globalStartMs > GLOBAL_TIMEOUT_MS) {
         this.abort("单次运行总时限已到（已收集 " + this.collected + " 个）");
         return;
      }
      if (countElytra(player) > this.baselineElytra) {
         this.onElytraGained(mc, player, now);
         return;
      }

      // Probe the path every tick rather than trusting the previous nav state:
      // a guard tucked into the ship hull would otherwise make the controller
      // push into its hitbox until a timeout gives up. Long cruises ignore
      // distant mobs - see tickThreat().
      if (this.phase != Phase.LOCATING && this.tickThreat(mc, player, level, now)) {
         return;
      }

      switch (this.phase) {
         case LOCATING -> this.tickLocating(mc, player, now);
         case TRAVELLING -> this.tickTravelling(mc, player, level, now);
         case VERIFYING -> this.tickVerifying(mc, player, level, now);
         case APPROACHING -> this.tickApproaching(mc, player, level, now);
         case TAKING -> this.tickTaking(mc, player, level, now);
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
            BlockPos target = candidate.target();
            if (Math.hypot(target.getX(), target.getZ()) + CENTER_HYSTERESIS < this.minTargetRadius) {
               // Inside the already-explored central ring: never fly back in.
               continue;
            }
            if (this.queue.size() >= MAX_QUEUE_SIZE) {
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
      // pushedOrigin() no longer depends on the current candidate: always push
      // the search centre further away from (0,0).
      this.lastOrigin = pushedOrigin(player, this.rescanRound);
      VanillaStructureLocator.clearCityCache();
      this.queue.clear();
      this.current = null;
      this.clearTarget();
      this.sweepTarget = null;
      this.verifyPoint = null;
      this.verifyScans = 0;
      this.verifyOffsetIndex = 0;
      this.structureHint = null;
      this.lastScan = null;
      this.flight.hover(Minecraft.getInstance(), player, true);
      this.attempts = 0;
      this.phaseStartMs = now;
      this.pendingResult = null;
      // Without this the state machine stays in TRAVELLING/VERIFYING and the
      // asynchronously computed result is never consumed (old bug).
      this.phase = Phase.LOCATING;
      this.info(
         "候选耗尽，沿远离 (0,0) 的方向外推至半径 " + this.outwardRadius
            + " 格重新定位（第 " + this.rescanRound + " 轮，中心门槛 " + this.minTargetRadius + " 格）",
         ChatFormatting.YELLOW
      );
      this.submitLocating(this.lastOrigin, Math.min(32, this.candidateCount + this.rescanRound * 4));
      return true;
   }

   /** Bearing away from (0,0); a stable per-seed fallback when standing near the centre. */
   private static double outwardBearing(LocalPlayer player) {
      if (Math.hypot(player.getX(), player.getZ()) > 256.0) {
         return Math.atan2(player.getZ(), player.getX());
      }
      return Math.toRadians(Math.floorMod(OreXrayManager.INSTANCE.getWorldSeed(), 360));
   }

   /**
    * Next search centre, strictly further from (0,0) than the previous one.
    *
    * <p>The old version normalised the vector towards {@code current.target()}
    * and pushed along it. A failed candidate on the near side made that vector
    * point straight back into the looted centre, so each "push outwards"
    * actually re-scanned the same islands. Here the radius is a linear ramp
    * away from world spawn and the bearing only swings within about +/- 29
    * degrees of the outward ray, which spreads sectors without ever turning
    * back. The radius is also monotonic even if the player flies inwards.</p>
    */
   private BlockPos pushedOrigin(LocalPlayer player, int round) {
      int ramp = Math.max(this.outwardBaseRadius, OUTWARD_MIN_RADIUS) + ORIGIN_PUSH * round;
      int radius = Math.max(ramp, this.outwardRadius + ORIGIN_PUSH);
      this.outwardRadius = radius;
      this.minTargetRadius = Math.max(
         this.minTargetRadius, Math.max(0, radius - ORIGIN_PUSH - CENTER_HYSTERESIS)
      );
      double angle = this.outwardAngle + Math.sin(round * 0.8) * OUTWARD_ANGLE_SWING;
      return new BlockPos(
         (int)(Math.cos(angle) * radius), player.getBlockY(), (int)(Math.sin(angle) * radius)
      );
   }

   /**
    * Pick the candidate furthest from (0,0) among the nearest few results.
    * Taking the queue head used to send the hunt straight back into the looted
    * centre; taking the global maximum would make every flight a maximal trip,
    * so only a small window of nearby results is scored.
    */
   private CityCandidate takeNextCandidate() {
      List<CityCandidate> all = new ArrayList<>(this.queue);
      int window = Math.min(all.size(), OUTWARD_PICK_WINDOW);
      int bestIndex = 0;
      double bestRadius = -1.0;
      for (int i = 0; i < window; i++) {
         BlockPos target = all.get(i).target();
         double radius = Math.hypot(target.getX(), target.getZ());
         if (radius > bestRadius) {
            bestRadius = radius;
            bestIndex = i;
         }
      }
      CityCandidate chosen = all.remove(bestIndex);
      this.queue.clear();
      this.queue.addAll(all);
      return chosen;
   }

   private void nextCandidate(LocalPlayer player, long now) {
      if (this.queue.isEmpty()) {
         if (!this.pushOriginAndRelocate(player, now)) {
            this.abort("候选已全部耗尽");
         }
         return;
      }
      this.current = this.takeNextCandidate();
      this.attempts++;
      this.flight.resetForNewTarget();
      this.clearTarget();
      this.sweepTarget = null;
      this.sweepCount = 0;
      this.lastScan = null;
      this.lastScanMs = 0L;
      this.verifyScans = 0;
      this.verifyOffsetIndex = 0;
      this.structureHint = null;
      this.attackAttempts = 0;
      this.lastAttackMs = 0L;
      this.phaseStartMs = now;
      this.phase = Phase.TRAVELLING;
      BlockPos city = this.current.pos();
      BlockPos ship = this.current.target();
      int shipY = ship.getY() > 0 ? ship.getY() : DEFAULT_SHIP_Y;
      this.flight.setMinCruiseY(Math.max(FlyController.CRUISE_Y, shipY + 28));
      // Prepare the low-altitude approach point for the VERIFYING phase.
      this.verifyPoint = this.buildVerifyPoint(Minecraft.getInstance().level, ship);
      LOGGER.info(
         "[elytra] 换点 #{} 城={} 船={} shipKnown={} shipLocated={} 剩余 {}",
         this.attempts, city, this.current.shipPos(), this.current.shipKnown(),
         this.current.shipLocated(), this.queue.size()
      );
      this.info(
         "飞往船坐标 #" + this.attempts + " (" + ship.getX() + ", " + ship.getY() + ", " + ship.getZ() + ")"
            + (this.current.shipLocated() ? "" : "（离线未解析船坐标，暂用城坐标）")
            + "，剩余 " + this.queue.size(),
         ChatFormatting.AQUA
      );
   }

   private void nextCandidateWithReason(LocalPlayer player, long now, String reason) {
      this.lastReason = reason;
      LOGGER.info(
         "[elytra] 候选 城={} 船={} 失败：{}",
         this.current == null ? "?" : this.current.pos(),
         this.current == null ? "?" : this.current.shipPos(), reason
      );
      this.info(reason + "，换下一个候选", ChatFormatting.YELLOW);
      this.nextCandidate(player, now);
   }

   // ------------------------------------------------------------------ TRAVELLING

   private void tickTravelling(Minecraft mc, LocalPlayer player, ClientLevel level, long now) {
      if (this.current == null) {
         this.nextCandidate(player, now);
         return;
      }
      if (now - this.phaseStartMs > TRAVEL_TIMEOUT_MS) {
         this.nextCandidateWithReason(player, now, "飞行超时");
         return;
      }
      // Fly to the ship, not to the city's /locate position.
      BlockPos pos = this.current.target();
      Vec3 target = new Vec3(pos.getX() + 0.5, this.flight.cruiseY(), pos.getZ() + 0.5);
      double horiz = Math.hypot(target.x - player.getX(), target.z - player.getZ());

      // Early entity scan when approaching the ship horizontally so we lock on immediately if already synced.
      if (horiz < 64.0 && now - this.lastScanMs >= 500L) {
         this.lastScanMs = now;
         ScanResult earlyScan = this.scan(level, player, pos);
         this.lastScan = earlyScan;
         if (earlyScan.structureHint != null) {
            this.structureHint = earlyScan.structureHint;
         }
         if (this.tryLockTargetFromScan(earlyScan, now)) {
            return;
         }
      }

      if (horiz < ARRIVE_XZ && Math.abs(player.getY() - this.flight.cruiseY()) < 24.0) {
         this.flight.hover(mc, player, true);
         this.phaseStartMs = now;
         this.lastScanMs = 0L;
         this.sweepCount = 0;
         this.sweepTarget = null;
         this.lastScan = null;
         this.verifyPoint = this.buildVerifyPoint(level, pos);
         this.phase = Phase.VERIFYING;
         this.info("已抵达船坐标上空，正在下降到船体高度并校验区块 / 结构 / 展示框…", ChatFormatting.AQUA);
         return;
      }

      // If starting low or inside an enclosed structure (e.g. inside the previous End Ship cabin),
      // climb steeply to cruiseY first and allow mining through any blocking ceiling/tree.
      boolean climbingToCruise = player.getY() < this.flight.cruiseY() - 14.0 && horiz > 24.0;
      Vec3 navTarget = target;
      if (climbingToCruise) {
         double ux = (target.x - player.getX()) / Math.max(horiz, 1.0);
         double uz = (target.z - player.getZ()) / Math.max(horiz, 1.0);
         navTarget = new Vec3(player.getX() + ux * 12.0, this.flight.cruiseY(), player.getZ() + uz * 12.0);
      }

      boolean allowCruiseMine = climbingToCruise || this.flight.liftRetries() >= 2 || this.flight.isMining();
      FlyController.NavState state = this.flight.navigate(
         mc, level, player, navTarget, horiz < SLOW_RADIUS || climbingToCruise, allowCruiseMine
      );
      if (state == FlyController.NavState.BLOCKED) {
         if (this.flight.lift()) {
            this.info("前方有方块阻挡且无法绕行，抬升巡航高度至 " + this.flight.cruiseY(), ChatFormatting.YELLOW);
         } else if (this.flight.navigate(mc, level, player, navTarget, true, true) == FlyController.NavState.BLOCKED) {
            this.nextCandidateWithReason(player, now, "多次抬升后仍被方块阻挡且无法挖掘");
         } else {
            this.throttledInfo("已无法继续抬升，改用 3D 寻路 / 挖掘处理阻挡…", now);
         }
      }
      if (this.flight.checkStuck(player)) {
         if (this.flight.lift()) {
            this.info("疑似卡住（撞塔 / 撞岛），抬升巡航高度至 " + this.flight.cruiseY(), ChatFormatting.YELLOW);
         } else if (this.flight.navigate(mc, level, player, navTarget, true, true) == FlyController.NavState.BLOCKED) {
            this.nextCandidateWithReason(player, now, "多次抬升后仍无法前进");
         }
      }
      this.throttledInfo(
         "飞行中，距目标 " + (int)horiz + " 格，巡航高度 " + this.flight.cruiseY()
            + "，导航=" + state,
         now
      );
   }

   private boolean tryLockTargetFromScan(ScanResult scan, long now) {
      if (scan.elytraFrame != null) {
         this.clearTarget();
         this.targetFrame = scan.elytraFrame;
         this.targetFramePos = scan.elytraFrame.blockPosition();
         this.phaseStartMs = now;
         this.phase = Phase.APPROACHING;
         this.info("找到带鞘翅的展示框，开始 3D 寻路接近…", ChatFormatting.GREEN);
         return true;
      }
      if (scan.dropped != null) {
         this.clearTarget();
         this.droppedItem = scan.dropped;
         this.targetFramePos = scan.dropped.blockPosition();
         this.pickupOnly = true;
         this.phaseStartMs = now;
         this.phase = Phase.APPROACHING;
         this.info("发现已掉落但未拾取的鞘翅，前往吸取…", ChatFormatting.GREEN);
         return true;
      }
      return false;
   }

   // ------------------------------------------------------------------ VERIFYING

   private void tickVerifying(Minecraft mc, LocalPlayer player, ClientLevel level, long now) {
      if (this.current == null) {
         this.nextCandidate(player, now);
         return;
      }
      BlockPos ship = this.current.target();

      // Continuously scan while descending or sweeping so we lock onto the frame the moment it syncs!
      if ((this.verifyPoint != null || this.sweepTarget != null) && now - this.lastScanMs >= 500L) {
         this.lastScanMs = now;
         ScanResult midScan = this.scan(level, player, ship);
         this.lastScan = midScan;
         if (midScan.structureHint != null) {
            this.structureHint = midScan.structureHint;
         }
         if (this.tryLockTargetFromScan(midScan, now)) {
            return;
         }
      }

      // Phase 1: descend to the ship.
      if (this.verifyPoint != null) {
         double d = player.position().distanceTo(this.verifyPoint);
         if (d > 5.0 && now - this.phaseStartMs < VERIFY_APPROACH_TIMEOUT_MS) {
            FlyController.NavState state = this.flight.navigate(mc, level, player, this.verifyPoint, true, true);
            if (state == FlyController.NavState.BLOCKED) {
               this.verifyOffsetIndex++;
               this.verifyPoint = this.buildVerifyPoint(level, ship);
               this.phaseStartMs = now;
               this.info("下降路径被阻挡，换一个角度接近船体…", ChatFormatting.YELLOW);
            } else if (obstacleWork(state)) {
               this.phaseStartMs = now;
            }
            this.throttledInfo("正在下降到船体高度以同步展示框实体（还差 " + (int)d + " 格）…", now);
            return;
         }
         this.verifyPoint = null;
         this.lastScanMs = 0L;
         this.phaseStartMs = now;
      }

      // Phase 2: an explicit sweep waypoint (chunk-load workaround).
      if (this.sweepTarget != null) {
         double d = Math.hypot(this.sweepTarget.x - player.getX(), this.sweepTarget.z - player.getZ());
         if (d > ARRIVE_XZ && now - this.phaseStartMs < VERIFY_TIMEOUT_MS) {
            FlyController.NavState state = this.flight.navigate(mc, level, player, this.sweepTarget, true, true);
            if (obstacleWork(state)) {
               this.phaseStartMs = now;
            }
            return;
         }
         this.sweepTarget = null;
      }

      this.flight.hover(mc, player, true);
      if (now - this.lastScanMs < SCAN_INTERVAL_MS) {
         return;
      }
      this.lastScanMs = now;
      ScanResult scan = this.scan(level, player, ship);
      this.lastScan = scan;
      if (scan.structureHint != null) {
         this.structureHint = scan.structureHint;
      }

      if (this.tryLockTargetFromScan(scan, now)) {
         return;
      }
      if (!scan.chunkLoaded) {
         if (now - this.phaseStartMs < VERIFY_TIMEOUT_MS) {
            this.throttledInfo("等待船所在区块加载…", now);
            return;
         }
         if (this.sweepCount < MAX_SWEEPS) {
            this.sweepCount++;
            this.sweepTarget = spiralPoint(ship, this.sweepCount, this.verifyY(ship));
            this.phaseStartMs = now;
            this.info("区块未完全加载，绕飞重扫 " + this.sweepCount + "/" + MAX_SWEEPS, ChatFormatting.YELLOW);
            return;
         }
         this.nextCandidateWithReason(player, now, "区块长期未加载");
         return;
      }

      // Never judge "looted" from one scan: entity sync lags a few seconds and
      // the offline ship Y can be slightly off. Re-approach from a new angle.
      this.verifyScans++;
      if (this.verifyScans < MAX_VERIFY_SCANS) {
         this.verifyOffsetIndex++;
         this.verifyPoint = this.buildVerifyPoint(level, ship);
         this.phaseStartMs = now;
         this.info(
            "第 " + this.verifyScans + " 次扫描未见鞘翅（客户端实体 " + scan.entities
               + " / 展示框 " + scan.frames + "），换位置重扫…",
            ChatFormatting.YELLOW
         );
         return;
      }

      String reason;
      if (!scan.cityPresent && scan.entities == 0) {
         reason = "船坐标附近未发现末地城结构（种子/worldgen 不符或坐标偏差）";
      } else if (scan.entities == 0) {
         reason = "服务器未同步船附近实体（未真正进入实体追踪范围）";
      } else if (scan.frames > 0) {
         reason = "展示框存在但鞘翅已被取走";
      } else {
         reason = "无船或鞘翅已被取走";
      }
      this.nextCandidateWithReason(
         player, now,
         reason + "（扫描 " + this.verifyScans + " 次，实体 " + scan.entities + "，展示框 " + scan.frames + "）"
      );
   }

   /** Low-altitude point beside the ship; on-site evidence wins when worldgen Y is clearly off. */
   private int verifyY(BlockPos ship) {
      int offline = ship.getY() > 0 ? ship.getY() : DEFAULT_SHIP_Y;
      int hint = this.structureHint == null ? offline : this.structureHint.getY();
      boolean offlineTrustworthy = this.current.shipLocated() && Math.abs(hint - offline) <= 24;
      return (offlineTrustworthy ? offline : hint) + (int)SHIP_APPROACH_LIFT;
   }

   private Vec3 buildVerifyPoint(ClientLevel level, BlockPos ship) {
      double cx = ship.getX();
      double cz = ship.getZ();
      if (!this.current.shipLocated() && this.structureHint != null) {
         cx = this.structureHint.getX();
         cz = this.structureHint.getZ();
      }
      int vy = this.verifyY(ship);
      for (int attempt = 0; attempt < VERIFY_OFFSETS.length; attempt++) {
         int idx = Math.floorMod(this.verifyOffsetIndex + attempt, VERIFY_OFFSETS.length);
         double[] offset = VERIFY_OFFSETS[idx];
         int bx = (int)Math.floor(cx + offset[0]);
         int bz = (int)Math.floor(cz + offset[1]);
         if (level == null || FlyController.isPassableColumn(level, bx, vy, bz)) {
            this.verifyOffsetIndex = idx;
            return new Vec3(bx + 0.5, vy + 0.08, bz + 0.5);
         }
      }
      double[] offset = VERIFY_OFFSETS[Math.floorMod(this.verifyOffsetIndex, VERIFY_OFFSETS.length)];
      return new Vec3(cx + offset[0], vy, cz + offset[1]);
   }

   private static Vec3 spiralPoint(BlockPos center, int index, int y) {
      double[] offset = SWEEP_OFFSETS[(index - 1) % SWEEP_OFFSETS.length];
      return new Vec3(center.getX() + offset[0], y, center.getZ() + offset[1]);
   }

   /**
    * Scans around the SHIP position. Entity hits are the strongest signal: they
    * prove the structure is real and give an exact on-site coordinate used to
    * correct an imprecise offline ship Y. The old block lattice (step 8 in X/Z,
    * step 10 in Y, fixed 60..150) was too coarse for a ship hull and covered
    * the wrong height range; it is now denser and centred on the ship's own Y.
    */
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
            if (result.structureHint == null) {
               result.structureHint = entity.blockPosition();
            }
         }
         if (entity instanceof ItemFrame) {
            result.frames++;
            result.cityPresent = true;
            if (result.structureHint == null) {
               result.structureHint = entity.blockPosition();
            }
            ItemStack stack = frameItem(entity);
            if (stack != null && stack.getItem() == Items.ELYTRA && result.elytraFrame == null) {
               result.elytraFrame = entity;
            }
         } else if (entity instanceof ItemEntity item && item.getItem().getItem() == Items.ELYTRA && result.dropped == null) {
            result.dropped = entity;
            result.cityPresent = true;
            if (result.structureHint == null) {
               result.structureHint = entity.blockPosition();
            }
         }
      }

      if (result.elytraFrame == null && !result.cityPresent) {
         int baseY = center.getY() > 0 ? center.getY() : (int)player.getY();
         int minY = Math.max(40, baseY - 36);
         int maxY = Math.min(220, baseY + 40);
         outer:
         for (int x = center.getX() - 48; x <= center.getX() + 48; x += 6) {
            for (int z = center.getZ() - 48; z <= center.getZ() + 48; z += 6) {
               if (level.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null) {
                  continue;
               }
               for (int y = minY; y <= maxY; y += 6) {
                  BlockPos at = new BlockPos(x, y, z);
                  if (isEndCityBlock(level.getBlockState(at).getBlock())) {
                     result.cityPresent = true;
                     result.structureHint = at;
                     break outer;
                  }
               }
            }
         }
      }
      return result;
   }

   private static boolean isEndCityBlock(Block block) {
      return block == Blocks.PURPUR_BLOCK
         || block == Blocks.PURPUR_PILLAR
         || block == Blocks.END_STONE_BRICKS
         || block == Blocks.STAINED_GLASS.magenta();
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

   // ------------------------------------------------------------------ hostile guards

   /** The point the current phase is trying to reach; used for the threat ray probe. */
   private Vec3 threatGoal(LocalPlayer player) {
      Entity target = this.pickupOnly ? this.droppedItem : this.targetFrame;
      if (target != null && !target.isRemoved()) {
         return target.position();
      }
      if (this.targetFramePos != null) {
         return Vec3.atCenterOf(this.targetFramePos);
      }
      if (this.sweepTarget != null) {
         return this.sweepTarget;
      }
      if (this.verifyPoint != null) {
         return this.verifyPoint;
      }
      if (this.current != null) {
         return Vec3.atCenterOf(this.current.target());
      }
      return player.position().add(0.0, 0.0, 1.0);
   }

   /**
    * Clear the shulker that blocks the path. FlyController tries a detour first;
    * this is the fallback for a guard tucked inside the ship hull.
    *
    * @return true when this tick was consumed by combat (skip the normal phase).
    */
   private boolean tickThreat(Minecraft mc, LocalPlayer player, ClientLevel level, long now) {
      if (this.flight.isIgnoringThreats()) {
         return false;
      }
      // Do not interrupt an active block break for a guard unless the guard is right in our face.
      if (this.flight.isMining()) {
         return false;
      }
      if (this.pickupOnly && this.droppedItem != null && !this.droppedItem.isRemoved()
         && player.distanceToSqr(this.droppedItem) < 144.0) {
         return false;
      }
      Vec3 goal = this.threatGoal(player);
      Entity threat = FlyController.findThreat(level, player, goal);
      if (threat == null || player.distanceToSqr(threat) > 64.0) {
         if (this.threatSinceMs != 0L) {
            LOGGER.info("[elytra] 挡路实体已清除或超出近距范围，恢复正常接近");
         }
         this.threatSinceMs = 0L;
         this.flight.clearEntityState();
         return false;
      }
      // Only engage if we actually have unobstructed line of sight to the Shulker.
      if (!hasUnobstructedSight(level, player, threat.getBoundingBox().getCenter())) {
         this.threatSinceMs = 0L;
         return false;
      }
      if (this.threatSinceMs == 0L) {
         this.threatSinceMs = now;
         LOGGER.info("[elytra] 路径被 {} id={} at {} 阻挡，开始清除",
            threat.getClass().getSimpleName(), threat.getId(), threat.blockPosition());
      }
      this.phaseStartMs = now;
      if (this.dropChaseSinceMs != 0L) {
         this.dropChaseSinceMs = now;
         this.dropProgressSinceMs = now;
      }
      double eyeDist = player.getEyePosition().distanceTo(threat.getBoundingBox().getCenter());
      if (eyeDist <= 3.25) {
         this.flight.hover(mc, player, false);
         FlyController.aimAt(player, threat);
         if (mc.gameMode != null
            && player.getAttackStrengthScale(0.0F) >= 0.9F
            && now - this.lastCombatMs >= COMBAT_COOLDOWN_MS) {
            if (!this.flight.isMining()) {
               FlyController.equipForAttack(player);
            }
            mc.gameMode.attack(player, threat);
            player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            this.lastCombatMs = now;
         }
      } else {
         Vec3 toPlayer = player.position().subtract(threat.position());
         double len = Math.max(0.001, toPlayer.length());
         Vec3 stand = threat.position().add(toPlayer.scale(2.2 / len)).subtract(0.0, 0.5, 0.0);
         this.flight.navigate(mc, level, player, stand, true, true);
      }
      this.throttledInfo("前方有潜影贝挡路，正在清除…", now);
      if (now - this.threatSinceMs > 12000L) {
         LOGGER.warn("[elytra] 清除挡路实体超时 {} ms，改为无视守卫直接取物", now - this.threatSinceMs);
         this.threatSinceMs = 0L;
         this.flight.ignoreThreatsFor(18000L);
         this.flight.clearEntityState();
         this.info("清怪超时，改为直接靠近取物（18 秒）…", ChatFormatting.YELLOW);
      }
      return true;
   }

   private static boolean hasUnobstructedSight(ClientLevel level, LocalPlayer player, Vec3 point) {
      if (level == null || player == null || point == null) {
         return false;
      }
      try {
         BlockHitResult hit = level.clip(
            new ClipContext(player.getEyePosition(), point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)
         );
         return hit.getType() == HitResult.Type.MISS;
      } catch (Throwable ignored) {
         return true;
      }
   }

   private static boolean canAttackEntity(ClientLevel level, LocalPlayer player, Entity entity) {
      if (level == null || player == null || entity == null || entity.isRemoved()) {
         return false;
      }
      Vec3 center = entity.getBoundingBox().getCenter();
      if (player.getEyePosition().distanceTo(center) > 3.35) {
         return false;
      }
      return hasUnobstructedSight(level, player, center);
   }

   // ------------------------------------------------------------------ live target resolution

   /**
    * Client-side entity instances are disposable: the server drops an entity
    * from the tracking range when the player crosses a chunk / view-distance
    * boundary and re-sends a brand-new instance a moment later. The old Java
    * reference stays {@code isRemoved() == true} forever while the frame is
    * still hanging on the wall. Never treat that as "the frame disappeared".
    */
   private TargetStatus refreshTarget(ClientLevel level, LocalPlayer player, long now) {
      Entity drop = this.findDroppedElytra(level, player);
      if (drop != null) {
         if (this.droppedItem != drop) {
            LOGGER.info("[elytra] 锁定掉落鞘翅 id={} at {}", drop.getId(), drop.blockPosition());
         }
         this.droppedItem = drop;
         this.pickupOnly = true;
         // Do NOT start the pickup timer here. refreshTarget() is called from
         // every phase, so a drop seen early (e.g. popped by the mining key)
         // would burn the whole budget while combat/detours were still running.
         // chaseDrop() owns the timer and only starts it once it is actually
         // flying towards the item.
         this.targetMissingSinceMs = 0L;
         this.targetIssue = null;
         return TargetStatus.DROP;
      }

      Entity frame = this.targetFrame;
      boolean stale = frame == null || frame.isRemoved() || !frame.isAlive() || !(frame instanceof ItemFrame);
      if (stale) {
         Entity replacement = this.findFrameNearAnchor(level);
         if (replacement != null) {
            LOGGER.info(
               "[elytra] 展示框实体重新同步：旧={} 新 id={} at {}（第 {} 次重绑）",
               frame == null ? "null" : "removed(id=" + frame.getId() + ")",
               replacement.getId(), replacement.blockPosition(), ++this.rebindCount
            );
            frame = replacement;
            this.targetFrame = frame;
            this.targetFramePos = frame.blockPosition();
         } else {
            frame = null;
            this.targetFrame = null;
         }
      }

      if (frame != null) {
         ItemStack stack = frameItem(frame);
         if (!stack.isEmpty() && stack.getItem() == Items.ELYTRA) {
            this.pickupOnly = false;
            this.itemPopped = false;
            // Keep the chase budget running: a client-side frame/drop flicker
            // must not reset the timer forever and pin us to one ship.
            this.targetMissingSinceMs = 0L;
            this.targetIssue = null;
            return TargetStatus.FRAME;
         }
         // The frame is still there but the elytra was popped (by the mining
         // key, by another player, ...). Give the drop packet time to arrive.
         this.targetIssue = "展示框仍在，鞘翅已弹出";
      } else {
         this.targetIssue = "目标实体客户端实例已失效";
      }

      if (this.targetMissingSinceMs == 0L) {
         this.targetMissingSinceMs = now;
         LOGGER.info(
            "[elytra] 目标暂不可用：{}，锚点={}",
            this.targetIssue, this.targetFramePos == null ? "无" : format(this.targetFramePos)
         );
      }
      return now - this.targetMissingSinceMs < TARGET_REBIND_GRACE_MS
         ? TargetStatus.WAIT
         : TargetStatus.LOST;
   }

   /** Find a live ItemFrame instance at the recorded anchor (possibly a replacement instance). */
   private Entity findFrameNearAnchor(ClientLevel level) {
      if (this.targetFramePos == null) {
         return null;
      }
      Vec3 anchor = Vec3.atCenterOf(this.targetFramePos);
      double radiusSq = REBIND_RADIUS * REBIND_RADIUS;
      Entity best = null;
      double bestDistance = Double.MAX_VALUE;
      for (Entity entity : level.entitiesForRendering()) {
         if (!(entity instanceof ItemFrame) || entity.isRemoved() || !entity.isAlive()) {
            continue;
         }
         double distance = entity.position().distanceToSqr(anchor);
         if (distance <= radiusSq && distance < bestDistance) {
            best = entity;
            bestDistance = distance;
         }
      }
      return best;
   }

   /** Limit drops to the anchor so somebody else's elytra far away cannot hijack the state machine. */
   private Entity findDroppedElytra(ClientLevel level, LocalPlayer player) {
      Vec3 anchor = this.targetFramePos == null ? player.position() : Vec3.atCenterOf(this.targetFramePos);
      double radiusSq = DROP_RADIUS * DROP_RADIUS;
      Entity best = null;
      double bestDistance = Double.MAX_VALUE;
      for (Entity entity : level.entitiesForRendering()) {
         if (!(entity instanceof ItemEntity item) || entity.isRemoved() || !entity.isAlive()) {
            continue;
         }
         if (item.getItem().getItem() != Items.ELYTRA) {
            continue;
         }
         double distance = item.position().distanceToSqr(anchor);
         if (distance <= radiusSq && distance < bestDistance) {
            best = item;
            bestDistance = distance;
         }
      }
      return best;
   }

   private static boolean crosshairOn(Minecraft mc, Entity target) {
      return mc != null && mc.hitResult instanceof EntityHitResult hit && hit.getEntity() == target;
   }

   /**
    * Hovering outside the entity tracking range would never bring the frame
    * back. While waiting for a re-sync, fly back towards the recorded anchor.
    *
    * @return true when navigation was started (caller should just return).
    */
   private boolean flyBackToAnchor(Minecraft mc, ClientLevel level, LocalPlayer player, long now) {
      if (this.targetFramePos == null) {
         return false;
      }
      Vec3 anchor = Vec3.atCenterOf(this.targetFramePos);
      if (player.position().distanceTo(anchor) <= 12.0) {
         return false;
      }
      this.flight.navigate(mc, level, player, anchor, true, true);
      this.throttledInfo("目标实体暂未同步，正在靠近展示框位置触发重新同步…", now);
      return true;
   }

   // ------------------------------------------------------------------ APPROACHING

   private void tickApproaching(Minecraft mc, LocalPlayer player, ClientLevel level, long now) {
      TargetStatus status = this.refreshTarget(level, player, now);
      if (status == TargetStatus.LOST) {
         this.nextCandidateWithReason(
            player, now,
            (this.targetIssue == null ? "目标实体已消失" : this.targetIssue)
               + "（等待重新同步 " + (TARGET_REBIND_GRACE_MS / 1000L) + " 秒，重绑 "
               + this.rebindCount + " 次）"
         );
         return;
      }
      if (status == TargetStatus.WAIT) {
         if (!this.flyBackToAnchor(mc, level, player, now)) {
            this.flight.hover(mc, player, false);
            this.throttledInfo("目标实体暂未同步 / 鞘翅刚弹出，等待重新出现…", now);
         }
         return;
      }
      if (status == TargetStatus.DROP) {
         this.itemPopped = true;
         this.attackAttempts = 0;
         this.lastAttackMs = 0L;
         this.resetDropChase();
         this.phaseStartMs = now;
         this.phase = Phase.TAKING;
         this.info("检测到掉落的鞘翅，开始吸取追踪…", ChatFormatting.AQUA);
         return;
      }
      long approachLimit = this.pickupOnly ? VERIFY_APPROACH_TIMEOUT_MS : APPROACH_TIMEOUT_MS;
      if (now - this.phaseStartMs > approachLimit) {
         this.nextCandidateWithReason(player, now, "贴身接近超时");
         return;
      }
      Entity target = this.pickupOnly ? this.droppedItem : this.targetFrame;
      if (target == null || target.isRemoved() || !target.isAlive()) {
         this.throttledInfo("目标实体正在重新同步…", now);
         return;
      }

      // As soon as the ItemFrame is within true attack range and unobstructed, take it immediately!
      if (status == TargetStatus.FRAME && canAttackEntity(level, player, target)) {
         this.obstacleSinceMs = 0L;
         this.flight.hover(mc, player, false);
         FlyController.aimAt(player, target);
         this.phaseStartMs = now;
         this.attackAttempts = 0;
         this.lastAttackMs = 0L;
         this.itemPopped = false;
         this.resetDropChase();
         this.phase = Phase.TAKING;
         this.info("已进入展示框有效攻击距离且视线畅通，开始取物", ChatFormatting.AQUA);
         return;
      }

      Vec3 hoverPoint = this.pickupOnly
         ? target.position().add(0.0, 0.1, 0.0)
         : hoverPointFor(level, target, player);
      double distance = player.position().distanceTo(hoverPoint);
      double arrival = this.pickupOnly ? 1.2 : 1.8;

      if (distance > arrival) {
         FlyController.NavState state = this.flight.navigate(mc, level, player, hoverPoint, true, true);
         if (obstacleWork(state)) {
            if (this.obstacleSinceMs == 0L) {
               this.obstacleSinceMs = now;
            }
            if (now - this.obstacleSinceMs > OBSTACLE_GRACE_MS) {
               this.nextCandidateWithReason(player, now, "通路持续受阻（绕行 / 挖掘 / 清怪）");
               return;
            }
            this.phaseStartMs = now;
            this.throttledInfo(
               state == FlyController.NavState.MINING
                  ? "接近路径有方块阻挡，正在挖掘通道…"
                  : "正在通过 3D 寻路接近目标…",
               now
            );
         } else {
            this.obstacleSinceMs = 0L;
            this.throttledInfo("接近目标中，还差 " + String.format("%.1f", distance) + " 格", now);
         }
         return;
      }
      this.obstacleSinceMs = 0L;
      this.flight.hover(mc, player, false);
      if (this.pickupOnly) {
         this.throttledInfo("正在吸取掉落的鞘翅…", now);
         return;
      }
      FlyController.aimAt(player, target);
      this.phaseStartMs = now;
      this.attackAttempts = 0;
      this.lastAttackMs = 0L;
      this.itemPopped = false;
      this.resetDropChase();
      this.phase = Phase.TAKING;
      this.info("已就位，开始左键取物", ChatFormatting.AQUA);
   }

   /**
    * Compute the optimal player-feet hover point in front of the ItemFrame.
    *
    * <p>Note: {@code FlyController} treats {@code target.y} as the player's
    * <b>feet</b> Y ({@code player.getY()}), while the player's eyes are at
    * {@code feet + 1.62} and head top is at {@code feet + 1.8}. Setting
    * {@code y = frame.getY() + 0.5} in the legacy code placed the player's
    * head inside the End Ship cabin ceiling! Here we place the player's feet
    * on the cabin floor (~1 block below the frame) and prefer an already-open
    * 1x2 column with direct line of sight to the frame.</p>
    */
   private static Vec3 hoverPointFor(ClientLevel level, Entity target, LocalPlayer player) {
      Direction facing = null;
      if (target instanceof ItemFrame frame) {
         try {
            facing = frame.getDirection();
         } catch (Throwable ignored) {
         }
      }
      Vec3 frameCenter = target.getBoundingBox().getCenter();
      int frameBlockY = target.blockPosition().getY();

      if (facing != null && level != null) {
         Direction sideDir = facing.getClockWise();
         double[] dists = {1.5, 1.2, 1.9, 0.9};
         int[] dyFeet = {-1, 0, -2};
         int[] sides = {0, -1, 1};

         for (double d : dists) {
            for (int dy : dyFeet) {
               for (int side : sides) {
                  double cx = target.getX() + facing.getStepX() * d + sideDir.getStepX() * side * 0.7;
                  double cz = target.getZ() + facing.getStepZ() * d + sideDir.getStepZ() * side * 0.7;
                  int bx = (int)Math.floor(cx);
                  int by = frameBlockY + dy;
                  int bz = (int)Math.floor(cz);
                  if (!FlyController.isPassableColumn(level, bx, by, bz)) {
                     continue;
                  }
                  Vec3 eyePos = new Vec3(bx + 0.5, by + 1.62, bz + 0.5);
                  try {
                     BlockHitResult hit = level.clip(
                        new ClipContext(eyePos, frameCenter, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)
                     );
                     if (hit.getType() == HitResult.Type.MISS) {
                        return new Vec3(bx + 0.5, by + 0.08, bz + 0.5);
                     }
                  } catch (Throwable ignored) {
                  }
               }
            }
         }
         return new Vec3(
            target.getX() + facing.getStepX() * 1.5,
            frameBlockY - 1 + 0.08,
            target.getZ() + facing.getStepZ() * 1.5
         );
      }

      double dx = player.getX() - target.getX();
      double dz = player.getZ() - target.getZ();
      double length = Math.max(Math.sqrt(dx * dx + dz * dz), 0.001);
      return new Vec3(
         target.getX() + dx / length * 1.6,
         target.getY() - 1.0,
         target.getZ() + dz / length * 1.6
      );
   }

   // ------------------------------------------------------------------ TAKING

   private void tickTaking(Minecraft mc, LocalPlayer player, ClientLevel level, long now) {
      TargetStatus status = this.refreshTarget(level, player, now);
      if (status == TargetStatus.LOST) {
         this.nextCandidateWithReason(
            player, now,
            (this.targetIssue == null ? "展示框被移除" : this.targetIssue) + "，且未找到掉落鞘翅"
         );
         return;
      }
      if (status == TargetStatus.WAIT) {
         if (!this.flyBackToAnchor(mc, level, player, now)) {
            this.flight.hover(mc, player, false);
            this.throttledInfo("等待展示框 / 掉落鞘翅重新同步…", now);
         }
         return;
      }
      if (status == TargetStatus.DROP) {
         this.itemPopped = true;
         this.chaseDrop(mc, player, level, this.droppedItem, now);
         return;
      }

      // FRAME: the elytra is still inside a live item frame.
      this.itemPopped = false;
      if (now - this.phaseStartMs > TAKE_TIMEOUT_MS) {
         this.nextCandidateWithReason(player, now, "取物超时");
         return;
      }
      Entity frame = this.targetFrame;
      if (frame == null) {
         this.throttledInfo("等待展示框实体同步…", now);
         return;
      }
      if (!canAttackEntity(level, player, frame)) {
         FlyController.NavState state = this.flight.navigate(
            mc, level, player, hoverPointFor(level, frame, player), true, true
         );
         if (obstacleWork(state)) {
            this.phaseStartMs = now;
         }
         this.throttledInfo(
            state == FlyController.NavState.MINING
               ? "展示框被方块遮挡，正在挖开阻挡…"
               : "正在贴近展示框至有效攻击距离…",
            now
         );
         return;
      }
      this.flight.hover(mc, player, false);
      FlyController.aimAt(player, frame);
      if (this.attackAttempts >= ATTACK_RETRIES) {
         this.nextCandidateWithReason(player, now, "攻击 " + ATTACK_RETRIES + " 次仍未取得鞘翅");
         return;
      }
      if (player.getAttackStrengthScale(0.0F) >= 0.9F
         && now - this.lastAttackMs >= 850L
         && mc.gameMode != null) {
         if (!this.flight.isMining()) {
            FlyController.equipForAttack(player);
         }
         mc.gameMode.attack(player, frame);
         player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
         this.attackAttempts++;
         this.lastAttackMs = now;
         LOGGER.info("[elytra] 左键攻击展示框 #{} at {}", this.attackAttempts, frame.blockPosition());
      }
   }

   /**
    * Chase one dropped elytra until the inventory count changes.
    */
   private void chaseDrop(Minecraft mc, LocalPlayer player, ClientLevel level, Entity drop, long now) {
      if (drop == null || drop.isRemoved()) {
         this.flight.hover(mc, player, false);
         this.throttledInfo("等待掉落鞘翅重新同步…", now);
         return;
      }
      if (this.dropChaseSinceMs == 0L) {
         this.dropChaseSinceMs = now;
         this.dropBestDistance = Double.MAX_VALUE;
         this.dropProgressSinceMs = now;
         this.dropCloseSinceMs = 0L;
         this.dropDigUntilMs = 0L;
         this.dropIssue = null;
         LOGGER.info(
            "[elytra] 开始吸取掉落鞘翅 id={} at {} 距离={}",
            drop.getId(), drop.blockPosition(),
            String.format("%.2f", player.position().distanceTo(drop.position()))
         );
      }
      this.phaseStartMs = now;
      double distance = player.position().distanceTo(drop.position());
      if (distance < this.dropBestDistance - 0.15) {
         this.dropBestDistance = distance;
         this.dropProgressSinceMs = now;
      }

      boolean directClear = this.flight.isBoxSweepClear(
         level, player, player.position(), drop.position(), distance + 0.4, false
      );

      // Use 3D A* + block mining whenever a block is between the player and the drop,
      // or during an active dig window.
      if ((!directClear && distance > 0.9) || (this.dropDigUntilMs != 0L && now < this.dropDigUntilMs)) {
         FlyController.NavState state = this.flight.navigate(
            mc, level, player, drop.position(), true, true
         );
         this.dropIssue = "寻路/挖路中（导航=" + state + "，最近 "
            + String.format("%.1f", this.dropBestDistance) + " 格）";
         this.throttledInfo("正在挖开 / 绕行接近掉落的鞘翅…", now);
         return;
      }
      if (this.dropDigUntilMs != 0L) {
         this.dropDigUntilMs = 0L;
         this.dropProgressSinceMs = now;
      }

      boolean close = this.flight.approachForPickup(mc, player, drop.position());
      if (close) {
         if (this.dropCloseSinceMs == 0L) {
            this.dropCloseSinceMs = now;
         }
         this.flight.hover(mc, player, false);
         if (now - this.dropCloseSinceMs > DROP_PICKUP_WAIT_MS) {
            String full = player.getInventory().getFreeSlot() < 0 ? "（背包已满）" : "";
            this.nextCandidateWithReason(
               player, now,
               "已贴到掉落鞘翅（" + String.format("%.2f", distance) + " 格）仍未拾取" + full
                  + "，等待 " + (DROP_PICKUP_WAIT_MS / 1000L) + " 秒"
            );
         } else {
            this.throttledInfo("已贴到掉落物上，等待服务端拾取…", now);
         }
         return;
      }
      this.dropCloseSinceMs = 0L;

      if (now - this.dropProgressSinceMs > DROP_STALL_MS) {
         this.dropDigUntilMs = now + DROP_DIG_MS;
         this.dropIssue = "直线靠近受阻，改为挖掘 / 绕行";
         this.info("吸取路径被挡，正在挖开 / 绕行接近掉落物…", ChatFormatting.YELLOW);
         return;
      }
      if (now - this.dropChaseSinceMs > DROP_CHASE_TIMEOUT_MS) {
         String full = player.getInventory().getFreeSlot() < 0 ? "，背包已满" : "";
         this.nextCandidateWithReason(
            player, now,
            "掉落鞘翅追踪 " + (DROP_CHASE_TIMEOUT_MS / 1000L) + " 秒仍未拾取（最近 "
               + String.format("%.1f", this.dropBestDistance) + " 格，"
               + (this.dropIssue == null ? "直线接近" : this.dropIssue) + full + "）"
         );
         return;
      }
      this.throttledInfo(
         "正在吸取掉落的鞘翅（距 " + String.format("%.1f", distance) + " 格）…", now
      );
   }

   // ------------------------------------------------------------------ collection / finish

   /** A new elytra appeared: record it, refresh the baseline and keep hunting. */
   private void onElytraGained(Minecraft mc, LocalPlayer player, long now) {
      int total = countElytra(player);
      this.baselineElytra = total;
      this.collected++;
      this.flight.release(mc);
      long seconds = (now - this.globalStartMs) / 1000L;
      this.info(
         "已取得第 " + this.collected + " 个鞘翅（背包共 " + total + " 个，耗时 " + seconds
            + " 秒，已尝试 " + this.attempts + " 个候选）",
         ChatFormatting.GREEN
      );
      LOGGER.info(
         "[elytra] collected #{} in {} ms after {} candidates",
         this.collected, now - this.globalStartMs, this.attempts
      );

      if (!this.equipAttempted) {
         this.equipAttempted = true;
         this.tryEquip(mc, player);
         // The container click moves the stack between slots synchronously;
         // re-reading the baseline keeps the "inventory count increased" test exact.
         this.baselineElytra = countElytra(player);
      }
      if (this.targetElytra > 0 && this.collected >= this.targetElytra) {
         this.finish(mc, "已达目标数量 " + this.targetElytra + " 个");
         return;
      }
      if (player.getInventory().getFreeSlot() < 0) {
         this.finish(mc, "背包已满，无法继续拾取");
         return;
      }
      this.continueHunting(player, now);
   }

   /** Reset per-candidate state and immediately pick the next ship. */
   private void continueHunting(LocalPlayer player, long now) {
      this.current = null;
      this.clearTarget();
      this.sweepTarget = null;
      this.sweepCount = 0;
      this.lastScan = null;
      this.lastScanMs = 0L;
      this.lastAttackMs = 0L;
      this.attackAttempts = 0;
      this.flight.resetForNewTarget();
      this.phaseStartMs = now;
      this.info("继续收集下一个鞘翅…", ChatFormatting.AQUA);
      this.nextCandidate(player, now);
   }

   /** Optional and non-blocking: if the container API misbehaves, just tell the player. */
   private void tryEquip(Minecraft mc, LocalPlayer player) {
      try {
         if (player != null && player.containerMenu instanceof InventoryMenu && mc.gameMode != null) {
            int invSlot = this.findElytraSlot(player);
            if (invSlot >= 0) {
               int menuSlot = invSlot < 9 ? invSlot + 36 : invSlot;
               mc.gameMode.handleContainerInput(0, menuSlot, 0, ContainerInput.QUICK_MOVE, player);
               this.info("已尝试自动装备一个鞘翅（快捷移动至胸甲槽）", ChatFormatting.GREEN);
            }
         }
      } catch (Throwable t) {
         this.info("自动装备跳过，请手动装备鞘翅", ChatFormatting.YELLOW);
      }
   }

   private void finish(Minecraft mc, String reason) {
      this.lastReason = reason;
      this.phase = Phase.DONE;
      this.flight.release(mc);
      this.info(
         "流程结束：" + reason + "；本次共收集 " + this.collected
            + " 个鞘翅，飞行保持开启（不会掉进虚空）",
         ChatFormatting.GREEN
      );
      LOGGER.info("[elytra] finished: {} (collected {})", reason, this.collected);
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
      this.info("已停止：" + reason + "（本次共收集 " + this.collected + " 个）", ChatFormatting.RED);
      LOGGER.info("[elytra] aborted: {} (collected {})", reason, this.collected);
      this.phase = Phase.IDLE;
   }

   private void fail(String reason) {
      this.lastReason = reason;
      this.info(reason, ChatFormatting.RED);
      this.phase = Phase.ABORTED;
      this.phase = Phase.IDLE;
   }

   // ------------------------------------------------------------------ helpers

   /** True while the flight controller is actively routing around / through a block. */
   private static boolean obstacleWork(FlyController.NavState state) {
      return state == FlyController.NavState.DETOUR
         || state == FlyController.NavState.APPROACH_MINE
         || state == FlyController.NavState.MINING
         || state == FlyController.NavState.ENTITY
         || state == FlyController.NavState.BLOCKED;
   }

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
