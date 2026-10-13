# LocalChatTranslate with bundled llama.cpp

LocalChatTranslate 0.4.0+26.1.2 is a client-side Fabric MOD for Minecraft 26.1.2 and Java 25. This project was reconstructed from the original distribution JAR, then changed to run a bundled TranslateGemma 4B GGUF through a bundled llama.cpp server. The original chat parsing, placeholder protection, tooltip handling, and translation prompt remain in place.

## Installation

Install `build/libs/localchattranslate-0.4.0+26.1.2.jar` in the normal Fabric `mods` folder, replacing the older LocalChatTranslate JAR if present. This build targets Windows x64 with a working Vulkan driver. Minecraft 26.1.2, Java 25, Fabric Loader 0.19.5 or newer, and Fabric API 0.155.2 or newer are still required. Ollama, a separate llama.cpp installation, a separate model download, and a manually started server are unnecessary.

The JAR is about 2.5 GB. On first launch, the MOD streams and verifies the bundled runtime and GGUF into the game directory under `localchattranslate/runtime/<bundleVersion>/`. The runtime starts asynchronously; the settings screen shows its state. Its logs are written to `logs/localchattranslate-llama.log`. It listens only on `127.0.0.1`, uses a random port and session key, and exits when Minecraft exits. Windows Job Object supervision also stops it if the Minecraft process is killed.

The MOD keeps the existing `/lct` command and `[G]` tooltip translation key. The settings screen contains the translation, NPC, and item-description toggles. Existing config files with an `endpoint` field load without requiring migration; the field is ignored.

The included model is subject to Google's Gemma Terms of Use and Prohibited Use Policy. See `THIRD_PARTY_NOTICES.md` and the license documents bundled into the JAR before redistributing it.

## Build and verify

Use Java 25 and the included Gradle wrapper:

```powershell
$env:JAVA_HOME = 'path-to-jdk-25'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat clean build
.\gradlew.bat verifyRuntimeLifecycle
.\gradlew.bat runClientGameTest
```

`build` creates a Zip64 JAR and runs the recovered parser and placeholder checks. `verifyRuntimeLifecycle` tests resource extraction, key authentication, translation, restart after server death, and clean shutdown. `runClientGameTest` launches Minecraft to check the settings screen, `/lct`, chat, NPC chat, tooltip translation, and exit. The runtime and model binaries in `bundle/` are build inputs; the original distribution in the parent directory is never overwritten.

See `RECONSTRUCTION.md` for the original JAR checksum, source recovery, component inventory, binary sources, and test evidence.
