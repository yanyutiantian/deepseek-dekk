package com.dekk.yanyu;
import android.app.Activity;
import android.widget.*;
import org.json.JSONObject;

/** Text-only plugin editor; plugins are prompts, never executable code. */
public final class PluginEditor {
 private PluginEditor(){}
 public static void show(final Activity a,final JSONObject source){
  if(AccountStore.token(a).length()==0){AccountPages.open(a);return;}
  final CenterPage p=new CenterPage(a,source==null?"上传插件":"编辑插件",new Runnable(){public void run(){PluginPages.show(a,"mine");}});
  p.section("插件信息");LinearLayout c=p.card();
  final EditText name=p.field(c,"插件名称",1,false),desc=p.field(c,"插件简介",131073,true),prompt=p.field(c,"插件提示词",131073,true);
  p.rowIcon(p.card(),MiuixIcon.DOC,"从 TXT 导入提示词 ›",new Runnable(){public void run(){PersonaTxtImport.pick(p,prompt);}});
  p.section("可见范围");final int[] pub={source!=null&&"public".equals(source.optString("visibility"))?1:0};final TextView[] vv=new TextView[1];vv[0]=p.rowIcon(p.card(),MiuixIcon.STAR,"可见范围 ›",new Runnable(){public void run(){pub[0]=pub[0]==1?0:1;vv[0].setText(pub[0]==1?"可见范围：公开 · 所有人可下载 ›":"可见范围：私密 · 仅自己可拉取 ›");}});vv[0].setText(pub[0]==1?"可见范围：公开 · 所有人可下载 ›":"可见范围：私密 · 仅自己可拉取 ›");
  p.section("公开插件会先经服务端 AI 审核（名称 / 简介 / 提示词一次审完）；私密不审核。");
  if(source!=null){name.setText(source.optString("name"));desc.setText(source.optString("description"));prompt.setText(source.optString("prompt"));}
  final boolean[] busy={false};p.action("保存插件",new Runnable(){public void run(){
   if(busy[0])return;
   String n=name.getText().toString().trim(),d=desc.getText().toString(),q=prompt.getText().toString();
   if(n.length()==0||q.trim().length()==0||n.length()>100||d.length()>2000||q.length()>20000){p.message("请检查插件名称、简介和提示词长度");return;}
   JSONObject data=PersonaApi.object("name",n,"description",d,"prompt",q,"visibility",pub[0]==1?"public":"private");
   try{if(source!=null)data.put("id",source.optLong("id"));}catch(Exception e){return;}
   // 公开插件由服务端审核：被拒时把服务端返回的具体原因原样提示
   busy[0]=true;
   if(pub[0]==1)p.message("正在提交（公开内容会先经服务端审核）…");
   PersonaApi.request(a,"save_plugin",data,new PersonaApi.Callback(){public void done(JSONObject r,String e){
    busy[0]=false;
    if(!p.active())return;
    if(e!=null){p.message(e);return;}
    p.message(source==null?"插件已上传":"插件已更新");
    PluginPages.show(a,"mine");
   }});
  }});
  if(source!=null)p.row(p.card(),"删除服务器插件  ›",new Runnable(){public void run(){confirmDelete(a,source);}}).setTextColor(0xFFD84A4A);
  p.show();
 }
 private static void confirmDelete(final Activity a,final JSONObject source){
  final CenterPage p=new CenterPage(a,"删除插件",new Runnable(){public void run(){show(a,source);}});
  p.row(p.card(),"确定删除「"+source.optString("name")+"」？此操作无法恢复，已下载副本不受影响。",null);
  final boolean[] busy={false};p.action("确认删除",new Runnable(){public void run(){
   if(busy[0])return;busy[0]=true;
   PersonaApi.request(a,"delete_plugin",PersonaApi.object("id",String.valueOf(source.optLong("id"))),new PersonaApi.Callback(){public void done(JSONObject r,String e){
    busy[0]=false;if(!p.active())return;
    if(e!=null){p.message(e);return;}
    PluginPages.show(a,"mine");
   }});
  }});p.show();
 }
}