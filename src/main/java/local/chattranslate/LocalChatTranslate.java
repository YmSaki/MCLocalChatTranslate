package local.chattranslate;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import local.chattranslate.runtime.LlamaCppRuntime;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.ClientStopping;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.EndTick;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.Disconnect;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.Join;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LocalChatTranslate implements ClientModInitializer {
   public static final Logger LOG = LoggerFactory.getLogger("localchattranslate");
   private static LocalChatTranslate instance;
   private TranslationClient api;
   private LlamaCppRuntime runtime;
   private TooltipTranslations tooltips;
   private final LinkedHashSet<String> seenNames = new LinkedHashSet<>();
   private ModConfig config;
   private Path configPath;
   private long generation;
   private long candidates;
   private long translated;
   private long failures;
   private long skipped;
   private long nextWarning;
   private boolean modelNoticeShown;
   private Component lastError;

   public void onInitializeClient() {
      instance = this;
      Path logPath = FabricLoader.getInstance().getGameDir().resolve("logs/localchattranslate.log");
      this.runtime = new LlamaCppRuntime(FabricLoader.getInstance().getGameDir(),
         () -> new LlamaCppRuntime.ModelChoice(this.config.modelDownloadConsent, this.config.modelPath));
      this.api = new TranslationClient(this.runtime, new TranslationLog(logPath));
      this.configPath = FabricLoader.getInstance().getConfigDir().resolve("localchattranslate.json");
      this.reload();
      this.tooltips = new TooltipTranslations(this.api, () -> this.config);
      this.startRuntime();
      ClientTickEvents.END_CLIENT_TICK.register((EndTick)client -> this.tooltips.screenChanged(client.screen));
      ClientPlayConnectionEvents.JOIN.register((Join)(handler, sender, client) -> {
         this.reset();
         if (this.config.enabled && !this.modelNoticeShown && this.runtime.state() == LlamaCppRuntime.RuntimeState.MODEL_MISSING) {
            this.modelNoticeShown = true;
            client.execute(() -> client.gui.getChat().addClientSystemMessage(Component.translatable(KEY + "chat.modelMissing")));
         }
      });
      ClientPlayConnectionEvents.DISCONNECT.register((Disconnect)(handler, client) -> {
         this.reset();
      });
      ClientLifecycleEvents.CLIENT_STOPPING.register((ClientStopping)client -> {
         this.api.clearQueued();
         this.api.close();
         this.runtime.close();
      });
      ClientCommandRegistrationCallback.EVENT
         .register(
            (ClientCommandRegistrationCallback)(dispatcher, registry) -> dispatcher.register(
               (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)ClientCommands.literal(
                                 "lct"
                              )
                              .executes(context -> this.status(((FabricClientCommandSource)context.getSource())::sendFeedback)))
                           .then(
                              ClientCommands.literal("on")
                                 .executes(context -> this.toggle(true, ((FabricClientCommandSource)context.getSource())::sendFeedback))
                           ))
                        .then(
                           ClientCommands.literal("off")
                              .executes(context -> this.toggle(false, ((FabricClientCommandSource)context.getSource())::sendFeedback))
                        ))
                     .then(ClientCommands.literal("settings").executes(context -> {
                        Minecraft.getInstance().schedule(() -> Minecraft.getInstance().setScreen(new ConfigScreen(null)));
                        return 1;
                     })))
                  .then(ClientCommands.literal("download").executes(context -> {
                     this.consentToDownload();
                     ((FabricClientCommandSource)context.getSource()).sendFeedback(Component.translatable(KEY + "chat.downloadStarted"));
                     return 1;
                  }))
                  .then(ClientCommands.literal("reload").executes(context -> {
                     this.reload();
                     this.reset();
                     return this.status(((FabricClientCommandSource)context.getSource())::sendFeedback);
                  }))
            )
         );
      LOG.info("[LCT] 準備完了 | TranslateGemma 4B | 翻訳={} | 内蔵 llama.cpp | 翻訳ログ={}", this.config.enabled, logPath);
   }

   private void startRuntime() {
      this.runtime.start().exceptionally(error -> {
         if (this.runtime.state() == LlamaCppRuntime.RuntimeState.MODEL_MISSING) {
            LOG.info("[LCT] 翻訳モデルが未ダウンロードです。同意を待っています。");
         } else {
            LOG.error("Could not start bundled TranslateGemma runtime", error);
         }
         return null;
      });
   }

   /** Saves the model chosen on the model screen and switches the runtime to it. */
   public static void selectModel(String modelPath, boolean consentToDownload) throws IOException {
      ModConfig updated = instance.config.copy();
      updated.modelPath = blankToNull(modelPath);
      boolean newConsent = consentToDownload && !updated.modelDownloadConsent;
      if (consentToDownload) updated.modelDownloadConsent = true;
      boolean modelChanged = !java.util.Objects.equals(blankToNull(instance.config.modelPath), updated.modelPath);
      saveSettings(updated);
      if (!modelChanged && (newConsent || instance.runtime.state() == LlamaCppRuntime.RuntimeState.MODEL_MISSING
         || instance.runtime.state() == LlamaCppRuntime.RuntimeState.FAILED)) {
         instance.startRuntime();
      }
   }

   /** Folder whose top-level GGUF files the model screen lists. */
   public static Path modelsDir() {
      return FabricLoader.getInstance().getGameDir().resolve("localchattranslate/models");
   }

   private static volatile local.chattranslate.runtime.ModelStore defaultStore;

   public static boolean defaultModelDownloaded() {
      try {
         if (defaultStore == null) {
            defaultStore = new local.chattranslate.runtime.ModelStore(FabricLoader.getInstance().getGameDir(),
               local.chattranslate.runtime.RuntimeExtractor.readModelSpec(), null);
         }
         return defaultStore.isPresent();
      } catch (IOException e) {
         return false;
      }
   }

   public static void restartRuntime() {
      instance.runtime.restart().exceptionally(error -> null);
   }

   private static String blankToNull(String value) {
      return value == null || value.isBlank() ? null : value;
   }

   /** Records the user's consent to fetch the default model and starts the download. */
   public static void consentToDownload() {
      if (!instance.config.modelDownloadConsent) {
         instance.config.modelDownloadConsent = true;
         try {
            instance.config.save(instance.configPath);
         } catch (IOException e) {
            LOG.warn("Could not save model download consent", e);
         }
      }
      instance.startRuntime();
   }

   private static final String KEY = "localchattranslate.";

   /** Runtime state for the settings screen and /lct, in the client's language. */
   public static Component runtimeStatus() {
      LlamaCppRuntime runtime = instance.runtime;
      return switch (runtime.state()) {
         case STARTING, EXTRACTING -> Component.translatable(KEY + "state.starting");
         case MODEL_MISSING -> Component.translatable(KEY + "state.missing");
         case DOWNLOADING -> downloadStatus();
         case LOADING_MODEL -> Component.translatable(KEY + "state.loading");
         case READY -> Component.translatable(KEY + "state.ready");
         case FAILED -> Component.translatable(KEY + "state.failed", runtime.lastFailure() == null
            ? Component.translatable(KEY + "state.failed.noDetail") : errorComponent(runtime.lastFailure()));
         case STOPPING, STOPPED -> Component.translatable(KEY + "state.stopped");
      };
   }

   public static Component downloadStatus() {
      long[] progress = instance.runtime.downloadProgress();
      long done = progress == null ? 0 : progress[0];
      long total = progress == null ? 1 : progress[1];
      return Component.translatable(KEY + "state.downloading", String.format(java.util.Locale.ROOT, "%.2f", done / 1e9),
         String.format(java.util.Locale.ROOT, "%.2f", total / 1e9), done * 100 / total);
   }

   private static Component onOff(boolean on) {
      return net.minecraft.network.chat.CommonComponents.optionStatus(on);
   }

   /** File name of the selected GGUF, or TranslateGemma 4B for the default model. */
   public static String modelName() {
      String path = instance.config.modelPath;
      return path == null || path.isBlank() ? "TranslateGemma 4B" : Path.of(path).getFileName().toString();
   }

   private void reload() {
      try {
         this.config = ModConfig.load(this.configPath);
      } catch (Exception e) {
         this.config = new ModConfig();
         this.config.enabled = false;
         LOG.error("Cannot read localchattranslate.json; translation disabled", e);
      }
   }

   private void reset() {
      this.generation++;
      this.seenNames.clear();
      this.api.clearQueued();
      this.tooltips.reset();
   }

   public static void tooltipHover(Screen screen, Slot slot) {
      if (instance != null) {
         instance.tooltips.hover(screen, slot);
      }
   }

   public static List<Component> tooltipLines(List<Component> original) {
      return instance == null ? original : instance.tooltips.render(original);
   }

   public static boolean tooltipKey(Screen screen, Slot slot, KeyEvent key) {
      return instance != null && instance.tooltips.keyPressed(screen, slot, key);
   }

   public static ModConfig settings() {
      return instance.config.copy();
   }

   public static void saveSettings(ModConfig config) throws IOException {
      config.save(instance.configPath);
      boolean modelChanged = !java.util.Objects.equals(blankToNull(instance.config.modelPath), blankToNull(config.modelPath));
      instance.config = config.copy();
      instance.reset();
      if (modelChanged) {
         instance.runtime.restart().exceptionally(error -> null);
      }
      LOG.info("[LCT] 設定変更 | TranslateGemma 4B | 翻訳={} | NPC={} | アイテム説明={}", new Object[]{config.enabled, config.translateNpc, config.translateTooltips});
   }

   public static LlamaCppRuntime.RuntimeState runtimeState() {
      return instance.runtime.state();
   }

   /** Plain text of {@link #errorComponent} for the translation log file. */
   public static String errorMessage(Throwable error) {
      return errorComponent(error).getString();
   }

   public static Component errorComponent(Throwable error) {
      while (error.getCause() != null) {
         error = error.getCause();
      }

      if (error instanceof local.chattranslate.runtime.Localized localized) {
         return Component.translatable(localized.translationKey(), localized.translationArgs());
      }
      if (error instanceof ConnectException || error instanceof ClosedChannelException) {
         return Component.translatable(KEY + "error.connect");
      }
      if (error instanceof TimeoutException || error instanceof HttpTimeoutException) {
         return Component.translatable(KEY + "error.timeout");
      }
      String message = error.getMessage();
      if (message == null) {
         message = error.getClass().getSimpleName();
      }
      message = message.replaceAll("\\s+", " ");
      return Component.literal(message.substring(0, Math.min(message.length(), 180)));
   }

   public static void received(ChatBridge chat, GuiMessage message) {
      if (instance != null) {
         instance.onReceived(chat, message);
      }
   }

   private void onReceived(ChatBridge chat, GuiMessage message) {
      if (this.config.enabled && message.source() != GuiMessageSource.SYSTEM_CLIENT) {
         Optional<IncomingChat> parsed = IncomingChat.parse(message.content().getString());
         if (!parsed.isEmpty()) {
            IncomingChat publicChat = parsed.get();
            if (!publicChat.npc() || this.config.translateNpc) {
               this.candidates++;
               if (!publicChat.npc()) {
                  this.seenNames.add(publicChat.sender());
               }

               while (this.seenNames.size() > 2048) {
                  this.seenNames.removeFirst();
               }

               LinkedHashSet<String> names = new LinkedHashSet<>(this.seenNames);
               Minecraft client = Minecraft.getInstance();
               if (client.getConnection() != null) {
                  for (PlayerInfo info : client.getConnection().getOnlinePlayers()) {
                     names.add(info.getProfile().name());
                  }
               }

               if (client.getGameProfile() != null) {
                  names.add(client.getGameProfile().name());
               }

               ProtectedBody body = ProtectedBody.prepare(publicChat.body(), names);
               if (!body.hasEnglishCandidate()) {
                  this.skipped++;
               } else {
                  long requestGeneration = this.generation;

                  try {
                     this.api.translate(body).whenComplete((result, error) -> client.execute(() -> {
                        if (requestGeneration == this.generation && this.config.enabled) {
                           if (error != null) {
                              this.failures++;
                              this.lastError = errorComponent(error);
                              if (System.currentTimeMillis() >= this.nextWarning) {
                                 this.nextWarning = System.currentTimeMillis() + 60000L;
                                 LOG.warn("[LCT] TranslateGemma 4B | 原文を保持 | {}", this.lastError.getString());
                              }
                           } else if (result.isPresent() && chat.lct$attach(message, (String)result.get())) {
                              this.translated++;
                              this.lastError = null;
                           } else {
                              this.skipped++;
                           }
                        }
                     }));
                  } catch (RejectedExecutionException e) {
                     this.skipped++;
                  }
               }
            }
         }
      }
   }

   private int toggle(boolean enabled, Consumer<Component> feedback) {
      this.config.enabled = enabled;
      this.reset();
      try {
         this.config.save(this.configPath);
      } catch (Exception e) {
         LOG.warn("Could not save translation setting", e);
      }

      return this.status(feedback);
   }

   private int status(Consumer<Component> feedback) {
      feedback.accept(Component.translatable(KEY + "chat.status", modelName(), onOff(this.config.enabled),
         onOff(this.config.translateNpc), onOff(this.config.translateTooltips)));
      feedback.accept(Component.translatable(KEY + "chat.stats", this.translated, this.api.pending(), this.failures,
         this.candidates, this.skipped));
      feedback.accept(Component.translatable(KEY + "chat.engine", runtimeStatus()));
      if (this.lastError != null) {
         feedback.accept(Component.translatable(KEY + "chat.lastError", this.lastError));
      }

      return 1;
   }
}
