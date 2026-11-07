package kaptainwutax.seedcrackerX.elytra;

import net.minecraft.core.BlockPos;

/**
 * One offline end-city candidate. {@code shipKnown} is false when the local
 * structure-template manager was unavailable and the ship could not be
 * confirmed offline; such candidates are deliberately kept and verified on
 * site by {@link ElytraHuntManager}.
 */
public record CityCandidate(BlockPos pos, boolean shipKnown) {
}
