package com.example.douyinexport;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Android MediaCodec to 16-kHz mono PCM; original v0.7 speech splitting is unchanged. */
public final class OfflineTranscriber {
    public interface Listener {
        void progress(String text);
        boolean cancelled();
    }
    public static final class Result {
        public final String text;
        public final String subtitles;
        public final int segments;
        public final String performance;
        Result(String text, String subtitles, int segments, String performance) {
            this.text=text;
            this.subtitles=subtitles;
            this.segments=segments;
            this.performance=performance;
        }
    }
    private static final int TARGET_RATE=16000;
    private OfflineTranscriber() {}

    private static final class SampleReady extends Exception {
        final float[] samples;
        SampleReady(float[] samples){this.samples=samples;}
    }
    private static final class DecodeStats {
        long inferenceNs;
        int chunks;
        long fullNs;
    }
    /**
     * For opt-in thread benchmark only; the same first 8 seconds of decoded
     * PCM is given to every thread count, without modifying normal splitting.
     */
    public static float[] sampleForBenchmark(File file, Listener listener) throws Exception {
        try{
            decode(file,null,new ArrayList<>(),listener);
        }catch(SampleReady sample){
            return sample.samples;
        }
        throw new IOException("音频过短，无法获得用于测速的片段（建议至少 8 秒）");
    }

    private static void ensureRunning(Listener l) throws InterruptedIOException {
        if(l.cancelled()) throw new InterruptedIOException("识别已取消");
    }

    private static String normalize(String input) {
        String s=input==null?"":input.trim().replaceAll("\\s+"," ");
        s=s.replaceAll("(?<=\\p{IsHan})\\s+(?=\\p{IsHan})","");
        s=s.replaceAll("(?<=\\p{IsHan})\\s+(?=[，。！？；：])","");
        return s;
    }
    private static String punctuation(String s){
        if(s.isEmpty())return s;
        if(s.endsWith("。")||s.endsWith("？")||s.endsWith("！")||s.endsWith(".")||s.endsWith("?")||s.endsWith("!"))return s;
        return s+"。";
    }
    private static String time(long ms) {
        if(ms<0)ms=0;
        long h=ms/3600000,m=(ms/60000)%60,s=(ms/1000)%60,milli=ms%1000;
        return String.format(Locale.US,"%02d:%02d:%02d,%03d",h,m,s,milli);
    }
    private static final class Segment {
        final String text;
        final long startMs,endMs;
        Segment(String t,long s,long e){text=t;startMs=s;endMs=Math.max(s+200,e);}
    }
    private static final class Resampler {
        final Qwen3AsrEngine engine;
        final List<Segment> segments;
        final Listener listener;
        final int rate;
        long inferenceNs=0;
        long cursor=0,produced=0,chunkStart=0;
        double next=0;
        float previous=0;
        // Speech is decoded in a bounded buffer; no huge in-memory WAV allocation.
        final float[] block=new float[TARGET_RATE*22];
        int used=0,frameSamples=0,quietFrames=0;
        float frameSquares=0f,peakEnergy=0.013f;
        Resampler(Qwen3AsrEngine engine,List<Segment> segments,int sourceRate,Listener listener){
            this.engine=engine;this.segments=segments;this.rate=sourceRate;this.listener=listener;
        }
        void append(float current)throws Exception{
            if(cursor==0){
                emit(current);
                next=(double)rate/TARGET_RATE;
            }else{
                double step=(double)rate/TARGET_RATE;
                while(next<=cursor){
                    float alpha=(float)(next-(cursor-1));
                    if(alpha<0)alpha=0;
                    if(alpha>1)alpha=1;
                    emit(previous+(current-previous)*alpha);
                    next+=step;
                }
            }
            previous=current;
            cursor++;
        }
        void emit(float value)throws Exception{
            float v=Math.max(-1.0f,Math.min(1.0f,value));
            block[used++]=v;
            produced++;
            if(engine==null){
                if(used>=TARGET_RATE*8)
                    throw new SampleReady(java.util.Arrays.copyOf(block,used));
                return;
            }
            frameSquares+=v*v;frameSamples++;
            if(frameSamples>=2560){
                float rms=(float)Math.sqrt(frameSquares/frameSamples);
                peakEnergy=Math.max(rms,peakEnergy*0.992f);
                if(rms<Math.max(0.006f,peakEnergy*0.19f))quietFrames++;
                else quietFrames=0;
                frameSamples=0;frameSquares=0f;
                if(used>=TARGET_RATE*5&&quietFrames>=3){flush();return;}
            }
            // Force a boundary on speech with no pauses, before model length limits.
            if(used>=TARGET_RATE*19)flush();
        }
        void flush()throws Exception{
            ensureRunning(listener);
            if(used==0)return;
            if(engine==null){
                if(used>=TARGET_RATE*2)throw new SampleReady(java.util.Arrays.copyOf(block,used));
                return;
            }
            int count=used;
            float sum=0;
            for(int i=0;i<count;i++)sum+=block[i]*block[i];
            float rms=(float)Math.sqrt(sum/Math.max(1,count));
            long start=chunkStart, end=chunkStart+count;
            if(count>=TARGET_RATE/2&&rms>=0.0012f){
                listener.progress("Qwen3-ASR 识别中：已处理 "+(start/TARGET_RATE)+" 秒，正在识别下一段……");
                float[] clip=java.util.Arrays.copyOf(block,count);
                long startNs=android.os.SystemClock.elapsedRealtimeNanos();
                String text;
                try{text=normalize(engine.recognize(clip));}
                finally{inferenceNs+=android.os.SystemClock.elapsedRealtimeNanos()-startNs;}
                if(!text.isEmpty())segments.add(new Segment(text,
                    start*1000L/TARGET_RATE,end*1000L/TARGET_RATE));
            }
            used=0;chunkStart=end;frameSamples=0;frameSquares=0f;quietFrames=0;peakEnergy=0.013f;
        }
        void finish()throws Exception{flush();}
    }

    private static void addPcm(ByteBuffer data,int channels,int encoding,Resampler rs) throws Exception{
        data.order(ByteOrder.LITTLE_ENDIAN);
        final boolean floating=(encoding==AudioFormat.ENCODING_PCM_FLOAT);
        final int frameBytes=(floating?4:2)*channels;
        if(frameBytes<=0||channels>8||data.remaining()%frameBytes!=0)
            throw new IOException("音频 PCM 格式不支持或声道数据不完整");
        while(data.remaining()>=frameBytes){
            float mono=0;
            for(int ch=0;ch<channels;ch++){
                mono+=floating?data.getFloat():data.getShort()/32768.0f;
            }
            rs.append(mono/channels);
        }
    }
    private static DecodeStats decode(File media,Qwen3AsrEngine recog,List<Segment> segments,Listener listener) throws Exception{
        long overallStart=android.os.SystemClock.elapsedRealtimeNanos();
        DecodeStats stats=new DecodeStats();
        MediaExtractor extractor=new MediaExtractor();
        MediaCodec decoder=null;
        boolean decoderStarted=false;
        try{
            extractor.setDataSource(media.getAbsolutePath());
            int index=-1;
            for(int i=0;i<extractor.getTrackCount();i++){
                MediaFormat f=extractor.getTrackFormat(i);
                String mime=f.getString(MediaFormat.KEY_MIME);
                if(mime!=null&&mime.startsWith("audio/")){index=i;break;}
            }
            if(index<0)throw new IOException("视频没有可识别的音轨");
            extractor.selectTrack(index);
            MediaFormat format=extractor.getTrackFormat(index);
            String mime=format.getString(MediaFormat.KEY_MIME);
            long duration=format.containsKey(MediaFormat.KEY_DURATION)?format.getLong(MediaFormat.KEY_DURATION):-1;
            decoder=MediaCodec.createDecoderByType(mime);
            decoder.configure(format,null,null,0);
            decoder.start();decoderStarted=true;
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            int sampleRate=0,channels=0,encoding=AudioFormat.ENCODING_PCM_16BIT;
            Resampler rs=null;
            boolean inputEnd=false,outputEnd=false;
            long lastProgressSec=-10;
            long inputCount=0;
            while(!outputEnd){
                ensureRunning(listener);
                if(!inputEnd){
                    int inId=decoder.dequeueInputBuffer(10000);
                    if(inId>=0){
                        ByteBuffer input=decoder.getInputBuffer(inId);
                        if(input==null)throw new IOException("音频解码输入缓冲区不可用");
                        input.clear();
                        int n=extractor.readSampleData(input,0);
                        if(n<0){
                            decoder.queueInputBuffer(inId,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEnd=true;
                        }else{
                            decoder.queueInputBuffer(inId,0,n,Math.max(0,extractor.getSampleTime()),0);
                            inputCount++;
                            extractor.advance();
                        }
                    }
                }
                int outId=decoder.dequeueOutputBuffer(info,10000);
                if(outId==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                    MediaFormat f=decoder.getOutputFormat();
                    sampleRate=f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels=f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    encoding=f.containsKey(MediaFormat.KEY_PCM_ENCODING)?
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING):AudioFormat.ENCODING_PCM_16BIT;
                    if(encoding!=AudioFormat.ENCODING_PCM_16BIT&&encoding!=AudioFormat.ENCODING_PCM_FLOAT)
                        throw new IOException("不支持的解码音频位深："+encoding);
                    if(sampleRate<8000||sampleRate>192000)throw new IOException("不支持的采样率："+sampleRate);
                    rs=new Resampler(recog,segments,sampleRate,listener);
                    listener.progress("Qwen3-ASR 高精度离线识别已开始："+sampleRate+"Hz、"+channels+"声道");
                }else if(outId>=0){
                    try{
                        if(rs==null){
                            MediaFormat f=decoder.getOutputFormat();
                            sampleRate=f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                            channels=f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                            encoding=f.containsKey(MediaFormat.KEY_PCM_ENCODING)?
                                    f.getInteger(MediaFormat.KEY_PCM_ENCODING):AudioFormat.ENCODING_PCM_16BIT;
                            rs=new Resampler(recog,segments,sampleRate,listener);
                        }
                        if(info.size>0){
                            ByteBuffer data=decoder.getOutputBuffer(outId);
                            if(data==null)throw new IOException("音频解码数据不可用");
                            data.position(info.offset);data.limit(info.offset+info.size);
                            addPcm(data.slice(),channels,encoding,rs);
                        }
                        long sec=(rs.produced/TARGET_RATE);
                        if(sec-lastProgressSec>=3){
                            long percent=duration>0?
                                Math.min(99,rs.produced*1000000L*100L/(TARGET_RATE*duration)):-1;
                            String status="离线识别中："+(rs.produced/TARGET_RATE)+" 秒音频";
                            if(percent>=0)status+="（约 "+percent+"%）";
                            status+="，已识别 "+segments.size()+" 段";
                            listener.progress(status);
                            lastProgressSec=sec;
                        }
                        outputEnd=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                    }finally{decoder.releaseOutputBuffer(outId,false);}
                }
            }
            if(rs==null)throw new IOException("无法解码音轨");
            rs.finish();
            stats.inferenceNs=rs.inferenceNs;
            stats.chunks=segments.size();
        }finally{
            if(decoder!=null){
                if(decoderStarted)try{decoder.stop();}catch(Exception ignored){}
                decoder.release();
            }
            extractor.release();
        }
        stats.fullNs=android.os.SystemClock.elapsedRealtimeNanos()-overallStart;
        return stats;
    }

    public static Result transcribe(Context context,File file,Listener listener)throws Exception{
        if(file==null||!file.isFile())throw new IOException("请先导入或下载视频/音频");
        if(!QwenModelManager.installed(context))throw new IOException("请先下载安装 Qwen3-ASR-0.6B INT8 模型");
        File modelDir=QwenModelManager.modelDir(context);
        ensureRunning(listener);
        int threads=AsrPerformanceTuner.selectedThreads(context);
        long totalStart=android.os.SystemClock.elapsedRealtimeNanos();
        listener.progress("Qwen3-ASR-0.6B INT8 · "+threads+" CPU 线程，准备模型…");
        List<Segment> segments=new ArrayList<>();
        long modelNs,fullNs,inferenceNs;
        boolean reused;
        long modelStart=android.os.SystemClock.elapsedRealtimeNanos();
        try(QwenEnginePool.Lease lease=QwenEnginePool.obtain(modelDir.getAbsolutePath(),threads)){
            reused=lease.reused;
            modelNs=android.os.SystemClock.elapsedRealtimeNanos()-modelStart;
            DecodeStats timing=decode(file,lease.engine,segments,listener);
            fullNs=timing.fullNs;
            inferenceNs=timing.inferenceNs;
        }
        ensureRunning(listener);
        long totalNs=android.os.SystemClock.elapsedRealtimeNanos()-totalStart;
        long pipelineNs=Math.max(0,fullNs-inferenceNs);
        String stats=String.format(Locale.CHINA,
            "CPU %d 线程｜%d 段｜总耗时 %.2f 秒｜模型%s %.2f 秒｜AI 推理 %.2f 秒｜解码与分段约 %.2f 秒",
            threads,segments.size(),totalNs/1e9,reused?"复用":"加载",
            modelNs/1e9,inferenceNs/1e9,pipelineNs/1e9);
        listener.progress(stats);
        if(segments.isEmpty())throw new IOException("没有识别到清晰语音，请确认音轨包含中文人声");
        StringBuilder txt=new StringBuilder(),srt=new StringBuilder();
        int id=0;
        for(Segment part:segments){
            String line=punctuation(part.text);
            if(txt.length()>0)txt.append("\n\n");
            txt.append(line);
            id++;
            srt.append(id).append('\n')
               .append(time(part.startMs)).append(" --> ").append(time(part.endMs)).append('\n')
               .append(line).append("\n\n");
        }
        return new Result(txt.toString(),srt.toString(),id,stats);
    }
}
