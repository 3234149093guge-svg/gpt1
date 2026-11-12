package com.zhixu.demo;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.RSAPublicKeySpec;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Sign in with ChatGPT: OpenAI's open-source, local client flow.
 * OAuth credentials remain in Android Keystore-encrypted private storage.
 * Access/refresh/ID tokens NEVER cross the JS bridge.
 *
 * This implementation is a preview and requires interactive verification
 * with a participating ChatGPT account on a real Android device.
 */
final class ChatGptClient {
  private static final String AUTH="https://auth.openai.com";
  private static final String RESOURCE="https://api.openai.com/v1";
  private static final String TOKEN=AUTH+"/api/accounts/oauth/token";
  private static final String ALIAS="zhixu_chatgpt_local_aes_v1";
  private static final String CREDENTIALS="encrypted_account";
  private static final String SCOPES="openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";
  private final Activity activity;
  private final WebView web;
  private final SharedPreferences prefs;
  private final ExecutorService io=Executors.newSingleThreadExecutor();
  private final SecureRandom random=new SecureRandom();
  private volatile boolean signingIn=false;
  private volatile boolean working=false;
  private volatile boolean destroyed=false;

  ChatGptClient(Activity activity,WebView web){
    this.activity=activity;this.web=web;
    prefs=activity.getSharedPreferences("zhixu_auth",Activity.MODE_PRIVATE);
    if(prefs.getString("hostId",null)==null){
      prefs.edit().putString("hostId","urn:uuid:"+UUID.randomUUID().toString()).apply();
    }
  }

  private static String b64(byte[] data){return Base64.encodeToString(data,Base64.URL_SAFE|Base64.NO_PADDING|Base64.NO_WRAP);}
  private static byte[] un64(String s){return Base64.decode(s,Base64.URL_SAFE|Base64.NO_WRAP);}
  private String randomValue(){byte[] bytes=new byte[32];random.nextBytes(bytes);return b64(bytes);}
  private static String enc(String s)throws Exception{return URLEncoder.encode(s,"UTF-8");}
  private static String safeErr(Exception e){String m=e.getMessage();return m==null?"操作失败":m.length()>180?m.substring(0,180):m;}
  private SecretKey key()throws Exception{
    KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
    KeyStore.Entry entry=store.getEntry(ALIAS,null);
    if(entry!=null)return ((KeyStore.SecretKeyEntry)entry).getSecretKey();
    KeyGenerator kg=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
    kg.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
      .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
      .setRandomizedEncryptionRequired(true).build());
    return kg.generateKey();
  }
  private synchronized JSONObject load(){
    try{
      String raw=prefs.getString(CREDENTIALS,"");
      if(raw.length()==0)return new JSONObject();
      JSONObject wrap=new JSONObject(raw);
      Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(wrap.getString("iv"),Base64.NO_WRAP)));
      return new JSONObject(new String(cipher.doFinal(Base64.decode(wrap.getString("data"),Base64.NO_WRAP)),StandardCharsets.UTF_8));
    }catch(Exception e){return new JSONObject();}
  }
  private synchronized void persist(JSONObject record)throws Exception{
    Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE,key());
    JSONObject wrapper=new JSONObject();
    wrapper.put("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP));
    wrapper.put("data",Base64.encodeToString(cipher.doFinal(record.toString().getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP));
    if(!prefs.edit().putString(CREDENTIALS,wrapper.toString()).commit())throw new IOException("无法保存账号信息");
  }
  JSONObject status(){
    JSONObject a=load(),s=new JSONObject();
    try{
      boolean logged=a.optString("access_token").length()>0&&a.optString("client_id").length()>0;
      s.put("type","status").put("connected",logged);
      s.put("sharing",logged&&hasScope(a.optString("scope"),"chatgpt.tokens.use.direct"));
      s.put("email",a.optString("email")).put("name",a.optString("name"));
      s.put("model",a.optString("model")).put("busy",signingIn||working);
      s.put("registered",a.optString("client_id").length()>0);
    }catch(Exception ignored){}
    return s;
  }
  private static boolean hasScope(String scopes,String target){
    for(String s:scopes.split("\\s+"))if(s.equals(target))return true;
    return false;
  }
  private void event(JSONObject obj){
    final String json=obj.toString();
    activity.runOnUiThread(()->{
      if(destroyed)return;
      // JSONObject.quote protects JS literal boundaries; tokens are never in obj.
      web.evaluateJavascript("(function(){var e=JSON.parse("+JSONObject.quote(json)+");if(window.zhixuNativeEvent)window.zhixuNativeEvent(e)})()",null);
    });
  }
  private void emitStatus(){event(status());}
  private void failure(String operation,String message){
    try{event(new JSONObject().put("type","error").put("operation",operation).put("message",message));}catch(Exception ignored){}
  }
  private static HttpURLConnection connect(String path,String method,int timeout)throws Exception{
    URL url=new URL(path);
    if(!"https".equalsIgnoreCase(url.getProtocol()))throw new IOException("只允许 HTTPS");
    HttpURLConnection c=(HttpURLConnection)url.openConnection();
    c.setRequestMethod(method);
    c.setConnectTimeout(13000);c.setReadTimeout(timeout);
    c.setUseCaches(false);c.setInstanceFollowRedirects(false);
    c.setRequestProperty("Accept","application/json");
    return c;
  }
  private static String readBody(InputStream in,int max)throws Exception{
    if(in==null)return "";
    try(InputStream stream=in; ByteArrayOutputStream out=new ByteArrayOutputStream()){
      byte[] buf=new byte[4096];int n;
      while((n=stream.read(buf))>=0){
        if(out.size()+n>max)throw new IOException("响应内容过大");
        out.write(buf,0,n);
      }
      return out.toString("UTF-8");
    }
  }
  private static JSONObject formPost(String url,String[][] fields)throws Exception{
    StringBuilder body=new StringBuilder();
    for(String[] x:fields){if(body.length()>0)body.append('&');body.append(enc(x[0])).append('=').append(enc(x[1]));}
    HttpURLConnection c=connect(url,"POST",23000);
    c.setDoOutput(true);c.setRequestProperty("Content-Type","application/x-www-form-urlencoded");
    try{
      try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}
      int status=c.getResponseCode();
      String response=readBody(status<400?c.getInputStream():c.getErrorStream(),128*1024);
      if(status>=400)throw new IOException("授权服务返回 "+status+"："+response.substring(0,Math.min(130,response.length())));
      return response.trim().length()>0?new JSONObject(response):new JSONObject();
    }finally{c.disconnect();}
  }
  private static JSONObject jsonGet(String url,String bearer)throws Exception{
    HttpURLConnection c=connect(url,"GET",25000);
    if(bearer!=null)c.setRequestProperty("Authorization","Bearer "+bearer);
    try{
      int status=c.getResponseCode();
      String response=readBody(status<400?c.getInputStream():c.getErrorStream(),1_000_000);
      if(status>=400)throw new IOException("服务返回 "+status+"："+response.substring(0,Math.min(160,response.length())));
      return new JSONObject(response);
    }finally{c.disconnect();}
  }
  private JSONObject validateIdToken(String jwt,String client,String nonce)throws Exception{
    String[] parts=jwt.split("\\.");
    if(parts.length!=3)throw new SecurityException("ID Token 格式无效");
    JSONObject header=new JSONObject(new String(un64(parts[0]),StandardCharsets.UTF_8));
    if(!"RS256".equals(header.optString("alg")))throw new SecurityException("不支持的签名算法");
    String kid=header.optString("kid");
    if(kid.length()==0)throw new SecurityException("缺少签名密钥标识");
    JSONObject jwks=jsonGet(AUTH+"/.well-known/jwks.json",null);
    JSONArray keys=jwks.optJSONArray("keys");
    if(keys==null)throw new SecurityException("无法获取签名密钥");
    JSONObject matching=null;
    for(int i=0;i<keys.length();i++){
      JSONObject k=keys.optJSONObject(i);
      if(k!=null&&kid.equals(k.optString("kid"))&&"RSA".equals(k.optString("kty"))){matching=k;break;}
    }
    if(matching==null)throw new SecurityException("无法验证签名密钥");
    BigInteger n=new BigInteger(1,un64(matching.getString("n")));
    BigInteger e=new BigInteger(1,un64(matching.getString("e")));
    PublicKey pub=KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n,e));
    Signature sig=Signature.getInstance("SHA256withRSA");
    sig.initVerify(pub);sig.update((parts[0]+"."+parts[1]).getBytes(StandardCharsets.US_ASCII));
    if(!sig.verify(un64(parts[2])))throw new SecurityException("ID Token 签名验证失败");
    JSONObject p=new JSONObject(new String(un64(parts[1]),StandardCharsets.UTF_8));
    long now=System.currentTimeMillis()/1000L;
    if(!AUTH.equals(p.optString("iss")))throw new SecurityException("授权签发方不匹配");
    Object aud=p.opt("aud");
    boolean audience=client.equals(aud);
    if(aud instanceof JSONArray)for(int i=0;i<((JSONArray)aud).length();i++)if(client.equals(((JSONArray)aud).optString(i)))audience=true;
    if(!audience)throw new SecurityException("客户端身份不匹配");
    if(p.optLong("exp",0)<now-30||p.optLong("iat",Long.MAX_VALUE)>now+300)throw new SecurityException("身份凭据过期或时间异常");
    if(!nonce.equals(p.optString("nonce"))||p.optString("sub").length()==0)throw new SecurityException("登录校验失败");
    return p;
  }

  void signIn(){
    if(signingIn){failure("login","已有登录请求，请完成或取消");return;}
    signingIn=true;emitStatus();
    io.execute(()->{
      try{
        JSONObject previous=load();
        String issued=previous.optString("client_id"),originalSub=previous.optString("sub");
        boolean newClient=issued.length()==0;
        String client=newClient?"dynamic_agent_client":issued;
        String verifier=randomValue(),state=randomValue(),nonce=randomValue();
        String challenge=b64(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        try(ServerSocket server=new ServerSocket()){
          server.setReuseAddress(false);
          server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0));
          server.setSoTimeout(180000);
          String redirect="http://127.0.0.1:"+server.getLocalPort()+"/auth/callback";
          Uri.Builder url=Uri.parse(AUTH+"/api/accounts/authorize").buildUpon()
            .appendQueryParameter("client_id",client)
            .appendQueryParameter("ext_agent_host_id",prefs.getString("hostId",""))
            .appendQueryParameter("response_type","code")
            .appendQueryParameter("redirect_uri",redirect)
            .appendQueryParameter("scope",SCOPES)
            .appendQueryParameter("resource",RESOURCE)
            .appendQueryParameter("state",state)
            .appendQueryParameter("nonce",nonce)
            .appendQueryParameter("code_challenge",challenge)
            .appendQueryParameter("code_challenge_method","S256");
          if(newClient)url.appendQueryParameter("agent_name_hint","知序");
          else{
            if(previous.optString("id_token").length()>0)url.appendQueryParameter("id_token_hint",previous.optString("id_token"));
            if(previous.optString("email").length()>0)url.appendQueryParameter("login_hint",previous.optString("email"));
          }
          final Uri authUrl=url.build();
          activity.runOnUiThread(()->{
            try{activity.startActivity(new Intent(Intent.ACTION_VIEW,authUrl));}
            catch(Exception e){failure("login","无法打开系统浏览器："+safeErr(e));try{server.close();}catch(Exception ignored){}}
          });
          Uri callback=null;
          for(int request=0;request<8;request++){
            try(Socket socket=server.accept()){
              socket.setSoTimeout(15000);
              BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
              String line=reader.readLine();
              String path="";
              if(line!=null){
                String[] parts=line.split(" ");
                if(parts.length>=2)path=parts[1];
              }
              boolean valid=path.startsWith("/auth/callback?");
              String page=valid?"登录处理完成。请返回知序查看结果。":"无效请求";
              byte[] html=("<html><meta charset='utf-8'><meta name='viewport' content='width=device-width'><body style='font-family:sans-serif;padding:35px'><h2>"+page+"</h2></body></html>").getBytes(StandardCharsets.UTF_8);
              OutputStream out=socket.getOutputStream();
              String response="HTTP/1.1 "+(valid?"200 OK":"404 Not Found")+"\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nContent-Length: "+html.length+"\r\nConnection: close\r\n\r\n";
              out.write(response.getBytes(StandardCharsets.US_ASCII));out.write(html);out.flush();
              if(valid){callback=Uri.parse("http://127.0.0.1"+path);break;}
            }
          }
          if(callback==null)throw new IOException("未收到授权回调");
          if(!state.equals(callback.getQueryParameter("state")))throw new SecurityException("登录状态不匹配");
          String oauthError=callback.getQueryParameter("error");
          if(oauthError!=null)throw new SecurityException("授权未完成："+oauthError);
          String code=callback.getQueryParameter("code");
          if(code==null||code.isEmpty())throw new SecurityException("未收到授权码");
          String callbackClient=callback.getQueryParameter("client_id");
          String currentClient;
          if(newClient){
            if(callbackClient==null||!callbackClient.startsWith("oaiapp_"))throw new SecurityException("客户端注册尚未完成");
            currentClient=callbackClient;
          }else{
            if(callbackClient!=null&&!issued.equals(callbackClient))throw new SecurityException("客户端注册不匹配");
            currentClient=issued;
          }
          JSONObject tokens=formPost(TOKEN,new String[][]{
            {"grant_type","authorization_code"},{"client_id",currentClient},{"code",code},
            {"code_verifier",verifier},{"redirect_uri",redirect},{"resource",RESOURCE}});
          String idToken=tokens.optString("id_token");
          if(idToken.isEmpty())throw new SecurityException("缺少可验证身份凭据");
          JSONObject person=validateIdToken(idToken,currentClient,nonce);
          if(!newClient&&originalSub.length()>0&&!originalSub.equals(person.getString("sub")))
            throw new SecurityException("登录的账号与已保存的账号不同");
          JSONObject record=new JSONObject();
          record.put("client_id",currentClient).put("sub",person.getString("sub"));
          record.put("email",person.optString("email")).put("name",person.optString("name"));
          record.put("id_token",idToken).put("access_token",tokens.getString("access_token"))
            .put("refresh_token",tokens.optString("refresh_token"))
            .put("scope",tokens.optString("scope"))
            .put("expires_at",System.currentTimeMillis()+tokens.optLong("expires_in",3600)*1000L);
          if(!newClient)record.put("model",previous.optString("model"));
          persist(record);
          if(!hasScope(record.optString("scope"),"chatgpt.tokens.use.direct"))
            failure("login","已登录，但尚未授权使用 ChatGPT 会员额度，请重新授权");
          emitStatus();
          if(hasScope(record.optString("scope"),"chatgpt.tokens.use.direct"))loadModelsInside();
        }
      }catch(Exception e){failure("login",safeErr(e));}
      finally{signingIn=false;emitStatus();}
    });
  }
  private synchronized JSONObject validSession()throws Exception{
    JSONObject record=load();
    if(record.optString("access_token").length()==0)throw new SecurityException("请先在设置中连接 ChatGPT");
    if(!hasScope(record.optString("scope"),"chatgpt.tokens.use.direct"))throw new SecurityException("尚未授权会员额度");
    if(record.optLong("expires_at",0)>System.currentTimeMillis()+90_000)return record;
    String refresh=record.optString("refresh_token");
    if(refresh.length()==0)throw new SecurityException("登录已过期，请重新授权");
    JSONObject updated=formPost(TOKEN,new String[][]{{"grant_type","refresh_token"},
      {"client_id",record.getString("client_id")},{"refresh_token",refresh},{"resource",RESOURCE}});
    record.put("access_token",updated.getString("access_token"));
    record.put("refresh_token",updated.getString("refresh_token"));
    record.put("id_token",updated.optString("id_token",record.optString("id_token")));
    record.put("scope",updated.optString("scope",record.optString("scope")));
    record.put("expires_at",System.currentTimeMillis()+updated.optLong("expires_in",3600)*1000L);
    persist(record);return record;
  }
  void listModels(){io.execute(()->{try{loadModelsInside();}catch(Exception e){failure("models",safeErr(e));}});}
  private void loadModelsInside()throws Exception{
    JSONObject session=validSession();
    JSONObject response=jsonGet(RESOURCE+"/models",session.getString("access_token"));
    JSONArray models=response.optJSONArray("models");
    if(models==null)throw new IOException("模型列表格式不受支持");
    JSONArray shown=new JSONArray();
    for(int i=0;i<models.length();i++){
      JSONObject m=models.optJSONObject(i);
      if(m==null)continue;
      if(!"list".equals(m.optString("visibility")))continue;
      String slug=m.optString("slug");if(slug.isEmpty())continue;
      shown.put(new JSONObject().put("slug",slug).put("name",m.optString("display_name",slug)));
    }
    if(shown.length()==0)throw new IOException("此账号暂无可调用的模型");
    String selected=session.optString("model");boolean exists=false;
    for(int i=0;i<shown.length();i++)if(selected.equals(shown.getJSONObject(i).optString("slug")))exists=true;
    if(!exists){selected=shown.getJSONObject(0).getString("slug");session.put("model",selected);persist(session);}
    event(new JSONObject().put("type","models").put("models",shown).put("selected",selected));
    emitStatus();
  }
  void selectModel(String slug){
    io.execute(()->{
      try{
        if(slug.length()==0||slug.length()>100||!slug.matches("[A-Za-z0-9._:/-]+"))throw new IOException("模型名称无效");
        // Model selection must originate from the account-specific catalog.
        JSONObject models=jsonGet(RESOURCE+"/models",validSession().getString("access_token"));
        JSONArray all=models.optJSONArray("models");boolean allowed=false;
        if(all!=null)for(int i=0;i<all.length();i++){JSONObject m=all.optJSONObject(i);if(m!=null&&slug.equals(m.optString("slug"))&&"list".equals(m.optString("visibility")))allowed=true;}
        if(!allowed)throw new IOException("该模型不在当前账号可用列表");
        JSONObject record=load();record.put("model",slug);persist(record);emitStatus();
      }catch(Exception e){failure("model",safeErr(e));}
    });
  }
  void inference(String payload){
    if(working){failure("inference","已有一项 AI 任务在运行");return;}
    working=true;emitStatus();
    io.execute(()->{
      String requestId="";
      try{
        JSONObject task=new JSONObject(payload);
        requestId=task.optString("id",UUID.randomUUID().toString());
        String action=task.optString("action");
        String doc=task.optString("document");
        if(doc.length()<8)throw new IOException("请先填写有效的文字稿或笔记");
        if(doc.length()>60000)throw new IOException("文字内容过长，请先拆分");
        if(!action.equals("note")&&!action.equals("qa")&&!action.equals("cards"))throw new IOException("不支持的 AI 操作");
        JSONObject session=validSession();
        String model=session.optString("model");
        if(model.length()==0)throw new IOException("请先获取并选择模型");
        String question=task.optString("question");
        String instructions,content;
        if(action.equals("note")){
          String style=task.optString("style","detail");
          String styleText="brief".equals(style)?"精简版：只保留核心观点和5-8个关键点":
            "book".equals(style)?"教材版：按概念、原理、解释、例子、条件与总结组织章节":
              "详细版：按主题、分章节、观点、解释、例子、结论组织笔记";
          instructions="你是严谨的中文学习资料编辑。请直接输出完整的 Markdown 笔记，不写客套话。不得编造来源、数字或讲话者没有说过的内容。补充推论须标明为AI补充。"+styleText;
          content="资料标题："+task.optString("title")+"\n原始文字稿：\n"+doc;
        }else if(action.equals("qa")){
          if(question.trim().length()==0)throw new IOException("请先输入问题");
          instructions="你是严谨的个人知识学习助手。优先根据用户提供的学习笔记回答，明确区分笔记中的内容和你的扩展知识。找不到依据就直说。请简洁清楚地使用中文。";
          content="当前资料：\n"+doc+"\n\n用户问题："+question;
        }else{
          instructions="请根据下方笔记生成4到6张知识复习卡片。只输出JSON数组，不要Markdown代码块，格式：[{'question':'...','answer':'...'}]，注意实际输出必须使用JSON双引号。题目应考察概念与理解，不得编造原文没有的事实。";
          content="当前学习笔记：\n"+doc;
        }
        JSONObject body=new JSONObject();
        body.put("model",model).put("instructions",instructions);
        body.put("input",new JSONArray().put(new JSONObject().put("role","user").put("content",content)));
        body.put("store",false).put("stream",true);
        String text=streamResponse(session.getString("access_token"),body);
        event(new JSONObject().put("type","response").put("action",action).put("id",requestId).put("text",text));
      }catch(Exception e){
        try{event(new JSONObject().put("type","error").put("operation","inference").put("id",requestId).put("message",safeErr(e)));}catch(Exception ignored){}
      }finally{working=false;emitStatus();}
    });
  }
  private String streamResponse(String bearer,JSONObject body)throws Exception{
    HttpURLConnection c=connect(RESOURCE+"/responses","POST",180000);
    c.setDoOutput(true);
    c.setRequestProperty("Authorization","Bearer "+bearer);
    c.setRequestProperty("Content-Type","application/json");
    c.setRequestProperty("Accept","text/event-stream");
    try{
      try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}
      int status=c.getResponseCode();
      if(status>=400){
        String msg=readBody(c.getErrorStream(),50000);
        throw new IOException("GPT 请求失败（"+status+"）："+msg.substring(0,Math.min(msg.length(),230)));
      }
      boolean completed=false;
      StringBuilder text=new StringBuilder();
      String doneText="";
      try(BufferedReader reader=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8))){
        String line;while((line=reader.readLine())!=null){
          if(!line.startsWith("data: "))continue;
          String data=line.substring(6).trim();
          if(data.equals("[DONE]"))break;
          JSONObject ev;
          try{ev=new JSONObject(data);}catch(Exception ignored){continue;}
          String type=ev.optString("type");
          if(type.equals("response.output_text.delta"))text.append(ev.optString("delta"));
          if(type.equals("response.output_text.done"))doneText=ev.optString("text");
          if(type.equals("response.completed")){completed=true;break;}
          if(type.equals("response.failed")||type.equals("response.incomplete")||type.equals("error"))
            throw new IOException("GPT 生成中断："+data.substring(0,Math.min(170,data.length())));
        }
      }
      if(!completed)throw new IOException("响应未正常完成，未保存不完整结果");
      String result=text.length()>0?text.toString():doneText;
      if(result.trim().isEmpty())throw new IOException("模型未返回文字");
      return result.trim();
    }finally{c.disconnect();}
  }
  void signOut(){
    io.execute(()->{
      JSONObject record=load();String refresh=record.optString("refresh_token");
      boolean remotelyRevoked=refresh.isEmpty();
      try{
        if(!refresh.isEmpty()){
          JSONObject config=jsonGet(AUTH+"/.well-known/openid-configuration",null);
          String revocation=config.getString("revocation_endpoint");
          if(!revocation.startsWith(AUTH+"/"))throw new SecurityException("撤销端点不受信任");
          formPost(revocation,new String[][]{{"token",refresh},{"token_type_hint","refresh_token"},{"client_id",record.getString("client_id")}});
          remotelyRevoked=true;
        }
      }catch(Exception ignored){}
      try{
        // Preserve the verified registration mapping and stable host ID.
        JSONObject out=new JSONObject();
        out.put("client_id",record.optString("client_id")).put("sub",record.optString("sub"));
        out.put("email",record.optString("email")).put("name",record.optString("name"));
        persist(out);
      }catch(Exception e){prefs.edit().remove(CREDENTIALS).apply();}
      emitStatus();
      if(!remotelyRevoked)failure("logout","已从本机退出；网络撤销未确认，请在 ChatGPT 设置中检查应用授权");
    });
  }
  void destroy(){destroyed=true;io.shutdownNow();}
}
