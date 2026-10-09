# Third-party notices

Current source (0.4.4):

- `whisper.cpp`, ggml-org, commit `6e4ab854f67f743900934a703d5603419384c961`: https://github.com/ggml-org/whisper.cpp — MIT. CPU-only saved-audio inference, including upstream ggml. Pinned source archive hash in `DEPENDENCIES.json`; complete upstream notice in `app/src/main/assets/licenses/whisper.cpp-MIT.txt`.
- OpenAI Whisper multilingual model, quantized `ggml-medium-q5_0.bin`, distributed by `ggerganov/whisper.cpp` at revision `5359861c739e955e79d9a303bcbc70fb988958b1`: https://huggingface.co/ggerganov/whisper.cpp — MIT. Bundled, hash-verified offline model. Original model notice in `app/src/main/assets/licenses/whisper-model-MIT.txt`.
- Vosk Android 0.3.75 and `vosk-model-small-pt-0.3` (Portuguese): https://github.com/alphacep/vosk-api and https://alphacephei.com/vosk/models — Apache-2.0. Streaming live preview; pinned model/AAR hashes in `DEPENDENCIES.json`. Notice in `app/src/main/assets/licenses/vosk-license.txt`.
- Ubuntu Mono (regular, bold, bold italic) from `google/fonts` `ufl/ubuntumono`: Ubuntu Font Licence 1.0. Bundled in `app/src/main/res/font`; licence text in `app/src/main/assets/licenses/ubuntu-font-licence.txt`.
- JNA 5.18.1: https://github.com/java-native-access/jna — Apache-2.0 option. Vosk native binding; pinned AAR hash in `DEPENDENCIES.json`. Notice in `app/src/main/assets/licenses/jna-license.txt`.
- Concentus pure-Java Opus codec, lostromb/concentus commit `3885c4e46513ef0fc81fca100189e54f1714c6ca`: https://github.com/lostromb/concentus — BSD-style; full upstream notice in `app/src/main/assets/licenses/concentus-license.txt`.
- Omi BLE UUIDs and firmware protocol, BasedHardware/omi commit `d1fdcb4cc4fbd021eac799630e476f144e6bc18a`: https://github.com/BasedHardware/omi — MIT; notice in `app/src/main/assets/licenses/omi-license.txt`. Compatibility evidence in `verification/OMI-PROTOCOL.md`.
- GMind companion only (module `sentient`): SQLCipher for Android 4.12.0 (Zetetic LLC, bundles SQLite, public domain): https://github.com/sqlcipher/sqlcipher-android — BSD-3-Clause; notice in `sentient/src/main/assets/licenses/sqlcipher-android-BSD.txt`. AndroidX SQLite 2.2.0: Apache-2.0; notice in `sentient/src/main/assets/licenses/androidx-sqlite-Apache-2.0.txt`. Pinned AAR hashes in `DEPENDENCIES.json` (`sentient_runtime`). Sentient also bundles the same Ubuntu Mono fonts as Omi Tarefas (Ubuntu Font Licence 1.0; `sentient/src/main/assets/licenses/ubuntu-font-licence.txt`). Host tests also use xerial sqlite-jdbc 3.50.3.0 (Apache-2.0, test-only, never packaged).
- Gradle wrapper: https://gradle.org — Apache-2.0. Android Gradle plugin from Google — Apache-2.0.
- Public JFK speech fixture `samples/jfk.wav`, from the pinned whisper.cpp source: test-only; provenance/hash in `verification/whisper-fixture.json`. Not included in the release APK.

License texts are accessible from About (the i icon) → Licenses. This standalone app is not affiliated with Omi or Based Hardware. Owner-local Omi Private supplied selectively reused native build/model/adapter patterns; its BLE, PC/cloud/sync, credentials and key material were not copied for this migration.

Releases through 0.3.3 used Vosk alone. Release 0.4.0 replaced live recognition with Whisper and excluded Vosk/JNA. Release 0.4.1 restores the pinned Vosk/JNA runtime for streaming drafts and retains Whisper for post-save refinement. Public `test.wav` and `jfk.wav` fixtures are test-only, not shipped release recordings.
