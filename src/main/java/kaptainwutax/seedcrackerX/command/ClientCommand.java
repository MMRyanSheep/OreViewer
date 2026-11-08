package kaptainwutax.seedcrackerX.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import kaptainwutax.seedcrackerX.util.Compat;
import kaptainwutax.seedcrackerX.util.Log;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

public abstract class ClientCommand {
   public static void sendFeedback(String message, ChatFormatting color) {
      try {
         Compat.sendSystemMessage(Component.literal(message).withStyle(color));
      } catch (Exception var3) {
      }
   }

   public abstract String getName();

   public abstract void build(LiteralArgumentBuilder<FabricClientCommandSource> var1);

   public final void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
      LiteralArgumentBuilder<FabricClientCommandSource> builder = ClientCommands.literal(this.getName());
      this.build(builder);
      LiteralArgumentBuilder<FabricClientCommandSource> seedCrackerRootCommand = (LiteralArgumentBuilder<FabricClientCommandSource>)ClientCommands.literal("ov")
         .executes(context -> {
            Log.error("错误：请输入有效的指令，例如 /ov ks 开始反推 或 /ov sz 种子 设置矿物透视");
            return 1;
         });
      dispatcher.register((LiteralArgumentBuilder)seedCrackerRootCommand.then(builder));
   }
}
