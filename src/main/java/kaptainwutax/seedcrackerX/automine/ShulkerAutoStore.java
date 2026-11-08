package kaptainwutax.seedcrackerX.automine;

import java.util.HashSet;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
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
 *    <li>关闭界面、切镐子挖掉盒子、把带内容的盒子捡回背包；</li>
 *    <li>恢复自动鞘翅收集或自动挖矿。</li>
 * </ol>
 *
 * <p>任何一步失败都会尝试把已经放在地上的盒子挖回来；实在拿不回来时会把坐标
 * 明确告诉玩家，避免静默丢东西。</p>
 */
public final class ShulkerAutoStore {
   public static final ShulkerAutoStore INSTANCE = new ShulkerAutoStore();
   private static final Logger LOGGER = LoggerFactory.getLogger("SeedCrackerX-ShulkerStore");

   private static final int TOTAL_TIMEOUT_TICKS = 600;
   private static final int MAX_MOVES = 27;
   private static final int MAX_NON_ORE_MOVES = 8;
   private static final double MAX_REACH_SQ = 18.0;
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
      WAIT_CLOSE, BREAK, COLLECT
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
   private final Set<Integer> attemptedSlots = new HashSet<>();
   private Level startLevel;

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
      return this.findNonFullShulkerSlot(player) >= 0;
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
         this.tryTrigger();
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
         this.abort("存盒超时或切换了世界，正在收尾。", true);
         return;
      }

      if (this.placedPos != null
         && !(mc.level.getBlockState(this.placedPos).getBlock() instanceof ShulkerBoxBlock)
         && this.state == State.BREAK) {
         // 挖掘阶段盒子已经不在地上了，直接进入捡回确认（注意不能在 COLLECT 阶段重复触发，否则会死循环重置 ticks）。
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
      if (this.cooldown > 0 || !Config.get().autoMineShulkerSave || AutoEatManager.INSTANCE.isBusy()) {
         return;
      }

      // 每 10 tick 扫一次条件即可，避免每帧遍历背包和周围方块。
      if (++this.scanCounter % 10 != 0) {
         return;
      }

      Minecraft mc = Minecraft.getInstance();
      if (mc == null || mc.player == null || mc.level == null || mc.gameMode == null || mc.options == null) {
         return;
      }

      LocalPlayer player = mc.player;
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
      this.breakStarted = false;
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
         this.transition(State.BREAK);
      }
   }

   /** 挖回盒子：自动切换最优镐子，鼠标被抓取时交给原版驱动，否则自己驱动。 */
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

      BlockState boxState = mc.level.getBlockState(this.placedPos);
      try {
         FlyController.equipForMining(player, boxState);
         if (player.getInventory().getSelectedSlot() != this.originalSelectedSlot) {
            this.changedSelectedSlot = true;
         }
      } catch (Throwable ignored) {
      }

      Vec3 center = Vec3.atCenterOf(this.placedPos);
      this.faceAt(player, center);
      mc.hitResult = new BlockHitResult(center, Direction.UP, this.placedPos, false);

      boolean vanillaHandlesTick = mc.mouseHandler != null
         && mc.mouseHandler.isMouseGrabbed()
         && !player.isUsingItem();
      mc.options.keyAttack.setDown(vanillaHandlesTick);
      mc.options.keyUse.setDown(false);
      mc.options.keyShift.setDown(false);

      try {
         if (vanillaHandlesTick) {
            if (!this.breakStarted && !mc.gameMode.isDestroying()) {
               mc.gameMode.startDestroyBlock(this.placedPos, Direction.UP);
            }
         } else if (!mc.gameMode.isDestroying()) {
            mc.gameMode.startDestroyBlock(this.placedPos, Direction.UP);
         } else {
            mc.gameMode.continueDestroyBlock(this.placedPos, Direction.UP);
         }
         this.breakStarted = true;
      } catch (Throwable t) {
         LOGGER.warn("[shulker-store] break failed", t);
         this.stopBreaking(mc);
         this.finish(false, null);
         return;
      }

      player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
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
      if (this.ticks == 25 && this.inventoryFull(player)) {
         if (!this.dropOneJunkSlotForPickup(mc, player)) {
            this.dropOneLowPrioritySlotForBox(mc, player);
         }
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
         this.transition(State.BREAK);
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
      this.attemptedSlots.clear();
      this.cooldown = ok ? 40 : 120;
      this.failed = false;
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

      for (int dy = -3; dy <= 2; dy++) {
         for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
               int horizontal = dx * dx + dz * dz;
               if (horizontal == 0 || horizontal > 9) {
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

               // 优先选择完整实心顶面（sturdyTop）且与玩家处于相近高度的方块。
               double score = distSq + (sturdyTop ? 0.0 : 6.0) + Math.abs(dy) * 0.5;
               if (score < bestScore) {
                  bestScore = score;
                  best = support;
               }
            }
         }
      }

      return best;
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
