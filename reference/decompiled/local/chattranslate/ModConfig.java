package local.chattranslate;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ModConfig {
   public static final String DEFAULT_ENDPOINT = "http://127.0.0.1:11434";
   public boolean enabled = true;
   public boolean translateNpc = true;
   public boolean translateTooltips = true;
   public String endpoint = "http://127.0.0.1:11434";

   public ModConfig copy() {
      ModConfig copy = new ModConfig();
      copy.enabled = this.enabled;
      copy.translateNpc = this.translateNpc;
      copy.translateTooltips = this.translateTooltips;
      copy.endpoint = this.endpoint;
      return copy;
   }

   public static ModConfig load(Path path) throws IOException {
      if (!Files.exists(path)) {
         ModConfig config = new ModConfig();
         config.save(path);
         return config;
      }

      String text = Files.readString(path);
      ModConfig config = (ModConfig)new GsonBuilder().create().fromJson(text, ModConfig.class);
      if (config != null && config.endpoint != null) {
         JsonObject json = JsonParser.parseString(text).getAsJsonObject();
         URI endpoint = TranslationClient.validateEndpoint(config.endpoint);
         if (json.has("engine") || endpoint.getPort() == 5000 || endpoint.getPort() == 5001) {
            config.endpoint = "http://127.0.0.1:11434";
            config.save(path);
         }

         TranslationClient.validateEndpoint(config.endpoint);
         return config;
      } else {
         throw new IOException("Invalid config");
      }
   }

   public void save(Path path) throws IOException {
      Files.createDirectories(path.getParent());
      Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(this) + "\n");
   }
}
