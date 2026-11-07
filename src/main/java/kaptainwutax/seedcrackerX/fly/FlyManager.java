package kaptainwutax.seedcrackerX.fly;

import kaptainwutax.seedcrackerX.automine.BaritoneBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.GameType;

public final class FlyManager {
   private static boolean enabled = false;
   private static boolean granted = false;

   private FlyManager() {
   }

   public static boolean isEnabled() {
      return enabled;
   }

   public static void setEnabled(boolean on) {
      enabled = on;
   }

   public static void tick() {
      Minecraft client = Minecraft.getInstance();
      LocalPlayer player = client.player;
      if (player == null) {
         granted = false;
      } else {
         Abilities abilities = player.getAbilities();
         if (enabled) {
            abilities.mayfly = true;
            player.fallDistance = 0.0;
            if (!BaritoneBridge.hasGoalOrPath()) {
               abilities.flying = true;
            }

            granted = true;
         } else if (granted) {
            granted = false;
            GameType mode = client.gameMode != null ? client.gameMode.getPlayerMode() : null;
            boolean nativeFlight = mode == GameType.CREATIVE || mode == GameType.SPECTATOR;
            if (!nativeFlight) {
               abilities.mayfly = false;
               abilities.flying = false;
            }
         }
      }
   }
}
