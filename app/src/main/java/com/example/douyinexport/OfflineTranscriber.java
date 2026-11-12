package com.example.douyinexport;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

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

/**
 * Fully on-device Mandarin ASR. The packaged Vosk model is copied to private
 * storage on the first run; video/audio is decoded with Android MediaCodec
 * and resampled into the required 16kHz mono PCM stream.
 */
public final class OfflineTranscriber {
    public interface Listener {
        void progress(String text);
        boolean cancelled();
    }
    public static final class Result {
        public final String text;
        public final String subtitles;
        public final int segments;
        Result(String text, String subtitles, int segments) {
            this.text=text;
            this.subtitles=subtitles;
            this.segments=segments;
        }
    }
    private static final int TARGET_RATE=16000;
    private static final String ASSET_MODEL="model-cn";
    private OfflineTranscriber() {}

    private static void ensureRunning(Listener l) throws InterruptedIOException {
        if(l.cancelled()) throw new InterruptedIOException("识别已取消");
    }

    private static void removeRecursively(File f) {
        if(f.isDirectory()){
            File[] children=f.listFiles();
            if(children!=null) for(File child:children)removeRecursively(child);
        }
        f.delete();
    }

    private static void copyAssets(AssetManager assets, String path, File dst, Listener listener) throws Exception {
        ensureRunning(listener);
        String[] entries=assets.list(path);
        if(entries!=null&&entries.length>0){
            if(!dst.exists()&&!dst.mkdirs()) throw new IOException("无法创建模型目录");
            for(String entry:entries)copyAssets(assets,path+"/"+entry,new File(dst,entry),listener);
        }else{
            File parent=dst.getParentFile();
            if(parent!=null&&!parent.exists()&&!parent.mkdirs())throw new IOException("无法创建目录");
            try(InputStream in=assets.open(path);OutputStream out=new FileOutputStream(dst)){
                byte[] buf=new byte[65536];int size;
                while((size=in.read(buf))!=-1){
                    ensureRunning(listener);
                    out.write(buf,0,size);
                }
            }
        }
    }

    private static File ensureModel(Context context,Listener listener) throws Exception {
        File dir=new File(context.getFilesDir(),"asr/model-cn");
        File marker=new File(dir,".ready");
        if(marker.exists())return dir;
        listener.progress("首次使用：正在安装内置中文识别模型，请确保有足够存储空间……");
        if(dir.exists())removeRecursively(dir);
        try{
            copyAssets(context.getAssets(),ASSET_MODEL,dir,listener);
            if(!new File(dir,"am").isDirectory()||!new File(dir,"conf").isDirectory()){
                throw new IOException("APK 中未找到完整中文识别模型，请重新安装完整版");
            }
            if(!marker.createNewFile())throw new IOException("无法完成模型安装");
            return dir;
        }catch(Exception e){
            removeRecursively(dir);
            throw e;
        }
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
    private static void addResult(List<Segment> segments,String json,long samplesProcessed) {
        try{
            JSONObject result=new JSONObject(json);
            String t=normalize(result.optString("text",""));
            if(t.isEmpty())return;
            JSONArray words=result.optJSONArray("result");
            long s=Math.max(0,(samplesProcessed*1000L/TARGET_RATE)-3000),e=s+1800;
            if(words!=null&&words.length()>0){
                JSONObject first=words.optJSONObject(0),last=words.optJSONObject(words.length()-1);
                if(first!=null)s=Math.max(0,Math.round(first.optDouble("start",0)*1000));
                if(last!=null)e=Math.max(s+200,Math.round(last.optDouble("end",0)*1000));
            }
            segments.add(new Segment(t,s,e));
        }catch(Exception ignored){}
    }

    private static final class Resampler {
        final Recognizer recognizer;
        final List<Segment> segments;
        final int rate;
        long cursor=0,produced=0;
        double next=0;
        float previous=0;
        final byte[] block=new byte[8192];
        int used=0;
        Resampler(Recognizer recognizer,List<Segment> segments,int sourceRate){
            this.recognizer=recognizer;this.segments=segments;rate=sourceRate;
        }
        void append(float current){
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
        void emit(float sample){
            int v=Math.round(Math.max(-1f,Math.min(1f,sample))*32767f);
            block[used++]=(byte)(v&255);
            block[used++]=(byte)((v>>8)&255);
            produced++;
            if(used>=block.length)flush();
        }
        void flush(){
            if(used==0)return;
            if(recognizer.acceptWaveForm(block,used))
                addResult(segments,recognizer.getResult(),produced);
            used=0;
        }
        void finish(){
            flush();
            addResult(segments,recognizer.getFinalResult(),produced);
        }
    }

    private static void addPcm(ByteBuffer data,int channels,int encoding,Resampler rs) throws IOException{
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
    private static void decode(File media,Recognizer recog,List<Segment> segments,Listener listener) throws Exception{
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
                    rs=new Resampler(recog,segments,sampleRate);
                    listener.progress("中文离线识别已开始："+sampleRate+"Hz、"+channels+"声道");
                }else if(outId>=0){
                    try{
                        if(rs==null){
                            MediaFormat f=decoder.getOutputFormat();
                            sampleRate=f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                            channels=f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                            encoding=f.containsKey(MediaFormat.KEY_PCM_ENCODING)?
                                    f.getInteger(MediaFormat.KEY_PCM_ENCODING):AudioFormat.ENCODING_PCM_16BIT;
                            rs=new Resampler(recog,segments,sampleRate);
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
        }finally{
            if(decoder!=null){
                if(decoderStarted)try{decoder.stop();}catch(Exception ignored){}
                decoder.release();
            }
            extractor.release();
        }
    }

    public static Result transcribe(Context context,File file,Listener listener)throws Exception{
        if(file==null||!file.isFile())throw new IOException("请先导入或下载视频/音频");
        File modelDir=ensureModel(context,listener);
        ensureRunning(listener);
        listener.progress("正在加载中文语音模型（首次可能较慢）……");
        List<Segment> segments=new ArrayList<>();
        try(Model model=new Model(modelDir.getAbsolutePath());
            Recognizer rec=new Recognizer(model,TARGET_RATE)){
            rec.setWords(true);
            decode(file,rec,segments,listener);
        }
        ensureRunning(listener);
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
        return new Result(txt.toString(),srt.toString(),id);
    }
}
