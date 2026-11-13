package com.example.douyinexport;

import android.content.Context;
import android.net.Uri;
import android.os.StatFs;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;

public final class QwenModelManager {
    public interface Progress { void update(String msg); }
    private QwenModelManager() {}
    private static final String MODEL="sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25";
    public static final String MODEL_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"+MODEL+".tar.bz2";
    private static final String SHA256="393f8a14e2f5fb96746aaab342997a40641001fbd5bf9592a080a8329178ee96";
    private static final long ARCHIVE_SIZE=878702423L;
    private static final long MAX_UNPACKED=1200L*1024*1024;
    private static final Set<String> FILES=new HashSet<>(Arrays.asList(
        "conv_frontend.onnx","encoder.int8.onnx","decoder.int8.onnx",
        "tokenizer/merges.txt","tokenizer/tokenizer_config.json","tokenizer/vocab.json"
    ));

    public static File modelDir(Context ctx) {return new File(ctx.getFilesDir(),"qwen3-asr-0.6b-int8");}
    private static File archive(Context ctx) {return new File(ctx.getFilesDir(),"qwen3-asr-0.6b-int8.tar.bz2");}
    private static File partial(Context ctx) {return new File(ctx.getFilesDir(),"qwen3-asr-0.6b-int8.tar.bz2.part");}
    public static boolean installed(Context ctx) {
        File root=modelDir(ctx);
        if(!new File(root,".complete").isFile())return false;
        return new File(root,"conv_frontend.onnx").length()>30_000_000L
            && new File(root,"encoder.int8.onnx").length()>100_000_000L
            && new File(root,"decoder.int8.onnx").length()>500_000_000L
            && new File(root,"tokenizer/vocab.json").length()>1_000_000L
            && new File(root,"tokenizer/merges.txt").length()>1_000_000L;
    }
    private static void ensureSpace(Context ctx,long minimum)throws IOException{
        long space=ctx.getFilesDir().getUsableSpace();
        if(space<minimum)throw new IOException("存储空间不足，需要额外预留 "+(minimum/(1024*1024))+" MB，当前可用 "+(space/(1024*1024))+" MB");
    }
    private static void copy(InputStream in,OutputStream out)throws IOException{
        byte[] buf=new byte[65536];
        for(int n;(n=in.read(buf))!=-1;)out.write(buf,0,n);
    }
    private static String sha256(File file,Progress progress)throws Exception {
        MessageDigest d=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new BufferedInputStream(new FileInputStream(file))){
            byte[] buf=new byte[1024*1024];
            long count=0,last=0;
            for(int n;(n=in.read(buf))!=-1;){
                d.update(buf,0,n);count+=n;
                if(count-last>128L*1024*1024){
                    progress.update("校验文件完整性："+(count*100/file.length())+"%");
                    last=count;
                }
            }
        }
        StringBuilder out=new StringBuilder();
        for(byte b:d.digest())out.append(String.format(Locale.ROOT,"%02x",b&255));
        return out.toString();
    }
    private static void verifyArchive(File file,Progress progress)throws Exception{
        if(file.length()!=ARCHIVE_SIZE)throw new IOException(
            "模型文件大小不正确，期望 "+ARCHIVE_SIZE+" 字节，实际 "+file.length()+" 字节");
        progress.update("正在校验官方模型 SHA-256…");
        if(!SHA256.equals(sha256(file,progress)))throw new IOException("模型 SHA-256 校验失败，请重新下载");
    }
    public static synchronized File downloadAndInstall(Context ctx,Progress progress)throws Exception{
        if(installed(ctx))return modelDir(ctx);
        ensureSpace(ctx,2350L*1024*1024);
        File full=archive(ctx),part=partial(ctx);
        if(!full.exists()){
            if(part.exists()&&part.length()>ARCHIVE_SIZE)part.delete();
            long already=part.exists()?part.length():0;
            HttpURLConnection c=(HttpURLConnection)new URL(MODEL_URL).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent","ZhiyinAndroid/1.0");
            c.setRequestProperty("Accept-Encoding","identity");
            if(already>0)c.setRequestProperty("Range","bytes="+already+"-");
            try{
                int status=c.getResponseCode();
                if(status!=200&&status!=206)throw new IOException("模型下载返回 HTTP "+status);
                if(status==200)already=0;
                progress.update(already>0?"正在继续下载模型（断点续传）…":"正在下载 Qwen3-ASR-0.6B INT8…");
                try(InputStream in=new BufferedInputStream(c.getInputStream());
                    RandomAccessFile dst=new RandomAccessFile(part,"rw")){
                    dst.setLength(already);
                    dst.seek(already);
                    byte[] buf=new byte[65536];long total=already,lastReport=0;
                    for(int n;(n=in.read(buf))!=-1;){
                        dst.write(buf,0,n);total+=n;
                        if(total>ARCHIVE_SIZE)throw new IOException("下载超出模型文件大小");
                        if(total-lastReport>8L*1024*1024){
                            progress.update("模型下载："+(total*100/ARCHIVE_SIZE)+"%（"+(total/1024/1024)+" / "+(ARCHIVE_SIZE/1024/1024)+" MB）");
                            lastReport=total;
                        }
                    }
                }
            }finally{c.disconnect();}
            if(part.length()!=ARCHIVE_SIZE)throw new IOException("下载未完成，已保留进度，点击重试可继续");
            if(!part.renameTo(full))throw new IOException("模型临时文件无法完成写入");
        }
        try{
            verifyArchive(full,progress);
        }catch(Exception e){
            full.delete();
            throw e;
        }
        ensureSpace(ctx,1050L*1024*1024);
        File root=modelDir(ctx),staging=new File(ctx.getFilesDir(),"qwen3-asr-stage");
        delete(staging);
        if(!staging.mkdirs())throw new IOException("无法建立模型安装目录");
        try{
            progress.update("校验通过，正在解压模型（约 1 GB）…");
            long total=0;
            try(InputStream input=new BufferedInputStream(new FileInputStream(full));
                BZip2CompressorInputStream bz=new BZip2CompressorInputStream(input,true);
                TarArchiveInputStream tar=new TarArchiveInputStream(bz)){
                TarArchiveEntry entry;
                while((entry=tar.getNextTarEntry())!=null){
                    if(!entry.isFile())continue;
                    String name=entry.getName();
                    if(!name.startsWith(MODEL+"/"))continue;
                    String relative=name.substring(MODEL.length()+1);
                    if(!FILES.contains(relative))continue;
                    if(entry.getSize()<0||entry.getSize()>800L*1024*1024)throw new IOException("模型条目大小异常");
                    total+=entry.getSize();
                    if(total>MAX_UNPACKED)throw new IOException("模型解压数据异常");
                    File dst=new File(staging,relative);
                    File parent=dst.getParentFile();
                    if(!parent.exists()&&!parent.mkdirs())throw new IOException("无法建立模型子目录");
                    try(OutputStream out=new BufferedOutputStream(new FileOutputStream(dst))){
                        byte[] buf=new byte[65536];
                        long left=entry.getSize();
                        while(left>0){
                            int n=tar.read(buf,0,(int)Math.min(buf.length,left));
                            if(n<0)throw new EOFException("模型包提前结束");
                            out.write(buf,0,n);
                            left-=n;
                        }
                    }
                    progress.update("已安装 "+relative+"（"+(total/1024/1024)+" MB）");
                }
            }
            for(String relative:FILES)if(!new File(staging,relative).isFile())
                throw new IOException("模型包缺少 "+relative);
            if(root.exists())delete(root);
            if(!staging.renameTo(root))throw new IOException("无法完成模型目录安装");
            new File(root,".complete").createNewFile();
            if(!installed(ctx))throw new IOException("解压完成但模型文件校验未通过");
            full.delete();
            progress.update("Qwen3-ASR-0.6B INT8 安装完成，可完全离线转写");
            return root;
        }catch(Exception e){delete(staging);throw e;}
    }
    public static synchronized File importArchive(Context ctx,Uri uri,Progress progress)throws Exception{
        if(installed(ctx))return modelDir(ctx);
        ensureSpace(ctx,2100L*1024*1024);
        File part=partial(ctx),full=archive(ctx);
        progress.update("正在复制用户选取的模型包…");
        try(InputStream in=ctx.getContentResolver().openInputStream(uri);
            OutputStream out=new BufferedOutputStream(new FileOutputStream(part,false))){
            if(in==null)throw new IOException("无法读取选中的文件");
            byte[] buf=new byte[65536];long total=0;
            for(int n;(n=in.read(buf))!=-1;){
                total+=n;if(total>ARCHIVE_SIZE)throw new IOException("选择的文件大于官方模型大小");
                out.write(buf,0,n);
            }
        }
        if(part.length()!=ARCHIVE_SIZE)throw new IOException("模型包大小错误，请选择官方 Qwen3-ASR-0.6B INT8 压缩包");
        if(full.exists())full.delete();
        if(!part.renameTo(full))throw new IOException("无法保存模型包");
        return downloadAndInstall(ctx,progress);
    }
    private static void delete(File f){
        if(!f.exists())return;
        if(f.isDirectory()){
            File[] contents=f.listFiles();
            if(contents!=null)for(File child:contents)delete(child);
        }
        f.delete();
    }
}
