package com.dekk.yanyu;
import android.content.*;
import android.graphics.drawable.*;
import android.view.*;
/** Stores a user-selected image URI and applies it to injected pages. */
public final class BeautifyStore {
 private static final String PREF="dekk_beautify";
 private BeautifyStore(){}
 public static void set(Context c,String uri){c.getSharedPreferences(PREF,0).edit().putString("background",uri==null?"":uri).apply();ChatBackgroundHook.refresh(c);}
 public static String get(Context c){return c.getSharedPreferences(PREF,0).getString("background","");}
 /** 壁纸透明度，0-100，默认 100（不透明）。 */
 public static int alpha(Context c){int v=c.getSharedPreferences(PREF,0).getInt("background_alpha",100);return v<0?0:(v>100?100:v);}
 /** 透明度变了只需重绘，不用重新解码图片。 */
 public static void setAlpha(Context c,int value){
  int v=value<0?0:(value>100?100:value);
  c.getSharedPreferences(PREF,0).edit().putInt("background_alpha",v).apply();
  ChatBackgroundHook.applyAlpha(c);
 }
 /** 只写配置、不触发刷新（迁移壁纸路径时用）。 */
 public static void setPath(Context c,String path){c.getSharedPreferences(PREF,0).edit().putString("background",path==null?"":path).apply();}
 public static void clear(Context c){set(c,"");WallpaperFile.delete(c);}
  public static boolean animations(Context c){return c.getSharedPreferences(PREF,0).getBoolean("animations",false);}
  public static void setAnimations(Context c,boolean v){c.getSharedPreferences(PREF,0).edit().putBoolean("animations",v).apply();}
  public static int composerStyle(Context c){return c.getSharedPreferences(PREF,0).getInt("composer_style",0);}
  public static void setComposerStyle(Context c,int v){c.getSharedPreferences(PREF,0).edit().putInt("composer_style",v>=0&&v<=2?v:0).apply();}
 public static void apply(Context c,View v){String s=get(c);if(s.length()==0)return;try{v.setBackground(Drawable.createFromPath(s));}catch(Throwable ignored){}}
}