package com.dekk.yanyu;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.widget.Toast;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 更新提示 + 自动重启宿主。
 *
 * <p>流程：宿主每次冷启动都比对 dex 的 md5（见 {@link HotDex}）→ 不一样就后台下载 →
 * 下载完成后由这里提示用户，并把宿主机重新拉起，让新 dex 生效。
 *
 * <p>重启不用 root：先给系统下一个 1.2 秒后的闹钟（拉起宿主主界面），再结束宿主进程；
 * 闹钟在系统服务里，进程死了也照样触发。
 */
final class HotUpdate {

    /** 宿主主界面（拿不到启动 Intent 时的兜底）。 */
    private static final String HOST_ACTIVITY = "com.deepseek.chat.MainActivity";

    private static WeakReference<Activity> current = new WeakReference<Activity>(null);
    private static volatile boolean ready;
    private static volatile boolean prompted;
    private static volatile boolean restarting;
    private static volatile String version = "";

    private HotUpdate() { }

    /** 在宿主进程里装「界面出现时提示并重启」的钩子。 */
    static void install(ClassLoader host) {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof Activity)) return;
                    Activity activity = (Activity) param.thisObject;
                    current = new WeakReference<Activity>(activity);
                    if (ready) prompt(activity);
                }
            });
            HotLog.i("更新提示已就绪（宿主每次启动都会比对 dex 版本）");
        } catch (Throwable t) {
            HotLog.i("更新提示安装失败：" + t);
        }
    }

    /** 新 dex 已经下载并落盘：可以提示 + 重启了。 */
    static void ready(String newVersion) {
        version = newVersion == null ? "" : newVersion;
        ready = true;
        Activity activity = current.get();
        if (activity != null) prompt(activity);
    }

    /** 当前是否有待重启的更新（宿主界面用得到）。 */
    static boolean isReady() {
        return ready;
    }

    private static void prompt(final Activity activity) {
        if (prompted) return;
        prompted = true;
        final String shortId = shortId(version);
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    new AlertDialog.Builder(activity)
                            .setTitle("新更新")
                            .setMessage("检测到新版本，已经下载完成。\n"
                                    + "重启DeepSeek后完成。")
                            .setCancelable(false)
                            .setPositiveButton("立即重启", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    restart(activity);
                                }
                            })
                            .show();
                } catch (Throwable t) {
                    HotLog.i("弹提示失败，直接重启：" + t);
                    restart(activity);
                    return;
                }
                // 用户没点也会自动重启（用户要求：下载完成即自动重启）
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        restart(activity);
                    }
                }, 4000);
            }
        });
    }

    /** 安排闹钟拉起宿主 → 结束当前宿主进程。 */
    private static void restart(Activity activity) {
        if (restarting) return;
        restarting = true;
        try {
            Context context = activity == null ? null : activity.getApplicationContext();
            if (context != null) {
                Intent launch = context.getPackageManager().getLaunchIntentForPackage(HotConfig.HOST);
                if (launch == null) launch = new Intent().setClassName(HotConfig.HOST, HOST_ACTIVITY);
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                PendingIntent pending = PendingIntent.getActivity(context, 0, launch,
                        PendingIntent.FLAG_UPDATE_CURRENT);
                AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
                if (alarm != null) {
                    alarm.set(AlarmManager.RTC, System.currentTimeMillis() + 1200, pending);
                }
                if (activity != null) {
                    Toast.makeText(context, "正在重启", Toast.LENGTH_SHORT).show();
                }
            }
        } catch (Throwable t) {
            HotLog.i("安排重启失败（下次手动打开宿主即可生效）：" + t);
        }
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                HotLog.i("重启宿主以加载新 dex");
                Process.killProcess(Process.myPid());
            }
        }, 500);
    }

    private static String shortId(String value) {
        if (value == null) return "";
        return value.length() > 8 ? value.substring(0, 8) : value;
    }
}