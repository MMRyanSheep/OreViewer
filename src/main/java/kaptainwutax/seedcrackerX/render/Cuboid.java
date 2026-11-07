package kaptainwutax.seedcrackerX.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.PoseStack.Pose;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

public class Cuboid {
   private static final float LINE_WIDTH = 2.0F;
   private final AABB box;
   private final int argb;
   private final BlockPos centerPos;

   public Cuboid(AABB box, int argb) {
      this.box = box;
      this.argb = argb;
      this.centerPos = BlockPos.containing(box.getCenter());
   }

   public Cuboid(BoundingBox boundingBox, int argb) {
      this(AABB.of(boundingBox), argb);
   }

   public Cuboid(BlockPos pos, int argb) {
      this(new AABB(pos), argb);
   }

   public Cuboid(BlockPos pos, Vec3i size, int argb) {
      this(AABB.encapsulatingFullBlocks(pos, pos.offset(size)), argb);
   }

   public BlockPos getCenterPos() {
      return this.centerPos;
   }

   public Cuboid offset(Camera camera) {
      return new Cuboid(this.box.move(camera.position().scale(-1.0)), this.argb);
   }

   public void render(PoseStack poseStack, SubmitNodeCollector submitter) {
      submitter.submitCustomGeometry(poseStack, NoDepthLayer.LINES_NO_DEPTH_LAYER, (pose, consumer) -> {
         float x0 = (float)this.box.minX;
         float y0 = (float)this.box.minY;
         float z0 = (float)this.box.minZ;
         float x1 = (float)this.box.maxX;
         float y1 = (float)this.box.maxY;
         float z1 = (float)this.box.maxZ;
         this.edge(consumer, pose, x0, y0, z0, x1, y0, z0, 1.0F, 0.0F, 0.0F);
         this.edge(consumer, pose, x0, y1, z0, x1, y1, z0, 1.0F, 0.0F, 0.0F);
         this.edge(consumer, pose, x0, y0, z1, x1, y0, z1, 1.0F, 0.0F, 0.0F);
         this.edge(consumer, pose, x0, y1, z1, x1, y1, z1, 1.0F, 0.0F, 0.0F);
         this.edge(consumer, pose, x0, y0, z0, x0, y1, z0, 0.0F, 1.0F, 0.0F);
         this.edge(consumer, pose, x1, y0, z0, x1, y1, z0, 0.0F, 1.0F, 0.0F);
         this.edge(consumer, pose, x0, y0, z1, x0, y1, z1, 0.0F, 1.0F, 0.0F);
         this.edge(consumer, pose, x1, y0, z1, x1, y1, z1, 0.0F, 1.0F, 0.0F);
         this.edge(consumer, pose, x0, y0, z0, x0, y0, z1, 0.0F, 0.0F, 1.0F);
         this.edge(consumer, pose, x1, y0, z0, x1, y0, z1, 0.0F, 0.0F, 1.0F);
         this.edge(consumer, pose, x0, y1, z0, x0, y1, z1, 0.0F, 0.0F, 1.0F);
         this.edge(consumer, pose, x1, y1, z0, x1, y1, z1, 0.0F, 0.0F, 1.0F);
      });
   }

   private void edge(VertexConsumer consumer, Pose pose, float x1, float y1, float z1, float x2, float y2, float z2, float nx, float ny, float nz) {
      consumer.addVertex(pose, x1, y1, z1).setColor(this.argb).setNormal(pose, nx, ny, nz).setLineWidth(2.0F);
      consumer.addVertex(pose, x2, y2, z2).setColor(this.argb).setNormal(pose, nx, ny, nz).setLineWidth(2.0F);
   }
}
