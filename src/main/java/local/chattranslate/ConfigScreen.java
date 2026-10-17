package local.chattranslate;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import local.chattranslate.runtime.LlamaCppRuntime;

public final class ConfigScreen extends Screen {
   private static final String KEY = "localchattranslate.config.";
   private final Screen parent;
   private final ModConfig edited;
   private Component status = Component.translatable(KEY + "footer");
   private boolean failed;
   private int top;
   private Button download;
   private Button logs;
   private Button model;
   private int rowX;
   private int rowWidth;

   public ConfigScreen(Screen parent) {
      super(Component.translatable(KEY + "title"));
      this.parent = parent;
      this.edited = LocalChatTranslate.settings();
   }

   protected void init() {
      int w = Math.min(340, this.width - 24);
      int x = (this.width - w) / 2;
      this.top = Math.max(8, (this.height - 224) / 2);
      this.rowX = x;
      this.rowWidth = w;
      this.addRenderableWidget(Button.builder(toggleLabel("translate", this.edited.enabled), button -> {
         this.edited.enabled = !this.edited.enabled;
         button.setMessage(toggleLabel("translate", this.edited.enabled));
      }).bounds(x, this.top + 36, (w - 6) / 2, 20).build());
      this.addRenderableWidget(Button.builder(toggleLabel("translateNpc", this.edited.translateNpc), button -> {
         this.edited.translateNpc = !this.edited.translateNpc;
         button.setMessage(toggleLabel("translateNpc", this.edited.translateNpc));
      }).bounds(x + (w + 6) / 2, this.top + 36, (w - 6) / 2, 20).build());
      this.addRenderableWidget(Button.builder(toggleLabel("translateTooltips", this.edited.translateTooltips), button -> {
         this.edited.translateTooltips = !this.edited.translateTooltips;
         button.setMessage(toggleLabel("translateTooltips", this.edited.translateTooltips));
      }).bounds(x, this.top + 62, (w - 6) / 2, 20).build());
      this.addRenderableWidget(Button.builder(CommonComponents.optionNameValue(Component.translatable(KEY + "target"),
         LocalChatTranslate.targetSettingLabel(this.edited.targetLanguage)),
         button -> this.minecraft.setScreen(new TargetLanguageScreen(this, this.edited))).bounds(x + (w + 6) / 2, this.top + 62, (w - 6) / 2, 20).build());
      this.download = this.addRenderableWidget(Button.builder(Component.translatable(KEY + "download"), button -> {
         if (LocalChatTranslate.runtimeState() == LlamaCppRuntime.RuntimeState.MODEL_MISSING) {
            LocalChatTranslate.consentToDownload();
            this.edited.modelDownloadConsent = true;
         } else {
            LocalChatTranslate.restartRuntime();
         }
         this.updateDownloadButton();
      }).bounds(x, this.top + 142, (w - 6) / 2, 20).build());
      this.model = this.addRenderableWidget(Button.builder(Component.translatable(KEY + "model"),
         button -> this.minecraft.setScreen(new ModelScreen(this, this.edited))).bounds(x, this.top + 142, w, 20).build());
      this.logs = this.addRenderableWidget(
         Button.builder(Component.translatable(KEY + "openLogs"), button -> Util.getPlatform().openPath(FabricLoader.getInstance().getGameDir().resolve("logs")))
            .bounds(x, this.top + 142, w, 20)
            .build()
      );
      this.updateDownloadButton();
      this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.save()).bounds(x, this.top + 196, (w - 6) / 2, 20).build());
      this.addRenderableWidget(
         Button.builder(CommonComponents.GUI_CANCEL, button -> this.onClose()).bounds(x + (w + 6) / 2, this.top + 196, (w - 6) / 2, 20).build()
      );
   }

   private void updateDownloadButton() {
      LlamaCppRuntime.RuntimeState state = LocalChatTranslate.runtimeState();
      boolean missing = state == LlamaCppRuntime.RuntimeState.MODEL_MISSING;
      this.download.visible = missing || state == LlamaCppRuntime.RuntimeState.FAILED || state == LlamaCppRuntime.RuntimeState.DOWNLOADING;
      this.download.active = state != LlamaCppRuntime.RuntimeState.DOWNLOADING;
      this.download.setMessage(Component.translatable(KEY + (missing ? "download" : "restart")));
      this.download.setTooltip(Tooltip.create(Component.translatable(KEY + (missing ? "download.tooltip" : "restart.tooltip"))));
      Button[] row = this.download.visible ? new Button[]{this.download, this.model, this.logs} : new Button[]{this.model, this.logs};
      int cell = (this.rowWidth - 6 * (row.length - 1)) / row.length;
      for (int i = 0; i < row.length; i++) {
         row[i].setX(this.rowX + i * (cell + 6));
         row[i].setWidth(cell);
      }
   }

   public void tick() {
      super.tick();
      this.updateDownloadButton();
   }

   private static Component toggleLabel(String key, boolean on) {
      return CommonComponents.optionStatus(Component.translatable(KEY + key), on);
   }

   private void save() {
      try {
         LocalChatTranslate.saveSettings(this.edited);
         this.onClose();
      } catch (Exception e) {
         this.status = Component.translatable(KEY + "saveFailed");
         this.failed = true;
      }
   }

   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
      super.extractRenderState(graphics, mouseX, mouseY, delta);
      graphics.centeredText(this.font, this.title, this.width / 2, this.top, -1);
      int w = Math.min(340, this.width - 24);
      String model = this.edited.modelPath == null || this.edited.modelPath.isBlank() ? "TranslateGemma 4B"
         : java.nio.file.Path.of(this.edited.modelPath).getFileName().toString();
      this.line(graphics, Component.translatable(KEY + "subtitle", model, LocalChatTranslate.targetName(this.edited.targetLanguage)), w, 16, -4473925);
      this.line(graphics, Component.translatable(KEY + "keyHint"), w, 88, -4473925);
      this.line(graphics, Component.translatable(KEY + "engine", LocalChatTranslate.runtimeStatus()), w, 108, -4473925);
      if (!this.failed && this.edited.enabled && LocalChatTranslate.resolveTarget(this.edited.targetLanguage).isEmpty()) {
         this.line(graphics, LocalChatTranslate.noTargetMessage(this.edited.targetLanguage, false), w, 177, -30584);
      } else {
         this.line(graphics, this.status, w, 177, this.failed ? -30584 : -2236963);
      }
   }

   private void line(GuiGraphicsExtractor graphics, Component text, int width, int y, int color) {
      graphics.centeredText(this.font, this.font.plainSubstrByWidth(text.getString(), width), this.width / 2, this.top + y, color);
   }

   public void onClose() {
      if (this.minecraft != null) {
         this.minecraft.setScreen(this.parent);
      }
   }
}
