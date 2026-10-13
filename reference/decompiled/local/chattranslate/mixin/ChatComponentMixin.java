package local.chattranslate.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.List;
import java.util.function.Predicate;
import local.chattranslate.ChatAttachments;
import local.chattranslate.ChatBridge;
import local.chattranslate.LocalChatTranslate;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessage.Line;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin implements ChatBridge {
   @Shadow
   @Final
   private List<GuiMessage> allMessages;
   @Shadow
   @Final
   private List<Line> trimmedMessages;
   @Shadow
   private Predicate<GuiMessage> visibleMessageFilter;
   @Shadow
   private int chatScrollbarPos;
   @Shadow
   private boolean newMessageSinceScroll;
   @Unique
   private final ChatAttachments lct$attachments = new ChatAttachments();

   @Shadow
   protected abstract void refreshTrimmedMessages();

   @Inject(method = "addMessageToQueue", at = @At("TAIL"))
   private void lct$received(GuiMessage message, CallbackInfo ci) {
      if (this.lct$attachments.prune(this.allMessages)) {
         this.refreshTrimmedMessages();
      }

      LocalChatTranslate.received(this, message);
   }

   @Inject(method = "refreshTrimmedMessages", at = @At("HEAD"))
   private void lct$prune(CallbackInfo ci) {
      this.lct$attachments.prune(this.allMessages);
   }

   @WrapOperation(method = "refreshTrimmedMessages", at = @At(value = "INVOKE", target = "Ljava/util/function/Predicate;test(Ljava/lang/Object;)Z"))
   private boolean lct$parentVisibility(Predicate<GuiMessage> filter, Object message, Operation<Boolean> original) {
      return (Boolean)original.call(new Object[]{filter, this.lct$attachments.parentOrSelf((GuiMessage)message)});
   }

   @Inject(method = "clearMessages", at = @At("TAIL"))
   private void lct$clear(boolean clearHistory, CallbackInfo ci) {
      this.lct$attachments.clear();
   }

   @Override
   public boolean lct$attach(GuiMessage original, String translation) {
      if (this.visibleMessageFilter.test(original) && this.lct$attachments.add(this.allMessages, original, translation)) {
         int scroll = this.chatScrollbarPos;
         boolean newMessage = this.newMessageSinceScroll;
         GuiMessage anchor = scroll > 0 && scroll < this.trimmedMessages.size() ? this.trimmedMessages.get(scroll).parent() : null;
         int lineInAnchor = 0;
         if (anchor != null) {
            for (int i = scroll - 1; i >= 0 && this.trimmedMessages.get(i).parent() == anchor; i--) {
               lineInAnchor++;
            }
         }

         this.refreshTrimmedMessages();
         if (anchor != null) {
            for (int i = 0; i < this.trimmedMessages.size(); i++) {
               if (this.trimmedMessages.get(i).parent() == anchor && lineInAnchor-- == 0) {
                  scroll = i;
                  break;
               }
            }
         }

         this.chatScrollbarPos = scroll;
         this.newMessageSinceScroll = newMessage;
         return true;
      } else {
         return false;
      }
   }
}
