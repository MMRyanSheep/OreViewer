package kaptainwutax.seedcrackerX.automine;

import java.util.HashSet;
import java.util.Set;
import kaptainwutax.seedcrackerX.autoeat.AutoEatManager;
import kaptainwutax.seedcrackerX.command.ClientCommand;
import kaptainwutax.seedcrackerX.config.Config;
import kaptainwutax.seedcrackerX.elytra.ElytraHuntManager;
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
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 背包满时自动把物品存进背包内的潜影盒。
 *
 * <p>MC 26.3 原版不支持「在背包里直接打开潜影盒」（bundle 系列的容器点击交互只对
 * BundleItem 生效），所以这里走一条完全基于原版协议的链路，服务端不需要安装任何
 * 额外模组：</p>
 * <ol>
 *    <li>暂停自动挖矿 / 释放输入，把潜影盒拿在手上；</li>
 *    <li>在附近找一格空地放置潜影盒；</li>
 *    <li>空手右键打开盒子，服务器端会下发 ShulkerBoxMenu；</li>
 *    <li>对背包中的可堆叠物品逐格 QUICK_MOVE（等同 shift 点击）存入盒子；</li>
 *    <li>关闭界面、挖掉盒子、把带内容的盒子捡回背包；</li>
 *    <li>恢复挖矿。</li>
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
   private static final double MAX_REACH_SQ = 12.25;
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
      "green_bundle", "red_bundle", "black_bundle"
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

   /** 存盒流程是否正在占用玩家操作（自动挖矿等其它自动化需要让路）。 */
   public boolean isBusy() {
      return this.state != State.IDLE;
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
         && this.state.ordinal() >= State.BREAK.ordinal()) {
         // 盒子已经不在地上了，直接进入捡回确认。
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
      // 不打断玩家正在进行的界面操作，也不和鞘翅自动收集抢控制权。
      if (Compat.screen(mc) != null || !(player.containerMenu instanceof InventoryMenu)) {
         return;
      }

      try {
         if (ElytraHuntManager.INSTANCE.isRunning()) {
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
         // 附近没有安全放置点，稍后再试，不刷屏。
         this.cooldown = 100;
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
      this.supportPos = support;
      this.placedPos = support.above();
      this.attemptedSlots.clear();
      this.initialShulkerCount = this.countShulkers(player);
      this.startLevel = mc.level;
      this.originalSelectedSlot = player.getInventory().getSelectedSlot();
      this.changedSelectedSlot = false;

      this.releaseInputs(mc);
      if (AutoMineManager.INSTANCE.isActive() && FabricLoader.getInstance().isModLoaded("baritone")) {
         try {
            BaritoneBridge.cancel();
         } catch (Throwable t) {
            LOGGER.warn("[shulker-store] failed to pause Baritone", t);
         }
      }

      this.info("背包已满，开始把物品存入潜影盒…", ChatFormatting.GOLD);
   }

   /** 准备：等两拍让 Baritone / 按键状态落定，然后把潜影盒拿在手上。 */
   private void tickPrepare(Minecraft mc, LocalPlayer player) {
      if (this.ticks < 2) {
         return;
      }

      Inventory inv = player.getInventory();
      if (this.boxInvSlot < 9) {
         inv.setSelectedSlot(this.boxInvSlot);
         this.changedSelectedSlot = true;
         // 本流程挂在 END_CLIENT_TICK，比 Minecraft.tick() 的自动同步晚，必须立刻发包，
         // 否则服务端收到 useItemOn 时还以为玩家拿的是旧槽位的物品。
         if (mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundSetCarriedItemPacket(this.boxInvSlot));
         }
      } else {
         int menuSlot = this.menuSlotOfInventoryIndex(this.boxInvSlot);
         mc.gameMode.handleContainerInput(0, menuSlot, this.originalSelectedSlot, ContainerInput.SWAP, player);
      }

      if (!this.isShulkerItem(player.getMainHandItem())) {
         this.abort("无法把潜影盒切换到手持栏，放弃本次存盒。", false);
         return;
      }

      this.faceAt(player, Vec3.atCenterOf(this.placedPos));
      this.transition(State.PLACE);
   }

   /** 放置：拿着潜影盒右键支撑方块的顶面。 */
   private void tickPlace(Minecraft mc, LocalPlayer player) {
      if (!this.placementStillValid(mc.level, player)) {
         this.abort("放置位置已失效，放弃本次存盒。", false);
         return;
      }

      this.releaseUseKeys(mc);
      this.faceAt(player, Vec3.atCenterOf(this.placedPos));
      Vec3 hitPoint = new Vec3(this.supportPos.getX() + 0.5, this.supportPos.getY() + 1.0, this.supportPos.getZ() + 0.5);
      BlockHitResult hit = new BlockHitResult(hitPoint, Direction.UP, this.supportPos, false);

      InteractionResult result;
      try {
         result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
      } catch (Throwable t) {
         LOGGER.warn("[shulker-store] place failed", t);
         this.abort("放置潜影盒失败，放弃本次存盒。", false);
         return;
      }

      boolean placed = mc.level.getBlockState(this.placedPos).getBlock() instanceof ShulkerBoxBlock;
      if (!placed && (result == null || !result.consumesAction())) {
         this.abort("放置潜影盒被拒绝（可能被领地保护或反作弊拦截）。", false);
         return;
      }

      this.transition(State.WAIT_PLACE);
   }

   /** 等服务端确认方块确实放下了，并确保主手不再是潜影盒（否则右键会再放一个）。 */
   private void tickWaitPlace(Minecraft mc, LocalPlayer player) {
      if (!(mc.level.getBlockState(this.placedPos).getBlock() instanceof ShulkerBoxBlock)) {
         if (this.ticks > 30) {
            this.abort("服务端未确认潜影盒放置，放弃本次存盒。", false);
         }
         return;
      }

      if (this.ticks < 3) {
         return;
      }

      if (this.isShulkerItem(player.getMainHandItem())) {
         // 潜影盒是整叠放置的，正常情况主手会变空；这里兜底处理多个潜影盒叠在一起的情况。
         Inventory inv = player.getInventory();
         int other = -1;
         for (int i = 0; i < 9; i++) {
            if (i != inv.getSelectedSlot() && !this.isShulkerItem(inv.getItem(i)) && !inv.getItem(i).isEmpty()) {
               other = i;
               break;
            }
         }

         if (other < 0) {
            this.abort("主手仍是潜影盒，无法安全打开盒子。", true);
            return;
         }

         mc.gameMode.handleContainerInput(0, this.menuSlotOfInventoryIndex(other), inv.getSelectedSlot(), ContainerInput.SWAP, player);
      }

      this.transition(State.OPEN_BOX);
   }

   /** 空手右键打开刚放下的潜影盒。 */
   private void tickOpenBox(Minecraft mc, LocalPlayer player) {
      if (!(mc.level.getBlockState(this.placedPos).getBlock() instanceof ShulkerBoxBlock)) {
         this.abort("潜影盒方块已消失，放弃打开。", true);
         return;
      }

      this.releaseUseKeys(mc);
      this.faceAt(player, Vec3.atCenterOf(this.placedPos));
      Vec3 center = Vec3.atCenterOf(this.placedPos);
      BlockHitResult hit = new BlockHitResult(center, Direction.UP, this.placedPos, false);
      mc.hitResult = hit;

      try {
         mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
      } catch (Throwable t) {
         LOGGER.warn("[shulker-store] open failed", t);
         this.abort("打开潜影盒失败，正在把盒子取回。", true);
         return;
      }

      this.transition(State.WAIT_OPEN);
   }

   private void tickWaitOpen(Minecraft mc, LocalPlayer player) {
      if (player.containerMenu instanceof ShulkerBoxMenu) {
         this.transition(State.STORE);
         return;
      }

      if (this.ticks > 60) {
         this.abort("潜影盒界面打开超时（服务器可能禁止放置/交互），正在把盒子取回。", true);
      }
   }

   /** 逐格 shift-click 把背包物品移进盒子。矿石优先，其它可堆叠杂物限量存入。 */
   private void tickStore(Minecraft mc, LocalPlayer player) {
      if (!(player.containerMenu instanceof ShulkerBoxMenu menu)) {
         this.abort("潜影盒界面被关闭，正在收尾。", true);
         return;
      }

      if (this.ticks > 160 || this.moves >= MAX_MOVES) {
         this.closeAndBreak(mc, player);
         return;
      }

      // 给服务端一点时间同步上一次点击的结果。
      if (this.ticks % 2 != 0) {
         return;
      }

      Inventory inv = player.getInventory();
      int slotIndex = this.nextStoreSlot(menu, inv, true);
      boolean ore = true;
      if (slotIndex < 0 && this.nonOreMoves < MAX_NON_ORE_MOVES) {
         slotIndex = this.nextStoreSlot(menu, inv, false);
         ore = false;
      }

      if (slotIndex < 0) {
         this.closeAndBreak(mc, player);
         return;
      }

      this.attemptedSlots.add(slotIndex);
      this.moves++;
      if (!ore) {
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

   /** 挖回盒子：与 FlyController 一致，鼠标被抓取时交给原版驱动，否则自己驱动。 */
   private void tickBreak(Minecraft mc, LocalPlayer player) {
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

   /** 确认带内容的潜影盒回到背包；必要时朝掉落物走几步。 */
   private void tickCollect(Minecraft mc, LocalPlayer player) {
      if (this.countShulkers(player) >= this.initialShulkerCount) {
         this.finish(
            !this.failed,
            this.failed ? "潜影盒已取回，但本次存入未完成。" : "已存入潜影盒并取回，继续挖矿。"
         );
         return;
      }

      if (this.ticks > 120) {
         this.warn("潜影盒未自动捡回，掉落物应在 " + this.posText(this.placedPos) + " 附近，请手动捡起！", ChatFormatting.YELLOW);
         this.finish(false, null);
         return;
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
      double dz = target.z - player.getZ();
      player.setYRot((float)(Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
      mc.options.keyUp.setDown(true);
      mc.options.keySprint.setDown(false);
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
         if (mc.player != null && this.changedSelectedSlot) {
            try {
               mc.player.getInventory().setSelectedSlot(this.originalSelectedSlot);
               if (mc.getConnection() != null) {
                  mc.getConnection().send(new ServerboundSetCarriedItemPacket(this.originalSelectedSlot));
               }
            } catch (Throwable ignored) {
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

      this.state = State.IDLE;
      this.ticks = 0;
      this.totalTicks = 0;
      this.placedPos = null;
      this.supportPos = null;
      this.boxInvSlot = -1;
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
         if (this.isShulkerItem(stack) && this.boxHasCapacity(stack)) {
            return i;
         }
      }

      return -1;
   }

   /** 盒内还有空格，或存在尚未堆满的同类堆叠，都算「有空间」。 */
   private boolean boxHasCapacity(ItemStack box) {
      NonNullList<ItemStack> contents = NonNullList.withSize(27, ItemStack.EMPTY);
      try {
         ItemContainerContents component = box.get(DataComponents.CONTAINER);
         if (component != null) {
            component.copyInto(contents);
         }
      } catch (Throwable t) {
         return true;
      }

      for (ItemStack stack : contents) {
         if (stack.isEmpty() || stack.getCount() < stack.getMaxStackSize()) {
            return true;
         }
      }

      return false;
   }

   private boolean isStorable(ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return false;
      }

      if (this.isShulkerItem(stack) || stack.getMaxStackSize() <= 1) {
         return false;
      }

      if (stack.has(DataComponents.CONTAINER) || stack.has(DataComponents.CONTAINER_LOOT)) {
         return false;
      }

      // 食物、药水、工具、装备等留给玩家自己用。
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

   private int nextStoreSlot(ShulkerBoxMenu menu, Inventory inv, boolean oresOnly) {
      for (int i = 0; i < menu.slots.size(); i++) {
         if (this.attemptedSlots.contains(i)) {
            continue;
         }

         Slot slot = menu.getSlot(i);
         if (slot.container != inv) {
            continue;
         }

         ItemStack stack = slot.getItem();
         if (!this.isStorable(stack) || (oresOnly && !this.isOre(stack))) {
            continue;
         }

         if (!this.boxHasSpaceFor(menu, stack)) {
            continue;
         }

         return i;
      }

      return -1;
   }

   private boolean boxHasSpaceFor(ShulkerBoxMenu menu, ItemStack stack) {
      int boxSlots = Math.min(27, menu.slots.size());
      for (int i = 0; i < boxSlots; i++) {
         ItemStack inBox = menu.getSlot(i).getItem();
         if (inBox.isEmpty()) {
            return true;
         }

         if (ItemStack.isSameItem(inBox, stack) && inBox.getCount() < inBox.getMaxStackSize()) {
            return true;
         }
      }

      return false;
   }

   /** 找一格靠近玩家、上方无遮挡、下面有实心支撑的空地。 */
   private BlockPos findPlacementSpot(Level level, LocalPlayer player) {
      BlockPos feet = player.blockPosition();
      BlockPos head = feet.above();
      Vec3 eye = player.getEyePosition();
      BlockPos best = null;
      double bestDist = Double.MAX_VALUE;

      for (int dy = -1; dy <= 1; dy++) {
         for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
               int horizontal = dx * dx + dz * dz;
               if (horizontal == 0 || horizontal > 4) {
                  continue;
               }

               BlockPos support = feet.offset(dx, dy, dz);
               BlockPos target = support.above();
               if (target.equals(feet) || target.equals(head)) {
                  continue;
               }

               BlockState supportState = level.getBlockState(support);
               BlockState targetState = level.getBlockState(target);
               if (!supportState.isFaceSturdy(level, support, Direction.UP)) {
                  continue;
               }

               if (!targetState.isAir() && !targetState.canBeReplaced()) {
                  continue;
               }

               // 潜影盒打开需要上方留空。
               if (!level.getBlockState(target.above()).isAir()) {
                  continue;
               }

               if (!level.getFluidState(target).isEmpty()) {
                  continue;
               }

               Vec3 hitPoint = new Vec3(support.getX() + 0.5, support.getY() + 1.0, support.getZ() + 0.5);
               double distSq = eye.distanceToSqr(hitPoint);
               if (distSq > MAX_REACH_SQ) {
                  continue;
               }

               if (distSq < bestDist) {
                  bestDist = distSq;
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

      BlockState target = level.getBlockState(this.placedPos);
      return target.isAir() || target.canBeReplaced();
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
