#include <jni.h>
#include <android/log.h>
#include <oboe/Oboe.h>
#include <array>
#include <atomic>
#include <algorithm>
#include <memory>
#include <mutex>

#define LOG_TAG "DJFULANI_AUDIO"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
constexpr int kDeckCount = 4;

struct DeckState {
    std::atomic<bool> playing{false};
    std::atomic<float> gain{1.0f};
};

class DjAudioEngine final : public oboe::AudioStreamDataCallback {
public:
    oboe::Result start() {
        if (stream_ && stream_->getState() == oboe::StreamState::Started) return oboe::Result::OK;
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Shared)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Stereo)
            ->setDataCallback(this);
        const oboe::Result result = builder.openStream(stream_);
        if (result != oboe::Result::OK) {
            LOGE("Failed to open output stream: %s", oboe::convertToText(result));
            return result;
        }
        return stream_->requestStart();
    }

    void stop() {
        if (stream_) {
            stream_->requestStop();
            stream_->close();
            stream_.reset();
        }
    }

    void setDeckPlaying(int deck, bool playing) {
        if (validDeck(deck)) decks_[deck].playing.store(playing);
    }
    void setDeckGain(int deck, float gain) {
        if (validDeck(deck)) decks_[deck].gain.store(std::clamp(gain, 0.0f, 1.0f));
    }
    void setMasterGain(float gain) {
        masterGain_.store(std::clamp(gain, 0.0f, 1.0f));
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* audioData, int32_t numFrames) override {
        // Milestone 1: safe low-latency output scaffold. PCM decoding,
        // deck buffers, mixing, limiter and effects come in later milestones.
        auto* output = static_cast<float*>(audioData);
        std::fill(output, output + (numFrames * 2), 0.0f);
        return oboe::DataCallbackResult::Continue;
    }

private:
    static bool validDeck(int deck) { return deck >= 0 && deck < kDeckCount; }
    std::array<DeckState, kDeckCount> decks_{};
    std::atomic<float> masterGain_{1.0f};
    std::shared_ptr<oboe::AudioStream> stream_;
};

std::mutex gEngineMutex;
std::unique_ptr<DjAudioEngine> gEngine;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_djfulanipro_MainActivity_nativeCreateEngine(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (!gEngine) gEngine = std::make_unique<DjAudioEngine>();
    return JNI_TRUE;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_djfulanipro_MainActivity_nativeStartEngine(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (!gEngine) gEngine = std::make_unique<DjAudioEngine>();
    return gEngine->start() == oboe::Result::OK ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT void JNICALL
Java_com_djfulanipro_MainActivity_nativeStopEngine(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) gEngine->stop();
}
extern "C" JNIEXPORT void JNICALL
Java_com_djfulanipro_MainActivity_nativeSetDeckPlaying(JNIEnv*, jobject, jint deck, jboolean playing) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) gEngine->setDeckPlaying(deck, playing == JNI_TRUE);
}
extern "C" JNIEXPORT void JNICALL
Java_com_djfulanipro_MainActivity_nativeSetDeckGain(JNIEnv*, jobject, jint deck, jfloat gain) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) gEngine->setDeckGain(deck, gain);
}
extern "C" JNIEXPORT void JNICALL
Java_com_djfulanipro_MainActivity_nativeSetMasterGain(JNIEnv*, jobject, jfloat gain) {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (gEngine) gEngine->setMasterGain(gain);
}
