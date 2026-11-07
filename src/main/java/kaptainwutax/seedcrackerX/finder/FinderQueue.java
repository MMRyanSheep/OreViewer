package kaptainwutax.seedcrackerX.finder;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import kaptainwutax.seedcrackerX.automine.AutoMineManager;
import kaptainwutax.seedcrackerX.config.Config;
import kaptainwutax.seedcrackerX.render.Cuboid;
import kaptainwutax.seedcrackerX.util.Compat;
import kaptainwutax.seedcrackerX.xray.OreXrayManager;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.CollectSubmits;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FinderQueue {
   private static final FinderQueue INSTANCE = new FinderQueue();
   private static final Logger log = LoggerFactory.getLogger(FinderQueue.class);
   public static ExecutorService SERVICE = Executors.newFixedThreadPool(5);
   private static final Map<LevelRenderState, Set<Cuboid>> CUBOIDS_BY_STATE = Collections.synchronizedMap(new WeakHashMap<>());
   public FinderControl finderControl = new FinderControl();

   private FinderQueue() {
      this.clear();
   }

   public static void registerEvents() {
      LevelRenderEvents.COLLECT_SUBMITS.register((CollectSubmits)ctx -> {
         Camera camera = Compat.mainCamera(ctx.gameRenderer());
         get().extractCuboids(ctx.levelState(), camera);
         get().renderCuboids(ctx.submitNodeCollector(), ctx.poseStack(), ctx.levelState());
      });
   }

   public static FinderQueue get() {
      return INSTANCE;
   }

   public void onChunkData(Level world, ChunkPos chunkPos) {
      if (Config.get().active || AutoMineManager.proMode) {
         this.getActiveFinderTypes().forEach(type -> SERVICE.submit(() -> {
            try {
               List<Finder> finders = type.finderBuilder.build(world, chunkPos);
               finders.forEach(finder -> {
                  if (finder.isValidDimension(world.dimensionType())) {
                     finder.findInChunk();
                     this.finderControl.addFinder(type, finder);
                  }
               });
            } catch (Exception e) {
               e.printStackTrace();
            }
         }));
      }
   }

   private void extractCuboids(LevelRenderState state, Camera camera) {
      if (Config.get().render == Config.RenderType.OFF) {
         CUBOIDS_BY_STATE.put(state, Collections.emptySet());
      } else {
         Set<Cuboid> cuboids = new HashSet<>();
         this.finderControl.getActiveFinders().forEach(finder -> {
            if (finder.shouldRender()) {
               finder.cuboids.forEach(cuboid -> cuboids.add(cuboid.offset(camera)));
            }
         });
         OreXrayManager.INSTANCE.getCuboids(camera).forEach(cuboid -> cuboids.add(cuboid.offset(camera)));
         CUBOIDS_BY_STATE.put(state, cuboids);
      }
   }

   public void renderCuboids(SubmitNodeCollector submitter, PoseStack poseStack, LevelRenderState state) {
      Set<Cuboid> cuboids = CUBOIDS_BY_STATE.get(state);
      if (cuboids != null) {
         cuboids.forEach(cuboid -> cuboid.render(poseStack, submitter));
      }
   }

   public List<Finder.Type> getActiveFinderTypes() {
      return Arrays.stream(Finder.Type.values()).filter(type -> type.enabled.get()).collect(Collectors.toList());
   }

   public void clear() {
      this.finderControl = new FinderControl();
   }
}
