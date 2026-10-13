# Reconstruction log

## Source artifact

- File: `reference/original/localchattranslate-0.4.0+26.1.2.jar`
- SHA-256: `4DC1BE6BABC2381E99391ADB73B7C8487C1DDEFEBD65F9832A9E2C59C1BA783C`
- The original distribution JAR at the parent directory was copied, not modified.

## Recovery

- Extracted the original archive to `reference/extracted/`.
- Decompiled the original archive with Vineflower 1.12.0 into `reference/decompiled/`.
- Copied the 14 recovered Java source files to `src/main/java/`.
- Copied `fabric.mod.json`, `localchattranslate.mixins.json`, the icon, and both language files directly from the original archive to `src/main/resources/`.
- Saved verbose `javap` output for `TranslationClient`, `LocalChatTranslate`, `ConfigScreen`, `ProtectedBody`, and both mixins under `reference/javap/` for bytecode and annotation cross-checks.
- Used Temurin JDK 25.0.4.1+1 (verified SHA-256 `00c847d804f4a78e9f04f2683faf14fed898535b177b7fc704486cb0284e9283`) and Gradle 9.8.0 (verified SHA-256 `BAFD5CE9CFAEA0FBCCFDC8439A1AC42FBD4CD9C89DC9A988228D8A2639A58E6C`) to build. The Gradle wrapper pins 9.8.0.

## Rebuild status

The recovered sources still contain the original Ollama behavior. The first Minecraft behavior gate passed before any llama.cpp changes.

- `ContainerTooltipMixin`: restored the `(Object)` intermediary cast before `(Screen)` in two callbacks. The original bytecode contains `checkcast Screen`, and this is necessary because Java compilation cannot otherwise cast the mixin class to its target superclass.

## First gate: original behavior

- `gradle clean build`: passed; artifact `build/libs/localchattranslate-0.4.0+26.1.2.jar`.
- `gradle runClient`: launched Minecraft 26.1.2 with Fabric Loader 0.19.5, Fabric API 0.155.3+26.1.2, LocalChatTranslate 0.4.0+26.1.2, and Mixin Extras 0.5.5. The original `[LCT] 準備完了` message appeared. Resource loading and graphics initialization completed. The game later logged `Stopping!` and the Gradle run completed successfully.
- `run/logs/debug.log` records `ContainerTooltipMixin` mixing into `AbstractContainerScreen` and `ChatComponentMixin` mixing into `ChatComponent`; no mixin application failure was observed.
- The development client used an unauthenticated test player; Realms/user-property authentication errors in the log did not stop the game.
- A later interactive launch failed in native `glfw.dll` during `RenderSystem.pollEvents()` (`run/hs_err_pid70412.log`). This does not establish a MOD logic failure, but the corresponding GUI checks cannot count as passed.
- `gradle build` runs `verifyOriginalBehavior`, which passed tests for normal/NPC/unrelated chat, name placeholder protection and restoration, malformed model output rejection, and translation candidate detection. The test code is separate from the MOD sources.
- `gradle runClientGameTest --offline`: passed in a separate Fabric client game test source set, which is absent from the distribution JAR. The test opened `ConfigScreen`, loaded a singleplayer world, found `/lct` in the active client command dispatcher, received `[1] TestPlayer: Hello world.`, and kept the original message after the expected Ollama connection failure.
- The test placed a diamond sword in the inventory, hovered its slot, and rendered its actual tooltip without crashing. The screenshot at `reference/evidence/original-tooltip.png` shows the original `[G] 説明を日本語に翻訳` line. The game closed the world and logged `Stopping!`; Gradle exited successfully. **First gate passed.**
- This automated game test does not establish a successful translation response, because the first gate explicitly permits Ollama to be absent. It does verify the original chat and tooltip hooks on a running Minecraft client.

## Ollama dependency inventory (before migration)

- `TranslationClient`: validates a configurable localhost endpoint, sends `POST /api/chat` with model `translategemma:4b`, `stream=false`, `temperature=0`, `num_ctx=2048`, `num_predict=192`, and `keep_alive=15m`; parses `message.content`; uses `POST /api/generate` with `keep_alive=0` for GPU release.
- `ModConfig`: persists `endpoint` (default `http://127.0.0.1:11434`) and migrates old `engine` and ports 5000/5001.
- `ConfigScreen`: displays Ollama URL input, connection test, and Ollama-specific help/status.
- `LocalChatTranslate`: passes the endpoint for chat and connection tests; triggers GPU release on disconnect, reload, disable, settings changes, and shutdown; reports Ollama-specific errors.
- `TooltipTranslations`: passes the endpoint when translating item description lines.

## Bundled runtime and model

- Runtime: official [llama.cpp release b10964](https://github.com/ggml-org/llama.cpp/releases/tag/b10964), Windows x64 Vulkan archive. Archive SHA-256: `1EE3AD952F4BA71F438BD6D7BEBEF19E1C7AF04ADCAA35D08B4DDABB27D4C642`. The bundle includes `llama-server.exe`, all 30 DLLs from the archive, and its LLVM OpenMP license. `llama-server --version` reported `0.4.1-dev (build 10964, commit b29c606e2)`.
- Model: [bullerwins TranslateGemma 4B Q4_K_M GGUF](https://huggingface.co/bullerwins/translategemma-4b-it-GGUF), 2,489,909,312 bytes. SHA-256: `7F7357C14ABD9DA4EB200B38B05DA502CD6E10D7E1D403FBC9F78C19F3209B72`, equal to its published Hugging Face LFS object ID.
- This llama.cpp build refused the model's strict embedded TranslateGemma chat template during startup. Starting with `--no-jinja --chat-template gemma` succeeded while preserving the original MOD translation prompt. The standalone script `reference/scripts/test-standalone.ps1` verified `/health` 200 and `POST /v1/chat/completions` 200, yielding `村にはお店があります。` for `You can find a shop in the village.`
- The Gradle `generateBundleManifest` task hashes the native files and GGUF by stream, producing `bundled/manifest.json`; `processResources` packages those files and third-party licenses. `jar` uses Zip64. `RuntimeExtractor` copies each file through a `.tmp` file, checks size and SHA-256, moves it atomically, and writes `.complete` only after every file is valid. It serializes simultaneous extraction through a file lock.
- `LlamaCppRuntime` extracts on a daemon worker, starts a private `127.0.0.1` server on a random port with a random session key, waits for `/health` 200, redirects server output to `logs/localchattranslate-llama.log`, and restarts after server death. Its Windows Job Object has `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`, so closing the Minecraft process also closes the server's job handle.
- `gradle verifyRuntimeLifecycle --offline`: passed extraction, `/health`, API-key rejection, translation, restart after forced child death, and shutdown. The same command passed again after adding Windows Job Object supervision.

## Minecraft integration

- `TranslationClient` now waits for the bundled runtime and sends the unchanged prompt to `/v1/chat/completions`, with `temperature=0`, `max_tokens=192`, `stream=false`, and a Bearer key. The existing `ProtectedBody` restoration and output validation remain unchanged.
- `ModConfig` no longer writes or reads an endpoint. Gson ignores the obsolete endpoint in existing config files. `ConfigScreen` no longer offers Ollama URL or connection test and shows runtime status. Ollama GPU release requests and `/api/chat`/`/api/generate` calls were removed.
- `gradle build --offline`: passed and produced a Zip64 JAR containing `fabric.mod.json`, MOD classes, the manifest, native runtime, GGUF, and license files. `jar tf` confirmed the key entries.
- `gradle runClientGameTest --offline`: passed with the bundled runtime on Minecraft 26.1.2. The test opened the settings screen, found `/lct`, attached Japanese lines to both `[1] TestPlayer: Hello world.` and `[NPC] Villager: Welcome to the village.`, translated the hovered diamond sword's English lore, and exited cleanly. `reference/evidence/bundled-settings.png` and `reference/evidence/bundled-tooltip.png` show the UI and tooltip with original colored stats retained. The log ends with `Stopping!`.
- An early integration run emitted a `process reaper` StackOverflowError after using `Process.onExit()`. That callback was removed in favor of checking `Process.isAlive()` when querying runtime state or handling a request. Two subsequent client test runs passed without that error; the original unrelated Minecraft Anisotropic Filtering and unauthenticated Realms messages remained.

## Final checks

- A separate `runOrphanProbe` test JVM was killed with `Stop-Process -Force` after it started llama-server. The child's PID disappeared within three seconds through the Windows Job Object. Gradle's nonzero exit in this deliberately killed probe was expected. No `llama-server` process remained after other tests either.
- The extracted test GGUF was deliberately corrupted, its `.complete` marker removed, and an incomplete `.tmp` file placed beside it. The next `verifyRuntimeLifecycle` run restored the 2,489,909,312-byte model from the JAR resources, verified its published SHA-256, ignored the incomplete file, created `.complete`, and translated successfully.
- `gradle clean build --offline`: passed after all production code changes. `reference/scripts/verify-final-jar.ps1` streamed all 32 native/model entries out of the Zip64 JAR, matching their manifest sizes and SHA-256 values. Fabric metadata, classes, and all license documents were present.
- Final JAR: `build/libs/localchattranslate-0.4.0+26.1.2.jar`, 2,482,487,841 bytes, SHA-256 `489A7306885978A0CFA75DD794DC451248477F79A06F4512F6CF2367DCDF71A5`.
- The original parent-directory JAR and preserved copy still have the original SHA-256 and are byte-identical. The final artifact is under `build/libs/`; the original file was never overwritten.
- The original translation prompt was compared character-for-character against `reference/decompiled/local/chattranslate/TranslationClient.java` and was unchanged. `IncomingChat`, `ProtectedBody`, `TooltipLine`, `ChatAttachments`, `ChatBridge`, and `ChatComponentMixin` still have the same SHA-256 as their recovered sources; the only pre-migration source repair remains the Mixin cast documented above.
