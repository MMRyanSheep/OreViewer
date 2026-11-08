package kaptainwutax.seedcrackerX.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import kaptainwutax.seedcrackerX.autoeat.AutoEatManager;
import kaptainwutax.seedcrackerX.config.Config;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/** /ov eat —— 饿肚子自动吃背包食物的开关与阈值。 */
public class AutoEatCommand extends ClientCommand {
   @Override
   public String getName() {
      return "eat";
   }

   @Override
   public void build(LiteralArgumentBuilder<FabricClientCommandSource> builder) {
      builder.executes(context -> this.set(!Config.get().autoEatEnabled));
      builder.then(ClientCommands.literal("开").executes(context -> this.set(true)));
      builder.then(ClientCommands.literal("关").executes(context -> this.set(false)));
      builder.then(ClientCommands.literal("状态").executes(context -> this.status()));
      builder.then(
         ClientCommands.literal("阈值")
            .then(
               ClientCommands.argument("值", IntegerArgumentType.integer(1, 19))
                  .executes(context -> this.setThreshold(IntegerArgumentType.getInteger(context, "值")))
            )
      );
   }

   private int set(boolean on) {
      Config.get().autoEatEnabled = on;
      Config.save();
      if (!on) {
         AutoEatManager.INSTANCE.cancel();
      }
      this.status();
      return 1;
   }

   private int setThreshold(int value) {
      Config.get().autoEatThreshold = Math.max(1, Math.min(19, value));
      Config.save();
      this.status();
      return 1;
   }

   private int status() {
      boolean on = Config.get().autoEatEnabled;
      Minecraft mc = Minecraft.getInstance();
      LocalPlayer player = mc == null ? null : mc.player;
      int hunger = player == null ? -1 : player.getFoodData().getFoodLevel();
      int food = player == null ? -1 : AutoEatManager.countFood(player);
      sendFeedback(
         "[自动进食] 当前" + (on ? "已开启" : "已关闭")
            + "，触发阈值：饱食度 ≤ " + AutoEatManager.threshold() + "/20"
            + (hunger >= 0 ? "，当前饱食度 " + hunger + "/20，可用食物 " + food + " 格" : "")
            + "。开启时：饱食度不足会自动挑最合适的食物（必要时临时换到快捷栏）进食，结束后还原。"
            + " 有毒/会瞬移/效果不明的食物不会自动食用。",
         on ? ChatFormatting.GREEN : ChatFormatting.YELLOW
      );
      return 1;
   }
}
