package com.dekk.yanyu;

import org.json.*;
import java.net.*;
import java.io.*;
import java.util.*;

/** QQ 音乐官网 musicu 接口；只向官方域发送账号 Cookie。 */
final class QqMusicApi {
    static String value(String cookie,String key){
        if(cookie==null)return "";
        for(String part:cookie.split(";")){int i=part.indexOf('=');if(i>0&&part.substring(0,i).trim().equals(key))return part.substring(i+1).trim();}
        return "";
    }
    static boolean valid(String c){return c!=null&&!c.contains("\r")&&!c.contains("\n")&&!value(c,"uin").isEmpty()&&(!value(c,"qm_keyst").isEmpty()||!value(c,"qqmusic_key").isEmpty());}
    static JSONObject call(String module,String method,JSONObject param,String cookie)throws Exception{
        String key=value(cookie,"qm_keyst");if(key.length()==0)key=value(cookie,"qqmusic_key");
        int hash=5381;for(int i=0;i<key.length();i++)hash+=(hash<<5)+key.charAt(i);
        JSONObject comm=new JSONObject().put("ct",24).put("cv",0).put("format","json").put("uin",value(cookie,"uin")).put("authst",key);
        JSONObject req=new JSONObject().put("comm",comm).put("req_0",new JSONObject().put("module",module).put("method",method).put("param",param));
        HttpURLConnection c=(HttpURLConnection)new URL("https://u.y.qq.com/cgi-bin/musicu.fcg?g_tk="+(hash&0x7fffffff)).openConnection();
TlsDebug.apply(c);
        c.setConnectTimeout(15000);c.setReadTimeout(20000);c.setInstanceFollowRedirects(false);c.setRequestMethod("POST");c.setDoOutput(true);
        c.setRequestProperty("Content-Type","application/json;charset=UTF-8");c.setRequestProperty("Referer","https://y.qq.com/");c.setRequestProperty("User-Agent","Mozilla/5.0");
        if(cookie!=null&&!cookie.contains("\r")&&!cookie.contains("\n"))c.setRequestProperty("Cookie",cookie);
        try{
            OutputStream out=c.getOutputStream();try{out.write(req.toString().getBytes("UTF-8"));}finally{out.close();}
            if(c.getResponseCode()!=200)throw new IOException("HTTP "+c.getResponseCode());
            InputStream in=c.getInputStream();ByteArrayOutputStream buf=new ByteArrayOutputStream();
            try{byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1){if(buf.size()+n>4*1024*1024)throw new IOException("响应过大");buf.write(bytes,0,n);}}finally{in.close();}
            JSONObject root=new JSONObject(buf.toString("UTF-8"));JSONObject reply=root.optJSONObject("req_0");
            if(root.optInt("code",-1)!=0||reply==null||reply.optInt("code",-1)!=0)throw new IOException("QQ音乐接口拒绝请求，可能需要重新登录或验证（"+(reply==null?root.optInt("code",-1):reply.optInt("code",-1))+"）");
            JSONObject data=reply.optJSONObject("data");if(data==null)throw new IOException("接口响应缺少 data");return data;
        }finally{c.disconnect();}
    }
    static NeteaseApi.Track track(JSONObject s){
        NeteaseApi.Track t=new NeteaseApi.Track();t.id="qq:"+s.optString("mid",s.optString("songmid"));t.name=s.optString("title",s.optString("name"));t.duration=s.optLong("interval")*1000;
        JSONArray singers=s.optJSONArray("singer");if(singers!=null)for(int i=0;i<singers.length();i++){JSONObject a=singers.optJSONObject(i);if(a!=null)t.artists+=(t.artists.length()==0?"":" / ")+a.optString("name");}
        JSONObject album=s.optJSONObject("album");if(album!=null){t.album=album.optString("name");String mid=album.optString("mid");if(mid.matches("[A-Za-z0-9]+"))t.cover="https://y.gtimg.cn/music/photo_new/T002R300x300M000"+mid+".jpg";}
        return t;
    }
    static List<NeteaseApi.Track> search(String word,String cookie)throws Exception{
        JSONObject d=call("music.search.SearchCgiService","DoSearchForQQMusicDesktop",new JSONObject().put("query",word).put("num_per_page",20).put("page_num",1).put("search_type",0),cookie);
        JSONArray songs=d.getJSONObject("body").getJSONObject("song").getJSONArray("list");List<NeteaseApi.Track> result=new ArrayList<NeteaseApi.Track>();
        for(int i=0;i<songs.length();i++)result.add(track(songs.getJSONObject(i)));return result;
    }
    static NeteaseApi.Track detail(String id,String cookie)throws Exception{
        JSONObject d=call("music.pf_song_detail_svr","get_song_detail_yqq",new JSONObject().put("song_mid",mid(id)),cookie);return track(d.getJSONObject("track_info"));
    }
    static String mid(String id){String m=id.startsWith("qq:")?id.substring(3):id;if(!m.matches("[A-Za-z0-9]+"))throw new IllegalArgumentException("无效歌曲 MID");return m;}
    static NeteaseApi.Audio audio(String id,String cookie,String guid)throws Exception{
        JSONObject p=new JSONObject().put("guid",guid).put("songmid",new JSONArray().put(mid(id))).put("songtype",new JSONArray().put(0)).put("uin",value(cookie,"uin")).put("loginflag",1).put("platform","20");
        JSONObject d=call("vkey.GetVkeyServer","CgiGetVkey",p,cookie);JSONArray info=d.optJSONArray("midurlinfo");NeteaseApi.Audio a=new NeteaseApi.Audio();
        if(info==null||info.length()==0)return a;String path=info.getJSONObject(0).optString("purl");if(path.length()==0)return a;
        JSONArray sip=d.optJSONArray("sip");String base=sip!=null&&sip.length()>0?sip.optString(0):"https://dl.stream.qqmusic.qq.com/";
        if(base.startsWith("http://"))base="https://"+base.substring(7);
        URL u=new URL(new URL(base),path);String host=u.getHost();if(!u.getProtocol().equals("https")||!(host.equals("qq.com")||host.endsWith(".qq.com")))throw new IOException("非官方播放地址");
        a.url=u.toString();a.br=128000;return a;
    }
    static String lyric(String id,String cookie)throws Exception{
        JSONObject d=call("music.musichallSong.PlayLyricInfo","GetPlayLyricInfo",new JSONObject().put("songMID",mid(id)).put("songID",0),cookie);
        String text=d.optString("lyric");if(text.startsWith("["))return text;
        return new String(android.util.Base64.decode(text,android.util.Base64.DEFAULT),"UTF-8");
    }
    static void verify(String cookie)throws Exception{
        if(!valid(cookie))throw new IOException("CK 缺少 uin 或音乐登录密钥");
        // 账号相关接口需要有效登录态；搜索和公开主页成功不能证明登录成功。
        call("music.UserInfo.userInfoServer","GetLoginUserInfo",new JSONObject(),cookie);
    }
}