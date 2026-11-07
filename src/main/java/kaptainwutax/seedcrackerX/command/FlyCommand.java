package kaptainwutax.seedcrackerX.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import kaptainwutax.seedcrackerX.fly.FlyManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;

public class FlyCommand extends ClientCommand {
   @Override
   public String getName() {
      return "f";
   }

   @Override
   public void build(LiteralArgumentBuilder<FabricClientCommandSource> builder) {
      ((LiteralArgumentBuilder)((LiteralArgumentBuilder)builder.executes(context -> {
         set(!FlyManager.isEnabled());
         return 1;
      })).then(ClientCommands.literal("on").executes(context -> {
         set(true);
         return 1;
      }))).then(ClientCommands.literal("off").executes(context -> {
         set(false);
         return 1;
      }));
   }

   private static void set(boolean on) {
      FlyManager.setEnabled(on);
      if (on) {
         ClientCommand.sendFeedback("已开启强制飞行：双击空格起飞，Shift 下降，免疫摔落伤害；服务器反作弊可能回弹或踢出", ChatFormatting.GREEN);
      } else {
         ClientCommand.sendFeedback("已关闭强制飞行", ChatFormatting.GREEN);
      }
   }
}
