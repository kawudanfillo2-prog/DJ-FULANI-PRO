# DJ FULANI PRO

A professional four-deck DJ mixing application for Android.

## Current milestone
**Milestone 2 — Four-deck audio playback and basic mixing controls.**

The Android app now has four independent music decks. Each deck can select an audio file through Android's document picker, prepare it, play, pause, stop, and seek. Each deck has its own volume control. The master level and crossfader adjust the four deck outputs.

## Implemented in this milestone
- Four independent audio players (Deck A–D)
- Android audio-file picker using the Storage Access Framework
- Asynchronous track loading
- Play, pause, stop and seek controls
- Per-deck volume
- Master volume
- Crossfader (Deck A/C on left, Deck B/D on right)
- Landscape four-deck console layout

## Not implemented yet
- Accurate BPM detection and beat grids
- Beatmatching / time-stretch sync
- Jog wheels and scratching
- Waveforms and hot cues
- EQ, filters, FX and sampler
- Headphone cue routing
- Recording and professional low-latency native mixing

## Technology
- Kotlin Android application
- Android MediaPlayer for this playback milestone
- Planned future native low-latency engine: C++ and Google Oboe

## Build
Open this repository in Android Studio with JDK 17 and Android SDK 35 installed. Sync Gradle and build the app module. Test on a physical Android device.

## Important
This is an in-progress project. The current milestone provides real multi-deck playback, but it is not yet a feature-complete VirtualDJ replacement. Device audio routing and simultaneous playback behavior must be tested on the target phone.
