package com.dekk.yanyu;
import android.content.Context;
import org.json.JSONObject;
/** One-shot persona binding. The flag is consumed only after the host request succeeds. */
public final class PersonaSessionStore {
 private static final String PREF="dekk_persona_session";
 private PersonaSessionStore(){}
 public static void bind(Context c,JSONObject p){c.getSharedPreferences(PREF,0).edit().putString("prompt",p.optString("prompt")).putString("name",p.optString("name")).putBoolean("pending",true).apply();}
 public static String pending(Context c){return c.getSharedPreferences(PREF,0).getBoolean("pending",false)?c.getSharedPreferences(PREF,0).getString("prompt",""):"";}
 public static void consume(Context c){c.getSharedPreferences(PREF,0).edit().putBoolean("pending",false).remove("prompt").apply();}
 public static void clear(Context c){c.getSharedPreferences(PREF,0).edit().clear().apply();}
 public static boolean isPending(Context c){return pending(c).length()>0;}
}