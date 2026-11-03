package com.dekk.yanyu;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;
import java.util.Map;

/**
 * 通话状态机。
 *
 * <p>整个通话是「回合制」的：用户说话 → 宿主原生 ASR 识别并发送 → AI 回复 →
 * 回复完整生成后宿主自己朗读（见 {@link VoiceCallTts}）。模块只负责界面与串场，
 * 音频不经过模块，所以不会影响识别和音色。
 *
 * <p>轮询每秒一次，读宿主的会话状态（和「主动消息」用的是同一套反射）：
 * <ul>
 *   <li>最后一条 AI 消息里带 {@code [ctx:call]} → 弹来电邀请；</li>
 *   <li>会话正在生成 → 通话页显示「对方正在说话」。</li>
 * </ul>
 */
final class VoiceCallManager {

    static final int IDLE = 0, OUTGOING = 1, INCOMING = 2, ACTIVE = 3;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long POLL_MS = 1200L;

    private static boolean installed;
    private static ClassLoader hostLoader;
    private static int state = IDLE;
    private static String session = "";
    private static String opening = "";
    private static int seenInviteId = -1;
    private static int lastAssistantId = -1;
    /** 已经朗读过的 AI 消息 id，避免重复念。 */
    private static int spokenId = -1;
    private static long connectedAt;
    private static long callSeconds;

    private VoiceCallManager() { }

    static void install(ClassLoader loader) {
        if (installed) return;
        hostLoader = loader;
        installed = true;
        MAIN.postDelayed(new Runnable() {
            public void run() {
                try {
                    step();
                } catch (Throwable t) {
                    HotBridge.log("[DEKK][通话] 轮询异常: " + t);
                }
                MAIN.postDelayed(this, POLL_MS);
            }
        }, 1500);
        HotBridge.log("[DEKK][通话] 状态机已启动");
    }

    static int state() {
        return state;
    }

    static String session() {
        return session;
    }

    // ── 轮询 ────────────────────────────────────────────────

    private static void step() throws Throwable {
        Context context = HotApp.currentApplication();
        if (context == null || !VoiceCallStore.enabled(context)) return;
        Activity activity = DekkSettingsPage.currentActivity();
        Object owner = MusicSend.owner();
        if (owner == null) return;
        Object conversation = HotHelpers.callMethod(owner, "M");
        if (conversation == null) return;

        String sid = (String) HotHelpers.callMethod(owner, "e");
        Map<?, ?> messages = (Map<?, ?>) HotHelpers.getObjectField(conversation, "f");
        if (messages == null) return;

        int assistantId = -1;
        int newestId = -1;
        String assistantText = null;
        String newestText = null;
        Object assistantMessage = null;
        for (Object value : messages.values()) {
            if (value == null) continue;
            Object idValue = HotHelpers.callMethod(value, "x");
            Object roleValue = HotHelpers.callMethod(value, "D");
            if (!(idValue instanceof Integer)) continue;
            int id = ((Integer) idValue).intValue();
            String role = roleValue == null ? "" : roleValue.toString();
            String text = VoiceCallText.raw(value);
            if (text == null) {
                try {
                    // 用户消息是 r()，AI 正文是 C()（见 ConversationInjector 的注释）
                    Object body = "ASSISTANT".equals(role)
                            ? HotHelpers.callMethod(value, "C")
                            : HotHelpers.callMethod(value, "r");
                    text = body == null ? null : body.toString();
                } catch (Throwable ignored) {
                    try {
                        Object body = HotHelpers.callMethod(value, "r");
                        text = body == null ? null : body.toString();
                    } catch (Throwable ignoredToo) { }
                }
            }
            if ("ASSISTANT".equals(role) && id > assistantId) {
                assistantId = id;
                assistantText = text;
                assistantMessage = value;
            }
            if (id > newestId) {
                newestId = id;
                newestText = text;
            }
        }
        if (assistantId > 0) lastAssistantId = assistantId;

        if (state != IDLE) {
            if (VoiceCallUi.current() == null) {
                // 界面被宿主销毁/移除了，别再挂着状态
                hangup(false);
                return;
            }
            boolean busy = isBusy(conversation);
            updateSpeaking(busy);
            // 回复完整生成后才朗读（等价于点这条消息的朗读按钮）
            if (!busy && assistantId > 0 && assistantId != spokenId && assistantMessage != null) {
                spokenId = assistantId;
                if (VoiceCallStore.autoSpeak(context)) {
                    VoiceCallTts.speak(assistantMessage, conversation);
                }
            }
            checkDelivered(context, sid, messages);
            return;
        }
        if (activity == null || activity.isFinishing()) return;

        // 来电：AI 最近一条回复里带标记，且这一轮已经生成完。
        if (assistantId <= 0 || assistantText == null) return;
        if (assistantId == seenInviteId) return;
        if (!VoiceCallProtocol.requestsCall(assistantText)) return;
        if (isBusy(conversation)) return;

        seenInviteId = assistantId;
        ringIn(activity, sid, VoiceCallProtocol.opening(assistantText));
    }

    private static void updateSpeaking(boolean busy) {
        VoiceCallUi ui = VoiceCallUi.current();
        if (ui == null) return;
        ui.setSpeaking(busy);
    }

    private static boolean isBusy(Object conversation) {
        try {
            if (idleState == null) {
                idleState = HotHelpers.getStaticObjectField(HotHelpers.findClass("ir", hostLoader), "a");
            }
            Object flow = HotHelpers.getObjectField(conversation, "i");
            Object status = HotHelpers.callMethod(flow, "getValue");
            return status != idleState;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object idleState;

    // ── 发送兜底 ────────────────────────────────────────────

    private static long micClosedAt;
    private static int userIdAtClose;

    /** 松开麦克风时记一笔，几秒后确认宿主真的把识别结果发出去了。 */
    static void markMicClosed() {
        micClosedAt = SystemClock.elapsedRealtime();
        userIdAtClose = currentUserId();
    }

    private static void checkDelivered(Context context, String sid, Map<?, ?> messages) {
        if (micClosedAt == 0) return;
        if (SystemClock.elapsedRealtime() - micClosedAt < 5000) return;
        micClosedAt = 0;
        int now = maxUserId(messages);
        if (now > userIdAtClose) {
            HotBridge.log("[DEKK][通话] 语音消息已发出（id=" + now + "）");
            return;
        }
        String draft = draft();
        if (draft == null || draft.trim().length() == 0) {
            HotBridge.log("[DEKK][通话] 松开后既没有新消息，也没有草稿 —— 多半没录上");
            return;
        }
        HotBridge.log("[DEKK][通话] 宿主没自动发送，兜底发出：" + draft);
        rescue(sid, draft);
    }

    private static int currentUserId() {
        try {
            Object owner = MusicSend.owner();
            if (owner == null) return 0;
            Object conversation = HotHelpers.callMethod(owner, "M");
            if (conversation == null) return 0;
            return maxUserId((Map<?, ?>) HotHelpers.getObjectField(conversation, "f"));
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int maxUserId(Map<?, ?> messages) {
        int max = 0;
        if (messages == null) return 0;
        for (Object value : messages.values()) {
            if (value == null) continue;
            try {
                Object role = HotHelpers.callMethod(value, "D");
                if (!"USER".equals(role)) continue;
                Object id = HotHelpers.callMethod(value, "x");
                if (id instanceof Integer && ((Integer) id).intValue() > max) {
                    max = ((Integer) id).intValue();
                }
            } catch (Throwable ignored) { }
        }
        return max;
    }

    private static String draft() {
        try {
            Object owner = MusicSend.owner();
            if (owner == null) return null;
            Object input = HotHelpers.callMethod(owner, "Q");
            if (input == null) return null;
            Object text = HotHelpers.callMethod(input, "b");
            return text == null ? null : text.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void rescue(final String sid, final String text) {
        final HostMessageDispatch dispatch = ConversationTransfers.dispatcher();
        if (dispatch == null) {
            HotBridge.log("[DEKK][通话] 兜底发送不可用：dispatcher 未就绪");
            return;
        }
        try {
            dispatch.send(sid, text, "call-" + SystemClock.elapsedRealtime(),
                    new HostMessageDispatch.Completion() {
                        public void done(boolean delivered) {
                            HotBridge.log("[DEKK][通话] 兜底发送结果: " + delivered);
                        }
                    });
        } catch (Throwable t) {
            HotBridge.log("[DEKK][通话] 兜底发送失败: " + t);
        }
    }

    // ── 呼出 / 来电 / 接通 / 挂断 ────────────────────────────

    /** 用户主动拨打（相机入口或设置页测试按钮）。 */
    static void startOutgoing(Activity activity) {
        if (!VoiceCallStore.enabled(HotApp.currentApplication())) {
            toast(activity, "语音通话未开启");
            return;
        }
        if (state != IDLE) return;
        Object owner = MusicSend.owner();
        if (owner == null) {
            toast(activity, "请先进入一个会话");
            return;
        }
        try {
            session = (String) HotHelpers.callMethod(owner, "e");
        } catch (Throwable t) {
            toast(activity, "读取会话失败");
            return;
        }
        if (session == null || session.length() == 0) {
            toast(activity, "请先进入一个会话");
            return;
        }
        opening = "";
        state = OUTGOING;
        VoiceCallUi ui = VoiceCallUi.show(activity, VoiceCallUi.OUTGOING, "DeepSeek",
                LISTENER);
        if (ui == null) {
            state = IDLE;
            return;
        }
        MAIN.postDelayed(new Runnable() {
            public void run() {
                if (state == OUTGOING) connect();
            }
        }, 1800);
    }

    /** AI 主动来电。 */
    private static void ringIn(Activity activity, String sid, String openingText) {
        if (state != IDLE) return;
        session = sid;
        opening = openingText == null ? "" : openingText;
        state = INCOMING;
        VoiceCallUi ui = VoiceCallUi.show(activity, VoiceCallUi.INCOMING, "DeepSeek", LISTENER);
        if (ui == null) {
            state = IDLE;
            return;
        }
        HotBridge.log("[DEKK][通话] 收到来电邀请: " + opening);
    }

    private static void connect() {
        state = ACTIVE;
        connectedAt = SystemClock.elapsedRealtime();
        // 通话前的那条 AI 消息不念，只念接通之后新生成的
        spokenId = Math.max(lastAssistantId, 0);
        VoiceCallTts.begin();
        VoiceCallUi ui = VoiceCallUi.current();
        if (ui != null) {
            ui.setMode(VoiceCallUi.ACTIVE);
            if (opening.length() > 0) ui.setStatus(opening);
        }
        HotBridge.log("[DEKK][通话] 已接通，会话 " + session);
    }

    private static void hangup(boolean user) {
        VoiceCallKey.cancel();
        VoiceCallTts.end();
        spokenId = -1;
        if (connectedAt > 0) {
            callSeconds = (SystemClock.elapsedRealtime() - connectedAt) / 1000L;
        }
        state = IDLE;
        connectedAt = 0;
        VoiceCallUi.dismiss();
        HotBridge.log("[DEKK][通话] 已挂断，时长 " + callSeconds + "s");
        Activity activity = DekkSettingsPage.currentActivity();
        if (activity != null && callSeconds > 0) {
            toast(activity, "通话结束 " + duration(callSeconds));
        }
        callSeconds = 0;
    }

    static String duration(long seconds) {
        return String.format("%02d:%02d", Long.valueOf(seconds / 60), Long.valueOf(seconds % 60));
    }

    // ── 界面回调 ────────────────────────────────────────────

    private static final VoiceCallUi.Listener LISTENER = new VoiceCallUi.Listener() {
        public void onAccept() {
            if (state == INCOMING) connect();
        }

        public void onHangup() {
            hangup(true);
        }

        public void onMic(boolean on) {
            final Activity activity = DekkSettingsPage.currentActivity();
            if (activity == null) return;
            if (on) {
                // 用户开口 → 先掐掉正在朗读的那条
                VoiceCallTts.interrupt(lastAssistantId);
                VoiceCallUi ui = VoiceCallUi.current();
                if (ui != null) ui.setStatus("正在打开麦克风…");
                VoiceCallKey.press(activity, new VoiceCallKey.Callback() {
                    public void onResult(boolean recording) {
                        VoiceCallUi current = VoiceCallUi.current();
                        if (current == null) {
                            VoiceCallKey.release();
                            return;
                        }
                        if (recording) {
                            current.setMic(true);
                            current.setStatus("麦克风已开，说完点一下麦克风");
                        } else {
                            current.setMic(false);
                            current.fail("没摸到语音键，先自己按住说一句话再试");
                        }
                    }
                });
            } else {
                VoiceCallKey.release();
                markMicClosed();
                VoiceCallUi ui = VoiceCallUi.current();
                if (ui != null) ui.setStatus("正在识别…");
            }
        }
    };

    /** 请求组装时追加的隐藏块：通话中说明状态，平时教模型怎么主动来电。 */
    static String promptBlock(android.content.Context context) {
        if (context == null || !VoiceCallStore.enabled(context)) return "";
        if (state == ACTIVE) return VoiceCallProtocol.modePrompt();
        if (!inviteLogged) {
            inviteLogged = true;
            HotBridge.log("[DEKK][通话] 通话提示词已开始自动携带");
        }
        return VoiceCallProtocol.invitePrompt();
    }

    private static boolean inviteLogged;

    private static int inviteTick;

    /** 相机入口被点击时用：确认后发起通话。 */
    static void requestFromUi(final Activity activity) {
        startOutgoing(activity);
    }

    private static void toast(Activity activity, String text) {
        try {
            Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) { }
    }
}
