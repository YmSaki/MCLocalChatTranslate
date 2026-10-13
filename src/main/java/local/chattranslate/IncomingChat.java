package local.chattranslate;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record IncomingChat(String sender, String body, boolean npc) {
   private static final Pattern COLORS = Pattern.compile("(?i)§[0-9a-fk-orx]");
   private static final Pattern NPC = Pattern.compile("^\\[NPC] (?<name>[^:\\r\\n]{1,64}): (?<body>.+)$");
   private static final Pattern HEADER = Pattern.compile(
      "^\\[(?:0|[1-9][0-9]{0,3})] +(?:(?<badge>[^\\s\\[\\]:]{1,8}) +)?(?:\\[(?:VIP\\+?|MVP\\+{0,2}|YOUTUBE|ADMIN|GM|MOD|HELPER|PIG\\+{0,3})] +)?(?<name>[A-Za-z0-9_]{1,16}): (?<body>.+)$"
   );

   public static Optional<IncomingChat> parse(String message) {
      if (message != null && message.length() <= 2048 && message.indexOf(10) < 0 && message.indexOf(13) < 0) {
         String plain = COLORS.matcher(message).replaceAll("");
         Matcher npcMatch = NPC.matcher(plain);
         if (npcMatch.matches() && !npcMatch.group("body").isBlank()) {
            return Optional.of(new IncomingChat(npcMatch.group("name"), npcMatch.group("body").strip(), true));
         }

         Matcher match = HEADER.matcher(plain);
         if (!match.matches()) {
            return Optional.empty();
         }

         String badge = match.group("badge");
         if (badge != null && badge.codePoints().anyMatch(c -> c < 128)) {
            return Optional.empty();
         }

         String body = match.group("body").strip();
         return body.isEmpty() ? Optional.empty() : Optional.of(new IncomingChat(match.group("name"), body, false));
      } else {
         return Optional.empty();
      }
   }

   public static boolean validName(String name) {
      return name != null && name.matches("[A-Za-z0-9_]{1,16}");
   }
}
