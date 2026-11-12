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
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.JavascriptInterface;
import android.webkit.CookieManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Button;
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
    private static final long LIMIT=500L*1024*1024;
    private FrameLayout frame;
    private WebView ui, browser;
    private LinearLayout overlay;
    private volatile String mediaUrl="", sourceUrl="";
    private volatile File video, audio;
    private String incoming="";
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
        @JavascriptInterface public void resolve(String text){work(()->resolveLink(text));}
        @JavascriptInterface public void open(String text){runOnUiThread(()->openPage(text));}
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
        @JavascriptInterface public void transcript(String key){work(()->transcribe(key));}
        @JavascriptInterface public void saveText(String content){work(()->{
            try {
                if(content.trim().isEmpty())throw new IOException("文字稿为空");
                File f=new File(getCacheDir(),"text_"+System.currentTimeMillis()+".txt");
                try(OutputStream o=new FileOutputStream(f)){o.write(content.getBytes(StandardCharsets.UTF_8));}
                String n=name("文字稿","txt");save(f,n,"text/plain");send("ok","文字稿已保存："+n);
            }catch(Exception e){fail(e);}
        });}
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
            send("info","步骤 1/3：解析短链及作品信息……");
            String id=videoId(link),html="";
            try{
                html=getHtml(link);
                if(id.isEmpty())id=videoId(sourceUrl);
                if(id.isEmpty()){
                    Matcher ref=Pattern.compile("(?:video/|aweme_id[=:][\"']?)([0-9]{15,22})").matcher(html);
                    if(ref.find())id=ref.group(1);
                }
            }catch(Exception e){send("info","分享页需要浏览器加载，尝试移动分享页……");}
            String result=structuredMedia(html);
            if(result.isEmpty()&&!id.isEmpty()){
                send("info","步骤 2/3：读取移动分享页元数据……");
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
            if(!result.isEmpty()){
                mediaUrl=result;
                send("media",result);
                return;
            }
            send("info","步骤 3/3：正在打开抖音网页。若视频没有自动加载，请点播放，再返回下载。");
            runOnUiThread(()->openPage(link));
        }catch(Exception e){fail(e);}
    }
    private void openPage(String text){
        String url=findUrl(text);
        if(!douyin(url)){send("error","请输入有效的抖音 HTTPS 链接");return;}
        if(overlay!=null)return;
        overlay=new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        Button close=new Button(this);
        close.setText("关闭网页并返回");
        close.setOnClickListener(v->closePage());
        overlay.addView(close,new LinearLayout.LayoutParams(-1,(int)(52*getResources().getDisplayMetrics().density)));
        browser=new WebView(this);
        browser.getSettings().setJavaScriptEnabled(true);
        browser.getSettings().setDomStorageEnabled(true);
        browser.getSettings().setMediaPlaybackRequiresUserGesture(false);
        browser.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView w,WebResourceRequest req){
                String u=req.getUrl().toString();
                if(https(u)&&media(u)&&!u.equals(mediaUrl)){mediaUrl=u;send("media",u);}
                return null;
            }
            @Override public void onPageFinished(WebView w,String u){probeVideo(w);w.postDelayed(()->probeVideo(w),3000);}
        });
        overlay.addView(browser,new LinearLayout.LayoutParams(-1,0,1));
        frame.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
        browser.loadUrl(url);
    }
    private void probeVideo(WebView w){
        if(w!=browser)return;
        w.evaluateJavascript("(function(){var v=document.querySelector('video');return v?(v.currentSrc||v.src||''):''})()",value->{
            try{
                String url=new JSONObject("{\"v\":"+value+"}").optString("v");
                if(https(url)&&media(url)){mediaUrl=url;send("media",url);}
            }catch(Exception ignored){}
        });
    }
    private void closePage(){
        if(overlay!=null){frame.removeView(overlay);overlay=null;}
        if(browser!=null){browser.stopLoading();browser.destroy();browser=null;}
    }
    @Override public void onBackPressed(){if(browser!=null)closePage();else super.onBackPressed();}
    private static void copy(InputStream in,OutputStream out,long max)throws IOException{
        byte[] b=new byte[65536];long count=0;int n;
        while((n=in.read(b))!=-1){count+=n;if(count>max)throw new IOException("文件超出大小限制");out.write(b,0,n);}
    }
    private void downloadVideo(){
        if(mediaUrl.isEmpty()){send("error","请先解析视频链接或打开网页播放");return;}
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
        if(request!=PICK||result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        work(()->{
            try {
                File f=new File(getCacheDir(),"import_"+System.currentTimeMillis()+".mp4");
                try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(f)){
                    if(in==null)throw new IOException("无法读取视频");
                    copy(in,out,LIMIT);
                }
                MediaExtractor p=new MediaExtractor();
                try{p.setDataSource(f.getAbsolutePath());if(p.getTrackCount()<1)throw new IOException("不是可用的视频");}
                finally{p.release();}
                video=f;audio=null;send("ready","本地视频已导入。可以导出音频或文字稿。");
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
    private void transcribe(String key){
        try{
            if(key==null||key.trim().isEmpty())throw new IOException("请填写 OpenAI API Key");
            File f=extractAudio();
            if(f.length()>24L*1024*1024)throw new IOException("音频超过 24MB，暂不支持转写");
            send("info","正在云端识别语音，可能产生 API 费用……");
            String boundary="----DExport"+UUID.randomUUID().toString().replace("-","");
            HttpURLConnection c=(HttpURLConnection)new URL("https://api.openai.com/v1/audio/transcriptions").openConnection();
            try{
                c.setRequestMethod("POST");c.setConnectTimeout(25000);c.setReadTimeout(180000);
                c.setDoOutput(true);c.setChunkedStreamingMode(65536);
                c.setRequestProperty("Authorization","Bearer "+key.trim());
                c.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);
                try(OutputStream out=c.getOutputStream();InputStream in=new FileInputStream(f)){
                    out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\ngpt-4o-mini-transcribe\r\n").getBytes(StandardCharsets.UTF_8));
                    out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    copy(in,out,24L*1024*1024);
                    out.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
                }
                int code=c.getResponseCode();
                InputStream in=code>=400?c.getErrorStream():c.getInputStream();
                if(in==null)throw new IOException("识别服务无响应 HTTP "+code);
                ByteArrayOutputStream b=new ByteArrayOutputStream();
                try(InputStream stream=in){copy(stream,b,2L*1024*1024);}
                String raw=b.toString("UTF-8");
                if(code>=400)throw new IOException("识别失败 HTTP "+code+": "+raw.substring(0,Math.min(raw.length(),130)));
                String t=new JSONObject(raw).optString("text");
                if(t.isEmpty())throw new IOException("服务没有返回文字");
                send("transcript",t);
            }finally{c.disconnect();}
        }catch(Exception e){fail(e);}
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
    @Override protected void onDestroy(){if(browser!=null)browser.destroy();if(ui!=null)ui.destroy();super.onDestroy();}
}