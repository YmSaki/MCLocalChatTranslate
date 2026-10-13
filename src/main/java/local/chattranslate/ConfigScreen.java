package local.chattranslate;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

public final class ConfigScreen extends Screen {
   private final Screen parent;
   private final ModConfig edited;
   private String status = "原文の下に訳文を追加します。";
   private boolean failed;
   private int top;

   public ConfigScreen(Screen parent) {
      super(Component.literal("Local Chat Translate"));
      this.parent = parent;
      this.edited = LocalChatTranslate.settings();
   }

   protected void init() {
      int w = Math.min(340, this.width - 24);
      int x = (this.width - w) / 2;
      this.top = Math.max(8, (this.height - 224) / 2);
      this.addRenderableWidget(Button.builder(toggleLabel("翻訳", this.edited.enabled), button -> {
         this.edited.enabled = !this.edited.enabled;
         button.setMessage(toggleLabel("翻訳", this.edited.enabled));
      }).bounds(x, this.top + 36, (w - 6) / 2, 20).build());
      this.addRenderableWidget(Button.builder(toggleLabel("NPCの台詞も翻訳", this.edited.translateNpc), button -> {
         this.edited.translateNpc = !this.edited.translateNpc;
         button.setMessage(toggleLabel("NPCの台詞も翻訳", this.edited.translateNpc));
      }).bounds(x + (w + 6) / 2, this.top + 36, (w - 6) / 2, 20).build());
      this.addRenderableWidget(Button.builder(toggleLabel("アイテム説明の翻訳", this.edited.translateTooltips), button -> {
         this.edited.translateTooltips = !this.edited.translateTooltips;
         button.setMessage(toggleLabel("アイテム説明の翻訳", this.edited.translateTooltips));
      }).bounds(x, this.top + 62, w, 20).build());
      this.addRenderableWidget(
         Button.builder(Component.literal("ログフォルダを開く"), button -> Util.getPlatform().openPath(FabricLoader.getInstance().getGameDir().resolve("logs")))
            .bounds(x, this.top + 142, w, 20)
            .build()
      );
      this.addRenderableWidget(Button.builder(Component.literal("保存して閉じる"), button -> this.save()).bounds(x, this.top + 196, (w - 6) / 2, 20).build());
      this.addRenderableWidget(
         Button.builder(Component.literal("キャンセル"), button -> this.onClose()).bounds(x + (w + 6) / 2, this.top + 196, (w - 6) / 2, 20).build()
      );
   }

   private static Component toggleLabel(String text, boolean on) {
      return Component.literal(text + ": " + (on ? "ON" : "OFF"));
   }

   private void save() {
      try {
         LocalChatTranslate.saveSettings(this.edited);
         this.onClose();
      } catch (Exception e) {
         this.status = "設定ファイルを保存できませんでした。";
         this.failed = true;
      }
   }

   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
      super.extractRenderState(graphics, mouseX, mouseY, delta);
      graphics.centeredText(this.font, this.title, this.width / 2, this.top, -1);
      graphics.centeredText(this.font, "TranslateGemma 4B / 内蔵 llama.cpp · 英語 → 日本語", this.width / 2, this.top + 16, -4473925);
      graphics.centeredText(this.font, "ツールチップのキーは「操作設定」で変更できます。", this.width / 2, this.top + 88, -4473925);
      graphics.centeredText(this.font, "翻訳エンジン: " + LocalChatTranslate.runtimeState(), this.width / 2, this.top + 114, -4473925);
      int w = Math.min(340, this.width - 24);
      String line = this.font.plainSubstrByWidth(this.status, w);
      graphics.centeredText(this.font, line, this.width / 2, this.top + 177, this.failed ? -30584 : -2236963);
   }

   public void onClose() {
      if (this.minecraft != null) {
         this.minecraft.setScreen(this.parent);
      }
   }
}
