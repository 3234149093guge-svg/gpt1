package com.zhixu.demo;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Color;
import android.print.PrintManager;
import android.print.PrintAttributes;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

public final class MainActivity extends Activity {
  private WebView web;
  private ChatGptClient chatGpt;
  private String contentToSave;
  static final int SAVE=102;

  @Override public void onCreate(Bundle state){
    super.onCreate(state);
    getWindow().setStatusBarColor(Color.rgb(250,250,248));
    getWindow().setNavigationBarColor(Color.WHITE);
    getWindow().getDecorView().setSystemUiVisibility(0x2000|0x10);
    web=new WebView(this);
    web.getSettings().setJavaScriptEnabled(true);
    web.getSettings().setDomStorageEnabled(true);
    web.getSettings().setAllowFileAccess(true);
    web.getSettings().setAllowContentAccess(false);
    web.getSettings().setAllowFileAccessFromFileURLs(false);
    web.getSettings().setAllowUniversalAccessFromFileURLs(false);
    web.getSettings().setBlockNetworkLoads(true);
    web.setWebViewClient(new WebViewClient(){
      @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){
        return !r.getUrl().toString().equals("file:///android_asset/index.html");
      }
    });
    chatGpt=new ChatGptClient(this,web);
    web.addJavascriptInterface(new Bridge(),"Android");
    setContentView(web);
    web.loadUrl("file:///android_asset/index.html");
  }

  final class Bridge {
    @JavascriptInterface public String chatGptStatus(){return chatGpt.status().toString();}
    @JavascriptInterface public void chatGptSignIn(){chatGpt.signIn();}
    @JavascriptInterface public void chatGptModels(){chatGpt.listModels();}
    @JavascriptInterface public void chatGptSelectModel(String slug){chatGpt.selectModel(slug);}
    @JavascriptInterface public void chatGptInfer(String payload){chatGpt.inference(payload);}
    @JavascriptInterface public void chatGptLogout(){chatGpt.signOut();}

    @JavascriptInterface public void openUsageSettings(){
      runOnUiThread(()->{
        try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://chatgpt.com/#settings/usage")));}
        catch(Exception e){Toast.makeText(MainActivity.this,"无法打开 ChatGPT 设置",Toast.LENGTH_SHORT).show();}
      });
    }
    @JavascriptInterface public void saveFile(String name,String text,String mime){
      runOnUiThread(()->{
        contentToSave=text;
        Intent it=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        it.setType(mime);it.addCategory(Intent.CATEGORY_OPENABLE);
        it.putExtra(Intent.EXTRA_TITLE,name);
        try{startActivityForResult(it,SAVE);}
        catch(Exception e){Toast.makeText(MainActivity.this,"无法打开保存位置",Toast.LENGTH_SHORT).show();}
      });
    }
    @JavascriptInterface public void printPdf(String html){
      runOnUiThread(()->{
        WebView printView=new WebView(MainActivity.this);
        printView.loadDataWithBaseURL(null,html,"text/html","UTF-8",null);
        printView.setWebViewClient(new WebViewClient(){
          @Override public void onPageFinished(WebView v,String url){
            try{
              PrintManager pm=(PrintManager)getSystemService(PRINT_SERVICE);
              pm.print("知序学习笔记",v.createPrintDocumentAdapter("知序学习笔记"),new PrintAttributes.Builder().build());
            }catch(Exception e){
              Toast.makeText(MainActivity.this,"打印功能暂不可用",Toast.LENGTH_SHORT).show();
            }
          }
        });
      });
    }
  }

  @Override protected void onActivityResult(int req,int res,Intent data){
    super.onActivityResult(req,res,data);
    if(req==SAVE){
      if(res==RESULT_OK&&data!=null&&data.getData()!=null){
        try(java.io.OutputStream out=getContentResolver().openOutputStream(data.getData())){
          if(out!=null&&contentToSave!=null){
            out.write(contentToSave.getBytes("UTF-8"));
            Toast.makeText(this,"文件已保存",Toast.LENGTH_SHORT).show();
          }
        }catch(Exception e){Toast.makeText(this,"保存失败",Toast.LENGTH_LONG).show();}
      }
      contentToSave=null;
    }
  }
  @Override public void onBackPressed(){
    web.evaluateJavascript("window.demoBack && window.demoBack()",v->{
      if(!"true".equals(v))MainActivity.super.onBackPressed();
    });
  }
  @Override public void onDestroy(){
    chatGpt.destroy();
    web.destroy();
    super.onDestroy();
  }
}