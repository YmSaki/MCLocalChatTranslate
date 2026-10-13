package local.chattranslate;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;

public final class ChatAttachments {
   private final Map<GuiMessage, GuiMessage> parents = new IdentityHashMap<>();

   public boolean add(List<GuiMessage> history, GuiMessage original, String translation) {
      int index = identityIndex(history, original);
      if (index >= 0 && !this.parents.containsValue(original)) {
         GuiMessage child = new GuiMessage(
            original.addedTime(),
            Component.literal("↳").withStyle(ChatFormatting.AQUA).append(Component.literal(translation).withStyle(ChatFormatting.WHITE)),
            null,
            original.source(),
            GuiMessageTag.system()
         );
         history.add(index, child);
         this.parents.put(child, original);
         return true;
      } else {
         return false;
      }
   }

   public GuiMessage parentOrSelf(GuiMessage message) {
      return this.parents.getOrDefault(message, message);
   }

   public boolean prune(List<GuiMessage> history) {
      if (this.parents.isEmpty()) {
         return false;
      }

      Set<GuiMessage> live = Collections.newSetFromMap(new IdentityHashMap<>());
      live.addAll(history);
      boolean changed = false;
      Iterator<Entry<GuiMessage, GuiMessage>> iterator = this.parents.entrySet().iterator();

      while (iterator.hasNext()) {
         Entry<GuiMessage, GuiMessage> entry = iterator.next();
         if (!live.contains(entry.getKey()) || !live.contains(entry.getValue())) {
            int index = identityIndex(history, entry.getKey());
            if (index >= 0) {
               history.remove(index);
               changed = true;
            }

            iterator.remove();
         }
      }

      return changed;
   }

   public void clear() {
      this.parents.clear();
   }

   private static int identityIndex(List<GuiMessage> history, GuiMessage message) {
      for (int i = 0; i < history.size(); i++) {
         if (history.get(i) == message) {
            return i;
         }
      }

      return -1;
   }
}
