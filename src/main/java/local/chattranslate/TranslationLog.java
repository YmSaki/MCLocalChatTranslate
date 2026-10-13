package local.chattranslate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class TranslationLog {
   private static final long MAX_BYTES = 2000000L;
   private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
   private final Path path;
   private boolean warned;

   public TranslationLog(Path path) {
      this.path = path;
   }

   public void translated(String source, String translation, double seconds) {
      this.append(String.format(Locale.ROOT, "TranslateGemma 4B | %.2f秒%n  原文: %s%n  訳文: %s%n%n", seconds, oneLine(source), oneLine(translation)));
   }

   public void failed(String source, String reason) {
      this.append("翻訳を省略 | " + oneLine(reason) + "\n  原文: " + oneLine(source) + "\n\n");
   }

   private synchronized void append(String entry) {
      try {
         Files.createDirectories(this.path.getParent());
         if (Files.exists(this.path) && Files.size(this.path) >= 2000000L) {
            Path previous = this.path.resolveSibling(this.path.getFileName() + ".1");
            if (Files.exists(previous)) {
               Files.move(previous, this.path.resolveSibling(this.path.getFileName() + ".2"), StandardCopyOption.REPLACE_EXISTING);
            }

            Files.move(this.path, previous, StandardCopyOption.REPLACE_EXISTING);
         }

         Files.writeString(this.path, TIMESTAMP.format(LocalDateTime.now()) + " | " + entry, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      } catch (IOException e) {
         if (!this.warned) {
            this.warned = true;
            LocalChatTranslate.LOG.warn("[LCT] 翻訳ログに書き込めません: {}", this.path, e);
         }
      }
   }

   private static String oneLine(String text) {
      return text.replaceAll("[\\p{Cc}\\p{Zl}\\p{Zp}]+", " ");
   }
}
