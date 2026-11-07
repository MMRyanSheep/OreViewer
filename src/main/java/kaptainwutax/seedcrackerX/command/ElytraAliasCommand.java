package kaptainwutax.seedcrackerX.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

/** Optional alias so {@code /os elytra ...} behaves exactly like {@code /os qc ...}. */
public class ElytraAliasCommand extends ClientCommand {
   @Override
   public String getName() {
      return "elytra";
   }

   @Override
   public void build(LiteralArgumentBuilder<FabricClientCommandSource> builder) {
      ElytraCommand.buildShared(builder);
   }
}
