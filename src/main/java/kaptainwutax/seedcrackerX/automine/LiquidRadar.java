package kaptainwutax.seedcrackerX.automine;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.world.level.Level;

final class LiquidRadar {
   private static volatile Set<Long> POSITIONS = Set.of();
   private static long lastUpdateGameTime = Long.MIN_VALUE;

   private LiquidRadar() {
   }

   static void update(Level level, BlockPos center) {
      long now = level.getGameTime();
      if (now - lastUpdateGameTime >= 60L) {
         lastUpdateGameTime = now;
         HashSet<Long> found = new HashSet<>();
         int cx = center.getX();
         int cy = center.getY();
         int cz = center.getZ();
         MutableBlockPos pos = new MutableBlockPos();

         for (int x = cx - 32; x <= cx + 32; x++) {
            for (int z = cz - 32; z <= cz + 32; z++) {
               for (int y = cy - 20; y <= cy + 12; y++) {
                  pos.set(x, y, z);
                  if (!level.getFluidState(pos).isEmpty()) {
                     found.add(BlockPos.asLong(x, y, z));
                  }
               }
            }
         }

         POSITIONS = Collections.unmodifiableSet(found);
      }
   }

   static boolean nearLiquid(int x, int y, int z) {
      Set<Long> s = POSITIONS;
      return s.isEmpty()
         ? false
         : s.contains(BlockPos.asLong(x, y, z))
            || s.contains(BlockPos.asLong(x, y - 1, z))
            || s.contains(BlockPos.asLong(x, y + 1, z))
            || s.contains(BlockPos.asLong(x + 1, y, z))
            || s.contains(BlockPos.asLong(x - 1, y, z))
            || s.contains(BlockPos.asLong(x, y, z + 1))
            || s.contains(BlockPos.asLong(x, y, z - 1));
   }
}
