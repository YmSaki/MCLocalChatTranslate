package local.chattranslate;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.Screen;

/** Access to the separate GUI and HUD introduced in Minecraft 26.2. */
final class ClientUi {
   private ClientUi() { }

   static Screen screen(Minecraft client) {
      return client.gui.screen();
   }

   static void setScreen(Minecraft client, Screen screen) {
      client.gui.setScreen(screen);
   }

   static ChatComponent chat(Minecraft client) {
      return client.gui.hud.getChat();
   }
}
