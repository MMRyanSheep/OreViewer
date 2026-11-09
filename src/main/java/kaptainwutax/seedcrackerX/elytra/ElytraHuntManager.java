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
import kaptainwutax.seedcrackerX.autoeat.AutoEatManager;
import kaptainwutax.seedcrackerX.automine.BaritoneBridge;
import kaptainwutax.seedcrackerX.automine.ShulkerAutoStore;
import kaptainwutax.seedcrackerX.command.ClientCommand;
import kaptainwutax.seedcrackerX.fly.FlyManager;
import kaptainwutax.seedcrackerX.guidance.VanillaStructureLocator;
import kaptainwutax.seedcrackerX.xray.OreXrayManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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
 * target count is reached, the inventory is full, or {@code /ov qc 停止} is
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

   private static final int DEFAULT_CANDIDATES = 24;
   private static final int MAX_QUEUE_SIZE = 64;
   private static final int REFILL_QUEUE_THRESHOLD = 4;
   private static final int MAX_TARGET_ELYTRA = 64;
   private static final double ARRIVE_XZ = 12.0;
   private static final double SLOW_RADIUS = 32.0;
   private static final double SCAN_RADIUS = 96.0;
   // An end ship cabin sits around box.minY() + 6 while cruising happens at CRUISE_Y=165+.
   // ItemFrame entities are only synced to clients within the server's entity
   // tracking range, so VERIFYING descends directly above the ship cabin roof first.
   private static final double SHIP_APPROACH_LIFT = 8.0;
   // Measured range of ship bounding-box centres is y=95..171 across three
   // seeds; the city placement start is only y=60..66.
   private static final int DEFAULT_SHIP_Y = 120;
   private static final int MAX_VERIFY_SCANS = 8;
   private static final long VERIFY_APPROACH_TIMEOUT_MS = 20000L;
   // Sweeps may have to dig through an end-ship hull, so give them more room.
   private static final long VERIFY_TIMEOUT_MS = 30000L;
   /**
    * There is NO fixed flight-time limit any more. The old 120 s wall clock was
    * the only deadline, so a ship 5000+ blocks away (the outward search pushes
    * 1500 blocks per round, up to ~100000 in late rounds) was abandoned with
    * "飞行超时" while the player was still closing in at full speed. Instead we
    * only give up after a continuous no-progress window: every real step
    * towards the ship renews the deadline, so trip length can never cause a
    * false timeout. See {@link #travelDeadlineMs}.
    */
   /** Grace period before the first measurable progress (climb out, chunk load). */
   private static final long TRAVEL_START_GRACE_MS = 120000L;
   /** +1 s of start grace per this many blocks of initial distance, capped. */
   private static final double TRAVEL_GRACE_PER_BLOCKS = 250.0;
   private static final long TRAVEL_MAX_START_GRACE_MS = 240000L;
   /**
    * Give up only after this long without any progress. Generous on purpose:
    * mining through an island can stall horizontal progress for a while, and a
    * false "飞行超时" is far worse than a slow give-up.
    */
   private static final long TRAVEL_STALL_MS = 90000L;
   /** Distance improvement that counts as progress and renews the deadline. */
   private static final double TRAVEL_PROGRESS_STEP = 12.0;
   private static final long TAKE_TIMEOUT_MS = 10000L;
   // Vertical-column approach: locate the item frame, align with the block center
   // in front of the frame, then dig a 1x1 vertical shaft down through the cabin roof.
   private static final int DESCENT_CLEARANCE = 7;
   private static final double DESCENT_FLOOR_OFFSET = 1.0;
   /** Above this distance a coarse 3D flight to the overhead point is used; below it the strict align servo takes over. */
   private static final double COLUMN_COARSE_XZ = 3.0;
   /** Maximum X/Z drift tolerated while descending before re-centring directly over the frame. */
   private static final double COLUMN_DESCENT_MAX_XZ = 1.2;
   /** At a raised fly speed exact centring may be physically unreachable: accept a slightly wider column after this long. */
   private static final long COLUMN_ALIGN_GRACE_MS = 20000L;
   private static final double COLUMN_ALIGN_FALLBACK_XZ = 0.6;
   private static final long VERTICAL_APPROACH_TIMEOUT_MS = 60000L;
   private static final long COLUMN_BLOCKED_TIMEOUT_MS = 3000L;
   private static final int DEPART_CLEARANCE = 24;
   private static final long DEPART_TIMEOUT_MS = 30000L;
   /** Give up climbing (and continue the hunt) after this long without gaining height. */
   private static final long DEPART_STALL_MS = 8000L;
   // Only enforced for a finite target; renewed on every pickup.
   private static final long GLOBAL_TIMEOUT_MS = 1200000L;
   private static final long LOCATE_TIMEOUT_MS = 90000L;
   private static final int ATTACK_RETRIES = 3;
   private static final int ORIGIN_PUSH = 1500;
   private static final int CENTRAL_ISLAND_EXCLUSION_RADIUS = 1024;
   private static final int OUTWARD_MIN_RADIUS = 1280;
   private static final double OUTWARD_ANGLE_SWING = 0.5;
   private static final int CENTER_HYSTERESIS = 512;
   /**
    * Outward-from-(0,0) preference. The candidate ranking still starts from
    * nearest-neighbour distance (see {@link #takeNextCandidate}), but a ship
    * further from the origin gets a discount while a ship back towards the
    * already-looted centre is charged extra. The discount is capped so a
    * distant ship can never win purely by having a large radius - that was
    * the old rule, which produced 4000+ block zig-zag flights.
    */
   private static final double OUTWARD_BIAS_FACTOR = 0.5;
   private static final double OUTWARD_BIAS_CAP = 700.0;
   /**
    * Hard ceiling on the extra flight the outward preference may cost: only
    * ships within this many blocks of the closest untried ship compete. This
    * is what keeps the 4000+ block cross-quadrant zig-zag from coming back.
    */
   private static final double OUTWARD_DETOUR_MAX = 900.0;
   /** How far the ring-search centre is nudged outwards on a refill/rescan. */
   private static final int OUTWARD_SEARCH_LEAD = 400;
   // Continuous collection keeps pushing outwards as nearby ships get looted.
   private static final int MAX_RESCAN_ROUNDS = 256;
   private static final int MAX_SWEEPS = 3;
   private static final long SCAN_INTERVAL_MS = 1000L;
   private static final long INFO_INTERVAL_MS = 3000L;
   // A client-side Entity instance is discarded (isRemoved() == true) whenever
   // the server stops tracking it and later re-adds a fresh instance. The frame
   // is still in the world - only the old Java reference is stale. Re-resolve
   // by the recorded anchor instead of jumping to the next candidate.
   private static final long TARGET_REBIND_GRACE_MS = 5000L;
   // A dropped elytra is a real, visible target: chase it with a generous
   // budget instead of an 8-second wall-clock grace.
   private static final long DROP_CHASE_TIMEOUT_MS = 45000L;
   /** No-progress window before switching from a straight approach to a dig/route. */
   private static final long DROP_STALL_MS = 2500L;
   /** How long to hover on top of the item waiting for the server-side pickup. */
   private static final long DROP_PICKUP_WAIT_MS = 6000L;
   /** Length of one "dig through the blocker" attempt when the direct path stalls. */
   private static final long DROP_DIG_MS = 8000L;
   private static final double REBIND_RADIUS = 8.0;
   private static final double DROP_RADIUS = 48.0;
   private static final double BULLET_SWAT_RANGE_SQ = 3.0 * 3.0;
   /** Rate-limit the packets; a single hit destroys a ShulkerBullet. */
   private static final long BULLET_SWAT_COOLDOWN_MS = 120L;
   private static final double[][] SWEEP_OFFSETS = {{24, 0}, {0, 24}, {-24, 0}, {0, -24}, {32, 32}};
   // Approach points directly above and closely around the ship cabin so the
   // ItemFrame is always well within the server's entity tracking range (<16 blocks)
   // and never clips into adjacent End City towers at +/-20..26.
   private static final double[][] VERIFY_OFFSETS = {
      {0, 0}, {0, -8}, {0, 8}, {-8, 0}, {8, 0},
      {10, 10}, {-10, 10}, {10, -10}, {-10, -10},
      {14, 0}, {-14, 0}, {0, 14}, {0, -14}
   };

   public enum Phase {
      IDLE,
      LOCATING,
      TRAVELLING,
      VERIFYING,
      APPROACHING,
      TAKING,
      DEPARTING,
      DONE,
      ABORTED
   }

   /** Sub-stage of the strictly vertical approach to the located item frame. */
   private enum ColumnStage {
      ALIGN,   // fly to the point directly above the frame's X/Z column
      DESCEND  // dig straight down inside that column
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
   /** Inner-ring ships skipped by the outward floor; excluded from later locates so they cannot thrash. */
   private final Set<Long> inwardSkipped = new HashSet<>();

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
   /** 0 = keep collecting until {@code /ov qc 停止}. */
   private int targetElytra;
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
   /** Furthest radius from (0,0) reached during this run; never decreases. */
   private int maxRadiusSeen;
   /** Radial gain (candidate radius - player radius) of the last chosen ship. */
   private double lastRadialGain;
   private int attackAttempts;
   private long phaseStartMs;
   /** Absolute deadline for the current cruise; renewed whenever the ship gets closer. */
   private long travelDeadlineMs;
   /** Smallest horizontal distance to the ship seen during this cruise. */
   private double travelBestHoriz;
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
   /** Defensive-only: last time a ShulkerBullet was knocked away. */
   private long lastBulletSwatMs;
   private int bulletsSwatted;
   private Vec3 lastBulletPos;
   private long obstacleSinceMs;
   /** Vertical shaft derived from the located item frame coordinates. */
   private ColumnStage columnStage;
   private double columnX;
   private double columnZ;
   private double columnTopY;
   private double columnFloorY;
   /** Lowest feet Y reached during the current descent; used to renew the approach timeout on real progress. */
   private double columnBestY;
   private long columnSinceMs;
   private long columnBlockedSinceMs;
   /** Vertical departure after a pickup, before the next candidate is chosen. */
   private double departX;
   private double departZ;
   private double departY;
   private long departSinceMs;
   /** Highest Y reached during the departure, used for progress-based stall detection. */
   private double departBestY;
   private long departProgressMs;
   /** Non-null = finish with this reason once the vertical departure completes. */
   private String departFinishReason;
   /** 0 = not paused; otherwise the wall-clock timestamp of the last paused tick. */
   private long autoEatPauseMs;

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
      this.lastBulletSwatMs = 0L;
      this.lastBulletPos = null;
      this.obstacleSinceMs = 0L;
      this.pickupOnly = false;
      this.itemPopped = false;
      this.columnStage = null;
      this.columnSinceMs = 0L;
      this.columnBlockedSinceMs = 0L;
      this.columnBestY = 0.0;
      this.departSinceMs = 0L;
      this.departBestY = 0.0;
      this.departProgressMs = 0L;
      this.departFinishReason = null;
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

   /** Called by {@code /ov qc}. Performs every precondition check listed in the plan. */
   public void start() {
      Minecraft mc = Minecraft.getInstance();
      if (this.isRunning()) {
         this.info("已在运行中；如需退出请执行 /ov qc 停止", ChatFormatting.YELLOW);
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
         this.fail("尚未设置世界种子，请先用 /ov sz <种子> 设置");
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
      this.bulletsSwatted = 0;
      this.lastBulletSwatMs = 0L;
      this.lastBulletPos = null;

      this.autoEatPauseMs = 0L;
      this.queue.clear();
      this.tried.clear();
      this.inwardSkipped.clear();
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
      this.maxRadiusSeen = this.outwardBaseRadius;
      this.lastRadialGain = 0.0;
      this.updateOutwardFloor(player);
      this.lastOrigin = player.blockPosition();
      VanillaStructureLocator.clearCityCache();
      this.phase = Phase.LOCATING;
      this.phaseStartMs = this.globalStartMs;
      this.info(
         "开始持续收集（目标 " + (this.targetElytra == 0 ? "不限，/ov qc 停止 结束" : this.targetElytra + " 个")
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
      this.candidateCount = Math.max(1, Math.min(MAX_QUEUE_SIZE, count));
      this.info("候选数量已设为 " + this.candidateCount, ChatFormatting.GREEN);
   }

   /** {@code 0} means "collect until stopped"; any other value is the haul target for one run. */
   public void setTargetElytra(int count) {
      this.targetElytra = Math.max(0, Math.min(MAX_TARGET_ELYTRA, count));
      this.info(
         this.targetElytra == 0
            ? "目标数量设为不限（持续收集，/ov qc 停止 结束）"
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
      this.lastOrigin = this.searchOriginFor(player, this.rescanRound);
      this.clearTarget();
      this.phase = Phase.LOCATING;
      this.phaseStartMs = System.currentTimeMillis();
      this.info(
         "已清空候选缓存，从 ("
            + this.lastOrigin.getX() + ", " + this.lastOrigin.getZ() + ")"
            + " 重新定位未访问的带船末地城（已排除 " + (this.tried.size() / 2) + " 处已访问目标）…",
         ChatFormatting.GREEN
      );
      this.submitLocating(this.lastOrigin, Math.min(MAX_QUEUE_SIZE, this.candidateCount + this.rescanRound * 4));
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
            + " 最远半径=" + this.maxRadiusSeen
            + " 内侧跳过=" + (this.inwardSkipped.size() / 2)
            + " 本次径向=" + (int)this.lastRadialGain
            + " 外推方位=" + (int)Math.toDegrees(this.outwardAngle) + "°"
            + " 巡航高度=" + this.flight.cruiseY()
            + " 玩家高度=" + (player == null ? -1 : (int)player.getY())
            + " 抬升次数=" + this.flight.liftRetries()
            + " 导航=" + this.flight.navState()
            + " 无进展剩余=" + (this.travelDeadlineMs == 0L
               ? 0 : Math.max(0L, (this.travelDeadlineMs - System.currentTimeMillis()) / 1000L)) + "s"
            + " 最近距离=" + (int)this.travelBestHoriz
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
         "进近阶段=" + (this.columnStage == null ? "无" : this.columnStage)
            + " 垂直列=(" + String.format("%.1f", this.columnX) + ", " + String.format("%.1f", this.columnZ) + ")"
            + " 列顶=" + (int)this.columnTopY
            + " 列底=" + (int)this.columnFloorY
            + " 列偏移=" + (player == null
               ? "-" : String.format("%.2f", Math.hypot(this.columnX - player.getX(), this.columnZ - player.getZ())))
            + " 对准容差=" + String.format("%.2f", this.flight.columnAlignTolerance(player))
            + " 飞离起点=(" + String.format("%.1f", this.departX) + ", " + String.format("%.1f", this.departZ) + ")"
            + " 飞离目标Y=" + (int)this.departY
            + " 飞离最高Y=" + (int)this.departBestY
            + " 飞离计时=" + (this.departSinceMs == 0L
               ? 0 : (System.currentTimeMillis() - this.departSinceMs)) + "ms"
            + " " + this.flight.verticalDiagnostics(),
         ChatFormatting.AQUA
      );
      this.info(
         "导航=" + this.flight.navState()
            + " " + this.flight.miningDiagnostics()
            + " 已击落飞弹=" + this.bulletsSwatted
            + " 最近飞弹=" + formatVec(this.lastBulletPos)
            + " 潜影贝=忽略"
            + " 进食让路=" + (this.autoEatPauseMs != 0L ? "是" : "否")
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

   /** Whether ShulkerAutoStore is allowed to start a storage session right now. */
   public boolean allowsShulkerStore() {
      if (!this.isRunning()) {
         return true;
      }
      if (this.phase == Phase.LOCATING || this.flight.isMining()) {
         return false;
      }
      return true;
   }

   /** Called by ShulkerAutoStore when a storage session completes (or aborts). */
   public void onShulkerStoreFinished() {
      Minecraft mc = Minecraft.getInstance();
      if (mc != null && mc.player != null) {
         this.baselineElytra = countElytra(mc.player);
      }
      FlyController.resetToolState();
      long now = System.currentTimeMillis();
      this.phaseStartMs = now;
      if (this.columnSinceMs != 0L) {
         this.columnSinceMs = now;
      }
      if (this.departSinceMs != 0L) {
         this.departSinceMs = now;
         this.departProgressMs = now;
      }
      if (this.dropChaseSinceMs != 0L) {
         this.dropChaseSinceMs = now;
         this.dropProgressSinceMs = now;
      }
   }

   /**
    * 自动进食准备接管右键/快捷栏时调用。返回 {@code true} 表示鞘翅流程已经暂停，
    * 整个进食期间 {@link #onClientTick()} 都会让路；返回 {@code false} 表示此刻
    * 正在挖掘/攻击，换手会重置服务端破坏进度，请调用方延后到下一次扫描。
    */
   public boolean suspendForAutoEat() {
      if (!this.isRunning()) {
         return true;
      }
      Minecraft mc = Minecraft.getInstance();
      LocalPlayer player = mc == null ? null : mc.player;
      if (this.flight.isMining()
         || (mc != null && mc.gameMode != null && mc.gameMode.isDestroying())
         || (mc != null && mc.options != null && mc.options.keyAttack.isDown())
         || (player != null && player.isUsingItem())) {
         return false;
      }
      if (mc != null) {
         // 只松开飞行/攻击/挖掘键，绝不碰 keyUse：原版 handleKeybinds() 发现
         // player.isUsingItem() 而 keyUse 没按住就会立刻 releaseUsingItem()，
         // 这正是「鞘翅搜寻时无法长食用食物」的根因。
         this.flight.pauseForAutoEat(mc);
      }
      if (this.autoEatPauseMs == 0L) {
         this.autoEatPauseMs = System.currentTimeMillis();
      }
      return true;
   }

   /**
    * 把「让路」这段时间从所有墙钟计时里平移掉。进食期间 onClientTick() 直接
    * return，若不冻结，恢复后 phaseStartMs / columnSinceMs / dropCloseSinceMs
    * 等会一次性吃掉 2~4 秒预算，触发进近受阻、吸取超时或飞行无进展而误换点。
    */
   private void freezeAutoEatPause(long now) {
      if (this.autoEatPauseMs == 0L) {
         this.autoEatPauseMs = now;
         return;
      }
      long delta = now - this.autoEatPauseMs;
      this.autoEatPauseMs = now;
      // 单帧跨度过大通常是切世界/游戏暂停，别把计时推到未来。
      if (delta <= 0L || delta > 30000L) {
         return;
      }
      this.phaseStartMs += delta;
      this.globalStartMs += delta;
      this.lastInfoMs = shiftTime(this.lastInfoMs, delta);
      this.lastScanMs = shiftTime(this.lastScanMs, delta);
      this.lastAttackMs = shiftTime(this.lastAttackMs, delta);
      this.lastBulletSwatMs = shiftTime(this.lastBulletSwatMs, delta);
      this.travelDeadlineMs = shiftTime(this.travelDeadlineMs, delta);
      this.targetMissingSinceMs = shiftTime(this.targetMissingSinceMs, delta);
      this.dropChaseSinceMs = shiftTime(this.dropChaseSinceMs, delta);
      this.dropProgressSinceMs = shiftTime(this.dropProgressSinceMs, delta);
      this.dropDigUntilMs = shiftTime(this.dropDigUntilMs, delta);
      this.dropCloseSinceMs = shiftTime(this.dropCloseSinceMs, delta);
      this.obstacleSinceMs = shiftTime(this.obstacleSinceMs, delta);
      this.columnSinceMs = shiftTime(this.columnSinceMs, delta);
      this.columnBlockedSinceMs = shiftTime(this.columnBlockedSinceMs, delta);
      this.departSinceMs = shiftTime(this.departSinceMs, delta);
      this.departProgressMs = shiftTime(this.departProgressMs, delta);
   }

   private static long shiftTime(long value, long delta) {
      return value == 0L ? 0L : value + delta;
   }

   /** Registered after {@code FlyManager.tick()} in {@code SeedCracker.onInitialize()}. */
   public void onClientTick() {
      if (!this.isRunning()) {
         this.autoEatPauseMs = 0L;
         return;
      }

      Minecraft mc = Minecraft.getInstance();
      LocalPlayer player = mc.player;
      long now = System.currentTimeMillis();

      // 自动进食（或玩家自己长按右键吃东西）期间彻底让路。
      // 原版 handleKeybinds() 只要发现 player.isUsingItem() 且 keyUse 未按住，
      // 就会立刻 releaseUsingItem()，所以这里连输入键都不能乱碰；同时冻结全部
      // 墙钟计时，否则吃一口的时间会被算成「无进展」而误判超时、换掉目标。
      if (AutoEatManager.INSTANCE.isBusy() || (player != null && player.isUsingItem())) {
         this.freezeAutoEatPause(now);
         if (this.phase == Phase.TRAVELLING) {
            this.travelDeadlineMs = Math.max(this.travelDeadlineMs, now + TRAVEL_STALL_MS);
         }
         this.flight.pauseForAutoEat(mc);
         return;
      }
      if (this.autoEatPauseMs != 0L) {
         // 进食结束：把最后一段暂停也平移掉，再恢复巡航/进近。
         this.freezeAutoEatPause(now);
         this.autoEatPauseMs = 0L;
      }

      ClientLevel level = mc.level;
      if (player == null || level == null || !player.isAlive() || player.isRemoved() || !isEnd(level)) {
         this.abort("玩家不可用 / 已死亡 / 离开末地");
         return;
      }
      // Monotone record of how far out we have already been. Flying back towards
      // (0,0) afterwards must never drag the "already explored" floor back in.
      this.updateOutwardFloor(player);
      if (this.targetElytra > 0 && now - this.globalStartMs > GLOBAL_TIMEOUT_MS) {
         this.abort("单次运行总时限已到（已收集 " + this.collected + " 个）");
         return;
      }
      if (countElytra(player) > this.baselineElytra) {
         this.onElytraGained(mc, player, now);
         return;
      }
      // Yield control while ShulkerAutoStore is placing/opening/storing/breaking the shulker box,
      // and keep baselineElytra synchronized as elytras are moved into the box.
      if (ShulkerAutoStore.INSTANCE.isBusy()) {
         this.baselineElytra = countElytra(player);
         this.phaseStartMs = now;
         // Storing into a shulker box pauses the cruise: do not bill it to the
         // flight budget, otherwise a far target times out during the pause.
         if (this.phase == Phase.TRAVELLING) {
            this.travelDeadlineMs = Math.max(this.travelDeadlineMs, now + TRAVEL_STALL_MS);
         }
         if (this.columnSinceMs != 0L) {
            this.columnSinceMs = now;
         }
         if (this.departSinceMs != 0L) {
            this.departSinceMs = now;
            this.departProgressMs = now;
         }
         if (this.dropChaseSinceMs != 0L) {
            this.dropChaseSinceMs = now;
            this.dropProgressSinceMs = now;
         }
         return;
      }

      // Purely defensive and NEVER consumes the tick. The Shulker itself is
      // ignored; only its projectiles are knocked away. No aiming, no hover,
      // no stopMining and no tool swap are involved, so the vertical shaft
      // keeps its server-side destroy progress while this runs.
      // If there is no bullet in reach we simply fall through to the normal
      // phase (dig the column / attack the frame / chase the dropped elytra).
      this.defendBullets(mc, player, level, now);

      switch (this.phase) {
         case LOCATING -> this.tickLocating(mc, player, now);
         case TRAVELLING -> this.tickTravelling(mc, player, level, now);
         case VERIFYING -> this.tickVerifying(mc, player, level, now);
         case APPROACHING -> this.tickApproaching(mc, player, level, now);
         case TAKING -> this.tickTaking(mc, player, level, now);
         case DEPARTING -> this.tickDeparting(mc, player, level, now);
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
      final Set<Long> excluded = new HashSet<>(this.tried);
      excluded.addAll(this.inwardSkipped);
      this.pendingResult = null;
      this.scanExecutor.execute(() -> {
         this.scanning.set(true);
         boolean failed = false;
         List<CityCandidate> cities = List.of();
         long start = System.currentTimeMillis();
         try {
            VanillaStructureLocator.CityResult result = VanillaStructureLocator.findEndCitiesWithShip(
               seed, fixedOrigin, fixedNeed, excluded
            );
            failed = !result.available();
            cities = result.available() ? new ArrayList<>(result.cities()) : List.of();
            LOGGER.info(
               "[elytra] 异步定位耗时 {} ms，返回 {} 个带船候选（已排除 {}）",
               System.currentTimeMillis() - start, cities.size(), excluded.size()
            );
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

   /** Drain any completed background locate result into {@link #queue}. */
   private int consumeLocateResult(LocateResult result) {
      if (result == null || result.cities() == null) {
         return 0;
      }
      int added = 0;
      for (CityCandidate candidate : result.cities()) {
         if (candidate == null || candidate.pos() == null || !candidate.shipLocated() || candidate.shipPos() == null) {
            continue;
         }
         BlockPos target = candidate.target();
         long cityKey = candidate.pos().asLong();
         long shipKey = target.asLong();
         double radius = Math.hypot(target.getX(), target.getZ());
         // Never accept a coordinate inside the End central island exclusion zone (1024-block radius).
         if (radius <= CENTRAL_ISLAND_EXCLUSION_RADIUS) {
            continue;
         }
         // Outward drift: rings we already left behind are treated as explored.
         // Hysteresis keeps ships only slightly inward still eligible, so the
         // nearest-neighbour router is not starved of candidates. Skipped ships
         // are excluded from later locate calls (but NOT marked visited) so the
         // ring scan looks further out instead of returning the same inner ones.
         if (radius + CENTER_HYSTERESIS < this.minTargetRadius) {
            this.inwardSkipped.add(cityKey);
            this.inwardSkipped.add(shipKey);
            continue;
         }
         if (this.tried.contains(cityKey) || this.tried.contains(shipKey)) {
            continue;
         }
         if (this.queue.size() >= MAX_QUEUE_SIZE) {
            break;
         }
         this.tried.add(cityKey);
         this.tried.add(shipKey);
         this.queue.add(candidate);
         added++;
      }
      return added;
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
      int added = this.consumeLocateResult(result);
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
      this.info("定位完成，新增 " + added + " 个带船候选，剩余 " + this.queue.size(), ChatFormatting.GREEN);
      this.nextCandidate(player, now);
   }

   /** Candidate exhaustion: locate the next batch of untried End Ships around the player (or pushed further out). */
   private boolean pushOriginAndRelocate(LocalPlayer player, long now) {
      if (this.rescanRound >= MAX_RESCAN_ROUNDS) {
         return false;
      }
      this.rescanRound++;
      this.lastOrigin = this.searchOriginFor(player, this.rescanRound);
      VanillaStructureLocator.clearCityCache();
      this.current = null;
      this.clearTarget();
      this.sweepTarget = null;
      this.verifyPoint = null;
      this.verifyScans = 0;
      this.verifyOffsetIndex = 0;
      this.structureHint = null;
      this.lastScan = null;
      this.flight.hover(Minecraft.getInstance(), player, true);
      this.phaseStartMs = now;
      this.pendingResult = null;
      this.phase = Phase.LOCATING;
      this.info(
         "当前批次候选已飞完，沿远离 (0,0) 方向外推至 (" + this.lastOrigin.getX() + ", " + this.lastOrigin.getZ()
            + ") 重新定位（第 " + this.rescanRound + " 轮，半径 " + this.outwardRadius
            + " 格，内侧门槛 " + this.minTargetRadius + " 格）…",
         ChatFormatting.YELLOW
      );
      this.submitLocating(this.lastOrigin, Math.min(MAX_QUEUE_SIZE, this.candidateCount + this.rescanRound * 4));
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
    * Raises the "already explored" radius floor monotonically. {@link #maxRadiusSeen}
    * records the furthest point reached in this run and {@link #outwardRadius} the
    * furthest search origin, so flying back towards (0,0) can never lower the
    * floor again. The hysteresis band keeps a ship only slightly inward eligible.
    */
   private void updateOutwardFloor(LocalPlayer player) {
      if (player != null) {
         this.maxRadiusSeen = Math.max(this.maxRadiusSeen, (int)Math.hypot(player.getX(), player.getZ()));
      }
      int reached = Math.max(this.outwardBaseRadius, Math.max(this.maxRadiusSeen, this.outwardRadius));
      this.minTargetRadius = Math.max(this.minTargetRadius, reached - CENTER_HYSTERESIS);
   }

   /**
    * Computes the search centre for locating untried End Ships.
    *
    * <p>Because {@link VanillaStructureLocator#findEndCitiesWithShip} now
    * receives {@link #tried} directly and skips all already-visited cities while
    * expanding rings, searching around the player's current position finds the
    * closest untried ships without flying thousands of blocks away. If a round
    * around the player's position ever yields zero results, subsequent rounds
    * step outward along {@link #outwardAngle}.</p>
    *
    * <p>Every refill/rescan also nudges the centre a little further out along
    * that bearing, so the ring expansion itself leans away from (0,0) instead
    * of drifting back into the already-looted centre.</p>
    */
   private BlockPos searchOriginFor(LocalPlayer player, int round) {
      if (player == null) {
         this.updateOutwardFloor(null);
         return this.lastOrigin != null ? this.lastOrigin : new BlockPos(OUTWARD_MIN_RADIUS, DEFAULT_SHIP_Y, 0);
      }
      double px = player.getX();
      double pz = player.getZ();
      double playerRadius = Math.hypot(px, pz);
      if (playerRadius > 256.0) {
         this.outwardAngle = Math.atan2(pz, px);
      }
      if (round <= 1 && playerRadius >= OUTWARD_MIN_RADIUS) {
         this.outwardRadius = (int)playerRadius;
         double lead = round >= 1 ? OUTWARD_SEARCH_LEAD : 0.0;
         BlockPos origin = lead > 0.0
            ? new BlockPos(
               (int)(px + Math.cos(this.outwardAngle) * lead), player.getBlockY(),
               (int)(pz + Math.sin(this.outwardAngle) * lead)
            )
            : player.blockPosition();
         this.updateOutwardFloor(player);
         return origin;
      }
      int baseRadius = Math.max((int)playerRadius, OUTWARD_MIN_RADIUS);
      int extraPush = Math.max(0, round - 1) * ORIGIN_PUSH;
      int radius = Math.max(baseRadius + extraPush, this.outwardRadius + (round > 1 ? ORIGIN_PUSH / 2 : 0));
      this.outwardRadius = radius;
      double angle = this.outwardAngle + (round > 1 ? Math.sin(round * 0.8) * OUTWARD_ANGLE_SWING : 0.0);
      BlockPos origin = new BlockPos(
         (int)(Math.cos(angle) * radius), player.getBlockY(), (int)(Math.sin(angle) * radius)
      );
      this.updateOutwardFloor(player);
      return origin;
   }

   /**
    * Nearest-neighbour routing with a bounded outward-from-(0,0) bias.
    *
    * <p>The plain farthest-radius rule was removed by the routing optimisation
    * because it caused 4000+ block zig-zag flights across opposite quadrants.
    * We keep nearest-neighbour as the dominant term and only let ships within
    * {@link #OUTWARD_DETOUR_MAX} blocks of the closest one compete. Inside that
    * bounded set a ship continuing outwards is discounted and one heading back
    * inwards is surcharged, by at most {@link #OUTWARD_BIAS_CAP}. Combined with
    * the monotone floor in {@link #consumeLocateResult} and the outward-shifted
    * search centre this restores the "keep moving away from 0,0" tendency
    * without bringing the zig-zag back.</p>
    */
   private CityCandidate takeNextCandidate(LocalPlayer player) {
      List<CityCandidate> all = new ArrayList<>(this.queue);
      double refX = player != null ? player.getX() : (this.lastOrigin != null ? this.lastOrigin.getX() : 0.0);
      double refZ = player != null ? player.getZ() : (this.lastOrigin != null ? this.lastOrigin.getZ() : 0.0);
      double refRadius = Math.hypot(refX, refZ);
      int size = all.size();
      double[] dist = new double[size];
      double[] gain = new double[size];
      double nearest = Double.MAX_VALUE;
      for (int i = 0; i < size; i++) {
         BlockPos target = all.get(i).target();
         double tx = target.getX() + 0.5;
         double tz = target.getZ() + 0.5;
         dist[i] = Math.hypot(tx - refX, tz - refZ);
         gain[i] = Math.hypot(tx, tz) - refRadius;
         nearest = Math.min(nearest, dist[i]);
      }
      double limit = nearest + OUTWARD_DETOUR_MAX;
      int bestIndex = 0;
      double bestScore = Double.MAX_VALUE;
      double bestGain = 0.0;
      for (int i = 0; i < size; i++) {
         if (dist[i] > limit) {
            continue;
         }
         double bias = Math.max(-OUTWARD_BIAS_CAP,
            Math.min(OUTWARD_BIAS_CAP, gain[i] * OUTWARD_BIAS_FACTOR));
         double score = dist[i] - bias;
         if (score < bestScore) {
            bestScore = score;
            bestGain = gain[i];
            bestIndex = i;
         }
      }
      this.lastRadialGain = bestGain;
      CityCandidate chosen = all.remove(bestIndex);
      this.queue.clear();
      this.queue.addAll(all);
      return chosen;
   }

   /**
    * Grace before the first progress. Slightly longer for distant ships
    * (climbing to cruise altitude and waiting for chunk generation both take
    * wall-clock), but still bounded - the rolling no-progress deadline takes
    * over as soon as the player starts moving.
    */
   private static long travelStartGraceMs(double distance) {
      long extra = (long)(distance / TRAVEL_GRACE_PER_BLOCKS * 1000.0);
      return Math.min(TRAVEL_MAX_START_GRACE_MS, TRAVEL_START_GRACE_MS + extra);
   }

   private void nextCandidate(LocalPlayer player, long now) {
      // Consume any completed background prefetch before checking queue size.
      LocateResult prefetched = this.pendingResult;
      if (prefetched != null && prefetched.generation() == this.locateGeneration) {
         this.pendingResult = null;
         int added = this.consumeLocateResult(prefetched);
         if (added > 0) {
            this.info("后台预加载已补充 " + added + " 个带船候选，当前队列 " + this.queue.size(), ChatFormatting.GREEN);
         }
      }
      if (this.queue.isEmpty()) {
         if (!this.pushOriginAndRelocate(player, now)) {
            this.abort("候选已全部耗尽");
         }
         return;
      }
      this.current = this.takeNextCandidate(player);
      // Proactively prefetch the next batch in the background when the queue runs low
      // so continuous hunting never stalls after the initial batch of candidates.
      if (this.queue.size() <= REFILL_QUEUE_THRESHOLD && !this.scanning.get() && this.pendingResult == null) {
         BlockPos refillOrigin = this.searchOriginFor(player, 1);
         this.lastOrigin = refillOrigin;
         this.submitLocating(refillOrigin, this.candidateCount);
      }
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
      double initialHoriz = player == null ? 0.0
         : Math.hypot(ship.getX() + 0.5 - player.getX(), ship.getZ() + 0.5 - player.getZ());
      this.travelBestHoriz = initialHoriz;
      this.travelDeadlineMs = now + travelStartGraceMs(initialHoriz);
      int shipY = ship.getY() > 0 ? ship.getY() : DEFAULT_SHIP_Y;
      this.flight.setMinCruiseY(Math.max(FlyController.CRUISE_Y, shipY + 28));
      // Prepare the low-altitude approach point directly above the ship cabin.
      this.verifyPoint = this.buildVerifyPoint(Minecraft.getInstance().level, ship);
      LOGGER.info(
         "[elytra] 换点 #{} 城={} 船={} shipKnown={} shipLocated={} 剩余 {}",
         this.attempts, city, this.current.shipPos(), this.current.shipKnown(),
         this.current.shipLocated(), this.queue.size()
      );
      this.info(
         "飞往船坐标 #" + this.attempts + " (" + ship.getX() + ", " + ship.getY() + ", " + ship.getZ() + ")"
            + "，距离 " + (int)initialHoriz + " 格（进度制超时，持续靠近不会换点）"
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
      // Opportunistically drain any finished background prefetch.
      LocateResult prefetched = this.pendingResult;
      if (prefetched != null && prefetched.generation() == this.locateGeneration) {
         this.pendingResult = null;
         int added = this.consumeLocateResult(prefetched);
         if (added > 0) {
            LOGGER.info("[elytra] 巡航中后台补充 {} 个候选，当前队列 {}", added, this.queue.size());
         }
      }
      // Fly to the ship, not to the city's /locate position.
      BlockPos pos = this.current.target();
      Vec3 target = new Vec3(pos.getX() + 0.5, this.flight.cruiseY(), pos.getZ() + 0.5);
      double horiz = Math.hypot(target.x - player.getX(), target.z - player.getZ());

      // If starting low or inside an enclosed structure (e.g. inside the previous End Ship cabin),
      // climb steeply to cruiseY first and allow mining through any blocking ceiling/tree.
      boolean climbingToCruise = player.getY() < this.flight.cruiseY() - 14.0 && horiz > 24.0;

      if (this.travelDeadlineMs == 0L) {
         this.travelBestHoriz = horiz;
         this.travelDeadlineMs = now + travelStartGraceMs(horiz);
      }
      boolean closingIn = horiz + TRAVEL_PROGRESS_STEP < this.travelBestHoriz;
      if (closingIn) {
         this.travelBestHoriz = horiz;
      }
      if (closingIn || climbingToCruise || this.flight.isMining()) {
         this.travelDeadlineMs = now + TRAVEL_STALL_MS;
      }
      if (now > this.travelDeadlineMs) {
         this.nextCandidateWithReason(player, now, "飞行无进展（距目标仍有 " + (int)horiz + " 格）");
         return;
      }

      // Early entity scan when approaching the ship horizontally so we lock on immediately if already synced.
      if (horiz < 64.0 && now - this.lastScanMs >= 500L) {
         this.lastScanMs = now;
         ScanResult earlyScan = this.scan(level, player, pos);
         this.lastScan = earlyScan;
         if (earlyScan.structureHint != null) {
            this.structureHint = earlyScan.structureHint;
         }
         if (this.tryLockTargetFromScan(earlyScan, player, now)) {
            return;
         }
      }

      if (horiz < ARRIVE_XZ && Math.abs(player.getY() - this.flight.cruiseY()) < 28.0) {
         this.flight.hover(mc, player, true);
         this.phaseStartMs = now;
         this.lastScanMs = 0L;
         this.sweepCount = 0;
         this.sweepTarget = null;
         this.lastScan = null;
         this.verifyPoint = this.buildVerifyPoint(level, pos);
         this.phase = Phase.VERIFYING;
         this.info("已抵达船坐标上空，正在下降到船舱顶部并校验区块 / 结构 / 展示框…", ChatFormatting.AQUA);
         return;
      }

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
            + "，导航=" + state
            + "，无进展剩余 " + Math.max(0L, (this.travelDeadlineMs - now) / 1000L) + " 秒",
         now
      );
   }

   private boolean tryLockTargetFromScan(ScanResult scan, LocalPlayer player, long now) {
      if (scan.elytraFrame != null) {
         this.clearTarget();
         this.targetFrame = scan.elytraFrame;
         this.targetFramePos = scan.elytraFrame.blockPosition();
         this.beginColumnApproach(player, scan.elytraFrame, now);
         this.phase = Phase.APPROACHING;
         this.info(
            "已定位展示框坐标 " + format(this.targetFramePos) + "，对准方块中心垂直列并向下挖掘…",
            ChatFormatting.GREEN
         );
         return true;
      }
      if (scan.dropped != null) {
         this.clearTarget();
         this.droppedItem = scan.dropped;
         this.targetFramePos = scan.dropped.blockPosition();
         this.pickupOnly = true;
         this.beginColumnApproach(player, scan.dropped, now);
         this.phase = Phase.APPROACHING;
         this.info(
            "已定位掉落鞘翅坐标 " + format(this.targetFramePos) + "，对准方块中心垂直列并向下挖掘…",
            ChatFormatting.GREEN
         );
         return true;
      }
      return false;
   }

   /**
    * Step 1 of the approach: snap the X/Z column to the <b>centre of the air
    * block</b> containing the item frame ({@code block.getX() + 0.5, block.getZ() + 0.5}),
    * NOT the entity's exact wall-flush coordinate ({@code .0625 / .9375}).
    *
    * <p>An {@link ItemFrame} hangs 1/16th of a block off the solid cabin wall.
    * Centering the 0.68-wide player at {@code target.position().x/z} made the
    * player's bounding box overlap 0.28 blocks into the wall supporting the
    * frame, forcing a 2-column shaft and breaking the frame's support block.
    * At {@code (bx + 0.5, bz + 0.5)}, the player's {@code [0.16, 0.84]} footprint
    * fits 100% inside a single 1x1 column directly in front of the frame.</p>
    */
   private void beginColumnApproach(LocalPlayer player, Entity target, long now) {
      BlockPos block = target.blockPosition();
      this.columnX = block.getX() + 0.5;
      this.columnZ = block.getZ() + 0.5;
      ClientLevel level = Minecraft.getInstance().level;
      int topBlockY = block.getY() + DESCENT_CLEARANCE;
      if (level != null) {
         for (int y = block.getY() + 16; y >= block.getY() + 2; y--) {
            if (!FlyController.isPassableBlock(level, new BlockPos(block.getX(), y, block.getZ()))) {
               topBlockY = Math.max(block.getY() + 4, y + 2);
               break;
            }
         }
         while (topBlockY < block.getY() + 22
            && !FlyController.isPassableColumn(level, block.getX(), topBlockY, block.getZ())) {
            topBlockY++;
         }
      }
      this.columnTopY = topBlockY + 0.08;
      this.columnFloorY = block.getY() - DESCENT_FLOOR_OFFSET;
      this.columnBestY = player == null ? this.columnTopY : player.getY();
      // NEVER skip the overhead leg. Skipping it (the old `horiz <= 0.85`
      // shortcut) is what let the shaft be started from the side of the
      // cabin: travelColumn no longer re-centres X/Z before descending.
      this.columnStage = ColumnStage.ALIGN;
      this.columnSinceMs = now;
      this.columnBlockedSinceMs = 0L;
      this.phaseStartMs = now;
   }

   // ------------------------------------------------------------------ VERIFYING

   private void tickVerifying(Minecraft mc, LocalPlayer player, ClientLevel level, long now) {
      if (this.current == null) {
         this.nextCandidate(player, now);
         return;
      }
      BlockPos ship = this.current.target();

      // Continuously scan while descending or sweeping so we lock onto the frame the moment it syncs!
      if ((this.verifyPoint != null || this.sweepTarget != null) && now - this.lastScanMs >= 400L) {
         this.lastScanMs = now;
         ScanResult midScan = this.scan(level, player, ship);
         this.lastScan = midScan;
         if (midScan.structureHint != null) {
            this.structureHint = midScan.structureHint;
         }
         if (this.tryLockTargetFromScan(midScan, player, now)) {
            return;
         }
      }

      // Phase 1: descend directly above / beside the ship cabin.
      if (this.verifyPoint != null) {
         double d = player.position().distanceTo(this.verifyPoint);
         if (d > 3.5 && now - this.phaseStartMs < VERIFY_APPROACH_TIMEOUT_MS) {
            FlyController.NavState state = this.flight.navigate(
               mc, level, player, this.verifyPoint, d < 12.0, true
            );
            if (state == FlyController.NavState.BLOCKED) {
               this.verifyOffsetIndex++;
               this.verifyPoint = this.buildVerifyPoint(level, ship);
               this.phaseStartMs = now;
               this.info("下降路径被阻挡，换一个角度接近船体…", ChatFormatting.YELLOW);
            } else if (obstacleWork(state)) {
               this.phaseStartMs = now;
            }
            this.throttledInfo("正在下降到船舱顶部以同步展示框实体（还差 " + (int)d + " 格）…", now);
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
            FlyController.NavState state = this.flight.navigate(mc, level, player, this.sweepTarget, d < 12.0, true);
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

      if (this.tryLockTargetFromScan(scan, player, now)) {
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

   /**
    * Low-altitude point directly above the ship cabin.
    *
    * <p>When {@code current.shipLocated()} is true, {@code ship.getY()} comes
    * directly from the generated {@code end_city/ship} template piece and MUST
    * NOT be overridden by a lower End City tower Shulker (which used to pull
    * {@code verifyY} 60-80 blocks down into the city towers).</p>
    */
   private int verifyY(BlockPos ship) {
      int offline = ship.getY() > 0 ? ship.getY() : DEFAULT_SHIP_Y;
      if (this.current != null && this.current.shipLocated()) {
         return offline + (int)SHIP_APPROACH_LIFT;
      }
      int hint = this.structureHint == null ? offline : this.structureHint.getY();
      return hint + (int)SHIP_APPROACH_LIFT;
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
         if (level == null) {
            this.verifyOffsetIndex = idx;
            return new Vec3(bx + 0.5, vy + 0.08, bz + 0.5);
         }
         // Search a small vertical window at (bx, bz) so a roof/mast block at vy
         // is stepped over rather than rejecting the close offset.
         for (int dy = 0; dy <= 10; dy += 2) {
            if (FlyController.isPassableColumn(level, bx, vy + dy, bz)) {
               this.verifyOffsetIndex = idx;
               return new Vec3(bx + 0.5, vy + dy + 0.08, bz + 0.5);
            }
         }
      }
      double[] offset = VERIFY_OFFSETS[Math.floorMod(this.verifyOffsetIndex, VERIFY_OFFSETS.length)];
      return new Vec3(cx + offset[0] + 0.5, vy + 0.08, cz + offset[1] + 0.5);
   }

   private static Vec3 spiralPoint(BlockPos center, int index, int y) {
      double[] offset = SWEEP_OFFSETS[(index - 1) % SWEEP_OFFSETS.length];
      return new Vec3(center.getX() + offset[0], y, center.getZ() + offset[1]);
   }

   /**
    * Scans around the SHIP position. Entity hits are the strongest signal: they
    * prove the structure is real and give an exact on-site coordinate.
    *
    * <p>Shulkers on lower End City towers (dozens of blocks below the ship) are
    * no longer allowed to overwrite {@code structureHint} when the ship's own
    * position is already known.</p>
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
            if (result.structureHint == null
               && (center.getY() <= 0 || Math.abs(entity.getY() - center.getY()) <= 16.0)) {
               result.structureHint = entity.blockPosition();
            }
         }
         if (entity instanceof ItemFrame) {
            result.frames++;
            result.cityPresent = true;
            result.structureHint = entity.blockPosition();
            ItemStack stack = frameItem(entity);
            if (stack != null && stack.getItem() == Items.ELYTRA && result.elytraFrame == null) {
               result.elytraFrame = entity;
            }
         } else if (entity instanceof ItemEntity item && item.getItem().getItem() == Items.ELYTRA && result.dropped == null) {
            result.dropped = entity;
            result.cityPresent = true;
            result.structureHint = entity.blockPosition();
         }
      }

      if (result.elytraFrame == null && !result.cityPresent) {
         int baseY = center.getY() > 0 ? center.getY() : (int)player.getY();
         int minY = Math.max(40, baseY - 24);
         int maxY = Math.min(220, baseY + 24);
         outer:
         for (int x = center.getX() - 32; x <= center.getX() + 32; x += 4) {
            for (int z = center.getZ() - 32; z <= center.getZ() + 32; z += 4) {
               if (level.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null) {
                  continue;
               }
               for (int y = minY; y <= maxY; y += 4) {
                  BlockPos at = new BlockPos(x, y, z);
                  if (isEndCityBlock(level.getBlockState(at).getBlock())) {
                     result.cityPresent = true;
                     if (result.structureHint == null) {
                        result.structureHint = at;
                     }
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

   // ------------------------------------------------------------------ projectile defence

   /**
    * Knock away ShulkerBullets while completely IGNORING the Shulker itself.
    *
    * <p>This deliberately does NOT consume the tick: when no projectile is in
    * melee reach the state machine simply carries on digging the column,
    * attacking the item frame or chasing the dropped elytra. The old guard
    * clearance was a hard gate that called hover() -&gt; stopMining() and
    * swapped to a weapon whenever the shaft opened a line of sight, which
    * aborted the server-side block break and produced an endless
    * mine / fight / mine / fight ping-pong.</p>
    *
    * <p>26.3 specifics:
    * <ul>
    *   <li>ServerGamePacketListenerImpl.handleAttack only checks the entity id,
    *       isWithinAttackRange(mainHand, entityBox, 3.0) and a few item flags.
    *       It never checks the crosshair, so NO aiming is needed: the player
    *       keeps looking at the block being mined.</li>
    *   <li>We send the vanilla packet directly instead of calling
    *       MultiPlayerGameMode.attack(), because that method resets the client
    *       attack-strength ticker. Doing that on every projectile would starve
    *       the item-frame attack (which needs a full attack strength).</li>
    *   <li>No equipForAttack / hover / stopMining / hitResult / phase-timer
    *       changes here, so an active destroy session keeps both its tool and
    *       its server-side progress.</li>
    * </ul></p>
    */
   private void defendBullets(Minecraft mc, LocalPlayer player, ClientLevel level, long now) {
      if (mc == null || player == null || level == null || mc.getConnection() == null
         || !player.isAlive() || player.isRemoved()
         || (mc.gui != null && mc.gui.screen() != null)) {
         return;
      }
      Vec3 eye = player.getEyePosition();
      Entity nearest = null;
      double nearestDistSq = Double.MAX_VALUE;
      for (Entity entity : level.entitiesForRendering()) {
         if (entity == null || entity.isRemoved()
            || entity.getType() != EntityTypes.SHULKER_BULLET) {
            continue;
         }
         double distSq = eye.distanceToSqr(entity.getBoundingBox().getCenter());
         if (distSq <= BULLET_SWAT_RANGE_SQ && distSq < nearestDistSq) {
            nearest = entity;
            nearestDistSq = distSq;
         }
      }
      if (nearest == null || now - this.lastBulletSwatMs < BULLET_SWAT_COOLDOWN_MS) {
         return;
      }
      try {
         // Exactly the packet Minecraft.startAttack() sends for an entity hit;
         // ShulkerBullet.hurtServer() destroys the projectile on any damage.
         mc.getConnection().send(new ServerboundAttackPacket(nearest.getId()));
         player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
         this.lastBulletSwatMs = now;
         this.lastBulletPos = nearest.position();
         this.bulletsSwatted++;
      } catch (Throwable ignored) {
         // A failed defensive packet must never disturb the hunt.
      }
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
      Entity target = this.pickupOnly ? this.droppedItem : this.targetFrame;
      if (target == null || target.isRemoved() || !target.isAlive()) {
         this.throttledInfo("目标实体正在重新同步…", now);
         return;
      }
      // Only take the frame from the vertical shaft. An unobstructed shot from
      // the SIDE of the cabin must NOT short-circuit the strict overhead
      // approach - that is exactly the "approaching from another direction"
      // regression this gate prevents.
      if (!this.pickupOnly && status == TargetStatus.FRAME
         && this.columnStage == ColumnStage.DESCEND
         && canAttackEntity(level, player, target)) {
         this.enterTaking(mc, player, target, now, "已沿垂直列进入展示框有效攻击距离，开始取物");
         return;
      }

      // FRAME (or a frame-anchored pickup target): strictly vertical column approach.
      if (this.columnStage == null) {
         this.beginColumnApproach(player, target, now);
      }
      if (now - this.columnSinceMs > VERTICAL_APPROACH_TIMEOUT_MS) {
         this.nextCandidateWithReason(player, now, "垂直进近超时");
         return;
      }

      // Step 2a: HARD GATE - fly to the point directly above the located
      // coordinates and centre the 1x1 column before ANY downward digging.
      // The anti-stall rewrite of travelColumn deliberately stopped re-centring
      // X/Z, so this gate is the only thing that guarantees the descent starts
      // from directly above the display frame.
      if (this.columnStage == ColumnStage.ALIGN) {
         double horiz = Math.hypot(this.columnX - player.getX(), this.columnZ - player.getZ());
         // Aim a little ABOVE the shaft top: at a raised fly speed the vertical
         // servo can only hold within one impulse-scaled band, and starting the
         // descent from inside the roof must be avoided.
         double holdY = this.columnTopY + 2.0;
         Vec3 overhead = new Vec3(this.columnX, holdY, this.columnZ);
         boolean coarse = horiz > COLUMN_COARSE_XZ
            || player.getY() < this.columnTopY - 8.0
            || player.getY() > this.columnTopY + 12.0;
         FlyController.ColumnState align;
         if (coarse) {
            FlyController.NavState state = this.flight.navigate(
               mc, level, player, overhead, horiz < 10.0, true
            );
            if (state == FlyController.NavState.BLOCKED) {
               this.flight.lift();
            }
            align = FlyController.ColumnState.MOVING;
         } else {
            align = this.flight.alignOverhead(
               mc, level, player, this.columnX, this.columnZ, holdY, true
            );
         }
         boolean aligned = align == FlyController.ColumnState.ARRIVED;
         // At a raised fly speed one keypress already coasts several blocks, so
         // an exact centre may be physically unreachable. Accept a slightly
         // wider column after a grace period instead of stalling forever.
         if (!aligned && !coarse && align != FlyController.ColumnState.BLOCKED
            && now - this.columnSinceMs > COLUMN_ALIGN_GRACE_MS
            && horiz <= COLUMN_ALIGN_FALLBACK_XZ
            && player.getY() >= this.columnTopY - 2.0) {
            aligned = true;
            this.info("高速飞行下已达到对准精度上限，仍从展示框正上方开始下挖…", ChatFormatting.YELLOW);
         }
         if (!aligned) {
            if (align == FlyController.ColumnState.BLOCKED) {
               if (this.columnBlockedSinceMs == 0L) {
                  this.columnBlockedSinceMs = now;
               }
               if (now - this.columnBlockedSinceMs > COLUMN_BLOCKED_TIMEOUT_MS) {
                  this.nextCandidateWithReason(player, now, "无法飞到展示框正上方（被不可挖掘方块阻断）");
                  return;
               }
            } else {
               this.columnBlockedSinceMs = 0L;
            }
            this.throttledInfo(
               "正在飞到展示框正上方并对准垂直列（水平还差 " + String.format("%.2f", horiz) + " 格，高度还差 "
                  + (int)Math.max(0.0, this.columnTopY - player.getY()) + " 格）…", now
            );
            return;
         }
         this.flight.hover(mc, player, true);
         this.columnStage = ColumnStage.DESCEND;
         this.columnSinceMs = now;
         this.columnBlockedSinceMs = 0L;
         this.columnBestY = player.getY();
         this.phaseStartMs = now;
         this.info("已对准展示框正上方 1x1 垂直列，开始向下挖掘进舱…", ChatFormatting.AQUA);
         return;
      }

      // Step 2b: dig straight down inside the located 1x1 block-centered column.
      double columnHoriz = Math.hypot(this.columnX - player.getX(), this.columnZ - player.getZ());
      if (columnHoriz > COLUMN_DESCENT_MAX_XZ) {
         // Hold the current altitude and re-centre instead of continuing to
         // dig a shaft that has wandered away from the frame.
         this.flight.alignOverhead(mc, level, player, this.columnX, this.columnZ, player.getY(), true);
         this.throttledInfo(
            "下降时偏离展示框垂直列（" + String.format("%.2f", columnHoriz) + " 格），正在重新对准正上方…", now
         );
         return;
      }
      if (player.getY() < this.columnBestY - 0.8) {
         this.columnBestY = player.getY();
         this.columnSinceMs = now; // real downward progress, not a stall
      }
      FlyController.ColumnState column = this.flight.travelColumn(
         mc, level, player, this.columnX, this.columnZ, this.columnFloorY, true
      );
      if (column == FlyController.ColumnState.MINING) {
         this.columnSinceMs = now; // breaking blocks is progress, not a stall
         this.columnBlockedSinceMs = 0L;
         this.throttledInfo("正沿展示框正上方向下挖掘通道…", now);
         return;
      }
      if (column == FlyController.ColumnState.BLOCKED) {
         if (this.columnBlockedSinceMs == 0L) {
            this.columnBlockedSinceMs = now;
         }
         if (now - this.columnBlockedSinceMs > COLUMN_BLOCKED_TIMEOUT_MS) {
            this.nextCandidateWithReason(player, now, "垂直通道被不可挖掘方块阻断");
            return;
         }
         this.throttledInfo("垂直通道暂时受阻，等待方块可挖…", now);
         return;
      }
      this.columnBlockedSinceMs = 0L;
      if (column == FlyController.ColumnState.ARRIVED
         || player.getY() <= this.columnFloorY + 0.45) {
         this.enterTaking(mc, player, target, now, "已沿垂直列挖到展示框位置，开始取物");
         return;
      }
      this.throttledInfo(
         "正沿展示框垂直列下降（还差 " + (int)Math.max(0.0, player.getY() - this.columnFloorY) + " 格）…", now
      );
   }

   /** Common TAKING transition for the attack-range hit and the shaft bottom. */
   private void enterTaking(Minecraft mc, LocalPlayer player, Entity target, long now, String message) {
      this.flight.hover(mc, player, false);
      if (!this.pickupOnly) {
         FlyController.aimAt(player, target);
      }
      this.obstacleSinceMs = 0L;
      this.phaseStartMs = now;
      this.attackAttempts = 0;
      this.lastAttackMs = 0L;
      this.itemPopped = false;
      this.resetDropChase();
      this.phase = Phase.TAKING;
      this.info(message, ChatFormatting.AQUA);
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
         // Strictly vertical: never fall back to 3D A* here. That fallback used
         // to fly / dig towards the frame from the side of the cabin, which is
         // exactly the behaviour the user rejected.
         if (this.columnStage == null) {
            this.beginColumnApproach(player, frame, now);
            this.columnStage = ColumnStage.DESCEND;
         }
         double horiz = Math.hypot(this.columnX - player.getX(), this.columnZ - player.getZ());
         if (horiz > COLUMN_DESCENT_MAX_XZ) {
            this.flight.alignOverhead(mc, level, player, this.columnX, this.columnZ, player.getY(), true);
            this.throttledInfo("取物时偏离展示框垂直列，正在重新对准正上方…", now);
            return;
         }
         FlyController.ColumnState column = this.flight.travelColumn(
            mc, level, player, this.columnX, this.columnZ, this.columnFloorY, true
         );
         if (column == FlyController.ColumnState.MINING) {
            this.phaseStartMs = now;
         }
         this.throttledInfo(
            column == FlyController.ColumnState.MINING
               ? "展示框仍被方块遮挡，继续沿垂直列向下挖掘…"
               : "正在沿垂直列贴近展示框至有效攻击距离…",
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

      // Use block-centered vertical shaft or 3D A* mining whenever a block is between the player and the drop.
      if ((!directClear && distance > 0.9) || (this.dropDigUntilMs != 0L && now < this.dropDigUntilMs)) {
         BlockPos dropBlock = drop.blockPosition();
         double dropColX = dropBlock.getX() + 0.5;
         double dropColZ = dropBlock.getZ() + 0.5;
         FlyController.ColumnState column = this.flight.travelColumn(
            mc, level, player, dropColX, dropColZ, dropBlock.getY(), true
         );
         this.dropIssue = "垂直挖路中（" + column + "，最近 "
            + String.format("%.1f", this.dropBestDistance) + " 格）";
         if (column == FlyController.ColumnState.MOVING || column == FlyController.ColumnState.MINING) {
            this.throttledInfo("正沿掉落鞘翅所在方块中心列向下挖掘…", now);
            return;
         }
         if (!directClear) {
            FlyController.NavState nav = this.flight.navigate(mc, level, player, drop.position(), true, true);
            if (nav == FlyController.NavState.MINING || nav == FlyController.NavState.DETOUR) {
               return;
            }
         }
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
         // If the inventory was filled by mined blocks while digging down to the cabin,
         // store into a shulker box or drop 1 stack of mined junk blocks so the elytra can be picked up.
         if (player.getInventory().getFreeSlot() < 0) {
            if (ShulkerAutoStore.INSTANCE.canStoreInventory(player) && ShulkerAutoStore.INSTANCE.tryTriggerImmediate()) {
               return;
            }
            if (ShulkerAutoStore.INSTANCE.dropOneJunkSlotForPickup(mc, player)) {
               this.dropCloseSinceMs = now;
               return;
            }
         }
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
      // Renew globalStartMs on every successful pickup so multi-ship runs never time out after ~12 ships.
      this.globalStartMs = now;
      this.info(
         "已取得第 " + this.collected + " 个鞘翅（背包共 " + total + " 个，本个耗时 " + seconds
            + " 秒，已尝试 " + this.attempts + " 个候选）",
         ChatFormatting.GREEN
      );
      LOGGER.info(
         "[elytra] collected #{} in {} s after {} candidates",
         this.collected, seconds, this.attempts
      );

      if (this.targetElytra > 0 && this.collected >= this.targetElytra) {
         this.beginDeparture(player, now, "已达目标数量 " + this.targetElytra + " 个");
         return;
      }
      if (player.getInventory().getFreeSlot() < 0) {
         if (ShulkerAutoStore.INSTANCE.canStoreInventory(player)) {
            this.info("背包已满，正在将鞘翅存入潜影盒后继续收集…", ChatFormatting.GOLD);
            ShulkerAutoStore.INSTANCE.tryTriggerImmediate();
            this.beginDeparture(player, now, null);
            return;
         }
         this.beginDeparture(player, now, this.fullInventoryReason(mc, player));
         return;
      }
      this.beginDeparture(player, now, null);
   }

   /** 背包满且无法继续收集时，给出自动存盒的真实受阻原因，而不是笼统的「无可用潜影盒」。 */
   private String fullInventoryReason(Minecraft mc, LocalPlayer player) {
      String why;
      try {
         why = ShulkerAutoStore.INSTANCE.diagnose(mc, player);
      } catch (Throwable t) {
         why = "诊断失败：" + t.getClass().getSimpleName();
      }
      if (why == null) {
         why = "原因未知";
      }
      return "背包已满，暂时无法存入潜影盒（" + why + "），无法继续拾取";
   }

   /**
    * Step 3: after a successful pickup, climb straight up out of the ship.
    *
    * <p>Uses {@link FlyController#climbOut} instead of {@code travelColumn}:
    * Tweakeroo's fly-speed tweak makes one tick move several blocks, so the old
    * fixed 0.42-block ARRIVED window and the 1x1 column alignment step could
    * never complete, and the fixed-thrust controller ended up alternating
    * jump / sneak (crouch / stand) forever at a single altitude.</p>
    */
   private void beginDeparture(LocalPlayer player, long now, String finishReason) {
      // climbOut climbs from the CURRENT position - no block-centre alignment
      // is needed because the shaft was already dug on the way down.
      this.departX = player.getX();
      this.departZ = player.getZ();
      this.departY = Math.max(player.getY() + DEPART_CLEARANCE, this.flight.cruiseY());
      this.departSinceMs = now;
      this.departBestY = player.getY();
      this.departProgressMs = now;
      this.departFinishReason = finishReason;
      this.phaseStartMs = now;
      this.phase = Phase.DEPARTING;
      this.info("已拾取鞘翅，正沿原 1x1 垂直通道向上飞离船体…", ChatFormatting.AQUA);
   }

   private void tickDeparting(Minecraft mc, LocalPlayer player, ClientLevel level, long now) {
      // While climbing out through the End Ship cabin/roof, immediately trigger ShulkerAutoStore
      // as soon as a valid support block on the ship is within reach.
      if (this.departFinishReason == null
         && player.getInventory().getFreeSlot() < 0
         && ShulkerAutoStore.INSTANCE.canStoreInventory(player)) {
         if (ShulkerAutoStore.INSTANCE.tryTriggerImmediate()) {
            this.flight.hover(mc, player, true);
            this.departProgressMs = now;
            return;
         }
      }

      boolean arrived = this.flight.climbOut(mc, level, player, this.departY);
      // Mining a ceiling block is progress too; only a genuine height stall
      // (unbreakable roof, missing chunk, anti-cheat) should end the departure.
      if (this.flight.isMining() || player.getY() > this.departBestY + 0.25) {
         this.departBestY = Math.max(this.departBestY, player.getY());
         this.departProgressMs = now;
      }
      boolean stalled = now - this.departProgressMs > DEPART_STALL_MS;
      boolean timedOut = now - this.departSinceMs > DEPART_TIMEOUT_MS;
      if (!arrived && !stalled && !timedOut) {
         this.throttledInfo(
            "正在垂直向上飞离（还差 " + (int)Math.max(0.0, this.departY - player.getY()) + " 格）…", now
         );
         return;
      }
      if (stalled && !arrived) {
         this.info("垂直飞离受阻，已跳过剩余爬升…", ChatFormatting.YELLOW);
      }
      String reason = this.departFinishReason;
      this.departFinishReason = null;
      this.departSinceMs = 0L;
      this.departProgressMs = 0L;
      this.flight.hover(mc, player, true);
      if (reason == null && player.getInventory().getFreeSlot() < 0 && !ShulkerAutoStore.INSTANCE.canStoreInventory(player)) {
         reason = this.fullInventoryReason(mc, player);
      }
      if (reason != null) {
         this.finish(mc, reason);
      } else {
         this.continueHunting(player, now);
      }
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

   private static String formatVec(Vec3 pos) {
      if (pos == null) {
         return "无";
      }
      return "(" + String.format("%.1f", pos.x) + ", " + String.format("%.1f", pos.y)
         + ", " + String.format("%.1f", pos.z) + ")";
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
