package local.chattranslate;

import java.util.List;
import java.util.Optional;

/** Checks the recovered classes before any translation backend changes. */
public final class OriginalBehaviorSmokeTest {
   public static void main(String[] args) {
      normalChat();
      npcChat();
      ignoredChat();
      placeholders();
      malformedTranslations();
      candidates();
      System.out.println("Original behavior smoke checks passed");
   }

   private static void normalChat() {
      IncomingChat chat = IncomingChat.parse("[123] [MVP+] Alex_1: Hello world!").orElseThrow();
      require("Alex_1".equals(chat.sender()), "normal chat sender");
      require("Hello world!".equals(chat.body()), "normal chat body");
      require(!chat.npc(), "normal chat type");
   }

   private static void npcChat() {
      IncomingChat chat = IncomingChat.parse("[NPC] Villager: You can find a shop.").orElseThrow();
      require("Villager".equals(chat.sender()), "NPC sender");
      require("You can find a shop.".equals(chat.body()), "NPC body");
      require(chat.npc(), "NPC type");
   }

   private static void ignoredChat() {
      require(IncomingChat.parse("[Guild] Alex: Hello").isEmpty(), "unrelated chat");
      require(IncomingChat.parse("[1] Alex: first\nsecond").isEmpty(), "multiline chat");
      require(IncomingChat.parse(null).equals(Optional.empty()), "null chat");
   }

   private static void placeholders() {
      ProtectedBody body = ProtectedBody.prepare("Alex gave Steve a sword for Alex.", List.of("Alex", "Steve"));
      require(body.names().equals(List.of("Alex", "Steve", "Alex")), "protected names and order");
      require(body.requestText().equals("ZXQ0 gave ZXQ1 a sword for ZXQ2."), "request placeholders");
      require(body.restore("ZXQ0 は ZXQ1 に ZXQ2 の剣を渡した。").equals("Alex は Steve に Alex の剣を渡した。"), "restore names");

      ProtectedBody collision = ProtectedBody.prepare("ZXQ appears with Alex.", List.of("Alex"));
      require(!collision.marker().equals("ZXQ"), "marker collision");
      require(collision.restore(collision.marker() + "0").equals("Alex"), "collision restore");
   }

   private static void malformedTranslations() {
      ProtectedBody body = ProtectedBody.prepare("Hello Alex and Steve.", List.of("Alex", "Steve"));
      expectFailure(() -> body.restore("こんにちは ZXQ0。"), "missing placeholder");
      expectFailure(() -> body.restore("ZXQ0 ZXQ0 ZXQ1"), "duplicate placeholder");
      expectFailure(() -> body.restore("ZXQ0 ZXQ1 ZXQ9"), "unknown placeholder");
      expectFailure(() -> body.restore(null), "null translation");
   }

   private static void candidates() {
      require(ProtectedBody.prepare("Hello Alex!", List.of("Alex")).hasEnglishCandidate(), "English candidate");
      require(!ProtectedBody.prepare("gg wp", List.of()).hasEnglishCandidate(), "game slang");
      require(!ProtectedBody.prepare("こんにちは", List.of()).hasEnglishCandidate(), "Japanese text");
      require(!ProtectedBody.prepare("Alex", List.of("Alex")).hasEnglishCandidate(), "name only");
   }

   private static void expectFailure(Runnable action, String label) {
      try {
         action.run();
      } catch (IllegalArgumentException expected) {
         return;
      }
      throw new AssertionError("Expected rejection: " + label);
   }

   private static void require(boolean condition, String label) {
      if (!condition) {
         throw new AssertionError(label);
      }
   }
}
