package com.dekk.yanyu;

import android.content.Context;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 消息分段渲染器 —— 按 ilke 的 {@code dev.codex.deepseektweaks.ConversationSegments}
 * （jadx 反编译源码，见 {@code ilkejava/src/sources/dev/codex/deepseektweaks/ConversationSegments.java}）
 * 逐段对齐移植，混淆名适配宿主 DeepSeek 2.5.1。
 *
 * <h3>为什么必须用「代理」而不是构造真对象</h3>
 * <pre>
 * ilke 的每一段都用一个 {@link SegmentFlow}（Proxy 实现 ps8）顶上去：
 *   args[2]  = segment.proxy      // ps8 代理：getValue() 动态返回「本段当前文本」
 *   args[8]  = segment.options()  // options 第 0 字段换成 hv3 代理：v() = 本段是否完成
 *   args[9]  = segment.metadata() // metadata 第 3 字段换成 hv3 代理：v() = 本段是否仍在流式
 *   args[11] = 我们自己的 Composer（restartGroup 的返回值）
 * 之前用「一次性 new 出来的 rs8/gh7 假 flow」代替代理，宿主重组时读到的 flow 行为与它
 * 预期不一致 → Compose 组栈错乱 → FATAL「Missed recording an endGroup」。
 * </pre>
 *
 * <h3>组管理（严格照 ilke）</h3>
 * <pre>
 * objCall = ey3.i0(ROOT_KEY)                  // startRestartGroup，之后一律用它当 Composer
 * remember()/ru0.q(flow,...)                  // 记住 Source + 订阅 flow
 * for (part : parts) {
 *     ey3.e0(PART_KEY, part.key)              // startReplaceableGroup：每段一个独立节点
 *     renderer.render(source.arguments(...))  // 用「本段参数」重调 ru0.c 画这一段
 *     ey3.q(false)                            // endReplaceableGroup（finally 里也补一次）
 * }
 * group = ey3.v()                             // endRestartGroup → jk7
 * jk7.d = 重组回调 { q(newComposer) → 用新 Composer 重新 render }
 * </pre>
 * ★ ilke 在 catch 分支里 <b>不会</b> 补 endRestartGroup —— 这里照抄，
 * 避免在 Composer 已经出错时再动它的组栈。
 */
final class ConversationSegments {

    private static final String PREF = "dekk_prefs";
    private static final String PREF_KEY = "segmented_replies";

    /** 参数下标（对着 2.5.1 的 ru0.c 签名核过）。 */
    private static final int CARRIER_SLOT = 2;
    private static final int OPTIONS_SLOT = 8;
    private static final int METADATA_SLOT = 9;
    private static final int PADDING_SLOT = 10;
    private static final int COMPOSER_SLOT = 11;
    private static final int CHANGED_SLOT_1 = 12;
    private static final int CHANGED_SLOT_2 = 13;

    /** 组 key 与 changed 掩码（照 ilke 原值）。 */
    private static final int ROOT_KEY = 0x496c6b31;
    private static final int PART_KEY = 0x496c6b32;
    private static final int CHANGED_MASK_1 = -2113930113;
    private static final int CHANGED_MASK_2 = -15;

    private static final Pattern MARKER_LINE =
            Pattern.compile("(?m)^\\s*\\[SGM[1-9][0-9]{0,8}\\]\\s*$");

    private static boolean installed;
    private static final List<String> LOG_STATE = new ArrayList<String>();
    private static final ThreadLocal<Boolean> RENDERING = new ThreadLocal<Boolean>();

    private final ClassLoader loader;
    private final Method hook;                 // ru0.c
    private final Method startRestart;         // ey3.i0(int) → Composer
    private final Method endRestart;           // ey3.v() → jk7
    private final Method remember;             // ey3.S() → Object
    private final Method updateRemember;       // ey3.q0(Object)
    private final Method startPart;            // ey3.e0(int, Object)
    private final Method endPart;              // ey3.q(boolean)
    private final Method collectState;         // ru0.q(ps8, l44, ey3, int, int) → state
    private final Method stateValue;           // ns8/db6.getValue()
    private final Method flowValue;            // ps8.getValue()
    private final Method invoke0;              // hv3.v()
    private final Field restartField;          // jk7.d (wv3)
    private final Field streamingField;        // rr5.d (hv3)
    private final Object unit;                 // lp9.a
    private final Class<?> flowType;           // ps8
    private final Class<?> function0;          // hv3
    private final Class<?> function2;          // wv3
    private final Constructor<?> optionsCtor;
    private final Field[] optionsFields;
    private final Constructor<?> metadataCtor;
    private final Field[] metadataFields;
    private final Method padding;              // jt5.I(c76, f, f, f, f)

    private ConversationSegments(ClassLoader loader) throws Exception {
        this.loader = loader;
        flowType = HotHelpers.findClass("ps8", loader);
        Method found = null;
        Method[] methods = HotHelpers.findClass("ru0", loader).getDeclaredMethods();
        for (int i = 0; i < methods.length && found == null; i++) {
            Class<?>[] types = methods[i].getParameterTypes();
            if ("c".equals(methods[i].getName()) && types.length > CHANGED_SLOT_2
                    && types[CARRIER_SLOT] == flowType) {
                found = methods[i];
            }
        }
        if (found == null) {
            throw new NoSuchMethodException("ru0.c(...,ps8,...)");
        }
        hook = found;

        Class<?> composer = HotHelpers.findClass("ey3", loader);
        startRestart = composer.getMethod("i0", new Class<?>[]{Integer.TYPE});
        endRestart = composer.getMethod("v", new Class<?>[0]);
        remember = composer.getMethod("S", new Class<?>[0]);
        updateRemember = composer.getMethod("q0", new Class<?>[]{Object.class});
        startPart = composer.getMethod("e0", new Class<?>[]{Integer.TYPE, Object.class});
        endPart = composer.getMethod("q", new Class<?>[]{Boolean.TYPE});

        // ru0.q 返回的 SnapshotState：先试 ns8（ilke 用的），保底 db6。
        Method value = null;
        try {
            value = HotHelpers.findClass("ns8", loader).getMethod("getValue", new Class<?>[0]);
        } catch (Throwable ignored) { }
        if (value == null) {
            try {
                value = HotHelpers.findClass("db6", loader).getMethod("getValue", new Class<?>[0]);
            } catch (Throwable ignored) { }
        }
        stateValue = value;
        collectState = HotHelpers.findClass("ru0", loader).getMethod("q",
                new Class<?>[]{flowType, HotHelpers.findClass("l44", loader), composer,
                        Integer.TYPE, Integer.TYPE});
        restartField = HotHelpers.findClass("jk7", loader).getDeclaredField("d");
        restartField.setAccessible(true);
        unit = HotHelpers.findClass("lp9", loader).getField("a").get(null);

        flowValue = flowType.getMethod("getValue", new Class<?>[0]);
        function0 = HotHelpers.findClass("hv3", loader);
        function2 = HotHelpers.findClass("wv3", loader);
        invoke0 = function0.getMethod("v", new Class<?>[0]);
        streamingField = HotHelpers.findClass("rr5", loader).getField("d");

        Class<?> or5 = HotHelpers.findClass("or5", loader);
        optionsCtor = or5.getConstructor(new Class<?>[]{
                HotHelpers.findClass("i4", loader), Boolean.TYPE, HotHelpers.findClass("j", loader),
                Boolean.TYPE, Integer.TYPE, Integer.TYPE});
        optionsFields = new Field[9];
        for (int i = 0; i < optionsFields.length; i++) {
            optionsFields[i] = or5.getField(String.valueOf((char) ('a' + i)));
            optionsFields[i].setAccessible(true);
        }
        Class<?> rr5 = HotHelpers.findClass("rr5", loader);
        metadataCtor = rr5.getConstructor(new Class<?>[]{
                String.class, Integer.class, Integer.TYPE, function0});
        metadataFields = new Field[3];
        for (int i = 0; i < metadataFields.length; i++) {
            metadataFields[i] = rr5.getField(String.valueOf((char) ('a' + i)));
            metadataFields[i].setAccessible(true);
        }
        padding = HotHelpers.findClass("jt5", loader).getMethod("I", new Class<?>[]{
                HotHelpers.findClass("c76", loader), Float.TYPE, Float.TYPE, Float.TYPE, Float.TYPE});
    }

    /** 每段渲染回调（= ilke 的 {@code Renderer}）。 */
    private interface Renderer {
        void render(Object[] args) throws Throwable;
    }

    /** 安装到宿主（对应 ilke 的 {@code ConversationThemeHook$2}）。 */
    static void install(ClassLoader loader) {
        if (installed) {
            return;
        }
        try {
            final ConversationSegments segments = new ConversationSegments(loader);
            HotBridge.hookMethod(segments.hook, new HotHook() {
                protected void beforeHookedMethod(HotParam param) {
                    if (param.args == null || param.args.length <= CHANGED_SLOT_2) {
                        return;
                    }
                    if (Boolean.TRUE.equals(RENDERING.get())) {
                        // 我们自己重调进来的（ilke 的 owns 分支）：只清 changed 位，
                        // 不 setResult → 让原方法正常画「这一段」。
                        try {
                            param.args[CHANGED_SLOT_2] = Integer.valueOf(
                                    ((Integer) param.args[CHANGED_SLOT_2]).intValue()
                                            & CHANGED_MASK_2);
                        } catch (Throwable ignored) { }
                        return;
                    }
                    Context context = HotApp.currentApplication();
                    if (context == null) {
                        return;
                    }
                    // ★「回复分段」生效 = 分段开关 + 微信对话气泡都开着。
                    //   没开 → 这条链路完全不介入（不改参数、不 setResult），宿主原生渲染。
                    //   唯一例外：单独开了「虚拟转账」，且这条文本确实带 [转账&金额] 标记。
                    boolean segmented = enabled(context) && ChatBubbleHook.enabled(context);
                    if (!segmented
                            && (!TransferStore.promptEnabled(context)
                                    || !segments.hasTransferMark(param))) {
                        return;
                    }
                    RENDERING.set(Boolean.TRUE);
                    try {
                        segments.render(param.args, new Renderer() {
                            public void render(Object[] args) throws Throwable {
                                segments.hook.invoke(null, args);   // ← ilke: hook.invoke(null, objArr2)
                            }
                        });
                        param.setResult(null);                 // ★ 永远阻止原方法（否则会多出一条完整气泡）
                    } catch (Throwable t) {
                        HotBridge.log("[DEKK][分段] 分段渲染失败: " + describe(t));
                        // 照 ilke：不动渲染结果，让原方法继续
                    } finally {
                        RENDERING.remove();
                    }
                }
            });
            installed = true;
            HotBridge.log("[DEKK][分段] 已挂钩 ru0.c（ilke 完整机制：Source + SegmentFlow 代理）");
        } catch (Throwable t) {
            HotBridge.log("[DEKK][分段] 安装失败: " + describe(t));
        }
    }

    /** 这条消息的文本是否带 {@code [转账&金额]} 标记（只开转账、不开分段时用于精确判断）。 */
    private boolean hasTransferMark(HotParam param) {
        try {
            Object value = flowValue.invoke(param.args[CARRIER_SLOT], new Object[0]);
            return value instanceof String && TransferProtocol.has((String) value);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 对应 ilke 的 {@code ConversationSegments#render(Object[], Renderer)}。
     * 组结构与异常处理都按原文，顺序不能改。
     */
    private void render(Object[] args, Renderer renderer) throws Throwable {
        final Object composerArg = args[COMPOSER_SLOT];
        if (composerArg == null) {
            return;
        }
        final Object composer = startRestart.invoke(composerArg, new Object[]{Integer.valueOf(ROOT_KEY)});
        try {
            // --- remember()：把 Source 记在这次 restartGroup 的记忆槽里 ---
            Source source = null;
            try {
                Object remembered = remember.invoke(composer, new Object[0]);
                Object streamingFlag = streamingField.get(args[METADATA_SLOT]);
                if (remembered instanceof Source
                        && ((Source) remembered).flow == args[CARRIER_SLOT]
                        && same(((Source) remembered).isStreaming, streamingFlag)) {
                    source = (Source) remembered;
                } else {
                    source = new Source(args[CARRIER_SLOT], streamingFlag);
                    updateRemember.invoke(composer, new Object[]{source});
                }
            } catch (Throwable ignored) { }
            if (source == null) {
                source = new Source(args[CARRIER_SLOT], null);
            }

            // --- ru0.q(flow, ..., Composer, 0, 7)：订阅正文 flow（流式重组由它驱动） ---
            try {
                Object state = collectState.invoke(null, new Object[]{
                        source.flow, null, composer, Integer.valueOf(0), Integer.valueOf(7)});
                if (state != null && stateValue != null) {
                    stateValue.invoke(state, new Object[0]);
                }
            } catch (Throwable ignored) { }

            final List<ReplySegments.Part> parts = source.current().parts;
            for (int i = 0; i < parts.size(); i++) {
                final ReplySegments.Part part = parts.get(i);
                final boolean notFirst = i > 0;
                // 流式中的「半截协议」（转账等）这一段先不分段，交给宿主整条渲染。
                if (!part.complete && TransferStore.promptEnabled(HotApp.currentApplication())
                        && TransferProtocol.has(part.text)) {
                    continue;
                }
                startPart.invoke(composer, new Object[]{
                        Integer.valueOf(PART_KEY), Integer.valueOf(part.key)});   // startReplaceableGroup
                try {
                    if (!ConversationTransfers.render(args, composer, part, notFirst)) {
                        renderer.render(source.arguments(args, composer, part.key, notFirst));
                    }
                } finally {
                    endPart.invoke(composer, new Object[]{Boolean.FALSE});     // endReplaceableGroup
                }
            }

            // --- endRestartGroup + 写回重组回调（★ 只在正常路径调用，异常时不补，照 ilke） ---
            final Object group = endRestart.invoke(composer, new Object[0]);
            if (group != null) {
                final Object[] snapshot = (Object[]) args.clone();
                final Renderer target = renderer;
                try {
                    restartField.set(group, Proxy.newProxyInstance(function2.getClassLoader(),
                            new Class<?>[]{function2}, new InvocationHandler() {
                                public Object invoke(Object proxy, Method method, Object[] a)
                                        throws Throwable {
                                    if (!"q".equals(method.getName())) {
                                        return objectMethod(proxy, method, a);
                                    }
                                    Object[] again = (Object[]) snapshot.clone();
                                    if (a != null && a.length > 0 && a[0] != null) {
                                        again[COMPOSER_SLOT] = a[0];      // 新的 Composer
                                    }
                                    again[CHANGED_SLOT_1] = Integer.valueOf(
                                            ((Integer) again[CHANGED_SLOT_1]).intValue() | 1);
                                    render(again, target);
                                    return unit;
                                }
                            }));
                    logOnce("restart 回调已接管（可防止宿主重绘原始气泡）");
                } catch (Throwable t) {
                    logOnce("restart 回调写入失败: " + describe(t));
                }
            } else {
                // 宿主自己创建的 p50 会留在 jk7.d → 重组时会重绘一条「含标记的原始气泡」
                logOnce("endRestartGroup 返回 null →宿主可能重绘原始气泡");
            }
        } catch (Throwable t) {
            // 照 ilke：吞掉异常（不 rethrow、也不补 endRestartGroup）
            logOnce("渲染异常: " + describe(t));
        }
    }

    private static boolean same(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    /**
     * 一次「正文流」的解析缓存（= ilke 的 {@code ConversationSegments$Source}）：
     * 拿到当前完整文本 → 解析成若干段 → 为每段提供代理 flow / options / metadata。
     */
    private final class Source {

        final Object flow;              // 原始 ps8
        final Object isStreaming;       // hv3（rr5.d）

        private ReplySegments.Result lastResult;
        private String lastText;
        private boolean lastStreaming;
        private boolean lastSplit;

        private final Map<Integer, SegmentFlow> segments = new HashMap<Integer, SegmentFlow>();

        Source(Object flow, Object isStreaming) {
            this.flow = flow;
            this.isStreaming = isStreaming;
        }

        /** 当前是否仍在流式（hv3.v()）。 */
        private boolean streaming() throws Throwable {
            if (isStreaming == null) {
                return true;
            }
            Object value = invoke0.invoke(isStreaming, new Object[0]);
            return !Boolean.FALSE.equals(value);
        }

        /** 对应 ilke 的 {@code Source#parse(String)}：带缓存，只在文本/流式/开关变化时重解析。 */
        synchronized ReplySegments.Result parse(String text) throws Throwable {
            boolean streaming = streaming();
            Context context = HotApp.currentApplication();
            boolean split = enabled(context) && ChatBubbleHook.enabled(context);
            if (lastResult == null || !same(text, lastText)
                    || streaming != lastStreaming || split != lastSplit) {
                lastText = text;
                lastStreaming = streaming;
                lastSplit = split;
                lastResult = parseNow(text, streaming, split);
            }
            return lastResult;
        }

        /** 原 flow 的当前值。 */
        synchronized ReplySegments.Result current() throws Throwable {
            Object value = flowValue.invoke(flow, new Object[0]);
            return parse(value instanceof String ? (String) value : "");
        }

        private ReplySegments.Result parseNow(String text, boolean streaming, boolean split) {
            if (text == null) {
                text = "";
            }
            // ★ 无论分段开关如何，都交给 MessageRenderParts：
            //   · 它会把「转账独立行」切成卡片段（与分段开关无关）→ 虚拟转账不再依赖分段开关；
            //   · split=false 时它不切普通文本段，整条文本仍是一个气泡。
            boolean transfers = TransferStore.promptEnabled(HotApp.currentApplication());
            List<ReplySegments.Part> parts = MessageRenderParts.parse(text, streaming, split, transfers);
            if (parts == null) {
                parts = new ArrayList<ReplySegments.Part>();
            }
            boolean transformed = parts.size() > 1 || text.indexOf("[SGM") >= 0;
            if (split && text.indexOf("[SGM") >= 0) {
                // 分段开启、且有标记但没能正确切段（标记没独占行首）→ 至少把标记从显示里去掉
                List<ReplySegments.Part> fixed = new ArrayList<ReplySegments.Part>(parts.size());
                for (int i = 0; i < parts.size(); i++) {
                    ReplySegments.Part part = parts.get(i);
                    fixed.add(new ReplySegments.Part(part.key, strip(part.text), part.complete));
                }
                parts = fixed;
            }
            logOnce("解析 " + parts.size() + " 段（split=" + split + "，transfers=" + transfers
                    + "，streaming=" + streaming + "，含标记=" + (text.indexOf("[SGM") >= 0) + "）");
            return new ReplySegments.Result(new ArrayList<ReplySegments.Part>(parts), transformed);
        }

        /** 取（或建）第 key 段的代理（= ilke 的 {@code Source#segment(int)}）。 */
        synchronized SegmentFlow segment(int key) {
            SegmentFlow segment = segments.get(Integer.valueOf(key));
            if (segment == null) {
                segment = new SegmentFlow(this, key);
                segments.put(Integer.valueOf(key), segment);
            }
            return segment;
        }

        /**
         * 为「这一段」克隆一份参数（= ilke 的 {@code Source#arguments}）：
         * flow → 代理；options/metadata → 代理标志；非首段加段间距；清 changed 位。
         */
        Object[] arguments(Object[] args, Object composer, int key, boolean notFirst)
                throws Throwable {
            final SegmentFlow segment = segment(key);
            Object[] out = (Object[]) args.clone();
            out[CARRIER_SLOT] = segment.proxy;
            out[OPTIONS_SLOT] = segment.options(args[OPTIONS_SLOT]);
            out[METADATA_SLOT] = segment.metadata(args[METADATA_SLOT]);
            out[COMPOSER_SLOT] = composer;                    // ★ ilke：换成我们 restartGroup 的 Composer
            if (notFirst && args[PADDING_SLOT] != null) {
                try {
                    out[PADDING_SLOT] = padding.invoke(null, new Object[]{
                            args[PADDING_SLOT], Float.valueOf(0f), Float.valueOf(12f),
                            Float.valueOf(0f), Float.valueOf(0f)});
                } catch (Throwable ignored) { }
            }
            out[CHANGED_SLOT_1] = Integer.valueOf(
                    ((Integer) out[CHANGED_SLOT_1]).intValue() & CHANGED_MASK_1);
            out[CHANGED_SLOT_2] = Integer.valueOf(
                    ((Integer) out[CHANGED_SLOT_2]).intValue() & CHANGED_MASK_2);
            return out;
        }
    }

    /**
     * 一段的 ps8 代理（= ilke 的 {@code ConversationSegments$SegmentFlow}）：
     * {@code getValue()} 永远从「原始 flow 的当前文本」现解析出本段文本，
     * 所以宿主重组时读到的始终是最新内容。
     */
    private final class SegmentFlow implements InvocationHandler {

        final Source source;
        final int key;
        final Object proxy;          // ps8 代理
        final Object complete;       // hv3 代理：v() = 本段是否已完成
        final Object active;         // hv3 代理：v() = 本段是否仍在流式

        private Object fixedOptions;
        private Object originalOptions;
        private Object fixedMetadata;
        private Object originalMetadata;

        SegmentFlow(Source source, int key) {
            this.source = source;
            this.key = key;
            this.proxy = Proxy.newProxyInstance(loader, new Class<?>[]{flowType}, this);
            this.complete = flag(false);
            this.active = flag(true);
        }

        /** hv3 代理：{@code v()} 返回本段的完成/流式标志（inverted=true 时取反）。 */
        private Object flag(final boolean inverted) {
            return Proxy.newProxyInstance(function0.getClassLoader(), new Class<?>[]{function0},
                    new InvocationHandler() {
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            if ("v".equals(method.getName())) {
                                boolean done;
                                try {
                                    done = complete();
                                } catch (Throwable ignored) {
                                    done = false;
                                }
                                return Boolean.valueOf(inverted ? !done : done);
                            }
                            return objectMethod(proxy, method, args);
                        }
                    });
        }

        boolean complete() throws Throwable {
            ReplySegments.Part part = source.current().part(key);
            return part == null || part.complete;
        }

        String text(String full) throws Throwable {
            ReplySegments.Part part = source.parse(full).part(key);
            return part == null ? "" : part.text;
        }

        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("getValue".equals(method.getName())) {
                Object value = flowValue.invoke(source.flow, new Object[0]);
                return text(value instanceof String ? (String) value : "");
            }
            return objectMethod(proxy, method, args);
        }

        Object options(Object original) throws Exception {
            if (original != this.originalOptions) {
                Object copy = optionsCtor.newInstance(new Object[]{
                        null, Boolean.FALSE, null, Boolean.FALSE,
                        Integer.valueOf(0), Integer.valueOf(1023)});
                for (int i = 0; i < optionsFields.length; i++) {
                    optionsFields[i].set(copy, optionsFields[i].get(original));
                }
                optionsFields[0].set(copy, complete);
                this.originalOptions = original;
                this.fixedOptions = copy;
            }
            return this.fixedOptions;
        }

        Object metadata(Object original) throws Exception {
            if (original != this.originalMetadata) {
                this.fixedMetadata = metadataCtor.newInstance(new Object[]{
                        metadataFields[0].get(original), metadataFields[1].get(original),
                        metadataFields[2].get(original), active});
                this.originalMetadata = original;
            }
            return this.fixedMetadata;
        }
    }

    /** 诊断日志（同内容只打一次，总数上限 20 条）。 */
    private static void logOnce(String line) {
        synchronized (LOG_STATE) {
            if (LOG_STATE.size() >= 20 || LOG_STATE.contains(line)) {
                return;
            }
            LOG_STATE.add(line);
        }
        HotBridge.log("[DEKK][分段] " + line);
    }

    /** 去掉独占一行的 {@code [SGMn]} 标记。 */
    static String strip(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = MARKER_LINE.matcher(text).replaceAll("");
        while (cleaned.contains("\n\n\n")) {
            cleaned = cleaned.replace("\n\n\n", "\n\n");
        }
        return cleaned.trim();
    }

    static String describe(Throwable throwable) {
        StringBuilder builder = new StringBuilder();
        Throwable t = throwable;
        int guard = 0;
        while (t != null && guard < 6) {
            if (builder.length() > 0) {
                builder.append(" <- ");
            }
            builder.append(t.getClass().getSimpleName());
            if (t.getMessage() != null) {
                builder.append('(').append(t.getMessage()).append(')');
            }
            t = t.getCause();
            guard++;
        }
        return builder.toString();
    }

    /** 设置页开关。 */
    static boolean enabled(Context context) {
        return context != null
                && context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                        .getBoolean(PREF_KEY, false);
    }

    static void setEnabled(Context context, boolean value) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_KEY, value).apply();
    }

    static boolean isInstalled() {
        return installed;
    }

    /** 代理上的通用 Object 方法处理（照 ilke 的 objectMethod，另对原始类型兜底）。 */
    static Object objectMethod(Object proxyRef, Method method, Object[] args) {
        String name = method.getName();
        if ("toString".equals(name)) {
            return "DEKK.Segments";
        }
        if ("hashCode".equals(name)) {
            return Integer.valueOf(System.identityHashCode(proxyRef));
        }
        if ("equals".equals(name)) {
            return Boolean.valueOf(args != null && args.length > 0 && proxyRef == args[0]);
        }
        Class<?> type = method.getReturnType();
        if (type == Boolean.TYPE) {
            return Boolean.FALSE;
        }
        if (type == Integer.TYPE) {
            return Integer.valueOf(0);
        }
        if (type == Long.TYPE) {
            return Long.valueOf(0L);
        }
        if (type == Float.TYPE) {
            return Float.valueOf(0f);
        }
        if (type == Double.TYPE) {
            return Double.valueOf(0d);
        }
        return null;
    }
}