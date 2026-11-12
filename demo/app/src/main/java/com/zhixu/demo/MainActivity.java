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
  WebView web; String contentToSave; String mimeToSave; static final int SAVE=102;
  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().setStatusBarColor(Color.rgb(248,247,243));
    getWindow().setNavigationBarColor(Color.rgb(255,252,247));
    getWindow().getDecorView().setSystemUiVisibility(0x2000|0x10);
    web=new WebView(this); setContentView(web);
    web.getSettings().setJavaScriptEnabled(true);
    web.getSettings().setDomStorageEnabled(true);
    web.getSettings().setAllowFileAccess(true);
    web.getSettings().setBlockNetworkLoads(true);
    web.setWebViewClient(new WebViewClient(){
      @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return !r.getUrl().toString().startsWith("file:///android_asset/");}
    });
    web.addJavascriptInterface(new Bridge(),"Android");
    web.loadUrl("file:///android_asset/index.html");
  }
  class Bridge {
    @JavascriptInterface public void saveFile(String name,String text,String mime) {
      runOnUiThread(()->{
        contentToSave=text;mimeToSave=mime;
        Intent it=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        it.setType(mime);it.addCategory(Intent.CATEGORY_OPENABLE);
        it.putExtra(Intent.EXTRA_TITLE,name);
        try{startActivityForResult(it,SAVE);}catch(Exception e){Toast.makeText(MainActivity.this,"无法打开保存位置",Toast.LENGTH_SHORT).show();}
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
            }catch(Exception e){Toast.makeText(MainActivity.this,"打印功能暂不可用",Toast.LENGTH_SHORT).show();}
          }
        });
      });
    }
  }
  @Override protected void onActivityResult(int req,int res,Intent data){
    super.onActivityResult(req,res,data);
    if(req==SAVE){
      if(res==RESULT_OK&&data!=null&&data.getData()!=null){
        try(java.io.OutputStream out=getContentResolver().openOutputStream(data.getData())) {
          if(out!=null){out.write(contentToSave.getBytes("UTF-8"));Toast.makeText(this,"文件已保存",Toast.LENGTH_SHORT).show();}
        }catch(Exception e){Toast.makeText(this,"保存失败",Toast.LENGTH_LONG).show();}
      }
      contentToSave=null;mimeToSave=null;
    }
  }
  @Override public void onBackPressed(){web.evaluateJavascript("window.demoBack && window.demoBack()",v->{if(!"true".equals(v))MainActivity.super.onBackPressed();});}
  @Override public void onDestroy(){web.destroy();super.onDestroy();}
}