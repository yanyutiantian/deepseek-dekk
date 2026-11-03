package com.dekk.yanyu;

import android.app.Activity;
import android.widget.Toast;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** DeepSeek 2.5.1 drawer selection. Never invokes pin/delete actions. */
public final class DeepSeekSelectionHook {
    private static final ThreadLocal<Object> DRAWER = new ThreadLocal<Object>();
    private static final ThreadLocal<Boolean> BAR = new ThreadLocal<Boolean>();
    private DeepSeekSelectionHook() { }

    public static void install(final ClassLoader loader) {
        try {
            Class<?> drawer = HotHelpers.findClass("va1", loader);
            Class<?> bar = HotHelpers.findClass("zc", loader);
            final Class<?> function = HotHelpers.findClass("hv3", loader);
            HotBridge.hookAllMethods(drawer, "l", new HotHook() {
                protected void beforeHookedMethod(HotParam p) {
                    p.setObjectExtra("previous", DRAWER.get());
                    if (HotHelpers.getIntField(p.thisObject, "a") == 0) DRAWER.set(p.thisObject);
                }
                protected void afterHookedMethod(HotParam p) {
                    Object previous = p.getObjectExtra("previous");
                    if (previous == null) DRAWER.remove(); else DRAWER.set(previous);
                }
            });
            HotHelpers.findAndHookMethod(bar, "p", boolean.class, boolean.class, boolean.class,
                    function, function, function, HotHelpers.findClass("c76", loader),
                    HotHelpers.findClass("ey3", loader), int.class, new HotHook() {
                protected void beforeHookedMethod(HotParam p) {
                    p.setObjectExtra("previous", BAR.get());
                    if (!DekkSettingsPage.isSelectAll99Enabled()) return;
                    final Object owner = DRAWER.get();
                    if (owner == null) return;
                    final Object vm = HotHelpers.getObjectField(owner, "c");
                    final Object selected = HotHelpers.getObjectField(owner, "d");
                    if (!(selected instanceof Collection)) return;
                    p.args[3] = Proxy.newProxyInstance(loader, new Class<?>[]{function}, new InvocationHandler() {
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            String name = method.getName();
                            if ("hashCode".equals(name)) return Integer.valueOf(System.identityHashCode(proxy));
                            if ("equals".equals(name)) return Boolean.valueOf(args != null && proxy == args[0]);
                            if ("toString".equals(name)) return "DEKK select 99";
                            if ("v".equals(name)) {
                                if (DekkSettingsPage.isSelectAll99Enabled()) select(vm, selected);
                                return HotHelpers.getStaticObjectField(HotHelpers.findClass("lp9", loader), "a");
                            }
                            return null;
                        }
                    });
                    p.args[0] = Boolean.TRUE; // allow selection from an initially empty set
                    p.args[8] = Integer.valueOf(0); // changed callback and enabled state must be recomposed
                    BAR.set(Boolean.TRUE);
                }
                protected void afterHookedMethod(HotParam p) {
                    Object previous = p.getObjectExtra("previous");
                    if (previous == null) BAR.remove(); else BAR.set((Boolean) previous);
                }
            });
            HotBridge.hookAllMethods(HotHelpers.findClass("z89", loader), "b", new HotHook() {
                protected void beforeHookedMethod(HotParam p) {
                    if (!Boolean.TRUE.equals(BAR.get()) || p.args.length == 0) return;
                    Object text = p.args[0];
                    if ("置顶".equals(text) || "取消置顶".equals(text) || "Pin".equals(text) || "Unpin".equals(text)) p.args[0] = "全选";
                }
            });
            HotBridge.log("[DEKK] 历史聊天全选 Hook 已安装：zc.p / va1.d");
        } catch (Throwable t) {
            HotBridge.log("[DEKK] 全选 Hook 安装失败");
            HotBridge.log(t);
        }
    }

    @SuppressWarnings("unchecked")
    private static void select(Object vm, Object state) {
        Collection<Object> selected = (Collection<Object>) state;
        List<Object> before = new ArrayList<Object>(selected);
        boolean changed = false;
        try {
            Object source = HotHelpers.callMethod(vm, "l");
            if (!(source instanceof Iterable)) throw new IllegalStateException("会话列表类型不匹配");
            List<String> ordered = new ArrayList<String>();
            for (Object row : (Iterable<?>) source) {
                Object id = HotHelpers.getObjectField(row, "a");
                if (!(id instanceof String)) throw new IllegalStateException("会话 ID 类型不匹配");
                ordered.add((String) id);
            }
            if (ordered.isEmpty()) { message("当前没有已加载的会话"); return; }
            List<String> target = Selection99.choose(ordered, before);
            changed = true;
            selected.clear();
            selected.addAll(target);
            if (selected.size() != target.size() || !selected.containsAll(target)) throw new IllegalStateException("勾选集合写回校验失败");
            HotBridge.log("[DEKK] 全选成功：已加载=" + ordered.size() + "，原选中=" + before.size() + "，现选中=" + selected.size());
            message("已选中 " + selected.size() + " 条" + (target.size() < 99 ? "（当前已加载会话不足 99 条）" : ""));
        } catch (Throwable t) {
            if (changed) {
                try { selected.clear(); selected.addAll(before); } catch (Throwable restore) { HotBridge.log(restore); }
            }
            HotBridge.log(t);
            message("全选失败，请查看 DEKK 日志；未执行置顶或删除");
        }
    }
    private static void message(String text) {
        Activity activity = DekkSettingsPage.currentActivity();
        if (activity != null) Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
    }
}
