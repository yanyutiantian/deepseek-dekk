package com.dekk.yanyu;

import android.content.Context;
import java.util.List;

/** 接口诊断：把账号、搜索、详情、取地址（三个码率）的结果汇总成一段可复制的文本。 */
final class NeteaseDiag {
    private NeteaseDiag() { }

    static String run(Context context,String keyword){
        StringBuilder out=new StringBuilder();
        String cookie=NeteaseAccount.cookie(context);
        out.append("CK：").append(NeteaseCookie.masked(cookie)).append('\n');
        out.append("登录状态：").append(NeteaseCookie.valid(cookie)?"有效":"未登录或无效").append('\n');
        out.append("设备标识：").append(NeteaseDevice.cookies().length()==0?"未生成":"已生成")
                .append('\n');
        out.append("当前会话：").append(MusicSend.hasSession()?"已捕捉（一起听可收发）":"未捕捉（进聊天页发一条消息再试）")
                .append('\n');
        out.append("迷你条：").append(MusicMiniBar.attached()?"已挂载":"未挂载（需在聊天页且开关打开）")
                .append("\n\n");

        try{
            NeteaseApi.Reply account=NeteaseApi.account(cookie);
            out.append("① 账号接口：code=").append(account.code)
                    .append(" 昵称=").append(NeteaseApi.nickname(account.body))
                    .append(account.ok?"":" 错误="+account.error)
                    .append('\n');
        }catch(Throwable t){
            out.append("① 账号接口异常：").append(t.getClass().getSimpleName()).append('\n');
        }

        String songId="";
        String word=keyword==null||keyword.length()==0?"周杰伦":keyword;
        try{
            NeteaseApi.Reply search=NeteaseApi.search(word,3,cookie);
            List<NeteaseApi.Track> tracks=NeteaseApi.parseSongs(search);
            out.append("② 搜索「").append(word).append("」：code=").append(search.code)
                    .append(" 结果=").append(tracks.size()).append(" 条")
                    .append(search.ok?"":" 错误="+search.error).append('\n');
            if(!tracks.isEmpty()){
                songId=tracks.get(0).id;
                out.append("   第一首：").append(tracks.get(0).label())
                        .append("（id=").append(songId).append("）\n");
            }
        }catch(Throwable t){
            out.append("② 搜索异常：").append(t.getClass().getSimpleName()).append('\n');
        }

        try{
            if(songId.length()>0){
                NeteaseApi.Reply detail=NeteaseApi.detail(songId,cookie);
                NeteaseApi.Track track=NeteaseApi.parseDetail(detail);
                out.append("③ 歌曲详情：code=").append(detail.code)
                        .append(" 歌名=").append(track==null?"":track.name).append('\n');
            }
        }catch(Throwable t){
            out.append("③ 歌曲详情异常：").append(t.getClass().getSimpleName()).append('\n');
        }

        if(songId.length()>0){
            for(int br:new int[]{NeteaseApi.BR_LOW,NeteaseApi.BR_MID,NeteaseApi.BR_HIGH}){
                try{
                    NeteaseApi.Reply reply=NeteaseApi.playerUrl(songId,br,cookie);
                    out.append("④ 取地址 br=").append(br).append("：")
                            .append(NeteaseApi.describe(reply)).append('\n');
                }catch(Throwable t){
                    out.append("④ 取地址 br=").append(br).append(" 异常：")
                            .append(t.getClass().getSimpleName()).append('\n');
                }
            }
        }else{
            out.append("④ 取地址：没拿到歌曲 id，先看②\n");
        }

        out.append("\n判断方法：url=空 且 code=200 →网易云不给直链（版权/需要会员）；")
                .append("code=301/302 →CK 失效要重登；http 不是 200 →请求被拦或网络异常；")
                .append("出现「试听限制=是」→只能试听。");
        return out.toString();
    }
}