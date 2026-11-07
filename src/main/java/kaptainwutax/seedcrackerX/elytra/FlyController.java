package kaptainwutax.seedcrackerX.elytra;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.phys.Vec3;

/**
 * Key-simulated creative flight for the elytra hunt.
 *
 * <p>Movement deliberately goes through the vanilla input pipeline (no
 * {@code setDeltaMovement}, no Baritone goal), so the server only ever sees
 * legal {@code MovePlayerPacket}s. In 26.3 {@code Entity.moveRelative} uses
 * {@code getYRot()} only - pitch does NOT make creative flight climb - so
 * vertical travel is driven by the jump / sneak keys, while pitch is still set
 * for aiming. Every call also re-asserts the flying ability, which makes this
 * independent of {@code FlyManager} / {@code BaritoneBridge.hasGoalOrPath()}.</p>
 */
public final class FlyController {
   public static final int CRUISE_Y = 140;
   private static final float PITCH_LIMIT = 35.0F;
   private static final double VERTICAL_DEADZONE = 2.0;
   private static final int STUCK_TICKS = 100;
   private static final double STUCK_MOVE_THRESHOLD = 1.0;
   private static final int MAX_LIFT_RETRIES = 3;
   private static final int LIFT_STEP = 20;

   private int cruiseY = CRUISE_Y;
   private int liftRetries;
   private int stuckTicks;
   private double lastX;
   private double lastY;
   private double lastZ;
   private boolean hasLastPos;

   public int cruiseY() {
      return this.cruiseY;
   }

   public int liftRetries() {
      return this.liftRetries;
   }

   public void resetForNewTarget() {
      this.cruiseY = CRUISE_Y;
      this.liftRetries = 0;
      this.resetStuck();
   }

   /** @return false when the maximum number of altitude lifts has been used. */
   public boolean lift() {
      if (this.liftRetries >= MAX_LIFT_RETRIES) {
         return false;
      }
      this.liftRetries++;
      this.cruiseY += LIFT_STEP;
      this.resetStuck();
      return true;
   }

   private void resetStuck() {
      this.stuckTicks = 0;
      this.hasLastPos = false;
   }

   /** Steer towards a world-space point. {@code slow} releases sprint near the target. */
   public void steer(Minecraft mc, LocalPlayer player, Vec3 target, boolean slow) {
      this.ensureFlight(player);
      double dx = target.x - player.getX();
      double dz = target.z - player.getZ();
      double dy = target.y - player.getEyeY();
      double horiz = Math.sqrt(dx * dx + dz * dz);
      float yaw = (float)(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
      double pitch = -Math.toDegrees(Math.atan2(dy, Math.max(horiz, 0.001)));
      player.setYRot(yaw);
      player.setXRot((float)Mth.clamp(pitch, -PITCH_LIMIT, PITCH_LIMIT));

      if (mc.options != null) {
         mc.options.keyAttack.setDown(false);
         mc.options.keyUse.setDown(false);
         mc.options.keyUp.setDown(true);
         mc.options.keyDown.setDown(false);
         mc.options.keyLeft.setDown(false);
         mc.options.keyRight.setDown(false);
         mc.options.keySprint.setDown(!slow);

         double climb = target.y - player.getY();
         mc.options.keyJump.setDown(climb > VERTICAL_DEADZONE);
         mc.options.keyShift.setDown(climb < -VERTICAL_DEADZONE);
      }
   }

   /** Stop horizontal movement but stay airborne. Never disables FlyManager. */
   public void hover(Minecraft mc, LocalPlayer player, boolean levelPitch) {
      this.ensureFlight(player);
      this.releaseMovementKeys(mc);
      if (levelPitch) {
         player.setXRot(0.0F);
      }
   }

   /** Full key release, used on stop / abort / success. */
   public void release(Minecraft mc) {
      this.releaseMovementKeys(mc);
      if (mc != null && mc.options != null) {
         mc.options.keyAttack.setDown(false);
         mc.options.keyUse.setDown(false);
      }
      this.resetStuck();
   }

   private void releaseMovementKeys(Minecraft mc) {
      if (mc == null || mc.options == null) {
         return;
      }
      mc.options.keyUp.setDown(false);
      mc.options.keyDown.setDown(false);
      mc.options.keyLeft.setDown(false);
      mc.options.keyRight.setDown(false);
      mc.options.keyJump.setDown(false);
      mc.options.keyShift.setDown(false);
      mc.options.keySprint.setDown(false);
   }

   /** The flight ability fallback described in the plan (FlyManager may be off). */
   public void ensureFlight(LocalPlayer player) {
      Abilities abilities = player.getAbilities();
      abilities.mayfly = true;
      abilities.flying = true;
      player.fallDistance = 0.0;
   }

   /** Sample the position every {@value #STUCK_TICKS} ticks; true means "stuck". */
   public boolean checkStuck(LocalPlayer player) {
      if (!this.hasLastPos) {
         this.record(player);
         this.stuckTicks = 0;
         return false;
      }
      if (++this.stuckTicks < STUCK_TICKS) {
         return false;
      }
      double dx = player.getX() - this.lastX;
      double dy = player.getY() - this.lastY;
      double dz = player.getZ() - this.lastZ;
      double moved = Math.sqrt(dx * dx + dy * dy + dz * dz);
      this.record(player);
      this.stuckTicks = 0;
      return moved < STUCK_MOVE_THRESHOLD;
   }

   private void record(LocalPlayer player) {
      this.lastX = player.getX();
      this.lastY = player.getY();
      this.lastZ = player.getZ();
      this.hasLastPos = true;
   }

   /** Copy of {@code AutoMineManager.faceEntity} (private in the original class). */
   public static void aimAt(LocalPlayer player, Entity target) {
      double dx = target.getX() - player.getX();
      double dz = target.getZ() - player.getZ();
      double dy = target.getY() + target.getBbHeight() * 0.5 - player.getEyeY();
      double horiz = Math.sqrt(dx * dx + dz * dz);
      float yaw = (float)(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
      float pitch = (float)(-Math.toDegrees(Math.atan2(dy, horiz)));
      player.setYRot(yaw);
      player.setXRot(Mth.clamp(pitch, -90.0F, 90.0F));
   }
}
