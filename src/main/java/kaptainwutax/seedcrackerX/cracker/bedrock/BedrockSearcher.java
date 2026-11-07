package kaptainwutax.seedcrackerX.cracker.bedrock;

import java.util.function.LongConsumer;

public interface BedrockSearcher {
   void search(BedrockSearcher.Emitter var1, LongConsumer var2) throws InterruptedException;

   void cancel();

   interface Emitter {
      boolean onSeed(long var1);
   }

   enum Output {
      WORLD,
      STRUCTURE;
   }
}
