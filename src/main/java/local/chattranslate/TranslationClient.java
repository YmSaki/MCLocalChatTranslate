package local.chattranslate;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import local.chattranslate.runtime.LlamaCppRuntime;

public final class TranslationClient implements AutoCloseable {
   public static final String MODEL = "translategemma";
   private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3L)).proxy(new ProxySelector() {
      @Override
      public List<Proxy> select(URI uri) {
         return List.of(Proxy.NO_PROXY);
      }

      @Override
      public void connectFailed(URI uri, SocketAddress address, IOException error) {
      }
   }).followRedirects(Redirect.NEVER).build();
   private final ThreadPoolExecutor workers = new ThreadPoolExecutor(
      1, 1, 0L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), Thread.ofPlatform().daemon().name("localchattranslate-", 0L).factory()
   );
   private final TranslationLog log;
   private final LlamaCppRuntime runtime;

   public TranslationClient(LlamaCppRuntime runtime, TranslationLog log) {
      this.runtime = runtime;
      this.log = log;
   }

   public CompletableFuture<Optional<String>> translate(ProtectedBody body) {
      return this.translate(body, 30, null);
   }

   public CompletableFuture<Optional<String>> testConnection() {
      return this.translate(ProtectedBody.prepare("You can find a shop in the village.", List.of()), 90, null);
   }

   CompletableFuture<Optional<String>> translateTooltipLine(TooltipLine line) {
      return this.translate(line.body, 30, line);
   }

   private CompletableFuture<Optional<String>> translate(ProtectedBody body, int timeoutSeconds, TooltipLine tooltip) {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
      return CompletableFuture.<Optional<String>>supplyAsync(
            () -> {
               if (body.hasEnglishCandidate() && System.nanoTime() < deadline) {
                  long start = System.nanoTime();

                  try {
                     long remaining = deadline - System.nanoTime();
                     if (remaining <= 0) throw new IOException("Translation expired");
                     LlamaCppRuntime.ServerHandle server = this.runtime.ensureStarted().get(remaining, TimeUnit.NANOSECONDS);
                     JsonObject result = this.post(server, request(body, tooltip != null), deadline);
                     if (!result.has("model") || !MODEL.equals(result.get("model").getAsString())) {
                        throw new IOException("TranslateGemma 4Bの応答ではありません。");
                     }

                     JsonObject choice = result.getAsJsonArray("choices").get(0).getAsJsonObject();
                     if (choice.has("finish_reason") && "stop".equals(choice.get("finish_reason").getAsString())) {
                        String translated = body.restore(choice.getAsJsonObject("message").get("content").getAsString()).replaceAll("(?U)\\s+", " ").strip();
                        String readable = tooltip == null ? translated : tooltip.restore(translated).getString();
                        validateOutput(body.original(), readable);
                        if (System.nanoTime() < deadline && !translated.equals(body.original())) {
                           if (this.log != null) {
                              this.log.translated(body.original(), readable, (System.nanoTime() - start) / 1.0E9);
                           }

                           return Optional.of(translated);
                        } else {
                           return Optional.empty();
                        }
                     } else {
                        throw new IOException("途中で切れた訳文を省略しました。");
                     }
                  } catch (InterruptedException e) {
                     Thread.currentThread().interrupt();
                     throw new CompletionException(e);
                  } catch (Exception e) {
                     if (this.log != null) {
                        this.log.failed(body.original(), LocalChatTranslate.errorMessage(e));
                     }

                     throw new CompletionException(e);
                  }
               } else {
                  return Optional.empty();
               }
            },
            this.workers
         )
         .orTimeout(timeoutSeconds, TimeUnit.SECONDS);
   }

   private static JsonObject request(ProtectedBody body, boolean tooltip) {
      String prompt = "You are a professional English (en) to Japanese (ja) translator. Your goal is to accurately convey the meaning and nuances of the original English text while adhering to Japanese grammar, vocabulary, and cultural sensitivities.\n"
         + (tooltip ? "The source is a Minecraft Hypixel SkyBlock item tooltip. Use game terminology for stats and abilities.\n" : "")
         + "Produce only the Japanese translation, without any additional explanations or commentary. Preserve placeholder tokens like "
         + body.marker()
         + "0 and "
         + body.marker()
         + "1 exactly as written. Please translate the following English text into Japanese:\n\n\n"
         + body.requestText();
      JsonObject message = new JsonObject();
      message.addProperty("role", "user");
      message.addProperty("content", prompt);
      JsonArray messages = new JsonArray();
      messages.add(message);
      JsonObject payload = new JsonObject();
      payload.addProperty("model", MODEL);
      payload.addProperty("stream", false);
      payload.addProperty("temperature", 0);
      payload.addProperty("max_tokens", 192);
      payload.add("messages", messages);
      return payload;
   }

   private JsonObject post(LlamaCppRuntime.ServerHandle server, JsonObject payload, long deadline) throws Exception {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0L) {
         throw new IOException("Translation expired");
      }

      HttpRequest request = HttpRequest.newBuilder(server.baseUri().resolve("/v1/chat/completions"))
         .timeout(Duration.ofNanos(remaining))
         .header("Content-Type", "application/json; charset=utf-8")
         .header("Authorization", "Bearer " + server.apiKey())
         .POST(BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
         .build();
      CompletableFuture<HttpResponse<String>> call = this.http.sendAsync(request, BodyHandlers.limiting(BodyHandlers.ofString(StandardCharsets.UTF_8), 65536L));

      try {
         HttpResponse<String> response = call.get(remaining, TimeUnit.NANOSECONDS);
         if (response.statusCode() != 200) {
            String detail = "llama-server HTTP " + response.statusCode();

            try {
               detail = detail + ": " + JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("error").get("message").getAsString();
            } catch (RuntimeException var16) {
            }

            throw new IOException(detail);
         } else {
            return JsonParser.parseString(response.body()).getAsJsonObject();
         }
      } finally {
         if (!call.isDone()) {
            call.cancel(true);
         }
      }
   }

   public static void validateOutput(String source, String text) {
      if (text.isBlank()
         || text.length() > Math.max(160, source.length() * 5)
         || Pattern.compile("(.{1,16}?)\\1{7,}").matcher(text.replaceAll("\\s+", "")).find()) {
         throw new IllegalArgumentException("空・異常に長い・繰り返す訳文を省略しました。");
      }
   }

   public int pending() {
      return this.workers.getActiveCount() + this.workers.getQueue().size();
   }

   public void clearQueued() {
      this.workers.getQueue().clear();
   }

   @Override
   public void close() {
      this.workers.shutdownNow();
      this.http.shutdownNow();
   }
}
