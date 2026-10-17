package local.chattranslate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

/** Model picker laid out like the resource pack screen: the default model, GGUFs in the models folder and the Hugging Face cache. */
public final class ModelScreen extends Screen {
   private static final String KEY = "localchattranslate.model.";
   private static final Identifier ICON = Identifier.fromNamespaceAndPath("localchattranslate", "icon.png");
   private final Screen parent;
   private final ModConfig edited;
   private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
   /** Files added with 参照… or drag and drop during this visit. */
   private final List<Path> added = new ArrayList<>();
   private ModelList list;
   private Component error = Component.empty();

   public ModelScreen(Screen parent, ModConfig edited) {
      super(Component.translatable(KEY + "title"));
      this.parent = parent;
      this.edited = edited;
   }

   protected void init() {
      this.layout.setHeaderHeight(4 + 9 + 4 + 9 + 4);
      LinearLayout header = this.layout.addToHeader(LinearLayout.vertical().spacing(4));
      header.defaultCellSetting().alignHorizontallyCenter();
      header.addChild(new StringWidget(this.getTitle(), this.font));
      header.addChild(new StringWidget(Component.translatable(KEY + "dropInfo"), this.font));
      ModelChoice previous = this.list == null ? null : this.list.selectedChoice();
      this.list = this.layout.addToContents(new ModelList(this.minecraft, this.width, this.height - 66));
      LinearLayout footer = this.layout.addToFooter(LinearLayout.horizontal().spacing(8));
      footer.addChild(Button.builder(Component.translatable(KEY + "openFolder"), button -> this.openFolder())
         .tooltip(Tooltip.create(Component.translatable(KEY + "folderInfo"))).width(130).build());
      footer.addChild(Button.builder(Component.translatable(KEY + "browse"), button -> this.browse()).width(130).build());
      footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> this.done()).width(130).build());
      this.layout.visitWidgets(this::addRenderableWidget);
      this.repositionElements();
      this.reload(previous);
   }

   protected void repositionElements() {
      this.layout.arrangeElements();
      if (this.list != null) this.list.updateSize(this.width, this.layout);
   }

   /** One selectable model; {@code path} is null for the default model. */
   private record ModelChoice(Path path, Component name, Component detail) { }

   private void reload(ModelChoice keep) {
      Map<Path, ModelChoice> files = new LinkedHashMap<>();
      for (Path file : scan(LocalChatTranslate.modelsDir(), 1)) {
         files.putIfAbsent(key(file), new ModelChoice(file, Component.literal(file.getFileName().toString()), Component.translatable(KEY + "source.folder", size(file))));
      }
      for (Path file : scanHuggingFaceCache()) {
         files.putIfAbsent(key(file), new ModelChoice(file, Component.literal(file.getFileName().toString()), Component.translatable(KEY + "source.hfCache", repoName(file), size(file))));
      }
      List<Path> extra = new ArrayList<>(this.added);
      if (this.edited.modelPath != null && !this.edited.modelPath.isBlank()) extra.add(Path.of(this.edited.modelPath));
      for (Path file : extra) {
         files.putIfAbsent(key(file), new ModelChoice(file, Component.literal(file.getFileName().toString()),
            Files.isRegularFile(file) ? Component.translatable(KEY + "source.file", size(file), String.valueOf(file.getParent()))
               : Component.translatable(KEY + "source.missing", String.valueOf(file.getParent()))));
      }
      List<ModelChoice> choices = new ArrayList<>();
      choices.add(new ModelChoice(null, Component.translatable("pack.nameAndSource", "TranslateGemma 4B",
         Component.translatable(KEY + "default")), Component.empty()));
      choices.addAll(files.values());

      Path wanted = keep != null ? keep.path() : (this.edited.modelPath == null || this.edited.modelPath.isBlank() ? null : Path.of(this.edited.modelPath));
      this.list.replace(choices, wanted == null ? null : key(wanted));
   }

   private static List<Path> scan(Path dir, int depth) {
      if (!Files.isDirectory(dir)) return List.of();
      try (Stream<Path> paths = Files.walk(dir, depth)) {
         return paths.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gguf") && Files.isRegularFile(p))
            .sorted().toList();
      } catch (IOException | RuntimeException e) {
         return List.of();
      }
   }

   /** Snapshot files in the Hugging Face hub cache ({@code HF_HUB_CACHE}, {@code HF_HOME/hub} or {@code ~/.cache/huggingface/hub}). */
   private static List<Path> scanHuggingFaceCache() {
      Path hub;
      String hubCache = System.getenv("HF_HUB_CACHE");
      String home = System.getenv("HF_HOME");
      if (hubCache != null && !hubCache.isBlank()) hub = Path.of(hubCache);
      else if (home != null && !home.isBlank()) hub = Path.of(home, "hub");
      else hub = Path.of(System.getProperty("user.home"), ".cache", "huggingface", "hub");
      if (!Files.isDirectory(hub)) return List.of();
      List<Path> found = new ArrayList<>();
      try (Stream<Path> repos = Files.list(hub)) {
         for (Path repo : repos.filter(p -> p.getFileName().toString().startsWith("models--")).sorted().toList()) {
            found.addAll(scan(repo.resolve("snapshots"), 4));
         }
      } catch (IOException | RuntimeException e) {
         return found;
      }
      return found;
   }

   private static String repoName(Path snapshotFile) {
      for (Path p = snapshotFile; p != null; p = p.getParent()) {
         String name = p.getFileName() == null ? "" : p.getFileName().toString();
         if (name.startsWith("models--")) return name.substring("models--".length()).replace("--", "/");
      }
      return "";
   }

   private static Path key(Path file) {
      try {
         return file.toRealPath();
      } catch (IOException | RuntimeException e) {
         return file.toAbsolutePath().normalize();
      }
   }

   private static String size(Path file) {
      try {
         return String.format(Locale.ROOT, "%.2f GB", Files.size(file) / 1e9);
      } catch (IOException e) {
         return "? GB";
      }
   }

   /** Download state of the default model, refreshed while the screen is open. */
   private static Component defaultStatus() {
      if (LocalChatTranslate.runtimeState() == local.chattranslate.runtime.LlamaCppRuntime.RuntimeState.DOWNLOADING) {
         return LocalChatTranslate.downloadStatus();
      }
      return Component.translatable(KEY + (LocalChatTranslate.defaultModelDownloaded() ? "downloaded" : "notDownloaded"));
   }

   private void openFolder() {
      Path dir = LocalChatTranslate.modelsDir();
      try {
         Files.createDirectories(dir);
      } catch (IOException ignored) {
         // openPath reports a missing folder itself.
      }
      Util.getPlatform().openPath(dir);
   }

   private void browse() {
      String title = Component.translatable(KEY + "browse.title").getString();
      String filterName = Component.translatable(KEY + "browse.filter").getString();
      // The native dialog blocks, so it runs off the render thread.
      Thread.ofPlatform().daemon().name("localchattranslate-file-dialog").start(() -> {
         String chosen;
         try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(1);
            filters.put(stack.UTF8("*.gguf")).flip();
            chosen = TinyFileDialogs.tinyfd_openFileDialog(title, null, filters, filterName, false);
         }
         if (chosen != null && this.minecraft != null) {
            this.minecraft.execute(() -> this.addFiles(List.of(Path.of(chosen))));
         }
      });
   }

   public void onFilesDrop(List<Path> files) {
      this.addFiles(files);
   }

   private void addFiles(List<Path> files) {
      List<Path> models = files.stream().filter(ModelScreen::isGguf).toList();
      List<Path> rejected = files.stream().filter(p -> !isGguf(p)).toList();
      this.error = Component.empty();
      if (!models.isEmpty()) {
         this.added.addAll(models);
         this.reload(new ModelChoice(models.getFirst(), Component.empty(), Component.empty()));
      }
      if (!rejected.isEmpty()) {
         String names = String.join(", ", rejected.stream().map(p -> p.getFileName().toString()).toList());
         ClientUi.setScreen(this.minecraft, new net.minecraft.client.gui.screens.AlertScreen(() -> ClientUi.setScreen(this.minecraft, this),
            Component.translatable(KEY + "dropRejected.title"), Component.translatable(KEY + "dropRejected.message", names)));
      }
   }

   private static boolean isGguf(Path file) {
      return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gguf") && Files.isRegularFile(file);
   }

   private void done() {
      ModelChoice choice = this.list.selectedChoice();
      if (choice == null) {
         this.onClose();
         return;
      }
      if (choice.path() != null && !Files.isRegularFile(choice.path())) {
         this.error = Component.translatable(KEY + "fileMissing", choice.path().toString());
         return;
      }
      String modelPath = choice.path() == null ? null : choice.path().toString();
      // Choosing the default model while it is missing, with its size and source shown, is the consent to download it.
      boolean consent = choice.path() == null && !LocalChatTranslate.defaultModelDownloaded();
      try {
         LocalChatTranslate.selectModel(modelPath, consent);
      } catch (IOException e) {
         this.error = Component.translatable("localchattranslate.config.saveFailed");
         return;
      }
      this.edited.modelPath = modelPath;
      if (consent) this.edited.modelDownloadConsent = true;
      this.onClose();
   }

   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
      super.extractRenderState(graphics, mouseX, mouseY, delta);
      if (!this.error.getString().isEmpty()) {
         int w = Math.min(340, this.width - 24);
         graphics.centeredText(this.font, this.font.plainSubstrByWidth(this.error.getString(), w), this.width / 2,
            this.height - this.layout.getFooterHeight() - 12, -30584);
      }
   }

   public void onClose() {
      if (this.minecraft != null) {
         ClientUi.setScreen(this.minecraft, this.parent);
      }
   }

   private final class ModelList extends ObjectSelectionList<ModelList.Entry> {
      ModelList(Minecraft minecraft, int width, int height) {
         super(minecraft, width, height, 33, 36);
      }

      public int getRowWidth() {
         return Math.min(320, this.width - 24);
      }

      void replace(List<ModelChoice> choices, Path selectedKey) {
         this.clearEntries();
         Entry selected = null;
         for (ModelChoice choice : choices) {
            Entry entry = new Entry(choice);
            this.addEntry(entry);
            boolean match = choice.path() == null ? selectedKey == null : selectedKey != null && key(choice.path()).equals(selectedKey);
            if (match && selected == null) selected = entry;
         }
         this.setSelected(selected);
         if (selected != null) this.scrollToEntry(selected);
      }

      ModelChoice selectedChoice() {
         Entry selected = this.getSelected();
         return selected == null ? null : selected.choice;
      }

      final class Entry extends ObjectSelectionList.Entry<Entry> {
         private final ModelChoice choice;
         private final StringWidget name;
         private final MultiLineTextWidget detail;

         Entry(ModelChoice choice) {
            this.choice = choice;
            this.name = new StringWidget(choice.name(), ModelScreen.this.font);
            this.detail = new MultiLineTextWidget(choice.detail().copy().withStyle(ChatFormatting.GRAY), ModelScreen.this.font);
            this.detail.setMaxRows(2);
         }

         public Component getNarration() {
            return Component.translatable("narrator.select", this.choice.name());
         }

         public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
            if (this.choice.path() == null) {
               Component status = defaultStatus();
               if (!this.detail.getMessage().getString().equals(status.getString())) {
                  this.detail.setMessage(status.copy().withStyle(ChatFormatting.GRAY));
               }
            }
            int x = this.getContentX();
            int y = this.getContentY();
            if (this.choice.path() == null) {
               graphics.blit(RenderPipelines.GUI_TEXTURED, ICON, x, y, 0.0F, 0.0F, 32, 32, 32, 32);
            } else {
               graphics.fill(x, y, x + 32, y + 32, -14671840);
               graphics.centeredText(ModelScreen.this.font, "GGUF", x + 16, y + 12, -5592406);
            }
            int textWidth = this.getContentWidth() - 36;
            this.name.setMaxWidth(textWidth);
            this.name.setPosition(x + 36, y + 1);
            this.name.extractRenderState(graphics, mouseX, mouseY, a);
            this.detail.setMaxWidth(textWidth);
            this.detail.setPosition(x + 36, y + 12);
            this.detail.extractRenderState(graphics, mouseX, mouseY, a);
         }

         public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
            ModelList.this.setSelected(this);
            ModelScreen.this.error = Component.empty();
            if (doubleClick) ModelScreen.this.done();
            return true;
         }
      }
   }
}
