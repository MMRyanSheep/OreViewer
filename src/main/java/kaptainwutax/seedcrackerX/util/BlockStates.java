package kaptainwutax.seedcrackerX.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public class BlockStates {
   public static BlockState of(String id) {
      return ((Block)BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:" + id))).defaultBlockState();
   }
}
