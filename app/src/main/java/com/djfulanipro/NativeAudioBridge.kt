package com.djfulanipro

internal object NativeAudioBridge {
    init { System.loadLibrary("djfulani_audio") }
    external fun nativeStartEngine(): Boolean
    external fun nativeStopEngine()
    external fun nativePushPcm(deck: Int, pcm: FloatArray, offsetFloats: Int, frames: Int): Int
    external fun nativeClearDeck(deck: Int)
    external fun nativeSetDeckSourceInfo(deck: Int, sampleRate: Int, durationMs: Long, startMs: Long)
    external fun nativeGetDeckPositionMs(deck: Int): Long
    external fun nativeGetOutputSampleRate(): Int
    external fun nativeGetBufferedFrames(deck: Int): Int
    external fun nativeSetDeckPlaying(deck: Int, playing: Boolean)
    external fun nativeSetDeckGain(deck: Int, gain: Float)
    external fun nativeSetDeckSpeed(deck: Int, speed: Float)
    external fun nativeSetDeckEq(deck: Int, low: Float, mid: Float, high: Float)
    external fun nativeSetDeckEcho(deck: Int, enabled: Boolean)
    external fun nativeSetMasterGain(gain: Float)
    external fun nativeSetCrossfader(value: Float)
    external fun nativeStartRecording()
    external fun nativeStopRecording()
    external fun nativeReadRecording(destination: FloatArray, maxFrames: Int): Int
}
