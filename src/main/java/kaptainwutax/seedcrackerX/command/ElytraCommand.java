package kaptainwutax.seedcrackerX.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import kaptainwutax.seedcrackerX.elytra.ElytraHuntManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

/** {@code /ov qc ...} - automatic elytra hunt in the outer End islands. */
public class ElytraCommand extends ClientCommand {
   @Override
   public String getName() {
      return "qc";
   }

   @Override
   public void build(LiteralArgumentBuilder<FabricClientCommandSource> builder) {
      builder.executes(context -> {
         ElytraHuntManager.INSTANCE.start();
         return 1;
      });
      builder.then(ClientCommands.literal("停止").executes(context -> stop()));
      builder.then(ClientCommands.literal("stop").executes(context -> stop()));
      builder.then(ClientCommands.literal("调试").executes(context -> debug()));
      builder.then(ClientCommands.literal("debug").executes(context -> debug()));
      builder.then(ClientCommands.literal("重扫").executes(context -> rescan()));
      builder.then(ClientCommands.literal("rescan").executes(context -> rescan()));
      builder.then(
         ClientCommands.literal("数量")
            .then(
               ClientCommands.argument("n", IntegerArgumentType.integer(1, 32)).executes(context -> {
                  ElytraHuntManager.INSTANCE.setCandidateCount(IntegerArgumentType.getInteger(context, "n"));
                  return 1;
               })
            )
      );
      // 0 = unlimited (collect until /ov qc 停止); n = stop after n new elytra.
      builder.then(
         ClientCommands.literal("目标")
            .then(
               ClientCommands.argument("n", IntegerArgumentType.integer(0, 64)).executes(context -> {
                  ElytraHuntManager.INSTANCE.setTargetElytra(IntegerArgumentType.getInteger(context, "n"));
                  return 1;
               })
            )
      );
      builder.then(
         ClientCommands.literal("target")
            .then(
               ClientCommands.argument("n", IntegerArgumentType.integer(0, 64)).executes(context -> {
                  ElytraHuntManager.INSTANCE.setTargetElytra(IntegerArgumentType.getInteger(context, "n"));
                  return 1;
               })
            )
      );
   }

   /** Shared by the optional {@code /ov elytra} alias. */
   static void buildShared(LiteralArgumentBuilder<FabricClientCommandSource> builder) {
      new ElytraCommand().build(builder);
   }

   private static int stop() {
      ElytraHuntManager.INSTANCE.stop();
      return 1;
   }

   private static int debug() {
      ElytraHuntManager.INSTANCE.debug();
      return 1;
   }

   private static int rescan() {
      ElytraHuntManager.INSTANCE.rescan();
      return 1;
   }
}
