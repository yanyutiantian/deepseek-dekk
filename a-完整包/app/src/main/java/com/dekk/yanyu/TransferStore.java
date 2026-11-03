package com.dekk.yanyu;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;

/** 本地虚拟转账记录；不连接支付平台，不产生真实扣款。 */
final class TransferStore {
    static final class Record{
        String id,amount,note; long time; boolean incoming,received;
    }
    private static final String PREF="dekk_transfers";
    /** 已处理消息的映射键前缀（避免重启后重复弹卡片）。 */
    private static final String KEY_PREFIX="key:";
    private TransferStore(){}
    static synchronized Record create(Context c,String amount,boolean incoming){
        return create(c,amount,incoming,"");
    }
    /** key 非空时同时记录"这条消息已处理过"，重启后不会重复弹卡片。 */
    static synchronized Record create(Context c,String amount,boolean incoming,String key){
        Record r=new Record();r.id=java.util.UUID.randomUUID().toString();r.amount=amount;r.incoming=incoming;r.time=System.currentTimeMillis();
        android.content.SharedPreferences.Editor editor=c.getSharedPreferences(PREF,0).edit();
        editor.putString(r.id,encode(r));
        if(key!=null&&key.length()>0)editor.putString(KEY_PREFIX+key,r.id);
        editor.apply();
        return r;
    }
    static synchronized Record byKey(Context c,String key){
        if(c==null||key==null||key.length()==0)return null;
        String id=c.getSharedPreferences(PREF,0).getString(KEY_PREFIX+key,"");
        return id.length()==0?null:get(c,id);
    }
    static synchronized void receive(Context c,String id){
        Record r=get(c,id);if(r==null)return;r.received=true;c.getSharedPreferences(PREF,0).edit().putString(id,encode(r)).apply();
    }
    static synchronized Record get(Context c,String id){
        if(c==null||id==null)return null;String x=c.getSharedPreferences(PREF,0).getString(id,"");return x.length()==0?null:decode(id,x);
    }
    static synchronized List<Record> all(Context c){
        List<Record> out=new ArrayList<Record>();for(String id:c.getSharedPreferences(PREF,0).getAll().keySet()){Record r=get(c,id);if(r!=null)out.add(r);}return out;
    }
    /** 虚拟转账总开关：默认关闭（关闭时不注入协议，也不识别/显示转账卡片）。 */
    static boolean promptEnabled(Context c){
        return c!=null&&c.getSharedPreferences(PREF,0).getBoolean("prompt_enabled",false);
    }
    static void setPromptEnabled(Context c,boolean value){
        if(c!=null)c.getSharedPreferences(PREF,0).edit().putBoolean("prompt_enabled",value).apply();
    }
    static String status(Context c){
        StringBuilder b=new StringBuilder();for(Record r:all(c))b.append(r.incoming?"AI发起":"用户发起").append(" ").append(r.amount).append("元 ").append(r.received?"已收款":"待收款").append("\n");return b.toString();
    }
    private static String encode(Record r){return r.amount+"|"+r.time+"|"+(r.incoming?1:0)+"|"+(r.received?1:0);}
    private static Record decode(String id,String x){try{String[] a=x.split("\\|",-1);Record r=new Record();r.id=id;r.amount=a[0];r.time=Long.parseLong(a[1]);r.incoming="1".equals(a[2]);r.received=a.length>3&&"1".equals(a[3]);return r;}catch(Throwable t){return null;}}
}