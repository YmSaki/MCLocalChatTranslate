# LocalChatTranslate

LocalChatTranslate is a client-side Fabric MOD for Minecraft 26.1.2 that translates English chat, NPC dialogue, and item descriptions into your language on your own PC. It was written for Hypixel SkyBlock. Translation runs locally with TranslateGemma 4B on a bundled llama.cpp server; no text is sent to an online translation service.

## Requirements

- Windows x64 with a working Vulkan driver. Other operating systems are not supported.
- Minecraft 26.1.2 and Java 25
- Fabric Loader 0.19.5 or newer and Fabric API 0.155.2 or newer
- About 2.6 GB of free disk space for the translation model

## Installation

1. Put `localchattranslate-<version>.jar` in the `mods` folder. Remove any older LocalChatTranslate JAR.
2. Start Minecraft. The translation model is not included in the JAR, so the first launch shows that the model is missing.
3. Open the settings screen (Mod Menu, or `/lct settings`) and press **Download Model** (モデルをダウンロード), or run `/lct download`.

The MOD then downloads `translategemma-4b-it-Q4_K_M.gguf` (2,489,909,312 bytes) from [Hugging Face](https://huggingface.co/bullerwins/translategemma-4b-it-GGUF). Nothing is downloaded until you agree. The download is pinned to one repository revision, resumes after an interruption, and is used only if its SHA-256 matches. The model is stored in `localchattranslate/models/` inside the game directory and is reused by later versions of the MOD.

The model is subject to Google's [Gemma Terms of Use](https://ai.google.dev/gemma/terms) and [Prohibited Use Policy](https://ai.google.dev/gemma/prohibited_use_policy).

## Usage

- Translations appear under the original chat line, prefixed with `↳`.
- Hover over an item and press `G` to translate its description. The key can be changed in Controls.
- `/lct` shows the status, `/lct on` and `/lct off` toggle translation, `/lct settings` opens the settings screen, `/lct download` starts the model download, `/lct lang <code>` sets the target language, and `/lct reload` reloads the config file.

### Choosing the target language

Press **Translate To** (翻訳先) on the settings screen. **Same as Game Language** follows the language selected in Minecraft. Pick a language from the list to translate into it while the game itself stays in another language, for example English. The list shows the Minecraft languages that TranslateGemma can write. The same setting is available as `/lct lang ja_jp` or `/lct lang auto`, using Minecraft's language codes.

English is the source language, so it cannot be the target. With the game in English and **Same as Game Language** selected, nothing is translated until you choose a language. Configs from earlier versions keep translating into Japanese.

Lines that contain Japanese, Chinese, or Korean characters are left untranslated. So are lines that contain letters of the target language's own script, for example Cyrillic when translating into Russian. For targets written in Latin script, such as German or Spanish, a line already in that language cannot be told apart from English and is sent for translation.

The runtime starts in the background. It listens only on `127.0.0.1` with a random port and session key, and it stops when Minecraft exits. Its log is `logs/localchattranslate-llama.log`.

### Choosing a model

Press **Model Settings...** (モデル設定…) on the settings screen. Like the resource pack screen, it lists the models you can use:

- TranslateGemma 4B, the default, with its download state
- `.gguf` files in `localchattranslate/models/` inside the game directory (**Open Model Folder** opens it)
- `.gguf` files in the Hugging Face cache (`HF_HUB_CACHE`, `HF_HOME/hub`, or `~/.cache/huggingface/hub`)
- files added with **Browse...** or by dragging them onto the screen; these are used where they are, not copied

Select one and press **Done** (or double-click it). The runtime switches to that model without restarting the game. Selecting the default while it is not downloaded starts its download. The translation prompt is written for TranslateGemma, so other models may translate poorly.

## Network access

The MOD connects to the internet only to download the default model, and only after you agree. That request goes to `huggingface.co` and its download CDN. Chat and item text never leave your PC.

## Building

Use Java 25 and the Gradle wrapper. The build needs the llama.cpp b10964 Windows x64 Vulkan release extracted to `bundle/llama.cpp/windows-x64/`. The integration tests also need the default GGUF at `bundle/model/translategemma-4b.gguf`; they copy it into the test game directory instead of downloading it. See `THIRD_PARTY_NOTICES.md` for the exact sources.

```powershell
$env:JAVA_HOME = 'path-to-jdk-25'
.\gradlew.bat clean build              # JAR, recovered-behavior checks, model download checks
.\gradlew.bat verifyRuntimeLifecycle   # starts llama-server, translates, restarts, shuts down
.\gradlew.bat runClientGameTest        # launches Minecraft and checks chat, NPC, tooltip, settings
```

`src/main/resources/assets/localchattranslate/target_languages.json` maps Minecraft languages to the language names and codes in the TranslateGemma prompt. It is generated from the model's chat template by `reference/scripts/generate-target-languages.py`.

`build` runs `verifyTargetLanguage`, which checks target resolution, config migration, and that the Japanese prompt is unchanged. It also runs `verifyModelStore`, which tests consent, download through a redirect, resume, checksum rejection, stall recovery, and HTTP errors against a local HTTP server.

`RECONSTRUCTION.md` explains how the source was recovered from the original 0.4.0 release.

## License

The MOD's code is licensed under the GNU Lesser General Public License v3.0; see `COPYING.LESSER` and `COPYING`. The bundled llama.cpp runtime and the downloaded model have their own licenses; see `THIRD_PARTY_NOTICES.md`.
