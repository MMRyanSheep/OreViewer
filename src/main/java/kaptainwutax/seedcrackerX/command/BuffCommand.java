package kaptainwutax.seedcrackerX.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import kaptainwutax.seedcrackerX.SeedCracker;
import kaptainwutax.seedcrackerX.buff.BuffManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

public class BuffCommand extends ClientCommand {
   @Override
   public String getName() {
      return "buff";
   }

   @Override
   public void build(LiteralArgumentBuilder<FabricClientCommandSource> builder) {
      builder.executes(context -> this.openBuffGui());
   }

   private int openBuffGui() {
      BuffManager.INSTANCE.loadFromConfig();
      SeedCracker.get().getDataStorage().openBuffGui = true;
      return 0;
   }
}
