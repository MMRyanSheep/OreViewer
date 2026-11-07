package kaptainwutax.seedcrackerX.cracker.bedrock;

public final class CheckObject {
   public static final CheckObject PASS = new CheckObject(0L, 0L, 0L);
   private final long posHash;
   private final long condition;
   private final long offset;

   private CheckObject(long posHash, long condition, long offset) {
      this.posHash = posHash;
      this.condition = condition;
      this.offset = offset;
   }

   public static CheckObject of(long posHash, long lowerBound, long upperBound, long lowerBitMask) {
      long offset = 281474976710655L - upperBound;
      long ph = posHash & 281474976710655L - lowerBitMask;
      long condition = lowerBound + offset - lowerBitMask * 25214903917L;
      return new CheckObject(ph, condition, offset);
   }

   public boolean check(long upperBits) {
      long value = (upperBits ^ this.posHash) * 25214903917L + this.offset & 281474976710655L;
      return Long.compareUnsigned(value, this.condition) < 0;
   }
}
