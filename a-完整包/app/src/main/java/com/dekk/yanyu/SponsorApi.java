package com.dekk.yanyu;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 赞助身份（服务端 {@code sponsor_check} 的唯一客户端入口）。
 *
 * <pre>
 *   POST https://dekk.example.com/API/?action=sponsor_check   （V2 加密信封，需 Bearer token）
 *   → {"ok":true,"sponsor":1,"features":["force_login","email_login","gold_name"]}
 * </pre>
 *
 * <p>约定（所有赞助限定功能都照这个来，别各自写一套）：
 * <ol>
 *   <li>{@link #isSponsor} 只读本地缓存，<b>不打网络</b>：界面在渲染线程里问它；</li>
 *   <li>{@link #check} 才打服务器，用于「打开功能前验一次」；</li>
 *   <li>缓存 6 小时内可信（{@link #TTL_MS}）；过期后先按 {@link UserProfile} 里的 sponsor 字段兜底，
 *       下次打开页面时由 {@link #checkIfStale} 静默刷新；</li>
 *   <li>功能粒度用 {@link #has}：服务端没下发 features 时按「是赞助就全给」处理（老缓存兼容）。</li>
 * </ol>
 *
 * <p>刻意<b>不带</b>等级、到期时间：赞助就是「是 / 不是」，功能用一个字符串列表表达，
 * 以后加功能只加名字。
 */
final class SponsorApi {

    /**
     * 强开登录（谷歌 / 邮箱整个分区）。
     *
     * <p>2026-11-01 起「强开登录」取消赞助限制，客户端不再校验这两个名字；
     * 常量保留只是为了记录服务端下发的功能名，别再用它们卡登录。
     */
    static final String FEATURE_FORCE_LOGIN = "force_login";
    /** 强开邮箱账号登录（同上：已不对客户端生效，仅保留名字）。 */
    static final String FEATURE_EMAIL_LOGIN = "email_login";
    /** 灿金扫光昵称。 */
    static final String FEATURE_GOLD_NAME = "gold_name";

    private static final String PREF = "dekk_prefs";
    private static final String KEY_OK = "sponsor_ok";
    private static final String KEY_FEATURES = "sponsor_features";
    private static final String KEY_AT = "sponsor_checked_at";

    /** 本地信任窗口：这段时间内不再打扰服务器。 */
    private static final long TTL_MS = 6L * 3600L * 1000L;

    interface Callback { void done(boolean sponsor, String error); }

    private static boolean checking;

    private SponsorApi() { }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 是否赞助用户（本地缓存优先，不打网络）。 */
    static boolean isSponsor(Context context) {
        if (context == null) return false;
        if (isFresh(context)) return prefs(context).getBoolean(KEY_OK, false);
        // 缓存过期：退回资料缓存里的 sponsor（同样是服务端下发的），界面不会突然少一块
        return UserProfile.isSponsor(context);
    }

    /** 本地缓存是否还在信任窗口内。 */
    static boolean isFresh(Context context) {
        if (context == null) return false;
        long at = prefs(context).getLong(KEY_AT, 0L);
        return at > 0L && System.currentTimeMillis() - at <= TTL_MS;
    }

    /** 某个赞助功能能不能用。 */
    static boolean has(Context context, String feature) {
        if (context == null || feature == null || !isSponsor(context)) return false;
        String csv = prefs(context).getString(KEY_FEATURES, "");
        if (csv.length() == 0) return true;      // 没下发明细：是赞助就全给
        String[] parts = csv.split(",");
        for (int i = 0; i < parts.length; i++) {
            if (feature.equals(parts[i].trim())) return true;
        }
        return false;
    }

    /** 向服务器确认一次（打开赞助功能前调用）。回调在主线程。 */
    static void check(final Activity activity, final Callback callback) {
        if (activity == null) {
            if (callback != null) callback.done(false, "没有可用的界面");
            return;
        }
        synchronized (SponsorApi.class) {
            if (checking) {                       // 正在验：直接给当前结论，不并发打服务器
                if (callback != null) callback.done(isSponsor(activity), null);
                return;
            }
            checking = true;
        }
        PersonaApi.request(activity, "sponsor_check", null, new PersonaApi.Callback() {
            public void done(JSONObject result, String error) {
                synchronized (SponsorApi.class) { checking = false; }
                if (error != null || result == null) {
                    if (callback != null) callback.done(isSponsor(activity), error);
                    return;
                }
                boolean sponsor = result.optInt("sponsor", 0) == 1 || result.optBoolean("sponsor", false);
                save(activity.getApplicationContext(), result, sponsor);
                if (callback != null) callback.done(sponsor, null);
            }
        });
    }

    /** 缓存过期才打服务器；没过期直接给本地结论（界面打开时用这个，省一次请求）。 */
    static void checkIfStale(Activity activity, Callback callback) {
        if (isFresh(activity)) {
            if (callback != null) callback.done(isSponsor(activity), null);
            return;
        }
        check(activity, callback);
    }

    /** 清掉本地结论（退出登录 / 换账号时调用）。 */
    static void clear(Context context) {
        if (context == null) return;
        prefs(context).edit()
                .remove(KEY_OK).remove(KEY_FEATURES).remove(KEY_AT).apply();
    }

    private static void save(Context context, JSONObject result, boolean sponsor) {
        StringBuilder features = new StringBuilder();
        JSONArray list = result.optJSONArray("features");
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                String name = list.optString(i, "").trim();
                if (name.length() == 0) continue;
                if (features.length() > 0) features.append(',');
                features.append(name);
            }
        }
        prefs(context).edit()
                .putBoolean(KEY_OK, sponsor)
                .putString(KEY_FEATURES, features.toString())
                .putLong(KEY_AT, System.currentTimeMillis())
                .apply();
        // 顺带把资料缓存里的 sponsor 对齐：灿金昵称、首页「登录」分区都读它
        UserProfile.rememberSponsor(context, sponsor);
    }
}
