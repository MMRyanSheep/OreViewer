package kaptainwutax.seedcrackerX.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import kaptainwutax.seedcrackerX.automine.ShulkerAutoStore;
import kaptainwutax.seedcrackerX.config.Config;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;

/** /ov ch —— 背包满自动存潜影盒的快捷开关、实时诊断与手动触发。 */
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
      builder.then(ClientCommands.literal("立即").executes(context -> this.force()));
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
            + "。开启时：背包满 + 背包内有未满的潜影盒 → 自动把鞘翅、矿物等物品存入盒内并取回盒子。",
         on ? ChatFormatting.GREEN : ChatFormatting.YELLOW
      );
      sendFeedback("[自动存盒] " + ShulkerAutoStore.INSTANCE.statusLine(), ChatFormatting.GRAY);
      sendFeedback("[自动存盒] /ov ch 立即 可立即强制存盒一次（忽略开关与冷却）。", ChatFormatting.GRAY);
      return 1;
   }

   private int force() {
      ShulkerAutoStore.INSTANCE.forceStore();
      return 1;
   }
}
