package com.dekk.yanyu;

import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

/**
 * API 请求 /响应加密（V2协议）。
 *
 * <p>方案：AES-256-CBC 加密正文，AES 密钥用 RSA 公钥加密，最外层再套一层 base64；
 * 同时用 HMAC-SHA256 做完整性校验，并带时间戳 + 随机数防重放。
 *
 * <p>新旧兼容：请求 URL 带 {@code ver=v2} 表示新协议；不带则服务端按 V1（明文）处理。
 * V1 会在服务端设定的截止时间（2026-10-08 12:00）后关闭。
 * 因此这里也保留了明文回退：服务端没返回加密信封时（例如灰度期间或报错）按明文解析。
 */
final class ApiCrypto {

    /** 客户端协议版本。 */
    static final String VERSION = "v2";
    /** 服务端 RSA 公钥（对应服务端私钥 dekk_rsa_private.pem）。 */
    private static final String PUBLIC_KEY_BASE64 =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAxSdKjVUftbGZ3+vOQKPbDiaBwUUtEl7AwyhnpdZxEMbGDKsG9oNPkJDjtdVcB1PFB6Hj7R63GQFjJkGuPTcHrf4fcp3nRyKr1kz/s76LH6eRp+ldEXygX20B3JlTTB0aSM/NKLNa9Wzp2ml2INsjbtzzCllwS9boCd/p7vnRSTKM4owqbY1VMq6cigj+4Ceg1rtF53n/W1q3AiptemzbViz0vUh7KXHL1ijnNWOih0q4cZi1wJ4hle6PiQW76ofWjwXIMAX9JhHbdozayYeX5GlOKXvqTJ9zVcIhK/NSfXPWhyDqsxD+meKMWFYb8nDjfpU7uSbMH1xRxY2bX/yiBQIDAQAB";

    private static final SecureRandom RANDOM = new SecureRandom();
    /** 本次请求的会话密钥；响应要用同一把密钥解开。 */
    private static final ThreadLocal<byte[]> SESSION = new ThreadLocal<byte[]>();

    private ApiCrypto() { }

    /** 把明文请求体打成信封，返回可直接发送的 base64 外壳。 */
    static String seal(String plaintext) throws Exception {
        byte[] master = new byte[32];
        RANDOM.nextBytes(master);
        SESSION.set(master);
        byte[] encKey = derive(master, "enc");
        byte[] macKey = derive(master, "mac");

        byte[] iv = new byte[16];
        RANDOM.nextBytes(iv);
        byte[] cipher = aes(Cipher.ENCRYPT_MODE, encKey, iv, utf8(plaintext));
        String mac = hmac(macKey, concat(iv, cipher));

        JSONObject envelope = new JSONObject();
        envelope.put("v", 2);
        envelope.put("ts", System.currentTimeMillis() / 1000L);
        envelope.put("nonce", randomHex(16));
        envelope.put("key", Base64.encodeToString(rsaEncryptMaster(master), Base64.NO_WRAP));
        envelope.put("iv", Base64.encodeToString(iv, Base64.NO_WRAP));
        envelope.put("data", Base64.encodeToString(cipher, Base64.NO_WRAP));
        envelope.put("mac", mac);
        // 最外层再用 base64 包一层（协议要求的外壳）
        return Base64.encodeToString(utf8(envelope.toString()), Base64.NO_WRAP);
    }

    /** 打好的信封需要放进 URL 时用（GET 请求参数很少，放得下）。 */
    static String sealForQuery(String plaintext) throws Exception {
        return URLEncoder.encode(seal(plaintext), "UTF-8");
    }

    /**
     * 解析响应：服务端返回加密信封就解开，返回明文（含错误信息）则原样返回，
     * 这样服务端先上线、客户端后上线的灰度顺序也能正常工作。
     */
    static String open(String responseBody) throws Exception {
        if (responseBody == null) {
            throw new Exception("empty response");
        }
        String trimmed = responseBody.trim();
        if (trimmed.length() == 0 || trimmed.charAt(0) != '{') {
            return responseBody;
        }
        JSONObject outer = new JSONObject(trimmed);
        if (!outer.has("data") || !outer.has("iv") || !outer.has("mac")) {
            return responseBody;   // 明文响应（V1 或服务端错误）
        }
        byte[] master = SESSION.get();
        if (master == null) {
            throw new Exception("no session key");
        }
        byte[] encKey = derive(master, "enc");
        byte[] macKey = derive(master, "mac");
        byte[] iv = Base64.decode(outer.getString("iv"), Base64.DEFAULT);
        byte[] cipher = Base64.decode(outer.getString("data"), Base64.DEFAULT);
        String expect = outer.getString("mac");
        if (!constantTimeEquals(expect, hmac(macKey, concat(iv, cipher)))) {
            throw new Exception("响应校验失败");
        }
        byte[] plain = aes(Cipher.DECRYPT_MODE, encKey, iv, cipher);
        return utf8(plain);
    }

    /** 用后清掉会话密钥（避免线程复用时串用）。 */
    static void clearSession() {
        SESSION.remove();
    }

    private static byte[] derive(byte[] master, String label) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(master);
        digest.update(utf8(label));
        return digest.digest();
    }

    private static byte[] aes(int mode, byte[] key, byte[] iv, byte[] data) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(data);
    }

    private static byte[] rsaEncryptMaster(byte[] master) throws Exception {
        byte[] keyBytes = Base64.decode(PUBLIC_KEY_BASE64, Base64.DEFAULT);
        java.security.PublicKey key = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(keyBytes));
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return cipher.doFinal(master);
    }

    private static String hmac(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return Base64.encodeToString(mac.doFinal(data), Base64.NO_WRAP);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    private static String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        StringBuilder builder = new StringBuilder(bytes * 2);
        for (int i = 0; i < value.length; i++) {
            builder.append(Character.forDigit((value[i] >> 4) & 0xF, 16))
                    .append(Character.forDigit(value[i] & 0xF, 16));
        }
        return builder.toString();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(a.length + b.length);
        out.write(a, 0, a.length);
        out.write(b, 0, b.length);
        return out.toByteArray();
    }

    private static byte[] utf8(String text) throws Exception {
        return text.getBytes("UTF-8");
    }

    private static String utf8(byte[] data) throws Exception {
        return new String(data, "UTF-8");
    }
}