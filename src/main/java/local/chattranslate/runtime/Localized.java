package local.chattranslate.runtime;

import java.io.IOException;
import java.util.Arrays;

/** An error whose user-facing text comes from a translation key, so code outside Minecraft can report it. */
public interface Localized {
   String PREFIX = "localchattranslate.";

   String translationKey();

   Object[] translationArgs();

   private static String describe(String key, Object[] args) {
      return args.length == 0 ? key : key + " " + Arrays.toString(args);
   }

   final class Failure extends IOException implements Localized {
      private final String key;
      private final Object[] args;

      public Failure(String key, Object... args) {
         super(describe(PREFIX + key, args));
         this.key = PREFIX + key;
         this.args = args;
      }

      public String translationKey() { return key; }
      public Object[] translationArgs() { return args; }
   }

   final class Rejection extends IllegalArgumentException implements Localized {
      private final String key;
      private final Object[] args;

      public Rejection(String key, Object... args) {
         super(describe(PREFIX + key, args));
         this.key = PREFIX + key;
         this.args = args;
      }

      public String translationKey() { return key; }
      public Object[] translationArgs() { return args; }
   }
}
