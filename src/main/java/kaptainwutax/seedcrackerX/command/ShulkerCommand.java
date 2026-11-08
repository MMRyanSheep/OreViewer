package kaptainwutax.seedcrackerX.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import kaptainwutax.seedcrackerX.config.Config;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;

/** /os ch —— 背包满自动存潜影盒的快捷开关。 */
public class ShulkerCommand extends ClientCommand {
   @Override
   public String getName() {
      return "ch";
   }

   @Override
   public void build(LiteralArgumentBuilder<FabricClientCommandSource> builder) {
      builder.executes(context -> this.set(!Config.get().autoMineShulkerSave));
      builder.then(ClientCommands.literal("开").executes(context -> this.set(true)));
      builder.then(ClientCommands.literal("关").executes(context -> this.set(false)));
      builder.then(ClientCommands.literal("状态").executes(context -> this.status()));
   }

   private int set(boolean on) {
      Config.get().autoMineShulkerSave = on;
      Config.save();
      this.status();
      return 1;
   }

   private int status() {
      boolean on = Config.get().autoMineShulkerSave;
      sendFeedback(
         "[自动存盒] 当前" + (on ? "已开启" : "已关闭")
            + "。开启时：背包满 + 背包内有未满的潜影盒 → 自动把可堆叠物品存入盒内并取回盒子。",
         on ? ChatFormatting.GREEN : ChatFormatting.YELLOW
      );
      return 1;
   }
}
