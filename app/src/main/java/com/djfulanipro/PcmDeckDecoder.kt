package com.djfulanipro

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

internal class PcmDeckDecoder(
    private val context: Context,
    private val deckIndex: Int,
    private val uri: Uri,
    private val requestedStartMs: Long,
    private val onReady: (Long, Long) -> Unit,
    private val onFinished: () -> Unit,
    private val onError: (String) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread({ decodeLoop() }, "DJ-FULANI-deck-$deckIndex").apply {
            priority = Thread.NORM_PRIORITY
            start()
        }
    }
    fun stop() {
        running.set(false)
        worker?.interrupt()
        try { worker?.join(500) } catch (_: InterruptedException) { }
        worker = null
    }
    private fun decodeLoop() {
        val extractor=MediaExtractor(); var codec:MediaCodec?=null
        try {
            extractor.setDataSource(context,uri,null)
            var track=-1; var inputFormat:MediaFormat?=null
            for(i in 0 until extractor.trackCount){
                val f=extractor.getTrackFormat(i);val mime=f.getString(MediaFormat.KEY_MIME)?:continue
                if(mime.startsWith("audio/")){track=i;inputFormat=f;break}
            }
            if(track<0||inputFormat==null){onError("No audio stream found.");return}
            extractor.selectTrack(track)
            if(requestedStartMs>0)extractor.seekTo(requestedStartMs*1000L,MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val actualStart=max(0L,extractor.sampleTime/1000L)
            val mime=inputFormat.getString(MediaFormat.KEY_MIME)?:throw IllegalStateException("Missing MIME")
            codec=MediaCodec.createDecoderByType(mime);codec.configure(inputFormat,null,null,0);codec.start()
            val duration=if(inputFormat.containsKey(MediaFormat.KEY_DURATION))inputFormat.getLong(MediaFormat.KEY_DURATION)/1000L else 0L
            var rate=inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels=inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var encoding=AudioFormat.ENCODING_PCM_16BIT
            NativeAudioBridge.nativeSetDeckSourceInfo(deckIndex,rate,duration,actualStart)
            onReady(duration,actualStart)
            var inputDone=false;var outputDone=false;val info=MediaCodec.BufferInfo()
            while(running.get()&&!outputDone){
                if(!inputDone){
                    val inputIndex=codec.dequeueInputBuffer(5000)
                    if(inputIndex>=0){
                        val buf=codec.getInputBuffer(inputIndex)?:throw IllegalStateException("No codec input buffer")
                        buf.clear();val size=extractor.readSampleData(buf,0)
                        if(size<0){codec.queueInputBuffer(inputIndex,0,0,0L,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true}
                        else{val pts=extractor.sampleTime.coerceAtLeast(0L);codec.queueInputBuffer(inputIndex,0,size,pts,0);extractor.advance()}
                    }
                }
                when(val outIndex=codec.dequeueOutputBuffer(info,5000)){
                    MediaCodec.INFO_TRY_AGAIN_LATER->Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED->{
                        val fmt=codec.outputFormat
                        if(fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE))rate=fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if(fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT))channels=fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        if(fmt.containsKey(MediaFormat.KEY_PCM_ENCODING))encoding=fmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        NativeAudioBridge.nativeSetDeckSourceInfo(deckIndex,rate,duration,actualStart)
                    }
                    else->if(outIndex>=0){
                        val out=codec.getOutputBuffer(outIndex)
                        if(out!=null&&info.size>0&&running.get()){
                            out.position(info.offset);out.limit(info.offset+info.size)
                            val pcm=out.slice().order(ByteOrder.LITTLE_ENDIAN)
                            val bytes=if(encoding==AudioFormat.ENCODING_PCM_FLOAT)4 else 2
                            val frames=pcm.remaining()/(bytes*channels)
                            if(frames>0){
                                val stereo=FloatArray(frames*2)
                                for(frame in 0 until frames){
                                    val base=frame*channels
                                    val left=read(pcm,base,encoding)
                                    val right=if(channels==1)left else read(pcm,base+1,encoding)
                                    stereo[frame*2]=left;stereo[frame*2+1]=right
                                }
                                var sent=0
                                while(running.get()&&sent<frames){
                                    val n=NativeAudioBridge.nativePushPcm(deckIndex,stereo,sent*2,frames-sent)
                                    if(n>0)sent+=n else try{Thread.sleep(2)}catch(_:InterruptedException){}
                                }
                            }
                        }
                        outputDone=(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0
                        codec.releaseOutputBuffer(outIndex,false)
                    }
                }
            }
            if(running.get()&&outputDone)onFinished()
        }catch(e:Exception){if(running.get())onError(e.message?:"Audio decoder failed.")}
        finally{
            running.set(false)
            try{codec?.stop()}catch(_:Exception){}
            try{codec?.release()}catch(_:Exception){}
            try{extractor.release()}catch(_:Exception){}
        }
    }
    private fun read(buf:java.nio.ByteBuffer,index:Int,encoding:Int):Float =
        if(encoding==AudioFormat.ENCODING_PCM_FLOAT)buf.getFloat(index*4).coerceIn(-1f,1f)
        else buf.getShort(index*2)/32768f
}
