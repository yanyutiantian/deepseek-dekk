package com.dekk.yanyu;
import android.app.Activity;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;

/** Upload and owner-only editing; server remains authoritative for ownership. */
public final class PersonaEditor {
    private PersonaEditor(){}
    public static void show(final Activity a,final JSONObject source){
        // 兜底：这个页面在部分机型/热更新包上出现过一点就闪退，先保证不崩，并把异常打进日志
        try{ showInner(a,source); }
        catch(Throwable t){
            HotBridge.log("[DEKK][人设] 打开发布页失败：" + t);
            try{android.widget.Toast.makeText(a,"打开发布页失败："+t.getClass().getSimpleName(),android.widget.Toast.LENGTH_LONG).show();}catch(Throwable ignored){}
        }
    }

    private static void showInner(final Activity a,final JSONObject source){
        if(AccountStore.token(a).length()==0){AccountPages.open(a);return;}
        final CenterPage p=new CenterPage(a,source==null?"上传人设":"编辑人设",new Runnable(){public void run(){PersonaPages.show(a,"mine");}});
        p.section("基本信息");LinearLayout basics=p.card();
        final EditText name=p.field(basics,"角色名（最多100字）",1,false);
        final EditText description=p.field(basics,"简介（最多2000字）",131073,true);
        p.section("人设提示词");final EditText prompt=p.field(p.card(),"填写人设正文（最多20000字）",131073,true);
        p.rowIcon(p.card(),MiuixIcon.DOC,"从 TXT 导入正文 ›",new Runnable(){public void run(){PersonaTxtImport.pick(p,prompt);}});
        p.section("可见范围");LinearLayout visibility=p.card();
        // 用 int[] 存 0/1：加密工具会把常量改写成算术，写进 boolean[] 会变成
        // "register has type Integer but expected Boolean" → ART 拒绝整个类（2026-10-30 实测闪退）
        final int[] isPublic={source!=null && source.optString("visibility").equals("public")?1:0};
        // 以前这行标题是空的，看起来像漏了字；改成「标题 + 说明」并即时刷新
        final MiuixUi.Row[] toggle=new MiuixUi.Row[1];
        toggle[0]=MiuixUi.row(p.activity,visibility,p.night,MiuixIcon.STAR,"可见范围",
                visibilityText(isPublic[0]==1),true,new View.OnClickListener(){public void onClick(View v){
                    isPublic[0]=isPublic[0]==1?0:1;
                    toggle[0].setSummary(visibilityText(isPublic[0]==1));
                }});
        LinearLayout noteCard=p.card();
        p.caption(noteCard,"转为私密或删除后，不会清除他人已下载的公开副本。");
        if(source!=null){name.setText(source.optString("name"));description.setText(source.optString("description"));prompt.setText(source.optString("prompt"));}
        final boolean[] busy={false};p.action("保存人设",new Runnable(){public void run(){
            if(busy[0])return;
            String n=name.getText().toString().trim(),d=description.getText().toString(),t=prompt.getText().toString();
            if(n.length()==0 || n.length()>100 || d.length()>2000 || t.trim().length()==0 || t.length()>20000){p.message("请检查角色名、简介和正文长度");return;}
            JSONObject data=PersonaApi.object("name",n,"description",d,"prompt",t,"visibility",isPublic[0]==1?"public":"private");
            try{if(source!=null)data.put("id",source.optLong("id"));}catch(Exception e){return;}
            // 公开人设由服务端做 AI 审核（名称/简介/提示词一次审完），被拒时把服务端原因原样提示
            busy[0]=true;
            if(isPublic[0]==1)p.message("正在提交（公开内容会先经服务端审核）…");
            PersonaApi.request(a,"save_persona",data,new PersonaApi.Callback(){public void done(JSONObject r,String e){
                busy[0]=false;
                if(!p.active())return;
                if(e!=null){p.message(e);return;}
                p.message("人设已保存");PersonaPages.show(a,"mine");
            }});
        }});
        if(source!=null)p.rowIcon(p.card(),MiuixIcon.DOC,"删除服务器人设 ›",new Runnable(){public void run(){confirmDelete(a,source);}}).setTextColor(0xFFD84A4A);
        p.show();
    }
    private static String visibilityText(boolean pub){
        return pub?"公开 · 所有人可下载":"私密 · 仅自己可拉取";
    }
    private static void confirmDelete(final Activity a,final JSONObject source){
        final CenterPage p=new CenterPage(a,"删除人设",new Runnable(){public void run(){show(a,source);}});
        p.row(p.card(),"确定删除「"+source.optString("name")+"」？此操作无法恢复，已下载副本不受影响。",null);
        final boolean[] busy={false};p.action("确认删除",new Runnable(){public void run(){if(busy[0])return;busy[0]=true;
            PersonaApi.request(a,"delete_persona",PersonaApi.object("id",String.valueOf(source.optLong("id"))),new PersonaApi.Callback(){public void done(JSONObject r,String e){busy[0]=false;if(!p.active())return;if(e!=null){p.message(e);return;}PersonaPages.show(a,"mine");}});
        }});p.show();
    }
}