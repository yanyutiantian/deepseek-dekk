package com.dekk.yanyu;

import android.content.Context;

import java.util.Collection;
import java.util.Map;

/**
 * {@code TraceLog} 的移植版 —— 给 {@link ShumeiBypass} 用。
 *
 * <p>原始过检测源码里 TraceLog 是"写文件 + logcat"两路输出，还带一个可被 ADB 读的落盘目录。
 * 这里改成只走 HotBridge 日志（热更新 dex 里不能直接用真 Xposed 类）：
 * <ul>
 *   <li>不用存储权限，也不用在宿主目录里留文件（少一个被数美看到的痕迹）；</li>
 *   <li>带总量上限，避免 hook 到热路径（ma/ma2 每次登录都会连打）时把日志刷爆。</li>
 * </ul>
 */
final class ShumeiTrace {

    /** 每个进程最多输出这么多行，超了就只报数。 */
    private static final int MAX_LINES = 1200;
    /** 单个值的最大展示长度。 */
    private static final int MAX_VALUE = 300;

    private static int lines;
    private static int suppressed;
    private static boolean truncatedLogged;

    static void init(Context ctx) {
        write("TraceLog.init ctx=" + ctx);
    }

    static void write(String line) {
        emit(line);
    }

    static void enter(String site, Object[] args) {
        emit(">>> " + site + " args=" + describeArgs(args));
    }

    static void exit(String site, Object result) {
        emit("<<< " + site + " -> " + describeOne(result));
    }

    static void exception(String site, Throwable t) {
        emit("!!! " + site + " 抛异常 " + brief(t));
    }

    static void exception(Throwable t) {
        exception("?", t);
    }

    /** 把一组参数压成一行。 */
    static String describeArgs(Object[] args) {
        if (args == null) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(describeOne(args[i]));
        }
        return sb.append(']').toString();
    }

    /** 把单个值压成短字符串，别把整个 byte[] 或 Map 打进日志。 */
    static String describeOne(Object v) {
        if (v == null) return "null";
        try {
            if (v instanceof String) {
                String s = (String) v;
                return s.length() <= MAX_VALUE ? s : s.substring(0, MAX_VALUE) + "…(" + s.length() + "字)";
            }
            if (v instanceof byte[]) {
                return "byte[" + ((byte[]) v).length + "]";
            }
            if (v instanceof char[]) {
                return "char[" + ((char[]) v).length + "]";
            }
            if (v instanceof Object[]) {
                Object[] a = (Object[]) v;
                StringBuilder sb = new StringBuilder("Object[" + a.length + "]{");
                int n = Math.min(a.length, 8);
                for (int i = 0; i < n; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(describeOne(a[i]));
                }
                return sb.append('}').toString();
            }
            if (v instanceof Map) {
                Map<?, ?> m = (Map<?, ?>) v;
                return "Map(" + m.size() + "){" + keysOf(m.keySet(), 8) + "}";
            }
            if (v instanceof Collection) {
                Collection<?> c = (Collection<?>) v;
                return "Collection(" + c.size() + "){"
                        + (c.isEmpty() ? "" : describeOne(c.iterator().next())) + "}";
            }
            String s = String.valueOf(v);
            return s.length() <= MAX_VALUE ? s : s.substring(0, MAX_VALUE) + "…";
        } catch (Throwable t) {
            return "<toString 失败:" + t.getClass().getSimpleName() + ">";
        }
    }

    private static String keysOf(Collection<?> keys, int limit) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        try {
            for (Object k : keys) {
                if (i >= limit) {
                    sb.append(",…");
                    break;
                }
                if (i > 0) sb.append(',');
                sb.append(k);
                i++;
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    private static String brief(Throwable t) {
        if (t == null) return "null";
        String m = null;
        try {
            m = t.getMessage();
        } catch (Throwable ignored) {
        }
        String s = t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
        return s.length() <= MAX_VALUE ? s : s.substring(0, MAX_VALUE) + "…";
    }

    private static void emit(String line) {
        synchronized (ShumeiTrace.class) {
            if (lines >= MAX_LINES) {
                suppressed++;
                if (!truncatedLogged) {
                    truncatedLogged = true;
                    HotBridge.log("[DEKK][数美trace] 已达上限，后续不再逐条输出（只计数）");
                }
                return;
            }
            lines++;
        }
        HotBridge.log("[DEKK][数美trace] " + line);
    }

    static int suppressedCount() {
        return suppressed;
    }

    private ShumeiTrace() { }
}
