package com.dekk.yanyu;
import android.content.Context;
import java.io.*;
import org.json.*;
/** Local snapshots for play plugins; prompt is never returned by market list. */
public final class PluginStore {
 private static final String DIR="dekk_plugins";
 private PluginStore(){}
 public static boolean delete(Context c,long id){if(id<=0)return false;File f=new File(new File(c.getFilesDir(),DIR),id+".json");return !f.exists()||f.delete();}
 public static void save(Context c,JSONObject x)throws Exception{File d=new File(c.getFilesDir(),DIR);if(!d.exists()&&!d.mkdirs())throw new IOException();long id=x.getLong("id");File f=new File(d,id+".json");FileOutputStream o=new FileOutputStream(f);try{o.write(x.toString().getBytes("UTF-8"));}finally{o.close();}}
 public static JSONArray list(Context c){JSONArray a=new JSONArray();File[] fs=new File(c.getFilesDir(),DIR).listFiles();if(fs==null)return a;for(File f:fs)try{a.put(new JSONObject(read(f)));}catch(Exception ignored){}return a;}
 public static String read(File f)throws Exception{FileInputStream i=new FileInputStream(f);ByteArrayOutputStream b=new ByteArrayOutputStream();try{byte[] q=new byte[4096];int n;while((n=i.read(q))!=-1){if(b.size()+n>300000)throw new IOException();b.write(q,0,n);}}finally{i.close();}return b.toString("UTF-8");}
}