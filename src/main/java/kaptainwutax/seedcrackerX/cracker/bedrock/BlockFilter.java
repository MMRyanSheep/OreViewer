package kaptainwutax.seedcrackerX.cracker.bedrock;

import java.util.ArrayList;
import java.util.List;

public final class BlockFilter {
   private final long posHash;
   private final long lowerBound;
   private final long upperBound;
   private long possibleRange = 281474976710655L;

   public BlockFilter(BedrockBlock block, BedrockMode mode) {
      int y = block.y;
      long[] bounds = bounds(y, block.type);
      this.lowerBound = bounds[0];
      this.upperBound = bounds[1];
      if (mode == BedrockMode.PAPER_1_18) {
         y = y > 5 ? 122 : 0;
      }

      this.posHash = BedrockMath.positionHash(block.x, y, block.z) ^ 25214903917L;
   }

   public CheckObject createCheck(int lowerBits) {
      long lowerBitsMask = (1L << lowerBits) - 1L;
      this.checkWithBits(lowerBits);
      return CheckObject.of(this.posHash, this.lowerBound, this.upperBound, lowerBitsMask);
   }

   public double discardedSeeds(int lowerBits) {
      long lowerBitsMask = (1L << lowerBits) - 1L;
      long bound = this.bound() + lowerBitsMask * 25214903917L;
      double successChance = (double)bound / this.possibleRange;
      double failChance = 1.0 - successChance;
      return failChance <= 0.0 ? 0.0 : failChance * (1L << lowerBits);
   }

   private void checkWithBits(int lowerBits) {
      long lowerBitsMask = (1L << lowerBits) - 1L;
      long jiggleRoom = lowerBitsMask * 25214903917L;
      long newRange = jiggleRoom + this.bound();
      if (Long.compareUnsigned(newRange, this.possibleRange) >= 0) {
         throw new IllegalStateException("bedrock filter layer gained information instead of losing it");
      }

      this.possibleRange = newRange;
   }

   private long bound() {
      if (this.upperBound <= this.lowerBound) {
         throw new IllegalStateException("bedrock filter bounds must be strictly ordered");
      } else {
         return this.upperBound - this.lowerBound;
      }
   }

   public double acceptanceWidth() {
      return (this.upperBound - this.lowerBound) / 2.81474976710655E14;
   }

   public static long getFilterPower(Iterable<BlockFilter> filters) {
      double resultingSeeds = 1.0;

      for (BlockFilter filter : filters) {
         resultingSeeds *= 1.0 - filter.discardedSeeds(0);
      }

      resultingSeeds *= 2.8147498E14F;
      return (long)resultingSeeds;
   }

   public static long[] estimatePerSurface(List<BedrockBlock> sampleBlocks) {
      List<BlockFilter> floor = new ArrayList<>();
      List<BlockFilter> roof = new ArrayList<>();

      for (BedrockBlock block : sampleBlocks) {
         if (block.y < 64) {
            floor.add(new BlockFilter(block, BedrockMode.NORMAL));
         } else {
            roof.add(new BlockFilter(block, BedrockMode.NORMAL));
         }
      }

      return new long[]{getFilterPower(floor), getFilterPower(roof)};
   }

   private static long[] bounds(int layer, BlockType type) {
      double lowerBound = 0.0;
      double upperBound = 1.0;
      if (layer < 0) {
         layer += 64;
      }

      if (layer > 5) {
         layer -= 122;
         double bound = (5 - layer) / 5.0;
         if (type == BlockType.BEDROCK) {
            lowerBound = bound;
         } else {
            upperBound = bound;
         }
      } else {
         double bound = (5 - layer) / 5.0;
         if (type == BlockType.BEDROCK) {
            upperBound = bound;
         } else {
            lowerBound = bound;
         }
      }

      lowerBound *= 2.81474976710655E14;
      upperBound *= 2.81474976710655E14;
      return new long[]{(long)lowerBound, (long)upperBound};
   }
}
