package kaptainwutax.seedcrackerX.elytra;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import kaptainwutax.seedcrackerX.autoeat.AutoEatManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Intelligent 3D flight pathfinder, full-AABB obstacle resolver, and block miner
 * for the automatic elytra hunt.
 *
 * <p>Key improvements over the legacy single-ray controller:</p>
 * <ul>
 *   <li><b>3D Voxel A* Pathfinding</b>: routes around towers, chorus trees, and
 *       ship hulls when an open-air path exists, and plans minimal-cost tunnels
 *       through soft blocks (Purpur / Stained Glass) while avoiding Obsidian
 *       and Shulkers when the target is enclosed.</li>
 *   <li><b>Full-Body Swept AABB Collision &amp; Mining</b>: sweeps the player's
 *       entire {@code 0.6 x 1.8} bounding box (with safety margin) along the
 *       movement vector so blocks at feet, knees, head, or shoulders are never
 *       missed by a thin eye-level ray, and 1x1 holes are always expanded to a
 *       full passable tunnel.</li>
 *   <li><b>Occlusion-Aware Nearest-First Mining</b>: always mines the closest
 *       visible blocking block within reach and synchronizes with vanilla's
 *       {@code MultiPlayerGameMode} without double-ticking {@code destroyProgress}.</li>
 *   <li><b>Velocity-Damped Precision Steering</b>: actively cancels lateral
 *       creative-flight drift via counter-strafing and avoids 180-degree yaw
 *       oscillation during vertical climbs/descents.</li>
 * </ul>
 */
public final class FlyController {
   public static final int CRUISE_Y = 165;

   private enum ToolIntent { MINE, ATTACK }

   private static final Map<Item, Integer> PICKAXE_RANK = Map.ofEntries(
      Map.entry(Items.WOODEN_PICKAXE, 1),
      Map.entry(Items.COPPER_PICKAXE, 2),
      Map.entry(Items.STONE_PICKAXE, 3),
      Map.entry(Items.GOLDEN_PICKAXE, 4),
      Map.entry(Items.IRON_PICKAXE, 5),
      Map.entry(Items.DIAMOND_PICKAXE, 6),
      Map.entry(Items.NETHERITE_PICKAXE, 7)
   );
   private static final Map<Item, Integer> WEAPON_SCORE = Map.ofEntries(
      Map.entry(Items.MACE, 100),
      Map.entry(Items.TRIDENT, 95),
      Map.entry(Items.NETHERITE_SWORD, 90),
      Map.entry(Items.NETHERITE_AXE, 85),
      Map.entry(Items.DIAMOND_SWORD, 80),
      Map.entry(Items.DIAMOND_AXE, 75),
      Map.entry(Items.IRON_SWORD, 65),
      Map.entry(Items.IRON_AXE, 62),
      Map.entry(Items.GOLDEN_SWORD, 55),
      Map.entry(Items.GOLDEN_AXE, 52),
      Map.entry(Items.STONE_SWORD, 45),
      Map.entry(Items.STONE_AXE, 42),
      Map.entry(Items.COPPER_SWORD, 35),
      Map.entry(Items.COPPER_AXE, 32),
      Map.entry(Items.WOODEN_SWORD, 25),
      Map.entry(Items.WOODEN_AXE, 22)
   );
   private static final long TOOL_MOVE_COOLDOWN_MS = 800L;
   private static final int MAX_TOOL_MOVES = 24;
   /** Minimum score advantage before a backpack tool is swapped into the hotbar. */
   private static final double MINING_INVENTORY_MARGIN = 5.0;
   private static final double ATTACK_INVENTORY_MARGIN = 10.0;

   private static long lastToolMoveMs;
   private static int toolMoves;
   private static String lastToolAction = "未切换";

   /**
    * Deadline during which MultiPlayerGameMode.stopDestroyBlock() is suppressed.
    *
    * <p>Minecraft.continueAttack() calls stopDestroyBlock() on EVERY tick whose
    * crosshair hits an ENTITY (the ignored Shulker or one of its bullets) or
    * whenever keyAttack is released. That sends ABORT_DESTROY_BLOCK, silently
    * resetting the server-side break progress back to zero - which is why the
    * shaft could be mined forever without ever breaking a block. We refresh
    * this deadline on each mining tick and clear it in stopMining(), so it
    * lapses by itself if the hunt is aborted. See MultiPlayerGameModeMixin.</p>
    */
   private static volatile long destroyProtectedUntilMs;

   /** True while we are actively driving a block break that must not be aborted. */
   public static boolean isDestroyProtected() {
      return System.currentTimeMillis() < FlyController.destroyProtectedUntilMs;
   }

   public static void resetToolState() {
      FlyController.lastToolMoveMs = 0L;
      FlyController.toolMoves = 0;
      FlyController.lastToolAction = "未切换";
   }

   /**
    * True while a long item use (auto-eat, or the player's own held right click)
    * owns {@code keyUse}. Minecraft.handleKeybinds() calls releaseUsingItem() as
    * soon as {@code isUsingItem()} is true but keyUse is not down, so forcibly
    * clearing that key - which steerRaw/tickMining used to do on EVERY tick -
    * silently cancelled eating while the elytra hunt was running.
    */
   private static boolean useKeyInUse(Minecraft mc) {
      if (AutoEatManager.INSTANCE.isBusy()) {
         return true;
      }
      LocalPlayer player = mc == null ? null : mc.player;
      return player != null && player.isUsingItem();
   }

   /**
    * Release flight/attack inputs while another subsystem (auto-eat) owns the
    * player's hands. keyUse is deliberately left alone, see {@link #useKeyInUse}.
    */
   public void pauseForAutoEat(Minecraft mc) {
      if (mc == null || mc.options == null) {
         return;
      }
      this.stopMining(mc);
      this.releaseMovementKeys(mc);
   }

   /** Equip the fastest tool for {@code state}. Safe to call every tick. */
   public static boolean equipForMining(LocalPlayer player, BlockState state) {
      return equip(player, state, ToolIntent.MINE);
   }

   /** Equip the hardest-hitting melee weapon. Safe to call every tick. */
   public static boolean equipForAttack(LocalPlayer player) {
      return equip(player, null, ToolIntent.ATTACK);
   }

   public static String toolDiagnostics(LocalPlayer player) {
      if (player == null) {
         return "手持=? 工具状态=" + FlyController.lastToolAction;
      }
      ItemStack held = player.getInventory().getSelectedItem();
      return "手持=" + itemName(held)
         + " 槽=" + (player.getInventory().getSelectedSlot() + 1)
         + " 工具状态=" + FlyController.lastToolAction;
   }

   private static boolean equip(LocalPlayer player, BlockState state, ToolIntent intent) {
      if (player == null) {
         return false;
      }
      Inventory inventory = player.getInventory();
      int hotbarSlot = bestHotbarSlot(inventory, state, intent);
      double hotbarScore = hotbarSlot >= 0 ? toolScore(inventory.getItem(hotbarSlot), state, intent) : -1.0;

      // Mining is block-specific: even a top-tier hotbar pickaxe is the WRONG
      // tool for a chest/barrel (MINEABLE_WITH_AXE) because it gets no speed
      // bonus there. The old "hotbarScore < 100" gate treated any pickaxe as
      // good enough, so an axe sitting in the backpack was never considered.
      // Always compare against the backpack when mining; keep the old gate for
      // weapons so combat behaviour is unchanged.
      boolean checkInventory = intent == ToolIntent.MINE
         || hotbarSlot < 0
         || hotbarScore < 100.0;
      double margin = intent == ToolIntent.MINE
         ? MINING_INVENTORY_MARGIN
         : ATTACK_INVENTORY_MARGIN;
      if (checkInventory) {
         int invSlot = bestInventorySlot(inventory, state, intent);
         double invScore = invSlot >= 0 ? toolScore(inventory.getItem(invSlot), state, intent) : -1.0;
         // Mining: only bring in a tool that ACTUALLY speeds up this block
         // (score >= 100 means destroySpeed > 1). Otherwise breaking a
         // tag-less block such as a shulker box would reshuffle the hotbar
         // mid-break and reset the server-side destroy progress.
         boolean effective = intent != ToolIntent.MINE || invScore >= 100.0;
         if (effective && invScore > hotbarScore + margin
            && moveToolFromInventory(player, invSlot, intent)) {
            hotbarSlot = bestHotbarSlot(inventory, state, intent);
         }
      }

      if (hotbarSlot < 0) {
         FlyController.lastToolAction = (intent == ToolIntent.MINE ? "无可用挖掘工具" : "无可用武器");
         return false;
      }
      String target = intent == ToolIntent.MINE ? " 目标=" + blockLabel(state) : "";
      if (hotbarSlot != inventory.getSelectedSlot()) {
         selectSlot(player, hotbarSlot);
         FlyController.lastToolAction = (intent == ToolIntent.MINE ? "挖掘切工具" : "攻击切武器")
            + " → 槽" + (hotbarSlot + 1) + " " + itemName(inventory.getItem(hotbarSlot)) + target;
      } else {
         FlyController.lastToolAction = (intent == ToolIntent.MINE ? "挖掘" : "攻击")
            + "沿用 " + itemName(inventory.getItem(hotbarSlot)) + target;
      }
      return true;
   }

   private static void selectSlot(LocalPlayer player, int slot) {
      try {
         player.getInventory().setSelectedSlot(slot);
         if (player.connection != null) {
            player.connection.send(new ServerboundSetCarriedItemPacket(slot));
         }
      } catch (Throwable ignored) {
      }
   }

   private static int bestHotbarSlot(Inventory inventory, BlockState state, ToolIntent intent) {
      int best = -1;
      double bestScore = 0.0;
      for (int i = 0; i < Inventory.getSelectionSize(); i++) {
         double value = toolScore(inventory.getItem(i), state, intent);
         if (value > bestScore) {
            bestScore = value;
            best = i;
         }
      }
      if (best < 0) {
         return -1;
      }
      int current = inventory.getSelectedSlot();
      if (toolScore(inventory.getItem(current), state, intent) >= bestScore) {
         return current;
      }
      return best;
   }

   private static int bestInventorySlot(Inventory inventory, BlockState state, ToolIntent intent) {
      int best = -1;
      double bestScore = 0.0;
      for (int i = Inventory.getSelectionSize(); i < 36; i++) {
         double value = toolScore(inventory.getItem(i), state, intent);
         if (value > bestScore) {
            bestScore = value;
            best = i;
         }
      }
      return best;
   }

   private static double toolScore(ItemStack stack, BlockState state, ToolIntent intent) {
      if (stack == null || stack.isEmpty()) {
         return -1.0;
      }
      if (stack.getMaxDamage() > 0 && stack.getMaxDamage() - stack.getDamageValue() <= 3) {
         return -1.0;
      }
      Item item = stack.getItem();
      double base = -1.0;
      if (intent == ToolIntent.MINE) {
         // Rank by the REAL mining speed of this stack against this exact block.
         // In 26.3 Item.getDestroySpeed() reads the TOOL component rules: an axe
         // gets e.g. 8.0 on a chest (MINEABLE_WITH_AXE), while a pickaxe gets
         // only the 1.0 default. The old fixed "100 + pickaxe tier" bonus made a
         // pickaxe unbeatable on EVERY block, so chests were mined bare-hand slow
         // and the axe was never equipped.
         float destroySpeed = 1.0F;
         if (state != null) {
            try {
               destroySpeed = stack.getDestroySpeed(state);
            } catch (Throwable ignored) {
            }
         }
         if (destroySpeed > 1.0F) {
            base = 100.0 + destroySpeed * 10.0;
         } else if (isMiningTool(stack)) {
            // No tool speeds this block up (shulker box, glass, ...): keep a
            // tool in hand instead of some random item, with the historical
            // slight pickaxe preference.
            base = PICKAXE_RANK.containsKey(item) ? 20.0 : 10.0;
         } else {
            return -1.0;
         }
      } else {
         Integer score = WEAPON_SCORE.get(item);
         if (score == null) {
            Integer pickRank = PICKAXE_RANK.get(item);
            if (pickRank != null) {
               base = 10.0 + pickRank;
            } else {
               return -1.0;
            }
         } else {
            base = score;
         }
      }
      try {
         if (stack.isEnchanted()) {
            base += 5.0;
         }
         if (stack.getMaxDamage() > 0) {
            base += Math.min(2.0, (stack.getMaxDamage() - stack.getDamageValue()) / 500.0);
         }
      } catch (Throwable ignored) {
      }
      return base;
   }

   /** True for pickaxes, axes, shovels, hoes, shears, ... (26.3 TOOL component). */
   private static boolean isMiningTool(ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return false;
      }
      try {
         if (stack.get(DataComponents.TOOL) != null) {
            return true;
         }
      } catch (Throwable ignored) {
      }
      return PICKAXE_RANK.containsKey(stack.getItem());
   }

   /** Compact debug label showing which tool category the target block wants. */
   private static String blockLabel(BlockState state) {
      if (state == null) {
         return "无";
      }
      String id;
      try {
         id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
      } catch (Throwable ignored) {
         id = state.getBlock().getClass().getSimpleName();
      }
      String tool = "通用";
      try {
         if (state.is(BlockTags.MINEABLE_WITH_AXE)) {
            tool = "斧";
         } else if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
            tool = "镐";
         } else if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) {
            tool = "锹";
         } else if (state.is(BlockTags.MINEABLE_WITH_HOE)) {
            tool = "锄";
         }
      } catch (Throwable ignored) {
      }
      return id + "[" + tool + "]";
   }

   /** Move or SWAP a tool from main inventory (slots 9..35) into the hotbar, even if hotbar is full. */
   private static boolean moveToolFromInventory(LocalPlayer player, int invSlot, ToolIntent intent) {
      Minecraft mc = Minecraft.getInstance();
      if (mc == null || mc.gameMode == null || !(player.containerMenu instanceof InventoryMenu)) {
         return false;
      }
      if (FlyController.toolMoves >= MAX_TOOL_MOVES) {
         return false;
      }
      long now = System.currentTimeMillis();
      if (now - FlyController.lastToolMoveMs < TOOL_MOVE_COOLDOWN_MS) {
         return false;
      }
      Inventory inventory = player.getInventory();
      int targetHotbar = -1;
      for (int i = 0; i < Inventory.getSelectionSize(); i++) {
         if (inventory.getItem(i).isEmpty()) {
            targetHotbar = i;
            break;
         }
      }
      if (targetHotbar < 0) {
         // Hotbar is full: pick an expendable slot, but never evict a tool
         // (axes included - they are the correct tool for chests/wood), a
         // weapon, or the elytra.
         for (int i = Inventory.getSelectionSize() - 1; i >= 0; i--) {
            ItemStack s = inventory.getItem(i);
            Item item = s.getItem();
            if (!isMiningTool(s) && !WEAPON_SCORE.containsKey(item) && item != Items.ELYTRA) {
               targetHotbar = i;
               break;
            }
         }
         if (targetHotbar < 0) {
            targetHotbar = inventory.getSelectedSlot();
         }
      }
      try {
         mc.gameMode.handleContainerInput(0, invSlot, targetHotbar, ContainerInput.SWAP, player);
         selectSlot(player, targetHotbar);
         FlyController.lastToolMoveMs = now;
         FlyController.toolMoves++;
         FlyController.lastToolAction = (intent == ToolIntent.MINE ? "挖掘" : "攻击")
            + "：从背包槽 " + invSlot + " 换入快捷栏槽 " + (targetHotbar + 1);
         return true;
      } catch (Throwable ignored) {
         return false;
      }
   }

   private static String itemName(ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return "空手";
      }
      try {
         return stack.getItemName().getString();
      } catch (Throwable ignored) {
         return stack.getItem().toString();
      }
   }

   /** What {@link #navigate} decided to do on the last tick. */
   public enum NavState {
      CLEAR,          // straight swept-AABB path is free, flying to the target
      DETOUR,         // following a 3D A* path around obstacles
      APPROACH_MINE,  // moving within reach of a blocking block to mine it
      MINING,         // breaking a blocking block
      ENTITY,         // a hostile entity blocks the path and must be cleared
      BLOCKED,        // no route available under current constraints
      DONE,           // internal: mined block is broken
      FAILED          // internal: mining timed out or failed
   }

   /** Outcome of a strictly vertical, single-column movement (used by the elytra approach). */
   public enum ColumnState {
      MOVING,   // sliding onto the column, or flying vertically inside it
      MINING,   // breaking the block that blocks the shaft
      ARRIVED,  // aligned with the column and at the requested feet Y
      BLOCKED   // an unbreakable / unloaded block stops the shaft
   }

   private static final float PITCH_LIMIT = 45.0F;
   private static final double CRUISE_VERTICAL_DEADZONE = 0.85;
   private static final double LOCAL_VERTICAL_DEADZONE = 0.22;
   private static final int STUCK_TICKS = 70;
   private static final double STUCK_MOVE_THRESHOLD = 0.9;
   private static final int MAX_LIFT_RETRIES = 4;
   private static final int LIFT_STEP = 18;

   /**
    * Vertical creative-flight physics in MC 26.3 (decompiled from
    * {@code LocalPlayer.aiStep()} and {@code Player.travel()}):
    * <ul>
    *   <li>holding jump / sneak adds {@code 3 * flyingSpeed} to vy for that tick;</li>
    *   <li>after moving, {@code Player.travel()} keeps only 60% of that vy;</li>
    *   <li>with no vertical input vy decays by 0.6 per tick, so the remaining
    *       coast distance is {@code v * (1 + .6 + .36 + ...) = 2.5 * v}.</li>
    * </ul>
    * Tweakeroo's fly-speed tweak scales the impulse but NOT the 0.6 drag.
    * The old controller predicted the coast with {@code 1.5 * vy} and used a
    * fixed 0.08 brake threshold, which at high fly speeds converges to a stable
    * jump/sneak 2-cycle: the player crouches and stands forever at one height.
    */
   private static final double FLY_VERTICAL_KEEP = 0.6;
   private static final double FLY_COAST_FACTOR = 1.0 / (1.0 - FLY_VERTICAL_KEEP); // 2.5
   private static final double DEFAULT_VERTICAL_IMPULSE = 0.15;                   // 3 * 0.05 (vanilla)
   private static final double MIN_VERTICAL_BAND = 0.42;
   /** Cap for the departure arrival band, even at absurd fly speeds. */
   private static final double MAX_DEPART_BAND = 8.0;
   /** Keep holding sneak until the feet are this close to the descent target. */
   private static final double DESCENT_HOLD_Y = 0.20;
   /** Horizontal tolerance for declaring the column reached. */
   private static final double COLUMN_ARRIVE_XZ = 0.75;

   // Swept AABB & A* parameters
   private static final double PROBE_DISTANCE = 40.0;
   private static final double PLAYER_HALF_WIDTH = 0.34;
   private static final double PLAYER_HEIGHT = 1.84;
   private static final double NODE_FEET_OFFSET_Y = 0.08;
   private static final double WAYPOINT_ARRIVE_XZ = 0.68;
   private static final double WAYPOINT_ARRIVE_Y = 0.85;
   private static final long PATH_RECOMPUTE_MS = 2200L;
   private static final int MAX_ASTAR_NODES = 2000;
   private static final double ASTAR_SUBGOAL_DIST = 28.0;

   // Mining parameters
   private static final double MINE_REACH = 4.25;
   private static final double MINE_APPROACH_DIST = 2.1;
   private static final long MINE_TIMEOUT_MS = 12000L;
   private static final double THREAT_PROBE_DISTANCE = 28.0;
   private static final double THREAT_PADDING = 0.35;

   private int cruiseY = CRUISE_Y;
   private int liftRetries;
   private int stuckTicks;
   private double lastX;
   private double lastY;
   private double lastZ;
   private boolean hasLastPos;

   // Adaptive vertical-flight model (Tweakeroo may change the fly speed at any time).
   /** Decaying peak of |vy|; steady-state climb speed is ~1.5 * verticalImpulse. */
   private double verticalSpeedPeak;
   /** Impulse measured from the velocity change across consecutive ticks. */
   private double observedImpulse;
   /** Estimated per-tick vertical impulse (3 * effective flyingSpeed). */
   private double verticalImpulse = DEFAULT_VERTICAL_IMPULSE;
   /** -1 / 0 / +1: latched vertical input, prevents per-tick jump/sneak flipping. */
   private int verticalDirLatch;
   private double lastVerticalVy;
   private int lastVerticalDir;
   private long verticalSampleMs;

   /** Last blocking block found below the player, for the /ov qc debug line. */
   private BlockPos lastColumnBlocker;
   private double lastColumnOffset;

   // 3D A* path state
   private List<BlockPos> currentPath;
   private int pathIndex;
   private Vec3 lastPathGoal;
   private boolean lastPathAllowMine;
   private long lastPathTimeMs;

   // Mining state
   private BlockPos miningPos;
   private Direction miningDir;
   private Vec3 miningAimPoint;
   private boolean miningStarted;
   private boolean miningFootBlocker;
   private long miningStartMs;
   private int offAimTicks;
   private long ignoreThreatUntilMs;
   private NavState navState = NavState.CLEAR;

   public int cruiseY() {
      return this.cruiseY;
   }

   public void setMinCruiseY(int minCruiseY) {
      if (minCruiseY > this.cruiseY) {
         this.cruiseY = minCruiseY;
      }
   }

   public int liftRetries() {
      return this.liftRetries;
   }

   public NavState navState() {
      return this.navState;
   }

   public BlockPos miningBlock() {
      return this.miningPos;
   }

   public void resetForNewTarget() {
      this.stopMining(Minecraft.getInstance());
      this.cruiseY = CRUISE_Y;
      this.liftRetries = 0;
      this.clearPath();
      this.ignoreThreatUntilMs = 0L;
      this.setNav(NavState.CLEAR);
      this.resetStuck();
      this.resetVerticalModel();
   }

   private void clearPath() {
      this.currentPath = null;
      this.pathIndex = 0;
      this.lastPathGoal = null;
      this.lastPathTimeMs = 0L;
   }

   public void ignoreThreatsFor(long millis) {
      this.ignoreThreatUntilMs = System.currentTimeMillis() + millis;
   }

   public boolean isIgnoringThreats() {
      return System.currentTimeMillis() < this.ignoreThreatUntilMs;
   }

   public boolean lift() {
      if (this.liftRetries >= MAX_LIFT_RETRIES) {
         return false;
      }
      this.liftRetries++;
      this.cruiseY += LIFT_STEP;
      this.clearPath();
      this.resetStuck();
      return true;
   }

   private void resetStuck() {
      this.stuckTicks = 0;
      this.hasLastPos = false;
   }

   // ------------------------------------------------------------------ adaptive vertical flight model

   /**
    * Refresh the estimate of the per-tick vertical impulse. Tweakeroo can change
    * the fly speed either through {@code Abilities.getFlyingSpeed()} or by
    * injecting into {@code LocalPlayer.aiStep()}, so we look at BOTH the
    * configured value and the velocity we actually observe. In steady flight
    * {@code |vy| = 1.5 * impulse}, hence {@code impulse = |vy| / 1.5}.
    */
   private void sampleVerticalSpeed(LocalPlayer player) {
      if (player == null) {
         return;
      }
      long now = System.currentTimeMillis();
      long previousSampleMs = this.verticalSampleMs;
      // Several controller methods can run in the same client tick; only the
      // first one samples, otherwise the "previous velocity" would be stale.
      if (now - previousSampleMs < 20L) {
         return;
      }
      this.verticalSampleMs = now;

      double vy = player.getDeltaMovement().y;
      // v(n) = 0.6 * (v(n-1) + impulse * dir). Invert it to measure the real
      // per-tick impulse, which also covers mods that inject into aiStep()
      // without touching Abilities.getFlyingSpeed().
      if (this.lastVerticalDir != 0 && previousSampleMs != 0L && now - previousSampleMs <= 120L) {
         double observed = Math.abs(vy / FLY_VERTICAL_KEEP - this.lastVerticalVy);
         if (observed > 0.03 && observed < 24.0) {
            this.observedImpulse = Math.max(observed * 0.9, this.observedImpulse * 0.97);
         }
      }
      this.lastVerticalVy = vy;

      double configured = 3.0 * Math.max(0.02F, player.getAbilities().getFlyingSpeed());
      if (player.getAbilities().flying) {
         this.verticalSpeedPeak = Math.max(Math.abs(vy), this.verticalSpeedPeak * 0.97);
      }
      this.verticalImpulse = Mth.clamp(
         Math.max(configured, Math.max(this.observedImpulse, this.verticalSpeedPeak / 1.5)),
         0.05, 24.0
      );
   }

   /** Remember which vertical input was actually applied, for the next sample. */
   private void recordVerticalInput(int dir) {
      this.lastVerticalDir = dir;
   }

   /** Effective per-tick vertical impulse (3 * flyingSpeed), Tweakeroo-safe. */
   public double verticalImpulse() {
      return this.verticalImpulse;
   }

   /**
    * Vertical tolerance that grows with the fly speed: one input tick plus the
    * following coast already moves the player by roughly {@code 2.5 * impulse},
    * so a fixed 0.42-block arrival window can never be hit at high speeds.
    */
   public double verticalArrivalBand() {
      return Math.max(MIN_VERTICAL_BAND, 2.8 * this.verticalImpulse);
   }

   public String verticalDiagnostics() {
      return "飞行脉冲=" + String.format("%.2f", this.verticalImpulse)
         + " 垂直容差=" + String.format("%.2f", this.verticalArrivalBand());
   }

   private void resetVerticalModel() {
      this.verticalImpulse = DEFAULT_VERTICAL_IMPULSE;
      this.verticalSpeedPeak = 0.0;
      this.observedImpulse = 0.0;
      this.verticalDirLatch = 0;
      this.lastVerticalVy = 0.0;
      this.lastVerticalDir = 0;
      this.verticalSampleMs = 0L;
      this.lastColumnBlocker = null;
      this.lastColumnOffset = 0.0;
   }

   /**
    * Drive keyJump / keyShift so the player coasts to {@code targetFeetY}.
    * Uses the exact coast distance, an impulse-scaled dead band and a latched
    * direction, so a high fly speed can no longer produce an endless
    * jump/sneak (crouch/stand) limit cycle near the target.
    */
   private void driveVertical(Minecraft mc, LocalPlayer player, double targetFeetY, double deadzone) {
      if (mc == null || mc.options == null) {
         return;
      }
      this.sampleVerticalSpeed(player);
      double vy = player.getDeltaMovement().y;
      double dy = targetFeetY - player.getY();
      double band = Math.max(this.verticalArrivalBand(), deadzone + 0.12);
      // Where the player would end up relative to the target if ALL vertical
      // keys were released right now.
      double residual = dy - FLY_COAST_FACTOR * vy;

      int dir = this.verticalDirLatch;
      if (dir > 0) {
         if (residual <= band * 0.35) {
            dir = 0;
         }
      } else if (dir < 0) {
         if (residual >= -band * 0.35) {
            dir = 0;
         }
      } else if (residual > band) {
         dir = 1;
      } else if (residual < -band && dy < 0.0) {
         dir = -1;
      }
      // keyShift is both "descend" and the crouch key. Reverse-thrusting an
      // ascent is exactly what made the player bob up and down at one height,
      // so while the goal is above us we only ever coast, never sneak.
      if (dir < 0 && dy > 0.0) {
         dir = 0;
      }
      mc.options.keyJump.setDown(dir > 0);
      mc.options.keyShift.setDown(dir < 0);
      this.verticalDirLatch = dir;
      this.recordVerticalInput(dir);
   }

   // ------------------------------------------------------------------ coast-aware column descent servo

   /**
    * Descend by HOLDING sneak until the feet reach the target.
    *
    * <p>The generic arrival servo ({@link #driveVertical}) uses a coast-derived
    * dead band of {@code 2.8 * impulse} to avoid a jump/sneak limit cycle in
    * free flight. That band is far too wide for a descent: the shaft floor
    * clamps {@code vy} to zero, so the servo would release sneak 1-2 blocks
    * early and leave the player hovering above the shaft. Here we only use a
    * single jump pulse to brake a genuine free fall that would overshoot the
    * target, and never alternate jump/sneak near the target. Simulated against
    * 26.3 physics this converges to the target with 0-2 direction changes for
    * impulses 0.15..3.0 (i.e. up to 20x vanilla fly speed).</p>
    */
   private void driveDescentVertical(Minecraft mc, LocalPlayer player, double targetFeetY) {
      if (mc == null || mc.options == null) {
         return;
      }
      this.sampleVerticalSpeed(player);
      double vy = player.getDeltaMovement().y;
      double predicted = player.getY() + FLY_COAST_FACTOR * vy;
      double brakeBand = Math.max(1.0, 2.5 * this.verticalImpulse);
      int dir;
      if (predicted < targetFeetY - brakeBand) {
         dir = 1;   // free-falling too fast: one braking pulse
      } else if (player.getY() > targetFeetY + DESCENT_HOLD_Y) {
         dir = -1;  // keep descending
      } else {
         dir = 0;   // arrived / coast
      }
      mc.options.keyJump.setDown(dir > 0);
      mc.options.keyShift.setDown(dir < 0);
      this.verticalDirLatch = 0;
      this.recordVerticalInput(dir);
   }

   /**
    * Descend a shaft WITHOUT first demanding block-centre alignment.
    *
    * <p>Creative flight keeps 91% of its HORIZONTAL velocity each tick
    * ({@code LivingEntity.travelInAir}), so at a raised fly speed one input
    * pulse coasts ~11x its velocity: a position-based "slide onto the exact
    * centre" controller can never converge on a 0.15-block tolerance. Chasing
    * the centre is what made the old code stall while refusing to look at the
    * floor. Here we only damp horizontal drift (the shaft walls clamp it), and
    * let the full-AABB blocker scan in {@link #travelColumn} dig whatever is
    * actually under the player's feet, in whichever column it happens to be.</p>
    */
   private void driveColumnDescent(Minecraft mc, LocalPlayer player, double targetFeetY) {
      if (mc == null || mc.options == null) {
         return;
      }
      this.brakeHorizontal(mc, player);
      // Hold sneak until the feet actually reach the target instead of
      // stopping a couple of blocks short at a high fly speed.
      this.driveDescentVertical(mc, player, targetFeetY);
      mc.options.keySprint.setDown(false);
      mc.options.keyAttack.setDown(false);
      if (!useKeyInUse(mc)) {
         mc.options.keyUse.setDown(false);
      }
   }

   // ------------------------------------------------------------------ main navigation entry

   /**
    * Navigate towards {@code target} using full-AABB sweep checks, 3D A* pathfinding,
    * and automatic 2-block-tall tunnel mining when needed.
    */
   public NavState navigate(Minecraft mc, ClientLevel level, LocalPlayer player, Vec3 target, boolean slow, boolean allowMine) {
      if (level == null || mc == null || mc.gameMode == null || player == null || target == null) {
         this.steer(mc, player, target, slow);
         return this.setNav(NavState.CLEAR);
      }
      long now = System.currentTimeMillis();

      // 1. If we are actively mining a valid nearby block, keep mining it unless it's broken or occluded.
      if (this.miningPos != null) {
         if (isPassableBlock(level, this.miningPos)) {
            this.stopMining(mc);
         } else {
            BlockPos closerOccluder = this.findOccluderOrCloserBlocker(level, player, this.miningPos);
            if (closerOccluder != null && !closerOccluder.equals(this.miningPos)) {
               this.startMiningBlock(player, level, closerOccluder, now);
            }
            NavState mineState = this.tickMining(mc, level, player, now);
            if (mineState == NavState.MINING || mineState == NavState.APPROACH_MINE) {
               return this.setNav(mineState);
            }
            if (mineState == NavState.FAILED) {
               this.clearPath();
               return this.blocked(mc, player);
            }
         }
      }

      // 2. Check if the player's body is currently colliding with or immediately blocked by a block
      //    right in front of them, or if the direct swept AABB to target is completely clear.
      boolean checkThreats = now > this.ignoreThreatUntilMs && player.position().distanceToSqr(target) < 48.0 * 48.0;
      if (this.isBoxSweepClear(level, player, player.position(), target, PROBE_DISTANCE, checkThreats)) {
         this.clearPath();
         double vDeadzone = slow || player.position().distanceToSqr(target) < 64.0
            ? LOCAL_VERTICAL_DEADZONE
            : CRUISE_VERTICAL_DEADZONE;
         this.steer(mc, player, target, slow, vDeadzone);
         return this.setNav(NavState.CLEAR);
      }

      // 3. Direct path is obstructed. Maintain or compute a 3D A* voxel path.
      boolean needRecompute = this.currentPath == null
         || this.pathIndex >= this.currentPath.size()
         || this.lastPathGoal == null
         || this.lastPathGoal.distanceToSqr(target) > 4.0
         || this.lastPathAllowMine != allowMine
         || now - this.lastPathTimeMs > PATH_RECOMPUTE_MS;

      if (!needRecompute && this.pathIndex < this.currentPath.size()) {
         Vec3 wpCenter = waypointVec(this.currentPath.get(this.pathIndex));
         if (player.position().distanceToSqr(wpCenter) > 100.0) {
            needRecompute = true;
         }
      }

      if (needRecompute) {
         List<BlockPos> computed = this.computeAStarPath(level, player, target, allowMine, checkThreats);
         if (computed != null && !computed.isEmpty()) {
            this.currentPath = computed;
            this.pathIndex = 0;
            this.lastPathGoal = target;
            this.lastPathAllowMine = allowMine;
            this.lastPathTimeMs = now;
         } else {
            this.clearPath();
         }
      }

      // 4. Advance along the A* path and perform dynamic lookahead string-pulling on clear air segments.
      Vec3 nextWaypoint = target;
      BlockPos nextNodePos = BlockPos.containing(target);
      if (this.currentPath != null && !this.currentPath.isEmpty()) {
         while (this.pathIndex < this.currentPath.size()) {
            BlockPos wp = this.currentPath.get(this.pathIndex);
            Vec3 wpVec = waypointVec(wp);
            double horiz = Math.hypot(wpVec.x - player.getX(), wpVec.z - player.getZ());
            double dy = Math.abs(wpVec.y - player.getY());
            if (horiz <= WAYPOINT_ARRIVE_XZ && dy <= WAYPOINT_ARRIVE_Y && isPassableColumn(level, wp.getX(), wp.getY(), wp.getZ())) {
               this.pathIndex++;
            } else {
               break;
            }
         }
         // Dynamic lookahead: skip intermediate waypoints if the swept AABB to a further waypoint is 100% clear.
         while (this.pathIndex + 1 < this.currentPath.size()) {
            BlockPos next = this.currentPath.get(this.pathIndex + 1);
            if (!isPassableColumn(level, next.getX(), next.getY(), next.getZ())) {
               break;
            }
            Vec3 nextVec = waypointVec(next);
            if (player.position().distanceToSqr(nextVec) <= 64.0
               && this.isBoxSweepClear(level, player, player.position(), nextVec, 8.5, checkThreats)) {
               this.pathIndex++;
            } else {
               break;
            }
         }
         if (this.pathIndex < this.currentPath.size()) {
            nextNodePos = this.currentPath.get(this.pathIndex);
            nextWaypoint = waypointVec(nextNodePos);
         }
      }

      // 5. Check if any solid block intersects the swept player AABB towards nextWaypoint (or at nextNodePos).
      BlockPos blocker = this.findNextBlockToMine(level, player, nextNodePos, nextWaypoint);
      if (blocker != null) {
         if (!allowMine || !isMineableBlock(level, player, blocker)) {
            this.clearPath();
            return this.blocked(mc, player);
         }
         this.startMiningBlock(player, level, blocker, now);
         NavState mineState = this.tickMining(mc, level, player, now);
         if (mineState == NavState.FAILED) {
            this.clearPath();
            return this.blocked(mc, player);
         }
         return this.setNav(mineState);
      }

      // 6. Check if a Shulker blocks the immediate segment to nextWaypoint when no detour around it existed.
      if (checkThreats) {
         Entity threat = findThreat(level, player, player.getEyePosition(), nextWaypoint.add(0.0, 1.0, 0.0));
         if (threat != null && player.distanceToSqr(threat) < 36.0) {
            this.stopMining(mc);
            return this.setNav(NavState.ENTITY);
         }
      }

      // 7. Segment to nextWaypoint is clear: steer smoothly with lateral drift cancellation.
      boolean isFinal = nextWaypoint == target;
      this.steer(mc, player, nextWaypoint, slow || !isFinal, LOCAL_VERTICAL_DEADZONE);
      return this.setNav(isFinal ? NavState.CLEAR : NavState.DETOUR);
   }

   /**
    * Vertical approach inside one X/Z column.
    *
    * <p>Unlike {@link #navigate}, this never plans a detour. It moves the
    * player's feet towards {@code targetFeetY} while breaking the blocks in the
    * direction of travel. Blocking blocks are found by scanning the FULL player
    * AABB footprint ({@link #findVerticalBlocker}), so a fraction-of-a-block
    * horizontal offset - common when a mod raises the creative fly speed - can
    * no longer hide the floor block under the player's feet. Descending no
    * longer waits for an exact 1x1 column centre alignment.</p>
    *
    * <p>Mining reuses {@link #startMiningBlock} / {@link #tickMining}, so tool
    * switching and vanilla {@code destroyProgress} stay consistent with the
    * normal 3D navigation path.</p>
    *
    * @param targetFeetY requested player <b>feet</b> Y; below the current Y digs
    *                    down through the floor, above it digs up through the ceiling
    */
   public ColumnState travelColumn(
      Minecraft mc, ClientLevel level, LocalPlayer player,
      double columnX, double columnZ, double targetFeetY, boolean allowMine
   ) {
      if (mc == null || level == null || player == null) {
         return ColumnState.BLOCKED;
      }
      long now = System.currentTimeMillis();
      this.ensureFlight(player);
      this.sampleVerticalSpeed(player);

      // Never interrupt an active break session: changing the target mid-break
      // resets the server-side destroy progress in 1.21+/26.x.
      if (this.miningPos != null) {
         if (isPassableBlock(level, this.miningPos)) {
            this.stopMining(mc);
         } else {
            NavState mineState = this.tickMining(mc, level, player, now);
            if (mineState == NavState.MINING || mineState == NavState.APPROACH_MINE) {
               this.setNav(mineState);
               return ColumnState.MINING;
            }
            if (mineState == NavState.FAILED) {
               this.stopMining(mc);
               return ColumnState.BLOCKED;
            }
            this.stopMining(mc);
         }
      }

      double dx = columnX - player.getX();
      double dz = columnZ - player.getZ();
      double horiz = Math.sqrt(dx * dx + dz * dz);
      double dy = targetFeetY - player.getY();
      this.lastColumnOffset = horiz;

      // Crossing detection with a TIGHT band. The wide band of the departure
      // servo would report "arrived" several blocks too early at a high fly
      // speed; a descent is paced by block breaking, so a small band is safe.
      double arrivalY = Mth.clamp(0.5 * this.verticalImpulse, MIN_VERTICAL_BAND, 0.6);
      boolean reachedY = dy < 0.0
         ? player.getY() <= targetFeetY + arrivalY
         : Math.abs(dy) <= arrivalY;
      if (horiz <= COLUMN_ARRIVE_XZ && reachedY) {
         this.hover(mc, player, false);
         this.setNav(NavState.CLEAR);
         return ColumnState.ARRIVED;
      }

      boolean descending = dy < -0.35;
      boolean ascending = dy > 0.35;
      BlockPos blocker;
      if (descending || ascending) {
         // Scan EVERY block column touched by the real player AABB. A 0.68-wide
         // player only 0.2 blocks off centre already straddles two columns, so
         // the floor block that actually stops the descent can be in the
         // neighbouring column - which the old 1x1 scan never examined.
         blocker = this.findVerticalBlocker(level, player, targetFeetY, descending, columnX, columnZ);
      } else {
         Vec3 alignPoint = new Vec3(columnX, player.getY(), columnZ);
         blocker = this.findNextBlockToMine(level, player, BlockPos.containing(alignPoint), alignPoint);
      }
      this.lastColumnBlocker = blocker;

      if (blocker != null) {
         if (!allowMine || !isMineableBlock(level, player, blocker)) {
            this.hover(mc, player, false);
            this.setNav(NavState.BLOCKED);
            return ColumnState.BLOCKED;
         }
         this.startMiningBlock(player, level, blocker, now);
         NavState mineState = this.tickMining(mc, level, player, now);
         if (mineState == NavState.FAILED) {
            this.stopMining(mc);
            return ColumnState.BLOCKED;
         }
         this.setNav(mineState);
         return ColumnState.MINING;
      }

      this.stopMining(mc);
      if (descending) {
         // Do NOT gate the descent on reaching the exact block centre. At a
         // raised fly speed that alignment never converges, so the old code
         // only "tried to descend" while never digging the floor underfoot.
         this.driveColumnDescent(mc, player, targetFeetY);
      } else {
         this.steerRaw(mc, player, new Vec3(columnX, targetFeetY, columnZ), true, 0.14);
      }
      this.setNav(NavState.CLEAR);
      return ColumnState.MOVING;
   }

   /**
    * First solid layer in the direction of travel, scanned across the FULL
    * player AABB footprint (not a single 1x1 column).
    *
    * <p>This is the fix for the "keeps trying to descend but never mines the
    * block under its feet" stall: the old code only inspected the ideal shaft
    * column, and only when {@code |dx| <= 0.15 && |dz| <= 0.15}. With a raised
    * fly speed that tolerance is never met, the alignment branch checks only
    * head/feet blocks at the CURRENT altitude, and the floor one block lower
    * is never mined.</p>
    */
   private BlockPos findVerticalBlocker(
      ClientLevel level, LocalPlayer player, double targetFeetY,
      boolean descending, double columnX, double columnZ
   ) {
      AABB box = player.getBoundingBox();
      int minX = Mth.floor(box.minX + 0.001);
      int maxX = Mth.floor(box.maxX - 0.001);
      int minZ = Mth.floor(box.minZ + 0.001);
      int maxZ = Mth.floor(box.maxZ - 0.001);
      int colBlockX = Mth.floor(columnX);
      int colBlockZ = Mth.floor(columnZ);
      double lookAhead = 1.2 + Math.min(2.0, this.verticalImpulse);
      int startY, endY, stepY;
      if (descending) {
         // Include the head/body layer: the 1.84-tall AABB also collides with
         // blocks one level above the feet, and those can be in a neighbouring
         // column when the player is off centre.
         startY = Mth.floor(player.getY() + 1.0);
         endY = Math.max(Mth.floor(targetFeetY + 0.05), Mth.floor(player.getY() - lookAhead));
         stepY = -1;
      } else {
         startY = Mth.floor(player.getY() + 1.0);
         endY = Math.min(
            Mth.floor(targetFeetY + PLAYER_HEIGHT + 0.05),
            Mth.floor(player.getY() + PLAYER_HEIGHT + lookAhead)
         );
         stepY = 1;
      }

      BlockPos best = null;
      BlockPos unbreakable = null;
      double bestScore = Double.MAX_VALUE;
      for (int y = startY; stepY > 0 ? y <= endY : y >= endY; y += stepY) {
         boolean layerFound = false;
         for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
               BlockPos pos = new BlockPos(x, y, z);
               if (isPassableBlock(level, pos)) {
                  continue;
               }
               layerFound = true;
               double score = Math.abs(y + 0.5 - player.getY()) * 10.0
                  + Math.abs(x + 0.5 - player.getX())
                  + Math.abs(z + 0.5 - player.getZ());
               // Prefer the intended shaft column so a small offset does not
               // widen the hole more than strictly necessary.
               if (x != colBlockX || z != colBlockZ) {
                  score += 0.6;
               }
               if (!isMineableBlock(level, player, pos)) {
                  if (unbreakable == null) {
                     unbreakable = pos;
                  }
                  continue;
               }
               if (score < bestScore) {
                  bestScore = score;
                  best = pos;
               }
            }
         }
         if (layerFound) {
            break; // first obstructed layer in the direction of travel
         }
      }
      if (best == null) {
         best = unbreakable;
      }
      if (best != null) {
         BlockPos occluder = this.findOccluder(level, player, best);
         if (occluder != null) {
            best = occluder;
         }
      }
      return best;
   }

   /**
    * Pure vertical ascent used after a successful elytra pickup.
    *
    * <p>{@link #travelColumn} first slides the player onto the exact centre of
    * a 1x1 block column. That alignment step is driven by {@link #steerRaw},
    * which at short horizontal distances only brakes momentum; with a raised
    * fly speed the player overshoots, never reaches the 0.15-block centre
    * tolerance, and stalls at one altitude while the vertical controller
    * alternates jump / sneak (the visible "crouching" loop). The departure does
    * not need block-centre precision at all - the shaft was already dug on the
    * way down - so this method climbs straight up from wherever the player is,
    * scans the FULL player AABB for a ceiling block, and NEVER presses the
    * sneak key.</p>
    *
    * @return true once the player has reached (or crossed) {@code targetFeetY}
    */
   public boolean climbOut(Minecraft mc, ClientLevel level, LocalPlayer player, double targetFeetY) {
      if (mc == null || level == null || player == null) {
         return true;
      }
      this.ensureFlight(player);
      this.sampleVerticalSpeed(player);
      long now = System.currentTimeMillis();
      // Crossing detection, not a fixed 0.42-block window: one tick at a high
      // fly speed already moves several blocks, so the old ARRIVED check could
      // never be satisfied.
      double band = Math.min(this.verticalArrivalBand(), MAX_DEPART_BAND);
      if (player.getY() >= targetFeetY - band) {
         this.hover(mc, player, true);
         this.setNav(NavState.CLEAR);
         return true;
      }

      // Never interrupt an active break session; changing the target resets the
      // server-side destroy progress.
      if (this.miningPos != null) {
         if (isPassableBlock(level, this.miningPos)) {
            this.stopMining(mc);
         } else {
            NavState mineState = this.tickMining(mc, level, player, now);
            if (mineState == NavState.MINING || mineState == NavState.APPROACH_MINE) {
               this.setNav(mineState);
               return false;
            }
            this.stopMining(mc);
            if (mineState == NavState.FAILED) {
               this.setNav(NavState.BLOCKED);
               return false;
            }
         }
      }

      BlockPos blocker = this.findUpwardBlocker(level, player);
      if (blocker != null) {
         if (!isMineableBlock(level, player, blocker)) {
            this.hover(mc, player, true);
            this.setNav(NavState.BLOCKED);
            return false;
         }
         this.startMiningBlock(player, level, blocker, now);
         NavState mineState = this.tickMining(mc, level, player, now);
         if (mineState == NavState.FAILED) {
            this.stopMining(mc);
         }
         this.setNav(mineState);
         return false;
      }

      this.stopMining(mc);
      if (mc.options != null) {
         this.brakeHorizontal(mc, player);
         // Hold jump only. keyShift is the sneak key AND the descend key; using
         // it as a reverse thruster is what produced the endless crouch loop.
         mc.options.keyJump.setDown(true);
         mc.options.keyShift.setDown(false);
         this.recordVerticalInput(1);
      }
      this.verticalDirLatch = 0;
      this.setNav(NavState.CLEAR);
      return false;
   }

   /** Nearest solid block just above the player's FULL 0.68-wide AABB footprint. */
   private BlockPos findUpwardBlocker(ClientLevel level, LocalPlayer player) {
      AABB box = player.getBoundingBox();
      int minX = Mth.floor(box.minX + 0.001);
      int maxX = Mth.floor(box.maxX - 0.001);
      int minZ = Mth.floor(box.minZ + 0.001);
      int maxZ = Mth.floor(box.maxZ - 0.001);
      int startY = Mth.floor(player.getY() + 1.0);
      // Look a little further ahead when the fly speed is high, so a ceiling
      // is mined before the player rams into it at several blocks per tick.
      int endY = Mth.floor(
         player.getY() + PLAYER_HEIGHT + 1.1 + Math.min(3.0, this.verticalImpulse)
      );
      BlockPos best = null;
      double bestScore = Double.MAX_VALUE;
      for (int y = startY; y <= endY; y++) {
         for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
               BlockPos pos = new BlockPos(x, y, z);
               if (isPassableBlock(level, pos)) {
                  continue;
               }
               double score = (y - player.getY()) * 10.0
                  + Math.abs(x + 0.5 - player.getX())
                  + Math.abs(z + 0.5 - player.getZ());
               if (score < bestScore) {
                  bestScore = score;
                  best = pos;
               }
            }
         }
      }
      if (best != null) {
         BlockPos occluder = this.findOccluder(level, player, best);
         if (occluder != null) {
            best = occluder;
         }
      }
      return best;
   }

   private static Vec3 waypointVec(BlockPos pos) {
      return new Vec3(pos.getX() + 0.5, pos.getY() + NODE_FEET_OFFSET_Y, pos.getZ() + 0.5);
   }

   private NavState blocked(Minecraft mc, LocalPlayer player) {
      this.hover(mc, player, true);
      return this.setNav(NavState.BLOCKED);
   }

   private NavState setNav(NavState state) {
      this.navState = state;
      return state;
   }

   // ------------------------------------------------------------------ 3D A* Voxel Pathfinder

   private List<BlockPos> computeAStarPath(
      ClientLevel level, LocalPlayer player, Vec3 target, boolean allowMine, boolean avoidThreats
   ) {
      BlockPos start = player.blockPosition();
      Vec3 delta = target.subtract(player.position());
      double dist = delta.length();
      Vec3 subGoalVec = dist > ASTAR_SUBGOAL_DIST
         ? player.position().add(delta.scale(ASTAR_SUBGOAL_DIST / dist))
         : target;
      BlockPos goal = resolveGoalBlock(level, player, BlockPos.containing(subGoalVec), allowMine);

      if (start.equals(goal)) {
         return List.of(goal);
      }

      List<AABB> threatBoxes = avoidThreats ? collectThreatBoxes(level, player) : Collections.emptyList();

      int cap = MAX_ASTAR_NODES + 16;
      long[] nodePos = new long[cap];
      double[] gScore = new double[cap];
      double[] fScore = new double[cap];
      int[] parent = new int[cap];
      boolean[] closed = new boolean[cap];

      // Open-addressed hash table mapping BlockPos.asLong() -> node index (1-based; 0 = empty)
      int tableSize = 4096;
      int tableMask = tableSize - 1;
      long[] tableKeys = new long[tableSize];
      int[] tableVals = new int[tableSize];

      // Binary min-heap of node indices ordered by fScore
      int[] heap = new int[cap];
      int heapSize = 0;

      int nodeCount = 1;
      long startLong = start.asLong();
      nodePos[0] = startLong;
      gScore[0] = 0.0;
      fScore[0] = heuristic(start.getX(), start.getY(), start.getZ(), goal.getX(), goal.getY(), goal.getZ());
      parent[0] = -1;

      int slot = hashLong(startLong) & tableMask;
      tableKeys[slot] = startLong;
      tableVals[slot] = 1; // index 0 + 1

      heap[heapSize++] = 0;

      int bestIdx = 0;
      double bestH = fScore[0];

      int gx = goal.getX();
      int gy = goal.getY();
      int gz = goal.getZ();

      // 6 cardinal + 4 horizontal diagonal offsets
      final int[] dxArr = {1, -1, 0, 0, 0, 0, 1, 1, -1, -1};
      final int[] dyArr = {0, 0, 1, -1, 0, 0, 0, 0, 0, 0};
      final int[] dzArr = {0, 0, 0, 0, 1, -1, 1, -1, 1, -1};

      int expansions = 0;
      while (heapSize > 0 && expansions < MAX_ASTAR_NODES && nodeCount < cap - 12) {
         // Pop min from heap
         int current = heap[0];
         heapSize--;
         if (heapSize > 0) {
            int moved = heap[heapSize];
            double movedF = fScore[moved];
            int idx = 0;
            int half = heapSize >>> 1;
            while (idx < half) {
               int left = (idx << 1) + 1;
               int right = left + 1;
               int smaller = left;
               if (right < heapSize && fScore[heap[right]] < fScore[heap[left]]) {
                  smaller = right;
               }
               if (movedF <= fScore[heap[smaller]]) {
                  break;
               }
               heap[idx] = heap[smaller];
               idx = smaller;
            }
            heap[idx] = moved;
         }

         if (closed[current]) {
            continue;
         }
         closed[current] = true;
         expansions++;

         long cLong = nodePos[current];
         int cx = BlockPos.getX(cLong);
         int cy = BlockPos.getY(cLong);
         int cz = BlockPos.getZ(cLong);

         if (cx == gx && cy == gy && gz == gz) {
            bestIdx = current;
            break;
         }

         double hNow = heuristic(cx, cy, cz, gx, gy, gz);
         if (hNow < bestH) {
            bestH = hNow;
            bestIdx = current;
         }

         for (int dir = 0; dir < 10; dir++) {
            int nx = cx + dxArr[dir];
            int ny = cy + dyArr[dir];
            int nz = cz + dzArr[dir];
            if (ny < 4 || ny > 250) {
               continue;
            }

            double stepCost = this.stepTransitionCost(
               level, player, cx, cy, cz, nx, ny, nz, dir >= 6, allowMine, threatBoxes, gx, gy, gz
            );
            if (stepCost < 0.0) {
               continue;
            }

            long nLong = BlockPos.asLong(nx, ny, nz);
            int hSlot = hashLong(nLong) & tableMask;
            int existing = 0;
            while (true) {
               int val = tableVals[hSlot];
               if (val == 0) {
                  break;
               }
               if (tableKeys[hSlot] == nLong) {
                  existing = val;
                  break;
               }
               hSlot = (hSlot + 1) & tableMask;
            }

            double tentativeG = gScore[current] + stepCost;
            int nIdx;
            if (existing == 0) {
               if (nodeCount >= cap) {
                  continue;
               }
               nIdx = nodeCount++;
               nodePos[nIdx] = nLong;
               gScore[nIdx] = tentativeG;
               double nh = heuristic(nx, ny, nz, gx, gy, gz);
               fScore[nIdx] = tentativeG + nh * 1.15;
               parent[nIdx] = current;
               tableKeys[hSlot] = nLong;
               tableVals[hSlot] = nIdx + 1;
            } else {
               nIdx = existing - 1;
               if (closed[nIdx] || tentativeG >= gScore[nIdx]) {
                  continue;
               }
               gScore[nIdx] = tentativeG;
               double nh = heuristic(nx, ny, nz, gx, gy, gz);
               fScore[nIdx] = tentativeG + nh * 1.15;
               parent[nIdx] = current;
            }

            // Push onto heap
            int up = heapSize++;
            double nf = fScore[nIdx];
            while (up > 0) {
               int p = (up - 1) >>> 1;
               if (nf >= fScore[heap[p]]) {
                  break;
               }
               heap[up] = heap[p];
               up = p;
            }
            heap[up] = nIdx;
         }
      }

      if (bestIdx <= 0) {
         // Fallback if boxed in and allowMine is true: step 1 block towards goal
         if (allowMine) {
            BlockPos step = start.offset(
               Integer.compare(gx, start.getX()),
               Integer.compare(gy, start.getY()),
               Integer.compare(gz, start.getZ())
            );
            return List.of(step, goal);
         }
         return null;
      }

      List<BlockPos> result = new ArrayList<>();
      int cur = bestIdx;
      while (cur > 0) {
         result.add(BlockPos.of(nodePos[cur]));
         cur = parent[cur];
      }
      Collections.reverse(result);
      return result;
   }

   private static int hashLong(long key) {
      long h = key * 0x9E3779B97F4A7C15L;
      return (int)(h ^ (h >>> 32));
   }

   private static double heuristic(int x1, int y1, int z1, int x2, int y2, int z2) {
      double dx = x1 - x2;
      double dy = y1 - y2;
      double dz = z1 - z2;
      return Math.sqrt(dx * dx + dy * dy + dz * dz);
   }

   private double stepTransitionCost(
      ClientLevel level,
      LocalPlayer player,
      int cx, int cy, int cz,
      int nx, int ny, int nz,
      boolean diagonal,
      boolean allowMine,
      List<AABB> threatBoxes,
      int gx, int gy, int gz
   ) {
      if (diagonal) {
         // Diagonals are only allowed in completely open air (including both adjacent cardinal columns).
         if (!isPassableColumn(level, nx, ny, nz)
            || !isPassableColumn(level, cx, cy, nz)
            || !isPassableColumn(level, nx, cy, cz)) {
            return -1.0;
         }
         return 1.414;
      }

      double cost = (ny != cy) ? 1.15 : 1.0;

      // Check feet and head at destination (nx, ny, nz) and (nx, ny + 1, nz)
      double c0 = blockStepCost(level, player, new BlockPos(nx, ny, nz), allowMine);
      if (c0 < 0.0) {
         return -1.0;
      }
      double c1 = blockStepCost(level, player, new BlockPos(nx, ny + 1, nz), allowMine);
      if (c1 < 0.0) {
         return -1.0;
      }
      cost += c0 + c1;

      // Vertical step requires head/foot transition clearance
      if (ny > cy) {
         double cTop = blockStepCost(level, player, new BlockPos(cx, cy + 2, cz), allowMine);
         if (cTop < 0.0) {
            return -1.0;
         }
         cost += cTop;
      } else if (ny < cy) {
         double cBot = blockStepCost(level, player, new BlockPos(cx, cy - 1, cz), allowMine);
         if (cBot < 0.0) {
            return -1.0;
         }
         cost += cBot;
      }

      // Slight clearance preference in open air so paths stay centered in doorways and away from walls
      if (c0 == 0.0 && c1 == 0.0) {
         if (!isPassableBlock(level, new BlockPos(nx + 1, ny, nz))
            || !isPassableBlock(level, new BlockPos(nx - 1, ny, nz))
            || !isPassableBlock(level, new BlockPos(nx, ny, nz + 1))
            || !isPassableBlock(level, new BlockPos(nx, ny, nz - 1))) {
            cost += 0.25;
         }
      }

      // Avoid Shulker bounding boxes unless right at the goal
      if (!threatBoxes.isEmpty() && Math.abs(nx - gx) + Math.abs(ny - gy) + Math.abs(nz - gz) > 2) {
         AABB col = new AABB(nx + 0.15, ny, nz + 0.15, nx + 0.85, ny + 1.8, nz + 0.85);
         for (AABB threat : threatBoxes) {
            if (threat.intersects(col)) {
               cost += 45.0;
               break;
            }
         }
      }

      return cost;
   }

   /** Returns 0 for passable air, positive mining penalty for mineable block, or -1 if impassable. */
   private static double blockStepCost(ClientLevel level, LocalPlayer player, BlockPos pos, boolean allowMine) {
      if (isPassableBlock(level, pos)) {
         return 0.0;
      }
      if (!allowMine || !isMineableBlock(level, player, pos)) {
         return -1.0;
      }
      BlockState state = level.getBlockState(pos);
      if (state.is(Blocks.OBSIDIAN) || state.is(Blocks.CRYING_OBSIDIAN)) {
         return 160.0;
      }
      if (state.is(Blocks.CHEST) || state.is(Blocks.ENDER_CHEST) || state.getBlock() instanceof ShulkerBoxBlock) {
         return 65.0;
      }
      float hardness = 1.5F;
      try {
         hardness = Math.max(0.1F, state.getDestroySpeed(level, pos));
      } catch (Throwable ignored) {
      }
      return 4.5 + Math.min(80.0, hardness * 4.0);
   }

   private static BlockPos resolveGoalBlock(ClientLevel level, LocalPlayer player, BlockPos rawGoal, boolean allowMine) {
      if (isPassableColumn(level, rawGoal.getX(), rawGoal.getY(), rawGoal.getZ())) {
         return rawGoal;
      }
      if (allowMine
         && isMineableOrPassable(level, player, rawGoal)
         && isMineableOrPassable(level, player, rawGoal.above())
         && !level.getBlockState(rawGoal).is(Blocks.OBSIDIAN)) {
         return rawGoal;
      }
      // Search nearby for a passable 1x2 column
      for (int r = 1; r <= 3; r++) {
         for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
               for (int dz = -r; dz <= r; dz++) {
                  BlockPos cand = rawGoal.offset(dx, dy, dz);
                  if (isPassableColumn(level, cand.getX(), cand.getY(), cand.getZ())) {
                     return cand;
                  }
               }
            }
         }
      }
      return rawGoal;
   }

   private static boolean isMineableOrPassable(ClientLevel level, LocalPlayer player, BlockPos pos) {
      return isPassableBlock(level, pos) || isMineableBlock(level, player, pos);
   }

   // ------------------------------------------------------------------ Swept AABB & Blocker Detection

   /**
    * True when the player's full {@code 0.68 x 1.84} bounding box can move in a
    * straight line from {@code from} to {@code to} (up to {@code maxDist}) without
    * intersecting any solid block or hostile Shulker.
    */
   public boolean isBoxSweepClear(
      ClientLevel level, LocalPlayer player, Vec3 from, Vec3 to, double maxDist, boolean checkThreats
   ) {
      Vec3 delta = to.subtract(from);
      double length = delta.length();
      if (length < 0.05) {
         return true;
      }
      double checkLen = Math.min(length, maxDist);
      Vec3 dir = delta.scale(1.0 / length);

      if (checkThreats) {
         Vec3 eyeFrom = from.add(0.0, 1.62, 0.0);
         Vec3 eyeTo = from.add(dir.scale(checkLen)).add(0.0, 1.62, 0.0);
         if (findThreat(level, player, eyeFrom, eyeTo) != null) {
            return false;
         }
      }

      int steps = Math.max(1, (int)Math.ceil(checkLen / 0.45));
      for (int i = 1; i <= steps; i++) {
         double d = Math.min(checkLen, i * (checkLen / steps));
         Vec3 p = from.add(dir.scale(d));
         AABB box = playerBoxAt(p);
         int minX = Mth.floor(box.minX);
         int maxX = Mth.floor(box.maxX);
         int minY = Mth.floor(box.minY);
         int maxY = Mth.floor(box.maxY);
         int minZ = Mth.floor(box.minZ);
         int maxZ = Mth.floor(box.maxZ);
         for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
               for (int z = minZ; z <= maxZ; z++) {
                  if (boxCollidesWithBlock(level, box, new BlockPos(x, y, z))) {
                     return false;
                  }
               }
            }
         }
      }
      return true;
   }

   private static AABB playerBoxAt(Vec3 feet) {
      return new AABB(
         feet.x - PLAYER_HALF_WIDTH,
         feet.y + 0.02,
         feet.z - PLAYER_HALF_WIDTH,
         feet.x + PLAYER_HALF_WIDTH,
         feet.y + PLAYER_HEIGHT,
         feet.z + PLAYER_HALF_WIDTH
      );
   }

   private static boolean boxCollidesWithBlock(ClientLevel level, AABB box, BlockPos pos) {
      try {
         if (level.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false) == null) {
            return false;
         }
         BlockState state = level.getBlockState(pos);
         if (state.isAir()) {
            return false;
         }
         VoxelShape shape = state.getCollisionShape(level, pos);
         if (shape.isEmpty()) {
            return false;
         }
         return box.intersects(shape.bounds().move(pos));
      } catch (Throwable ignored) {
         return false;
      }
   }

   public static boolean isPassableColumn(ClientLevel level, int x, int y, int z) {
      return isPassableBlock(level, new BlockPos(x, y, z))
         && isPassableBlock(level, new BlockPos(x, y + 1, z));
   }

   public static boolean isPassableBlock(ClientLevel level, BlockPos pos) {
      try {
         if (level.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false) == null) {
            return true;
         }
         BlockState state = level.getBlockState(pos);
         return state.isAir() || state.getCollisionShape(level, pos).isEmpty();
      } catch (Throwable ignored) {
         return true;
      }
   }

   public static boolean isMineableBlock(ClientLevel level, LocalPlayer player, BlockPos pos) {
      try {
         if (level.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false) == null) {
            return false;
         }
         BlockState state = level.getBlockState(pos);
         if (state.isAir() || !state.getFluidState().isEmpty()) {
            return false;
         }
         if (state.is(Blocks.BEDROCK)
            || state.is(Blocks.END_PORTAL_FRAME)
            || state.is(Blocks.END_PORTAL)
            || state.is(Blocks.END_GATEWAY)
            || state.is(Blocks.BARRIER)) {
            return false;
         }
         return state.getDestroySpeed(level, pos) >= 0.0F;
      } catch (Throwable ignored) {
         return false;
      }
   }

   /**
    * Find the immediate solid block that must be mined so the player's full
    * {@code 0.68 x 1.84} AABB can advance from {@code player.position()} to
    * {@code waypointVec}. Also checks line-of-sight occlusion so if a closer
    * block is in front of the chosen block, the closer block is mined first.
    */
   private BlockPos findNextBlockToMine(
      ClientLevel level, LocalPlayer player, BlockPos nodePos, Vec3 waypointVec
   ) {
      Vec3 from = player.position();
      Vec3 delta = waypointVec.subtract(from);
      double dist = delta.length();
      double sweepDist = Math.min(Math.max(dist, 0.5), 3.6);
      Vec3 dir = dist > 0.001 ? delta.scale(1.0 / dist) : Vec3.ZERO;

      List<BlockPos> candidates = new ArrayList<>();
      int steps = Math.max(1, (int)Math.ceil(sweepDist / 0.35));
      for (int i = 0; i <= steps; i++) {
         double d = i * (sweepDist / steps);
         Vec3 p = from.add(dir.scale(d));
         AABB box = playerBoxAt(p);
         int minX = Mth.floor(box.minX);
         int maxX = Mth.floor(box.maxX);
         int minY = Mth.floor(box.minY);
         int maxY = Mth.floor(box.maxY);
         int minZ = Mth.floor(box.minZ);
         int maxZ = Mth.floor(box.maxZ);
         for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
               for (int z = minZ; z <= maxZ; z++) {
                  BlockPos pos = new BlockPos(x, y, z);
                  if (boxCollidesWithBlock(level, box, pos) && !candidates.contains(pos)) {
                     candidates.add(pos);
                  }
               }
            }
         }
         // Stop at the first frontier of colliding blocks so we don't pick blocks behind the wall
         if (!candidates.isEmpty() && i > 0) {
            break;
         }
      }

      // Also check the target node's 1x2 column if we are close to it
      if (candidates.isEmpty() && dist <= 3.8) {
         if (!isPassableBlock(level, nodePos.above())) {
            candidates.add(nodePos.above());
         }
         if (!isPassableBlock(level, nodePos)) {
            candidates.add(nodePos);
         }
      }

      if (candidates.isEmpty()) {
         return null;
      }

      Vec3 eye = player.getEyePosition();
      BlockPos best = null;
      double bestScore = Double.MAX_VALUE;
      int playerFeetY = player.blockPosition().getY();

      for (BlockPos pos : candidates) {
         if (!isMineableBlock(level, player, pos)) {
            continue;
         }
         double d = eye.distanceTo(Vec3.atCenterOf(pos));
         // Prefer mining upper/eye-level blocks slightly before floor blocks so line of sight stays open
         double bias = (pos.getY() >= playerFeetY + 1) ? -0.3 : 0.0;
         // Strongly avoid breaking chests/obsidian if an adjacent block can be broken first
         BlockState st = level.getBlockState(pos);
         if (st.is(Blocks.OBSIDIAN)) {
            bias += 20.0;
         } else if (st.is(Blocks.CHEST)) {
            bias += 10.0;
         }
         double score = d + bias;
         if (score < bestScore) {
            bestScore = score;
            best = pos;
         }
      }

      if (best == null) {
         return candidates.get(0);
      }

      // If another mineable block occludes the ray from eye to `best`, mine the occluder first!
      BlockPos occluder = this.findOccluder(level, player, best);
      if (occluder != null) {
         return occluder;
      }
      return best;
   }

   /**
    * Check if the ray from {@code player.getEyePosition()} to {@code targetPos}
    * hits a closer mineable block first.
    */
   private BlockPos findOccluder(ClientLevel level, LocalPlayer player, BlockPos targetPos) {
      Vec3 eye = player.getEyePosition();
      Vec3 aim = findBestAimPoint(level, player, targetPos);
      try {
         BlockHitResult hit = level.clip(
            new ClipContext(eye, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player)
         );
         if (hit.getType() == HitResult.Type.BLOCK) {
            BlockPos hitPos = hit.getBlockPos();
            if (!hitPos.equals(targetPos) && isMineableBlock(level, player, hitPos)) {
               return hitPos;
            }
         }
      } catch (Throwable ignored) {
      }
      return null;
   }

   private BlockPos findOccluderOrCloserBlocker(ClientLevel level, LocalPlayer player, BlockPos currentMining) {
      return this.findOccluder(level, player, currentMining);
   }

   /** Find a point on {@code pos} that has direct unobstructed line of sight from the player's eyes. */
   private static Vec3 findBestAimPoint(ClientLevel level, LocalPlayer player, BlockPos pos) {
      Vec3 eye = player.getEyePosition();
      Vec3 center = Vec3.atCenterOf(pos);
      try {
         VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
         if (!shape.isEmpty()) {
            center = shape.bounds().move(pos).getCenter();
         }
      } catch (Throwable ignored) {
      }

      // Candidate aim points: center and 6 face insets towards the player
      double[][] offsets = {
         {0.0, 0.0, 0.0},
         {0.0, 0.35, 0.0},
         {0.0, -0.35, 0.0},
         {0.35, 0.0, 0.0},
         {-0.35, 0.0, 0.0},
         {0.0, 0.0, 0.35},
         {0.0, 0.0, -0.35}
      };
      Vec3 bestFallback = center;
      double bestDistSq = Double.MAX_VALUE;
      for (double[] off : offsets) {
         Vec3 pt = new Vec3(pos.getX() + 0.5 + off[0], pos.getY() + 0.5 + off[1], pos.getZ() + 0.5 + off[2]);
         double dSq = eye.distanceToSqr(pt);
         if (dSq < bestDistSq) {
            bestDistSq = dSq;
            bestFallback = pt;
         }
         try {
            BlockHitResult hit = level.clip(
               new ClipContext(eye, pt, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player)
            );
            if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos)) {
               return pt;
            }
         } catch (Throwable ignored) {
         }
      }
      return bestFallback;
   }

   private static Direction bestFaceDirection(LocalPlayer player, BlockPos pos) {
      Vec3 eye = player.getEyePosition();
      Vec3 delta = eye.subtract(Vec3.atCenterOf(pos));
      return Direction.getApproximateNearest(delta.x, delta.y, delta.z);
   }

   // ------------------------------------------------------------------ Hostile Threat Detection

   /**
    * Nearest Shulker whose hitbox intersects the unobstructed ray from the player's
    * eyes to {@code point}. Solid walls stop the ray so Shulkers behind closed
    * walls are never falsely treated as blocking the current segment.
    */
   public static Entity findThreat(ClientLevel level, LocalPlayer player, Vec3 point) {
      if (level == null || player == null || point == null) {
         return null;
      }
      return findThreat(level, player, player.getEyePosition(), point);
   }

   public static Entity findThreat(ClientLevel level, LocalPlayer player, Vec3 from, Vec3 to) {
      if (level == null || player == null || from == null || to == null) {
         return null;
      }
      Vec3 delta = to.subtract(from);
      double length = delta.length();
      if (length < 0.5) {
         return null;
      }
      Vec3 end = length > THREAT_PROBE_DISTANCE
         ? from.add(delta.scale(THREAT_PROBE_DISTANCE / length))
         : to;

      // Clip against solid blocks first so we never detect a Shulker through a solid ship wall!
      try {
         BlockHitResult blockHit = level.clip(
            new ClipContext(from, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)
         );
         if (blockHit.getType() == HitResult.Type.BLOCK) {
            end = blockHit.getLocation();
         }
      } catch (Throwable ignored) {
      }

      Entity best = null;
      double bestDistance = Double.MAX_VALUE;
      for (Entity entity : level.entitiesForRendering()) {
         if (entity == player || entity.isRemoved() || !entity.isAlive()) {
            continue;
         }
         if (!(entity instanceof Shulker shulker)) {
            continue;
         }
         var hit = shulker.getBoundingBox().inflate(THREAT_PADDING).clip(from, end);
         if (hit.isEmpty()) {
            continue;
         }
         double distance = hit.get().distanceToSqr(from);
         if (distance < bestDistance) {
            best = shulker;
            bestDistance = distance;
         }
      }
      return best;
   }

   private static List<AABB> collectThreatBoxes(ClientLevel level, LocalPlayer player) {
      List<AABB> boxes = new ArrayList<>();
      Vec3 pos = player.position();
      for (Entity entity : level.entitiesForRendering()) {
         if (entity instanceof Shulker shulker && entity.isAlive() && !entity.isRemoved()) {
            if (entity.position().distanceToSqr(pos) <= 36.0 * 36.0) {
               boxes.add(shulker.getBoundingBox().inflate(0.6));
            }
         }
      }
      return boxes;
   }

   public void clearEntityState() {
      this.clearPath();
      this.setNav(NavState.CLEAR);
   }

   public String miningDiagnostics() {
      return "挖掘方块=" + (this.miningPos == null ? "无" : this.miningPos)
         + " 贴身阻挡=" + this.miningFootBlocker
         + " 脚下阻挡=" + (this.lastColumnBlocker == null ? "无" : this.lastColumnBlocker)
         + " 列偏移=" + String.format("%.2f", this.lastColumnOffset)
         + " 路径节点=" + (this.currentPath == null ? 0 : (this.pathIndex + "/" + this.currentPath.size()))
         + " 准星偏离=" + this.offAimTicks + " tick";
   }

   // ------------------------------------------------------------------ Mining Execution

   private void startMiningBlock(LocalPlayer player, ClientLevel level, BlockPos blocker, long now) {
      if (blocker.equals(this.miningPos)) {
         return;
      }
      BlockState state = level.getBlockState(blocker);
      equipForMining(player, state);
      int feetY = player.blockPosition().getY();
      double dx = blocker.getX() + 0.5 - player.getX();
      double dz = blocker.getZ() + 0.5 - player.getZ();
      this.miningFootBlocker = blocker.getY() <= feetY + 1 && Math.hypot(dx, dz) <= 2.5;
      this.miningPos = blocker;
      this.miningDir = bestFaceDirection(player, blocker);
      this.miningAimPoint = findBestAimPoint(level, player, blocker);
      this.miningStartMs = now;
      this.miningStarted = false;
      this.offAimTicks = 0;
   }

   /**
    * Break {@link #miningPos} cleanly without double-ticking {@code destroyProgress}.
    */
   private NavState tickMining(Minecraft mc, ClientLevel level, LocalPlayer player, long now) {
      BlockPos pos = this.miningPos;
      if (pos == null || mc.gameMode == null) {
         return NavState.FAILED;
      }
      // Keep vanilla's ABORT_DESTROY_BLOCK away for this break session, even
      // when an entity sits on the crosshair. Refreshed every mining tick.
      FlyController.destroyProtectedUntilMs = System.currentTimeMillis() + 1000L;
      if (isPassableBlock(level, pos)) {
         this.stopMining(mc);
         return NavState.DONE;
      }
      if (now - this.miningStartMs > MINE_TIMEOUT_MS) {
         this.stopMining(mc);
         return NavState.FAILED;
      }

      Vec3 aim = findBestAimPoint(level, player, pos);
      this.miningAimPoint = aim;
      this.miningDir = bestFaceDirection(player, pos);
      Vec3 eye = player.getEyePosition();
      double distToAim = eye.distanceTo(aim);

      if (distToAim > MINE_REACH) {
         this.offAimTicks = 0;
         if (mc.options != null) {
            mc.options.keyAttack.setDown(false);
         }
         Vec3 toEye = eye.subtract(aim);
         double len = Math.max(0.001, toEye.length());
         Vec3 standOff = aim.add(toEye.scale(MINE_APPROACH_DIST / len)).subtract(0.0, 1.5, 0.0);
         this.steerRaw(mc, player, standOff, true, LOCAL_VERTICAL_DEADZONE);
         return NavState.APPROACH_MINE;
      }

      // Within reach: brake movement so the player doesn't slide past or into the block while mining
      this.ensureFlight(player);
      this.brakeInPlace(mc, player);
      aimAtVec(player, aim);

      Direction direction = this.miningDir == null ? Direction.UP : this.miningDir;

      // Equip best tool BEFORE starting the break session (changing slots mid-break resets progress)
      if (!this.miningStarted && !mc.gameMode.isDestroying()) {
         equipForMining(player, level.getBlockState(pos));
      }

      // Ensure mc.hitResult points to the block we are mining so Minecraft.continueAttack(true)
      // does not call stopDestroyBlock() on the next tick.
      HitResult crosshair = mc.hitResult;
      boolean entityOnCrosshair = crosshair != null && crosshair.getType() == HitResult.Type.ENTITY;
      if (!entityOnCrosshair) {
         mc.hitResult = new BlockHitResult(aim, direction, pos, false);
      }

      boolean vanillaHandlesTick = mc.mouseHandler != null
         && mc.mouseHandler.isMouseGrabbed()
         && !player.isUsingItem()
         && !entityOnCrosshair;

      if (mc.options != null) {
         mc.options.keyAttack.setDown(vanillaHandlesTick);
         if (!useKeyInUse(mc)) {
            mc.options.keyUse.setDown(false);
         }
      }

      try {
         if (vanillaHandlesTick) {
            // When mouse is grabbed and no screen is open, Minecraft.continueAttack(true)
            // already calls gameMode.continueDestroyBlock() once per tick during handleKeybinds().
            // Only call startDestroyBlock on the very first tick if not yet destroying.
            if (!this.miningStarted && !mc.gameMode.isDestroying()) {
               mc.gameMode.startDestroyBlock(pos, direction);
               player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            }
            this.miningStarted = true;
         } else {
            // Screen is open (e.g., chat) or mouse is unfocused or an entity is in the way:
            // drive the break directly once per tick.
            if (!mc.gameMode.isDestroying()) {
               mc.gameMode.startDestroyBlock(pos, direction);
            } else {
               mc.gameMode.continueDestroyBlock(pos, direction);
            }
            player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            this.miningStarted = true;
         }
      } catch (Throwable ignored) {
         this.stopMining(mc);
         return NavState.FAILED;
      }
      return NavState.MINING;
   }

   /** Idempotent cleanup: abort any pending block break and release the attack key. */
   private void stopMining(Minecraft mc) {
      // Cleared BEFORE the explicit stopDestroyBlock() below, so our own abort
      // is still delivered to the server.
      FlyController.destroyProtectedUntilMs = 0L;
      if (mc != null && mc.options != null) {
         mc.options.keyAttack.setDown(false);
      }
      if (this.miningStarted && mc != null && mc.gameMode != null) {
         try {
            if (mc.gameMode.isDestroying()) {
               mc.gameMode.stopDestroyBlock();
            }
         } catch (Throwable ignored) {
         }
      }
      this.miningPos = null;
      this.miningDir = null;
      this.miningAimPoint = null;
      this.miningStarted = false;
      this.miningFootBlocker = false;
      this.miningStartMs = 0L;
      this.offAimTicks = 0;
   }

   private static void aimAtVec(LocalPlayer player, Vec3 target) {
      double dx = target.x - player.getX();
      double dz = target.z - player.getZ();
      double dy = target.y - player.getEyeY();
      double horizontal = Math.sqrt(dx * dx + dz * dz);
      player.setYRot((float)(Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
      player.setXRot((float)Mth.clamp(-Math.toDegrees(Math.atan2(dy, Math.max(horizontal, 0.001))), -90.0F, 90.0F));
   }

   // ------------------------------------------------------------------ Flight Primitives & Damping

   public void steer(Minecraft mc, LocalPlayer player, Vec3 target, boolean slow) {
      this.steer(mc, player, target, slow, slow ? LOCAL_VERTICAL_DEADZONE : CRUISE_VERTICAL_DEADZONE);
   }

   public void steer(Minecraft mc, LocalPlayer player, Vec3 target, boolean slow, double verticalDeadzone) {
      this.stopMining(mc);
      this.steerRaw(mc, player, target, slow, verticalDeadzone);
   }

   public boolean isMining() {
      return this.miningPos != null;
   }

   /**
    * Fly onto a dropped item for pickup.
    */
   public boolean approachForPickup(Minecraft mc, LocalPlayer player, Vec3 itemPos) {
      if (player == null || itemPos == null) {
         return false;
      }
      double dx = itemPos.x - player.getX();
      double dz = itemPos.z - player.getZ();
      double dy = itemPos.y - player.getY();
      double horizontal = Math.sqrt(dx * dx + dz * dz);
      if (horizontal <= 0.7 && Math.abs(dy) <= 0.9) {
         this.hover(mc, player, false);
         return true;
      }
      this.stopMining(mc);
      Vec3 target = new Vec3(itemPos.x, itemPos.y - 0.25, itemPos.z);
      this.steerRaw(mc, player, target, true, 0.2);
      return false;
   }

   /**
    * Velocity-damped 3D steering:
    * - Prevents 180° yaw spinning when horizontally aligned and climbing/descending.
    * - Actively counter-strafes (`keyLeft` / `keyRight`) to cancel lateral drift in creative flight.
    * - Modulates `keyUp` / `keyDown` to prevent overshooting tight waypoints.
    */
   private void steerRaw(Minecraft mc, LocalPlayer player, Vec3 target, boolean slow, double verticalDeadzone) {
      this.ensureFlight(player);
      double dx = target.x - player.getX();
      double dz = target.z - player.getZ();
      double dyEye = target.y + 1.5 - player.getEyeY();
      double horiz = Math.sqrt(dx * dx + dz * dz);

      // Only update yaw when horizontal distance is meaningful so vertical climbs/descents never spin 180°
      if (horiz > 0.18) {
         float yaw = (float)(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
         player.setYRot(yaw);
      }
      double pitch = -Math.toDegrees(Math.atan2(dyEye, Math.max(horiz, 0.25)));
      player.setXRot((float)Mth.clamp(pitch, -PITCH_LIMIT, PITCH_LIMIT));

      if (mc != null && mc.options != null) {
         mc.options.keyAttack.setDown(false);
         if (!useKeyInUse(mc)) {
            mc.options.keyUse.setDown(false);
         }

         // Decompose current horizontal velocity into player's local yaw frame
         Vec3 vel = player.getDeltaMovement();
         double yawRad = Math.toRadians(player.getYRot());
         double forwardX = -Math.sin(yawRad);
         double forwardZ = Math.cos(yawRad);
         double rightX = -forwardZ;
         double rightZ = forwardX;

         double forwardVel = vel.x * forwardX + vel.z * forwardZ;
         double rightVel = vel.x * rightX + vel.z * rightZ;

         if (horiz <= 0.35) {
            // Horizontally at target: brake horizontal momentum while vertical keys adjust Y
            mc.options.keyUp.setDown(forwardVel < -0.05);
            mc.options.keyDown.setDown(forwardVel > 0.05);
            mc.options.keySprint.setDown(false);
         } else {
            boolean shouldBrake = slow && horiz < 1.4 && forwardVel > 0.22;
            mc.options.keyUp.setDown(!shouldBrake);
            mc.options.keyDown.setDown(shouldBrake);
            mc.options.keySprint.setDown(!slow && horiz > 6.0 && Math.abs(rightVel) < 0.18);
         }

         // Counter-strafe to cancel sideways skidding around corners
         mc.options.keyLeft.setDown(rightVel > 0.045);
         mc.options.keyRight.setDown(rightVel < -0.045);

         // Speed-adaptive vertical servo (see driveVertical). The old
         // "dy - 1.5 * vy" bang-bang controller assumed vanilla fly speed and
         // alternated jump/sneak forever once Tweakeroo multiplied it.
         this.driveVertical(mc, player, target.y, verticalDeadzone);
      }
   }

   /** Counter horizontal drift only; vertical keys are left to the caller. */
   private void brakeHorizontal(Minecraft mc, LocalPlayer player) {
      if (mc == null || mc.options == null) {
         return;
      }
      Vec3 vel = player.getDeltaMovement();
      double yawRad = Math.toRadians(player.getYRot());
      double forwardX = -Math.sin(yawRad);
      double forwardZ = Math.cos(yawRad);
      double rightX = -forwardZ;
      double rightZ = forwardX;

      double forwardVel = vel.x * forwardX + vel.z * forwardZ;
      double rightVel = vel.x * rightX + vel.z * rightZ;

      mc.options.keyUp.setDown(forwardVel < -0.06);
      mc.options.keyDown.setDown(forwardVel > 0.06);
      mc.options.keyLeft.setDown(rightVel > 0.06);
      mc.options.keyRight.setDown(rightVel < -0.06);
      mc.options.keySprint.setDown(false);
   }

   /** Actively brake horizontal & vertical drift using counter-inputs. */
   private void brakeInPlace(Minecraft mc, LocalPlayer player) {
      if (mc == null || mc.options == null) {
         return;
      }
      this.brakeHorizontal(mc, player);
      this.sampleVerticalSpeed(player);
      // ONE reverse pulse plus the natural 0.6 drag settles the vertical drift.
      // A fixed 0.08 threshold instead produces a stable jump/sneak 2-cycle of
      // amplitude ~0.56 * impulse whenever a mod raises the fly speed - that is
      // the "endless crouching at one height" bug. Scale the dead band with the
      // impulse so we never start that cycle in the first place.
      double vy = player.getDeltaMovement().y;
      double threshold = Math.max(0.12, 0.9 * this.verticalImpulse);
      int brakeDir = vy < -threshold ? 1 : (vy > threshold ? -1 : 0);
      mc.options.keyJump.setDown(brakeDir > 0);
      mc.options.keyShift.setDown(brakeDir < 0);
      this.verticalDirLatch = 0;
      this.recordVerticalInput(brakeDir);
   }

   /** Stop horizontal movement and stay airborne. */
   public void hover(Minecraft mc, LocalPlayer player, boolean levelPitch) {
      this.stopMining(mc);
      this.ensureFlight(player);
      this.brakeInPlace(mc, player);
      if (levelPitch) {
         player.setXRot(0.0F);
      }
   }

   /** Full key release, used on stop / abort / success. */
   public void release(Minecraft mc) {
      this.stopMining(mc);
      this.releaseMovementKeys(mc);
      if (mc != null && mc.options != null) {
         mc.options.keyAttack.setDown(false);
         if (!useKeyInUse(mc)) {
            mc.options.keyUse.setDown(false);
         }
      }
      this.clearPath();
      this.resetStuck();
      this.resetVerticalModel();
   }

   private void releaseMovementKeys(Minecraft mc) {
      if (mc == null || mc.options == null) {
         return;
      }
      mc.options.keyUp.setDown(false);
      mc.options.keyDown.setDown(false);
      mc.options.keyLeft.setDown(false);
      mc.options.keyRight.setDown(false);
      mc.options.keyJump.setDown(false);
      mc.options.keyShift.setDown(false);
      mc.options.keySprint.setDown(false);
      // No vertical input is being applied, so the next velocity sample must
      // not be attributed to a stale direction.
      this.lastVerticalDir = 0;
   }

   public void ensureFlight(LocalPlayer player) {
      Abilities abilities = player.getAbilities();
      abilities.mayfly = true;
      abilities.flying = true;
      player.fallDistance = 0.0;
   }

   /** Sample the position every {@value #STUCK_TICKS} ticks; true means "stuck". */
   public boolean checkStuck(LocalPlayer player) {
      if (this.miningPos != null) {
         // Actively breaking a block is not "stuck".
         this.record(player);
         this.stuckTicks = 0;
         return false;
      }
      if (!this.hasLastPos) {
         this.record(player);
         this.stuckTicks = 0;
         return false;
      }
      if (++this.stuckTicks < STUCK_TICKS) {
         return false;
      }
      double dx = player.getX() - this.lastX;
      double dy = player.getY() - this.lastY;
      double dz = player.getZ() - this.lastZ;
      double moved = Math.sqrt(dx * dx + dy * dy + dz * dz);
      this.record(player);
      this.stuckTicks = 0;
      return moved < STUCK_MOVE_THRESHOLD;
   }

   private void record(LocalPlayer player) {
      this.lastX = player.getX();
      this.lastY = player.getY();
      this.lastZ = player.getZ();
      this.hasLastPos = true;
   }

   public static void aimAt(LocalPlayer player, Entity target) {
      Vec3 center = target.getBoundingBox().getCenter();
      double dx = center.x - player.getX();
      double dz = center.z - player.getZ();
      double dy = center.y - player.getEyeY();
      double horiz = Math.sqrt(dx * dx + dz * dz);
      float yaw = (float)(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
      float pitch = (float)(-Math.toDegrees(Math.atan2(dy, Math.max(horiz, 0.001))));
      player.setYRot(yaw);
      player.setXRot(Mth.clamp(pitch, -90.0F, 90.0F));
   }
}
