package com.dekk.yanyu;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 网易云接口的极简 HTTP：表单提交 + Set-Cookie 抓取，只用 HttpURLConnection。 */
final class NeteaseHttp {
    static final String ORIGIN="https://music.163.com";
    /**
     * 桌面浏览器 UA 池：每次登录随机挑一个（同一次登录里的所有请求共用同一个，
     * 免得身份前后不一致又被风控盯上）。
     */
    private static final String[] USER_AGENTS=new String[]{
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36 Edg/122.0.2365.66",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:122.0) Gecko/20100101 Firefox/122.0",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2.1 Safari/605.1.15",
    };
    /** 安卓客户端 UA 池：登录用这个身份，被拦时网易云会给可完成的验证页（-462）。 */
    private static final String[] APP_USER_AGENTS=new String[]{
            "NeteaseMusic/8.9.70.240701170529(8009070);Dalvik/2.1.0 (Linux; U; Android 13)",
            "NeteaseMusic/9.0.10.240726120251(8009010);Dalvik/2.1.0 (Linux; U; Android 13)",
            "NeteaseMusic/8.10.80.240612141535(8009080);Dalvik/2.1.0 (Linux; U; Android 12)",
    };
    private static volatile String userAgent=pickUserAgent(false);

    /** 随机挑一个 UA（appIdentity=true 时从客户端 UA 池里挑）。 */
    private static String pickUserAgent(boolean appIdentity){
        String[] pool=appIdentity?APP_USER_AGENTS:USER_AGENTS;
        return pool[new java.util.Random().nextInt(pool.length)];
    }

    /** 当前使用的 UA（WebView 加载验证页时也要用同一个）。 */
    static String userAgent(){return userAgent;}

    /** 换一个随机 UA：每次登录开始时调用一次。 */
    static String rollUserAgent(){
        userAgent=pickUserAgent(false);
        return userAgent;
    }

    /** 换一个随机"客户端"UA。 */
    static String rollAppUserAgent(){
        userAgent=pickUserAgent(true);
        return userAgent;
    }
    private static final int CONNECT_TIMEOUT_MS=8000;
    private static final int READ_TIMEOUT_MS=12000;
    private static final int MAX_BYTES=1024*1024;

    static final class Response {
        final int status;
        final String body;
        final String setCookie;
        Response(int status,String body,String setCookie){this.status=status;this.body=body;this.setCookie=setCookie;}
        boolean ok(){return status>=200&&status<300;}
    }

    private NeteaseHttp() { }

    static String encode(String text){
        try{return URLEncoder.encode(text,"UTF-8");}catch(UnsupportedEncodingException e){return text;}
    }

    static String form(Map<String,String> values){
        StringBuilder text=new StringBuilder();
        for(Map.Entry<String,String> entry:values.entrySet()){
            if(text.length()>0)text.append('&');
            text.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        return text.toString();
    }

    static Response post(String path,Map<String,String> form,String cookie) throws Exception{
        return request("POST",path,form,cookie,false);
    }

    /** 用安卓客户端身份发请求（登录专用：Referer/Origin 不带，Cookie 走 os=android）。 */
    static Response postAsApp(String path,Map<String,String> form,String cookie) throws Exception{
        return request("POST",path,form,cookie,true);
    }

    static Response get(String path,String query,String cookie) throws Exception{
        return request("GET",path+(query==null||query.length()==0?"":"?"+query),null,cookie,false);
    }

    /**
     * 补上 PC 网页端常用 Cookie。
     *
     * <p>要点：os / appver 必须和 UA 一致（PC 网页端 = os=pc + appver=2.9.7），
     * 并带上固定的设备标识（_ntes_nuid / _ntes_nnid / NMTID），
     * 否则登录和其它接口都容易被判成"异常环境"。
     */
    private static String withClientCookie(String cookie,boolean appIdentity){
        StringBuilder text=new StringBuilder(cookie==null?"":NeteaseCookie.normalize(cookie));
        java.util.List<String> pairs=new java.util.ArrayList<String>();
        if(appIdentity){
            pairs.add("os=android");
            pairs.add("appver=8.9.70");
            pairs.add("osver=13");
            pairs.add("channel=netease");
            String deviceId=NeteaseDevice.deviceId();
            if(deviceId.length()>0)pairs.add("deviceId="+deviceId);
        }else{
            pairs.add("os=pc");
            pairs.add("appver=2.9.7");
            pairs.add("channel=netease");
        }
        String device=NeteaseDevice.cookies();
        if(device.length()>0)for(String pair:device.split(";"))pairs.add(pair.trim());
        for(String pair:pairs){
            if(pair.length()==0)continue;
            int cut=pair.indexOf('=');
            if(cut<=0)continue;
            if(NeteaseCookie.pairs(text.toString()).containsKey(pair.substring(0,cut)))continue;
            if(text.length()>0)text.append("; ");
            text.append(pair);
        }
        return text.toString();
    }

    /**
     * 登录前的预热：先访问一次首页，让服务端下发 JSESSIONID-WYYY / NMTID 等 Cookie。
     * 直接裸着 POST 登录接口很容易被判成异常环境。
     */
    static String warmUp(){
        try{
            Response response=request("GET","/",null,null,false);
            return response.setCookie==null?"":response.setCookie;
        }catch(Throwable t){
            return "";
        }
    }

    private static Response request(String method,String path,Map<String,String> form,String cookie,
                                    boolean appIdentity) throws Exception{
        URL url=new URL(path.startsWith("http")?path:ORIGIN+path);
        HttpURLConnection connection=(HttpURLConnection)url.openConnection();
TlsDebug.apply(connection);
        try{
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent",userAgent);
            if(!appIdentity){
                connection.setRequestProperty("Referer",ORIGIN+"/");
                connection.setRequestProperty("Origin",ORIGIN);
            }
            connection.setRequestProperty("Accept","application/json, text/plain, */*");
            connection.setRequestProperty("Accept-Language","zh-CN,zh;q=0.9");
            String header=cookie==null||cookie.length()==0?withClientCookie("",appIdentity)
                    :withClientCookie(cookie,appIdentity);
            connection.setRequestProperty("Cookie",header);
            byte[] payload=null;
            if(form!=null){
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8");
                payload=form(form).getBytes("UTF-8");
                connection.setFixedLengthStreamingMode(payload.length);
                OutputStream out=connection.getOutputStream();
                try{out.write(payload);}finally{out.close();}
            }else{
                connection.setRequestMethod(method);
            }
            int status=connection.getResponseCode();
            InputStream stream=status>=400?connection.getErrorStream():connection.getInputStream();
            String body="";
            if(stream!=null){
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                try{
                    byte[] buffer=new byte[4096];
                    int read;
                    while((read=stream.read(buffer))!=-1){
                        bytes.write(buffer,0,read);
                        if(bytes.size()>MAX_BYTES)break;
                    }
                }finally{stream.close();}
                body=new String(bytes.toByteArray(),"UTF-8");
            }
            return new Response(status,body,joinCookies(connection));
        }finally{
            connection.disconnect();
        }
    }

    private static String joinCookies(HttpURLConnection connection){
        Map<String,List<String>> headers=connection.getHeaderFields();
        if(headers==null)return "";
        StringBuilder text=new StringBuilder();
        for(Map.Entry<String,List<String>> entry:headers.entrySet()){
            if(entry.getKey()==null||!"set-cookie".equalsIgnoreCase(entry.getKey()))continue;
            for(String value:entry.getValue()){
                if(value==null)continue;
                int cut=value.indexOf(';');
                String pair=cut>=0?value.substring(0,cut):value;
                if(pair.indexOf('=')<0)continue;
                if(text.length()>0)text.append("; ");
                text.append(pair.trim());
            }
        }
        return text.toString();
    }

    /** 合并新的 Set-Cookie 到已有 CK，同名覆盖。 */
    static String mergeCookie(String current,String fresh){
        Map<String,String> pairs=new LinkedHashMap<String,String>();
        addPairs(pairs,current);
        addPairs(pairs,fresh);
        return NeteaseCookie.build(pairs);
    }

    private static void addPairs(Map<String,String> target,String cookie){
        if(cookie==null)return;
        for(String part:cookie.split(";")){
            String pair=part.trim();
            int cut=pair.indexOf('=');
            if(cut<=0)continue;
            target.put(pair.substring(0,cut).trim(),pair.substring(cut+1).trim());
        }
    }
}