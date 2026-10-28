# Yourbit AI

An Android app for working with AI: cloud models, your own server, plugins and on-device local models (GGUF via llama.cpp).

## Features

- Cloud models through your own API keys (OpenRouter, Hugging Face, and others) and OpenAI-compatible servers
- Local GGUF models on the device
- Plugins (example: `docs/example-openai-compatible.plugin`)
- Backups and sync (Supabase schema: `supabase/schema.sql`)
- Multi-language interface

## Requirements

- JDK 17
- Android SDK 34+, NDK `27.0.12077973`, CMake `3.22.1`
- minSdk 26

## Build

```bash
git clone --depth 1 https://github.com/ggml-org/llama.cpp app/src/main/cpp/llama.cpp
./gradlew assembleDebug
```

The APK appears in `app/build/outputs/apk/debug/`.

## Release signing

Without a key the build is signed with the default debug key. To use your own signature, set these environment variables (or Gradle properties):

| Variable | Description |
|---|---|
| `KEYSTORE_PATH` | path to the `.keystore` / `.jks` file |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

GitHub Actions uses the secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

## Tests

```bash
./gradlew test
```

## Sync server

Run `supabase/schema.sql` in full in the SQL Editor of your Supabase project (the script is idempotent).

## License

The code is distributed under the [MIT](LICENSE) license.

Third-party components keep their own licenses: llama.cpp (MIT), AndroidX and PDFBox-Android (Apache-2.0), junrar (UnRAR license). The font in `app/src/main/assets/fonts` is VCR OSD Mono [RUS by Daymarius] (version 1.00, 2018). No license is specified; rights belong to its authors, and the project's MIT license does not cover the font.

## Documentation

Modules, plugins, forks and mods: [docs/DOCUMENTATION.pdf](docs/DOCUMENTATION.pdf).
