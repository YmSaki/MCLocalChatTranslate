package local.chattranslate;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.impl.command.client.ClientCommandInternals;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import local.chattranslate.runtime.LlamaCppRuntime;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

/** Runs only with the Fabric client game test task; it is absent from the distributed MOD. */
@SuppressWarnings("UnstableApiUsage")
public final class OriginalClientGameTest implements FabricClientGameTest {
   @Override
   public void runTest(ClientGameTestContext context) {
      context.runOnClient(client -> {
         if (LocalChatTranslate.settings() == null) {
            throw new AssertionError("Original config is unavailable");
         }
      });

      context.setScreen(() -> new ConfigScreen(null));
      context.waitForScreen(ConfigScreen.class);
      context.runOnClient(client -> {
         if (!(client.screen instanceof ConfigScreen)) {
            throw new AssertionError("Configuration screen did not open");
         }
      });
      System.out.println("[LCT reconstruction test] ConfigScreen opened");
      System.out.println("[LCT integration test] settings screenshot: " + context.takeScreenshot("bundled-settings"));
      context.setScreen(() -> null);

      context.waitFor(client -> LocalChatTranslate.runtimeState() == LlamaCppRuntime.RuntimeState.READY, 2400);
      System.out.println("[LCT integration test] bundled runtime READY");
      context.setScreen(() -> new ConfigScreen(null));
      context.waitForScreen(ConfigScreen.class);
      context.waitTicks(2);
      context.runOnClient(client -> {
         try {
            Field download = ConfigScreen.class.getDeclaredField("download");
            download.setAccessible(true);
            if (((net.minecraft.client.gui.components.Button) download.get(client.screen)).visible) {
               throw new AssertionError("Model download button is shown while the runtime is READY");
            }
         } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
         }
      });
      context.setScreen(() -> null);
      System.out.println("[LCT integration test] download button hidden when READY");
      context.setScreen(() -> new ModelScreen(null, LocalChatTranslate.settings()));
      context.waitForScreen(ModelScreen.class);
      System.out.println("[LCT integration test] model screen screenshot: " + context.takeScreenshot("model-screen"));
      context.setScreen(() -> new TargetLanguageScreen(null, LocalChatTranslate.settings()));
      context.waitForScreen(TargetLanguageScreen.class);
      System.out.println("[LCT integration test] target language screen screenshot: " + context.takeScreenshot("target-language-screen"));
      context.setScreen(() -> null);

      try (TestSingleplayerContext world = context.worldBuilder().create()) {
         world.getClientLevel().waitForChunksRender();
         context.waitFor(client -> ClientCommandInternals.getActiveDispatcher() != null
            && ClientCommandInternals.getActiveDispatcher().getRoot().getChild("lct") != null);
         System.out.println("[LCT reconstruction test] /lct registered");

         // A fresh config follows the game language, and the test client runs in English: nothing may be translated.
         context.runOnClient(client -> {
            if (!TargetLanguage.AUTO.equals(LocalChatTranslate.settings().targetLanguage) || LocalChatTranslate.target().isPresent()) {
               throw new AssertionError("Fresh config with an English client resolved a target");
            }
            client.gui.getChat().addPlayerMessage(Component.literal("[1] TestPlayer: Good morning."), null, GuiMessageTag.system());
         });
         context.waitTicks(100);
         context.runOnClient(client -> {
            if (!translatedMessages(client).isEmpty()) throw new AssertionError("Translated without a target language");
         });
         System.out.println("[LCT integration test] no translation while the target is unresolved");

         context.runOnClient(client -> {
            ModConfig config = LocalChatTranslate.settings();
            config.targetLanguage = "ja_jp";
            try {
               LocalChatTranslate.saveSettings(config);
            } catch (java.io.IOException e) {
               throw new AssertionError(e);
            }
         });

         context.runOnClient(client -> client.gui.getChat().addPlayerMessage(
            Component.literal("[1] TestPlayer: Hello world."), null, GuiMessageTag.system()));
         context.waitFor(client -> translatedMessages(client).size() >= 1, 1200);
         System.out.println("[LCT integration test] chat translated: " +
            context.computeOnClient(client -> translatedMessages(client).get(0)));

         context.runOnClient(client -> client.gui.getChat().addPlayerMessage(
            Component.literal("[NPC] Villager: Welcome to the village."), null, GuiMessageTag.system()));
         context.waitFor(client -> translatedMessages(client).size() >= 2, 1200);
         System.out.println("[LCT integration test] NPC translated: " +
            context.computeOnClient(client -> translatedMessages(client).get(0)));

         context.runOnClient(client -> {
            ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
            sword.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("A powerful sword for brave adventurers."))));
            client.player.getInventory().setItem(0, sword);
         });

         context.setScreen(() -> new InventoryScreen(Minecraft.getInstance().player));
         context.waitForScreen(InventoryScreen.class);
         double[] cursor = context.computeOnClient(client -> {
            InventoryScreen screen = (InventoryScreen) client.screen;
            Slot itemSlot = screen.getMenu().slots.stream()
               .filter(slot -> slot.getItem().is(Items.DIAMOND_SWORD))
               .findFirst().orElseThrow(() -> new AssertionError("Test item is not in inventory"));
            try {
               Field left = AbstractContainerScreen.class.getDeclaredField("leftPos");
               Field top = AbstractContainerScreen.class.getDeclaredField("topPos");
               left.setAccessible(true);
               top.setAccessible(true);
               int scale = client.getWindow().getGuiScale();
               return new double[]{(left.getInt(screen) + itemSlot.x + 8) * scale,
                  (top.getInt(screen) + itemSlot.y + 8) * scale};
            } catch (ReflectiveOperationException e) {
               throw new AssertionError(e);
            }
         });
         context.getInput().setCursorPos(cursor[0], cursor[1]);
         context.waitFor(client -> {
            if (!(client.screen instanceof InventoryScreen screen)) return false;
            try {
               Field hovered = AbstractContainerScreen.class.getDeclaredField("hoveredSlot");
               hovered.setAccessible(true);
               Slot slot = (Slot) hovered.get(screen);
               return slot != null && slot.getItem().is(Items.DIAMOND_SWORD);
            } catch (ReflectiveOperationException e) {
               throw new AssertionError(e);
            }
         });
         context.waitTicks(5);
         context.getInput().pressKey(71);
         context.waitTicks(60);
         System.out.println("[LCT integration test] tooltip screenshot: " + context.takeScreenshot("bundled-tooltip"));
         context.runOnClient(client -> {
            if (cachedTranslations() == 0) throw new AssertionError("Tooltip translation was not cached");
         });

         // Following the game language: switching it must drop translations made for the old target.
         // The config is changed without saveSettings so only the per-tick check can notice.
         context.runOnClient(client -> {
            client.getLanguageManager().setSelected("ko_kr");
            modConfig().targetLanguage = TargetLanguage.AUTO;
         });
         context.waitTicks(5);
         context.runOnClient(client -> {
            if (cachedTranslations() != 0) throw new AssertionError("Tooltip translations survived a target change");
            List<Component> lines = LocalChatTranslate.tooltipLines(List.of(Component.literal("Sword"), Component.literal("A sharp blade.")));
            String hint = lines.getLast().getString();
            if (!hint.contains("한국어")) throw new AssertionError("Tooltip hint does not name the new target: " + hint);
         });
         System.out.println("[LCT integration test] game language switch reset tooltip translations");

         // Switching the game language to English leaves no target; the player must be told.
         context.runOnClient(client -> client.getLanguageManager().setSelected("en_us"));
         context.waitTicks(5);
         context.runOnClient(client -> {
            if (allMessages(client).stream().filter(text -> text.startsWith("[LCT] Translation is paused")).count() < 2) {
               throw new AssertionError("No notice when the target was lost");
            }
         });
         System.out.println("[LCT integration test] notice shown when the target was lost");
         context.setScreen(() -> null);
      }
   }

   private static List<String> translatedMessages(Minecraft client) {
      return allMessages(client).stream().filter(text -> text.startsWith("↳")).toList();
   }

   private static List<String> allMessages(Minecraft client) {
      try {
         Field messages = ChatComponent.class.getDeclaredField("allMessages");
         messages.setAccessible(true);
         @SuppressWarnings("unchecked") List<GuiMessage> history = (List<GuiMessage>) messages.get(client.gui.getChat());
         return history.stream().map(message -> message.content().getString()).toList();
      } catch (ReflectiveOperationException e) {
         throw new AssertionError(e);
      }
   }

   private static Object field(Object owner, Class<?> type, String name) throws ReflectiveOperationException {
      Field field = type.getDeclaredField(name);
      field.setAccessible(true);
      return field.get(owner);
   }

   private static Object instance() throws ReflectiveOperationException {
      return field(null, LocalChatTranslate.class, "instance");
   }

   /** The live config, not the copy settings() returns. */
   private static ModConfig modConfig() {
      try {
         return (ModConfig) field(instance(), LocalChatTranslate.class, "config");
      } catch (ReflectiveOperationException e) {
         throw new AssertionError(e);
      }
   }

   /** Number of tooltip lines with a stored translation. */
   private static long cachedTranslations() {
      try {
         Object tooltips = field(instance(), LocalChatTranslate.class, "tooltips");
         Map<?, ?> cache = (Map<?, ?>) field(tooltips, TooltipTranslations.class, "cache");
         long count = 0;
         for (Object entry : cache.values()) {
            List<?> translated = (List<?>) field(entry, entry.getClass(), "translated");
            count += translated.stream().filter(java.util.Objects::nonNull).count();
         }
         return count;
      } catch (ReflectiveOperationException e) {
         throw new AssertionError(e);
      }
   }
}
