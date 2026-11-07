package kaptainwutax.seedcrackerX.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import kaptainwutax.seedcrackerX.automine.AutoMineManager;
import kaptainwutax.seedcrackerX.cracker.bedrock.BedrockCollector;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;

public class ProCommand extends ClientCommand {
   @Override
   public String getName() {
      return "pro";
   }

   @Override
   public void build(LiteralArgumentBuilder<FabricClientCommandSource> builder) {
      ((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)builder.executes(
                        context -> {
                           set(!AutoMineManager.proMode);
                           return 1;
                        }
                     ))
                     .then(ClientCommands.literal("on").executes(context -> {
                        set(true);
                        return 1;
                     })))
                  .then(ClientCommands.literal("off").executes(context -> {
                     set(false);
                     return 1;
                  })))
               .then(ClientCommands.literal("status").executes(context -> {
                  ClientCommand.sendFeedback(BedrockCollector.get().status(), ChatFormatting.AQUA);
                  return 1;
               })))
            .then(ClientCommands.literal("crack").executes(context -> {
               if (!AutoMineManager.proMode) {
                  ClientCommand.sendFeedback("请先使用 /os pro on 开启破混淆模式（地狱采集基岩，或末地扫描黑曜石柱）", ChatFormatting.YELLOW);
                  return 1;
               } else if (BedrockCollector.get().isCracking()) {
                  ClientCommand.sendFeedback("破译已经在进行中，请稍候", ChatFormatting.YELLOW);
                  return 1;
               } else {
                  ClientCommand.sendFeedback("手动触发基岩破译，正在评估已采集数据…", ChatFormatting.GOLD);
                  BedrockCollector.get().maybeAutoStart(true);
                  return 1;
               }
            })))
         .then(ClientCommands.literal("reset").executes(context -> {
            BedrockCollector.get().reset();
            ClientCommand.sendFeedback("已清空基岩采集数据，可重新采集", ChatFormatting.GREEN);
            return 1;
         }));
   }

   private static void set(boolean on) {
      AutoMineManager.setProMode(on);
      if (on) {
         BedrockCollector.get().reset();
         ClientCommand.sendFeedback("已开启破混淆（反种子混淆）模式", ChatFormatting.GREEN);
         ClientCommand.sendFeedback(
            "破混淆：地狱自动采集顶层(y123-126)/底层(y1-4)基岩，末地自动扫描黑曜石柱；正常移动扩大范围，数据足够后自动破译真实种子（/os pro status 查看进度，/os pro crack 手动提交基岩破译）", ChatFormatting.AQUA
         );
         ClientCommand.sendFeedback("破译出种子后点击聊天框 [直接设置]，之后 /os zd 钻石 等自动挖矿将按种子预测位置挖掘", ChatFormatting.YELLOW);
      } else {
         BedrockCollector.get().reset();
         ClientCommand.sendFeedback("已关闭破混淆模式，种子功能恢复正常（基岩采集数据已清空）", ChatFormatting.GREEN);
      }
   }
}
