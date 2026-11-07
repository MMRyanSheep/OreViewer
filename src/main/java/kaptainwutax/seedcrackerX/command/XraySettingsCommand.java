package kaptainwutax.seedcrackerX.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import kaptainwutax.seedcrackerX.SeedCracker;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

public class XraySettingsCommand extends ClientCommand {
   @Override
   public String getName() {
      return "xs";
   }

   @Override
   public void build(LiteralArgumentBuilder<FabricClientCommandSource> builder) {
      builder.executes(context -> this.openXrayGui());
   }

   private int openXrayGui() {
      SeedCracker.get().getDataStorage().openXrayGui = true;
      return 0;
   }
}
