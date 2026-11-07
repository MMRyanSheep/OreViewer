package kaptainwutax.seedcrackerX.cracker.bedrock;

public final class BedrockBlock {
   public final int x;
   public final int y;
   public final int z;
   public final BlockType type;

   public BedrockBlock(int x, int y, int z, BlockType type) {
      this.x = x;
      this.y = y;
      this.z = z;
      this.type = type;
   }

   public boolean isRoof() {
      return this.y >= 64;
   }
}
