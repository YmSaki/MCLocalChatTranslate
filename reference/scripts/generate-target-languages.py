"""Generates src/main/resources/assets/localchattranslate/target_languages.json.

Maps each Minecraft language code to the language code and English name that the
TranslateGemma chat template uses in its prompt. The template is read from the GGUF's
tokenizer.chat_template metadata; the Minecraft codes come from the client assets index.

Usage: python -I generate-target-languages.py <model.gguf> <assets-index.json> <output.json>

Rules:
- The bare language code is used, so ja_jp stays "Japanese (ja)" as in the original prompt.
- A regional or script code is used only where it changes the written language (OVERRIDES).
- English is the source language and is never a target.
- Minecraft languages the template does not know, or whose script it cannot produce, are left out.
"""
import json
import re
import struct
import sys

OVERRIDES = {
    "zh_cn": "zh-Hans",
    "zh_hk": "zh-Hant",
    "zh_tw": "zh-TW",
    "pt_br": "pt-BR",
    "pt_pt": "pt-PT",
    "sr_cs": "sr-Latn",
    "sr_sp": "sr-Cyrl",
}
# Latin-script Belarusian; the template only offers Cyrillic Belarusian.
EXCLUDED = {"be_latn"}


def chat_template(path):
    sizes = {0: 1, 1: 1, 2: 2, 3: 2, 4: 4, 5: 4, 6: 4, 7: 1, 10: 8, 11: 8, 12: 8}
    with open(path, "rb") as f:
        def u32(): return struct.unpack("<I", f.read(4))[0]
        def u64(): return struct.unpack("<Q", f.read(8))[0]
        def text(): return f.read(u64()).decode("utf-8")

        def value(kind):
            if kind == 8:
                return text()
            if kind == 9:
                item, count = u32(), u64()
                if item in (8, 9):
                    return [value(item) for _ in range(count)]
                f.seek(sizes[item] * count, 1)
                return None
            f.seek(sizes[kind], 1)
            return None

        if f.read(4) != b"GGUF":
            raise SystemExit("not a GGUF file")
        u32()
        u64()
        for _ in range(u64()):
            key, kind = text(), u32()
            found = value(kind)
            if key == "tokenizer.chat_template":
                return found
    raise SystemExit("tokenizer.chat_template not found")


def main(gguf, index, output):
    names = dict(re.findall(r'^\s*"([^"]+)": "([^"]+)",?$', chat_template(gguf), re.M))
    with open(index, encoding="utf-8") as f:
        objects = json.load(f)["objects"]
    codes = sorted(m.group(1) for key in objects if (m := re.fullmatch(r"minecraft/lang/([a-z_]+)\.json", key)))
    table = {}
    for code in codes:
        if code in EXCLUDED:
            continue
        target = OVERRIDES.get(code, code.split("_")[0])
        if target not in names or names[target] == "English":
            continue
        table[code] = {"code": target, "name": names[target]}
    with open(output, "w", encoding="utf-8", newline="\n") as f:
        json.dump(table, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"{len(table)} target languages written to {output}")


if __name__ == "__main__":
    main(*sys.argv[1:4])
