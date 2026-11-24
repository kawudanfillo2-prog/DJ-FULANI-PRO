#include <jni.h>
#include <oboe/Oboe.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

namespace {
constexpr int N=4, ECHO_FRAMES=8192;
constexpr uint64_t CAP=1u<<17, MASK=CAP-1, REC_CAP=1u<<19, REC_MASK=REC_CAP-1;
struct Deck {
 std::array<float,CAP*2> pcm{};
 std::atomic<uint64_t> write{0},read{0},consumed{0};
 std::atomic<bool> playing{false},echo{false};
 std::atomic<int> rate{48000};
 std::atomic<int64_t> startMs{0},durationMs{0};
 std::atomic<float> gain{0.82f},speed{1},lowGain{1},midGain{1},highGain{1};
 std::atomic<uint32_t> clearReq{0},clearAck{0};
 double phase=0;
 float lowL=0,lowR=0,midL=0,midR=0;
 std::array<float,ECHO_FRAMES*2> delay{};
 int delayPos=0;
};
class Engine final: public oboe::AudioStreamDataCallback {
public:
 oboe::Result start(){
  if(stream_ && stream_->getState()==oboe::StreamState::Started) return oboe::Result::OK;
  oboe::AudioStreamBuilder b;
  b.setDirection(oboe::Direction::Output)->setPerformanceMode(oboe::PerformanceMode::LowLatency)
   ->setSharingMode(oboe::SharingMode::Exclusive)->setFormat(oboe::AudioFormat::Float)
   ->setChannelCount(oboe::ChannelCount::Stereo)->setUsage(oboe::Usage::Media)->setDataCallback(this);
  auto r=b.openStream(stream_);
  if(r!=oboe::Result::OK){stream_.reset();b.setSharingMode(oboe::SharingMode::Shared);r=b.openStream(stream_);}
  if(r!=oboe::Result::OK)return r;
  int32_t burst=stream_->getFramesPerBurst(); if(burst>0)stream_->setBufferSizeInFrames(burst*2);
  return stream_->requestStart();
 }
 void stop(){if(stream_){stream_->requestStop();stream_->close();stream_.reset();}}
 int sampleRate()const{return stream_?stream_->getSampleRate():48000;}
 int push(int d,JNIEnv*e,jfloatArray a,int off,int frames){
  if(!valid(d)||!a||frames<=0)return 0; auto&x=decks[d];
  uint64_t w=x.write.load(std::memory_order_relaxed), rd=x.read.load(std::memory_order_acquire);
  int n=(int)std::min<uint64_t>(CAP-(w-rd),(uint64_t)frames); if(n<=0)return 0;
  std::vector<jfloat> tmp((size_t)n*2);e->GetFloatArrayRegion(a,off,n*2,tmp.data());
  if(e->ExceptionCheck()){e->ExceptionClear();return 0;}
  for(int i=0;i<n;i++){uint64_t p=((w+i)&MASK)*2;x.pcm[p]=tmp[i*2];x.pcm[p+1]=tmp[i*2+1];}
  x.write.store(w+n,std::memory_order_release);return n;
 }
 void clear(int d){
  if(!valid(d))return;auto&x=decks[d];x.playing.store(false,std::memory_order_release);
  uint32_t req=x.clearReq.fetch_add(1,std::memory_order_acq_rel)+1;
  if(!stream_||stream_->getState()!=oboe::StreamState::Started){applyClear(x,req);return;}
  for(int i=0;i<2000;i++){if(x.clearAck.load(std::memory_order_acquire)==req)return;std::this_thread::yield();}
  applyClear(x,req);
 }
 void info(int d,int rate,int64_t duration,int64_t start){if(!valid(d))return;auto&x=decks[d];x.rate.store(std::max(8000,rate));x.durationMs.store(std::max<int64_t>(0,duration));x.startMs.store(std::max<int64_t>(0,start));x.consumed.store(0);}
 int64_t position(int d)const{if(!valid(d))return 0;auto&x=decks[d];int r=std::max(1,x.rate.load());return x.startMs.load()+(int64_t)(x.consumed.load()*1000ULL/(uint64_t)r);}
 int buffered(int d)const{if(!valid(d))return 0;auto&x=decks[d];return(int)std::min<uint64_t>(x.write.load()-x.read.load(),CAP);}
 void playing(int d,bool v){if(valid(d))decks[d].playing.store(v);}
 void gain(int d,float v){if(valid(d))decks[d].gain.store(std::clamp(v,0.f,1.5f));}
 void speed(int d,float v){if(valid(d))decks[d].speed.store(std::clamp(v,.5f,2.f));}
 void eq(int d,float l,float m,float h){if(valid(d)){decks[d].lowGain.store(std::clamp(l,0.f,2.f));decks[d].midGain.store(std::clamp(m,0.f,2.f));decks[d].highGain.store(std::clamp(h,0.f,2.f));}}
 void echo(int d,bool v){if(valid(d))decks[d].echo.store(v);}
 void master(float v){master_.store(std::clamp(v,0.f,1.f));}
 void cross(float v){cross_.store(std::clamp(v,0.f,1.f));}
 void startRec(){recRead_.store(0);recWrite_.store(0);recording_.store(true);}
 void stopRec(){recording_.store(false);}
 int readRec(JNIEnv*e,jfloatArray dst,int maxFrames){
  if(!dst||maxFrames<=0)return 0;uint64_t r=recRead_.load(),w=recWrite_.load(std::memory_order_acquire);
  int n=(int)std::min<uint64_t>(w-r,(uint64_t)maxFrames);if(n<=0)return 0;
  std::vector<jfloat> tmp((size_t)n*2);for(int i=0;i<n;i++){uint64_t p=((r+i)&REC_MASK)*2;tmp[i*2]=record_[p];tmp[i*2+1]=record_[p+1];}
  e->SetFloatArrayRegion(dst,0,n*2,tmp.data());if(e->ExceptionCheck()){e->ExceptionClear();return 0;}
  recRead_.store(r+n,std::memory_order_release);return n;
 }
 oboe::DataCallbackResult onAudioReady(oboe::AudioStream*s,void*data,int32_t nFrames)override{
  auto*out=(float*)data;int outRate=s->getSampleRate();float masterGain=master_.load(),f=cross_.load();
  float lCross=std::cos(f*1.57079632679f),rCross=std::sin(f*1.57079632679f);
  float a=1-std::exp(-2*3.14159265f*220/outRate),b=1-std::exp(-2*3.14159265f*2600/outRate);
  for(int fidx=0;fidx<nFrames;fidx++){
   float ml=0,mr=0;
   for(int d=0;d<N;d++){
    auto&x=decks[d];uint32_t req=x.clearReq.load(std::memory_order_acquire);if(req!=x.clearAck.load())applyClear(x,req);
    if(!x.playing.load())continue;
    uint64_t rd=x.read.load(),w=x.write.load(std::memory_order_acquire),av=w-rd,off=(uint64_t)x.phase;
    if(av<=off+1)continue;
    float fr=(float)(x.phase-off);uint64_t p0=((rd+off)&MASK)*2,p1=((rd+off+1)&MASK)*2;
    float il=x.pcm[p0]+(x.pcm[p1]-x.pcm[p0])*fr,ir=x.pcm[p0+1]+(x.pcm[p1+1]-x.pcm[p0+1])*fr;
    x.lowL+=a*(il-x.lowL);x.lowR+=a*(ir-x.lowR);x.midL+=b*(il-x.midL);x.midR+=b*(ir-x.midR);
    float pl=x.lowL*x.lowGain.load()+(x.midL-x.lowL)*x.midGain.load()+(il-x.midL)*x.highGain.load();
    float pr=x.lowR*x.lowGain.load()+(x.midR-x.lowR)*x.midGain.load()+(ir-x.midR)*x.highGain.load();
    int ep=x.delayPos*2;float el=x.delay[ep],er=x.delay[ep+1];x.delay[ep]=pl+el*.28f;x.delay[ep+1]=pr+er*.28f;x.delayPos=(x.delayPos+1)%ECHO_FRAMES;
    if(x.echo.load()){pl+=el*.35f;pr+=er*.35f;}
    float side=(d==0||d==2)?lCross:rCross,g=x.gain.load()*masterGain*side;ml+=pl*g;mr+=pr*g;
    x.phase+=((double)x.rate.load()/outRate)*(double)x.speed.load();
   }
   out[fidx*2]=std::clamp(ml,-.98f,.98f);out[fidx*2+1]=std::clamp(mr,-.98f,.98f);
  }
  for(int d=0;d<N;d++){auto&x=decks[d];if(!x.playing.load())continue;uint64_t c=(uint64_t)x.phase;if(c){x.read.fetch_add(c);x.consumed.fetch_add(c);x.phase-=c;}}
  if(recording_.load()){
   uint64_t w=recWrite_.load(),r=recRead_.load(std::memory_order_acquire);int can=(int)std::min<uint64_t>(REC_CAP-(w-r),(uint64_t)nFrames);
   for(int i=0;i<can;i++){uint64_t p=((w+i)&REC_MASK)*2;record_[p]=out[i*2];record_[p+1]=out[i*2+1];}
   recWrite_.store(w+can,std::memory_order_release);
  }
  return oboe::DataCallbackResult::Continue;
 }
private:
 static bool valid(int d){return d>=0&&d<N;}
 static void applyClear(Deck&x,uint32_t req){x.read.store(x.write.load());x.phase=0;x.consumed.store(0);x.lowL=x.lowR=x.midL=x.midR=0;x.delay.fill(0);x.delayPos=0;x.clearAck.store(req,std::memory_order_release);}
 std::array<Deck,N>decks_{};std::shared_ptr<oboe::AudioStream>stream_;
 std::atomic<float>master_{.9f},cross_{.5f};
 std::array<float,REC_CAP*2>record_{};std::atomic<uint64_t>recWrite_{0},recRead_{0};std::atomic<bool>recording_{false};
};
std::mutex mutex_;std::unique_ptr<Engine>engine;
Engine* get(){if(!engine)engine=std::make_unique<Engine>();return engine.get();}
}
#define ENGINE_CALL(name,call) extern "C" JNIEXPORT void JNICALL Java_com_djfulanipro_NativeAudioBridge_##name(JNIEnv*,jobject,jint d,jfloat v){std::lock_guard<std::mutex>l(mutex_);get()->call(d,v);}
extern "C" JNIEXPORT jboolean JNICALL Java_com_djfulanipro_NativeAudioBridge_nativeStartEngine(JNIEnv*,jobject){std::lock_guard<std::mutex>l(mutex_);return get()->start()==oboe::Result::OK?JNI_TRUE:JNI_FALSE;}
extern "C" JNIEXPORT void JNICALL Java_com_djfulanipro_NativeAudioBridge_nativeStopEngine(JNIEnv*,jobject){std::lock_guard<std::mutex>l(mutex_);if(engine)engine->stop();}
extern "C" JNIEXPORT jint JNICALL Java_com_djfulanipro_NativeAudioBridge_nativePushPcm(JNIEnv*e,jobject,jint d,jfloatArray a,jint o,jint n){std::lock_guard<std::mutex>l(mutex_);return get()->push(d,e,a,o,n);}
extern "C" JNIEXPORT void JNICALL Java_com_djfulanipro_NativeAudioBridge_nativeClearDeck(JNIEnv*,jobject,jint d){std::lock_guard<std::mutex>l(mutex_);get()->clear(d);}
extern "C" JNIEXPORT void JNICALL Java_com_djfulanipro_NativeAudioBridge_nativeSetDeckSourceInfo(JNIEnv*,jobject,jint d,jint r,jlong dur,jlong start){std::lock_guard<std::mutex>l(mutex_);get()->info(d,r,dur,start);}
extern "C" JNIEXPORT jlong JNICALL Java_com_djfulanipro_NativeAudioBridge_nativeGetDeckPositionMs(JNIEnv*,jobject,jint d){std::lock_guard<std::mutex>l(mutex_);return get()->position(d);}
extern "C" JNIEXPORT jint JNICALL Java_com_djfulanipro_NativeAudioBridge_nativeGetOutputSampleRate(JNIEnv*,jobject){std::lock_guard<std::mutex>l(mutex_);return get()->sampleRate();}
extern "C" JNIEXPORT jint JNICALL Java_com_djfulanipro_NativeAudioBridge_nativeGetBufferedFrames(JNIEnv*,jobject,jint d){std::lock_guard<std::mutex>l(mutex_);return get()->buffered(d);}
extern "C" JNIEXPORT void JNICALL Java_com_djfulanipro_NativeAudioBridge_nativeSetDeckPlaying(JNIEnv*,jobject,jint d,jboolean v){std::lock_guard<std::mutex>l(mutex_);get()->playing(d,v==JNI_TRUE);}
ENGINE_CALL(nativeSetDeckGain,gain)
ENGINE_CALL(nativeSetDeckSpeed,speed)
extern "C" JNIEXPORT void JNICALL Java_com_djfulanipro_NativeAudioBridge_nativeSetDeckEq(JNIEnv*,jobject,jint d,jfloat lo,jfloat mi,jfloat hi){std::lock_guard<std::mutex>l(mutex_);get()->eq(d,lo,mi,hi);}
extern "C" JNIEXPORT void JNICALL Java_com_djfulanipro_NativeAudioBridge_nativeSetDeckEcho(JNIEnv*,jobject,jint d,jboolean v){std::lock_guard<std::mutex>l(mutex_);get()->echo(d,v==JNI_TRUE);}
extern "C" JNIEXPORT void JNICALL Java_com_djfulanipro_NativeAudioBridge_nativeSetMasterGain(JNIEnv*,jobject,jfloat v){std::lock_guard<std::mutex>l(mutex_);get()->master(v);}
extern "C" JNIEXPORT void JNICALL Java_com_djfulanipro_NativeAudioBridge_nativeSetCrossfader(JNIEnv*,jobject,jfloat v){std::lock_guard<std::mutex>l(mutex_);get()->cross(v);}
extern "C" JNIEXPORT void JNICALL Java_com_djfulanipro_NativeAudioBridge_nativeStartRecording(JNIEnv*,jobject){std::lock_guard<std::mutex>l(mutex_);get()->startRec();}
extern "C" JNIEXPORT void JNICALL Java_com_djfulanipro_NativeAudioBridge_nativeStopRecording(JNIEnv*,jobject){std::lock_guard<std::mutex>l(mutex_);get()->stopRec();}
extern "C" JNIEXPORT jint JNICALL Java_com_djfulanipro_NativeAudioBridge_nativeReadRecording(JNIEnv*e,jobject,jfloatArray a,jint n){std::lock_guard<std::mutex>l(mutex_);return get()->readRec(e,a,n);}
