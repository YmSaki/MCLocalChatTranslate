package local.chattranslate;

import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

final class GameTestCompat {
    private GameTestCompat() {}

    static void waitForChunksRender(TestSingleplayerContext world) {
        world.getConnection().waitForChunksRender();
    }
}
