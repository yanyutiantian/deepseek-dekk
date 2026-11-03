package com.dekk.yanyu;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

/**
 * 把导入的聊天记录以「原生消息」注入当前会话（2.5.1）。
 *
 * 已核实的模型：
 *   wr.f        LinkedHashMap<Integer,vq>，key = vq.x()（消息序号）
 *   wr.a(vq,Z)  把消息按 x() 放进去 —— 宿主 Compose 会照常渲染
 *   vq.D()      角色："USER" / "ASSISTANT"
 *   vq.r()      用户文本（遍历内容块找 qt8）
 *   vq.C()      AI 正文（遍历内容块找 gg0 子类，取 j()）
 *   qt8(int,String)                                   用户文本块，构造简单
 *   tt8(int,String,int,String,List,Integer)           AI 正文块
 *   nx(int,Integer,String,double,boolean,boolean,String,boolean,int,List,?,?,List,String,?,?,String,int)
 *                                                     消息实现类的简化构造
 *
 * 参数里无法确定含义的引用类型一律先传 null，真机按日志再逐项收紧。
 */
final class ConversationInjector {
    private static ClassLoader loader;

    private ConversationInjector() { }

    static void install(ClassLoader classLoader) {
        loader = classLoader;
        // 关键：MusicSend.sessionOwner 原来绑定在 ao1 的**构造**上，多开/来回切换时会保留旧会话，
        // 导致注入渲染到错误的会话（真机表现：消息跑到第 1 个对话、切换会话闪退）。
        // 当前显示的会话页会持续读取会话状态 ao1.M()，所以用「最近一次读取 M() 的 ao1」覆盖绑定。
        try {
            Class<?> ao1 = HotHelpers.findClass("ao1", classLoader);
            HotBridge.hookMethod(ao1.getMethod("M"), new HotHook() {
                @Override
                protected void afterHookedMethod(HotParam param) {
                    if (param.thisObject != null) MusicSend.sessionOwner(param.thisObject);
                }
            });
            HotBridge.log("[DEKK][导入] 活跃会话跟踪已安装");
        } catch (Throwable t) {
            HotBridge.log("[DEKK][导入] 活跃会话跟踪安装失败: " + t);
        }
    }

    /** 是否已具备注入条件（需要宿主 classloader）。 */
    static boolean ready() { return loader != null; }

    /**
     * 往会话注入一条消息。
     *
     * @param session 宿主 wr（当前会话）
     * @param role    "USER" 或 "ASSISTANT"
     * @param text    正文
     * @param seq     消息序号（必须唯一，建议从当前最大序号往后排）
     */
    static void inject(Object session, String role, String text, int seq) throws Exception {
        if (session == null) throw new IllegalStateException("当前没有会话");
        Object block;
        try {
            block = "USER".equals(role) ? userBlock(text) : assistantBlock(text);
        } catch (Throwable t) {
            throw new IllegalStateException("构造文本块失败(" + role + "): " + describe(t), t);
        }
        List<Object> blocks = new ArrayList<Object>();
        blocks.add(block);
        Object message;
        try {
            message = message(role, blocks, seq);
        } catch (Throwable t) {
            throw new IllegalStateException("构造消息失败(" + role + "): " + describe(t), t);
        }
        try {
            // wr.a(vq, Z)：第二个参数是「是否通知刷新」，必须传 true，否则消息只进 map 不刷新 UI。
            HotHelpers.callMethod(session, "a", message, true);
        } catch (Throwable t) {
            throw new IllegalStateException("加入会话失败: " + describe(t), t);
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

    private static Object userBlock(String text) throws Exception {
        // qt8 字段 a=id(String), b=int, c=content(String)；公开构造只有 (int,String)，
        // 若序列化校验要求 id，则改用 4 参合成构造 (int,int,String,String)。
        Class<?> qt8 = HotHelpers.findClass("qt8", loader);
        try {
            Constructor<?> ctor = qt8.getDeclaredConstructor(int.class, String.class);
            ctor.setAccessible(true);
            return ctor.newInstance(0, text);
        } catch (Throwable first) {
            Constructor<?> ctor = qt8.getDeclaredConstructor(int.class, int.class, String.class, String.class);
            ctor.setAccessible(true);
            return ctor.newInstance(0, 0, "dekk-msg-" + System.nanoTime(), text);
        }
    }

    private static Object assistantBlock(String text) throws Exception {
        // tt8 = StaticChatMessageFragment.ResponseFragment，Kotlin 序列化要求 [id, content] 必填，
        // 字段顺序 a=id(String), b=int, c=content(String), d=List, e=Integer。
        Class<?> tt8 = HotHelpers.findClass("tt8", loader);
        Constructor<?> ctor = tt8.getDeclaredConstructor(String.class, int.class, String.class,
                List.class, Integer.class);
        ctor.setAccessible(true);
        return ctor.newInstance("dekk-msg-" + System.nanoTime(), 0, text, new ArrayList<Object>(), null);
    }

    private static Object message(String role, List<Object> blocks, int seq) throws Exception {
        // ⚠️ 已验证的坑：q06（第 12 个参数）传 null 时，宿主渲染这条消息会在
        //    c30.a(...) 里读 q06.a 抛 NullPointerException，整机闪退（真机堆栈已确认）。
        //    所以「注入渲染」在真实使用前，必须先从会话里已有的一条消息克隆出完整的
        //    q88/q06/xu/a02（不能使用 null）。目前模块里已不再提供渲染入口，
        //    这里保留实现仅作参考，切勿直接接回 UI。
        Class<?> nx = HotHelpers.findClass("nx", loader);
        Class<?> q88 = HotHelpers.findClass("q88", loader);
        Class<?> q06 = HotHelpers.findClass("q06", loader);
        Class<?> xu = HotHelpers.findClass("xu", loader);
        Class<?> a02 = HotHelpers.findClass("a02", loader);
        Constructor<?> ctor = nx.getDeclaredConstructor(int.class, Integer.class, String.class, double.class,
                boolean.class, boolean.class, String.class, boolean.class, int.class, List.class,
                q88, q06, List.class, String.class, xu, a02, String.class, int.class);
        ctor.setAccessible(true);
        return ctor.newInstance(seq, null, role, System.currentTimeMillis() / 1000d,
                false, false, null, false, 0, blocks,
                null, null, new ArrayList<Object>(), null, null, null, null, 0);
    }

    /** 当前会话的最大消息序号，用于给注入的消息排唯一序号。 */
    static int maxSeq(Object session) {
        try {
            java.util.Map<?, ?> messages = (java.util.Map<?, ?>) HotHelpers.getObjectField(session, "f");
            if (messages == null) return 0;
            int max = 0;
            for (Object key : messages.keySet()) {
                if (key instanceof Integer) max = Math.max(max, ((Integer) key).intValue());
                else if (key != null) {
                    Object message = messages.get(key);
                    if (message != null) max = Math.max(max, (Integer) HotHelpers.callMethod(message, "x"));
                }
            }
            return max;
        } catch (Throwable t) {
            return 0;
        }
    }
}