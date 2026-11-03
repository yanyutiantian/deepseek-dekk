package com.dekk.yanyu;

import android.content.Context;


/**
 * 一问多答核心 Hook：
 * 1) Hook ao1.T(d21) 监听流式回答完成状态；
 * 2) Hook ao1.J(jn1) 监听用户发送事件以启动队列，并拦截自动续答文本避免循环启动。
 * 所有反射异常均原样放行，不阻断宿主发送。
 */
public final class MultiReplyStateHook {
    private static ClassLoader savedLoader;
    private static java.lang.reflect.Method sendMethod;
    private static Object lastComposer;
    private static Object lastSendMode;
    private static boolean automaticDispatch;
    private MultiReplyStateHook() { }

    public static void install(ClassLoader loader) {
        savedLoader = loader;
        installTHook(loader);
        installJHook(loader);
        installSendEntryHook(loader);
        installPromptInspectHook(loader);
        // TextView 父容器透明方案无效且可能误伤复用视图，不再安装。
    }

    /**
     * 兼容旧方案：如果宿主把消息内容落到 TextView，命中自动指令后让自身及父气泡透明。
     * Compose 文本不经过这里，因此不会影响 Compose 的其他消息。
     */
    private static void installPromptTransparentViewHook(ClassLoader loader) {
        try {
            HotHelpers.findAndHookMethod(android.widget.TextView.class, "setText", CharSequence.class,
                    new HotHook() {
                @Override protected void afterHookedMethod(HotParam p) throws Throwable {
                    Object arg = p.args != null && p.args.length > 0 ? p.args[0] : null;
                    if (!(arg instanceof CharSequence)
                            || !MultiReplyQueue.getPrompt().contentEquals((CharSequence) arg)) return;
                    android.view.View view = (android.view.View) p.thisObject;
                    view.setAlpha(0f);
                    view.setVisibility(android.view.View.INVISIBLE);
                    android.view.View parent = view.getParent() instanceof android.view.View
                            ? (android.view.View) view.getParent() : null;
                    if (parent != null) {
                        parent.setAlpha(0f);
                        parent.setVisibility(android.view.View.INVISIBLE);
                    }
                    HotBridge.log("[DEKK][多答] 旧方案命中，已将用户气泡透明："
                            + view.getClass().getName());
                }
            });
            HotBridge.log("[DEKK][多答] 透明气泡兼容 Hook 已安装");
        } catch (Throwable t) {
            HotBridge.log("[DEKK][多答] 透明气泡兼容 Hook 安装失败：" + t);
        }
    }

    /** 先只诊断，不隐藏。确认 Compose 实际传入的模型类型后再做精确过滤。 */
    private static void installPromptInspectHook(ClassLoader loader) {
        try {
            Class<?> owner = HotHelpers.findClass("vs9", loader);
            java.lang.reflect.Method[] methods = owner.getDeclaredMethods();
            for (int i = 0; i < methods.length; i++) {
                java.lang.reflect.Method m = methods[i];
                if (!"a".equals(m.getName()) || !java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                m.setAccessible(true);
                HotBridge.hookMethod(m, new HotHook() {
                    @Override protected void beforeHookedMethod(HotParam p) throws Throwable {
                        if (p.args == null) return;
                        String prompt = MultiReplyQueue.getPrompt();
                        for (int i = 0; i < p.args.length; i++) {
                            Object v = p.args[i];
                            if (v == null) continue;
                            String dump = inspect(v, prompt, 0);
                            // wr 是当前消息页面状态容器，额外输出其字段名和字段类型，定位消息列表来源。
                            if (i == 3 || i == 2) {
                                dumpFields(v);
                            }
                            // 先输出所有非 Compose 内部参数的真实类型，便于定位消息模型。
                            String type = v.getClass().getName();
                            if (!type.startsWith("androidx.compose.")
                                    && !type.startsWith("kotlin.jvm.internal.")) {
                                HotBridge.log("[DEKK][多答][消息参数] vs9.a 参数" + i
                                        + " 类型=" + type + (dump == null ? "" : " " + dump));
                            }
                        }
                    }
                });
            }
            HotBridge.log("[DEKK][多答] Compose 消息诊断 Hook 已安装，暂不隐藏");
        } catch (Throwable t) {
            HotBridge.log("[DEKK][多答] Compose 消息诊断 Hook 安装失败：" + t);
        }
    }

    private static void dumpFields(Object value) {
        try {
            java.lang.reflect.Field[] fs = value.getClass().getDeclaredFields();
            StringBuilder b = new StringBuilder("[DEKK][多答][字段] ");
            b.append(value.getClass().getName()).append(" {");
            for (int j = 0; j < fs.length; j++) {
                b.append(fs[j].getName()).append(":").append(fs[j].getType().getName());
                try {
                    fs[j].setAccessible(true);
                    Object fieldValue = fs[j].get(value);
                    if (fieldValue instanceof java.util.List) {
                        java.util.List list = (java.util.List) fieldValue;
                        b.append("[size=").append(list.size()).append("]");
                        int limit = Math.min(list.size(), 12);
                        for (int k = 0; k < limit; k++) {
                            Object item = list.get(k);
                            b.append(" item").append(k).append("=")
                                    .append(item == null ? "null" : item.getClass().getName());
                            String text = "display";
                            if (item != null && "qt8".equals(item.getClass().getName())) {
                                b.append("<PROMPT:").append(text).append(">");
                                if (hidePromptObject(item)) {
                                    b.append("<HIDDEN>");
                                    HotBridge.log("[DEKK][多答] 已隐藏自动用户消息对象：" + item.getClass().getName());
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) { }
                if (j + 1 < fs.length) b.append(", ");
            }
            b.append("}");
            HotBridge.log(b.toString());
        } catch (Throwable ignored) { }
    }

    private static boolean hidePromptObject(Object value) {
        try {
            java.lang.reflect.Field[] fs = value.getClass().getDeclaredFields();
            boolean found = false;
            for (int i = 0; i < fs.length; i++) {
                fs[i].setAccessible(true);
                Object child = fs[i].get(value);
                if ("c".equals(fs[i].getName()) && child instanceof String
                        && !child.equals(LocalMessageText.visible((String) child))) {
                    String visible = LocalMessageText.visible((String) child);
                    fs[i].set(value, visible == null ? "" : visible);
                    found = true;
                }
            }
            return found;
        } catch (Throwable e) {
            HotBridge.log("[DEKK][多答] 消息对象隐藏失败：" + e);
            return false;
        }
    }

    private static boolean shouldHideUserText(String text) {
        if (text == null) return false;
        if (MultiReplyQueue.getPrompt().equals(text)) return true;
        // 新旧两套格式都认（升级前发的消息里还是旧标签）
        return PromptTags.isEnvBlock(text)
                && PromptTags.msgIndex(text) >= 0
                && PromptTags.envEnd(text) >= 0;
    }

    /** 模块加载即隐藏 DEKK 附加前缀，但保留真正的用户消息内容。 */
    private static String visibleUserText(String text) {
        if (MultiReplyQueue.getPrompt().equals(text)) return "";
        int start = PromptTags.msgIndex(text);
        int end = PromptTags.envEnd(text);
        if (start >= 0 && end >= 0 && end > start) {
            return text.substring(start + PromptTags.msgLength(text, start)).trim();
        }
        return "";
    }

    private static String findPromptField(Object value, int depth) {
        if (value == null || depth > 2) return null;
        String prompt = MultiReplyQueue.getPrompt();
        try {
            if (value instanceof String) return prompt.equals(value) ? "String" : null;
            java.lang.reflect.Field[] fs = value.getClass().getDeclaredFields();
            for (int i = 0; i < fs.length; i++) {
                fs[i].setAccessible(true);
                Object child = fs[i].get(value);
                if (child instanceof String && prompt.equals(child)) return fs[i].getName();
                if (child != null && !child.getClass().getName().startsWith("java.")) {
                    String r = findPromptField(child, depth + 1);
                    if (r != null) return fs[i].getName() + "." + r;
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static String inspect(Object value, String prompt, int depth) {
        if (value == null || depth > 2) return null;
        if (value instanceof CharSequence) {
            return prompt.contentEquals((CharSequence) value) ? "命中文本" : null;
        }
        java.lang.reflect.Field[] fs = value.getClass().getDeclaredFields();
        for (int i = 0; i < fs.length; i++) {
            try {
                fs[i].setAccessible(true);
                Object child = fs[i].get(value);
                if (child instanceof String && prompt.equals(child)) return "命中字段=" + fs[i].getName();
                if (child != null && !child.getClass().getName().startsWith("java.")) {
                    String r = inspect(child, prompt, depth + 1);
                    if (r != null) return "字段=" + fs[i].getName() + "." + r;
                }
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static void installTHook(ClassLoader loader) {
        try {
            Class<?> owner = HotHelpers.findClass("ao1", loader);
            Class<?> state = HotHelpers.findClass("d21", loader);
            boolean installed = false;
            java.lang.reflect.Method[] methods = owner.getDeclaredMethods();
            for (int i = 0; i < methods.length; i++) {
                java.lang.reflect.Method method = methods[i];
                Class<?>[] types = method.getParameterTypes();
                if (!"T".equals(method.getName()) || types.length != 2
                        || !state.isAssignableFrom(types[1])) continue;
                method.setAccessible(true);
                HotBridge.hookMethod(method, new HotHook() {
                    @Override
                    protected void afterHookedMethod(HotParam param) throws Throwable {
                        if (param.hasThrowable()) return;
                        Object result = param.args == null || param.args.length < 2 ? null : param.args[1];
                        if (result == null) return;
                        String name = result.getClass().getName();
                        StringBuilder detail = new StringBuilder(name);
                        Object a = null;
                        Object set = null;
                        boolean b = false;
                        boolean d = false;
                        if ("c21".equals(name)) {
                            try {
                                a = HotHelpers.getObjectField(result, "a");
                                b = HotHelpers.getBooleanField(result, "b");
                                set = HotHelpers.getObjectField(result, "c");
                                d = HotHelpers.getBooleanField(result, "d");
                                detail.append(" b=").append(b).append(" d=").append(d)
                                        .append(" a=")
                                        .append(a == null ? "null" : a.getClass().getName())
                                        .append(" setSize=")
                                        .append(set instanceof java.util.Set ? ((java.util.Set) set).size() : -1);
                                if (a != null && "eg6".equals(a.getClass().getName())) {
                                    Object first = HotHelpers.getObjectField(a, "a");
                                    Object second = HotHelpers.getObjectField(a, "b");
                                    detail.append(" eg6.a=").append(first == null ? "null" : first.getClass().getName())
                                            .append(":").append(safeValue(first))
                                            .append(" eg6.b=").append(second == null ? "null" : second.getClass().getName())
                                            .append(":").append(safeValue(second));
                                }
                            } catch (Throwable ignored) { }
                        }
                        boolean success = "c21".equals(name) && b == false && d == false
                                && firstIsSuccess(a);
                        detail.append(" success=").append(success);
                        HotBridge.log("[DEKK][多答] 状态事件：" + detail.toString());
                        if (success) {
                            HotBridge.log("[DEKK][多答] 检测到正常完成状态");
                            tryTriggerContinue(param.args[0]);
                        }
                    }
                });
                installed = true;
            }
            if (!installed) throw new NoSuchMethodException("ao1.T(*, d21)");
            HotBridge.log("[DEKK] 多答状态观察 T Hook 已安装");
        } catch (Throwable t) {
            HotBridge.log("[DEKK] 多答状态观察 T Hook 安装失败：" + t);
        }
    }

    private static void installJHook(ClassLoader loader) {
        try {
            Class<?> owner = HotHelpers.findClass("ao1", loader);
            Class<?> jn1 = HotHelpers.findClass("jn1", loader);
            java.lang.reflect.Method method = owner.getMethod("J", jn1);
            method.setAccessible(true);
            HotBridge.hookMethod(method, new HotHook() {
                @Override
                protected void beforeHookedMethod(HotParam param) throws Throwable {
                    Object event = param.args == null || param.args.length < 1 ? null : param.args[0];
                    if (event == null) return;
                    String eventName = event.getClass().getName();
                    if (automaticDispatch) return;
                    String text = extractPrompt(event, eventName);
                    if (text == null && "cn1".equals(eventName)) {
                        try {
                            Object state = HotHelpers.callMethod(param.thisObject, "Q");
                            Object composer = HotHelpers.callMethod(state, "l");
                            text = (String) HotHelpers.callMethod(composer, "b");
                            HotBridge.log("[DEKK][多答] 从输入状态读取正文：" + (text != null && text.length() > 0));
                        } catch (Throwable e) {
                            HotBridge.log("[DEKK][多答] 输入状态读取失败：" + e);
                        }
                    }
                    HotBridge.log("[DEKK][多答] J事件=" + eventName
                            + " prompt=" + (text == null ? "none" : "present"));
                    if (text == null || text.length() == 0) return;
                    String continuation = MultiReplyQueue.getPrompt();
                    if (continuation.equals(text)) {
                        HotBridge.log("[DEKK][多答] 自动续答消息 dispatch");
                        return;
                    }
                    // 2.5.1 的文本发送可能以 cn1、fn1 或 dn1 进入 J；三种都启动队列。
                    if (!"cn1".equals(eventName) && !"fn1".equals(eventName)
                            && !"dn1".equals(eventName)) return;
                    try {
                        Object ao1 = param.thisObject;
                        Object wr = HotHelpers.callMethod(ao1, "M");
                        String sessionId = (String) HotHelpers.getObjectField(wr, "a");
                        Context ctx = HotApp.currentApplication();
                        if (ctx == null) return;
                        if (!MultiReplySettings.enabled(ctx)) {
                            MultiReplyQueue.cancel();
                            return;
                        }
                        int total = MultiReplySettings.count(ctx);
                        if (total >= MultiReplySettings.MIN_COUNT) {
                            MultiReplyQueue.start(ao1, sessionId, total);
                            HotBridge.log("[DEKK][多答] 用户发送，启动队列 session=" + sessionId + " target=" + total);
                        } else {
                            MultiReplyQueue.cancel();
                        }
                    } catch (Throwable e) {
                        HotBridge.log("[DEKK][多答] J Hook 内部异常：" + e);
                    }
                }
            });
            HotBridge.log("[DEKK] 多答状态观察 J Hook 已安装");
        } catch (Throwable t) {
            HotBridge.log("[DEKK] 多答状态观察 J Hook 安装失败：" + t);
        }
    }

    private static void installSendEntryHook(ClassLoader loader) {
        try {
            Class<?> owner = HotHelpers.findClass("nb5", loader);
            Class<?> controller = HotHelpers.findClass("jb1", loader);
            Class<?> composer = HotHelpers.findClass("yp1", loader);
            Class<?> mode = HotHelpers.findClass("p31", loader);
            java.lang.reflect.Method method = owner.getDeclaredMethod("J", controller, composer,
                    String.class, mode);
            method.setAccessible(true);
            sendMethod = method;
            HotBridge.hookMethod(method, new HotHook() {
                @Override
                protected void beforeHookedMethod(HotParam param) throws Throwable {
                    if (param.args == null || param.args.length != 4) return;
                    String text = param.args[2] instanceof String ? (String) param.args[2] : null;
                    if (text == null || text.length() == 0) return;
                    lastComposer = param.args[1];
                    lastSendMode = param.args[3];
                    if (automaticDispatch || MultiReplyQueue.getPrompt().equals(text)) {
                        HotBridge.log("[DEKK][多答] 自动续答进入真实发送入口");
                        return;
                    }
                    Context ctx = HotApp.currentApplication();
                    if (ctx == null || !MultiReplySettings.enabled(ctx)) {
                        MultiReplyQueue.cancel();
                        return;
                    }
                    try {
                        Object wr = HotHelpers.callMethod(param.args[0], "M");
                        String sessionId = (String) HotHelpers.getObjectField(wr, "a");
                        int total = MultiReplySettings.count(ctx);
                        MultiReplyQueue.start(param.args[0], sessionId, total);
                        HotBridge.log("[DEKK][多答] 真实发送入口启动队列 session="
                                + sessionId + " target=" + total);
                    } catch (Throwable e) {
                        HotBridge.log("[DEKK][多答] 发送入口取会话失败：" + e);
                    }
                }
            });
            HotBridge.log("[DEKK] 多答真实发送入口 Hook 已安装");
        } catch (Throwable t) {
            HotBridge.log("[DEKK] 多答真实发送入口 Hook 安装失败：" + t);
        }
    }

    private static String extractPrompt(Object event, String eventName) {
        try {
            if ("cn1".equals(eventName)) {
                return (String) HotHelpers.getObjectField(event, "b");
            }
            if ("fn1".equals(eventName)) {
                return (String) HotHelpers.getObjectField(event, "a");
            }
            if ("dn1".equals(eventName)) {
                Object request = HotHelpers.getObjectField(event, "a");
                if (request != null && "uy5".equals(request.getClass().getName())) {
                    return (String) HotHelpers.getObjectField(request, "a");
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static void tryTriggerContinue(Object ao1) {
        try {
            Object wr = HotHelpers.callMethod(ao1, "M");
            String sessionId = (String) HotHelpers.getObjectField(wr, "a");
            HotBridge.log("[DEKK][多答] 完成匹配：" + MultiReplyQueue.completionStatus(sessionId));
            if (!MultiReplyQueue.bindCompletionSession(ao1, sessionId)) {
                HotBridge.log("[DEKK][多答] 完成忽略：控制器或会话不匹配");
                return;
            }
            HotBridge.log("[DEKK][多答] 会话绑定通过");
            MultiReplyQueue.onComplete(sessionId);
            if (MultiReplyQueue.shouldTrigger(sessionId)) {
                Context context = HotApp.currentApplication();
                if (context == null || !MultiReplySettings.enabled(context)) {
                    MultiReplyQueue.cancel();
                    return;
                }
                automaticDispatch = true;
                try {
                    // 对齐 nb5.J 普通文本分支；不复用其他会话的输入框对象。
                    Class<?> eventClass = HotHelpers.findClass("cn1", savedLoader);
                    Object event = HotHelpers.newInstance(eventClass,
                            MultiReplyQueue.getPrompt(), Integer.valueOf(3));
                    HotHelpers.callMethod(ao1, "J", event);
                    HotBridge.log("[DEKK][多答] 已调用 cn1 续答入口（待宿主确认发送）");
                } finally {
                    automaticDispatch = false;
                }
            }
        } catch (Throwable e) {
            MultiReplyQueue.cancel();
            HotBridge.log("[DEKK][多答] 续答触发失败，已取消队列：" + e);
        }
    }

    private static boolean firstIsSuccess(Object value) {
        try {
            if (value == null || !"eg6".equals(value.getClass().getName())) return false;
            Object first = HotHelpers.getObjectField(value, "a");
            return first != null && "l01".equals(first.getClass().getName())
                    && HotHelpers.getIntField(first, "a") == 0;
        } catch (Throwable ignored) { return false; }
    }

    private static String safeValue(Object value) {
        if (value == null) return "null";
        try {
            if (value instanceof Number || value instanceof Boolean || value instanceof String) {
                return String.valueOf(value);
            }
            if ("l01".equals(value.getClass().getName())) {
                return "code=" + HotHelpers.getIntField(value, "a");
            }
            if ("xz0".equals(value.getClass().getName())) return "singleton";
        } catch (Throwable ignored) { }
        return "object";
    }
}