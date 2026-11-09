package kaptainwutax.seedcrackerX.automine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import kaptainwutax.seedcrackerX.autoeat.AutoEatManager;
import kaptainwutax.seedcrackerX.command.ClientCommand;
import kaptainwutax.seedcrackerX.config.Config;
import kaptainwutax.seedcrackerX.elytra.ElytraHuntManager;
import kaptainwutax.seedcrackerX.elytra.FlyController;
import kaptainwutax.seedcrackerX.util.Compat;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 背包满时自动把鞘翅、矿物等物品存进背包内的潜影盒。
 *
 * <p>MC 26.3 原版不支持「在背包里直接打开潜影盒」（bundle 系列的容器点击交互只对
 * BundleItem 生效），所以这里走一条完全基于原版协议的链路，服务端不需要安装任何
 * 额外模组：</p>
 * <ol>
 *    <li>暂停自动挖矿 / 鞘翅收集推进 / 释放输入，把潜影盒拿在手上；</li>
 *    <li>在附近找一格不与玩家碰撞体积重叠的支撑方块放置潜影盒；</li>
 *    <li>非潜影盒手持状态右键打开盒子，服务器端下发 ShulkerBoxMenu；</li>
 *    <li>对背包中的鞘翅、矿物及可堆叠物品逐格 QUICK_MOVE（等同 shift 点击）存入盒子；</li>
 *    <li>关闭界面、先挖掉挡在玩家与盒子之间（或压在盒子上方）的方块，再挖掉盒子、把带内容的盒子捡回背包；</li>
 *    <li>恢复自动鞘翅收集或自动挖矿。</li>
 * </ol>
 *
 * <p>任何一步失败都会尝试把已经放在地上的盒子挖回来；实在拿不回来时会把坐标
 * 明确告诉玩家，避免静默丢东西。</p>
 */
public final class ShulkerAutoStore {
   public static final ShulkerAutoStore INSTANCE = new ShulkerAutoStore();
   private static final Logger LOGGER = LoggerFactory.getLogger("SeedCrackerX-ShulkerStore");

   private static final int TOTAL_TIMEOUT_TICKS = 1000;
   private static final int MAX_MOVES = 27;
   private static final int MAX_NON_ORE_MOVES = 8;
   private static final double MAX_REACH_SQ = 18.0;
   /** 清障阶段总上限：多个阻挡方块逐个开挖也不至于把整次存盒拖死。 */
   private static final int MAX_CLEAR_TICKS = 360;
   /** 单个阻挡方块挖不动（基岩 / 领地保护 / 黑曜石太慢）时的放弃上限。 */
   private static final int MAX_BLOCKER_TICKS = 220;
   /** 满包但存盒没能启动时，聊天提示的最短间隔（毫秒），避免刷屏。 */
   private static final long HINT_INTERVAL_MS = 10000L;
   /**
    * 存盒挖掘期间屏蔽原版 {@code stopDestroyBlock()}。
    * 26.3 的 {@code Minecraft.handleKeybinds()} 只要左键没按下就会每帧调用一次
    * {@code stopDestroyBlock()}（{@code continueAttack(false)} 分支），会把服务端
    * 的破坏进度反复清零。挖掘中持续续期，收尾时由 {@link #stopBreaking(Minecraft)}
    * 先清零再主动 ABORT。
    */
   private static volatile long destroyProtectedUntilMs;

   /** 供 {@code MultiPlayerGameModeMixin} 判断当前是否处于存盒挖掘会话。 */
   public static boolean isDestroyProtected() {
      return System.currentTimeMillis() < ShulkerAutoStore.destroyProtectedUntilMs;
   }

   private static void protectDestroy(long millis) {
      ShulkerAutoStore.destroyProtectedUntilMs = System.currentTimeMillis() + millis;
   }

   private static void clearDestroyProtection() {
      ShulkerAutoStore.destroyProtectedUntilMs = 0L;
   }
   private static final Set<String> ORE_DROP_ITEMS = Set.of(
      "diamond", "emerald", "lapis_lazuli", "redstone", "coal",
      "raw_iron", "raw_gold", "raw_copper",
      "raw_iron_block", "raw_gold_block", "raw_copper_block",
      "ancient_debris", "netherite_scrap",
      "diamond_ore", "deepslate_diamond_ore", "emerald_ore", "deepslate_emerald_ore",
      "lapis_ore", "deepslate_lapis_ore", "redstone_ore", "deepslate_redstone_ore",
      "coal_ore", "deepslate_coal_ore", "iron_ore", "deepslate_iron_ore",
      "gold_ore", "deepslate_gold_ore", "copper_ore", "deepslate_copper_ore",
      "nether_gold_ore", "nether_quartz_ore", "quartz", "flint", "gunpowder",
      "gold_nugget", "iron_nugget", "netherite_ingot", "iron_ingot", "gold_ingot",
      "copper_ingot", "diamond_block", "emerald_block", "lapis_block", "redstone_block",
      "coal_block", "iron_block", "gold_block"
   );
   private static final Set<String> NEVER_STORE = Set.of(
      "ender_chest", "bundle", "white_bundle", "orange_bundle", "magenta_bundle",
      "light_blue_bundle", "yellow_bundle", "lime_bundle", "pink_bundle", "gray_bundle",
      "light_gray_bundle", "cyan_bundle", "purple_bundle", "blue_bundle", "brown_bundle",
      "green_bundle", "red_bundle", "black_bundle", "firework_rocket", "totem_of_undying",
      "ender_pearl", "chorus_fruit"
   );
   private static final Set<String> JUNK_BLOCKS = Set.of(
      "purpur_block", "purpur_pillar", "purpur_stairs", "purpur_slab",
      "end_stone", "end_stone_bricks", "end_stone_brick_stairs", "end_stone_brick_slab", "end_stone_brick_wall",
      "magenta_stained_glass", "chorus_plant", "chorus_flower",
      "cobblestone", "cobbled_deepslate", "stone", "deepslate", "netherrack",
      "dirt", "gravel", "sand", "andesite", "diorite", "granite", "tuff", "basalt", "blackstone"
   );
   private static final Set<String> INTERACTIVE_SUPPORT_KEYWORDS = Set.of(
      "chest", "shulker_box", "barrel", "furnace", "smoker", "blast_furnace",
      "hopper", "dispenser", "dropper", "brewing_stand", "crafting_table",
      "anvil", "enchanting_table", "grindstone", "stonecutter", "smithing_table",
      "loom", "cartography_table", "fletching_table", "beacon", "bed", "door",
      "trapdoor", "fence_gate", "button", "lever", "note_block", "jukebox",
      "bell", "repeater", "comparator", "lectern", "chiseled_bookshelf",
      "crafter", "sign", "command_block", "respawn_anchor", "vault", "trial_spawner"
   );

   private enum State {
      IDLE, PREPARE, PLACE, WAIT_PLACE, OPEN_BOX, WAIT_OPEN, STORE,
      WAIT_CLOSE, CLEAR_OBSTRUCTION, BREAK, COLLECT
   }

   private State state = State.IDLE;
   private int ticks;
   private int totalTicks;
   private int cooldown;
   private int scanCounter;
   private BlockPos supportPos;
   private BlockPos placedPos;
   private int boxInvSlot = -1;
   private int placeHotbarSlot = -1;
   private boolean swappedFromMainInv;
   private int originalSelectedSlot;
   private boolean changedSelectedSlot;
   private int initialShulkerCount;
   private int moves;
   private int nonOreMoves;
   private boolean breakStarted;
   private boolean failed;
   /** 总超时只兜底回收一次，防止 abort→BREAK→下一 tick 又超时→abort 的死循环。 */
   private boolean timeoutRecovering;
   private final Set<Integer> attemptedSlots = new HashSet<>();
   /** 当前正在挖的方块（清障阶段为阻挡方块，BREAK 阶段为潜影盒本身）。 */
   private BlockPos breakTarget;
   private int breakTargetTicks;
   /** 挖不动的阻挡方块，避免在同一个基岩/受保护方块上空转到超时。 */
   private final Set<BlockPos> skippedBlockers = new HashSet<>();
   private Level startLevel;
   private long lastHintMs;
   private String lastHintReason;
   private long lastTriggerErrorMs;

   private ShulkerAutoStore() {
   }

   /** 存盒流程是否正在占用玩家操作（自动挖矿、自动收集鞘翅等其它自动化需要让路）。 */
   public boolean isBusy() {
      return this.state != State.IDLE;
   }

   /** 当前背包是否已满、且开启了自动存盒、且包内存在能够接收背包内可存物品的潜影盒。 */
   public boolean canStoreInventory(LocalPlayer player) {
      if (player == null || !Config.get().autoMineShulkerSave) {
         return false;
      }
      if (!this.inventoryFull(player)) {
         return false;
      }
      int box = this.findNonFullShulkerSlot(player);
      // 只有盒子却没有能腾出格子的物品时，存盒并不会让拾取继续；
      // 不能让鞘翅搜寻误以为「可以存盒」而继续跑下一个点。
      return box >= 0 && this.countStorable(player, box) > 0;
   }

   /**
    * 诊断「此刻为什么没有开始自动存盒」。返回 null 表示所有条件都已满足，随时可以开始。
    * 用于 /ov ch 状态、满包时的节流提示，以及鞘翅搜寻在背包满结束时的准确原因。
    */
   public String diagnose(Minecraft mc, LocalPlayer player) {
      if (player == null) {
         return "玩家不存在";
      }
      if (!Config.get().autoMineShulkerSave) {
         return "自动存盒开关已关闭（输入 /ov ch 开 开启）";
      }
      if (this.state != State.IDLE) {
         return "存盒流程正在进行中";
      }
      if (mc == null || mc.level == null || mc.gameMode == null || mc.options == null) {
         return "世界尚未加载";
      }
      if (this.cooldown > 0) {
         return "上一次存盒刚结束，冷却中（剩余 " + this.cooldown + " tick）";
      }
      if (Compat.screen(mc) != null) {
         return "当前打开了界面";
      }
      if (!(player.containerMenu instanceof InventoryMenu)) {
         return "当前不在背包界面";
      }
      if (AutoEatManager.INSTANCE.isBusy()) {
         return "自动进食进行中，稍后自动重试";
      }
      try {
         if (ElytraHuntManager.INSTANCE.isRunning() && !ElytraHuntManager.INSTANCE.allowsShulkerStore()) {
            return "鞘翅搜寻正在定位或挖掘，稍后自动重试";
         }
      } catch (Throwable ignored) {
      }
      if (!this.inventoryFull(player)) {
         return "背包还没满（还有 " + this.freeSlots(player) + " 个空格）";
      }
      int boxes = this.countShulkers(player);
      if (boxes == 0) {
         return "背包里没有潜影盒";
      }
      int box = this.findNonFullShulkerSlot(player);
      if (box < 0) {
         return "背包里的 " + boxes + " 个潜影盒都已装满";
      }
      if (this.countStorable(player, box) == 0) {
         return "背包里没有可自动存入的物品（工具、食物、装备、容器类不会存入）";
      }
      if (this.findPlacementSpot(mc.level, player) == null) {
         return "附近没有可放置潜影盒的安全空地（落到地面或开阔处会自动重试）";
      }
      return null;
   }

   /** /ov ch 状态 用的一行实时诊断。 */
   public String statusLine() {
      Minecraft mc = Minecraft.getInstance();
      LocalPlayer player = mc == null ? null : mc.player;
      int free = player == null ? -1 : this.freeSlots(player);
      int boxes = player == null ? 0 : this.countShulkers(player);
      int box = player == null ? -1 : this.findNonFullShulkerSlot(player);
      int storable = (player == null || box < 0) ? 0 : this.countStorable(player, box);
      String reason;
      try {
         reason = this.diagnose(mc, player);
      } catch (Throwable t) {
         reason = "诊断异常：" + t.getClass().getSimpleName();
      }
      return "开关=" + (Config.get().autoMineShulkerSave ? "开" : "关")
         + " / 空格=" + free + "/36"
         + " / 潜影盒=" + boxes + "（未满：" + (box >= 0 ? "有" : "无") + "）"
         + " / 可存格=" + storable
         + " / 流程=" + this.state
         + " / 挖掘目标=" + (this.breakTarget == null ? "无" : this.posText(this.breakTarget))
         + " / 跳过阻挡=" + this.skippedBlockers.size()
         + " / 冷却=" + this.cooldown + "tick"
         + " / 判定=" + (reason == null ? "条件齐备，可以存盒" : reason);
   }

   /**
    * 手动强制启动一次存盒：忽略开关、冷却、鞘翅搜寻/自动进食让路，
    * 但仍要求背包满、有未满潜影盒、有可存物品、附近能放置盒子。失败时把原因发到聊天。
    */
   public boolean forceStore() {
      if (this.state != State.IDLE) {
         this.warn("存盒流程正在进行中，请稍候。", ChatFormatting.YELLOW);
         return false;
      }

      Minecraft mc = Minecraft.getInstance();
      LocalPlayer player = mc == null ? null : mc.player;
      String reason;
      if (player == null) {
         reason = "玩家不存在";
      } else if (mc.level == null || mc.gameMode == null || mc.options == null) {
         reason = "世界尚未加载";
      } else if (Compat.screen(mc) != null) {
         reason = "当前打开了界面，请先关闭";
      } else if (!(player.containerMenu instanceof InventoryMenu)) {
         reason = "当前不在背包界面";
      } else if (!this.inventoryFull(player)) {
         reason = "背包还没满（还有 " + this.freeSlots(player) + " 个空格）";
      } else if (this.countShulkers(player) == 0) {
         reason = "背包里没有潜影盒";
      } else {
         int box = this.findNonFullShulkerSlot(player);
         if (box < 0) {
            reason = "背包里的潜影盒都已装满";
         } else if (this.countStorable(player, box) == 0) {
            reason = "背包里没有可自动存入的物品";
         } else {
            BlockPos support = this.findPlacementSpot(mc.level, player);
            if (support == null) {
               reason = "附近没有可放置潜影盒的安全空地，请移到地面或开阔处再试";
            } else {
               this.cooldown = 0;
               this.begin(mc, player, box, support);
               return true;
            }
         }
      }

      this.warn("无法开始存盒：" + reason, ChatFormatting.YELLOW);
      return false;
   }

   private int freeSlots(LocalPlayer player) {
      int n = 0;
      Inventory inv = player.getInventory();
      for (int i = 0; i < 36; i++) {
         if (inv.getItem(i).isEmpty()) {
            n++;
         }
      }

      return n;
   }

   /** 满包但没能存盒时，把具体原因节流发到聊天，避免再次「什么都没发生」。 */
   private void hint(String reason) {
      // 用户主动关掉开关时降低频率，避免刷屏；开关开启时的异常才需要 10 秒一次地提醒。
      long interval = Config.get().autoMineShulkerSave ? HINT_INTERVAL_MS : 60000L;
      long now = System.currentTimeMillis();
      if (now - this.lastHintMs < interval && Objects.equals(reason, this.lastHintReason)) {
         return;
      }

      this.lastHintMs = now;
      this.lastHintReason = reason;
      this.warn("背包已满但未自动存盒：" + reason, ChatFormatting.YELLOW);
   }

   /**
    * 立即尝试启动一次存盒流程（供鞘翅自动收集在拾取满包后或飞离途中直接调用，不等待 10 tick 节流）。
    *
    * @return 若已成功进入存盒状态机（或已在存盒中）返回 true
    */
   public boolean tryTriggerImmediate() {
      if (this.state != State.IDLE) {
         return true;
      }
      if (!Config.get().autoMineShulkerSave) {
         return false;
      }
      Minecraft mc = Minecraft.getInstance();
      if (mc == null || mc.player == null || mc.level == null || mc.gameMode == null || mc.options == null) {
         return false;
      }
      LocalPlayer player = mc.player;
      if (Compat.screen(mc) != null || !(player.containerMenu instanceof InventoryMenu)) {
         return false;
      }
      if (!this.inventoryFull(player)) {
         return false;
      }
      int box = this.findNonFullShulkerSlot(player);
      if (box < 0 || this.countStorable(player, box) == 0) {
         return false;
      }
      BlockPos support = this.findPlacementSpot(mc.level, player);
      if (support == null) {
         return false;
      }
      this.begin(mc, player, box, support);
      return true;
   }

   /**
    * 当背包满且附近暂时无法放置潜影盒（例如在 1x1 垂直井道内正准备拾取掉落鞘翅）时，
    * 丢弃 1 格挖掘产生的废石/紫珀块以腾出 1 个空位拾取鞘翅。绝不丢弃鞘翅、潜影盒、矿物、工具或食物。
    */
   public boolean dropOneJunkSlotForPickup(Minecraft mc, LocalPlayer player) {
      if (mc == null || player == null || mc.gameMode == null || !(player.containerMenu instanceof InventoryMenu)) {
         return false;
      }
      Inventory inv = player.getInventory();
      for (int i = 0; i < 36; i++) {
         ItemStack stack = inv.getItem(i);
         if (stack == null || stack.isEmpty()) {
            continue;
         }
         String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
         if (JUNK_BLOCKS.contains(id)) {
            int menuSlot = this.menuSlotOfInventoryIndex(i);
            try {
               mc.gameMode.handleContainerInput(0, menuSlot, 1, ContainerInput.THROW, player);
               this.info("背包已满，已丢弃 1 格挖掘废块（" + id + "）以腾出空位。", ChatFormatting.YELLOW);
               return true;
            } catch (Throwable ignored) {
            }
         }
      }
      return false;
   }

   public void onClientTick() {
      if (this.state == State.IDLE) {
         if (this.cooldown > 0) {
            this.cooldown--;
         }
         // 诊断会读取界面/背包/周围方块，任何异常都不能影响同一 END_CLIENT_TICK 上的
         // 其它监听（自动进食、自动挖矿、鞘翅搜寻等）。
         try {
            this.tryTrigger();
         } catch (Throwable t) {
            long now = System.currentTimeMillis();
            if (now - this.lastTriggerErrorMs > 10000L) {
               this.lastTriggerErrorMs = now;
               LOGGER.warn("[shulker-store] trigger scan failed", t);
            }
         }
         return;
      }

      Minecraft mc = Minecraft.getInstance();
      if (mc == null || mc.player == null || mc.level == null || mc.gameMode == null) {
         this.finish(false, "世界已卸载，存盒已中断。");
         return;
      }

      LocalPlayer player = mc.player;
      this.ticks++;
      this.totalTicks++;
      if (!player.isAlive()) {
         this.abort("玩家死亡，存盒已中断。", false);
         return;
      }

      if (mc.level != this.startLevel || this.totalTicks > TOTAL_TIMEOUT_TICKS) {
         if (this.timeoutRecovering) {
            this.finish(false, "存盒超时，已停止；若地上有潜影盒请手动挖回。");
         } else {
            this.timeoutRecovering = true;
            this.abort("存盒超时或切换了世界，正在收尾。", true);
         }
         return;
      }

      if (this.placedPos != null
         && !(mc.level.getBlockState(this.placedPos).getBlock() instanceof ShulkerBoxBlock)
         && (this.state == State.BREAK || this.state == State.CLEAR_OBSTRUCTION)) {
         // 挖掘/清障阶段盒子已经不在地上了，直接进入捡回确认（注意不能在 COLLECT 阶段重复触发，否则会死循环重置 ticks）。
         this.stopBreaking(mc);
         this.transition(State.COLLECT);
         return;
      }

      try {
         switch (this.state) {
            case PREPARE -> this.tickPrepare(mc, player);
            case PLACE -> this.tickPlace(mc, player);
            case WAIT_PLACE -> this.tickWaitPlace(mc, player);
            case OPEN_BOX -> this.tickOpenBox(mc, player);
            case WAIT_OPEN -> this.tickWaitOpen(mc, player);
            case STORE -> this.tickStore(mc, player);
            case WAIT_CLOSE -> this.tickWaitClose(mc, player);
            case CLEAR_OBSTRUCTION -> this.tickClearObstruction(mc, player);
            case BREAK -> this.tickBreak(mc, player);
            case COLLECT -> this.tickCollect(mc, player);
            default -> this.finish(false, "存盒状态异常，已重置。");
         }
      } catch (Throwable t) {
         LOGGER.error("[shulker-store] tick error in state {}", this.state, t);
         this.abort("存盒过程出错（" + t.getClass().getSimpleName() + "），正在收尾。", true);
      }
   }

   private void tryTrigger() {
      // 每 10 tick 扫一次条件即可，避免每帧遍历背包和周围方块。
      if (++this.scanCounter % 10 != 0) {
         return;
      }

      Minecraft mc = Minecraft.getInstance();
      if (mc == null || mc.player == null || mc.level == null || mc.gameMode == null || mc.options == null) {
         return;
      }

      LocalPlayer player = mc.player;

      // 关键：背包已满且包里确实有潜影盒，却因为某个前置条件没能启动时，
      // 必须明确告诉玩家原因（开关被旧配置关掉、附近没有放置点、盒子全满…），
      // 否则表现就是「什么都没发生」，无法排查。
      if (this.state == State.IDLE && this.inventoryFull(player) && this.countShulkers(player) > 0) {
         String reason = this.diagnose(mc, player);
         if (reason != null) {
            this.hint(reason);
         }
      }

      if (this.cooldown > 0 || !Config.get().autoMineShulkerSave || AutoEatManager.INSTANCE.isBusy()) {
         return;
      }

      // 不打断玩家正在进行的界面操作。
      if (Compat.screen(mc) != null || !(player.containerMenu instanceof InventoryMenu)) {
         return;
      }

      try {
         if (ElytraHuntManager.INSTANCE.isRunning() && !ElytraHuntManager.INSTANCE.allowsShulkerStore()) {
            return;
         }
      } catch (Throwable ignored) {
      }

      if (!this.inventoryFull(player)) {
         return;
      }

      int box = this.findNonFullShulkerSlot(player);
      if (box < 0 || this.countStorable(player, box) == 0) {
         return;
      }

      BlockPos support = this.findPlacementSpot(mc.level, player);
      if (support == null) {
         // 附近暂时没有安全放置点，等下一个 10 tick 周期继续探测（不设长冷却，避免飞越末地船屋顶时错过放置点）。
         return;
      }

      this.begin(mc, player, box, support);
   }

   private void begin(Minecraft mc, LocalPlayer player, int boxSlot, BlockPos support) {
      this.state = State.PREPARE;
      this.ticks = 0;
      this.totalTicks = 0;
      this.failed = false;
      this.timeoutRecovering = false;
      this.breakStarted = false;
      this.breakTarget = null;
      this.breakTargetTicks = 0;
      this.skippedBlockers.clear();
      this.moves = 0;
      this.nonOreMoves = 0;
      this.boxInvSlot = boxSlot;
      this.placeHotbarSlot = -1;
      this.swappedFromMainInv = false;
      this.supportPos = support;
      this.placedPos = support.above();
      this.attemptedSlots.clear();
      this.initialShulkerCount = this.countShulkers(player);
      this.startLevel = mc.level;
      this.originalSelectedSlot = player.getInventory().getSelectedSlot();
      this.changedSelectedSlot = false;

      this.releaseInputs(mc);
      this.stabilizePlayer(mc, player);
      if (AutoMineManager.INSTANCE.isActive() && FabricLoader.getInstance().isModLoaded("baritone")) {
         try {
            BaritoneBridge.cancel();
         } catch (Throwable t) {
            LOGGER.warn("[shulker-store] failed to pause Baritone", t);
         }
      }

      this.info("背包已满，开始把鞘翅/物品存入潜影盒…", ChatFormatting.GOLD);
   }

   /** 准备：等两拍让 Baritone / 飞行按键状态落定，然后把潜影盒拿在手上。 */
   private void tickPrepare(Minecraft mc, LocalPlayer player) {
      this.stabilizePlayer(mc, player);
      if (this.ticks < 2) {
         return;
      }

      Inventory inv = player.getInventory();
      if (this.boxInvSlot < 9) {
         this.placeHotbarSlot = this.boxInvSlot;
         this.swappedFromMainInv = false;
         inv.setSelectedSlot(this.boxInvSlot);
         this.changedSelectedSlot = true;
         // 本流程挂在 END_CLIENT_TICK，比 Minecraft.tick() 的自动同步晚，必须立刻发包，
         // 否则服务端收到 useItemOn 时还以为玩家拿的是旧槽位的物品。
         if (mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundSetCarriedItemPacket(this.boxInvSlot));
         }
      } else {
         int targetHotbar = this.originalSelectedSlot;
         if (this.isShulkerItem(inv.getItem(targetHotbar))) {
            for (int i = 0; i < 9; i++) {
               if (!this.isShulkerItem(inv.getItem(i))) {
                  targetHotbar = i;
                  break;
               }
            }
         }
         this.placeHotbarSlot = targetHotbar;
         if (targetHotbar != inv.getSelectedSlot()) {
            inv.setSelectedSlot(targetHotbar);
            this.changedSelectedSlot = true;
            if (mc.getConnection() != null) {
               mc.getConnection().send(new ServerboundSetCarriedItemPacket(targetHotbar));
            }
         }
         int menuSlot = this.menuSlotOfInventoryIndex(this.boxInvSlot);
         mc.gameMode.handleContainerInput(0, menuSlot, targetHotbar, ContainerInput.SWAP, player);
         this.swappedFromMainInv = true;
      }

      if (!this.isShulkerItem(player.getMainHandItem())) {
         this.abort("无法把潜影盒切换到手持栏，放弃本次存盒。", false);
         return;
      }

      Vec3 hitPoint = new Vec3(this.supportPos.getX() + 0.5, this.supportPos.getY() + 1.0, this.supportPos.getZ() + 0.5);
      this.faceAt(player, hitPoint);
      this.transition(State.PLACE);
   }

   /** 放置：拿着潜影盒右键支撑方块的顶面。 */
   private void tickPlace(Minecraft mc, LocalPlayer player) {
      this.stabilizePlayer(mc, player);
      if (!this.placementStillValid(mc.level, player)) {
         this.abort("放置位置已失效，放弃本次存盒。", false);
         return;
      }

      this.releaseUseKeys(mc);
      Vec3 hitPoint = new Vec3(this.supportPos.getX() + 0.5, this.supportPos.getY() + 1.0, this.supportPos.getZ() + 0.5);
      this.faceAt(player, hitPoint);
      BlockHitResult hit = new BlockHitResult(hitPoint, Direction.UP, this.supportPos, false);
      mc.hitResult = hit;

      InteractionResult result;
      try {
         result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
         player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
      } catch (Throwable t) {
         LOGGER.warn("[shulker-store] place failed", t);
         this.abort("放置潜影盒失败，放弃本次存盒。", false);
         return;
      }

      boolean placed = mc.level.getBlockState(this.placedPos).getBlock() instanceof ShulkerBoxBlock;
      if (!placed && (result == null || !result.consumesAction())) {
         this.abort("放置潜影盒被拒绝（可能被领地保护或碰撞体积阻挡）。", false);
         return;
      }

      this.transition(State.WAIT_PLACE);
   }

   /** 等服务端确认方块确实放下了，还原被临时交换的快捷栏工具，并确保主手不再是潜影盒。 */
   private void tickWaitPlace(Minecraft mc, LocalPlayer player) {
      this.stabilizePlayer(mc, player);
      if (!(mc.level.getBlockState(this.placedPos).getBlock() instanceof ShulkerBoxBlock)) {
         if (this.ticks > 30) {
            this.abort("服务端未确认潜影盒放置，放弃本次存盒。", false);
         }
         return;
      }

      if (this.ticks < 3) {
         return;
      }

      Inventory inv = player.getInventory();
      // 若此前从主背包交换了潜影盒到快捷栏，放置后快捷栏该槽位已空，立刻把原快捷栏物品（如镐子）换回快捷栏。
      if (this.swappedFromMainInv && this.boxInvSlot >= 9 && this.placeHotbarSlot >= 0) {
         if (inv.getItem(this.placeHotbarSlot).isEmpty() && !this.isShulkerItem(inv.getItem(this.boxInvSlot))) {
            try {
               mc.gameMode.handleContainerInput(0, this.menuSlotOfInventoryIndex(this.boxInvSlot), this.placeHotbarSlot, ContainerInput.SWAP, player);
               this.swappedFromMainInv = false;
            } catch (Throwable ignored) {
            }
         }
      }

      if (this.isShulkerItem(player.getMainHandItem())) {
         // 兜底：若主手仍是潜影盒（例如多盒堆叠或全快捷栏都是潜影盒），优先切到快捷栏内的非潜影盒槽位（含空槽位）。
         int nonShulkerHotbar = -1;
         for (int i = 0; i < 9; i++) {
            if (i != inv.getSelectedSlot() && !this.isShulkerItem(inv.getItem(i))) {
               nonShulkerHotbar = i;
               break;
            }
         }

         if (nonShulkerHotbar >= 0) {
            inv.setSelectedSlot(nonShulkerHotbar);
            this.changedSelectedSlot = true;
            if (mc.getConnection() != null) {
               mc.getConnection().send(new ServerboundSetCarriedItemPacket(nonShulkerHotbar));
            }
         } else {
            int nonShulkerInv = -1;
            for (int i = 9; i < 36; i++) {
               if (!this.isShulkerItem(inv.getItem(i))) {
                  nonShulkerInv = i;
                  break;
               }
            }
            if (nonShulkerInv < 0) {
               this.abort("主手仍是潜影盒，无法安全打开盒子。", true);
               return;
            }
            mc.gameMode.handleContainerInput(0, this.menuSlotOfInventoryIndex(nonShulkerInv), inv.getSelectedSlot(), ContainerInput.SWAP, player);
         }
      }

      this.faceAt(player, Vec3.atCenterOf(this.placedPos));
      this.transition(State.OPEN_BOX);
   }

   /** 右键打开刚放下的潜影盒。 */
   private void tickOpenBox(Minecraft mc, LocalPlayer player) {
      this.stabilizePlayer(mc, player);
      if (!(mc.level.getBlockState(this.placedPos).getBlock() instanceof ShulkerBoxBlock)) {
         this.abort("潜影盒方块已消失，放弃打开。", true);
         return;
      }

      this.releaseUseKeys(mc);
      Vec3 center = Vec3.atCenterOf(this.placedPos);
      this.faceAt(player, center);
      BlockHitResult hit = new BlockHitResult(center, Direction.UP, this.placedPos, false);
      mc.hitResult = hit;

      try {
         mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
         player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
      } catch (Throwable t) {
         LOGGER.warn("[shulker-store] open failed", t);
         this.abort("打开潜影盒失败，正在把盒子取回。", true);
         return;
      }

      this.transition(State.WAIT_OPEN);
   }

   private void tickWaitOpen(Minecraft mc, LocalPlayer player) {
      this.stabilizePlayer(mc, player);
      if (player.containerMenu instanceof ShulkerBoxMenu) {
         this.transition(State.STORE);
         return;
      }

      // 若 20 tick 仍未打开且盒子还在地上，重试一次右键打开，防止上一次交互包因朝向同步延迟被忽略。
      if (this.ticks == 20 && mc.level.getBlockState(this.placedPos).getBlock() instanceof ShulkerBoxBlock) {
         this.releaseUseKeys(mc);
         Vec3 center = Vec3.atCenterOf(this.placedPos);
         this.faceAt(player, center);
         BlockHitResult hit = new BlockHitResult(center, Direction.UP, this.placedPos, false);
         mc.hitResult = hit;
         try {
            mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
         } catch (Throwable ignored) {
         }
      }

      if (this.ticks > 60) {
         this.abort("潜影盒界面打开超时（服务器可能禁止放置/交互），正在把盒子取回。", true);
      }
   }

   /** 逐格 shift-click 把背包物品移进盒子。鞘翅第一优先，矿石次之，其它可堆叠杂物限量存入。 */
   private void tickStore(Minecraft mc, LocalPlayer player) {
      this.stabilizePlayer(mc, player);
      if (!(player.containerMenu instanceof ShulkerBoxMenu menu)) {
         this.abort("潜影盒界面被关闭，正在收尾。", true);
         return;
      }

      if (this.ticks > 160 || this.moves >= MAX_MOVES) {
         this.closeAndBreak(mc, player);
         return;
      }

      // 给服务端一点时间下发初始容器内容并同步上一次点击的结果。
      if (this.ticks < 2 || this.ticks % 2 != 0) {
         return;
      }

      Inventory inv = player.getInventory();
      // Pass 0: 优先存鞘翅（Items.ELYTRA）
      int slotIndex = this.nextStoreSlot(menu, inv, 0);
      boolean priority = true;
      // Pass 1: 存矿石与矿物块
      if (slotIndex < 0) {
         slotIndex = this.nextStoreSlot(menu, inv, 1);
      }
      // Pass 2: 其它可堆叠杂物（限量存入）
      if (slotIndex < 0 && this.nonOreMoves < MAX_NON_ORE_MOVES) {
         slotIndex = this.nextStoreSlot(menu, inv, 2);
         priority = false;
      }

      if (slotIndex < 0) {
         if (this.moves == 0) {
            this.failed = true;
         }
         this.closeAndBreak(mc, player);
         return;
      }

      this.attemptedSlots.add(slotIndex);
      this.moves++;
      if (!priority) {
         this.nonOreMoves++;
      }

      try {
         mc.gameMode.handleContainerInput(menu.containerId, slotIndex, 0, ContainerInput.QUICK_MOVE, player);
      } catch (Throwable t) {
         LOGGER.warn("[shulker-store] quick move failed", t);
         this.closeAndBreak(mc, player);
      }
   }

   private void closeAndBreak(Minecraft mc, LocalPlayer player) {
      if (player.containerMenu instanceof ShulkerBoxMenu) {
         try {
            player.closeContainer();
         } catch (Throwable ignored) {
         }
      }

      this.transition(State.WAIT_CLOSE);
   }

   private void tickWaitClose(Minecraft mc, LocalPlayer player) {
      this.stabilizePlayer(mc, player);
      if (!(player.containerMenu instanceof ShulkerBoxMenu) || this.ticks > 30) {
         if (Compat.screen(mc) != null && this.ticks > 30) {
            try {
               Compat.setScreen(mc, null);
            } catch (Throwable ignored) {
            }
         }

         this.releaseUseKeys(mc);
         this.transition(State.CLEAR_OBSTRUCTION);
      }
   }

   /**
    * 装满后的清障：把挡在玩家视线里、或压在盒子正上方（挡住开盖 / 掉落物）的实心方块
    * 逐个挖掉，然后才进入 BREAK 挖回盒子。
    *
    * <p>26.3 服务端破坏方块只校验距离、不校验视线，但客户端
    * {@code Minecraft.handleKeybinds()} 每帧用「真实准星」驱动
    * {@code continueDestroyBlock()}；视线被墙挡住时它会一直挖墙，
    * 与我们对盒子的 START_DESTROY_BLOCK 交替，服务端的破坏进度被反复清零，
    * 表现就是「阻挡方块没挖走、潜影盒也挖不掉」。这里显式把阻挡挖掉，
    * 并用统一的手动驱动绕开准星。</p>
    */
   private void tickClearObstruction(Minecraft mc, LocalPlayer player) {
      this.stabilizePlayer(mc, player);
      if (this.ticks <= 1) {
         // 存盒前鞘翅搜寻可能正在挖别的方块，先干净地结束那个破坏会话。
         this.stopBreaking(mc);
      }
      if (this.placedPos == null
         || !(mc.level.getBlockState(this.placedPos).getBlock() instanceof ShulkerBoxBlock)) {
         this.stopBreaking(mc);
         this.transition(State.COLLECT);
         return;
      }

      if (this.ticks > MAX_CLEAR_TICKS) {
         this.stopBreaking(mc);
         this.warn("清除潜影盒周围阻挡超时，尝试直接挖回盒子。", ChatFormatting.YELLOW);
         this.transition(State.BREAK);
         return;
      }

      BlockPos blocker = this.findBreakObstruction(mc.level, player);
      if (blocker == null) {
         // 没有可清除的阻挡了：干净结束上一个破坏会话，再切到挖盒。
         this.stopBreaking(mc);
         this.breakTarget = null;
         this.breakTargetTicks = 0;
         this.transition(State.BREAK);
         return;
      }

      if (!blocker.equals(this.breakTarget)) {
         this.stopBreaking(mc);
         this.breakTarget = blocker;
         this.breakTargetTicks = 0;
         this.equipForTarget(player, mc.level.getBlockState(blocker));
         this.info("潜影盒被 " + this.blockLabel(mc.level.getBlockState(blocker))
            + " 挡住，先挖掉阻挡方块 " + this.posText(blocker) + " …", ChatFormatting.GOLD);
      }

      this.breakTargetTicks++;
      if (this.breakTargetTicks > MAX_BLOCKER_TICKS) {
         this.skippedBlockers.add(blocker);
         this.warn("阻挡方块 " + this.posText(blocker)
            + " 挖不动（基岩 / 受保护 / 太硬），改为直接挖回潜影盒。", ChatFormatting.YELLOW);
         this.stopBreaking(mc);
         this.breakTarget = null;
         this.breakTargetTicks = 0;
         return;
      }

      this.driveBreak(mc, player, blocker, Vec3.atCenterOf(blocker));
   }

   /** 挖回盒子：与清障共用一套手动驱动，视线是否被挡都不影响。 */
   private void tickBreak(Minecraft mc, LocalPlayer player) {
      this.stabilizePlayer(mc, player);
      if (this.placedPos == null
         || !(mc.level.getBlockState(this.placedPos).getBlock() instanceof ShulkerBoxBlock)) {
         this.stopBreaking(mc);
         this.transition(State.COLLECT);
         return;
      }

      if (this.ticks > 240) {
         this.stopBreaking(mc);
         LOGGER.warn("[shulker-store] break timed out at {}", this.posText(this.placedPos));
         this.finish(false, null);
         return;
      }

      if (!this.placedPos.equals(this.breakTarget)) {
         this.stopBreaking(mc);
         this.breakTarget = this.placedPos;
         this.breakTargetTicks = 0;
         this.equipForTarget(player, mc.level.getBlockState(this.placedPos));
      }

      this.driveBreak(mc, player, this.placedPos, this.bestBoxAimPoint(mc.level, player));
   }

   /** 切换挖掘目标时只切一次工具，避免破坏中途换手把服务端进度清零。 */
   private void equipForTarget(LocalPlayer player, BlockState state) {
      try {
         FlyController.equipForMining(player, state);
         if (player.getInventory().getSelectedSlot() != this.originalSelectedSlot) {
            this.changedSelectedSlot = true;
         }
      } catch (Throwable ignored) {
      }
   }

   /**
    * 驱动一次方块破坏。{@code Minecraft.tick()} 会在 {@code handleKeybinds()}
    * 之前用真实准星重算 {@code mc.hitResult}，所以准星本来就在目标上、且原版
    * 能正常驱动时沿用原版链路；否则（视线被不可挖方块挡住、鼠标未抓取、
    * 界面打开）松开左键自己 {@code continueDestroyBlock(target)}，并挂上
    * {@link #isDestroyProtected()}，让原版 {@code continueAttack(false)} 每帧触发的
    * {@code stopDestroyBlock()} 被 mixin 吃掉，服务端破坏进度才能连续累积。
    * {@code continueDestroyBlock} 在目标变化时会自动重新 {@code startDestroyBlock}，
    * 因此准星偶尔被实体抢占、或左键被玩家点了一下都能自愈。
    */
   private void driveBreak(Minecraft mc, LocalPlayer player, BlockPos target, Vec3 aim) {
      this.stabilizePlayer(mc, player);
      this.faceAt(player, aim);
      Direction direction = Direction.getApproximateNearest(player.getEyePosition().subtract(aim));
      mc.hitResult = new BlockHitResult(aim, direction, target, false);

      protectDestroy(1000L);
      mc.options.keyUse.setDown(false);
      mc.options.keyShift.setDown(false);

      boolean vanillaHandlesTick = mc.mouseHandler != null
         && mc.mouseHandler.isMouseGrabbed()
         && !player.isUsingItem()
         && Compat.screen(mc) == null
         && this.crosshairHitsTarget(mc.level, player, target, aim);
      mc.options.keyAttack.setDown(vanillaHandlesTick);

      try {
         if (vanillaHandlesTick) {
            // 原版 Minecraft.continueAttack(true) 每 tick 会调一次 continueDestroyBlock，
            // 这里只在尚未开始破坏时补一次 START，避免重复推进破坏进度。
            if (!mc.gameMode.isDestroying()) {
               mc.gameMode.startDestroyBlock(target, direction);
            }
         } else if (mc.gameMode.isDestroying()) {
            mc.gameMode.continueDestroyBlock(target, direction);
         } else {
            mc.gameMode.startDestroyBlock(target, direction);
         }
         this.breakStarted = true;
      } catch (Throwable t) {
         LOGGER.warn("[shulker-store] break failed at {}", this.posText(target), t);
         this.stopBreaking(mc);
      }
      player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
   }

   /** 真实准星（忽略实体）是否正好落在要挖的方块上。 */
   private boolean crosshairHitsTarget(Level level, LocalPlayer player, BlockPos target, Vec3 aim) {
      try {
         BlockHitResult hit = level.clip(
            new ClipContext(player.getEyePosition(), aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player)
         );
         return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target);
      } catch (Throwable ignored) {
         return false;
      }
   }

   /** 找出当前最该先挖掉的阻挡方块：盒子正上方实心块优先，其次是视线上的第一个实心块。 */
   private BlockPos findBreakObstruction(Level level, LocalPlayer player) {
      if (this.placedPos == null) {
         return null;
      }

      BlockPos above = this.placedPos.above();
      if (this.isBreakableBlocker(level, above)
         && !level.getBlockState(above).getCollisionShape(level, above).isEmpty()) {
         return above;
      }

      Vec3 eye = player.getEyePosition();
      BlockPos best = null;
      double bestDistSq = Double.MAX_VALUE;
      for (Vec3 aim : this.boxAimPoints(player)) {
         try {
            BlockHitResult hit = level.clip(
               new ClipContext(eye, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player)
            );
            // 至少有一条视线直接命中盒子（或完全没碰到方块）→ 不需要清障。
            if (hit.getType() != HitResult.Type.BLOCK || hit.getBlockPos().equals(this.placedPos)) {
               return null;
            }
            BlockPos hitPos = hit.getBlockPos();
            if (!this.isBreakableBlocker(level, hitPos)) {
               continue;
            }
            double dSq = eye.distanceToSqr(hit.getLocation());
            if (dSq < bestDistSq) {
               bestDistSq = dSq;
               best = hitPos;
            }
         } catch (Throwable ignored) {
         }
      }
      return best;
   }

   /** 能挖、且值得挖的阻挡方块。容器 / 其它潜影盒 / 不可破坏方块交给「直接挖盒」兜底。 */
   private boolean isBreakableBlocker(Level level, BlockPos pos) {
      if (pos == null || pos.equals(this.placedPos) || this.skippedBlockers.contains(pos)) {
         return false;
      }
      BlockState state = level.getBlockState(pos);
      if (state.isAir() || state.getBlock() instanceof ShulkerBoxBlock || this.isInteractiveBlock(state)) {
         return false;
      }
      if (state.is(Blocks.BEDROCK) || state.is(Blocks.BARRIER)
         || state.is(Blocks.END_PORTAL) || state.is(Blocks.END_PORTAL_FRAME)) {
         return false;
      }
      try {
         float hardness = state.getDestroySpeed(level, pos);
         // 不可破坏 / 黑曜石、远古残骸这类超硬方块不在这里硬耗，
         // 直接交给「无视视线挖盒」兜底，避免一次存盒卡十几秒。
         if (hardness < 0.0F || hardness > 20.0F) {
            return false;
         }
      } catch (Throwable ignored) {
      }
      return true;
   }

   /** 盒子内部若干候选瞄点（都朝玩家一侧内缩），用于视线检测与手动破坏朝向。 */
   private List<Vec3> boxAimPoints(LocalPlayer player) {
      Vec3 fallback = this.placedPos == null ? player.getEyePosition() : Vec3.atCenterOf(this.placedPos);
      List<Vec3> points = new ArrayList<>(5);
      if (this.placedPos == null) {
         points.add(fallback);
         return points;
      }
      Vec3 eye = player.getEyePosition();
      double cx = this.placedPos.getX() + 0.5;
      double cy = this.placedPos.getY() + 0.5;
      double cz = this.placedPos.getZ() + 0.5;
      double sx = eye.x >= cx ? 0.34 : -0.34;
      double sy = eye.y >= cy ? 0.34 : -0.34;
      double sz = eye.z >= cz ? 0.34 : -0.34;
      points.add(new Vec3(cx, cy, cz));
      points.add(new Vec3(cx + sx, cy, cz + sz));
      points.add(new Vec3(cx + sx, cy + sy, cz));
      points.add(new Vec3(cx, cy + sy, cz + sz));
      points.add(new Vec3(cx + sx, cy + sy, cz + sz));
      return points;
   }

   /** 选一个视线能直接命中盒子的瞄点；都被挡住时退回中心，由手动驱动保证仍能挖到盒子。 */
   private Vec3 bestBoxAimPoint(Level level, LocalPlayer player) {
      Vec3 fallback = this.placedPos == null ? player.getEyePosition() : Vec3.atCenterOf(this.placedPos);
      if (this.placedPos == null) {
         return fallback;
      }
      Vec3 eye = player.getEyePosition();
      for (Vec3 aim : this.boxAimPoints(player)) {
         try {
            BlockHitResult hit = level.clip(
               new ClipContext(eye, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player)
            );
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(this.placedPos)) {
               return aim;
            }
         } catch (Throwable ignored) {
         }
      }
      return fallback;
   }

   private String blockLabel(BlockState state) {
      try {
         return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
      } catch (Throwable ignored) {
         return "方块";
      }
   }

   /** 确认带内容的潜影盒回到背包；等待原版 10 tick 掉落拾取延迟并在需要时靠近掉落物。 */
   private void tickCollect(Minecraft mc, LocalPlayer player) {
      if (this.countShulkers(player) >= this.initialShulkerCount) {
         boolean success = !this.failed && this.moves > 0;
         String doneMsg;
         if (!success) {
            doneMsg = "潜影盒已取回，但本次存入未完成。";
         } else if (ElytraHuntManager.INSTANCE.isRunning()) {
            doneMsg = "已将鞘翅/物品存入潜影盒并取回，继续收集鞘翅。";
         } else if (AutoMineManager.INSTANCE.isActive()) {
            doneMsg = "已存入潜影盒并取回，继续挖矿。";
         } else {
            doneMsg = "已存入潜影盒并取回。";
         }
         this.finish(success, doneMsg);
         return;
      }

      if (this.ticks > 120) {
         this.warn("潜影盒未自动捡回，掉落物应在 " + this.posText(this.placedPos) + " 附近，请手动捡起！", ChatFormatting.YELLOW);
         this.finish(false, null);
         return;
      }

      // 若在捡回潜影盒前地上的杂物恰好填满了最后一格背包，丢弃 1 格废块或普通可存杂物，确保潜影盒一定能进包。
      // 清障阶段可能额外挖掉多个方块、掉落多格废石，所以每 10 tick 复查一次而不是只在第 25 tick 试一次。
      if (this.ticks == 25 && this.inventoryFull(player)) {
         if (!this.dropOneJunkSlotForPickup(mc, player)) {
            this.dropOneLowPrioritySlotForBox(mc, player);
         }
      } else if (this.ticks >= 35 && this.ticks <= 95 && this.ticks % 10 == 0
         && this.inventoryFull(player) && this.countShulkers(player) < this.initialShulkerCount) {
         this.dropOneJunkSlotForPickup(mc, player);
      }

      Vec3 target = null;
      double bestSq = 64.0;
      if (mc.level instanceof ClientLevel clientLevel) {
         for (Entity entity : clientLevel.entitiesForRendering()) {
            if (entity instanceof ItemEntity itemEntity && this.isShulkerItem(itemEntity.getItem())) {
               double dSq = player.distanceToSqr(itemEntity);
               if (dSq < bestSq) {
                  bestSq = dSq;
                  target = itemEntity.position();
               }
            }
         }
      }

      if (target == null) {
         target = Vec3.atCenterOf(this.placedPos);
      }

      double dx = target.x - player.getX();
      double dy = target.y - player.getY();
      double dz = target.z - player.getZ();
      double horiz = Math.hypot(dx, dz);

      // 原版方块掉落物有 10 tick 拾取延迟；若已在拾取范围内，保持原地静止等待拾取，防止冲过头掉下末地船。
      if ((horiz <= 0.75 && Math.abs(dy) <= 0.9) || (this.ticks <= 12 && horiz <= 1.35 && Math.abs(dy) <= 1.25)) {
         this.releaseInputs(mc);
         this.stabilizePlayer(mc, player);
         return;
      }

      if (horiz > 0.18) {
         player.setYRot((float)(Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
      }
      mc.options.keySprint.setDown(false);
      mc.options.keyUp.setDown(horiz > 0.55);
      mc.options.keyDown.setDown(false);
      mc.options.keyLeft.setDown(false);
      mc.options.keyRight.setDown(false);

      if (player.getAbilities().flying) {
         mc.options.keyJump.setDown(dy > 0.45);
         mc.options.keyShift.setDown(dy < -0.45);
         if (horiz <= 0.55) {
            Vec3 v = player.getDeltaMovement();
            player.setDeltaMovement(0.0, v.y * 0.5, 0.0);
         }
      } else {
         mc.options.keyShift.setDown(false);
         mc.options.keyJump.setDown(player.horizontalCollision && dy > 0.2);
      }
   }

   /** 兜底腾格：当潜影盒在地上且背包 36 格被地上的杂物意外占满时，丢弃 1 格非鞘翅、非潜影盒的可存物品。 */
   private void dropOneLowPrioritySlotForBox(Minecraft mc, LocalPlayer player) {
      if (mc == null || player == null || mc.gameMode == null || !(player.containerMenu instanceof InventoryMenu)) {
         return;
      }
      Inventory inv = player.getInventory();
      int candidate = -1;
      for (int i = 0; i < 36; i++) {
         ItemStack stack = inv.getItem(i);
         if (!this.isStorable(stack) || stack.is(Items.ELYTRA)) {
            continue;
         }
         if (!this.isOre(stack)) {
            candidate = i;
            break;
         }
         if (candidate < 0) {
            candidate = i;
         }
      }
      if (candidate >= 0) {
         try {
            mc.gameMode.handleContainerInput(0, this.menuSlotOfInventoryIndex(candidate), 1, ContainerInput.THROW, player);
         } catch (Throwable ignored) {
         }
      }
   }

   // ---------------------------------------------------------------- 状态切换

   private void transition(State next) {
      this.state = next;
      this.ticks = 0;
   }

   private void stopBreaking(Minecraft mc) {
      // 必须先解除保护，否则下面主动发出的 ABORT_DESTROY_BLOCK 也会被 mixin 吃掉。
      clearDestroyProtection();
      if (mc.options != null) {
         mc.options.keyAttack.setDown(false);
      }

      if (mc.gameMode != null && mc.gameMode.isDestroying()) {
         try {
            mc.gameMode.stopDestroyBlock();
         } catch (Throwable ignored) {
         }
      }

      this.breakStarted = false;
   }

   /** 失败收尾：只要盒子还在地上，就先尝试挖回来，再结束流程。 */
   private void abort(String message, boolean recover) {
      this.warn(message, ChatFormatting.YELLOW);
      this.failed = true;
      Minecraft mc = Minecraft.getInstance();
      if (mc == null || mc.player == null) {
         this.finish(false, null);
         return;
      }

      boolean boxOnGround = this.placedPos != null
         && mc.level != null
         && mc.level.getBlockState(this.placedPos).getBlock() instanceof ShulkerBoxBlock;

      if (this.state == State.BREAK || this.state == State.COLLECT) {
         this.stopBreaking(mc);
         this.finish(false, null);
         return;
      }

      if (mc.player.containerMenu instanceof ShulkerBoxMenu) {
         try {
            mc.player.closeContainer();
         } catch (Throwable ignored) {
         }
      }

      if (recover && boxOnGround) {
         this.stopBreaking(mc);
         // 回收路径同样先清障，否则被墙挡住时依旧挖不回盒子。
         this.transition(State.CLEAR_OBSTRUCTION);
      } else {
         this.finish(false, null);
      }
   }

   /** 玩家死亡等外部事件中立即停止，不再尝试回收（提示坐标由调用方给出）。 */
   public void cancel() {
      if (this.state == State.IDLE) {
         return;
      }

      Minecraft mc = Minecraft.getInstance();
      if (mc != null) {
         this.stopBreaking(mc);
      }

      this.finish(false, "存盒流程已中断。");
   }

   private void finish(boolean ok, String message) {
      Minecraft mc = Minecraft.getInstance();
      if (mc != null) {
         this.releaseInputs(mc);
         this.stopBreaking(mc);
         if (mc.player != null) {
            // 若在 PREPARE 阶段把潜影盒从主背包换到了快捷栏但尚未换回，恢复原快捷栏物品。
            if (this.swappedFromMainInv && this.boxInvSlot >= 9 && this.placeHotbarSlot >= 0
               && mc.gameMode != null && mc.player.containerMenu instanceof InventoryMenu) {
               try {
                  mc.gameMode.handleContainerInput(
                     0, this.menuSlotOfInventoryIndex(this.boxInvSlot), this.placeHotbarSlot, ContainerInput.SWAP, mc.player
                  );
               } catch (Throwable ignored) {
               }
            }
            if (this.changedSelectedSlot) {
               try {
                  mc.player.getInventory().setSelectedSlot(this.originalSelectedSlot);
                  if (mc.getConnection() != null) {
                     mc.getConnection().send(new ServerboundSetCarriedItemPacket(this.originalSelectedSlot));
                  }
               } catch (Throwable ignored) {
               }
            }
         }
      }

      if (!ok && this.placedPos != null && mc != null && mc.level != null) {
         boolean boxOnGround = mc.level.getBlockState(this.placedPos).getBlock() instanceof ShulkerBoxBlock;
         LOGGER.warn(
            "[shulker-store] unfinished session near {} (state={}, boxOnGround={})",
            this.posText(this.placedPos), this.state, boxOnGround
         );
         if (boxOnGround) {
            this.warn("潜影盒仍留在 " + this.posText(this.placedPos) + "，请手动挖回，避免丢失物品！", ChatFormatting.RED);
         }
      }

      if (message != null) {
         this.info(message, ok ? ChatFormatting.GREEN : ChatFormatting.YELLOW);
      }

      if (AutoMineManager.INSTANCE.isActive()) {
         try {
            AutoMineManager.INSTANCE.onShulkerStoreFinished();
         } catch (Throwable ignored) {
         }
      }

      try {
         if (ElytraHuntManager.INSTANCE.isRunning()) {
            ElytraHuntManager.INSTANCE.onShulkerStoreFinished();
         }
      } catch (Throwable ignored) {
      }

      this.state = State.IDLE;
      this.ticks = 0;
      this.totalTicks = 0;
      this.placedPos = null;
      this.supportPos = null;
      this.boxInvSlot = -1;
      this.placeHotbarSlot = -1;
      this.swappedFromMainInv = false;
      this.startLevel = null;
      this.changedSelectedSlot = false;
      this.breakStarted = false;
      this.breakTarget = null;
      this.breakTargetTicks = 0;
      this.skippedBlockers.clear();
      this.attemptedSlots.clear();
      clearDestroyProtection();
      this.cooldown = ok ? 40 : 120;
      this.failed = false;
      this.timeoutRecovering = false;
   }

   // ---------------------------------------------------------------- 判定辅助

   private boolean inventoryFull(LocalPlayer player) {
      Inventory inv = player.getInventory();
      for (int i = 0; i < 36; i++) {
         if (inv.getItem(i).isEmpty()) {
            return false;
         }
      }

      return true;
   }

   private boolean isShulkerItem(ItemStack stack) {
      return stack != null && !stack.isEmpty()
         && stack.getItem() instanceof BlockItem blockItem
         && blockItem.getBlock() instanceof ShulkerBoxBlock;
   }

   private int countShulkers(LocalPlayer player) {
      int n = 0;
      Inventory inv = player.getInventory();
      for (int i = 0; i < 36; i++) {
         if (this.isShulkerItem(inv.getItem(i))) {
            n++;
         }
      }

      return n;
   }

   private int findNonFullShulkerSlot(LocalPlayer player) {
      Inventory inv = player.getInventory();
      for (int i = 0; i < 36; i++) {
         ItemStack stack = inv.getItem(i);
         if (this.isShulkerItem(stack) && this.boxCanAcceptFromInventory(stack, inv, i)) {
            return i;
         }
      }

      return -1;
   }

   /**
    * 判断该潜影盒是否能实际从玩家当前背包存入并腾出至少一格空间：
    * <ul>
    *    <li>若盒内还有空槽位（非空槽位不足 27 个），只要背包里有可存物品（如鞘翅、矿物）即可存入；</li>
    *    <li>若盒内 27 格均已被占用，则不可堆叠物品（如鞘翅）无法放入，仅当盒内同类同组件堆叠的剩余容量足以完整吞下背包中某格可存物品时才视为可用。</li>
    * </ul>
    */
   private boolean boxCanAcceptFromInventory(ItemStack box, Inventory inv, int boxSlot) {
      NonNullList<ItemStack> contents = NonNullList.withSize(27, ItemStack.EMPTY);
      try {
         ItemContainerContents component = box.get(DataComponents.CONTAINER);
         if (component != null) {
            component.copyInto(contents);
         }
      } catch (Throwable t) {
         return true;
      }

      boolean hasEmptySlot = false;
      for (ItemStack inBox : contents) {
         if (inBox.isEmpty()) {
            hasEmptySlot = true;
            break;
         }
      }

      for (int i = 0; i < 36; i++) {
         if (i == boxSlot) {
            continue;
         }
         ItemStack invStack = inv.getItem(i);
         if (!this.isStorable(invStack)) {
            continue;
         }
         if (hasEmptySlot) {
            return true;
         }
         if (invStack.getMaxStackSize() <= 1) {
            continue;
         }
         int mergeCapacity = 0;
         for (ItemStack inBox : contents) {
            if (!inBox.isEmpty() && this.canStacksMerge(inBox, invStack)) {
               mergeCapacity += Math.max(0, inBox.getMaxStackSize() - inBox.getCount());
               if (mergeCapacity >= invStack.getCount()) {
                  return true;
               }
            }
         }
      }

      return false;
   }

   private boolean canStacksMerge(ItemStack a, ItemStack b) {
      return a != null && b != null && !a.isEmpty() && !b.isEmpty()
         && ItemStack.isSameItem(a, b)
         && Objects.equals(a.getComponents(), b.getComponents());
   }

   private boolean isStorable(ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return false;
      }

      if (this.isShulkerItem(stack)) {
         return false;
      }

      if (stack.has(DataComponents.CONTAINER) || stack.has(DataComponents.CONTAINER_LOOT)) {
         return false;
      }

      // 鞘翅（Items.ELYTRA）虽然最大堆叠为 1 且有耐久度，但它是包满自动存盒的核心目标物品。
      if (stack.is(Items.ELYTRA)) {
         return true;
      }

      if (stack.getMaxStackSize() <= 1) {
         return false;
      }

      // 食物、药水、工具、其它装备等留给玩家自己用。
      if (stack.has(DataComponents.FOOD) || stack.isDamageableItem()) {
         return false;
      }

      String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
      return !NEVER_STORE.contains(id);
   }

   private boolean isOre(ItemStack stack) {
      String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
      return ORE_DROP_ITEMS.contains(id);
   }

   private int countStorable(LocalPlayer player, int boxSlot) {
      int n = 0;
      Inventory inv = player.getInventory();
      for (int i = 0; i < 36; i++) {
         if (i != boxSlot && this.isStorable(inv.getItem(i))) {
            n++;
         }
      }

      return n;
   }

   /**
    * 选择下一个要 QUICK_MOVE 的槽位。
    *
    * @param pass 0 = 仅鞘翅；1 = 仅矿石/矿物；2 = 其它可存物品
    */
   private int nextStoreSlot(ShulkerBoxMenu menu, Inventory inv, int pass) {
      int partialFallback = -1;
      for (int i = 0; i < menu.slots.size(); i++) {
         if (this.attemptedSlots.contains(i)) {
            continue;
         }

         Slot slot = menu.getSlot(i);
         if (slot.container != inv) {
            continue;
         }

         ItemStack stack = slot.getItem();
         if (!this.isStorable(stack)) {
            continue;
         }
         if (pass == 0 && !stack.is(Items.ELYTRA)) {
            continue;
         }
         if (pass == 1 && !this.isOre(stack)) {
            continue;
         }
         if (pass == 2 && (stack.is(Items.ELYTRA) || this.isOre(stack))) {
            continue;
         }

         if (!this.boxHasSpaceFor(menu, stack)) {
            continue;
         }

         if (this.boxCanCompletelyFit(menu, stack)) {
            return i;
         }
         if (partialFallback < 0) {
            partialFallback = i;
         }
      }

      return partialFallback;
   }

   private boolean boxHasSpaceFor(ShulkerBoxMenu menu, ItemStack stack) {
      int boxSlots = Math.min(27, menu.slots.size());
      for (int i = 0; i < boxSlots; i++) {
         ItemStack inBox = menu.getSlot(i).getItem();
         if (inBox.isEmpty()) {
            return true;
         }

         if (this.canStacksMerge(inBox, stack) && inBox.getCount() < inBox.getMaxStackSize()) {
            return true;
         }
      }

      return false;
   }

   private boolean boxCanCompletelyFit(ShulkerBoxMenu menu, ItemStack stack) {
      int boxSlots = Math.min(27, menu.slots.size());
      int capacity = 0;
      for (int i = 0; i < boxSlots; i++) {
         ItemStack inBox = menu.getSlot(i).getItem();
         if (inBox.isEmpty()) {
            return true;
         }
         if (this.canStacksMerge(inBox, stack)) {
            capacity += Math.max(0, inBox.getMaxStackSize() - inBox.getCount());
            if (capacity >= stack.getCount()) {
               return true;
            }
         }
      }
      return false;
   }

   private boolean isInteractiveBlock(BlockState state) {
      if (state == null || state.isAir()) {
         return false;
      }
      if (state.getBlock() instanceof ShulkerBoxBlock) {
         return true;
      }
      String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
      for (String kw : INTERACTIVE_SUPPORT_KEYWORDS) {
         if (path.contains(kw)) {
            return true;
         }
      }
      return false;
   }

   private boolean hasBlockingEntity(Level level, LocalPlayer player, AABB box) {
      if (!(level instanceof ClientLevel clientLevel)) {
         return false;
      }
      for (Entity entity : clientLevel.entitiesForRendering()) {
         if (entity == null || entity == player || entity.isRemoved() || !entity.isAlive()) {
            continue;
         }
         if (entity instanceof LivingEntity || entity instanceof ItemFrame) {
            if (entity.getBoundingBox().intersects(box)) {
               return true;
            }
         }
      }
      return false;
   }

   /** 找一格靠近玩家、不与玩家碰撞体积重叠、上方无遮挡、下面有非交互实心支撑的空地。 */
   private BlockPos findPlacementSpot(Level level, LocalPlayer player) {
      BlockPos feet = player.blockPosition();
      BlockPos head = feet.above();
      Vec3 eye = player.getEyePosition();
      AABB playerBox = player.getBoundingBox().inflate(0.08);
      BlockPos best = null;
      double bestScore = Double.MAX_VALUE;

      // 半径放宽到 4 格、高度 -4..+3：末地船屋顶/竖井口附近常常只有一格能放盒子，
      // 原来 3 格半径会直接返回 null，让整个功能静默失效。真正能否交互仍由
      // MAX_REACH_SQ 与 placementStillValid 二次校验，放宽扫描不会导致远距离放置。
      for (int dy = -4; dy <= 3; dy++) {
         for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
               int horizontal = dx * dx + dz * dz;
               if (horizontal == 0 || horizontal > 16) {
                  continue;
               }

               BlockPos support = feet.offset(dx, dy, dz);
               BlockPos target = support.above();
               if (target.equals(feet) || target.equals(head)) {
                  continue;
               }

               BlockState supportState = level.getBlockState(support);
               if (supportState.isAir() || this.isInteractiveBlock(supportState)) {
                  continue;
               }
               boolean sturdyTop = supportState.isFaceSturdy(level, support, Direction.UP);
               if (!sturdyTop) {
                  VoxelShape supportShape = supportState.getCollisionShape(level, support);
                  if (supportShape.isEmpty() || supportShape.max(Direction.Axis.Y) < 0.5) {
                     continue;
                  }
               }

               BlockState targetState = level.getBlockState(target);
               if (!targetState.isAir() && !targetState.canBeReplaced()) {
                  continue;
               }
               if (!level.getFluidState(target).isEmpty()) {
                  continue;
               }

               // 潜影盒打开需要上方留空（无碰撞体积）。
               BlockPos abovePos = target.above();
               BlockState aboveState = level.getBlockState(abovePos);
               if (!aboveState.isAir() && !aboveState.getCollisionShape(level, abovePos).isEmpty()) {
                  continue;
               }
               if (!level.getFluidState(abovePos).isEmpty()) {
                  continue;
               }

               // 放置目标格及上方开盖格绝不能与玩家自身碰撞箱或生物/展示框实体重叠，否则原版 useItemOn 会拒绝放置或无法开盖。
               AABB targetBox = new AABB(
                  target.getX(), target.getY(), target.getZ(),
                  target.getX() + 1.0, target.getY() + 1.0, target.getZ() + 1.0
               );
               AABB aboveBox = new AABB(
                  abovePos.getX(), abovePos.getY(), abovePos.getZ(),
                  abovePos.getX() + 1.0, abovePos.getY() + 1.0, abovePos.getZ() + 1.0
               );
               if (playerBox.intersects(targetBox) || player.getBoundingBox().intersects(aboveBox)) {
                  continue;
               }
               if (this.hasBlockingEntity(level, player, targetBox) || this.hasBlockingEntity(level, player, aboveBox)) {
                  continue;
               }

               Vec3 hitPoint = new Vec3(support.getX() + 0.5, support.getY() + 1.0, support.getZ() + 0.5);
               double distSq = eye.distanceToSqr(hitPoint);
               if (distSq > MAX_REACH_SQ || eye.distanceToSqr(Vec3.atCenterOf(target)) > MAX_REACH_SQ) {
                  continue;
               }

               // 优先选择完整实心顶面（sturdyTop）、与玩家处于相近高度、且视线不被墙挡住的位置。
               // 视线被挡不是硬性淘汰（竖井里常常只有这种位置），但优先清空地能少挖一格墙。
               double score = distSq + (sturdyTop ? 0.0 : 6.0) + Math.abs(dy) * 0.5
                  + (this.placementOccluded(level, player, support, target) ? 8.0 : 0.0);
               if (score < bestScore) {
                  bestScore = score;
                  best = support;
               }
            }
         }
      }

      return best;
   }

   /** 放置点到玩家眼睛之间是否隔着别的方块（支撑方块本身不算遮挡）。 */
   private boolean placementOccluded(Level level, LocalPlayer player, BlockPos support, BlockPos target) {
      try {
         BlockHitResult hit = level.clip(new ClipContext(
            player.getEyePosition(), Vec3.atCenterOf(target),
            ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player
         ));
         if (hit.getType() != HitResult.Type.BLOCK) {
            return false;
         }
         return !hit.getBlockPos().equals(support) && !hit.getBlockPos().equals(target);
      } catch (Throwable ignored) {
         return false;
      }
   }

   private boolean placementStillValid(Level level, LocalPlayer player) {
      if (this.supportPos == null || this.placedPos == null) {
         return false;
      }

      if (player.getEyePosition().distanceToSqr(Vec3.atCenterOf(this.placedPos)) > 20.25) {
         return false;
      }

      AABB targetBox = new AABB(
         this.placedPos.getX(), this.placedPos.getY(), this.placedPos.getZ(),
         this.placedPos.getX() + 1.0, this.placedPos.getY() + 1.0, this.placedPos.getZ() + 1.0
      );
      if (player.getBoundingBox().inflate(0.02).intersects(targetBox)) {
         return false;
      }

      BlockState target = level.getBlockState(this.placedPos);
      return target.isAir() || target.canBeReplaced();
   }

   private void stabilizePlayer(Minecraft mc, LocalPlayer player) {
      if (mc != null && mc.options != null) {
         mc.options.keyShift.setDown(false);
         mc.options.keyUp.setDown(false);
         mc.options.keyDown.setDown(false);
         mc.options.keyLeft.setDown(false);
         mc.options.keyRight.setDown(false);
         mc.options.keyJump.setDown(false);
      }
      if (player != null) {
         player.setShiftKeyDown(false);
         if (player.getAbilities().flying) {
            player.setDeltaMovement(Vec3.ZERO);
         }
      }
   }

   private void faceAt(LocalPlayer player, Vec3 point) {
      double dx = point.x - player.getX();
      double dy = point.y - player.getEyeY();
      double dz = point.z - player.getZ();
      double horizontal = Math.sqrt(dx * dx + dz * dz);
      player.setYRot((float)(Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
      player.setXRot((float)Mth.clamp(-Math.toDegrees(Math.atan2(dy, horizontal)), -90.0, 90.0));
   }

   private int menuSlotOfInventoryIndex(int invIndex) {
      return invIndex < 9 ? invIndex + 36 : invIndex;
   }

   private void releaseUseKeys(Minecraft mc) {
      if (mc.options != null) {
         mc.options.keyUse.setDown(false);
         mc.options.keyShift.setDown(false);
         mc.options.keyAttack.setDown(false);
      }
      if (mc.player != null) {
         mc.player.setShiftKeyDown(false);
      }
   }

   private void releaseInputs(Minecraft mc) {
      if (mc.options == null) {
         return;
      }

      mc.options.keyAttack.setDown(false);
      mc.options.keyUse.setDown(false);
      mc.options.keyUp.setDown(false);
      mc.options.keyDown.setDown(false);
      mc.options.keyLeft.setDown(false);
      mc.options.keyRight.setDown(false);
      mc.options.keyJump.setDown(false);
      mc.options.keyShift.setDown(false);
      mc.options.keySprint.setDown(false);
   }

   private String posText(BlockPos pos) {
      return pos.getX() + " " + pos.getY() + " " + pos.getZ();
   }

   private void info(String message, ChatFormatting color) {
      LOGGER.info("[shulker-store] {}", message);
      try {
         ClientCommand.sendFeedback("[自动存盒] " + message, color);
      } catch (Throwable ignored) {
      }
   }

   private void warn(String message, ChatFormatting color) {
      LOGGER.warn("[shulker-store] {}", message);
      try {
         ClientCommand.sendFeedback("[自动存盒] " + message, color);
      } catch (Throwable ignored) {
      }
   }
}
