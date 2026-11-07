package kaptainwutax.seedcrackerX.elytra;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Key-simulated creative flight for the elytra hunt, including obstacle handling.
 *
 * <p>Movement deliberately goes through the vanilla input pipeline (no
 * {@code setDeltaMovement}, no Baritone goal), so the server only ever sees
 * legal {@code MovePlayerPacket}s. In 26.3 {@code Entity.moveRelative} uses
 * {@code getYRot()} only - pitch does NOT make creative flight climb - so
 * vertical travel is driven by the jump / sneak keys, while pitch is still set
 * for aiming. Every call also re-asserts the flying ability, which makes this
 * independent of {@code FlyManager} / {@code BaritoneBridge.hasGoalOrPath()}.</p>
 *
 * <p>{@link #navigate} adds the missing "blocked path" behaviour: it ray-traces
 * towards the requested point, first tries to fly around the obstruction via a
 * side / over-the-top waypoint, and when there is no way around (e.g. the hull
 * of an end ship) it breaks the front-most blocking block with the vanilla
 * {@code MultiPlayerGameMode} start/continue destroy pipeline.</p>
 */
public final class FlyController {
   public static final int CRUISE_Y = 140;

   /** What {@link #navigate} decided to do on the last tick. */
   public enum NavState {
      CLEAR,          // straight line is free, flying to the target
      DETOUR,         // flying to a waypoint that goes around an obstacle
      APPROACH_MINE,  // flying towards the blocking block to get within reach
      MINING,         // breaking the blocking block
      ENTITY,         // a hostile entity blocks the path and no detour was found
      BLOCKED,        // no detour and mining is not allowed / not possible
      DONE,           // internal: the mined block is gone
      FAILED          // internal: mining timed out / could not start
   }

   private static final float PITCH_LIMIT = 35.0F;
   private static final double VERTICAL_DEADZONE = 2.0;
   private static final int STUCK_TICKS = 100;
   private static final double STUCK_MOVE_THRESHOLD = 1.0;
   private static final int MAX_LIFT_RETRIES = 3;
   private static final int LIFT_STEP = 20;

   // Obstacle navigation. The probe is capped so a 1000-block flight never
   // turns into a 1000-block ray trace on the render thread.
   private static final double PROBE_DISTANCE = 48.0;
   private static final double DETOUR_ARRIVE = 3.0;
   private static final long DETOUR_TIMEOUT_MS = 8000L;
   private static final int MAX_DETOUR_FAILURES = 2;
   private static final double MINE_REACH = 3.2;
   private static final int MAX_OFF_AIM_TICKS = 40;
   private static final long MINE_TIMEOUT_MS = 12000L;
   private static final double MINING_PREFER_DISTANCE = 8.0;
   private static final double THREAT_PROBE_DISTANCE = 32.0;
   private static final double THREAT_PADDING = 0.35;
   private static final double[] SIDE_OFFSETS = {6.0, 12.0, 20.0};
   private static final double[] SIDE_LIFTS = {0.0, 4.0, 8.0};

   private int cruiseY = CRUISE_Y;
   private int liftRetries;
   private int stuckTicks;
   private double lastX;
   private double lastY;
   private double lastZ;
   private boolean hasLastPos;

   private Vec3 detourTarget;
   private long detourStartMs;
   private int detourFailures;
   private BlockPos miningPos;
   private Direction miningDir;
   private boolean miningStarted;
   private long miningStartMs;
   private int offAimTicks;
   private long ignoreThreatUntilMs;
   private NavState navState = NavState.CLEAR;

   public int cruiseY() {
      return this.cruiseY;
   }

   public int liftRetries() {
      return this.liftRetries;
   }

   public NavState navState() {
      return this.navState;
   }

   public BlockPos miningBlock() {
      return this.miningPos;
   }

   public void resetForNewTarget() {
      this.stopMining(Minecraft.getInstance());
      this.cruiseY = CRUISE_Y;
      this.liftRetries = 0;
      this.detourTarget = null;
      this.detourFailures = 0;
      this.ignoreThreatUntilMs = 0L;
      this.setNav(NavState.CLEAR);
      this.resetStuck();
   }

   /** Temporarily stop treating guards as obstacles (combat timed out; go for the frame directly). */
   public void ignoreThreatsFor(long millis) {
      this.ignoreThreatUntilMs = System.currentTimeMillis() + millis;
   }

   public boolean isIgnoringThreats() {
      return System.currentTimeMillis() < this.ignoreThreatUntilMs;
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

   // ------------------------------------------------------------------ navigation

   /**
    * Fly towards {@code target}, dealing with solid blocks in the way.
    *
    * <p>Strategy: ray trace -> sidestep / climb over the obstruction -> if no
    * route exists (or the target is enclosed), dig through the blocking layer.
    * {@code allowMine} is false for long-distance cruising, where raising the
    * cruise altitude is preferable to tunnelling through every tower.</p>
    */
   public NavState navigate(Minecraft mc, ClientLevel level, LocalPlayer player, Vec3 target, boolean slow, boolean allowMine) {
      if (level == null || mc == null || mc.gameMode == null) {
         this.steer(mc, player, target, slow);
         return this.setNav(NavState.CLEAR);
      }
      long now = System.currentTimeMillis();

      // Keep following an active waypoint instead of recomputing one every tick,
      // which would make the player oscillate in front of the obstacle.
      if (this.detourTarget != null) {
         double remaining = player.position().distanceTo(this.detourTarget);
         boolean waypointBlocked = this.firstBlocker(level, player, this.detourTarget) != null;
         if (remaining > DETOUR_ARRIVE
            && now - this.detourStartMs < DETOUR_TIMEOUT_MS
            && !waypointBlocked) {
            this.steer(mc, player, this.detourTarget, true);
            return this.setNav(NavState.DETOUR);
         }
         this.detourFailures = remaining > DETOUR_ARRIVE ? this.detourFailures + 1 : 0;
         this.detourTarget = null;
      }

      // A shulker is a solid obstacle and also hijacks the crosshair, which
      // makes vanilla continueAttack() call stopDestroyBlock() instead of
      // accumulating break progress. Try a detour first (isClear() rejects
      // paths through hostile entities); if the hull leaves no gap, the manager
      // clears the guard.
      boolean nearGoal = player.position().distanceToSqr(target) < 48.0 * 48.0;
      Entity threat = nearGoal && now > this.ignoreThreatUntilMs
         ? findThreat(level, player, target)
         : null;
      if (threat != null) {
         Vec3 waypoint = this.findDetour(level, player, target, BlockPos.containing(threat.position()));
         if (waypoint != null) {
            this.detourTarget = waypoint;
            this.detourStartMs = now;
            this.steer(mc, player, waypoint, true);
            return this.setNav(NavState.DETOUR);
         }
         this.stopMining(mc);
         return this.setNav(NavState.ENTITY);
      }

      BlockHitResult hit = this.firstBlocker(level, player, target);

      // A mining session is sticky: keep breaking the same block until it is
      // gone, otherwise a tiny movement would switch targets every tick.
      if (this.miningPos != null) {
         if (hit == null) {
            this.stopMining(mc);
            this.steer(mc, player, target, slow);
            return this.setNav(NavState.CLEAR);
         }
         NavState mining = this.tickMining(mc, level, player, now);
         if (mining == NavState.DONE) {
            this.steer(mc, player, target, slow);
            return this.setNav(NavState.CLEAR);
         }
         if (mining == NavState.FAILED) {
            this.detourFailures = MAX_DETOUR_FAILURES;
            return this.blocked(mc, player);
         }
         return this.setNav(mining);
      }

      if (hit == null) {
         this.detourFailures = 0;
         this.steer(mc, player, target, slow);
         return this.setNav(NavState.CLEAR);
      }

      BlockPos blocker = hit.getBlockPos();
      boolean mineable;
      try {
         BlockState state = level.getBlockState(blocker);
         mineable = !state.isAir() && state.getDestroyProgress(player, level, blocker) > 0.0F;
      } catch (Throwable ignored) {
         // Unbreakable / unknown block state: only a detour can help.
         mineable = false;
      }
      boolean nearTarget = player.position().distanceTo(target) < MINING_PREFER_DISTANCE;
      boolean preferMine = allowMine && mineable && (nearTarget || this.detourFailures >= MAX_DETOUR_FAILURES);

      if (!preferMine) {
         Vec3 waypoint = this.findDetour(level, player, target, blocker);
         if (waypoint != null) {
            this.detourTarget = waypoint;
            this.detourStartMs = now;
            this.steer(mc, player, waypoint, true);
            return this.setNav(NavState.DETOUR);
         }
      }
      if (!allowMine || !mineable) {
         return this.blocked(mc, player);
      }

      this.miningPos = blocker;
      this.miningDir = hit.getDirection();
      this.miningStartMs = now;
      this.miningStarted = false;
      NavState mining = this.tickMining(mc, level, player, now);
      if (mining == NavState.DONE) {
         this.steer(mc, player, target, slow);
         return this.setNav(NavState.CLEAR);
      }
      if (mining == NavState.FAILED) {
         this.detourFailures = MAX_DETOUR_FAILURES;
         return this.blocked(mc, player);
      }
      return this.setNav(mining);
   }

   /** Stop in place and let the caller raise the cruise altitude / change target. */
   private NavState blocked(Minecraft mc, LocalPlayer player) {
      this.hover(mc, player, true);
      return this.setNav(NavState.BLOCKED);
   }

   private NavState setNav(NavState state) {
      this.navState = state;
      return state;
   }

   /**
    * Nearest hostile living entity whose hitbox intersects the ray. In an end
    * ship these are the shulkers guarding the elytra: they physically block
    * flight and are pickable, so they also steal the crosshair from the block
    * that is being mined.
    */
   public static Entity findThreat(ClientLevel level, LocalPlayer player, Vec3 point) {
      if (level == null || player == null || point == null) {
         return null;
      }
      return findThreat(level, player, player.getEyePosition(), point);
   }

   /** Same probe between two arbitrary points, used while choosing a detour. */
   public static Entity findThreat(ClientLevel level, LocalPlayer player, Vec3 from, Vec3 to) {
      if (level == null || player == null || from == null || to == null) {
         return null;
      }
      Vec3 delta = to.subtract(from);
      double length = delta.length();
      if (length < 0.5) {
         return null;
      }
      Vec3 end = length > THREAT_PROBE_DISTANCE
         ? from.add(delta.scale(THREAT_PROBE_DISTANCE / length))
         : to;
      Entity best = null;
      double bestDistance = Double.MAX_VALUE;
      for (Entity entity : level.entitiesForRendering()) {
         if (entity == player || entity.isRemoved() || !entity.isAlive()) {
            continue;
         }
         // Deliberately only shulkers. Matching the Enemy interface would also
         // catch every enderman on the outer islands and derail the whole hunt.
         if (!(entity instanceof Shulker shulker)) {
            continue;
         }
         var hit = shulker.getBoundingBox().inflate(THREAT_PADDING).clip(from, end);
         if (hit.isEmpty()) {
            continue;
         }
         double distance = hit.get().distanceToSqr(from);
         if (distance < bestDistance) {
            best = shulker;
            bestDistance = distance;
         }
      }
      return best;
   }

   /** Called by the manager once the blocking entity has been cleared. */
   public void clearEntityState() {
      this.detourTarget = null;
      this.setNav(NavState.CLEAR);
   }

   /** Compact diagnostics for /os qc 调试. */
   public String miningDiagnostics() {
      return "挖掘方块=" + (this.miningPos == null ? "无" : this.miningPos)
         + " 准星偏离=" + this.offAimTicks + " tick";
   }

   /** First solid block between the player's eyes and {@code target}, or null. */
   private BlockHitResult firstBlocker(ClientLevel level, LocalPlayer player, Vec3 target) {
      Vec3 eye = player.getEyePosition();
      Vec3 delta = target.subtract(eye);
      double length = delta.length();
      if (length < 0.5) {
         return null;
      }
      Vec3 end = length > PROBE_DISTANCE ? eye.add(delta.scale(PROBE_DISTANCE / length)) : target;
      try {
         BlockHitResult hit = level.clip(
            new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)
         );
         return hit.getType() == HitResult.Type.BLOCK ? hit : null;
      } catch (Throwable ignored) {
         // Unloaded chunk / clip failure: treat as clear rather than vetoing flight.
         return null;
      }
   }

   /** Try side-step and over-the-top waypoints, and keep the first one with a clear two-leg path. */
   private Vec3 findDetour(ClientLevel level, LocalPlayer player, Vec3 target, BlockPos blocker) {
      Vec3 eye = player.getEyePosition();
      double dx = target.x - player.getX();
      double dz = target.z - player.getZ();
      double horizontal = Math.sqrt(dx * dx + dz * dz);
      if (horizontal < 1.0) {
         return null;
      }
      double ux = dx / horizontal;
      double uz = dz / horizontal;
      double px = -uz;
      double pz = ux;
      double forward = Math.min(horizontal, 6.0);
      double baseY = Math.max(player.getY(), blocker.getY() + 3.0);

      for (int side = -1; side <= 1; side += 2) {
         for (double offset : SIDE_OFFSETS) {
            for (double lift : SIDE_LIFTS) {
               Vec3 waypoint = new Vec3(
                  player.getX() + ux * forward + px * side * offset,
                  baseY + lift,
                  player.getZ() + uz * forward + pz * side * offset
               );
               if (this.isInsideSolid(level, waypoint)) {
                  continue;
               }
               if (!this.isClear(level, player, eye, waypoint)) {
                  continue;
               }
               if (!this.isClear(level, player, waypoint, target)) {
                  continue;
               }
               return waypoint;
            }
         }
      }
      Vec3 over = new Vec3(player.getX() + ux * forward, baseY + 12.0, player.getZ() + uz * forward);
      if (!this.isInsideSolid(level, over)
         && this.isClear(level, player, eye, over)
         && this.isClear(level, player, over, target)) {
         return over;
      }
      return null;
   }

   private boolean isInsideSolid(ClientLevel level, Vec3 point) {
      BlockPos pos = BlockPos.containing(point);
      BlockState state = level.getBlockState(pos);
      return !state.getCollisionShape(level, pos).isEmpty();
   }

   private boolean isClear(ClientLevel level, LocalPlayer player, Vec3 from, Vec3 to) {
      Vec3 delta = to.subtract(from);
      double length = delta.length();
      if (length < 0.001) {
         return true;
      }
      if (findThreat(level, player, from, to) != null) {
         return false;
      }
      Vec3 end = length > PROBE_DISTANCE ? from.add(delta.scale(PROBE_DISTANCE / length)) : to;
      try {
         BlockHitResult hit = level.clip(
            new ClipContext(from, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)
         );
         return hit.getType() != HitResult.Type.BLOCK;
      } catch (Throwable ignored) {
         return true;
      }
   }

   /**
    * Break {@link #miningPos} with the vanilla destroy pipeline. Holding
    * {@code keyAttack} down is essential: {@code Minecraft.continueAttack(false)}
    * calls {@code stopDestroyBlock()} every tick otherwise, which resets the
    * server-side break progress before it can ever finish.
    */
   private NavState tickMining(Minecraft mc, ClientLevel level, LocalPlayer player, long now) {
      BlockPos pos = this.miningPos;
      if (pos == null || mc.gameMode == null) {
         return NavState.FAILED;
      }
      if (level.getBlockState(pos).isAir()) {
         this.stopMining(mc);
         return NavState.DONE;
      }
      if (now - this.miningStartMs > MINE_TIMEOUT_MS) {
         this.stopMining(mc);
         return NavState.FAILED;
      }
      Vec3 center = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
      if (player.getEyePosition().distanceTo(center) > MINE_REACH) {
         // steerRaw (not steer) so the pending mining session is not cancelled.
         this.offAimTicks = 0;
         this.steerRaw(mc, player, center, true);
         return NavState.APPROACH_MINE;
      }

      this.ensureFlight(player);
      this.releaseMovementKeys(mc);
      this.aimAtBlock(player, center);
      if (mc.options != null) {
         mc.options.keyAttack.setDown(true);
         mc.options.keyUse.setDown(false);
      }
      Direction direction = this.miningDir == null ? Direction.UP : this.miningDir;

      // 26.3 Minecraft.continueAttack(true) keeps a break going ONLY when
      // hitResult is a BLOCK hit on a non-air block. An EntityHitResult (item
      // frame, projectile, ...) or MISS makes it call stopDestroyBlock(), which
      // sends ABORT_DESTROY_BLOCK and resets the server-side progress every
      // single tick - that is the "progress keeps resetting" symptom. Do not
      // push progress while the crosshair is stolen; re-aim, hold the session,
      // and fail fast so the manager can detour or clear the obstruction.
      HitResult crosshair = mc.hitResult;
      boolean onTarget = crosshair instanceof BlockHitResult blockHit
         && blockHit.getBlockPos().equals(pos);
      if (!onTarget) {
         this.offAimTicks++;
         // Best effort: hand vanilla a valid BLOCK hit result. A render pick may
         // overwrite it next frame, but when it does not the break keeps its
         // progress. Keep an EntityHitResult intact so the manager can detect
         // that the crosshair is on the elytra frame and switch to taking it.
         if (crosshair == null || crosshair.getType() != HitResult.Type.ENTITY) {
            mc.hitResult = new BlockHitResult(center, direction, pos, false);
         }
         if (this.offAimTicks > MAX_OFF_AIM_TICKS) {
            this.stopMining(mc);
            return NavState.FAILED;
         }
         return NavState.MINING;
      }
      this.offAimTicks = 0;
      try {
         if (!mc.gameMode.isDestroying()) {
            mc.gameMode.startDestroyBlock(pos, direction);
         }
         mc.gameMode.continueDestroyBlock(pos, direction);
         this.miningStarted = true;
      } catch (Throwable ignored) {
         this.stopMining(mc);
         return NavState.FAILED;
      }
      return NavState.MINING;
   }

   /** Idempotent cleanup: abort any pending block break and release the attack key. */
   private void stopMining(Minecraft mc) {
      if (mc != null && mc.options != null) {
         mc.options.keyAttack.setDown(false);
      }
      if (this.miningStarted && mc != null && mc.gameMode != null) {
         try {
            if (mc.gameMode.isDestroying()) {
               mc.gameMode.stopDestroyBlock();
            }
         } catch (Throwable ignored) {
         }
      }
      this.miningPos = null;
      this.miningDir = null;
      this.miningStarted = false;
      this.miningStartMs = 0L;
      this.offAimTicks = 0;
   }

   private static void aimAtBlock(LocalPlayer player, Vec3 center) {
      double dx = center.x - player.getX();
      double dz = center.z - player.getZ();
      double dy = center.y - player.getEyeY();
      double horizontal = Math.sqrt(dx * dx + dz * dz);
      player.setYRot((float)(Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
      player.setXRot((float)Mth.clamp(-Math.toDegrees(Math.atan2(dy, Math.max(horizontal, 0.001))), -90.0F, 90.0F));
   }

   // ------------------------------------------------------------------ flight primitives

   /** Steer towards a world-space point. {@code slow} releases sprint near the target. */
   public void steer(Minecraft mc, LocalPlayer player, Vec3 target, boolean slow) {
      this.stopMining(mc);
      this.steerRaw(mc, player, target, slow);
   }

   private void steerRaw(Minecraft mc, LocalPlayer player, Vec3 target, boolean slow) {
      this.ensureFlight(player);
      double dx = target.x - player.getX();
      double dz = target.z - player.getZ();
      double dy = target.y - player.getEyeY();
      double horiz = Math.sqrt(dx * dx + dz * dz);
      float yaw = (float)(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
      double pitch = -Math.toDegrees(Math.atan2(dy, Math.max(horiz, 0.001)));
      player.setYRot(yaw);
      player.setXRot((float)Mth.clamp(pitch, -PITCH_LIMIT, PITCH_LIMIT));

      if (mc != null && mc.options != null) {
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
      this.stopMining(mc);
      this.ensureFlight(player);
      this.releaseMovementKeys(mc);
      if (levelPitch) {
         player.setXRot(0.0F);
      }
   }

   /** Full key release, used on stop / abort / success. */
   public void release(Minecraft mc) {
      this.stopMining(mc);
      this.releaseMovementKeys(mc);
      if (mc != null && mc.options != null) {
         mc.options.keyAttack.setDown(false);
         mc.options.keyUse.setDown(false);
      }
      this.detourTarget = null;
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
