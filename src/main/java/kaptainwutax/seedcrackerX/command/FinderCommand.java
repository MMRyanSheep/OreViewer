package kaptainwutax.seedcrackerX.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import kaptainwutax.seedcrackerX.config.Config;
import kaptainwutax.seedcrackerX.finder.Finder;
import kaptainwutax.seedcrackerX.finder.ReloadFinders;
import kaptainwutax.seedcrackerX.util.Log;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;

public class FinderCommand extends ClientCommand {
   ReloadFinders reloadFinders = new ReloadFinders();

   @Override
   public String getName() {
      return "finder";
   }

   @Override
   public void build(LiteralArgumentBuilder<FabricClientCommandSource> builder) {
      for (Finder.Type finderType : Finder.Type.values()) {
         builder.then(
            ClientCommands.literal("type")
               .then(
                  ((LiteralArgumentBuilder)((LiteralArgumentBuilder)ClientCommands.literal(finderType.toString())
                           .then(ClientCommands.literal("ON").executes(context -> this.setFinderType(finderType, true, true))))
                        .then(ClientCommands.literal("OFF").executes(context -> this.setFinderType(finderType, false, true))))
                     .executes(context -> this.printFinderType(finderType))
               )
         );
      }

      for (Finder.Category finderCategory : Finder.Category.values()) {
         builder.then(
            ClientCommands.literal("category")
               .then(
                  ((LiteralArgumentBuilder)((LiteralArgumentBuilder)ClientCommands.literal(finderCategory.toString())
                           .then(ClientCommands.literal("ON").executes(context -> this.setFinderCategory(finderCategory, true))))
                        .then(ClientCommands.literal("OFF").executes(context -> this.setFinderCategory(finderCategory, false))))
                     .executes(context -> this.printFinderCategory(finderCategory))
               )
         );
      }

      builder.then(ClientCommands.literal("reload").executes(context -> this.reload()));
   }

   private int printFinderCategory(Finder.Category finderCategory) {
      Finder.Type.getForCategory(finderCategory).forEach(this::printFinderType);
      return 0;
   }

   private int printFinderType(Finder.Type finderType) {
      sendFeedback(
         Log.translate("finder.isFinder").formatted(Log.translate(finderType.nameKey)) + " [" + String.valueOf(finderType.enabled.get()).toUpperCase() + "].",
         ChatFormatting.AQUA
      );
      return 0;
   }

   private int setFinderCategory(Finder.Category finderCategory, boolean flag) {
      Finder.Type.getForCategory(finderCategory).forEach(finderType -> this.setFinderType(finderType, flag, false));
      Config.save();
      return 0;
   }

   private int setFinderType(Finder.Type finderType, boolean flag, boolean save) {
      finderType.enabled.set(flag);
      if (save) {
         Config.save();
      }

      sendFeedback(
         Log.translate("finder.setFinder").formatted(Log.translate(finderType.nameKey)) + " [" + String.valueOf(flag).toUpperCase() + "].", ChatFormatting.AQUA
      );
      return 0;
   }

   private int reload() {
      this.reloadFinders.reload();
      return 0;
   }
}
