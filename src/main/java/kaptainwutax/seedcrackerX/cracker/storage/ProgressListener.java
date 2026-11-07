package kaptainwutax.seedcrackerX.cracker.storage;

import kaptainwutax.seedcrackerX.util.Compat;
import kaptainwutax.seedcrackerX.util.Log;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class ProgressListener {
   protected float progress;
   protected int count = 0;

   public ProgressListener() {
      this(0.0F);
   }

   public ProgressListener(float progress) {
      this.progress = progress;
   }

   public synchronized void addPercent(float percent, boolean debug) {
      this.count++;
      this.progress += percent;
      if ((this.count & 7) == 0) {
         this.showOnActionBar();
      }
   }

   private void showOnActionBar() {
      Minecraft mc = Minecraft.getInstance();
      if (mc != null && mc.gui != null) {
         String text = Log.translate("tmachine.progress") + ": " + String.format("%.1f", this.progress) + "%";
         Compat.setOverlayMessage(Component.literal(text).withStyle(ChatFormatting.AQUA), false);
      }
   }

   public synchronized float getProgress() {
      return this.progress;
   }
}
