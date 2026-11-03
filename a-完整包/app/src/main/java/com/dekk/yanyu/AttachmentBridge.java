package com.dekk.yanyu;

import android.content.Context;
import android.net.Uri;
import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 复用宿主的文件附件链路（2.5.1）。
 *
 * 已核实的调用链（对照 smali）：
 *   - oc1 是输入框/附件控制器，构造后缓存实例即可复用。
 *   - 添加附件的**真正入口**是 oc1.z(int sourceType, String sessionId, List&lt;Uri&gt; uris)：
 *       实测调用方（ao1 / st1）都是
 *         `iget wr.a`  → sessionId
 *         `invoke oc1->z(ILjava/lang/String;Ljava/util/List;)V`
 *     它内部会构造 z61（选中文件描述）→ 调 oc1.a → oc1.n（文件预处理）→ 加入待发附件。
 *   - oc1.n(zf3) 只是「单文件预处理」（图片压缩/转存），单独调它不会入队 —— 这一点已被真机日志证实
 *     （调用前后待发队列 p/m 都是 0，且 n() 直接把 zf3 原样返回）。
 *   - 真正的上传由宿主在「发送消息」时完成（/api/v0/file/upload_file，PoW 由宿主自己算）。
 */
final class AttachmentBridge {
    interface Result { void done(boolean ok, String error); }

    private static ClassLoader loader;
    private static Method uMethod;
    private static Class<?> fa2Class;
    private static volatile Object controller;
    private static volatile String lastError;

    private AttachmentBridge() { }

    static void install(final ClassLoader classLoader) {
        try {
            loader = classLoader;
            Class<?> oc1 = HotHelpers.findClass("oc1", classLoader);
            for (java.lang.reflect.Constructor<?> ctor : oc1.getDeclaredConstructors()) {
                try {
                    HotBridge.hookMethod(ctor, new HotHook() {
                        @Override
                        protected void afterHookedMethod(HotParam param) {
                            controller = param.thisObject;
                        }
                    });
                } catch (Throwable ignored) { }
            }
            HotBridge.log("[DEKK][附件] 附件控制器已安装");
        } catch (Throwable t) {
            HotBridge.log("[DEKK][附件] 安装失败: " + t);
        }
    }

    static boolean available() { return controller != null; }

    static String lastError() { return lastError; }

    /** 把本地文件加入当前会话的待发附件（宿主在发送消息时自动上传）。 */
    static void attach(Context context, File file, Result callback) {
        lastError = null;
        if (file == null || !file.exists()) { callback.done(false, "文件不存在"); return; }
        if (controller == null) { callback.done(false, "请先打开一个会话再上传附件"); return; }
        try {
            Object owner = MusicSend.owner();
            if (owner == null) { callback.done(false, "请先打开一个会话再上传附件"); return; }
            String sessionId = sessionIdOf(owner);
            if (sessionId.length() == 0) {
                // 宿主的会话是「懒创建」：全新会话在发出第一条消息之前没有服务端 ID，
                // 所以这里没法给附件定位目标会话，只能让用户先发一条消息。
                callback.done(false, "当前会话还没有 ID：全新的会话要等你发出第一条消息后才会创建。\n"
                        + "请先随便发送一条消息，再回来点「上传为文件附件」。");
                return;
            }
            ensureMethods();
            java.lang.reflect.Constructor<?> ctor = fa2Class.getDeclaredConstructor(
                    Uri.class, String.class, long.class, int.class, int.class);
            ctor.setAccessible(true);
            Object fa2 = ctor.newInstance(Uri.fromFile(file), file.getName(), file.length(), 0, 0);
            HotBridge.log("[DEKK][附件] 提交附件 " + file.getName() + " size=" + file.length()
                    + " session=" + sessionId + "；调用前 p=" + sizeOf(controller, "p") + " m=" + sizeOf(controller, "m"));
            // oc1.u(Lfa2;ILjava/lang/String;Lz61;)：单文件入口，来源类型固定 1（与宿主 ao1 对单文件的分支一致）
            uMethod.invoke(controller, fa2, 1, String.valueOf(sessionId), null);
            HotBridge.log("[DEKK][附件] 已提交；调用后 p=" + sizeOf(controller, "p") + " m=" + sizeOf(controller, "m"));
            if (callback != null) callback.done(true, null);
        } catch (Throwable t) {
            lastError = describe(t);
            HotBridge.log("[DEKK][附件] 添加失败: " + lastError);
            HotBridge.log(t);   // 完整堆栈，便于定位 NPE 发生在宿主哪一行
            if (callback != null) callback.done(false, "添加附件失败：" + lastError);
        }
    }

    private static String describe(Throwable t) {
        StringBuilder builder = new StringBuilder();
        Throwable current = t;
        int depth = 0;
        while (current != null && depth < 4) {
            if (depth > 0) builder.append(" <- ");
            builder.append(current.getClass().getSimpleName());
            String message = current.getMessage();
            if (message != null && message.length() > 0) builder.append(": ").append(message);
            current = current.getCause();
            depth++;
        }
        return builder.toString();
    }

    /**
     * 取当前会话的服务端 ID：优先 wr.a，其次 ao1.e()。
     * 全新会话在发出第一条消息之前，DeepSeek 还没创建它，两者都会是空 —— 这时只能提示用户先发一条消息。
     */
    private static String sessionIdOf(Object owner) {
        try {
            Object session = HotHelpers.callMethod(owner, "M");
            if (session != null) {
                Object id = HotHelpers.getObjectField(session, "a");
                if (id instanceof String && ((String) id).length() > 0) return (String) id;
            }
        } catch (Throwable ignored) { }
        try {
            Object id = HotHelpers.callMethod(owner, "e");
            if (id instanceof String) return (String) id;
        } catch (Throwable ignored) { }
        return "";
    }

    private static String sizeOf(Object target, String field) {
        try {
            Object value = HotHelpers.getObjectField(target, field);
            if (value instanceof java.util.Collection) return String.valueOf(((java.util.Collection<?>) value).size());
            if (value instanceof java.util.Map) return String.valueOf(((java.util.Map<?, ?>) value).size());
            return value == null ? "null" : "?";
        } catch (Throwable t) {
            return "?";
        }
    }

    private static void ensureMethods() throws Exception {
        if (uMethod != null) return;
        fa2Class = HotHelpers.findClass("fa2", loader);
        Class<?> z61Class = HotHelpers.findClass("z61", loader);
        uMethod = HotHelpers.findClass("oc1", loader).getMethod("u", fa2Class, int.class, String.class, z61Class);
    }
}