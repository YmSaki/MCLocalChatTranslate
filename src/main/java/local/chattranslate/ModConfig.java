package local.chattranslate;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ModConfig {
   public boolean enabled = true;
   public boolean translateNpc = true;
   public boolean translateTooltips = true;
   /** Set once the user agrees to download the default model from Hugging Face. */
   public boolean modelDownloadConsent = false;
   /** Absolute path to a user-supplied GGUF; null uses the default model. */
   public String modelPath = null;
   /**
    * Minecraft language code of the translations, or {@link TargetLanguage#AUTO} to follow the game language.
    * Null only in configs written before the setting existed; {@link #load} turns that into Japanese.
    */
   public String targetLanguage = null;

   public ModConfig copy() {
      ModConfig copy = new ModConfig();
      copy.enabled = this.enabled;
      copy.translateNpc = this.translateNpc;
      copy.translateTooltips = this.translateTooltips;
      copy.modelDownloadConsent = this.modelDownloadConsent;
      copy.modelPath = this.modelPath;
      copy.targetLanguage = this.targetLanguage;
      return copy;
   }

   public static ModConfig load(Path path) throws IOException {
      if (!Files.exists(path)) {
         ModConfig config = new ModConfig();
         config.targetLanguage = TargetLanguage.AUTO;
         config.save(path);
         return config;
      }

      ModConfig config = new GsonBuilder().create().fromJson(Files.readString(path), ModConfig.class);
      if (config == null) throw new IOException("Invalid config");
      // Earlier versions always translated into Japanese.
      if (config.targetLanguage == null || config.targetLanguage.isBlank()) config.targetLanguage = TargetLanguage.LEGACY;
      return config;
   }

   public void save(Path path) throws IOException {
      Files.createDirectories(path.getParent());
      Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(this) + "\n");
   }
}
