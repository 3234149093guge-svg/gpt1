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
import android.media.MediaExtractor;
import android.media.MediaFormat;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.*;

public class MainActivity extends Activity {
    private static final long LIMIT=500L*1024*1024;
    private static final String SAMPLE_LINK="https://v.douyin.com/NRlGp9fKoMs/";
    private static final String ANDROID_UA="Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36";
    private static final String IPHONE_UA="Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";
    private final java.util.concurrent.atomic.AtomicBoolean working=new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final java.net.CookieManager HTTP_COOKIES =
        new java.net.CookieManager(null,java.net.CookiePolicy.ACCEPT_ALL);
    private WebView ui;
    private volatile String sourceUrl="",mediaUrl="",workId="";
    private volatile String diagnostics="";
    private String incoming="";
    private int requestSerial=0;
    private final Object logLock=new Object();

    @Override public void onCreate(Bundle bundle){
        super.onCreate(bundle);
        java.net.CookieHandler.setDefault(HTTP_COOKIES);
        getWindow().setStatusBarColor(Color.rgb(247,247,245));
        getWindow().getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        ui=new WebView(this);
        ui.setBackgroundColor(Color.rgb(247,247,245));
        ui.getSettings().setJavaScriptEnabled(true);
        ui.getSettings().setDomStorageEnabled(true);
        ui.getSettings().setAllowFileAccessFromFileURLs(false);
        ui.getSettings().setAllowUniversalAccessFromFileURLs(false);
        ui.addJavascriptInterface(new Bridge(),"App");
        ui.setWebViewClient(new WebViewClient(){
            @Override public void onPageFinished(WebView view,String url){
                if(!incoming.isEmpty()){send("link",incoming);incoming="";}
            }
        });
        setContentView(ui);
        ui.loadUrl("file:///android_asset/index.html");
        accept(getIntent());
    }
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);setIntent(intent);accept(intent);
    }
    private void accept(Intent intent){
        if(intent!=null&&Intent.ACTION_SEND.equals(intent.getAction())){
            String val=intent.getStringExtra(Intent.EXTRA_TEXT);
            if(val!=null){incoming=val;ui.postDelayed(()->{
                if(!incoming.isEmpty()){send("link",incoming);incoming="";}
            },800);}
        }
    }
    private void send(String type,String value){
        runOnUiThread(()->{
            try{
                JSONObject obj=new JSONObject();
                obj.put("type",type);
                obj.put("text",value==null?"":value);
                ui.evaluateJavascript("window.fromAndroid("+obj.toString()+")",null);
            }catch(Exception ignored){}
        });
    }
    private void log(String s){
        synchronized(logLock){
            diagnostics+=new SimpleDateFormat("HH:mm:ss",Locale.ROOT).format(new Date())+"  "+s+"\n";
        }
        send("log",s);
    }
    public final class Bridge {
        @JavascriptInterface public void paste(){
            runOnUiThread(()->{
                ClipboardManager clipboard=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
                if(clipboard==null||!clipboard.hasPrimaryClip())return;
                ClipData data=clipboard.getPrimaryClip();
                if(data!=null&&data.getItemCount()>0){
                    send("link",data.getItemAt(0).coerceToText(MainActivity.this).toString());
                }
            });
        }
        @JavascriptInterface public void sample(){send("link",SAMPLE_LINK);}
        @JavascriptInterface public void resolve(String text){
            if(!working.compareAndSet(false,true)){
                send("message","已有任务正在进行");return;
            }
            new Thread(()->{
                try{resolveLink(text);}
                catch(Exception e){log("解析异常："+message(e));send("error","解析失败："+message(e));}
                finally{working.set(false);send("idle","");}
            },"video-resolve").start();
        }
        @JavascriptInterface public void download(){
            if(!working.compareAndSet(false,true)){send("message","已有任务正在进行");return;}
            new Thread(()->{
                try{downloadVideo();}
                catch(Exception e){log("下载异常："+message(e));send("error","下载失败："+message(e));}
                finally{working.set(false);send("idle","");}
            },"video-download").start();
        }
        @JavascriptInterface public void copyLogs(){synchronized(logLock){GenCopy(diagnostics);}}
    }
    private void GenCopy(String val){
        runOnUiThread(()->{
            android.content.ClipboardManager cm=(android.content.ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
            if(cm!=null)cm.setPrimaryClip(ClipData.newPlainText("解析诊断",val));
            send("message","诊断日志已复制（不会包含 Cookie 或完整媒体签名）");
        });
    }
    private static String message(Exception e){
        if(e instanceof java.net.SocketTimeoutException)return "连接超时";
        if(e instanceof java.net.UnknownHostException)return "网络域名解析失败";
        String m=e.getMessage();
        return m==null?e.getClass().getSimpleName():m.length()>130?m.substring(0,130):m;
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
    private HttpURLConnection connect(String url,String accept,String userAgent)throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(13000);
        c.setReadTimeout(25000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent",userAgent);
        c.setRequestProperty("Accept",accept);
        c.setRequestProperty("Accept-Language","zh-CN,zh;q=0.9");
        c.setRequestProperty("Referer","https://www.douyin.com/");
        return c;
    }
    private static String videoId(String text) {
        if(text==null)return "";
        Matcher m=Pattern.compile("(?:/video/|/share/video/|[?&](?:modal_id|aweme_id)=)([0-9]{15,22})").matcher(text);
        return m.find()?m.group(1):"";
    }
    // This is a strictly optional compatibility fallback. Keep the classic
    // v0.4 Android request unchanged and try iPhone SSR only after it fails.
    private String getHtml(String url,String agent)throws Exception {
        HttpURLConnection c=connect(url,"text/html,application/xhtml+xml,*/*",agent);
        try {
            int status=c.getResponseCode();
            if(status>=400)throw new IOException("HTTP "+status+"："+Uri.parse(url).getHost());
            String redirected=c.getURL().toString();
            if(douyin(redirected))sourceUrl=redirected;
            ByteArrayOutputStream body=new ByteArrayOutputStream();
            try(InputStream in=c.getInputStream()){copy(in,body,6L*1024*1024);}
            return body.toString("UTF-8");
        } finally {c.disconnect();}
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

    private static void copy(InputStream in,OutputStream out,long max)throws IOException{
        byte[] buf=new byte[65536];long total=0;int n;
        while((n=in.read(buf))!=-1){
            total+=n;if(total>max)throw new IOException("超出最大文件大小");
            out.write(buf,0,n);
        }
    }
    private static String codeState(String html){
        if(html==null||html.length()==0)return "空响应";
        if(html.contains("videoInfoRes")&&html.contains("play_addr"))return "包含视频详情";
        if(html.contains("_ROUTER_DATA"))return "仅包含页面结构";
        if(html.length()<4096)return "内容很少";
        return "没有视频字段";
    }
    private static String maskedHost(String link){
        try{
            String host=Uri.parse(link).getHost();
            return host==null?"unknown":host;
        }catch(Exception e){return "unknown";}
    }
    private void resolveLink(String input){
        String link=findUrl(input);
        if(!https(link))throw new IllegalArgumentException("需要包含 https:// 的视频链接");
        if(!douyin(link)&&!media(link))throw new IllegalArgumentException("仅支持抖音作品及视频直链");
        synchronized(logLock){diagnostics="";}
        mediaUrl="";sourceUrl=link;workId="";
        log("开始解析："+maskedHost(link));
        if(!douyin(link)){
            mediaUrl=link;
            send("found","识别到视频直链，准备检查");
            checkUrl(link);
            send("ready","可下载视频已验证");
            return;
        }
        // The short link can reveal the content ID while initializing cookies.
        String id=videoId(link);
        if(id.isEmpty()) {
            try{
                String html=getHtml(link,ANDROID_UA);
                id=videoId(sourceUrl);
                if(id.isEmpty()){
                    Matcher m=Pattern.compile("(?:video/|aweme_id[=:][\"']?)([0-9]{15,22})").matcher(html);
                    if(m.find())id=m.group(1);
                }
                if(!html.isEmpty())log("原分享页："+codeState(html));
                String candidate=structuredMedia(html);
                if(candidate.isEmpty())candidate=scanDirectVideo(html);
                if(!candidate.isEmpty()){
                    finishResolved(candidate,id);
                    return;
                }
            }catch(Exception e){log("短链接读取："+message(e));}
        }
        if(id.isEmpty()){
            send("error","短链接没有返回作品编号；请检查链接是否有效");
            return;
        }
        workId=id;
        send("videoId",id);
        // Android UA path matches user's earlier working v0.4. iPhone page
        // is tried after it; this fallback returned actual video data in CI.
        String[] urls={
            "https://www.iesdouyin.com/share/video/"+id+"/",
            "https://www.douyin.com/share/video/"+id+"/"
        };
        String[] agents={ANDROID_UA,IPHONE_UA};
        for(int round=1;round<=2;round++){
            for(int a=0;a<agents.length;a++){
                for(int i=0;i<urls.length;i++){
                    String name=(a==0?"Android":"iPhone")+"/"+(i==0?"移动分享":"公开分享");
                    send("progress","正在检查 "+name+" · 第 "+round+"/2 轮");
                    try {
                        String body=getHtml(urls[i],agents[a]);
                        String candidate=structuredMedia(body);
                        if(candidate.isEmpty())candidate=scanDirectVideo(body);
                        if(!candidate.isEmpty()){
                            log(name+"：取得视频元数据");
                            finishResolved(candidate,id);
                            return;
                        }
                        log(name+"："+codeState(body)+"（"+body.length()/1024+"KB）");
                    }catch(Exception e){log(name+"："+message(e));}
                }
            }
            if(round==1){
                log("第一轮未取得媒体数据，等待后自动复核一次");
                try{Thread.sleep(1100);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
            }
        }
        send("error","服务器没有提供视频播放地址。此时无法下载，并不代表作品已删除。可复制诊断日志定位请求阶段。");
    }
    private void finishResolved(String url,String id)throws Exception{
        if(!https(url))throw new IOException("播放地址不是 HTTPS 链接");
        send("found","已读取播放地址，正在验证是否是真正的 MP4");
        try{
            checkUrl(url);
            mediaUrl=url;workId=id==null?"":id;
            log("视频资源验证成功，域名："+maskedHost(url));
            send("ready","视频地址已验证，可下载 MP4");
        }catch(Exception e){
            log("候选媒体地址不可用："+message(e));
            send("error","已找到候选地址，但服务器没有返回可用 MP4："+message(e));
        }
    }
    private void checkUrl(String url)throws Exception {
        HttpURLConnection c=connect(url,"video/mp4,video/*,*/*",ANDROID_UA);
        c.setRequestProperty("Range","bytes=0-8191");
        try {
            int status=c.getResponseCode();
            if(status!=200&&status!=206)throw new IOException("HTTP "+status);
            String mime=c.getContentType();
            if(mime!=null&&(mime.contains("text/html")||mime.contains("application/json")))
                throw new IOException("返回验证网页，不是视频");
            byte[] header=new byte[16];
            try(InputStream in=c.getInputStream()){
                int n=0;while(n<header.length){
                    int k=in.read(header,n,header.length-n);
                    if(k<0)break;
                    n+=k;
                }
                if(n<8||header[4]!='f'||header[5]!='t'||header[6]!='y'||header[7]!='p')
                    throw new IOException("不是 MP4 文件");
            }
        }finally{c.disconnect();}
    }
    private void downloadVideo() throws Exception {
        String url=mediaUrl;
        if(url.isEmpty()){send("error","请先解析并验证视频链接");return;}
        File temp=new File(getCacheDir(),"video_"+System.currentTimeMillis()+".mp4");
        HttpURLConnection c=null;
        try{
            send("progress","正在下载视频…");
            c=connect(url,"video/mp4,video/*,*/*",ANDROID_UA);
            int response=c.getResponseCode();
            if(response!=200&&response!=206)throw new IOException("视频下载 HTTP "+response);
            String mime=c.getContentType();
            if(mime!=null&&(mime.contains("text/html")||mime.contains("application/json")))
                throw new IOException("视频服务器返回了网页或验证信息");
            long size=c.getContentLengthLong();
            long total=0,previous=0;
            try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(temp)){
                byte[] b=new byte[65536];int n;
                while((n=in.read(b))!=-1){
                    out.write(b,0,n);total+=n;
                    if(total>LIMIT)throw new IOException("视频超过 500 MB 上限");
                    if(total-previous>1024*1024){
                        int pct=size>0?(int)Math.min(99,total*100/size):-1;
                        send("downloadProgress",pct>=0?"已下载 "+pct+"% · "+(total/1024/1024)+" MB":"已下载 "+(total/1024/1024)+" MB");
                        previous=total;
                    }
                }
            }
            if(temp.length()<1024)throw new IOException("获取的视频文件过小");
            MediaExtractor check=new MediaExtractor();
            try{
                check.setDataSource(temp.getAbsolutePath());
                boolean videoTrack=false;
                for(int i=0;i<check.getTrackCount();i++){
                    MediaFormat format=check.getTrackFormat(i);
                    String m=format.getString(MediaFormat.KEY_MIME);
                    if(m!=null&&m.startsWith("video/"))videoTrack=true;
                }
                if(!videoTrack)throw new IOException("文件内没有视频轨道");
            }finally{check.release();}
            String id=workId;
            String filename="视频_"+(id.isEmpty()?"抖音":id)+"_"+
                new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.CHINA).format(new Date())+".mp4";
            save(temp,filename);
            log("MP4 完整下载并校验："+temp.length()/1024/1024+" MB");
            send("saved","视频已保存：下载 / 抖音提取器 / "+filename);
        }finally{
            if(c!=null)c.disconnect();
            if(temp.exists())temp.delete();
        }
    }
    private void save(File local,String filename)throws Exception{
        if(Build.VERSION.SDK_INT>=29){
            ContentValues v=new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME,filename);
            v.put(MediaStore.MediaColumns.MIME_TYPE,"video/mp4");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/抖音提取器");
            v.put(MediaStore.MediaColumns.IS_PENDING,1);
            Uri dest=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);
            if(dest==null)throw new IOException("无法创建下载文件");
            try{
                try(InputStream in=new FileInputStream(local);
                    OutputStream out=getContentResolver().openOutputStream(dest)){
                    if(out==null)throw new IOException("无法写入下载目录");
                    copy(in,out,LIMIT+1024);
                }
                v.clear();
                v.put(MediaStore.MediaColumns.IS_PENDING,0);
                getContentResolver().update(dest,v,null,null);
            }catch(Exception e){
                getContentResolver().delete(dest,null,null);
                throw e;
            }
        } else {
            File root=getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if(root==null)throw new IOException("手机没有可用下载目录");
            File dir=new File(root,"抖音提取器");
            if(!dir.exists()&&!dir.mkdirs())throw new IOException("无法创建视频保存目录");
            try(InputStream in=new FileInputStream(local);
                OutputStream out=new FileOutputStream(new File(dir,filename))){
                copy(in,out,LIMIT+1024);
            }
        }
    }
    @Override protected void onDestroy(){if(ui!=null)ui.destroy();super.onDestroy();}
}
