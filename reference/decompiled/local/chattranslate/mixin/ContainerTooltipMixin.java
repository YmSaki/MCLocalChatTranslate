package local.chattranslate.mixin;

import java.util.List;
import local.chattranslate.LocalChatTranslate;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerScreen.class)
public abstract class ContainerTooltipMixin {
   @Shadow
   protected Slot hoveredSlot;

   @Inject(method = "extractTooltip", at = @At("HEAD"))
   private void lct$hover(GuiGraphicsExtractor graphics, int x, int y, CallbackInfo ci) {
      LocalChatTranslate.tooltipHover((Screen)this, this.hoveredSlot);
   }

   @ModifyArg(
      method = "extractTooltip",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;IILnet/minecraft/resources/Identifier;)V"
      ),
      index = 1
   )
   private List<Component> lct$translateTooltip(List<Component> original) {
      return LocalChatTranslate.tooltipLines(original);
   }

   @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
   private void lct$key(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
      if (LocalChatTranslate.tooltipKey((Screen)this, this.hoveredSlot, event)) {
         cir.setReturnValue(true);
      }
   }
}
