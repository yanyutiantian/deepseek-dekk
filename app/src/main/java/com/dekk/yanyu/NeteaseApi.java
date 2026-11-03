package com.dekk.yanyu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 网易云明文接口（同 ilke）：搜索、歌曲详情、播放地址、歌词、账号信息。
 * 只依赖 HttpURLConnection + org.json，播放地址会校验域名必须是 *.music.126.net。
 */
final class NeteaseApi {
    static final int BR_HIGH=320000;
    static final int BR_MID=192000;
    static final int BR_LOW=128000;

    static final class Reply {
        final boolean ok;
        final int code;
        final String error;
        final JSONObject body;
        Reply(boolean ok,int code,String error,JSONObject body){
            this.ok=ok;this.code=code;this.error=error;this.body=body;
        }
        boolean needLogin(){return code==301||code==302||code==250;}
    }

    static final class Track {
        String id="";
        String name="";
        String artists="";
        String album="";
        long duration;
        String cover="";
        boolean trial;
        String label(){return artists.length()==0?name:name+" - "+artists;}
    }

    static final class Audio {
        String url="";
        int br;
        long size;
    }

    private NeteaseApi() { }

    private static Map<String,String> form(String cookie,String... pairs){
        Map<String,String> values=new LinkedHashMap<String,String>();
        for(int i=0;i+1<pairs.length;i+=2)values.put(pairs[i],pairs[i+1]);
        String csrf=NeteaseCookie.csrf(cookie);
        if(csrf.length()>0)values.put("csrf_token",csrf);
        return values;
    }

    static Reply call(String path,Map<String,String> values,String cookie) throws Exception{
        NeteaseHttp.Response response=NeteaseHttp.post(path,values,cookie);
        String body=response.body;
        if(body==null||body.length()==0){
            log(path,response.status,-1,"(空响应)");
            return new Reply(false,-1,"接口没有返回内容(HTTP "+response.status+")",null);
        }
        JSONObject json;
        try{json=new JSONObject(body);}catch(Throwable t){
            log(path,response.status,-1,body);
            return new Reply(false,-1,"返回不是 JSON",null);
        }
        int code=json.optInt("code",response.ok()?200:-1);
        boolean ok=response.ok()&&(code==200||code==0||code==-1);
        String error=ok?"":json.optString("msg",json.optString("message","请求失败("+code+")"));
        log(path,response.status,code,body);
        return new Reply(ok,code,error,json);
    }

    /** 打一条不含 Cookie 的接口日志，方便真机排查（body 截断）。 */
    private static void log(String path,int status,int code,String body){
        String snippet=body==null?"":(body.length()>240?body.substring(0,240)+"…":body);
        HotBridge.log("[DEKK][一起听] POST "+path
                +" -> http="+status+" code="+code+" "+snippet.replace('\n',' '));
    }

    /** 搜索单曲：POST /api/search/get */
    static Reply search(String keyword,int limit,String cookie) throws Exception{
        return call("/api/search/get",form(cookie,"s",keyword,"type","1","limit",String.valueOf(limit),"offset","0"),cookie);
    }

    static List<Track> parseSongs(Reply reply){
        List<Track> list=new ArrayList<Track>();
        if(!reply.ok||reply.body==null)return list;
        JSONObject result=reply.body.optJSONObject("result");
        JSONArray songs=result==null?null:result.optJSONArray("songs");
        if(songs==null)return list;
        for(int i=0;i<songs.length();i++){
            JSONObject item=songs.optJSONObject(i);
            if(item==null)continue;
            list.add(track(item));
        }
        return list;
    }

    /** 歌曲详情：POST /api/song/detail */
    static Reply detail(String id,String cookie) throws Exception{
        return call("/api/song/detail",form(cookie,"ids","["+id+"]"),cookie);
    }

    static Track parseDetail(Reply reply){
        if(!reply.ok||reply.body==null)return null;
        JSONArray songs=reply.body.optJSONArray("songs");
        if(songs==null||songs.length()==0)return null;
        JSONObject item=songs.optJSONObject(0);
        return item==null?null:track(item);
    }

    /** 播放地址：先试 weapi 加密接口（明文 /api 版对很多歌会返回空 url），再退回明文接口。 */
    static Reply playerUrl(String id,int br,String cookie) throws Exception{
        Reply encrypted=null;
        try{
            org.json.JSONObject body=new org.json.JSONObject();
            body.put("ids","["+id+"]");
            body.put("br",br);
            encrypted=weapiCall("/weapi/song/enhance/player/url",body,cookie);
        }catch(Throwable t){
            HotBridge.log("[DEKK][一起听] weapi 取地址异常: "+t.getClass().getSimpleName());
        }
        if(encrypted!=null&&encrypted.ok&&hasUrl(encrypted))return encrypted;
        Reply plain=call("/api/song/enhance/player/url",
                form(cookie,"ids","["+id+"]","br",String.valueOf(br)),cookie);
        if(plain.ok&&hasUrl(plain))return plain;
        return encrypted!=null&&encrypted.body!=null?encrypted:plain;
    }

    private static Reply weapiCall(String path,org.json.JSONObject json,String cookie) throws Exception{
        java.util.Map<String,String> values=NeteaseCrypto.weapiForm(json.toString(),NeteaseCookie.csrf(cookie));
        NeteaseHttp.Response response=NeteaseHttp.post(path,values,cookie);
        String body=response.body;
        if(body==null||body.length()==0){
            log(path,response.status,-1,"(空响应)");
            return new Reply(false,-1,"接口没有返回内容(HTTP "+response.status+")",null);
        }
        org.json.JSONObject parsed;
        try{parsed=new org.json.JSONObject(body);}catch(Throwable t){
            log(path,response.status,-1,body);
            return new Reply(false,-1,"返回不是 JSON",null);
        }
        int code=parsed.optInt("code",response.ok()?200:-1);
        boolean ok=response.ok()&&(code==200||code==0||code==-1);
        log(path,response.status,code,body);
        return new Reply(ok,code,ok?"":parsed.optString("msg","请求失败("+code+")"),parsed);
    }

    static boolean hasUrl(Reply reply){
        if(reply==null||reply.body==null)return false;
        org.json.JSONArray data=reply.body.optJSONArray("data");
        if(data==null||data.length()==0)return false;
        org.json.JSONObject item=data.optJSONObject(0);
        if(item==null)return false;
        String url=item.optString("url","");
        return url.length()>0;
    }

    /** 诊断用：把一次取地址的结果说清楚。 */
    static String describe(Reply reply){
        if(reply==null)return "没有响应";
        if(reply.body==null)return "http/JSON 异常："+reply.error;
        StringBuffer text=new StringBuffer();
        text.append("code=").append(reply.code);
        org.json.JSONArray data=reply.body.optJSONArray("data");
        if(data==null||data.length()==0){
            text.append(" data=空");
            return text.toString();
        }
        org.json.JSONObject item=data.optJSONObject(0);
        if(item==null)return text.append(" data[0]=空").toString();
        String url=item.optString("url","");
        text.append(" url=").append(url.length()==0?"空":("有("+url.length()+"字符)"))
                .append(" br=").append(item.optInt("br",0))
                .append(" size=").append(item.optLong("size",0L))
                .append(" code=").append(item.optInt("code",0));
        if(item.opt("freeTrialInfo")!=null&&!"null".equals(String.valueOf(item.opt("freeTrialInfo"))))
            text.append(" 试听限制=是");
        return text.toString();
    }

    /** 播放地址：解析 weapi/明文两次尝试里可用的那一个。 */
    static Audio audio(String id,int br,String cookie) throws Exception{
        Reply reply=playerUrl(id,br,cookie);
        if(reply==null||reply.body==null)return null;
        JSONArray data=reply.body.optJSONArray("data");
        if(data==null||data.length()==0)return null;
        JSONObject item=data.optJSONObject(0);
        if(item==null)return null;
        Audio audio=new Audio();
        audio.url=item.optString("url","");
        audio.br=item.optInt("br",br);
        audio.size=item.optLong("size",0L);
        if(audio.url.length()>0&&!trustedMedia(audio.url))audio.url="";
        return audio;
    }

    /** 歌词：POST /api/song/lyric */
    static String lyric(String id,String cookie) throws Exception{
        Reply reply=call("/api/song/lyric",form(cookie,"os","pc","id",id,"lv","-1","kv","-1","tv","-1"),cookie);
        if(!reply.ok||reply.body==null)return "";
        JSONObject lrc=reply.body.optJSONObject("lrc");
        return lrc==null?"":lrc.optString("lyric","");
    }

    /** 账号：POST /api/w/nuser/account/get */
    static Reply account(String cookie) throws Exception{
        return call("/api/w/nuser/account/get",form(cookie),cookie);
    }

    static String nickname(JSONObject body){
        if(body==null)return "";
        JSONObject profile=body.optJSONObject("profile");
        return profile==null?"":profile.optString("nickname","");
    }

    static String avatar(JSONObject body){
        if(body==null)return "";
        JSONObject profile=body.optJSONObject("profile");
        return profile==null?"":profile.optString("avatarUrl","");
    }

    static long userId(JSONObject body){
        if(body==null)return 0L;
        JSONObject profile=body.optJSONObject("profile");
        return profile==null?0L:profile.optLong("userId",0L);
    }

    private static boolean trustedMedia(String url){
        try{
            String host=new java.net.URL(url).getHost();
            if(host==null)return false;
            return host.equals("music.126.net")||host.endsWith(".music.126.net");
        }catch(Throwable t){
            return false;
        }
    }

    private static Track track(JSONObject item){
        Track track=new Track();
        track.id=item.optString("id",item.optString("songId",""));
        track.name=item.optString("name","");
        track.duration=item.optLong("duration",0L);
        JSONArray artists=item.optJSONArray("artists");
        if(artists==null)artists=item.optJSONArray("ar");
        StringBuilder names=new StringBuilder();
        if(artists!=null){
            for(int i=0;i<artists.length();i++){
                JSONObject artist=artists.optJSONObject(i);
                if(artist==null)continue;
                if(names.length()>0)names.append('/');
                names.append(artist.optString("name",""));
            }
        }
        track.artists=names.toString();
        JSONObject album=item.optJSONObject("album");
        if(album==null)album=item.optJSONObject("al");
        if(album!=null){
            track.album=album.optString("name","");
            track.cover=album.optString("picUrl",album.optString("blurPicUrl",""));
        }
        JSONObject privilege=item.optJSONObject("privilege");
        if(privilege!=null)track.trial=privilege.optInt("st",0)<0;
        return track;
    }
}