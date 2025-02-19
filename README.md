# LCEVC AndroidX Media

AndroidX Media is a collection of libraries for implementing media use cases on
Android, including local playback (via ExoPlayer), video editing (via Transformer) and media sessions.

This repo is a fork of the official Google maintained [AndroidX Media repo](https://github.com/androidx/media), tag 1.2.1, with the aim of adding support for the seamless playback of [MPEG-5 Part 2 ISO/IEC 23094-2](https://www.iso.org/standard/79143.html), **Low Complexity Enhancement Video Coding** ([LCEVC](https://www.lcevc.org)) encoded content.

Support for LCEVC is added here by means of a decoder library (previously known as 'extensions' in ExoPlayer, the precursor of AndroidX Media), called `decoder_lcevc`, in a similar way as the existing `decoder_av1` that is part of the AndroidX Media project.

In more detail, the upstream repo has here the following changes:
*   The additional [LCEVC decoder library](libraries/decoder_lcevc);
*   The additional LCEVC enabled variant, called `withDecoderExtensionsWithLcevc`, of the [main demo app](demos/main);
*   Minor changes in the core libraries, mainly some backports of changes already in the upstream repo, in release tags from 1.3.1 onwards, that are functional to the LCEVC workflow;

In this repo, every non-LCEVC functionality of the upstream repo, at the release tag mentioned above, is maintained. Please follow the upstream [README](https://github.com/androidx/media/blob/1.2.1/README.md), for reference.

Note: The test `androidx.media3.common.util.AtomicFileTest.writeRead` in `lib-common` fails in Windows (this is because the test tries to delete a file that is still in open state, which is not allowed in Windows), unfortunately this is behaviour from the upstream project. As a result, in Windows, a `.\gradlew build` command from the project root directory will fail. The gradlew assembleRelease or assembleDebug commands, however, will succeed since they do not run tests.

## Compliance and Legal Information
This section provides an explanation of the set of compliance artefacts included with the distribution of AndroidX Media, which has been modified and extended by V-Nova. The purpose of these artefacts is to ensure compliance with the open source licenses governing the components of the distributed software.

### Artefacts Included
The following artefacts have been collated to ensure compliance with the relevant open-source licenses:

#### Apache-2.0 License Text
This is the standard license text of the Apache-2.0 license, which governs the original AndroidX Media code as well as any modifications made to it by V-Nova.

Filename: [LICENSE (Apache-2.0 License)](https://github.com/androidx/media/blob/1.2.1/LICENSE)

#### BSD-3-Clause-Clear License Text
This license covers the decoder LCEVC library (also called 'the extension') developed by V-Nova for AndroidX Media.

Filename: [libraries/decoder_lcevc/LICENSE.txt (BSD-3-Clause-Clear License)](libraries/decoder_lcevc/LICENSE.txt)

Note: V-Nova has included additional clarifying wording to this license text (see section below). This wording is detailed in the file.

#### List of Modified Files
This file lists all files that V-Nova has modified from the original AndroidX Media project. The modifications are covered by the Apache-2.0 license.

Filename: [Modifications.txt](Modifications.txt)

#### V-Nova’s Additional Wording - No Relicense Exception

This text clarifies that certain portions of the distributed code (i.e., the extension) are governed by the BSD-3-Clause-Clear license and additional terms that protect V-Nova’s intellectual property.

Filename: [V-Nova_No_Relicense_Exception.txt](https://raw.githubusercontent.com/v-novaltd/licenses/refs/heads/main/V-Nova_No_Relicense_Exception.txt)

#### V-Nova Proprietary License Text

This proprietary license governs the use of V-Nova's proprietary LCEVC dependency package used by the decoder library, which is included in the distribution alongside AndroidX Media. The proprietary license applies to this specific component, which is not subject to open-source licensing terms such as Apache-2.0 or BSD-3-Clause-Clear. 

The proprietary license outlines the terms under which the decoder library may be used, modified, and distributed, including restrictions to protect V-Nova's intellectual property. 

This license does not grant any rights to the source code of the decoder library, and any use of the library must comply with the conditions specified in the proprietary license text. 

Filename: [V-Nova_Proprietary_License.txt](https://raw.githubusercontent.com/v-novaltd/licenses/refs/heads/main/V-Nova_Proprietary_License.txt)

### Contact Information

For any questions or clarifications regarding these artefacts or V-Nova’s licensing, please contact the legal team at:

E-mail: legal@v-nova.com 
