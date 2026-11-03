package com.dekk.yanyu;

import android.content.Context;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodHook.MethodHookParam;
import de.robv.android.xposed.XC_MethodHook.Unhook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import com.dekk.yanyu.RecallSessionCache.Entry;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

public final class AntiRecallHook {
    private static final Pattern TEMPLATE = Pattern.compile("\\\"type\\\"\\s*:\\s*\\\"TEMPLATE_RESPONSE\\\"");
    private static final RecallSessionCache cache = new RecallSessionCache();
    private static boolean installed;
    private static final Map<Object, String> owners = Collections.synchronizedMap(new WeakHashMap());
    private static final Map<Object, Object> pending = Collections.synchronizedMap(new WeakHashMap());
    private static boolean reported;

    public static synchronized void install(ClassLoader classLoader) {
        ClassLoader classLoader2 = classLoader;
        synchronized (AntiRecallHook.class) {
            if (installed) {
                return;
            }
            ArrayList<Unhook> arrayList = new ArrayList();
            try {
                Class findClass = XposedHelpers.findClass("px", classLoader2);
                Class findClass2 = XposedHelpers.findClass("vq", classLoader2);
                Class findClass3 = XposedHelpers.findClass("wr", classLoader2);
                String str = "h";
                Object[] objArr = new Object[5];
                try {
                    int i;
                    objArr[0] = Class.forName("java.lang.String");
                    objArr[1] = XposedHelpers.findClass("ev4", classLoader2);
                    objArr[2] = XposedHelpers.findClass("e11", classLoader2);
                    int i2 = 3;
                    objArr[3] = XposedHelpers.findClass("x42", classLoader2);
                    int i3 = 4;
                    objArr[4] = new XC_MethodHook() {
                        protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                            String str = "INCOMPLETE";
                            if (AntiRecallHook.active()) {
                                try {
                                    if (AntiRecallHook.blockedPatch((String) methodHookParam.args[0], methodHookParam.args[1]) && AntiRecallHook.hasContent(methodHookParam.thisObject)) {
                                        AntiRecallHook.capture(methodHookParam.thisObject);
                                        XposedHelpers.callMethod(methodHookParam.thisObject, "X", new Object[]{str});
                                        XposedHelpers.callMethod(methodHookParam.thisObject, "W", new Object[]{str});
                                        Object obj = null;
                                        methodHookParam.setResult(null);
                                    }
                                } catch (Throwable th) {
                                    AdbAiHook.error(th);
                                }
                            }
                        }
                    };
                    arrayList.add(XposedHelpers.findAndHookMethod(findClass, str, objArr));
                    String[] strArr = new String[]{"px", "nx"};
                    for (i = 0; i < 2; i++) {
                        String str2 = strArr[i];
                        String[] strArr2 = new String[]{"W", "X"};
                        int i4 = 0;
                        while (i4 < 2) {
                            String str3 = strArr2[i4];
                            Class findClass4 = XposedHelpers.findClass(str2, classLoader2);
                            Object[] objArr2 = new Object[2];
                            try {
                                objArr2[0] = Class.forName("java.lang.String");
                                objArr2[1] = new XC_MethodHook() {
                                    protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                                        if (!AntiRecallHook.active()) {
                                            return;
                                        }
                                        if (AntiRecallHook.filterStatus(methodHookParam.args[0])) {
                                            try {
                                                if (AntiRecallHook.hasContent(methodHookParam.thisObject)) {
                                                    AntiRecallHook.capture(methodHookParam.thisObject);
                                                    methodHookParam.args[0] = "INCOMPLETE";
                                                }
                                            } catch (Throwable th) {
                                                AdbAiHook.error(th);
                                            }
                                        }
                                    }
                                };
                                arrayList.add(XposedHelpers.findAndHookMethod(findClass4, str3, objArr2));
                                i4++;
                                i2 = 3;
                                i3 = 4;
                            } catch (ClassNotFoundException e) {
                                throw new RuntimeException(e.getMessage());
                            }
                        }
                    }
                    Method[] declaredMethods = findClass3.getDeclaredMethods();
                    i = 0;
                    for (Method method : declaredMethods) {
                        String name = method.getName();
                        Class[] parameterTypes = method.getParameterTypes();
                        if ((name.equals("a") || name.equals("c") || name.equals("u") || name.equals("v")) && parameterTypes.length > 0 && parameterTypes[0] == findClass2) {
                            arrayList.add(XposedBridge.hookMethod(method, new XC_MethodHook() {
                                protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                                    if (AntiRecallHook.active()) {
                                        try {
                                            methodHookParam.args[0] = AntiRecallHook.preserve(methodHookParam.thisObject, methodHookParam.args[0]);
                                        } catch (Throwable th) {
                                            AdbAiHook.error(th);
                                        }
                                    }
                                }
                            }));
                            i++;
                        }
                    }
                    if (i == i3) {
                        String str4 = "z";
                        Object[] objArr3 = new Object[i2];
                        objArr3[0] = findClass3;
                        try {
                            objArr3[1] = Class.forName("java.util.List");
                            objArr3[2] = new XC_MethodHook() {
                                protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                                    if (AntiRecallHook.active()) {
                                        if (methodHookParam.args[1] != null) {
                                            try {
                                                List list = (List) methodHookParam.args[1];
                                                ArrayList anonymousClass100000000 = new ArrayList(list.size());
                                                int i = 0;
                                                for (Object next : list) {
                                                    Object obj = next;
                                                    obj = AntiRecallHook.preserve(methodHookParam.args[0], next);
                                                    anonymousClass100000000.add(obj);
                                                    i |= obj == next ? 0 : 1;
                                                }
                                                if (i != 0) {
                                                    methodHookParam.args[1] = anonymousClass100000000;
                                                }
                                            } catch (Throwable th) {
                                                AdbAiHook.error(th);
                                            }
                                        }
                                    }
                                }
                            };
                            arrayList.add(XposedHelpers.findAndHookMethod(findClass3, str4, objArr3));
                            installed = true;
                            AdbBridge.log("[DEKK][防撤回] 2.5.1 流式补丁与消息合并 Hook 已安装（install-flow-v2）");
                            return;
                        } catch (ClassNotFoundException e2) {
                            throw new RuntimeException(e2.getMessage());
                        }
                    }
                    throw new RuntimeException("Unexpected message apply signatures");
                } catch (ClassNotFoundException e22) {
                    throw new RuntimeException(e22.getMessage());
                }
            } catch (Throwable th) {
                installed = false;
                for (Unhook unhook : arrayList) {
                    unhook.unhook();
                }
                AdbBridge.log("[DEKK][防撤回] Hook 安装失败，已回滚: " + th);
                AdbAiHook.error(th);
            }
        }
    }

    private static Object preserve(Object obj, Object obj2) {
        Object obj3 = null;
        if (obj2 == null) {
            obj = null;
            return null;
        }
        bind(obj, obj2);
        Object objectField = XposedHelpers.getObjectField(obj, "f");
        Object callMethod = XposedHelpers.callMethod(obj2, "x", new Object[0]);
        if (objectField instanceof Map) {
            obj3 = ((Map) objectField).get(callMethod);
        } else {
            objectField = null;
        }
        if (obj3 != null) {
            bind(obj, obj3);
        }
        if (!filtered(obj2)) {
            return obj2;
        }
        String str = "a";
        if (obj3 == null || obj3 == obj2 || filtered(obj3) || !hasContent(obj3)) {
            String sessionKey = (String) XposedHelpers.getObjectField(obj, str);
            int messageId = ((Number) callMethod).intValue();
            double messageTime = ((Number) XposedHelpers.callMethod(obj2, "u", new Object[0])).doubleValue();
            Entry entry = cache.get(sessionKey, messageId, messageTime);
            if (entry == null) {
                // 过滤/撤回更新会把时间戳改掉，严格匹配会漏；同 id 再找一次（仅此一处补充）
                entry = cache.getIgnoreTime(sessionKey, messageId);
                if (entry != null) {
                    AdbBridge.log("[DEKK][防撤回] 时间戳变了，按消息 id 命中缓存 #" + messageId);
                }
            }
            if (entry == null) {
                AdbBridge.log("[DEKK][防撤回] 存档里没有 #" + messageId + "（会话=" + sessionKey + "）");
                return obj2;
            }
            obj3 = entry.snapshot;
        }
        capture(obj3);
        obj = XposedHelpers.callMethod(obj3, str, new Object[0]);
        Object[] objArr = new Object[1];
        objArr[0] = "INCOMPLETE";
        XposedHelpers.callMethod(obj, "X", objArr);
        XposedHelpers.callMethod(obj, "W", new Object[]{"INCOMPLETE"});
        return obj;
    }

    private static void save(String str, Object obj) {
        if ("ASSISTANT".equals(XposedHelpers.getObjectField(obj, "i"))) {
            StringBuilder stringBuilder = new StringBuilder();
            for (Object next : (List) XposedHelpers.getObjectField(obj, "v")) {
                Object obj2 = next;
                if (next != null) {
                    if ("tt8".equals(next.getClass().getName())) {
                        stringBuilder.append((String) XposedHelpers.getObjectField(next, "c"));
                    }
                }
            }
            cache.put(str, ((Number) XposedHelpers.callMethod(obj, "x", new Object[0])).intValue(), new Entry(obj, stringBuilder.toString(), ((Number) XposedHelpers.callMethod(obj, "u", new Object[0])).doubleValue()));
        }
    }

    private static void bind(Object obj, Object obj2) {
        if (obj2 != null) {
            String str = (String) XposedHelpers.getObjectField(obj, "a");
            if (str != null) {
                if (str.length() != 0) {
                    owners.put(obj2, str);
                    obj2 = pending.remove(obj2);
                    if (obj2 != null) {
                        save(str, obj2);
                    }
                }
            }
        }
    }

    private static void capture(Object obj) {
        try {
            Object callMethod = XposedHelpers.callMethod(obj, "a", new Object[0]);
            String str = (String) owners.get(obj);
            if (str != null) {
                save(str, callMethod);
            } else {
                Map map = pending;
                synchronized (map) {
                    if (map.size() >= 64) {
                        map.clear();
                    }
                    map.put(obj, callMethod);
                }
            }
        } catch (Throwable th) {
            AdbAiHook.error(th);
        }
    }

    private static boolean hasContent(Object obj) {
        obj = XposedHelpers.getObjectField(XposedHelpers.callMethod(obj, "a", new Object[0]), "v");
        if (obj instanceof List) {
            return !((List) obj).isEmpty();
        } else {
            return false;
        }
    }

    private static boolean active() {
        return enabled(android.app.AndroidAppHelper.currentApplication());
    }

    static boolean blockedPatch(String str, Object obj) {
        return (("status".equals(str) || "quasi_status".equals(str)) && filterStatus(obj)) || ("fragments".equals(str) && TEMPLATE.matcher(String.valueOf(obj)).find());
    }

    public static boolean enabled(Context context) {
        if (context == null) {
            return false;
        }
        android.content.SharedPreferences prefs = context.getSharedPreferences("dekk_prefs", 0);
        if (!prefs.contains("anti_recall")) {
            // 首次装好默认开一次（数据被系统清空后会再开一次）；在设置里手动关掉后就不会再被打开
            prefs.edit().putBoolean("anti_recall", true).apply();
            AdbBridge.log("[DEKK][防撤回] 首次运行，已默认开启（设置里可关）");
            return true;
        }
        return prefs.getBoolean("anti_recall", false);
    }

    private static void error(Throwable th) {
        if (!reported) {
            reported = true;
            AdbBridge.log(new StringBuffer().append("[DEKK][防撤回] 适配异常，放行原操作: ").append(th.getClass().getSimpleName()).toString());
        }
    }

    static boolean filterStatus(Object obj) {
        String valueOf = String.valueOf(obj);
        return "CONTENT_FILTER".equals(valueOf) || "\"CONTENT_FILTER\"".equals(valueOf);
    }

    private static boolean filtered(Object obj) {
        return filterStatus(XposedHelpers.callMethod(obj, "G", new Object[0])) || filterStatus(XposedHelpers.callMethod(obj, "A", new Object[0]));
    }

    public static void setEnabled(Context context, boolean z) {
        context.getSharedPreferences("dekk_prefs", 0).edit().putBoolean("anti_recall", z).apply();
        if (!z) {
            cache.clear();
            owners.clear();
            pending.clear();
        }
    }
}