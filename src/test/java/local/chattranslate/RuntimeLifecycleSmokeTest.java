package local.chattranslate;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import local.chattranslate.runtime.LlamaCppRuntime;
import local.chattranslate.runtime.ModelStore;

/** Runs against the packaged resources; this class is never included in the MOD. */
public final class RuntimeLifecycleSmokeTest {
   public static void main(String[] args) throws Exception {
      checkMissingModel();
      checkMissingCustomModel();
      Path gameDir = Path.of("build/runtime-smoke").toAbsolutePath();
      // The seeded default model is found without consent, so no download is attempted.
      LlamaCppRuntime runtime = new LlamaCppRuntime(gameDir, () -> new LlamaCppRuntime.ModelChoice(false, null));
      LlamaCppRuntime.ServerHandle first = null;
      LlamaCppRuntime.ServerHandle second = null;
      try (HttpClient http = HttpClient.newHttpClient()) {
         try {
            first = runtime.start().get(150, TimeUnit.SECONDS);
            require(runtime.isReady(), "Runtime did not become READY");
            require("127.0.0.1".equals(first.baseUri().getHost()), "Server bound to unexpected host");
            System.out.println("Runtime READY on " + first.baseUri());
            translate(http, first);
            translateInto(http, first, "de_de", Character.UnicodeScript.LATIN);
            translateInto(http, first, "ko_kr", Character.UnicodeScript.HANGUL);

            first.process().destroyForcibly();
            first.process().waitFor(10, TimeUnit.SECONDS);
            second = runtime.ensureStarted().get(150, TimeUnit.SECONDS);
            require(second.process().isAlive() && second.process().pid() != first.process().pid(),
               "Runtime did not restart after child death");
            System.out.println("Runtime restarted after child death: " + second.process().pid());
            translate(http, second);
         } finally {
            runtime.close();
         }
      }
      require(!first.process().isAlive(), "First process survived shutdown");
      require(!second.process().isAlive(), "Second process survived shutdown");
      checkCustomModel(gameDir);
      System.out.println("Runtime lifecycle smoke checks passed");
   }

   /** Without consent and without a model the runtime must stop at MODEL_MISSING and never contact the network. */
   private static void checkMissingModel() throws Exception {
      Path gameDir = Path.of("build/runtime-smoke-missing").toAbsolutePath();
      LlamaCppRuntime runtime = new LlamaCppRuntime(gameDir, () -> new LlamaCppRuntime.ModelChoice(false, null));
      try {
         Throwable error = failure(runtime.start(), 150);
         require(error instanceof ModelStore.ModelMissingException, "Expected ModelMissingException, got " + error);
         require(runtime.state() == LlamaCppRuntime.RuntimeState.MODEL_MISSING, "State was " + runtime.state());
         long start = System.nanoTime();
         Throwable fast = failure(runtime.ensureStarted(), 5);
         require(fast instanceof ModelStore.ModelMissingException, "Translation request did not fail with ModelMissingException");
         require(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2), "Translation request did not fail fast");
         require(runtime.downloadProgress() == null, "A download started without consent");
         require(!Files.exists(gameDir.resolve("localchattranslate/models")), "Model store written without consent");
         System.out.println("Missing model stops at MODEL_MISSING without network access");
      } finally {
         runtime.close();
      }
   }

   private static void checkMissingCustomModel() throws Exception {
      Path gameDir = Path.of("build/runtime-smoke-missing").toAbsolutePath();
      String absent = gameDir.resolve("no-such-model.gguf").toString();
      LlamaCppRuntime runtime = new LlamaCppRuntime(gameDir, () -> new LlamaCppRuntime.ModelChoice(true, absent));
      try {
         Throwable error = failure(runtime.start(), 150);
         require(runtime.state() == LlamaCppRuntime.RuntimeState.FAILED, "State was " + runtime.state());
         require(error.getMessage().contains("no-such-model.gguf"), "Error did not name the missing GGUF: " + error);
         require(runtime.downloadProgress() == null, "Custom model path fell back to a download");
         System.out.println("Missing custom GGUF fails without downloading");
      } finally {
         runtime.close();
      }
   }

   /** A user-supplied GGUF path is loaded directly, outside the default model store. */
   private static void checkCustomModel(Path seededGameDir) throws Exception {
      Path seeded;
      try (var files = Files.walk(seededGameDir.resolve("localchattranslate/models"))) {
         seeded = files.filter(f -> f.toString().endsWith(".gguf")).findFirst().orElseThrow();
      }
      Path gameDir = Path.of("build/runtime-smoke-custom").toAbsolutePath();
      LlamaCppRuntime runtime = new LlamaCppRuntime(gameDir, () -> new LlamaCppRuntime.ModelChoice(false, seeded.toString()));
      try (HttpClient http = HttpClient.newHttpClient()) {
         LlamaCppRuntime.ServerHandle server = runtime.start().get(150, TimeUnit.SECONDS);
         translate(http, server);
         require(!Files.exists(gameDir.resolve("localchattranslate/models")), "Custom model path touched the default model store");
         System.out.println("Custom GGUF path loaded");
      } finally {
         runtime.close();
      }
   }

   private static Throwable failure(java.util.concurrent.CompletableFuture<?> future, int seconds) throws Exception {
      try {
         future.get(seconds, TimeUnit.SECONDS);
      } catch (ExecutionException e) {
         return e.getCause();
      }
      throw new AssertionError("Expected failure but runtime started");
   }

   private static void translate(HttpClient http, LlamaCppRuntime.ServerHandle server) throws Exception {
      String prompt = "You are a professional English (en) to Japanese (ja) translator. Your goal is to accurately convey the meaning and nuances of the original English text while adhering to Japanese grammar, vocabulary, and cultural sensitivities.\n"
         + "Produce only the Japanese translation, without any additional explanations or commentary. Preserve placeholder tokens like __LCT_0 and __LCT_1 exactly as written. Please translate the following English text into Japanese:\n\n\n"
         + "You can find a shop in the village.";
      JsonObject message = new JsonObject();
      message.addProperty("role", "user");
      message.addProperty("content", prompt);
      JsonArray messages = new JsonArray();
      messages.add(message);
      JsonObject body = new JsonObject();
      body.addProperty("model", "translategemma");
      body.addProperty("stream", false);
      body.addProperty("temperature", 0);
      body.addProperty("max_tokens", 192);
      body.add("messages", messages);
      URI endpoint = server.baseUri().resolve("/v1/chat/completions");
      HttpRequest noKey = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(15))
         .header("Content-Type", "application/json")
         .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
      require(http.send(noKey, HttpResponse.BodyHandlers.discarding()).statusCode() == 401,
         "API did not reject a request without the session key");
      HttpRequest authorized = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(60))
         .header("Content-Type", "application/json")
         .header("Authorization", "Bearer " + server.apiKey())
         .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
      HttpResponse<String> response = http.send(authorized, HttpResponse.BodyHandlers.ofString());
      require(response.statusCode() == 200, "Translation HTTP status " + response.statusCode() + ": " + response.body());
      JsonObject choice = JsonParser.parseString(response.body()).getAsJsonObject()
         .getAsJsonArray("choices").get(0).getAsJsonObject();
      String translation = choice.getAsJsonObject("message").get("content").getAsString();
      require("stop".equals(choice.get("finish_reason").getAsString()), "Incomplete translation");
      require(!translation.isBlank() && !translation.equals("You can find a shop in the village."), "No translation produced");
      System.out.println("Translation: " + translation);
   }

   /** Sends the MOD's own prompt for another target and checks the reply is written in that language's script. */
   private static void translateInto(HttpClient http, LlamaCppRuntime.ServerHandle server, String language,
                                     Character.UnicodeScript script) throws Exception {
      String source = "You can find a shop in the village.";
      TargetLanguage target = TargetLanguage.resolve(language, null).orElseThrow();
      JsonObject message = new JsonObject();
      message.addProperty("role", "user");
      message.addProperty("content", TranslationClient.prompt(ProtectedBody.prepare(source, java.util.List.of()), target, false));
      JsonArray messages = new JsonArray();
      messages.add(message);
      JsonObject body = new JsonObject();
      body.addProperty("model", "translategemma");
      body.addProperty("stream", false);
      body.addProperty("temperature", 0);
      body.addProperty("max_tokens", 192);
      body.add("messages", messages);
      HttpRequest request = HttpRequest.newBuilder(server.baseUri().resolve("/v1/chat/completions")).timeout(Duration.ofSeconds(60))
         .header("Content-Type", "application/json")
         .header("Authorization", "Bearer " + server.apiKey())
         .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      require(response.statusCode() == 200, "Translation HTTP status " + response.statusCode() + ": " + response.body());
      String translation = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("choices").get(0)
         .getAsJsonObject().getAsJsonObject("message").get("content").getAsString().strip();
      long letters = translation.codePoints().filter(Character::isLetter).count();
      long inScript = translation.codePoints().filter(c -> Character.isLetter(c) && Character.UnicodeScript.of(c) == script).count();
      require(letters > 0 && inScript * 2 > letters && !translation.equals(source),
         "Translation into " + target.code() + " is not in " + script + ": " + translation);
      System.out.println("Translation into " + target.code() + ": " + translation);
   }

   private static void require(boolean condition, String message) {
      if (!condition) throw new AssertionError(message);
   }
}
