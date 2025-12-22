# MPEG-5 Part 2 LCEVC (Low Complexity Enhancement Video Coding) decoder module

The LCEVC Decoder module provides `LcevcMediaCodecAdapterFactory`, which uses lcevc-dil native
library to enhance videos.

## License note
The decoder_lcevc source code in this module is covered by the [BSD-3-Clause-Clear License](LICENSE.txt) included in the repository.
The maven dependency used to build decoder_lcevc links to a package (lcevc-dil) covered by [V-Nova_Proprietary_License.txt](https://raw.githubusercontent.com/v-novaltd/licenses/refs/heads/main/V-Nova_Proprietary_License.txt). The proprietary license outlines the terms under which the decoder library may be used, modified, and distributed, including restrictions to protect V-Nova's intellectual property.
This license does not grant any rights to the source code of the decoder library, and any use of the library must comply with the conditions specified in the proprietary license text.

## Documentation

**Design Documents**

- [Dataflow Diagram](docs/androidx-media3-decoder-lcevc_dataflow.md)
- [Class Diagram](docs/androidx-media3-decoder-lcevc_classes.md)

**Core Changes**

These are the changes done to the core project to enable LCEVC MediaCodecAdapter implementation.
- [Changes Done to ExoPlayer Project](docs/lcevc-androidx-media3_core-changes.md)

**Integration Guide**

The project needs CMAKE 3.18.1 installed to build the LCEVC Decoder libraries and can be done through Tools -> SDK Manager -> SDK Tools -> CMake if Android Studio is being used to build the project.

The following are the methods in which the LCEVC MediaCodecAdapter implementation can be built. The changes involved in the process are also mentioned.

#### Guide to build an androidx/media3 based project with LCEVC MediaCodecAdapter
- [Build androidx/media3 from source](docs/lcevc-androidx-media3_build-changes.md)
