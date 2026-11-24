# DJ FULANI PRO

Professional four-deck DJ mixing application for Android.

## Current milestone
**Milestone 1 — Android project foundation and native low-latency audio-output scaffold.**

This is an early development project, not a finished APK. The current audio callback outputs silence safely. It does not yet decode or play music files.

## Planned engine
- Four independent decks (A–D)
- Audio file library and decoding
- BPM analysis, beat grids and beat sync
- Waveforms, hot cues and loops
- Per-deck EQ, filters, gain and channel faders
- Crossfader, master output and limiter
- Sampler, effects and recording
- Headphone cueing and audio-device routing

## Technology
- Kotlin Android application
- C++ audio engine using Google Oboe
- Gradle, Android NDK and CMake

## Build prerequisites
Android Studio, JDK 17, Android SDK, Android NDK and CMake.

Open this repository in Android Studio, sync Gradle, then build the app module. Test audio on a physical Android device.

## Important
Features will be implemented incrementally and tested on real devices. The first build is only a foundation; do not consider it a complete DJ mixer.
