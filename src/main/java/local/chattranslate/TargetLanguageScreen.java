package local.chattranslate;

import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.LanguageInfo;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Target language picker laid out like the language screen: "follow the game language", then every language the model can write. */
public final class TargetLanguageScreen extends Screen {
   private static final String KEY = "localchattranslate.target.";
   private final Screen parent;
   private final ModConfig edited;
   private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
   private LanguageList list;

   public TargetLanguageScreen(Screen parent, ModConfig edited) {
      super(Component.translatable(KEY + "title"));
      this.parent = parent;
      this.edited = edited;
   }

   protected void init() {
      this.layout.setHeaderHeight(4 + 9 + 4 + 9 + 4);
      LinearLayout header = this.layout.addToHeader(LinearLayout.vertical().spacing(4));
      header.defaultCellSetting().alignHorizontallyCenter();
      header.addChild(new StringWidget(this.getTitle(), this.font));
      header.addChild(new StringWidget(Component.translatable(KEY + "info").withStyle(ChatFormatting.GRAY), this.font));
      String previous = this.list == null ? this.edited.targetLanguage : this.list.selectedSetting();
      this.list = this.layout.addToContents(new LanguageList(this.minecraft, this.width, this.height - 66));
      this.layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, button -> this.done()).width(200).build());
      this.layout.visitWidgets(this::addRenderableWidget);
      this.repositionElements();
      this.list.fill(previous);
   }

   protected void repositionElements() {
      this.layout.arrangeElements();
      if (this.list != null) this.list.updateSize(this.width, this.layout);
   }

   private void done() {
      String setting = this.list.selectedSetting();
      if (setting != null) this.edited.targetLanguage = setting;
      this.onClose();
   }

   public void onClose() {
      if (this.minecraft != null) {
         this.minecraft.setScreen(this.parent);
      }
   }

   private final class LanguageList extends ObjectSelectionList<LanguageList.Entry> {
      LanguageList(Minecraft minecraft, int width, int height) {
         super(minecraft, width, height, 33, 18);
      }

      public int getRowWidth() {
         return Math.min(320, this.width - 24);
      }

      void fill(String selectedSetting) {
         this.clearEntries();
         Component game = Component.literal(LocalChatTranslate.gameLanguageName());
         boolean gameSupported = TargetLanguage.supported(Minecraft.getInstance().getLanguageManager().getSelected());
         Component auto = Component.translatable(KEY + "auto", Component.translatable(KEY + (gameSupported ? "auto.current" : "auto.unsupported"), game));
         Entry selected = this.add(TargetLanguage.AUTO, auto, selectedSetting);
         for (Map.Entry<String, LanguageInfo> language : Minecraft.getInstance().getLanguageManager().getLanguages().entrySet()) {
            if (!TargetLanguage.supported(language.getKey())) continue;
            Entry entry = this.add(language.getKey(), language.getValue().toComponent(), selectedSetting);
            if (selected == null && entry != null) selected = entry;
         }
         this.setSelected(selected);
         if (selected != null) this.scrollToEntry(selected);
      }

      /** Adds a row and returns it when it is the selected setting. */
      private Entry add(String setting, Component label, String selectedSetting) {
         Entry entry = new Entry(setting, label);
         this.addEntry(entry);
         return setting.equals(selectedSetting) ? entry : null;
      }

      String selectedSetting() {
         Entry selected = this.getSelected();
         return selected == null ? null : selected.setting;
      }

      final class Entry extends ObjectSelectionList.Entry<Entry> {
         private final String setting;
         private final Component label;

         Entry(String setting, Component label) {
            this.setting = setting;
            this.label = label;
         }

         public Component getNarration() {
            return Component.translatable("narrator.select", this.label);
         }

         public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
            graphics.centeredText(TargetLanguageScreen.this.font, this.label, this.getContentXMiddle(), this.getContentYMiddle() - 4, -1);
         }

         public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
            LanguageList.this.setSelected(this);
            if (doubleClick) TargetLanguageScreen.this.done();
            return true;
         }
      }
   }
}
