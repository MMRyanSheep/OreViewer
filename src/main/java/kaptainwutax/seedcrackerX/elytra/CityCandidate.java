package kaptainwutax.seedcrackerX.elytra;

import net.minecraft.core.BlockPos;

/**
 * One offline end-city candidate.
 *
 * <p>{@code pos} is the vanilla {@code /locate} position (the structure
 * placement start). That is NOT where the end ship is: the ship is a separate
 * {@code TemplateStructurePiece} attached to the side of a tower and can be
 * several dozen blocks away. {@code shipPos}, when available, is the centre of
 * that piece's bounding box extracted during offline generation - it is the
 * coordinate the hunt must actually fly to and scan around.</p>
 *
 * <p>{@code shipKnown} is false when the local structure-template manager was
 * unavailable and the ship could not be confirmed offline; such candidates are
 * deliberately kept and verified on site by {@link ElytraHuntManager}.</p>
 */
public record CityCandidate(BlockPos pos, boolean shipKnown, BlockPos shipPos) {

   public CityCandidate(BlockPos pos, boolean shipKnown) {
      this(pos, shipKnown, null);
   }

   /** The point to fly to / scan around: the ship when known, otherwise the city start. */
   public BlockPos target() {
      return this.shipPos != null ? this.shipPos : this.pos;
   }

   public boolean shipLocated() {
      return this.shipPos != null;
   }
}
