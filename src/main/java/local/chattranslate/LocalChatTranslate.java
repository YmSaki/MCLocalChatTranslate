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
   private String lastError = "なし";

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
            client.execute(() -> client.gui.getChat().addClientSystemMessage(Component.literal(
               "[LCT] 翻訳モデル（約 2.5 GB）が未ダウンロードです。/lct download か設定画面から、Hugging Face からの取得を開始できます。")));
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
                     ((FabricClientCommandSource)context.getSource()).sendFeedback(Component.literal(
                        "[LCT] 翻訳モデルを Hugging Face から取得します（約 2.5 GB）。進み具合は /lct で確認できます。"));
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

   public static void restartRuntime() {
      instance.startRuntime();
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

   /** Human-readable runtime state for the settings screen and /lct. */
   public static String runtimeStatus() {
      LlamaCppRuntime runtime = instance.runtime;
      return switch (runtime.state()) {
         case STARTING, EXTRACTING -> "準備中";
         case MODEL_MISSING -> "モデル未ダウンロード";
         case DOWNLOADING -> {
            long[] progress = runtime.downloadProgress();
            yield progress == null ? "モデルをダウンロード中"
               : String.format("モデルをダウンロード中 %.2f / %.2f GB (%d%%)", progress[0] / 1e9, progress[1] / 1e9,
                  progress[0] * 100 / progress[1]);
         }
         case LOADING_MODEL -> "モデルを読み込み中";
         case READY -> "準備完了";
         case FAILED -> "停止中（" + (runtime.lastError() == null ? "ログを確認してください" : runtime.lastError()) + "）";
         case STOPPING, STOPPED -> "停止";
      };
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
      instance.config = config.copy();
      instance.reset();
      LOG.info("[LCT] 設定変更 | TranslateGemma 4B | 翻訳={} | NPC={} | アイテム説明={}", new Object[]{config.enabled, config.translateNpc, config.translateTooltips});
   }

   public static LlamaCppRuntime.RuntimeState runtimeState() {
      return instance.runtime.state();
   }

   public static String errorMessage(Throwable error) {
      while (error.getCause() != null) {
         error = error.getCause();
      }

      if (error instanceof ConnectException || error instanceof ClosedChannelException) {
         return "内蔵翻訳サーバーに接続できません。ログを確認してください。";
      }

      if (!(error instanceof TimeoutException) && !(error instanceof HttpTimeoutException)) {
         String message = error.getMessage();
         if (message == null) {
            message = error.getClass().getSimpleName();
         }

         message = message.replaceAll("[\\r\\n]+", " ");
         return message.substring(0, Math.min(message.length(), 180));
      } else {
         return "翻訳がタイムアウトしました。初回のモデル読み込み中の場合もあります。";
      }
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
                              this.lastError = errorMessage(error);
                              if (System.currentTimeMillis() >= this.nextWarning) {
                                 this.nextWarning = System.currentTimeMillis() + 60000L;
                                 LOG.warn("[LCT] TranslateGemma 4B | 原文を保持 | {}", this.lastError);
                              }
                           } else if (result.isPresent() && chat.lct$attach(message, (String)result.get())) {
                              this.translated++;
                              this.lastError = "なし";
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
      feedback.accept(
         Component.literal(
            "[LCT] TranslateGemma 4B | 英語→日本語 "
               + (this.config.enabled ? "ON" : "OFF")
               + " | NPC "
               + (this.config.translateNpc ? "ON" : "OFF")
               + " | アイテム説明 "
               + (this.config.translateTooltips ? "ON" : "OFF")
         )
      );
      feedback.accept(
         Component.literal(
            "  チャット翻訳 "
               + this.translated
               + "件 / 待機（共通） "
               + this.api.pending()
               + "件 / 失敗 "
               + this.failures
               + "件 / 対象 "
               + this.candidates
               + "件 / 省略 "
               + this.skipped
               + "件"
         )
      );
      feedback.accept(Component.literal("  翻訳エンジン: " + runtimeStatus()));
      if (!this.lastError.equals("なし")) {
         feedback.accept(Component.literal("  最後のエラー: " + this.lastError));
      }

      return 1;
   }
}
