package kaptainwutax.seedcrackerX.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import kaptainwutax.seedcrackerX.SeedCracker;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

public class HelpCommand extends ClientCommand {
   @Override
   public String getName() {
      return "help";
   }

   @Override
   public void build(LiteralArgumentBuilder<FabricClientCommandSource> builder) {
      builder.executes(context -> this.openHelpGui());
   }

   private int openHelpGui() {
      SeedCracker.get().getDataStorage().openHelpGui = true;
      return 0;
   }
}
