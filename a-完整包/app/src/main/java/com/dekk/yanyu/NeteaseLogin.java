package com.dekk.yanyu;

import android.content.Context;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/**
 * 网易云手机号登录，多套方案依次尝试：
 * 1) weapi 加密 + 密码 2) 明文 api + 密码 3) weapi 加密 + 短信验证码 4) 明文 api + 短信验证码
 * 另有"粘贴 CK"入口。登录成功后把 CK（及其昵称/头像）加密存本地。
 */
final class NeteaseLogin {
    private static final String COUNTRY_CODE="86";
    /** 预热拿到的会话 Cookie（JSESSIONID-WYYY 等），登录/发码时带上可显著降低风控概率。 */
    private static String sessionCookie;

    private static String session(){
        if(webSessionCookie.length()>0)return webSessionCookie;
        // 登录不再做 PC 式预热：真机实测客户端身份"不带预热"也能拿到可完成的 -462 验证页，
        // 而混用 PC 会话反而可能触发 -460。
        return "";
    }

    /** 每次登录尝试换一个随机客户端 UA，并重新预热会话（身份和会话保持一致）。 */
    private static void beginAttempt(){
        String agent=NeteaseHttp.rollAppUserAgent();
        sessionCookie=null;
        webSessionCookie="";
        Log.line("本次登录使用客户端 UA："+(agent.length()>60?agent.substring(0,60)+"…":agent));
    }

    /** 验证页（WebView）里的会话 Cookie：重试登录必须带上，否则验证不生效。 */
    private static volatile String webSessionCookie="";

    static void adoptWebSession(String cookies){
        if(cookies==null||cookies.length()==0)return;
        webSessionCookie=cookies;
    }

    static final class Result {
        boolean ok;
        int code=-1;
        String message="";
        String cookie="";
        String nickname="";
        String avatar="";
        long userId;
        String scheme="";
        /** 被风控要求滑块验证时，这里是验证页地址（可能是空，用默认登录页兜底）。 */
        String captchaUrl="";
        boolean needCaptcha;

        static Result fail(int code,String message){
            Result result=new Result();
            result.code=code;
            result.message=message==null||message.length()==0?"登录失败("+code+")":message;
            return result;
        }
    }

    private NeteaseLogin() { }

    static boolean validPhone(String phone){
        if(phone==null)return false;
        String text=phone.trim();
        if(text.length()!=11)return false;
        for(int i=0;i<text.length();i++)if(!Character.isDigit(text.charAt(i)))return false;
        return text.startsWith("1");
    }

    /** 方案3/4：手机号 + 短信验证码。 */
    static Result loginByCaptcha(Context context,String phone,String captcha){
        return loginByCaptcha(context,phone,captcha,true);
    }

    static Result loginByCaptcha(Context context,String phone,String captcha,boolean fresh){
        if(!validPhone(phone))return Result.fail(-1,"请输入11位手机号");
        if(captcha==null||captcha.trim().length()<4)return Result.fail(-1,"请输入收到的验证码");
        if(fresh)beginAttempt();
        String code=captcha.trim();
        Result result=weapiLogin(context,phone,"captcha",code,"weapi+验证码");
        if(result.ok)return result;
        if(result.needCaptcha)return result;
        Log.line("weapi 验证码登录失败（"+result.message+"），改用明文接口重试");
        Result plain=apiLogin(context,phone,"captcha",code,"明文api+验证码");
        return plain.ok?plain:(plain.message.length()>0?plain:result);
    }

    /** 发送短信验证码（weapi → 明文两条通道，带客户端身份；被要求验证时把验证页带回去）。 */
    static Result sendCaptcha(String phone){
        if(!validPhone(phone))return Result.fail(-1,"请输入11位手机号");
        Result result=sendCaptchaAttempt(phone,true);
        if(result.ok||result.needCaptcha)return result;
        if(result.code==-460){
            Result other=sendCaptchaAttempt(phone,false);
            if(other.ok||other.needCaptcha||other.code!=result.code)return other;
        }
        return result;
    }

    private static Result sendCaptchaAttempt(String phone,boolean appIdentity){
        try{
            JSONObject json=new JSONObject();
            json.put("cellphone",phone);
            json.put("ctcode",COUNTRY_CODE);
            String body=NeteaseCrypto.weapi(json.toString(),false);
            Map<String,String> pairs=parsePairs(body);
            NeteaseHttp.Response response=appIdentity
                    ?NeteaseHttp.postAsApp("/weapi/sms/captcha/sent",pairs,session())
                    :NeteaseHttp.post("/weapi/sms/captcha/sent",pairs,session());
            Result first=readResult(response,"weapi+验证码");
            if(first.ok||first.needCaptcha)return first;
            Log.line("weapi 发码失败（"+first.message+"），改用明文接口重试");
        }catch(Throwable t){
            Log.line("weapi 发码异常: "+t.getClass().getSimpleName());
        }
        try{
            Map<String,String> values=plain("cellphone",phone,"ctcode",COUNTRY_CODE);
            NeteaseHttp.Response response=appIdentity
                    ?NeteaseHttp.postAsApp("/api/sms/captcha/sent",values,session())
                    :NeteaseHttp.post("/api/sms/captcha/sent",values,session());
            return readResult(response,"明文api+验证码");
        }catch(Throwable t){
            return Result.fail(-1,"发码失败: "+t.getClass().getSimpleName());
        }
    }

    /** 方案5：用户自己粘贴 CK（可从浏览器/其它工具复制）。 */
    static Result loginByCookie(Context context,String rawCookie){
        String cookie=NeteaseCookie.normalize(rawCookie);
        if(!NeteaseCookie.valid(cookie))return Result.fail(-1,"CK 里没有有效的 MUSIC_U，请重新复制");
        Result result=new Result();
        result.ok=true;
        result.code=200;
        result.cookie=cookie;
        result.scheme="粘贴 CK";
        fillProfile(result);
        persist(context,result);
        Log.line("CK 已保存："+NeteaseCookie.masked(cookie));
        return result;
    }

    /** 用已保存的 CK 刷新昵称/头像；CD 失效时返回 failed 并清空本地账号。 */
    static Result refresh(Context context){
        NeteaseAccount.Info info=NeteaseAccount.load(context);
        if(!NeteaseCookie.valid(info.cookie))return Result.fail(-1,"未登录");
        Result result=new Result();
        result.ok=true;
        result.code=200;
        result.cookie=info.cookie;
        result.scheme="已登录";
        result.nickname=info.name;
        result.avatar=info.avatar;
        result.userId=info.userId;
        try{
            NeteaseApi.Reply reply=NeteaseApi.account(info.cookie);
            if(!reply.ok){
                if(reply.needLogin()){
                    NeteaseAccount.clear(context);
                    Log.line("CK 已失效，需要重新登录");
                    return Result.fail(reply.code,"登录已过期，请重新登录网易云");
                }
                result.message=reply.error;
                return result;
            }
            result.nickname=NeteaseApi.nickname(reply.body);
            result.avatar=NeteaseApi.avatar(reply.body);
            result.userId=NeteaseApi.userId(reply.body);
            persist(context,result);
        }catch(Throwable t){
            result.message="网络异常: "+t.getClass().getSimpleName();
        }
        return result;
    }

    private static Result weapiLogin(Context context,String phone,String field,String value,String scheme){
        Result result=weapiAttempt(context,phone,field,value,scheme,true);
        if(result.ok||result.needCaptcha)return result;
        if(result.code==-460){
            // 客户端身份也被判"网络异常"：换 PC 身份再试一次（两个风控桶未必同时拦）
            Log.line("客户端身份被 -460 拦下，改用 PC 身份重试");
            Result other=weapiAttempt(context,phone,field,value,scheme,false);
            if(other.ok||other.needCaptcha||other.code!=result.code)return other;
        }
        return result;
    }

    private static Result weapiAttempt(Context context,String phone,String field,String value,
                                       String scheme,boolean appIdentity){
        try{
            JSONObject json=new JSONObject();
            json.put("phone",phone);
            json.put("countrycode",COUNTRY_CODE);
            json.put(field,value);
            json.put("rememberLogin","true");
            if("password".equals(field))json.put("csrf_token","");
            Map<String,String> pairs=parsePairs(NeteaseCrypto.weapi(json.toString(),false));
            NeteaseHttp.Response response=appIdentity
                    ?NeteaseHttp.postAsApp("/weapi/login/cellphone",pairs,session())
                    :NeteaseHttp.post("/weapi/login/cellphone",pairs,session());
            return finish(context,response,scheme);
        }catch(Throwable t){
            return Result.fail(-1,"weapi 登录异常: "+t.getClass().getSimpleName());
        }
    }

    private static Result apiLogin(Context context,String phone,String field,String value,String scheme){
        Result result=apiAttempt(context,phone,field,value,scheme,true);
        if(result.ok||result.needCaptcha)return result;
        if(result.code==-460){
            Result other=apiAttempt(context,phone,field,value,scheme,false);
            if(other.ok||other.needCaptcha||other.code!=result.code)return other;
        }
        return result;
    }

    private static Result apiAttempt(Context context,String phone,String field,String value,
                                     String scheme,boolean appIdentity){
        try{
            Map<String,String> values=plain("phone",phone,"countrycode",COUNTRY_CODE,
                    field,value,"rememberLogin","true");
            NeteaseHttp.Response response=appIdentity
                    ?NeteaseHttp.postAsApp("/api/login/cellphone",values,session())
                    :NeteaseHttp.post("/api/login/cellphone",values,session());
            return finish(context,response,scheme);
        }catch(Throwable t){
            return Result.fail(-1,"明文登录异常: "+t.getClass().getSimpleName());
        }
    }

    private static Result finish(Context context,NeteaseHttp.Response response,String scheme){
        Result result=readResult(response,scheme);
        if(!result.ok)return result;
        String cookie=NeteaseCookie.normalize(result.cookie);
        if(!NeteaseCookie.valid(cookie)){
            return Result.fail(result.code,"登录成功但没拿到有效 CK，请改用「粘贴 CK」方式");
        }
        result.cookie=cookie;
        result.scheme=scheme;
        fillProfile(result);
        persist(context,result);
        Log.line("登录成功（"+scheme+"）："+NeteaseCookie.masked(cookie)
                +(result.nickname.length()==0?"":"，账号 "+result.nickname));
        return result;
    }

    private static Result readResult(NeteaseHttp.Response response,String scheme){
        Result result=new Result();
        result.scheme=scheme;
        result.cookie=response.setCookie==null?"":response.setCookie;
        if(response.body==null||response.body.length()==0){
            return Result.fail(-1,"接口没有返回内容（HTTP "+response.status+"）");
        }
        try{
            JSONObject json=new JSONObject(response.body);
            result.code=json.optInt("code",-1);
            result.message=json.optString("msg",json.optString("message",""));
            result.ok=result.code==200;
            result.captchaUrl=captchaUrlOf(json);
            // 只有当服务端明确要求验证（501 / -462）才弹滑块。
            // 返回体里恰好带个 url 字段不算"要验证"，否则发验证码这种正常请求会被误弹。
            result.needCaptcha=result.code==501||result.code==-462;
        }catch(Throwable t){
            return Result.fail(-1,"返回不是 JSON（HTTP "+response.status+"）");
        }
        if(!result.ok)result.message=hint(result.code,result.message);
        return result;
    }

    /** 从返回体里找验证页地址（网易云不同版本放在不同字段）。 */
    private static String captchaUrlOf(JSONObject json){
        for(String key:new String[]{"url","captchaUrl","verifyUrl","redirectUrl"}){
            String value=json.optString(key,"");
            if(looksLikeUrl(value))return value;
        }
        JSONObject data=json.optJSONObject("data");
        if(data!=null){
            for(String key:new String[]{"url","captchaUrl","verifyUrl","redirectUrl"}){
                String value=data.optString(key,"");
                if(looksLikeUrl(value))return value;
            }
        }
        return "";
    }

    private static boolean looksLikeUrl(String value){
        return value!=null&&value.startsWith("http")&&value.indexOf("music.163.com")>=0;
    }

    /** 验证页兜底地址：没拿到具体 URL 时直接打开登录页让用户滑/登。 */
    static String defaultCaptchaUrl(){return NeteaseHttp.ORIGIN+"/login";}

    /** 给 WebView 用的 Cookie：身份必须和登录请求一致（客户端身份 + 固定设备标识）。 */
    static String webCookie(){
        java.util.LinkedHashMap<String,String> pairs=new java.util.LinkedHashMap<String,String>();
        addCookiePairs(pairs,session());
        addCookiePairs(pairs,"os=android; appver=8.9.70; osver=13; channel=netease");
        String deviceId=NeteaseDevice.deviceId();
        if(deviceId.length()>0)pairs.put("deviceId",deviceId);
        addCookiePairs(pairs,NeteaseDevice.cookies());
        return NeteaseCookie.build(pairs);
    }

    private static void addCookiePairs(java.util.Map<String,String> target,String cookie){
        if(cookie==null||cookie.length()==0)return;
        for(String part:cookie.split(";")){
            String pair=part.trim();
            int cut=pair.indexOf('=');
            if(cut<=0)continue;
            target.put(pair.substring(0,cut),pair.substring(cut+1));
        }
    }

    /** 把网易云的风控返回码翻成"还能怎么登录"的可读提示。 */
    private static String hint(int code,String message){
        String raw=message==null||message.length()==0?"":message;
        if(code==-460){
            return "网易云风控：网络环境存在异常（-460）"+(raw.length()==0?"":"："+raw)
                    +"。可改用「短信登录」或「粘贴 CK」；换个网络（关代理/切数据流量）后再试也常能通过。";
        }
        if(code==501){
            return "网易云要求滑块验证（501）"+(raw.length()==0?"":"："+raw)
                    +"。建议改用「短信登录」或「粘贴 CK」。";
        }
        if(code==-462){
            return "网易云要求安全验证（-462）"+(raw.length()==0?"":"："+raw)
                    +"。按提示完成滑动验证后会自动继续登录（同一次会话里验证才算数）。";
        }
        if(code==8821||code==-447){
            return "网易云拒绝本次登录（"+code+"）"+(raw.length()==0?"":"："+raw)
                    +"。建议改用「短信登录」或「粘贴 CK」。";
        }
        return raw.length()==0?"登录失败("+code+")":raw;
    }

    private static void fillProfile(Result result){
        try{
            NeteaseApi.Reply reply=NeteaseApi.account(result.cookie);
            if(!reply.ok)return;
            result.nickname=NeteaseApi.nickname(reply.body);
            result.avatar=NeteaseApi.avatar(reply.body);
            result.userId=NeteaseApi.userId(reply.body);
        }catch(Throwable t){
            Log.line("账号信息读取失败: "+t.getClass().getSimpleName());
        }
    }

    private static void persist(Context context,Result result){
        NeteaseAccount.Info info=new NeteaseAccount.Info();
        info.cookie=result.cookie;
        info.name=result.nickname;
        info.avatar=result.avatar;
        info.userId=result.userId;
        info.savedAt=System.currentTimeMillis();
        NeteaseAccount.save(context,info);
    }

    private static Map<String,String> plain(String... pairs){
        Map<String,String> values=new LinkedHashMap<String,String>();
        for(int i=0;i+1<pairs.length;i+=2)values.put(pairs[i],pairs[i+1]);
        return values;
    }

    /** weapi 的返回值形如 params=...&encSecKey=...，这里还原成表单键值。 */
    private static Map<String,String> parsePairs(String body){
        Map<String,String> values=new LinkedHashMap<String,String>();
        for(String part:body.split("&")){
            int cut=part.indexOf('=');
            if(cut<=0)continue;
            values.put(part.substring(0,cut),decode(part.substring(cut+1)));
        }
        return values;
    }

    private static String decode(String text){
        try{return java.net.URLDecoder.decode(text,"UTF-8");}catch(Throwable t){return text;}
    }

    private static final class Log {
        static void line(String message){HotBridge.log("[DEKK][一起听] "+message);}
    }
}