package local.chattranslate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Checks target language resolution, the config migration and the prompt; this class is never included in the MOD. */
public final class TargetLanguageSmokeTest {
   public static void main(String[] args) throws Exception {
      resolution();
      table();
      japanesePromptUnchanged();
      otherTargetPrompt();
      configMigration();
      System.out.println("Target language smoke checks passed");
   }

   private static void resolution() {
      require(code("auto", "ja_jp").equals(Optional.of("ja")), "auto follows Japanese game language");
      require(code("auto", "de_de").equals(Optional.of("de")), "auto follows German game language");
      require(code("auto", "en_us").isEmpty(), "English game language is not a target");
      require(code("auto", "en_gb").isEmpty(), "British English game language is not a target");
      require(code("auto", "lol_us").isEmpty(), "joke language is not a target");
      require(code("auto", null).isEmpty(), "unknown game language");
      require(code("ko_kr", "en_us").equals(Optional.of("ko")), "explicit target ignores game language");
      require(code("pt_br", "ja_jp").equals(Optional.of("pt-BR")), "Brazilian Portuguese keeps its region");
      require(code("zh_tw", "en_us").equals(Optional.of("zh-TW")), "Traditional Chinese (Taiwan)");
      require(code("zh_cn", "en_us").equals(Optional.of("zh-Hans")), "Simplified Chinese");
      require(code(null, "en_us").equals(Optional.of("ja")), "missing setting is the legacy Japanese target");
      require(code("en_us", "ja_jp").isEmpty(), "English cannot be chosen");
      require(code("xx_yy", "ja_jp").isEmpty(), "unknown explicit target");
   }

   private static void table() {
      require(TargetLanguage.all().size() > 50, "target table too small: " + TargetLanguage.all().size());
      require(TargetLanguage.all().values().stream().noneMatch(t -> t.name().equals("English")), "English listed as a target");
      require(TargetLanguage.supported("JA_JP"), "codes are case-insensitive");
   }

   /** Japanese is what every earlier version asked for; its prompt must stay byte for byte the same as 0.4.0. */
   private static void japanesePromptUnchanged() {
      TargetLanguage japanese = TargetLanguage.resolve("ja_jp", null).orElseThrow();
      ProtectedBody body = ProtectedBody.prepare("Alex found a shop.", List.of("Alex"));
      String original = "You are a professional English (en) to Japanese (ja) translator. Your goal is to accurately convey the meaning and nuances of the original English text while adhering to Japanese grammar, vocabulary, and cultural sensitivities.\n"
         + "Produce only the Japanese translation, without any additional explanations or commentary. Preserve placeholder tokens like ZXQ0 and ZXQ1 exactly as written. Please translate the following English text into Japanese:\n\n\n"
         + "ZXQ0 found a shop.";
      require(TranslationClient.prompt(body, japanese, false).equals(original), "Japanese chat prompt changed");
      String tooltip = original.replace("sensitivities.\n", "sensitivities.\nThe source is a Minecraft Hypixel SkyBlock item tooltip. Use game terminology for stats and abilities.\n");
      require(TranslationClient.prompt(body, japanese, true).equals(tooltip), "Japanese tooltip prompt changed");
   }

   private static void otherTargetPrompt() {
      TargetLanguage german = TargetLanguage.resolve("de_de", null).orElseThrow();
      String prompt = TranslationClient.prompt(ProtectedBody.prepare("Hello.", List.of()), german, false);
      require(prompt.startsWith("You are a professional English (en) to German (de) translator."), "German prompt header");
      require(prompt.contains("adhering to German grammar") && prompt.contains("Produce only the German translation")
         && prompt.contains("text into German:\n\n\nHello."), "German prompt body");
      require(!prompt.contains("Japanese"), "German prompt still mentions Japanese");
   }

   private static void configMigration() throws Exception {
      Path dir = Files.createTempDirectory("lct-config");
      try {
         Path fresh = dir.resolve("fresh.json");
         require(TargetLanguage.AUTO.equals(ModConfig.load(fresh).targetLanguage), "new config follows the game language");
         require(TargetLanguage.AUTO.equals(ModConfig.load(fresh).targetLanguage), "new config keeps auto after reload");

         Path legacy = dir.resolve("legacy.json");
         Files.writeString(legacy, "{\"enabled\": true, \"translateNpc\": false, \"endpoint\": \"http://127.0.0.1:11434\"}");
         ModConfig loaded = ModConfig.load(legacy);
         require(TargetLanguage.LEGACY.equals(loaded.targetLanguage), "config without the setting keeps Japanese");
         require(!loaded.translateNpc, "legacy fields still read");

         loaded.targetLanguage = "ko_kr";
         loaded.save(legacy);
         require("ko_kr".equals(ModConfig.load(legacy).targetLanguage), "chosen target saved");
         require("ko_kr".equals(loaded.copy().targetLanguage), "copy keeps the target");
      } finally {
         try (var files = Files.walk(dir)) {
            for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
         }
      }
   }

   private static Optional<String> code(String setting, String gameLanguage) {
      return TargetLanguage.resolve(setting, gameLanguage).map(TargetLanguage::code);
   }

   private static void require(boolean condition, String label) {
      if (!condition) {
         throw new AssertionError(label);
      }
   }
}
