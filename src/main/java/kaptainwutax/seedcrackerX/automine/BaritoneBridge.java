package kaptainwutax.seedcrackerX.automine;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.goals.GoalYLevel;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class BaritoneBridge {
   private static volatile Field oreFieldCache;

   private BaritoneBridge() {
   }

   private static void configure() {
      Object s = BaritoneAPI.getSettings();
      setSetting(s, "allowBreak", true);
      setSetting(s, "allowPlace", true);
      setSetting(s, "mineScanDroppedItems", true);
      setSetting(s, "mineDropLoiterDurationMSThanksLouca", 1000L);
      setSetting(s, "minYLevelWhileMining", -64);
      setSetting(s, "mineMaxOreLocationsCount", 65536);
      setSetting(s, "allowOnlyExposedOres", false);
      setSetting(s, "allowSprint", true);
      setSetting(s, "assumeWalkOnLava", false);
      setSetting(s, "assumeWalkOnWater", false);
      setSetting(s, "allowWaterBucketFall", false);
      setSetting(s, "strictLiquidCheck", true);
      setSetting(s, "walkOnWaterOnePenalty", 10000.0);
      setSetting(s, "blocksToAvoid", Arrays.asList(Blocks.LAVA, Blocks.WATER));
      setSetting(s, "blockBreakAdditionalPenalty", 6.0);
      setSetting(s, "mineGoalUpdateInterval", 0);
      setSetting(s, "exploreForBlocks", false);
      setSetting(s, "legitMine", false);
   }

   private static void setSetting(Object settings, String name, Object value) {
      try {
         Object setting = settings.getClass().getField(name).get(settings);
         setting.getClass().getField("value").set(setting, value);
      } catch (Throwable var4) {
      }
   }

   static void start(Block... blocks) {
      IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
      configure();
      baritone.getPathingBehavior().cancelEverything();
      baritone.getMineProcess().mineByName(blockNames(blocks));
   }

   static void gotoNear(final BlockPos pos) {
      IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
      configure();
      baritone.getPathingBehavior().cancelEverything();
      Goal goal = new Goal() {
         @Override
         public boolean isInGoal(int x, int y, int z) {
            return Math.abs(x - pos.getX()) <= 1 && Math.abs(y - pos.getY()) <= 1 && Math.abs(z - pos.getZ()) <= 1;
         }

         @Override
         public double heuristic(int x, int y, int z) {
            return Math.max(Math.max(Math.abs(x - pos.getX()), Math.abs(y - pos.getY())), Math.abs(z - pos.getZ())) - 1;
         }
      };
      baritone.getCustomGoalProcess().setGoalAndPath(goal);
   }

   static void gotoNear(BlockPos pos, boolean overworld) {
      if (!overworld) {
         gotoNear(pos);
      } else {
         IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
         configure();
         baritone.getPathingBehavior().cancelEverything();
         final int tx = pos.getX();
         final int ty = pos.getY();
         final int tz = pos.getZ();
         Goal goal = new Goal() {
            @Override
            public boolean isInGoal(int x, int y, int z) {
               return Math.abs(x - tx) <= 1 && Math.abs(z - tz) <= 1 && Math.abs(y - ty) <= 1;
            }

            @Override
            public double heuristic(int x, int y, int z) {
               double h = Math.max(Math.max(Math.abs(x - tx), Math.abs(y - ty)), Math.abs(z - tz)) - 1;
               if (LiquidRadar.nearLiquid(x, y, z)) {
                  h += 300.0;
               }

               return h;
            }
         };
         baritone.getCustomGoalProcess().setGoalAndPath(goal);
      }
   }

   static void gotoNearXZ(BlockPos pos, boolean overworld) {
      IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
      configure();
      baritone.getPathingBehavior().cancelEverything();
      if (!overworld) {
         baritone.getCustomGoalProcess().setGoalAndPath(new GoalXZ(pos.getX(), pos.getZ()));
      } else {
         final int tx = pos.getX();
         final int tz = pos.getZ();
         Goal goal = new Goal() {
            @Override
            public boolean isInGoal(int x, int y, int z) {
               return Math.abs(x - tx) <= 1 && Math.abs(z - tz) <= 1;
            }

            @Override
            public double heuristic(int x, int y, int z) {
               double h = Math.max(Math.abs(x - tx), Math.abs(z - tz)) - 0.5;
               if (LiquidRadar.nearLiquid(x, y, z)) {
                  h += 300.0;
               }

               return h;
            }
         };
         baritone.getCustomGoalProcess().setGoalAndPath(goal);
      }
   }

   static void climbTo(int y) {
      IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
      configure();
      baritone.getPathingBehavior().cancelEverything();
      baritone.getCustomGoalProcess().setGoalAndPath(new GoalYLevel(y));
   }

   static void startAt(List<BlockPos> positions, Block... blocks) {
      IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
      configure();
      baritone.getMineProcess().mineByName(blockNames(blocks));
   }

   private static String[] blockNames(Block... blocks) {
      return Arrays.stream(blocks).map(b -> BuiltInRegistries.BLOCK.getKey(b).toString()).toArray(String[]::new);
   }

   static void cancel() {
      IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
      baritone.getMineProcess().cancel();
      clearCustomGoal();
      baritone.getPathingBehavior().cancelEverything();
   }

   static boolean isTraveling() {
      return BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().isActive();
   }

   static boolean isMining() {
      return BaritoneAPI.getProvider().getPrimaryBaritone().getMineProcess().isActive();
   }

   static boolean isPathing() {
      return BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().isPathing();
   }

   public static boolean hasGoalOrPath() {
      try {
         IBaritone b = BaritoneAPI.getProvider().getPrimaryBaritone();
         if (b.getPathingBehavior().isPathing()) {
            return true;
         }

         if (b.getMineProcess().isActive()) {
            return true;
         }

         Object goal = b.getCustomGoalProcess().getClass().getMethod("getGoal").invoke(b.getCustomGoalProcess());
         return goal instanceof Optional && ((Optional)goal).isPresent();
      } catch (Throwable t) {
         return true;
      }
   }

   static boolean hasCustomGoal() {
      try {
         Object gp = BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess();
         Object goal = gp.getClass().getMethod("getGoal").invoke(gp);
         return goal instanceof Optional && ((Optional)goal).isPresent();
      } catch (Throwable t) {
         return true;
      }
   }

   static void clearCustomGoal() {
      try {
         Object gp = BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess();
         // Must pass exactly one (null) argument: a bare `null` would be treated
         // as an empty varargs array by Method.invoke (see original bytecode).
         gp.getClass().getMethod("setGoal", Goal.class).invoke(gp, (Object)null);
      } catch (Throwable var1) {
      }
   }

   static void pruneUnsafe(Level level, int minY, int centerChunkX, int centerChunkZ, int chunkRadius) {
      try {
         Object mine = BaritoneAPI.getProvider().getPrimaryBaritone().getMineProcess();
         Field f = oreFieldCache;
         if (f == null) {
            f = findOreField(mine);
            if (f == null) {
               return;
            }

            oreFieldCache = f;
         }

         Object listObj = f.get(mine);
         if (!(listObj instanceof List)) {
            return;
         }

         @SuppressWarnings("unchecked")
         List<BlockPos> orePositions = (List<BlockPos>)listObj;
         orePositions.removeIf(pos -> {
            if (pos.getY() < minY) {
               return true;
            }

            if (Math.abs((pos.getX() >> 4) - centerChunkX) <= chunkRadius && Math.abs((pos.getZ() >> 4) - centerChunkZ) <= chunkRadius) {
               for (Direction d : Direction.values()) {
                  BlockState st = level.getBlockState(pos.relative(d));
                  if (st.is(Blocks.LAVA) || st.is(Blocks.WATER)) {
                     return true;
                  }
               }

               return false;
            } else {
               return true;
            }
         });
      } catch (Throwable var8) {
      }
   }

   static int getKnownOreCount() {
      try {
         Object mine = BaritoneAPI.getProvider().getPrimaryBaritone().getMineProcess();
         Field f = oreFieldCache;
         if (f == null) {
            f = findOreField(mine);
            if (f == null) {
               return -1;
            }

            oreFieldCache = f;
         }

         Object o = f.get(mine);
         return o instanceof List ? ((List)o).size() : -1;
      } catch (Throwable t) {
         return -1;
      }
   }

   static boolean injectOres(Collection<BlockPos> positions) {
      try {
         Object mine = BaritoneAPI.getProvider().getPrimaryBaritone().getMineProcess();
         Field f = oreFieldCache;
         if (f == null) {
            f = findOreField(mine);
            if (f == null) {
               return false;
            }

            oreFieldCache = f;
         }

         List<BlockPos> list = (List<BlockPos>)f.get(mine);
         list.clear();
         list.addAll(positions);
         return true;
      } catch (Throwable t) {
         return false;
      }
   }

   private static Field findOreField(Object mine) {
      try {
         for (Field f : mine.getClass().getDeclaredFields()) {
            if (List.class.isAssignableFrom(f.getType())) {
               f.setAccessible(true);
               Type g = f.getGenericType();
               if (g instanceof ParameterizedType) {
                  Type arg = ((ParameterizedType)g).getActualTypeArguments()[0];
                  if (arg instanceof Class && BlockPos.class.isAssignableFrom((Class<?>)arg)) {
                     return f;
                  }
               }
            }
         }
      } catch (Throwable var7) {
      }

      return null;
   }
}
