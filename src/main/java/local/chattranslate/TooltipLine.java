package local.chattranslate;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.StringDecomposer;

final class TooltipLine {
   private static final Pattern NUMBER = Pattern.compile("[+-]?[0-9][0-9,]*(?:\\.[0-9]+)?(?:%|[kKmMbB])?");
   private static final Pattern PROTECTED = Pattern.compile(NUMBER.pattern() + "|[\\p{So}\\p{Sk}\\p{Sm}\\p{Co}]");
   final Component original;
   final ProtectedBody body;
   private final Style proseStyle;
   private final List<Component> protectedParts;

   TooltipLine(Component original) {
      this.original = original.copy();
      StringBuilder plain = new StringBuilder();
      List<Style> styles = new ArrayList<>();
      StringDecomposer.iterateFormatted(original, Style.EMPTY, (index, style, codePoint) -> {
         plain.appendCodePoint(codePoint);

         for (int i = 0; i < Character.charCount(codePoint); i++) {
            styles.add(style);
         }

         return true;
      });
      Style firstTextStyle = styles.isEmpty() ? Style.EMPTY : styles.getFirst();

      for (int i = 0; i < plain.length(); i++) {
         if (plain.charAt(i) >= 'A' && plain.charAt(i) <= 'Z' || plain.charAt(i) >= 'a' && plain.charAt(i) <= 'z') {
            firstTextStyle = styles.get(i);
            break;
         }
      }

      this.proseStyle = firstTextStyle;
      String text = plain.toString();
      String marker = "ZXQ";

      while (text.contains(marker)) {
         marker = marker + "X";
      }

      StringBuilder request = new StringBuilder();
      List<Component> parts = new ArrayList<>();
      Matcher matches = PROTECTED.matcher(text);

      int last;
      for (last = 0; matches.find(); last = matches.end()) {
         request.append(text, last, matches.start()).append(marker).append(parts.size());
         MutableComponent part = Component.empty();
         int start = matches.start();

         while (start < matches.end()) {
            int end = start + 1;

            while (end < matches.end() && styles.get(end).equals(styles.get(start))) {
               end++;
            }

            part.append(Component.literal(text.substring(start, end)).setStyle(styles.get(start)));
            start = end;
         }

         parts.add(part);
      }

      request.append(text.substring(last));
      this.protectedParts = List.copyOf(parts);
      this.body = new ProtectedBody(text, text, request.toString(), marker, List.of());
   }

   boolean needsTranslation() {
      return this.body.original().length() <= 512 && this.body.hasEnglishCandidate();
   }

   Component restore(String translated) {
      Matcher tokens = Pattern.compile(Pattern.quote(this.body.marker()) + "([0-9]+)").matcher(translated);
      boolean[] seen = new boolean[this.protectedParts.size()];
      MutableComponent result = Component.empty();
      StringBuilder prose = new StringBuilder();

      int last;
      for (last = 0; tokens.find(); last = tokens.end()) {
         int index;
         try {
            index = Integer.parseInt(tokens.group(1));
         } catch (NumberFormatException e) {
            throw invalidNumbers();
         }

         if (index >= seen.length || seen[index] || !Integer.toString(index).equals(tokens.group(1))) {
            throw invalidNumbers();
         }

         seen[index] = true;
         String segment = translated.substring(last, tokens.start());
         result.append(Component.literal(segment).setStyle(this.proseStyle));
         prose.append(segment);
         result.append(this.protectedParts.get(index).copy());
      }

      String tail = translated.substring(last);
      result.append(Component.literal(tail).setStyle(this.proseStyle));
      prose.append(tail);

      for (boolean present : seen) {
         if (!present) {
            throw invalidNumbers();
         }
      }

      if (prose.indexOf(this.body.marker()) < 0 && !NUMBER.matcher(prose).find()) {
         return result;
      } else {
         throw invalidNumbers();
      }
   }

   private static IllegalArgumentException invalidNumbers() {
      return new local.chattranslate.runtime.Localized.Rejection("tooltip.symbolsLost");
   }
}
