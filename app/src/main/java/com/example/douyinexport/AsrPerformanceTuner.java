package com.example.douyinexport;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;
import android.os.SystemClock;
import java.io.File;
import java.util.Locale;

/**
 * Explicit, on-device opt-in speed test on the SAME decoded sample:
 * compare 2/3/4 CPU threads, require identical output to v0.7's
 * 2-thread baseline and at least 10% measured speed gain before switching.
 *
 * The test incurs model startup costs; it does not run on each transcription.
 */
public final class AsrPerformanceTuner {
    public interface Listener {
        void progress(String message);
        boolean cancelled();
    }

    private static final String PREFS="zhiyin_qwen_perf";
    private AsrPerformanceTuner(){}
    public static int selectedThreads(Context context){
        int n=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getInt("threads",2);
        return n>=2&&n<=4?n:2;
    }
    public static void reset(Context context){
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putInt("threads",2).apply();
        QwenEnginePool.clearIdle();
    }
    private static void check(Listener l)throws java.io.InterruptedIOException{
        if(l.cancelled()||Thread.currentThread().isInterrupted())
            throw new java.io.InterruptedIOException("测速已取消");
    }
    public static String benchmark(Context ctx,File media,Listener listener)throws Exception{
        if(!QwenModelManager.installed(ctx))throw new java.io.IOException("请先安装 Qwen3-ASR 模型");
        if(media==null||!media.isFile())throw new java.io.IOException("请先选择本地音视频或下载 MP4");
        ActivityManager manager=(ActivityManager)ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if(manager!=null){
            ActivityManager.MemoryInfo memory=new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(memory);
            if(memory.lowMemory||memory.availMem<1600L*1024*1024)
                throw new java.io.IOException("系统可用内存偏低，暂不执行多线程测速");
        }
        QwenEnginePool.clearIdle();
        listener.progress("正在解码同一段 8 秒音频，用于公平比较 2/3/4 线程…");
        float[] sample=OfflineTranscriber.sampleForBenchmark(media,new OfflineTranscriber.Listener(){
            @Override public void progress(String p){listener.progress(p);}
            @Override public boolean cancelled(){return listener.cancelled();}
        });
        check(listener);
        String baseline=null;
        long baselineNs=0;
        long bestNs=Long.MAX_VALUE;
        int selected=2;
        StringBuilder report=new StringBuilder("同一段 "+String.format(Locale.ROOT,"%.1f",sample.length/16000.0)+" 秒音频：");
        File root=QwenModelManager.modelDir(ctx);
        for(int threads=2;threads<=4;threads++){
            check(listener);
            listener.progress("正在测试 "+threads+" 线程（重新加载模型，首次测速需更久）…");
            long initStart=SystemClock.elapsedRealtimeNanos();
            String result;long inferenceNs;long initNs;
            try(Qwen3AsrEngine engine=new Qwen3AsrEngine(root.getAbsolutePath(),threads)){
                initNs=SystemClock.elapsedRealtimeNanos()-initStart;
                long t=SystemClock.elapsedRealtimeNanos();
                result=engine.recognize(sample);
                inferenceNs=SystemClock.elapsedRealtimeNanos()-t;
            }
            check(listener);
            if(threads==2){
                baseline=result;
                baselineNs=inferenceNs;
                bestNs=inferenceNs;
                if(baseline==null||baseline.trim().length()==0)
                    throw new java.io.IOException("采样音频未识别出文字，请选择有人声的视频测速");
            }
            boolean identical=baseline.equals(result);
            if(threads!=2&&identical&&inferenceNs<bestNs
                &&inferenceNs*10L<=baselineNs*9L){
                bestNs=inferenceNs;selected=threads;
            }
            report.append("\n").append(threads).append("线程：")
                .append(String.format(Locale.ROOT,"%.2f",inferenceNs/1e9)).append("秒")
                .append("（加载 ").append(String.format(Locale.ROOT,"%.2f",initNs/1e9)).append("秒）")
                .append(identical?"，文字一致":"，文字有差异，不采用");
        }
        ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putInt("threads",selected).apply();
        report.append("\n").append("已选择 ").append(selected).append(" 线程");
        report.append(selected==2?"（保留原版高精度基线）":"（采样文字与 2 线程一致，速度更快）");
        report.append("。本测试为单片段测速，不保证整段视频同样提速。");
        return report.toString();
    }
}
