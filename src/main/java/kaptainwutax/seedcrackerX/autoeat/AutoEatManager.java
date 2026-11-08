package kaptainwutax.seedcrackerX.autoeat;

import java.util.Set;
import kaptainwutax.seedcrackerX.automine.AutoMineManager;
import kaptainwutax.seedcrackerX.automine.ShulkerAutoStore;
import kaptainwutax.seedcrackerX.command.ClientCommand;
import kaptainwutax.seedcrackerX.config.Config;
import kaptainwutax.seedcrackerX.elytra.ElytraHuntManager;
import kaptainwutax.seedcrackerX.util.Compat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Consumable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 饱食度不足时自动吃背包里的食物（全局功能，不依赖自动挖矿是否开启）。
 *
 * <p>全程只使用原版协议：必要时用 SWAP 把食物从背包 27 格换到当前快捷栏槽，
 * 然后直接调用 {@code gameMode.useItem()}（等价于对着空气右键，不会误开面前的
 * 箱子/工作台），按住原版右键键位直到服务端同步「使用物品」状态结束，最后把
 * 快捷栏还原。不修改客户端物品栏数据，服务端仍为权威。</p>
 *
 * <p>MC 26.3 的客户端不会自行结束使用物品（{@code LivingEntity.updateUsingItem}
 * 只在服务端调用 {@code completeUsingItem}），客户端的 {@code isUsingItem()} 靠
 * 服务端下发的实体数据标志复位，所以这里以「服务端标志复位 + 饱食度上升」作为
 * 完成判定，并留若干 tick 等待服务端数据。</p>
 */
public final class AutoEatManager {
   public static final AutoEatManager INSTANCE = new AutoEatManager();
   private static final Logger LOGGER = LoggerFactory.getLogger("SeedCrackerX-AutoEat");

   private static final int SCAN_INTERVAL_TICKS = 5;
   private static final int CONFIRM_TICKS = 12;
   private static final int COOLDOWN_OK_TICKS = 15;
   private static final int COOLDOWN_FAIL_TICKS = 200;
   private static final int NO_FOOD_COOLDOWN_TICKS = 100;
   private static final int PREPARE_TIMEOUT_TICKS = 20;
   private static final int EAT_EXTRA_TICKS = 100;
   private static final int EMERGENCY_FOOD_LEVEL = 6;
   private static final long WARN_INTERVAL_MS = 60000L;

   /** 有毒/会瞬移/效果不明的食物，永不自动食用。 */
   private static final Set<String> NEVER_EAT = Set.of(
      "spider_eye", "pufferfish", "poisonous_potato", "chorus_fruit", "suspicious_stew"
   );
   /** 会造成饥饿效果或很贵重：仅在快要饿死（饱食度 <= 6）且没有普通食物时才考虑。 */
   private static final Set<String> EMERGENCY_ONLY = Set.of(
      "rotten_flesh", "chicken", "golden_apple", "enchanted_golden_apple"
   );

   private enum State {
      IDLE, PREPARE, EATING, CONFIRM
   }

   private State state = State.IDLE;
   private int cooldown;
   private int scanCounter;
   private int ticks;
   private int originalSelectedSlot;
   private int foodSlot = -1;
   private int swapMenuSlot = -1;
   private boolean changedSelectedSlot;
   private int requiredTicks;
   private int startFoodLevel;
   private long lastWarnMs;

   private AutoEatManager() {
   }

   /** 是否正在占用玩家操作（其它自动化应让路）。 */
   public boolean isBusy() {
      return this.state != State.IDLE;
   }

   public boolean isEnabled() {
      return Config.get().autoEatEnabled;
   }

   /** 触发进食的饱食度阈值，钳制在 1..19。 */
   public static int threshold() {
      return Math.max(1, Math.min(19, Config.get().autoEatThreshold));
   }

   /** 自动挖矿的旧进食逻辑也用这个阈值，关闭全局功能时回退到原来的 14。 */
   public static int triggerLevel() {
      return Config.get().autoEatEnabled ? threshold() : 14;
   }

   public void onClientTick() {
      Minecraft mc = Minecraft.getInstance();
      if (mc == null) {
         return;
      }

      if (this.state == State.IDLE) {
         if (this.cooldown > 0) {
            this.cooldown--;
         }

         if (this.cooldown > 0 || ++this.scanCounter % SCAN_INTERVAL_TICKS != 0) {
            return;
         }

         this.tryTrigger(mc);
         return;
      }

      try {
         LocalPlayer player = mc.player;
         if (player == null || mc.level == null || mc.gameMode == null || mc.options == null) {
            this.abort(mc, "世界已卸载，自动进食已中断。");
            return;
         }

         if (!player.isAlive()) {
            this.abort(mc, "玩家死亡，自动进食已中断。");
            return;
         }

         if (Compat.screen(mc) != null) {
            this.abort(mc, "打开了界面，自动进食已中断。");
            return;
         }

         switch (this.state) {
            case PREPARE -> this.tickPrepare(mc, player);
            case EATING -> this.tickEating(mc, player);
            case CONFIRM -> this.tickConfirm(mc, player);
            default -> this.abort(mc, "自动进食状态异常，已重置。");
         }
      } catch (Throwable t) {
         LOGGER.error("[auto-eat] tick error in state {}", this.state, t);
         this.abort(mc, "自动进食出错（" + t.getClass().getSimpleName() + "），已中断。");
      }
   }

   private void tryTrigger(Minecraft mc) {
      if (!this.isEnabled() || mc.player == null || mc.level == null || mc.gameMode == null || mc.options == null) {
         return;
      }

      LocalPlayer player = mc.player;
      // 不打断玩家自己的操作，也不和其它自动化抢控制权。
      if (!player.isAlive() || player.isSpectator() || player.isCreative()
         || Compat.screen(mc) != null || !(player.containerMenu instanceof InventoryMenu)
         || player.isUsingItem() || mc.options.keyUse.isDown() || mc.options.keyAttack.isDown()
         || mc.gameMode.isDestroying()) {
         return;
      }

      if (ShulkerAutoStore.INSTANCE.isBusy() || AutoMineManager.INSTANCE.isActive()) {
         return;
      }

      try {
         if (ElytraHuntManager.INSTANCE.isRunning()) {
            return;
         }
      } catch (Throwable ignored) {
         // 选装功能不可用时不影响自动进食。
      }

      int hunger = player.getFoodData().getFoodLevel();
      if (hunger > threshold() || !player.canEat(false)) {
         return;
      }

      int slot = foodSlot(player);
      if (slot < 0) {
         this.cooldown = NO_FOOD_COOLDOWN_TICKS;
         if (hunger <= EMERGENCY_FOOD_LEVEL) {
            this.warn("饱食度只剩 " + hunger + "/20，但背包里没有可自动食用的食物。", WARN_INTERVAL_MS);
         }
         return;
      }

      this.originalSelectedSlot = player.getInventory().getSelectedSlot();
      this.foodSlot = slot;
      this.swapMenuSlot = -1;
      this.changedSelectedSlot = false;
      this.startFoodLevel = hunger;
      this.ticks = 0;
      this.state = State.PREPARE;
      this.prepare(mc, player);
   }

   /** 把选中的食物放到主手上。背包 9..35 格的 menu 槽号与物品栏下标一致。 */
   private void prepare(Minecraft mc, LocalPlayer player) {
      if (this.foodSlot < 9) {
         if (this.foodSlot != this.originalSelectedSlot) {
            player.getInventory().setSelectedSlot(this.foodSlot);
            this.sendCarriedItem(player, this.foodSlot);
            this.changedSelectedSlot = true;
         }
      } else {
         mc.gameMode.handleContainerInput(
            player.containerMenu.containerId, this.foodSlot, this.originalSelectedSlot, ContainerInput.SWAP, player
         );
         this.swapMenuSlot = this.foodSlot;
      }
   }

   private void tickPrepare(Minecraft mc, LocalPlayer player) {
      this.ticks++;
      ItemStack hand = player.getMainHandItem();
      if (!isFoodStack(hand)) {
         if (this.ticks >= PREPARE_TIMEOUT_TICKS) {
            this.abort(mc, "食物没能切到手上，自动进食已取消。");
         }
         return;
      }

      // 清掉残留的右键点击计数，避免原版 handleKeybinds 再触发一次 startUseItem。
      while (mc.options.keyUse.consumeClick()) {
      }

      mc.options.keyUse.setDown(true);
      Consumable consumable = hand.get(DataComponents.CONSUMABLE);
      int consumeTicks = consumable == null ? 32 : consumable.consumeTicks();
      this.requiredTicks = Math.max(10, Math.min(consumeTicks, 40) - 4);

      InteractionResult result = mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
      if (!result.consumesAction()) {
         this.abort(mc, "服务端未接受进食动作，自动进食已取消。");
         return;
      }

      this.ticks = 0;
      this.state = State.EATING;
   }

   private void tickEating(Minecraft mc, LocalPlayer player) {
      this.ticks++;
      // 原版只有 keyUse 保持按下才不会 releaseUsingItem。
      mc.options.keyUse.setDown(true);

      boolean hungerRose = player.getFoodData().getFoodLevel() > this.startFoodLevel;
      boolean serverFinished = this.ticks >= this.requiredTicks && !player.isUsingItem();
      if (hungerRose || serverFinished) {
         // 立即松开：否则服务端标志复位后，原版会把「按住右键」当成新的一次右键再吃一口。
         mc.options.keyUse.setDown(false);
         // 若原版已经抢先重新起了一次使用物品，主动收尾，避免服务端多算一次右键。
         if (player.isUsingItem() && mc.gameMode != null) {
            mc.gameMode.releaseUsingItem(player);
         }
         this.ticks = 0;
         this.state = State.CONFIRM;
         return;
      }

      if (this.ticks > this.requiredTicks + EAT_EXTRA_TICKS) {
         this.abort(mc, "进食超时，自动进食已中断。");
      }
   }

   private void tickConfirm(Minecraft mc, LocalPlayer player) {
      this.ticks++;
      if (this.ticks < CONFIRM_TICKS) {
         return;
      }

      boolean success = player.getFoodData().getFoodLevel() > this.startFoodLevel;
      this.restore(mc, player);
      this.state = State.IDLE;
      this.cooldown = success ? COOLDOWN_OK_TICKS : COOLDOWN_FAIL_TICKS;
      if (!success) {
         this.warn("进食后饱食度没有上升（可能被服务器拦截），已暂停 10 秒后重试。", WARN_INTERVAL_MS);
      }
   }

   private void restore(Minecraft mc, LocalPlayer player) {
      if (mc.options != null) {
         mc.options.keyUse.setDown(false);
      }

      if (this.swapMenuSlot >= 0) {
         // 换回原样：食物（或吃剩的碗）回到原来的背包格，原来的工具回到快捷栏。
         if (mc.gameMode != null && player.containerMenu != null && player.containerMenu.containerId == 0) {
            mc.gameMode.handleContainerInput(0, this.swapMenuSlot, this.originalSelectedSlot, ContainerInput.SWAP, player);
         }
         this.swapMenuSlot = -1;
      } else if (this.changedSelectedSlot && player.getInventory().getSelectedSlot() == this.foodSlot) {
         // 玩家在此期间自己换了快捷栏就不打断他。
         player.getInventory().setSelectedSlot(this.originalSelectedSlot);
         this.sendCarriedItem(player, this.originalSelectedSlot);
      }

      this.changedSelectedSlot = false;
      this.foodSlot = -1;
   }

   private void abort(Minecraft mc, String reason) {
      if (mc.options != null) {
         mc.options.keyUse.setDown(false);
      }

      LocalPlayer player = mc.player;
      if (player != null) {
         if (player.isUsingItem() && mc.gameMode != null) {
            mc.gameMode.releaseUsingItem(player);
         }
         this.restore(mc, player);
      }

      this.state = State.IDLE;
      this.ticks = 0;
      this.cooldown = Math.max(this.cooldown, 40);
      LOGGER.debug("[auto-eat] {}", reason);
      if (Config.get().debug) {
         ClientCommand.sendFeedback("[自动进食] " + reason, ChatFormatting.YELLOW);
      }
   }

   /** 供指令/界面在关闭功能时立即收尾。 */
   public void cancel() {
      Minecraft mc = Minecraft.getInstance();
      if (mc != null && this.state != State.IDLE) {
         this.abort(mc, "功能已关闭，正在还原快捷栏。");
      }
   }

   private static void sendCarriedItem(LocalPlayer player, int slot) {
      if (player.connection != null) {
         player.connection.send(new ServerboundSetCarriedItemPacket(slot));
      }
   }

   private void warn(String message, long intervalMs) {
      long now = System.currentTimeMillis();
      if (now - this.lastWarnMs < intervalMs) {
         return;
      }

      this.lastWarnMs = now;
      ClientCommand.sendFeedback("[自动进食] " + message, ChatFormatting.GOLD);
   }

   /**
    * 挑一格最合适的食物：优先刚好补满（浪费最少），其次饱和度更高，最后优先快捷栏。
    * 返回物品栏下标 0..35；hotbar 是 0..8，背包主格是 9..35。
    */
   public static int foodSlot(LocalPlayer player) {
      Inventory inv = player.getInventory();
      int hunger = player.getFoodData().getFoodLevel();
      boolean emergency = hunger <= EMERGENCY_FOOD_LEVEL;
      int missing = 20 - hunger;
      int best = -1;
      int bestWaste = Integer.MAX_VALUE;
      float bestSaturation = -1.0F;

      for (int i = 0; i < inv.getContainerSize(); i++) {
         ItemStack stack = inv.getItem(i);
         if (!isEdible(stack, player, emergency)) {
            continue;
         }

         FoodProperties food = stack.get(DataComponents.FOOD);
         int nutrition = food.nutrition();
         int waste = Math.max(0, nutrition - missing);
         float saturation = food.saturation();
         boolean better = waste < bestWaste
            || (waste == bestWaste && saturation > bestSaturation + 1.0E-4F)
            || (waste == bestWaste && Math.abs(saturation - bestSaturation) <= 1.0E-4F
               && best >= 9 && i < 9);
         if (better) {
            best = i;
            bestWaste = waste;
            bestSaturation = saturation;
         }
      }

      return best;
   }

   /** 指令状态栏用：背包里当前可以自动食用的食物格数。 */
   public static int countFood(LocalPlayer player) {
      Inventory inv = player.getInventory();
      boolean emergency = player.getFoodData().getFoodLevel() <= EMERGENCY_FOOD_LEVEL;
      int count = 0;

      for (int i = 0; i < inv.getContainerSize(); i++) {
         if (isEdible(inv.getItem(i), player, emergency)) {
            count++;
         }
      }

      return count;
   }

   private static boolean isFoodStack(ItemStack stack) {
      return stack != null && !stack.isEmpty()
         && stack.has(DataComponents.FOOD) && stack.has(DataComponents.CONSUMABLE);
   }

   private static boolean isEdible(ItemStack stack, LocalPlayer player, boolean emergency) {
      if (!isFoodStack(stack)) {
         return false;
      }

      FoodProperties food = stack.get(DataComponents.FOOD);
      Consumable consumable = stack.get(DataComponents.CONSUMABLE);
      // nutrition <= 0 的食物吃了不涨饱食度，会造成反复空吃。
      if (food == null || food.nutrition() <= 0 || !consumable.canConsume(player, stack)) {
         return false;
      }

      if (player.getCooldowns().isOnCooldown(stack)) {
         return false;
      }

      String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
      return !NEVER_EAT.contains(id) && (emergency || !EMERGENCY_ONLY.contains(id));
   }
}
