package com.example.douyinexport;

import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.content.Intent;
import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.net.Uri;
import android.provider.MediaStore;
import android.graphics.Color;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.JavascriptInterface;
import android.webkit.CookieManager;
import android.widget.FrameLayout;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaCodec;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.*;

public class MainActivity extends Activity {
    private static final int PICK=41;
    private static final int PICK_AUDIO=42;
    private static final long LIMIT=500L*1024*1024;
    private FrameLayout frame;
    private WebView ui;
    private volatile String mediaUrl="", sourceUrl="";
    private volatile File video, audio;
    private String incoming="";
    private final java.util.concurrent.atomic.AtomicBoolean parseBusy=new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean recognitionBusy=new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean recognitionCancelled=new java.util.concurrent.atomic.AtomicBoolean(false);
    private final String ua="Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(8,17,33));
        frame=new FrameLayout(this);
        ui=new WebView(this);
        ui.setBackgroundColor(Color.rgb(8,17,33));
        ui.getSettings().setJavaScriptEnabled(true);
        ui.getSettings().setDomStorageEnabled(true);
        ui.getSettings().setAllowFileAccessFromFileURLs(false);
        ui.getSettings().setAllowUniversalAccessFromFileURLs(false);
        ui.addJavascriptInterface(new Bridge(),"App");
        ui.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView w,String url) {
                if(!incoming.isEmpty()){send("link",incoming);incoming="";}
            }
        });
        frame.addView(ui);
        setContentView(frame);
        ui.loadUrl("file:///android_asset/index.html");
        accept(getIntent());
    }
    @Override protected void onNewIntent(Intent i) {super.onNewIntent(i);setIntent(i);accept(i);}
    private void accept(Intent i){
        if(i!=null && Intent.ACTION_SEND.equals(i.getAction())){
            String t=i.getStringExtra(Intent.EXTRA_TEXT);
            if(t!=null){incoming=t;ui.postDelayed(()->{if(!incoming.isEmpty()){send("link",incoming);incoming="";}},800);}
        }
    }
    private void send(String type,String data) {
        runOnUiThread(()->{
            try {
                JSONObject j=new JSONObject();
                j.put("type",type);j.put("text",data);
                if(ui!=null)ui.evaluateJavascript("window.fromAndroid("+j.toString()+")",null);
            } catch(Exception ignored){}
        });
    }
    private void fail(Exception e){send("error",e.getMessage()==null?e.toString():e.getMessage());}
    private void work(Runnable r){new Thread(r).start();}
    public final class Bridge {
        @JavascriptInterface public void paste() {
            runOnUiThread(()->{
                ClipboardManager c=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
                if(c!=null&&c.hasPrimaryClip()){
                    ClipData d=c.getPrimaryClip();
                    if(d!=null&&d.getItemCount()>0)send("link",d.getItemAt(0).coerceToText(MainActivity.this).toString());
                }
            });
        }
        @JavascriptInterface public void resolve(String text){
            if(!parseBusy.compareAndSet(false,true)){send("info","解析任务正在运行，请勿重复点击");return;}
            work(()->{try{resolveLink(text);}finally{parseBusy.set(false);send("parseDone","");}});
        }
        @JavascriptInterface public void download(){work(()->downloadVideo());}
        @JavascriptInterface public void pick(){runOnUiThread(()->{
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("video/*");
            startActivityForResult(i,PICK);
        });}
        @JavascriptInterface public void audio(){work(()->{
            try{File f=extractAudio();String name=name("音频","m4a");save(f,name,"audio/mp4");send("ok","音频已保存："+name);}
            catch(Exception e){fail(e);}
        });}
        @JavascriptInterface public void offlineTranscribe(){work(()->transcribeOffline());}
        @JavascriptInterface public void cancelTranscription(){
            recognitionCancelled.set(true);
            send("info","正在停止本地识别……");
        }
        @JavascriptInterface public void pickAudio(){runOnUiThread(()->{
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("audio/*");
            startActivityForResult(i,PICK_AUDIO);
        });}
        @JavascriptInterface public void saveSrt(String content){work(()->saveTranscriptFile(content,"字幕","srt","application/x-subrip"));}
        @JavascriptInterface public void saveText(String content){work(()->saveTranscriptFile(content,"文字稿","txt","text/plain"));}
    }
    private static String findUrl(String t){
        if(t==null)return "";
        Matcher m=Pattern.compile("https?://[^\\s\"'<>]+").matcher(t);
        if(!m.find())return "";
        return m.group().replaceAll("[，。！；、】）]+$","");
    }
    private static boolean https(String link){
        try{return "https".equalsIgnoreCase(Uri.parse(link).getScheme());}
        catch(Exception e){return false;}
    }
    private static boolean douyin(String link){
        try{
            Uri u=Uri.parse(link);String h=u.getHost();
            return https(link)&&h!=null&&(h.equals("douyin.com")||h.endsWith(".douyin.com")||h.equals("iesdouyin.com")||h.endsWith(".iesdouyin.com"));
        }catch(Exception e){return false;}
    }
    private static boolean media(String u){
        if(u==null)return false;
        String s=u.toLowerCase(Locale.ROOT);
        return s.contains(".mp4")||s.contains("douyinvod.com")||s.contains("/aweme/v1/play/")||s.contains("/video/tos/")||s.contains("/playwm/");
    }
    private HttpURLConnection connect(String url,String accept)throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(16000);c.setReadTimeout(45000);c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent",ua);
        c.setRequestProperty("Accept",accept);
        if(!sourceUrl.isEmpty())c.setRequestProperty("Referer",sourceUrl);
        String cookies=CookieManager.getInstance().getCookie(url);
        if(cookies!=null)c.setRequestProperty("Cookie",cookies);
        return c;
    }
    // Short links often redirect to a browser-rendered page with no plain MP4 URL.
    // Prefer the public mobile share page's embedded video metadata when available.
    private static String videoId(String text) {
        if(text==null)return "";
        Matcher m=Pattern.compile("(?:/video/|/share/video/|[?&](?:modal_id|aweme_id)=)([0-9]{15,22})").matcher(text);
        return m.find()?m.group(1):"";
    }
    // This is a strictly optional compatibility fallback. Keep the classic
    // v0.4 Android request unchanged and try iPhone SSR only after it fails.
    private String getIphoneShareHtml(String url)throws Exception {
        HttpURLConnection c=connect(url,"text/html,application/xhtml+xml,*/*");
        c.setRequestProperty("User-Agent",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1");
        c.setRequestProperty("Referer","https://www.douyin.com/");
        c.setRequestProperty("Accept-Language","zh-CN,zh;q=0.9");
        try{
            int code=c.getResponseCode();
            if(code>=400)throw new IOException("HTTP "+code);
            String target=c.getURL().toString();
            if(douyin(target))sourceUrl=target;
            ByteArrayOutputStream b=new ByteArrayOutputStream();
            try(InputStream in=c.getInputStream()){copy(in,b,6L*1024*1024);}
            return b.toString("UTF-8");
        }finally{c.disconnect();}
    }
    private String getHtml(String url)throws Exception {
        HttpURLConnection c=connect(url,"text/html,application/xhtml+xml,*/*");
        c.setRequestProperty("Accept-Language","zh-CN,zh;q=0.9");
        try{
            int code=c.getResponseCode();
            if(code>=400)throw new IOException("HTTP "+code+"："+Uri.parse(url).getHost());
            String target=c.getURL().toString();
            if(douyin(target))sourceUrl=target;
            ByteArrayOutputStream b=new ByteArrayOutputStream();
            try(InputStream in=c.getInputStream()){copy(in,b,6L*1024*1024);}
            return b.toString("UTF-8");
        }finally{c.disconnect();}
    }
    private static String jsonObjectFollowing(String html,String key) {
        int pos=html.indexOf(key);
        if(pos<0)return null;
        int start=html.indexOf('{',pos+key.length());
        if(start<0)return null;
        boolean quoted=false,escaped=false;int depth=0;
        for(int i=start;i<html.length();i++){
            char c=html.charAt(i);
            if(quoted){
                if(escaped)escaped=false;
                else if(c=='\\')escaped=true;
                else if(c=='"')quoted=false;
            }else if(c=='"')quoted=true;
            else if(c=='{')depth++;
            else if(c=='}'&&--depth==0)return html.substring(start,i+1);
        }
        return null;
    }
    private static String listUrl(JSONObject address) {
        if(address==null)return "";
        org.json.JSONArray urls=address.optJSONArray("url_list");
        if(urls==null)return "";
        for(int i=0;i<urls.length();i++){
            String value=urls.optString(i,"").replace("&amp;","&");
            if(https(value))return value;
        }
        return "";
    }
    private static String fromVideoObject(JSONObject video) {
        if(video==null)return "";
        String s=listUrl(video.optJSONObject("play_addr"));
        if(!s.isEmpty())return s;
        s=listUrl(video.optJSONObject("play_addr_h264"));
        if(!s.isEmpty())return s;
        // Some public pages nest alternate rendition URLs inside bit_rate.
        org.json.JSONArray rates=video.optJSONArray("bit_rate");
        if(rates!=null)for(int i=0;i<rates.length();i++){
            JSONObject rate=rates.optJSONObject(i);
            if(rate!=null){
                s=listUrl(rate.optJSONObject("play_addr"));
                if(!s.isEmpty())return s;
            }
        }
        return "";
    }
    private static String visitMedia(Object obj,int depth){
        if(depth>18||obj==null||obj==JSONObject.NULL)return "";
        if(obj instanceof JSONObject){
            JSONObject item=(JSONObject)obj;
            String s=fromVideoObject(item.optJSONObject("video"));
            if(!s.isEmpty())return s;
            s=fromVideoObject(item);
            if(!s.isEmpty())return s;
            Iterator<String> it=item.keys();
            while(it.hasNext()){
                String k=it.next();
                Object child=item.opt(k);
                if(child instanceof JSONObject||child instanceof org.json.JSONArray){
                    s=visitMedia(child,depth+1);
                    if(!s.isEmpty())return s;
                }
            }
        }else if(obj instanceof org.json.JSONArray){
            org.json.JSONArray a=(org.json.JSONArray)obj;
            for(int i=0;i<a.length();i++){
                String s=visitMedia(a.opt(i),depth+1);
                if(!s.isEmpty())return s;
            }
        }
        return "";
    }
    private static String structuredMedia(String html) {
        String data=jsonObjectFollowing(html,"_ROUTER_DATA");
        if(data!=null)try{
            String result=visitMedia(new JSONObject(data),0);
            if(!result.isEmpty())return result;
        }catch(Exception ignored){}
        // Legacy pages may include URL-encoded JSON in a RENDER_DATA script.
        Matcher m=Pattern.compile("<script[^>]*id=[\"']RENDER_DATA[\"'][^>]*>(.*?)</script>",Pattern.DOTALL|Pattern.CASE_INSENSITIVE).matcher(html);
        if(m.find())try{
            String decoded=java.net.URLDecoder.decode(m.group(1),"UTF-8");
            String result=visitMedia(new JSONObject(decoded),0);
            if(!result.isEmpty())return result;
        }catch(Exception ignored){}
        return "";
    }
    private static String scanDirectVideo(String html){
        Matcher m=Pattern.compile("https?://[^\\s\"'<>]+").matcher(html.replace("\\/","/").replace("&amp;","&"));
        while(m.find()){
            String v=m.group().replaceAll("[,;)]$","");
            if(https(v)&&(v.contains(".mp4")||v.contains("douyinvod.com")))return v;
        }
        return "";
    }
    private void resolveLink(String text){
        try{
            final String link=findUrl(text);
            if(!https(link))throw new IOException("请粘贴有效的 HTTPS 分享链接");
            mediaUrl="";video=null;audio=null;sourceUrl=link;
            if(!douyin(link)){
                if(media(link)){mediaUrl=link;send("media",link);return;}
                throw new IOException("仅支持抖音分享链接或视频直链");
            }
            // Keep v0.4's exact three URL sources and extraction strategy.
            // One tap may perform a second identical pass, rather than requiring
            // repeated manual taps after a fresh installation.
            for(int pass=1;pass<=2;pass++){
                sourceUrl=link;
                send("info","经典版解析 "+pass+"/2：读取分享视频信息……");
                String id=videoId(link),html="";
                try{
                    html=getHtml(link);
                    if(id.isEmpty())id=videoId(sourceUrl);
                    if(id.isEmpty()){
                        Matcher ref=Pattern.compile("(?:video/|aweme_id[=:][\"']?)([0-9]{15,22})").matcher(html);
                        if(ref.find())id=ref.group(1);
                    }
                }catch(Exception e){send("info","分享链接暂未提供视频信息，尝试备用分享地址……");}
                String result=structuredMedia(html);
                if(result.isEmpty()&&!id.isEmpty()){
                    send("info","正在使用 v0.4 的两个备用视频来源……");
                    String[] pages={
                        "https://www.iesdouyin.com/share/video/"+id+"/",
                        "https://www.douyin.com/share/video/"+id+"/"
                    };
                    for(String page:pages){
                        try{
                            String body=getHtml(page);
                            result=structuredMedia(body);
                            if(!result.isEmpty())break;
                            if(result.isEmpty())result=scanDirectVideo(body);
                            if(!result.isEmpty())break;
                        }catch(Exception ignored){}
                    }
                }
                if(result.isEmpty())result=scanDirectVideo(html);
                if(result.isEmpty()&&!id.isEmpty()){
                    // Verified on the supplied video: iPhone public SSR can
                    // include videoInfoRes when Android pages are layout-only.
                    send("info","经典版未找到视频，检查移动分享兼容地址……");
                    try{
                        String mobile=getIphoneShareHtml("https://www.iesdouyin.com/share/video/"+id+"/");
                        result=structuredMedia(mobile);
                        if(result.isEmpty())result=scanDirectVideo(mobile);
                        if(!result.isEmpty()){
                            // A stable referer was used for the validated MP4 sample.
                            sourceUrl="https://www.douyin.com/";
                        }
                    }catch(Exception ignored){}
                }
                if(!result.isEmpty()){
                    mediaUrl=result;
                    send("media",result);
                    return;
                }
                if(pass==1){
                    send("info","第一次没有取得视频地址，正在自动复核一次（不打开网页）……");
                    try{Thread.sleep(900);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
                }
            }
            send("error","经典版解析未获得可下载的视频地址。已在这次点击内自动复核；未打开网页。请检查平台是否返回可用的公开视频详情。");
        }catch(Exception e){fail(e);}
    }
    private static void copy(InputStream in,OutputStream out,long max)throws IOException{
        byte[] b=new byte[65536];long count=0;int n;
        while((n=in.read(b))!=-1){count+=n;if(count>max)throw new IOException("文件超出大小限制");out.write(b,0,n);}
    }
    private void downloadVideo(){
        if(mediaUrl.isEmpty()){send("error","请先解析视频链接或导入本地视频");return;}
        send("info","正在下载 MP4……");
        File f=new File(getCacheDir(),"video_"+System.currentTimeMillis()+".mp4");
        HttpURLConnection c=null;
        try{
            c=connect(mediaUrl,"video/mp4,video/*,*/*");
            if(c.getResponseCode()>=400)throw new IOException("下载 HTTP "+c.getResponseCode());
            String type=c.getContentType();
            if(type!=null&&(type.contains("text/html")||type.contains("application/json")))
                throw new IOException("返回的是网页或验证信息，不是视频");
            try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(f)){copy(in,out,LIMIT);}
            MediaExtractor probe=new MediaExtractor();
            try{probe.setDataSource(f.getAbsolutePath());if(probe.getTrackCount()<1)throw new IOException("无有效媒体轨道");}
            finally{probe.release();}
            video=f;audio=null;
            String n=name("视频","mp4");save(f,n,"video/mp4");send("ready","MP4 已保存："+n+"。现在可以提取音频或转文字稿。");
        }catch(Exception e){f.delete();fail(e);}
        finally{if(c!=null)c.disconnect();}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if((request!=PICK&&request!=PICK_AUDIO)||result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        work(()->{
            try {
                File f=new File(getCacheDir(),"import_"+System.currentTimeMillis()+(request==PICK_AUDIO?".m4a":".mp4"));
                try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(f)){
                    if(in==null)throw new IOException("无法读取视频");
                    copy(in,out,LIMIT);
                }
                MediaExtractor p=new MediaExtractor();
                try{p.setDataSource(f.getAbsolutePath());if(p.getTrackCount()<1)throw new IOException("不是可用的视频");}
                finally{p.release();}
                if(request==PICK_AUDIO){audio=f;video=null;send("ready","本地音频已导入，可以离线生成文字稿和字幕。");}
                else{video=f;audio=null;send("ready","本地视频已导入，可以导出音频或离线生成文字稿。");}
            }catch(Exception e){fail(e);}
        });
    }
    private synchronized File extractAudio()throws Exception{
        if(audio!=null&&audio.exists())return audio;
        if(video==null||!video.isFile())throw new IOException("请先下载或导入视频");
        send("info","正在提取 AAC 音轨……");
        MediaExtractor x=new MediaExtractor();
        MediaMuxer mux=null;boolean started=false;
        File dest=new File(getCacheDir(),"audio_"+System.currentTimeMillis()+".m4a");
        try{
            x.setDataSource(video.getAbsolutePath());
            int index=-1;
            for(int i=0;i<x.getTrackCount();i++){
                MediaFormat f=x.getTrackFormat(i);
                if("audio/mp4a-latm".equals(f.getString(MediaFormat.KEY_MIME))){index=i;break;}
            }
            if(index<0)throw new IOException("当前仅支持包含 AAC 音轨的视频");
            x.selectTrack(index);
            mux=new MediaMuxer(dest.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int track=mux.addTrack(x.getTrackFormat(index));mux.start();started=true;
            ByteBuffer buf=ByteBuffer.allocateDirect(1024*1024);
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            while(true){
                buf.clear();int size=x.readSampleData(buf,0);if(size<0)break;
                info.offset=0;info.size=size;info.presentationTimeUs=Math.max(0,x.getSampleTime());
                info.flags=x.getSampleFlags() & MediaCodec.BUFFER_FLAG_KEY_FRAME;
                mux.writeSampleData(track,buf,info);x.advance();
            }
            mux.stop();started=false;
            if(dest.length()<128)throw new IOException("空音轨");
            audio=dest;return dest;
        }finally{
            x.release();
            if(mux!=null){if(started)try{mux.stop();}catch(Exception ignored){}mux.release();}
        }
    }
    private void saveTranscriptFile(String content,String label,String ext,String mime){
        try{
            if(content==null||content.trim().isEmpty())throw new IOException(label+"为空，请先完成语音识别");
            File f=new File(getCacheDir(),"output_"+System.currentTimeMillis()+"."+ext);
            try(OutputStream o=new FileOutputStream(f)){o.write(content.getBytes(StandardCharsets.UTF_8));}
            String n=name(label,ext);
            save(f,n,mime);
            send("ok",label+"已保存："+n);
        }catch(Exception e){fail(e);}
    }
    private void transcribeOffline(){
        if(!recognitionBusy.compareAndSet(false,true)){
            send("error","识别任务正在运行，请勿重复提交");
            return;
        }
        recognitionCancelled.set(false);
        try{
            File input=video!=null&&video.isFile()?video:audio;
            if(input==null||!input.isFile())throw new IOException("请先下载视频或导入本地视频/音频");
            send("info","正在启动离线中文语音识别……");
            OfflineTranscriber.Result result=OfflineTranscriber.transcribe(this,input,new OfflineTranscriber.Listener(){
                @Override public void progress(String message){send("info",message);}
                @Override public boolean cancelled(){return recognitionCancelled.get()||Thread.currentThread().isInterrupted();}
            });
            send("transcript",result.text);
            send("subtitles",result.subtitles);
            send("ok","本地识别成功："+result.segments+"段，可编辑文字并导出 TXT/SRT。");
        }catch(java.io.InterruptedIOException e){
            send("info","识别已取消");
        }catch(Exception e){fail(e);}
        finally{recognitionBusy.set(false);}
    }
    private static String name(String label,String ext){
        return label+"_"+new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.CHINA).format(new Date())+"."+ext;
    }
    private void save(File source,String n,String mime)throws Exception{
        if(Build.VERSION.SDK_INT>=29){
            ContentValues v=new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME,n);
            v.put(MediaStore.MediaColumns.MIME_TYPE,mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/抖音提取器");
            v.put(MediaStore.MediaColumns.IS_PENDING,1);
            Uri u=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);
            if(u==null)throw new IOException("无法创建下载文件");
            try{
                try(InputStream in=new FileInputStream(source);OutputStream out=getContentResolver().openOutputStream(u)){
                    if(out==null)throw new IOException("无法写入下载目录");copy(in,out,LIMIT+1024);
                }
                v.clear();v.put(MediaStore.MediaColumns.IS_PENDING,0);getContentResolver().update(u,v,null,null);
            }catch(Exception e){getContentResolver().delete(u,null,null);throw e;}
        }else{
            File dir=new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),"抖音提取器");
            if(!dir.exists()&&!dir.mkdirs())throw new IOException("无法创建文件夹");
            try(InputStream in=new FileInputStream(source);OutputStream out=new FileOutputStream(new File(dir,n))){copy(in,out,LIMIT+1024);}
        }
    }
    @Override protected void onDestroy(){recognitionCancelled.set(true);if(ui!=null)ui.destroy();super.onDestroy();}
}