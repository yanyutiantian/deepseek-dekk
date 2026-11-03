package com.dekk.yanyu;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

/** Stored in the host application's private sandbox, never stores passwords. */
public final class AccountStore {
    private AccountStore() { }
    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("dekk_account", Context.MODE_PRIVATE);
    }
    public static String token(Context c) {
        SharedPreferences p = prefs(c);
        if (System.currentTimeMillis() >= p.getLong("expires", 0)) {
            clear(c); return "";
        }
        return p.getString("token", "");
    }
    public static void save(Context c, JSONObject response) throws Exception {
        prefs(c).edit().putString("token", response.getString("token"))
            .putLong("expires", System.currentTimeMillis() + response.getLong("expires_in") * 1000L)
            .putString("user", response.getJSONObject("user").toString()).commit();
    }
    public static JSONObject user(Context c) {
        try { return new JSONObject(prefs(c).getString("user", "{}")); }
        catch (Exception e) { return new JSONObject(); }
    }
    public static void updateUser(Context c, JSONObject user) {
        prefs(c).edit().putString("user", user.toString()).commit();
    }
    public static void clear(Context c) {
        prefs(c).edit().clear().commit();
        // 换账号 / 退出：赞助结论必须一起清，否则下一个账号还能看到赞助功能
        SponsorApi.clear(c);
    }
}
