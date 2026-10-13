package local.chattranslate;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import local.chattranslate.runtime.LlamaCppRuntime;

/** Runs against the packaged resources; this class is never included in the MOD. */
public final class RuntimeLifecycleSmokeTest {
   public static void main(String[] args) throws Exception {
      Path gameDir = Path.of("build/runtime-smoke").toAbsolutePath();
      LlamaCppRuntime runtime = new LlamaCppRuntime(gameDir);
      LlamaCppRuntime.ServerHandle first = null;
      LlamaCppRuntime.ServerHandle second = null;
      try (HttpClient http = HttpClient.newHttpClient()) {
         try {
            first = runtime.start().get(150, TimeUnit.SECONDS);
            require(runtime.isReady(), "Runtime did not become READY");
            require("127.0.0.1".equals(first.baseUri().getHost()), "Server bound to unexpected host");
            System.out.println("Runtime READY on " + first.baseUri());
            translate(http, first);

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
      System.out.println("Runtime lifecycle smoke checks passed");
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

   private static void require(boolean condition, String message) {
      if (!condition) throw new AssertionError(message);
   }
}
