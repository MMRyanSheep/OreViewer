package kaptainwutax.seedcrackerX.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import kaptainwutax.seedcrackerX.SeedCracker;
import kaptainwutax.seedcrackerX.config.Config;
import kaptainwutax.seedcrackerX.cracker.storage.DataStorage;
import kaptainwutax.seedcrackerX.util.Log;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;

public class KickstartCommand extends ClientCommand {
   @Override
   public String getName() {
      return "ks";
   }

   @Override
   public void build(LiteralArgumentBuilder<FabricClientCommandSource> builder) {
      builder.executes(context -> this.kickstart());
   }

   private int kickstart() {
      Config config = Config.get();
      boolean wasInactive = !config.active;
      if (wasInactive) {
         SeedCracker.get().reset();
      }

      config.active = true;
      if (config.render == Config.RenderType.OFF) {
         config.render = Config.RenderType.XRAY;
      }

      Config.save();
      DataStorage storage = SeedCracker.get().getDataStorage();
      int baseBits = (int)storage.getBaseBits();
      int wantedBits = (int)storage.getWantedBits();
      int liftingBits = (int)storage.getLiftingBits();
      if (wasInactive) {
         sendFeedback(Log.translate("ks.started"), ChatFormatting.GREEN);
      } else {
         sendFeedback(Log.translate("ks.alreadyRunning"), ChatFormatting.YELLOW);
      }

      String progressMsg = Log.translate("ks.progress").replace("%s1", String.valueOf(baseBits)).replace("%s2", String.valueOf(wantedBits));
      sendFeedback(progressMsg, ChatFormatting.AQUA);
      String liftingMsg = Log.translate("ks.lifting").replace("%s1", String.valueOf(liftingBits));
      sendFeedback(liftingMsg, ChatFormatting.AQUA);
      sendFeedback(Log.translate("ks.hint"), ChatFormatting.GRAY);
      return 0;
   }
}
