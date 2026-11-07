package kaptainwutax.seedcrackerX.cracker.bedrock;

import java.util.ArrayList;
import java.util.List;

public final class BedrockMath {
   public static final long MASK48 = 281474976710655L;
   public static final long MULTIPLIER = 25214903917L;
   public static final long INCREMENT = 11L;
   public static final long ROOF_HASH = 343340730L;
   public static final long FLOOR_HASH = 2042456806L;

   private BedrockMath() {
   }

   public static long nextLong(long seed) {
      long s = seed ^ 25214903917L;
      s = s * 25214903917L + 11L & 281474976710655L;
      int hi = (int)(s >>> 16);
      s = s * 25214903917L + 11L & 281474976710655L;
      int lo = (int)(s >>> 16);
      return ((long)hi << 32) + lo;
   }

   public static long positionHash(int x, int y, int z) {
      long l = x * 3129871 ^ z * 116129781L ^ y;
      l = l * l * 42317861L + l * 11L;
      return l >>> 16;
   }

   public static List<Long> getNextLong(long structureSeed) {
      structureSeed &= 281474976710655L;
      long lowerBits = structureSeed & 4294967295L;
      long upperBits = structureSeed >>> 32;
      if ((lowerBits & 2147483648L) != 0L) {
         upperBits++;
      }

      int bitsOfDanger = 1;
      long lowMin = lowerBits << 15;
      long lowMax = (lowerBits + 1L << 15) - 1L;
      long upperMin = (upperBits << 16) - 107048004364969L >> 1;
      long m1lv = floorDiv(lowMax * -33441L + upperMin * 17549L, 1073741824L) + 1L;
      long m2lv = floorDiv(lowMin * 46603L + upperMin * 39761L, 2147483648L) + 1L;
      List<Long> result = new ArrayList<>(4);

      for (int i = 0; i < 2; i++) {
         for (int j = 0; j < 2; j++) {
            long m1 = m1lv + i;
            long m2 = m2lv + j;
            long seed = -39761L * m1 + 35098L * m2;
            long check = 46603L * m1 + 66882L * m2 + 107048004364969L;
            if (check >> 16 == upperBits && seed >>> 16 == lowerBits) {
               result.add(seed * 254681119335897L + 120305458776662L & 281474976710655L);
            }
         }
      }

      return result;
   }

   public static List<Long> reverseNextLong(long seed) {
      List<Long> candidates = getNextLong(seed);
      List<Long> result = new ArrayList<>(candidates.size());

      for (long candidate : candidates) {
         result.add(candidate ^ 25214903917L);
      }

      return result;
   }

   private static long floorDiv(long x, long y) {
      long r = x / y;
      if ((x ^ y) < 0L && r * y != x) {
         r--;
      }

      return r;
   }
}
