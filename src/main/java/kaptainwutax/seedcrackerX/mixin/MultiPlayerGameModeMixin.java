package kaptainwutax.seedcrackerX.mixin;

import kaptainwutax.seedcrackerX.automine.ShulkerAutoStore;
import kaptainwutax.seedcrackerX.elytra.FlyController;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the elytra hunt's block break alive.
 *
 * <p>Minecraft.continueAttack() calls stopDestroyBlock() whenever keyAttack is
 * released or the crosshair hits an entity. Near the item frame the ignored
 * Shulker (and its bullets) regularly sit on the crosshair, so vanilla sent
 * ABORT_DESTROY_BLOCK every tick and the server-side break progress never
 * accumulated. FlyController sets a short, self-expiring deadline while it is
 * actively driving a break; in that window the abort is suppressed. Our own
 * stopMining() clears the deadline first, so intentional aborts still work.</p>
 *
 * <p>The automatic shulker-box store drives its break manually (it must ignore
 * the real crosshair to mine through an occluding wall), and Minecraft calls
 * stopDestroyBlock() on EVERY frame while keyAttack is released. It therefore
 * uses the same protection with its own independent deadline.</p>
 */
@Mixin(MultiPlayerGameMode.class)
public final class MultiPlayerGameModeMixin {

   @Inject(method = "stopDestroyBlock()V", at = @At("HEAD"), cancellable = true, require = 0)
   private void oreviewer$protectElytraBreak(CallbackInfo ci) {
      if (FlyController.isDestroyProtected() || ShulkerAutoStore.isDestroyProtected()) {
         ci.cancel();
      }
   }
}
