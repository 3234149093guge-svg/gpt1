package com.example.douyinexport;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.io.File;
import java.io.InterruptedIOException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * User-initiated, self-contained Android foreground service for processing
 * existing local audio/video. No microphone access, no network ASR.
 *
 * Activity destruction never cancels the job. Every completed segment is
 * durably checkpointed before the next segment starts.
 */
public final class TranscriptionService extends Service {
    public static final String ACTION_START="com.example.douyinexport.asr.START";
    public static final String ACTION_CANCEL="com.example.douyinexport.asr.CANCEL";
    public static final String ACTION_UPDATED="com.example.douyinexport.asr.UPDATED";
    public static final String EXTRA_FILE="media_path";
    private static final String CHANNEL="zhiyin_asr_processing";
    private static final int NOTIFICATION_ID=7402;
    private static final AtomicBoolean ACTIVE=new AtomicBoolean(false);

    private final AtomicBoolean cancelled=new AtomicBoolean(false);
    private final Object segmentsLock=new Object();
    private final StringBuilder partialText=new StringBuilder();
    private final StringBuilder partialSubtitles=new StringBuilder();
    private volatile String lastMessage="准备开始识别…",lastStats="";
    private volatile int segmentCount=0;
    private Thread worker;

    public static boolean isRunning(){return ACTIVE.get();}

    @Override public void onCreate(){
        super.onCreate();
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(manager!=null&&Build.VERSION.SDK_INT>=26){
            NotificationChannel ch=new NotificationChannel(CHANNEL,"知音 · 后台语音识别",
                NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("识别本地音视频时持续显示进度，并可停止任务");
            manager.createNotificationChannel(ch);
        }
    }

    @Override public int onStartCommand(Intent request,int flags,int startId){
        if(request==null){stopSelfResult(startId);return START_NOT_STICKY;}
        String action=request.getAction();
        if(ACTION_CANCEL.equals(action)){
            cancelled.set(true);
            if(ACTIVE.get())updateNotification("正在停止识别，已完成的文字将保留…");
            else stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        if(!ACTION_START.equals(action)){
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        if(!ACTIVE.compareAndSet(false,true)){
            publish();
            return START_NOT_STICKY;
        }
        cancelled.set(false);
        final String path=request.getStringExtra(EXTRA_FILE);
        if(path==null||path.trim().isEmpty()){
            ACTIVE.set(false);stopSelfResult(startId);return START_NOT_STICKY;
        }
        synchronized(segmentsLock){
            partialText.setLength(0);partialSubtitles.setLength(0);segmentCount=0;
            lastStats="";lastMessage="正在初始化 Qwen3-ASR-0.6B INT8…";
        }
        try{
            Notification init=notification("正在准备本地 AI 转写",true,false);
            if(Build.VERSION.SDK_INT>=29){
                int type=Build.VERSION.SDK_INT>=35
                    ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                    : ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
                startForeground(NOTIFICATION_ID,init,type);
            }else startForeground(NOTIFICATION_ID,init);
        }catch(Exception e){
            ACTIVE.set(false);
            TranscriptionStore.save(this,"failed","无法启动后台服务："+e.getMessage(),
                "","","",0);
            publish();
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        save("running",lastMessage);
        worker=new Thread(()->runTranscription(path,startId),"zhiyin-asr-foreground");
        worker.start();
        return START_NOT_STICKY;
    }

    private void runTranscription(String path,int startId){
        try{
            File file=new File(path);
            if(!file.isFile())throw new java.io.IOException("源音视频文件已不存在，请重新导入");
            if(!QwenModelManager.installed(this))
                throw new java.io.IOException("模型尚未安装，请先安装 Qwen3-ASR-0.6B INT8");
            OfflineTranscriber.Result result=OfflineTranscriber.transcribe(
                this,file,new OfflineTranscriber.Listener(){
                    @Override public void progress(String message){
                        lastMessage=message;
                        save("running",message);
                        updateNotification(message);
                    }
                    @Override public boolean cancelled(){return cancelled.get();}
                    @Override public void onSegment(String text,long begin,long end){
                        synchronized(segmentsLock){
                            segmentCount++;
                            if(partialText.length()>0)partialText.append("\n\n");
                            partialText.append(text);
                            partialSubtitles.append(segmentCount).append("\n")
                                .append(srtTime(begin)).append(" --> ").append(srtTime(end))
                                .append("\n").append(text).append("\n\n");
                            // Atomic checkpoint before advancing to the next clip.
                            save("running","已完成 "+segmentCount+" 段，正在继续…");
                        }
                    }
                });
            if(cancelled.get())throw new InterruptedIOException("识别已停止");
            synchronized(segmentsLock){
                lastStats=result.performance;
                lastMessage="识别完成，共 "+result.segments+" 段，结果已保存";
                TranscriptionStore.save(this,"completed",lastMessage,
                    result.text,result.subtitles,lastStats,result.segments);
            }
            publish();
            showCompletion("识别完成 · 点击查看文字稿");
        }catch(InterruptedIOException e){
            lastMessage="已停止识别，完成的 "+segmentCount+" 段文字已保存";
            save("cancelled",lastMessage);
            showCompletion("识别已停止 · 已保存部分文字");
        }catch(Throwable e){
            lastMessage="转写未完成："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage())
                +"；已保存 "+segmentCount+" 段";
            save("failed",lastMessage);
            showCompletion("转写中断 · 点击查看已保存内容");
        }finally{
            ACTIVE.set(false);
            QwenEnginePool.clearIdle();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelfResult(startId);
        }
    }

    private static String srtTime(long ms){
        if(ms<0)ms=0;
        long h=ms/3600000,m=(ms/60000)%60,s=(ms/1000)%60,x=ms%1000;
        return String.format(Locale.US,"%02d:%02d:%02d,%03d",h,m,s,x);
    }
    private void save(String state,String message){
        synchronized(segmentsLock){
            TranscriptionStore.save(this,state,message,
                partialText.toString(),partialSubtitles.toString(),lastStats,segmentCount);
        }
        publish();
    }
    private void publish(){
        Intent i=new Intent(ACTION_UPDATED);
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }
    private Notification notification(String description,boolean ongoing,boolean finished){
        Intent open=new Intent(this,MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openApp=PendingIntent.getActivity(this,101,open,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder=new Notification.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(finished?"知音 · 转写结果":"知音 · 正在后台转写")
            .setContentText(description)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(finished)
            .setWhen(System.currentTimeMillis());
        if(ongoing){
            Intent stop=new Intent(this,TranscriptionService.class);
            stop.setAction(ACTION_CANCEL);
            PendingIntent cancel=PendingIntent.getService(this,102,stop,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel,"停止识别",cancel);
        }
        return builder.build();
    }
    private void updateNotification(String message){
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(manager!=null)manager.notify(NOTIFICATION_ID,notification(message,true,false));
    }
    private void showCompletion(String message){
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(manager!=null)manager.notify(NOTIFICATION_ID+1,notification(message,false,true));
    }
    @Override public void onTaskRemoved(Intent rootIntent){
        // Deliberately keep processing: service is not bound to the activity task.
        super.onTaskRemoved(rootIntent);
    }
    @Override public void onDestroy(){
        cancelled.set(true);
        super.onDestroy();
    }
    @Override public void onTimeout(int startId,int fgsType){
        cancelled.set(true);
        save("interrupted","系统前台服务时间已到，已保存完成的文字段落");
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
