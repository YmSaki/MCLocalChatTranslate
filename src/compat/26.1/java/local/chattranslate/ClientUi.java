package local.chattranslate;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.Screen;

/** Access to the client UI before the GUI/HUD split in Minecraft 26.2. */
final class ClientUi {
   private ClientUi() { }

   static Screen screen(Minecraft client) {
      return client.screen;
   }

   static void setScreen(Minecraft client, Screen screen) {
      client.setScreen(screen);
   }

   static ChatComponent chat(Minecraft client) {
      return client.gui.getChat();
   }
}
