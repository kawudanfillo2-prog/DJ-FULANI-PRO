# DJ FULANI PRO

Professional four-deck Android DJ workstation — active development.

## Milestone 3 — Four-deck media and performance controls

This milestone replaces the first MediaPlayer-based four-deck screen with AndroidX Media3 ExoPlayer.

### Implemented in this milestone
- Four independent Media3 players (Deck A–D)
- Load local audio or video from the Android document picker
- Video preview surface on each deck when a video file is loaded
- Play, pause, stop, seek and per-deck volume
- Master gain and equal-power crossfader
- Manual BPM entry
- Selectable tempo master
- BPM ratio sync with approximate beat-phase alignment using user-set beat markers
- Set beat-zero marker, cue return and four hot-cue positions per deck
- Four-beat loop based on the entered BPM
- Pitch/tempo adjustment
- Per-deck Android Equalizer controls (LOW / MID / HIGH), where supported by the device
- Per-deck Preset Reverb and Bass Boost toggles, where supported by the device
- Eight performance sampler pads with audio sample loading

## Known limitations — not yet a complete professional DJ engine
- BPM is entered manually; automatic BPM and downbeat analysis are not implemented yet.
- Beat sync is BPM-ratio and beat-phase alignment from user-set beat markers. It is not sample-accurate sync.
- The four players use Android Media3 outputs. A single shared native audio mixer is still needed for sample-accurate mixing, advanced time-stretching, scratch response and a unified recording bus.
- Live waveform rendering and waveform analysis are not implemented yet.
- Jog-wheel vinyl scratching, echo/delay/flanger/filter DSP, sampler slicing, recording, stem separation, headphone cue routing and playlist/library management are future milestones.
- Four simultaneous video decoders may exceed a phone's hardware decoder limits. Audio-only decks are recommended for performance on mid-range phones.

## Technology
- Kotlin Android application
- AndroidX Media3 ExoPlayer 1.11.1 for local audio/video media playback
- Android audio effects API for basic EQ, preset reverb and bass boost where supported
- Planned next core engine: C++ with Google Oboe for shared low-latency mixing and DSP

## Build
Open this repository in Android Studio with JDK 17 and Android SDK 35, sync Gradle, and build the `app` module. GitHub Actions also builds a debug APK on each push to `main`.

## APK note
APK size is not a quality target. The app should include only codecs, effects and assets it actually uses; adding unused files merely to make the APK 300 MB would make installation and updates slower without improving sound or mixing quality.
