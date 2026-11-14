package com.example.douyinexport;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.*;

/** Private, atomic, crash-resistant progress + partial transcript checkpoint. */
public final class TranscriptionStore {
    private static final String NAME="background_asr_checkpoint.json";
    private TranscriptionStore(){}

    private static AtomicFile storage(Context context){
        return new AtomicFile(new File(context.getFilesDir(),NAME));
    }
    public static synchronized JSONObject read(Context context){
        AtomicFile file=storage(context);
        try(InputStream in=file.openRead()){
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            byte[] buffer=new byte[8192];int n;
            while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
            return new JSONObject(out.toString("UTF-8"));
        }catch(Exception e){
            JSONObject empty=new JSONObject();
            try{empty.put("state","idle");empty.put("message","尚未开始后台识别");}
            catch(Exception ignored){}
            return empty;
        }
    }
    public static synchronized void save(Context context,String state,String message,
            String transcript,String subtitles,String stats,int segments){
        AtomicFile file=storage(context);
        FileOutputStream out=null;
        try{
            JSONObject data=new JSONObject();
            data.put("state",state);
            data.put("message",message);
            data.put("transcript",transcript);
            data.put("subtitles",subtitles);
            data.put("stats",stats);
            data.put("segments",segments);
            data.put("updatedAt",System.currentTimeMillis());
            out=file.startWrite();
            out.write(data.toString().getBytes("UTF-8"));
            file.finishWrite(out);
        }catch(Exception e){
            if(out!=null)file.failWrite(out);
            android.util.Log.e("Zhiyin-ASR","Failed to save incremental transcript",e);
        }
    }
    /** If Android killed our process, keep completed segments rather than claiming it is running. */
    public static synchronized JSONObject snapshot(Context context,boolean serviceRunning){
        JSONObject value=read(context);
        if(!serviceRunning&&"running".equals(value.optString("state"))){
            save(context,"interrupted","上次识别被系统中断，已保存识别完成的片段",
                value.optString("transcript"),value.optString("subtitles"),
                value.optString("stats"),value.optInt("segments"));
            return read(context);
        }
        return value;
    }
}
