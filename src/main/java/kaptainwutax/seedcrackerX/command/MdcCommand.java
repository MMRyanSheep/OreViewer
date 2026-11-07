package kaptainwutax.seedcrackerX.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import kaptainwutax.seedcrackerX.guidance.GuidanceManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;

public class MdcCommand extends ClientCommand {
   @Override
   public String getName() {
      return "mdc";
   }

   @Override
   public void build(LiteralArgumentBuilder<FabricClientCommandSource> builder) {
      ((LiteralArgumentBuilder)((LiteralArgumentBuilder)builder.then(ClientCommands.literal("on").executes(c -> this.start())))
            .then(ClientCommands.literal("off").executes(c -> this.stop())))
         .executes(c -> this.start());
   }

   private int start() {
      GuidanceManager.INSTANCE.setStructureTarget(GuidanceManager.TargetKind.END_CITY, 3);
      return 0;
   }

   private int stop() {
      GuidanceManager.INSTANCE.clear();
      sendFeedback("[指引] 已关闭末地城指引。", ChatFormatting.YELLOW);
      return 0;
   }
}
