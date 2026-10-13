# Third-party notices

## llama.cpp

- Project: https://github.com/ggml-org/llama.cpp
- Binary: release build b10964 (`b29c606e2`), Windows x64 Vulkan distribution
- License: MIT; see `bundle/licenses/llama.cpp-LICENSE`
- The distribution also includes LLVM OpenMP; see `bundle/llama.cpp/windows-x64/LICENSE-LLVM-OpenMP`.
- The MOD packages `llama-server.exe` and all DLL files from that distribution. Other executables are excluded.

## TranslateGemma 4B

- Original model: https://huggingface.co/google/translategemma-4b-it
- GGUF quantization: https://huggingface.co/bullerwins/translategemma-4b-it-GGUF
- File: `translategemma-4b-it-Q4_K_M.gguf`, renamed to `translategemma-4b.gguf` in this project
- License: Gemma Terms of Use and Gemma Prohibited Use Policy. Copies of the official documents are in `bundle/licenses/gemma-terms.html` and `bundle/licenses/gemma-prohibited-use-policy.html`; the required notice is in `bundle/licenses/NOTICE`.
- The GGUF is a third-party quantization of Google's model. This project has not changed its weights.

The model is governed by the Gemma use restrictions in Section 3.2 of the terms. Anyone distributing a JAR containing it must pass on the terms and notice. The llama.cpp runtime and model are bundled for local inference only.
