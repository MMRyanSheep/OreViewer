package kaptainwutax.seedcrackerX.util;

import kaptainwutax.seedcrackerX.SeedCracker;
import kaptainwutax.seedcrackerX.config.Config;
import kaptainwutax.seedcrackerX.cracker.storage.DataStorage;
import kaptainwutax.seedcrackerX.cracker.storage.TimeMachine;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class ProgressDisplay {
   private static int tickCounter = 0;

   public static void onClientTick() {
      Minecraft mc = Minecraft.getInstance();
      if (mc != null && mc.player != null && mc.gui != null) {
         if (Config.get().active) {
            if (tickCounter++ % 10 == 0) {
               DataStorage storage = SeedCracker.get().getDataStorage();
               TimeMachine tm = storage.getTimeMachine();
               int baseBits = (int)storage.getBaseBits();
               int wantedBits = (int)storage.getWantedBits();
               int dataCount = storage.baseSeedData.size();
               StringBuilder sb = new StringBuilder();
               sb.append("§b[SCX] §f收集: §a").append(baseBits).append("/").append(wantedBits).append("§f位 §7(").append(dataCount).append("个数据)");
               if (tm.isRunning) {
                  if (!tm.worldSeeds.isEmpty()) {
                     sb.append(" §a| 找到世界种子: ").append(tm.worldSeeds.size());
                  } else if (!tm.structureSeeds.isEmpty()) {
                     sb.append(" §e| 结构种子: ").append(tm.structureSeeds.size());
                  } else if (tm.pillarSeeds != null) {
                     sb.append(" §e| 柱种子: ").append(tm.pillarSeeds.size());
                  } else {
                     sb.append(" §e| 搜索中...");
                  }
               }

               Compat.setOverlayMessage(Component.literal(sb.toString()), false);
            }
         }
      }
   }
}
