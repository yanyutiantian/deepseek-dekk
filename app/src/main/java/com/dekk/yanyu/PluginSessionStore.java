package com.dekk.yanyu;
import android.content.Context;
import org.json.JSONObject;
/** One-shot persona binding. Currently consumed at serialization, not network acknowledgement. */
public final class PluginSessionStore {
 private static final String PREF="dekk_plugin_session";
 private PluginSessionStore(){}
 public static void bind(Context c,JSONObject p){c.getSharedPreferences(PREF,0).edit().putString("prompt",p.optString("prompt")).putString("name",p.optString("name")).putBoolean("pending",true).apply();}
 public static String pending(Context c){return c.getSharedPreferences(PREF,0).getBoolean("pending",false)?c.getSharedPreferences(PREF,0).getString("prompt",""):"";}
 public static void consume(Context c){c.getSharedPreferences(PREF,0).edit().putBoolean("pending",false).remove("prompt").apply();}
 public static void clear(Context c){c.getSharedPreferences(PREF,0).edit().clear().apply();}
 public static boolean isPending(Context c){return pending(c).length()>0;}
}