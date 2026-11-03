package com.dekk.yanyu;


/**
 * DeepSeek targets SDK 36 without allowing cleartext, so HttpURLConnection refuses
 * http://127.0.0.1 . This hook lets plain HTTP through for the loopback host only.
 * Internet hosts keep the original policy.
 */
final class AdbCleartextHook {
    private static boolean installed;

    private AdbCleartextHook() { }

    static void install() {
        if (installed) return;
        try {
            Class<?> policy = Class.forName("android.security.NetworkSecurityPolicy");
            HotHelpers.findAndHookMethod(policy, "isCleartextTrafficPermitted", String.class,
                    new HotHook() {
                        @Override
                        protected void beforeHookedMethod(HotParam param) {
                            Object value = param.args.length > 0 ? param.args[0] : null;
                            if (isLoopback(value)) param.setResult(Boolean.TRUE);
                        }
                    });
            installed = true;
            HotBridge.log("[DEKK][ADB] 本地明文放行已安装（仅 127.0.0.1 / localhost / ::1）");
        } catch (Throwable t) {
            HotBridge.log("[DEKK][ADB] 本地明文放行安装失败：" + t.getClass().getSimpleName());
        }
    }

    private static boolean isLoopback(Object value) {
        if (!(value instanceof String)) return false;
        String host = (String) value;
        return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host);
    }
}