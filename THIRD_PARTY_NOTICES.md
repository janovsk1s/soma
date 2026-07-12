# Third-party notices

Soma uses the following principal third-party components. Their licenses apply
to those components independently of Soma's GPL license. Exact JVM dependency
versions are declared in `gradle/libs.versions.toml`.

| Component | Version or pin | License |
| --- | --- | --- |
| AndroidX Core, Lifecycle, Activity, WorkManager, Room, Jetpack Compose and Material 3 | Gradle version catalog | Apache License 2.0 |
| Kotlin, Kotlin coroutines and Gradle plugins | Gradle version catalog | Apache License 2.0 |
| whisper.cpp and ggml | 1.9.1, vendored under `whisper/src/main/cpp/vendor/whisper.cpp` | MIT |
| OpenAI Whisper multilingual tiny model, converted to ggml Q5_1 | SHA-256 `818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7` | MIT |
| JUnit 4 | test only | Eclipse Public License 1.0 |
| Robolectric and AndroidX Test | test only | Apache License 2.0 |

The bundled model is `ggml-tiny-q5_1.bin`, 32,152,673 bytes. It was obtained
from the [whisper.cpp model repository](https://huggingface.co/ggerganov/whisper.cpp/blob/c521a4b02f422512d734391fdf08bb08c0862f68/ggml-tiny-q5_1.bin).
Its recorded source and digest also live beside it in
`whisper/src/main/assets/MODEL_INFO.txt`. A release must not replace the model
unless that record, this notice, and the reviewed digest are updated together.

Authoritative project license sources:

- AndroidX and Compose: https://source.android.com/docs/setup/about/licenses
- Kotlin: https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt
- Kotlin coroutines: https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt
- whisper.cpp: https://github.com/ggml-org/whisper.cpp/blob/master/LICENSE
- OpenAI Whisper code and model weights: https://github.com/openai/whisper/blob/main/LICENSE
- JUnit 4: https://github.com/junit-team/junit4/blob/main/LICENSE-junit.txt
- Robolectric: https://github.com/robolectric/robolectric/blob/master/LICENSE

## whisper.cpp and ggml MIT notice

Copyright (c) 2023-2026 The ggml authors

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## OpenAI Whisper model MIT notice

Copyright (c) 2022 OpenAI

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
