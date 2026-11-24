# Third-party notices

## SoundTouch 2.4.1
DJ FULANI PRO uses SoundTouch 2.4.1 by Olli Parviainen for pitch-preserving tempo/time-stretch processing.

- Upstream project: https://soundtouch.surina.net/
- Source release: https://www.surina.net/soundtouch/soundtouch-2.4.1.tar.gz
- License: GNU Lesser General Public License, version 2.1 (LGPL-2.1)

The build downloads the official source archive at build time and compiles it for the Android ABIs configured in the app. SoundTouch is not a proprietary codec or a precompiled third-party APK.

Upstream license text (COPYING.TXT) is maintained by the SoundTouch project and is distributed with the source archive. Consult the upstream archive for the complete license text.

## Demucs Android engine and model
- Android engine source: https://github.com/jPaauwe/demucs-android (MIT)
- demucs.cpp base implementation: MIT.
- Demucs v4.1.0 htdemucs_6s weights: MIT, by Meta Platforms; the app downloads a pinned 55 MB model and verifies SHA-256.
- Eigen dependency: MPL-2.0, fetched as the upstream Git submodule.
- Source code and model files are fetched separately; model weights are not embedded as APK padding.
