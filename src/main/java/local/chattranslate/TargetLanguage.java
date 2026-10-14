package local.chattranslate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Language the translations are written in. {@code minecraftCode} names it in the UI and config;
 * {@code code} and {@code name} are what the TranslateGemma prompt uses (see target_languages.json).
 */
public record TargetLanguage(String minecraftCode, String code, String name) {
   /** Config value that follows the game language. */
   public static final String AUTO = "auto";
   /** Target of configs written before the setting existed. */
   public static final String LEGACY = "ja_jp";
   private static final Map<String, TargetLanguage> TABLE = load();

   /** Resolves the setting against the game language; empty when no target can be used. */
   public static Optional<TargetLanguage> resolve(String setting, String gameLanguage) {
      String code = setting == null || setting.isBlank() ? LEGACY : setting;
      if (AUTO.equals(code)) code = gameLanguage;
      return code == null ? Optional.empty() : Optional.ofNullable(TABLE.get(code.toLowerCase(Locale.ROOT)));
   }

   public static boolean supported(String minecraftCode) {
      return minecraftCode != null && TABLE.containsKey(minecraftCode.toLowerCase(Locale.ROOT));
   }

   /** Minecraft language codes that can be chosen, in Minecraft code order. */
   public static Map<String, TargetLanguage> all() {
      return TABLE;
   }

   private static Map<String, TargetLanguage> load() {
      try (InputStream stream = TargetLanguage.class.getResourceAsStream("/assets/localchattranslate/target_languages.json")) {
         if (stream == null) throw new IOException("Missing target_languages.json");
         JsonObject json = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
         Map<String, TargetLanguage> table = new LinkedHashMap<>();
         for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            JsonObject value = entry.getValue().getAsJsonObject();
            table.put(entry.getKey(), new TargetLanguage(entry.getKey(), value.get("code").getAsString(), value.get("name").getAsString()));
         }
         return Collections.unmodifiableMap(table);
      } catch (IOException e) {
         throw new UncheckedIOException(e);
      }
   }
}
