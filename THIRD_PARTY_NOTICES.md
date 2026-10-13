# Third-party notices

## llama.cpp

- Project: https://github.com/ggml-org/llama.cpp
- Binary: release build b10964 (`b29c606e2`), Windows x64 Vulkan distribution, archive SHA-256 `1EE3AD952F4BA71F438BD6D7BEBEF19E1C7AF04ADCAA35D08B4DDABB27D4C642`
- License: MIT; see `bundle/licenses/llama.cpp-LICENSE`
- The distribution also includes LLVM OpenMP; see `bundle/llama.cpp/windows-x64/LICENSE-LLVM-OpenMP`.
- The MOD packages `llama-server.exe` and all DLL files from that distribution. Other executables are excluded.

## TranslateGemma 4B

The MOD does not contain the model. It downloads the model from Hugging Face after the user agrees.

- Original model: https://huggingface.co/google/translategemma-4b-it
- GGUF quantization: https://huggingface.co/bullerwins/translategemma-4b-it-GGUF, revision `7c938465a870d8624bcfa98a8e4a3510053c19a8`
- File: `translategemma-4b-it-Q4_K_M.gguf`, 2,489,909,312 bytes, SHA-256 `7f7357c14abd9da4eb200b38b05da502cd6e10d7e1d403fbc9f78c19f3209b72`
- License: Gemma Terms of Use and Gemma Prohibited Use Policy. Copies of the official documents are in `bundle/licenses/gemma-terms.html` and `bundle/licenses/gemma-prohibited-use-policy.html`; the notice is in `bundle/licenses/NOTICE`. These documents are also packaged in the JAR.
- The GGUF is a third-party quantization of Google's model. This project does not change its weights.
