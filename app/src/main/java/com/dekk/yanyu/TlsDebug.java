package com.dekk.yanyu;

import java.net.HttpURLConnection;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 抓包调试用的 TLS 开关。
 *
 * <p>由 {@code MainActivity.INSECURE_TLS} 控制：
 * <ul>
 * <li>{@code true} —— 关闭 HTTPS 证书校验（信任所有证书 + 不做域名匹配），黄鸟/Charles 才能中间人解密；</li>
 * <li>{@code false} —— 恢复系统证书校验（发版必须是 false）。</li>
 * </ul>
 *
 * <p>关闭校验只影响本模块自己发起的请求，不影响宿主。
 */
final class TlsDebug {

    private static SSLSocketFactory factory;
    private static boolean logged;

    private TlsDebug() { }

    /** 按开关给连接套上「信任所有证书」的工厂；开关关闭时什么都不做。 */
    static void apply(HttpURLConnection connection) {
        if (!enabled() || !(connection instanceof HttpsURLConnection)) {
            return;
        }
        try {
            HttpsURLConnection https = (HttpsURLConnection) connection;
            https.setSSLSocketFactory(factory());
            https.setHostnameVerifier(new HostnameVerifier() {
                public boolean verify(String host, SSLSession session) {
                    return true;
                }
            });
            if (!logged) {
                logged = true;
                HotBridge.log("[DEKK][调试] HTTPS 证书校验已关闭（INSECURE_TLS=true），仅供抓包使用");
            }
        } catch (Throwable t) {
            HotBridge.log("[DEKK][调试] 关闭证书校验失败: " + t.getClass().getSimpleName());
        }
    }

    /** 读 MainActivity.INSECURE_TLS：用反射取值，避免 TlsDebug 与界面类耦合。 */
    static boolean enabled() {
        try {
            java.lang.reflect.Field field = Class.forName("com.dekk.yanyu.MainActivity")
                    .getField("INSECURE_TLS");
            return field.getBoolean(null);
        } catch (Throwable ignored) {
            return false;   // 读不到就按安全默认（启用证书校验）
        }
    }

    private static synchronized SSLSocketFactory factory() throws Exception {
        if (factory == null) {
            TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType)
                        throws CertificateException { }
                public void checkServerTrusted(X509Certificate[] chain, String authType)
                        throws CertificateException { }
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustAll, new java.security.SecureRandom());
            factory = context.getSocketFactory();
        }
        return factory;
    }
}