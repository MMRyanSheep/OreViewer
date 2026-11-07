package kaptainwutax.seedcrackerX.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import kaptainwutax.seedcrackerX.guidance.GuidanceManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;

public class DxCommand extends ClientCommand {
   @Override
   public String getName() {
      return "dx";
   }

   @Override
   public void build(LiteralArgumentBuilder<FabricClientCommandSource> builder) {
      builder.then(ClientCommands.literal("off").executes(c -> this.stop()));
      builder.then(ClientCommands.literal("海底神殿").executes(c -> this.structure(GuidanceManager.TargetKind.MONUMENT)));
      builder.then(ClientCommands.literal("沙漠神殿").executes(c -> this.structure(GuidanceManager.TargetKind.DESERT_PYRAMID)));
      builder.then(ClientCommands.literal("女巫小屋").executes(c -> this.structure(GuidanceManager.TargetKind.SWAMP_HUT)));
      builder.then(ClientCommands.literal("丛林神庙").executes(c -> this.structure(GuidanceManager.TargetKind.JUNGLE_PYRAMID)));
      builder.then(ClientCommands.literal("雪屋").executes(c -> this.structure(GuidanceManager.TargetKind.IGLOO)));
      builder.then(ClientCommands.literal("掠夺者哨塔").executes(c -> this.structure(GuidanceManager.TargetKind.OUTPOST)));
      builder.then(ClientCommands.literal("沉船").executes(c -> this.structure(GuidanceManager.TargetKind.SHIPWRECK)));
      builder.then(ClientCommands.literal("埋藏的宝藏").executes(c -> this.structure(GuidanceManager.TargetKind.BURIED_TREASURE)));
      builder.then(ClientCommands.literal("试炼密室").executes(c -> this.structure(GuidanceManager.TargetKind.TRIAL_CHAMBERS)));
      builder.then(ClientCommands.literal("末地城").executes(c -> this.structure(GuidanceManager.TargetKind.END_CITY)));
      builder.then(ClientCommands.literal("古城遗址").executes(c -> this.structure(GuidanceManager.TargetKind.ANCIENT_CITY)));
      builder.then(ClientCommands.literal("古城").executes(c -> this.structure(GuidanceManager.TargetKind.ANCIENT_CITY)));
      builder.then(ClientCommands.literal("地狱堡垒").executes(c -> this.structure(GuidanceManager.TargetKind.NETHER_FORTRESS)));
      builder.then(ClientCommands.literal("下界要塞").executes(c -> this.structure(GuidanceManager.TargetKind.NETHER_FORTRESS)));
      builder.then(ClientCommands.literal("猪灵堡垒").executes(c -> this.structure(GuidanceManager.TargetKind.BASTION_REMNANT)));
      builder.then(ClientCommands.literal("雪地").executes(c -> this.biome(GuidanceManager.TargetKind.BIOME_SNOWY)));
      builder.then(ClientCommands.literal("雪原").executes(c -> this.biome(GuidanceManager.TargetKind.BIOME_SNOWY)));
      builder.then(ClientCommands.literal("山脉").executes(c -> this.biome(GuidanceManager.TargetKind.BIOME_MOUNTAIN)));
      builder.then(ClientCommands.literal("高山").executes(c -> this.biome(GuidanceManager.TargetKind.BIOME_MOUNTAIN)));
      builder.then(ClientCommands.literal("黏土山").executes(c -> this.biome(GuidanceManager.TargetKind.BIOME_CLAY)));
      builder.then(ClientCommands.literal("恶地").executes(c -> this.biome(GuidanceManager.TargetKind.BIOME_CLAY)));
      builder.then(ClientCommands.literal("蘑菇岛").executes(c -> this.biome(GuidanceManager.TargetKind.BIOME_MUSHROOM)));
      builder.then(ClientCommands.literal("沼泽").executes(c -> this.biome(GuidanceManager.TargetKind.BIOME_SWAMP)));
      builder.then(ClientCommands.literal("村庄").executes(c -> this.structure(GuidanceManager.TargetKind.VILLAGE)));
      builder.then(ClientCommands.literal("沙漠").executes(c -> this.biome(GuidanceManager.TargetKind.BIOME_DESERT)));
      builder.then(ClientCommands.literal("丛林").executes(c -> this.biome(GuidanceManager.TargetKind.BIOME_JUNGLE)));
      builder.then(ClientCommands.literal("草原").executes(c -> this.biome(GuidanceManager.TargetKind.BIOME_SAVANNA)));
      builder.then(ClientCommands.literal("稀树草原").executes(c -> this.biome(GuidanceManager.TargetKind.BIOME_SAVANNA)));
      builder.then(ClientCommands.literal("森林").executes(c -> this.biome(GuidanceManager.TargetKind.BIOME_FOREST)));
      builder.then(ClientCommands.literal("平原").executes(c -> this.biome(GuidanceManager.TargetKind.BIOME_PLAINS)));
      builder.then(ClientCommands.literal("海洋").executes(c -> this.biome(GuidanceManager.TargetKind.BIOME_OCEAN)));
      builder.then(ClientCommands.literal("河流").executes(c -> this.biome(GuidanceManager.TargetKind.BIOME_RIVER)));
      builder.then(ClientCommands.literal("针叶林").executes(c -> this.biome(GuidanceManager.TargetKind.BIOME_TAIGA)));
      builder.then(ClientCommands.literal("冰刺").executes(c -> this.biome(GuidanceManager.TargetKind.BIOME_ICE)));
      builder.then(ClientCommands.literal("冰刺之地").executes(c -> this.biome(GuidanceManager.TargetKind.BIOME_ICE)));
   }

   private int structure(GuidanceManager.TargetKind kind) {
      GuidanceManager.INSTANCE.setStructureTarget(kind, 2);
      return 0;
   }

   private int biome(GuidanceManager.TargetKind kind) {
      GuidanceManager.INSTANCE.setBiomeTarget(kind, GuidanceManager.biomesForKind(kind), GuidanceManager.labelFor(kind), 2);
      return 0;
   }

   private int stop() {
      GuidanceManager.INSTANCE.clear();
      sendFeedback("[指引] 已关闭指引。", ChatFormatting.YELLOW);
      return 0;
   }
}
