package kaptainwutax.seedcrackerX.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import kaptainwutax.seedcrackerX.guidance.GuidanceManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public class FkCommand extends ClientCommand {
   @Override
   public String getName() {
      return "fk";
   }

   @Override
   public void build(LiteralArgumentBuilder<FabricClientCommandSource> builder) {
      builder.then(ClientCommands.literal("off").executes(c -> this.stop()));
      builder.then(ClientCommands.argument("block", StringArgumentType.word()).executes(c -> this.find(StringArgumentType.getString(c, "block"))));
   }

   private int find(String blockId) {
      String path = blockId.trim().toLowerCase();
      if (path.isEmpty()) {
         sendFeedback("[方块指引] 请输入方块英文ID，例如 /ov fk diamond_ore", ChatFormatting.RED);
         return 1;
      }

      if (path.startsWith("minecraft:")) {
         path = path.substring("minecraft:".length());
      }

      Block target;
      try {
         target = (Block)BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:" + path));
      } catch (Throwable t) {
         target = null;
      }

      if (target != null && target != Blocks.AIR) {
         GuidanceManager.INSTANCE.setBlockTarget(path);
         return 0;
      } else {
         sendFeedback("未知方块：" + blockId, ChatFormatting.RED);
         sendFeedback("请输入原版方块英文ID，例如 diamond_ore / deepslate_diamond_ore / emerald_ore", ChatFormatting.GRAY);
         return 1;
      }
   }

   private int stop() {
      GuidanceManager.INSTANCE.clear();
      sendFeedback("[方块指引] 已关闭。", ChatFormatting.YELLOW);
      return 0;
   }
}
