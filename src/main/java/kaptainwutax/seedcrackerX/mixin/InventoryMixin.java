package kaptainwutax.seedcrackerX.mixin;

import kaptainwutax.seedcrackerX.automine.AutoMineManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Inventory.class)
public class InventoryMixin {
   @Inject(method = "add(Lnet/minecraft/world/item/ItemStack;)Z", at = @At("HEAD"), cancellable = true)
   public void onAdd(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
      if (stack != null && !stack.isEmpty()) {
         try {
            String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            if (!AutoMineManager.isPickupWhitelisted(itemId)) {
               cir.setReturnValue(false);
            }
         } catch (Throwable var4) {
         }
      }
   }

   @Inject(method = "add(ILnet/minecraft/world/item/ItemStack;)Z", at = @At("HEAD"), cancellable = true)
   public void onAddSlot(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
      if (stack != null && !stack.isEmpty()) {
         try {
            String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            if (!AutoMineManager.isPickupWhitelisted(itemId)) {
               cir.setReturnValue(false);
            }
         } catch (Throwable var5) {
         }
      }
   }
}
