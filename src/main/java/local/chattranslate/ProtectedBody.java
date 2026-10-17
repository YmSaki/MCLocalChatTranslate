package local.chattranslate;

import java.lang.Character.UnicodeScript;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public record ProtectedBody(String original, String detectionText, String requestText, String marker, List<String> names) {
   private static final Pattern WORD = Pattern.compile("[A-Za-z0-9_]+");
   private static final Pattern GAME_SLANG = Pattern.compile("(?i)gg(?:[ ,]+wp|wp)?[!. ]*");

   public static ProtectedBody prepare(String body, Collection<String> knownNames) {
      Set<String> known = knownNames.stream().filter(IncomingChat::validName).map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
      String marker = "ZXQ";

      while (body.contains(marker)) {
         marker = marker + "X";
      }

      StringBuilder request = new StringBuilder();
      StringBuilder detection = new StringBuilder();
      List<String> protectedNames = new ArrayList<>();
      Matcher words = WORD.matcher(body);
      int last = 0;

      while (words.find()) {
         if (known.contains(words.group().toLowerCase(Locale.ROOT))) {
            request.append(body, last, words.start()).append(marker).append(protectedNames.size());
            detection.append(body, last, words.start()).append(' ');
            protectedNames.add(words.group());
            last = words.end();
         }
      }

      request.append(body.substring(last));
      detection.append(body.substring(last));
      return new ProtectedBody(body, detection.toString(), request.toString(), marker, List.copyOf(protectedNames));
   }

   public boolean hasEnglishCandidate() {
      if (GAME_SLANG.matcher(this.original.strip()).matches()) {
         return false;
      } else {
         return this.detectionText.codePoints().anyMatch(c -> {
            return switch (UnicodeScript.of(c)) {
               case HIRAGANA, KATAKANA, HAN, HANGUL -> true;
               default -> false;
            };
         }) ? false : this.detectionText.codePoints().anyMatch(c -> c >= 65 && c <= 90 || c >= 97 && c <= 122);
      }
   }

   /**
    * {@link #hasEnglishCandidate()} that also leaves alone text containing letters of the target's script,
    * for example Cyrillic when translating into Russian. A single such letter is a symbol, like the ф in Hypixel's
    * "Rift Time: +10s ф", so it takes two. Null adds nothing, as for Latin-script targets.
    */
   public boolean hasEnglishCandidate(UnicodeScript targetScript) {
      return this.hasEnglishCandidate() && (targetScript == null
         || this.detectionText.codePoints().filter(c -> Character.isLetter(c) && UnicodeScript.of(c) == targetScript).count() < 2);
   }

   public String restore(String response) {
      if (response != null && response.length() <= 16384) {
         if (this.names.isEmpty()) {
            return response.strip();
         }

         String restored = response;

         for (int i = this.names.size() - 1; i >= 0; i--) {
            Pattern token = Pattern.compile(Pattern.quote(this.marker + i) + "(?![0-9])");
            if (token.matcher(restored).results().count() != 1L) {
               throw new local.chattranslate.runtime.Localized.Rejection("error.nameLost");
            }

            restored = token.matcher(restored).replaceAll(Matcher.quoteReplacement(this.names.get(i)));
         }

         if (restored.contains(this.marker)) {
            throw new local.chattranslate.runtime.Localized.Rejection("error.placeholder");
         } else {
            return restored.strip();
         }
      } else {
         throw new IllegalArgumentException("Invalid translation");
      }
   }
}
