package kaptainwutax.seedcrackerX.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

/** Optional alias so {@code /ov elytra ...} behaves exactly like {@code /ov qc ...}. */
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
