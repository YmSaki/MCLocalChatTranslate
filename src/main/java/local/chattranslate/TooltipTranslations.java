package local.chattranslate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping.Category;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.Slot;

final class TooltipTranslations {
   private static final int CACHE_SIZE = 64;
   private final TranslationClient api;
   private final Supplier<ModConfig> settings;
   private final KeyMapping key = KeyMappingHelper.registerKeyMapping(
      new KeyMapping("key.localchattranslate.tooltip", 71, Category.register(Identifier.fromNamespaceAndPath("localchattranslate", "translation")))
   );
   private final LinkedHashMap<List<String>, TooltipTranslations.Entry> cache = new LinkedHashMap<>(16, 0.75F, true);
   private Screen screen;
   private Slot slot;
   private TooltipTranslations.Entry current;
   private long generation;

   TooltipTranslations(TranslationClient api, Supplier<ModConfig> settings) {
      this.api = api;
      this.settings = settings;
   }

   void hover(Screen screen, Slot slot) {
      if (this.screen != screen || this.slot != slot) {
         this.cancel();
         this.current = null;
      }

      this.screen = screen;
      this.slot = slot;
   }

   void screenChanged(Screen screen) {
      if (this.screen != screen) {
         this.hover(screen, null);
      }
   }

   private void cancel() {
      this.generation++;
      if (this.current != null && !this.current.complete) {
         this.current.visible = false;
         this.current.busy = false;
      }
   }

   void reset() {
      this.cancel();
      this.cache.clear();
      this.current = null;
      this.screen = null;
      this.slot = null;
   }

   List<Component> render(List<Component> original) {
      ModConfig config = this.settings.get();
      if (config.enabled && config.translateTooltips && !original.isEmpty() && original.size() <= 96) {
         List<String> signature = original.stream().<String>map(Component::getString).toList();
         if (signature.stream().mapToInt(String::length).sum() > 8192) {
            this.cancel();
            this.current = null;
            return original;
         }

         TooltipTranslations.Entry entry = this.cache.get(signature);
         if (entry == null) {
            entry = new TooltipTranslations.Entry(original);
            this.cache.put(signature, entry);

            while (this.cache.size() > 64) {
               this.cache.pollFirstEntry();
            }
         }

         if (this.current != entry) {
            this.cancel();
            this.current = entry;
         }

         for (int i = 0; i < original.size(); i++) {
            if (!entry.lines.get(i).original.equals(original.get(i))) {
               entry.lines.set(i, new TooltipLine(original.get(i)));
            }
         }

         if (!hasCandidates(entry)) {
            return original;
         }

         List<Component> result = new ArrayList<>(original);
         if (entry.visible) {
            for (int i = 1; i < entry.lines.size(); i++) {
               String translated = entry.translated.get(i);
               if (translated != null) {
                  try {
                     result.set(i, entry.lines.get(i).restore(translated));
                  } catch (IllegalArgumentException var9) {
                  }
               }
            }
         }

         String action = entry.visible ? "原文に戻す" : "説明を日本語に翻訳";
         if (entry.busy) {
            action = action + "・翻訳中…";
         } else if (entry.visible && entry.failed) {
            action = action + "・一部の行は原文";
         }

         result.add(
            Component.literal("[" + this.key.getTranslatedKeyMessage().getString() + "] " + action)
               .withStyle(entry.failed && entry.visible ? ChatFormatting.YELLOW : ChatFormatting.GRAY)
         );
         return result;
      } else {
         this.cancel();
         this.current = null;
         return original;
      }
   }

   boolean keyPressed(Screen screen, Slot slot, KeyEvent event) {
      ModConfig config = this.settings.get();
      if (!config.enabled
         || !config.translateTooltips
         || this.screen != screen
         || this.slot != slot
         || slot == null
         || !slot.hasItem()
         || this.current == null
         || !this.key.matches(event)
         || screen.getFocused() instanceof EditBox) {
         return false;
      }

      if (!hasCandidates(this.current)) {
         return false;
      }

      if (this.current.visible) {
         this.cancel();
         this.current.visible = false;
      } else {
         this.current.visible = true;
         this.current.failed = false;
         if (!this.current.complete) {
            this.translateNext(this.current, 1, ++this.generation);
         }
      }

      return true;
   }

   private static boolean hasCandidates(TooltipTranslations.Entry entry) {
      for (int i = 1; i < entry.lines.size(); i++) {
         if (canTranslate(entry.lines.get(i))) {
            return true;
         }
      }

      return false;
   }

   private static boolean canTranslate(TooltipLine line) {
      return line.needsTranslation() && !line.body.original().matches("[a-z0-9_.-]+:[a-z0-9_./-]+");
   }

   private void translateNext(TooltipTranslations.Entry entry, int from, long requestGeneration) {
      if (requestGeneration == this.generation && entry.visible) {
         int next = from;

         while (next < entry.lines.size() && (entry.translated.get(next) != null || !canTranslate(entry.lines.get(next)))) {
            next++;
         }

         if (next == entry.lines.size()) {
            entry.busy = false;
            entry.complete = !entry.failed;
         } else {
            int index = next;
            entry.busy = true;
            TooltipLine line = entry.lines.get(index);

            try {
               this.api
                  .translateTooltipLine(line)
                  .whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
                     if (requestGeneration == this.generation && entry.visible) {
                        if (error != null) {
                           entry.failed = true;
                        } else {
                           entry.translated.set(index, result.orElse(line.body.requestText()));
                        }

                        this.translateNext(entry, index + 1, requestGeneration);
                     }
                  }));
            } catch (RejectedExecutionException e) {
               entry.failed = true;
               entry.busy = false;
            }
         }
      }
   }

   private static final class Entry {
      final List<TooltipLine> lines;
      final List<String> translated;
      boolean visible;
      boolean busy;
      boolean complete;
      boolean failed;

      Entry(List<Component> original) {
         this.lines = new ArrayList<>(original.stream().map(TooltipLine::new).toList());
         this.translated = new ArrayList<>(Collections.nCopies(original.size(), null));
      }
   }
}
