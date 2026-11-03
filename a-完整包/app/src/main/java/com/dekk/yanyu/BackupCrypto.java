package com.dekk.yanyu;

import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 聊天记录备份的本地加密层。
 *
 * 设计：服务端只保存密文，看不到明文，也永远拿不到授权码本身（服务端只存授权码的 SHA-256）。
 * blob 结构（全部是大端，base64 无换行输出）：
 *   MAGIC 'DKBK'(4) | version(1) | saltLen(1) | salt(16) | ivWrap(16) | wrappedLen(2) | wrapped(48) | ivBody(16) | body
 * wrapped = AES-CBC(keyWrap, dataKey)，keyWrap = PBKDF2-HMAC-SHA256(授权码, salt)
 * body    = AES-CBC(dataKey, 明文 JSON)
 *
 * 这样即使拿到 blob，没有授权码也无法解出 dataKey，进而无法解出明文。
 */
final class BackupCrypto {
    private static final byte[] MAGIC = new byte[]{'D', 'K', 'B', 'K'};
    private static final int VERSION = 1;
    private static final int SALT_LEN = 16;
    private static final int IV_LEN = 16;
    private static final int KEY_LEN = 32;
    private static final int WRAPPED_LEN = KEY_LEN + 16;   // dataKey + PKCS5 填充
    private static final int PBKDF2_ITER = 10000;
    /** 授权码字符集：去掉 0/O/1/I，降低抄错概率。 */
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_CHARS = 20;
    private static final int CODE_GROUP = 5;

    /** 明文（JSON 字节）加密为可上传的 blob 字符串。 */
    static String seal(byte[] plain, String code) throws Exception {
        String normalized = normalizeCode(code);
        if (normalized == null || normalized.length() == 0) throw new IllegalArgumentException("授权码为空");
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[SALT_LEN];
        byte[] ivWrap = new byte[IV_LEN];
        byte[] ivBody = new byte[IV_LEN];
        byte[] dataKey = new byte[KEY_LEN];
        random.nextBytes(salt);
        random.nextBytes(ivWrap);
        random.nextBytes(ivBody);
        random.nextBytes(dataKey);
        byte[] keyWrap = pbkdf2(normalized, salt);
        byte[] wrapped = aes(Cipher.ENCRYPT_MODE, keyWrap, ivWrap, dataKey);
        byte[] body = aes(Cipher.ENCRYPT_MODE, dataKey, ivBody, plain);
        ByteArrayOutputStream out = new ByteArrayOutputStream(plain.length + 128);
        out.write(MAGIC);
        out.write(VERSION);
        out.write(SALT_LEN);
        out.write(salt, 0, salt.length);
        out.write(ivWrap, 0, ivWrap.length);
        out.write((wrapped.length >>> 8) & 0xFF);
        out.write(wrapped.length & 0xFF);
        out.write(wrapped, 0, wrapped.length);
        out.write(ivBody, 0, ivBody.length);
        out.write(body, 0, body.length);
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    /** blob 字符串 + 授权码还原明文；授权码错误会抛 BAD_CODE。 */
    static byte[] open(String blob, String code) throws Exception {
        String normalized = normalizeCode(code);
        if (normalized == null || normalized.length() == 0) throw new BackupCryptoException("授权码为空");
        byte[] raw;
        try {
            raw = Base64.decode(blob, Base64.DEFAULT);
        } catch (Throwable t) {
            throw new BackupCryptoException("备份内容损坏");
        }
        int p = 0;
        if (raw.length < 4 + 1 + 1 + SALT_LEN + IV_LEN + 2 + WRAPPED_LEN + IV_LEN) {
            throw new BackupCryptoException("备份内容不完整");
        }
        for (int i = 0; i < 4; i++) {
            if (raw[p++] != MAGIC[i]) throw new BackupCryptoException("不是本模块的备份文件");
        }
        int version = raw[p++] & 0xFF;
        if (version != VERSION) throw new BackupCryptoException("备份版本不支持：" + version);
        int saltLen = raw[p++] & 0xFF;
        if (saltLen != SALT_LEN) throw new BackupCryptoException("备份内容损坏");
        byte[] salt = slice(raw, p, saltLen); p += saltLen;
        byte[] ivWrap = slice(raw, p, IV_LEN); p += IV_LEN;
        int wrappedLen = ((raw[p] & 0xFF) << 8) | (raw[p + 1] & 0xFF); p += 2;
        if (wrappedLen != WRAPPED_LEN) throw new BackupCryptoException("备份内容损坏");
        byte[] wrapped = slice(raw, p, wrappedLen); p += wrappedLen;
        byte[] ivBody = slice(raw, p, IV_LEN); p += IV_LEN;
        byte[] body = slice(raw, p, raw.length - p);

        byte[] keyWrap = pbkdf2(normalized, salt);
        byte[] dataKey;
        try {
            dataKey = aes(Cipher.DECRYPT_MODE, keyWrap, ivWrap, wrapped);
        } catch (Throwable t) {
            throw new BackupCryptoException("授权码错误");
        }
        try {
            return aes(Cipher.DECRYPT_MODE, dataKey, ivBody, body);
        } catch (Throwable t) {
            throw new BackupCryptoException("授权码错误或备份已损坏");
        }
    }

    /** 服务端存的 code_hash；忽略分隔符与大小写，方便用户抄写。 */
    static String codeHash(String code) throws Exception {
        String normalized = normalizeCode(code);
        if (normalized == null) throw new BackupCryptoException("授权码为空");
        return sha256Hex(normalized.getBytes("UTF-8"));
    }

    /** 服务端存的 blob_hash：对 blob 字符串本身取 SHA-256（与 PHP hash('sha256',$blob) 一致）。 */
    static String blobHash(String blob) throws Exception {
        return sha256Hex(blob.getBytes("UTF-8"));
    }

    /** 生成一段授权码，形如 ABCDE-FGHJK-LMNPQ-RSTUV。 */
    static String newCode() {
        SecureRandom random = new SecureRandom();
        StringBuilder builder = new StringBuilder(CODE_CHARS + CODE_CHARS / CODE_GROUP);
        for (int i = 0; i < CODE_CHARS; i++) {
            if (i > 0 && i % CODE_GROUP == 0) builder.append('-');
            builder.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return builder.toString();
    }

    /** 去掉分隔符、转大写。 */
    static String normalizeCode(String code) {
        if (code == null) return null;
        return code.replace("-", "").replace(" ", "").trim().toUpperCase();
    }

    private static byte[] slice(byte[] source, int from, int length) {
        byte[] out = new byte[length];
        System.arraycopy(source, from, out, 0, length);
        return out;
    }

    private static byte[] aes(int mode, byte[] key, byte[] iv, byte[] data) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(data);
    }

    /**
     * PBKDF2-HMAC-SHA256。minSdk 21 没有 "PBKDF2WithHmacSHA256"，所以用 Mac 自己实现。
     * 输出长度固定 32 字节（一次 PRF 长度），对本用途足够。
     */
    private static byte[] pbkdf2(String password, byte[] salt) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(password.getBytes("UTF-8"), "HmacSHA256"));
        byte[] block = new byte[salt.length + 4];
        System.arraycopy(salt, 0, block, 0, salt.length);
        block[salt.length + 3] = 1;               // 大端 block index = 1
        byte[] prf = mac.doFinal(block);
        byte[] out = new byte[KEY_LEN];
        System.arraycopy(prf, 0, out, 0, Math.min(KEY_LEN, prf.length));
        for (int i = 1; i < PBKDF2_ITER; i++) {
            prf = mac.doFinal(prf);
            for (int j = 0; j < prf.length && j < KEY_LEN; j++) out[j] ^= prf[j];
        }
        return out;
    }

    static String sha256Hex(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data);
        StringBuilder builder = new StringBuilder(hash.length * 2);
        for (int i = 0; i < hash.length; i++) {
            int value = hash[i] & 0xFF;
            if (value < 16) builder.append('0');
            builder.append(Integer.toHexString(value));
        }
        return builder.toString();
    }

    static final class BackupCryptoException extends Exception {
        BackupCryptoException(String message) { super(message); }
    }

    private BackupCrypto() { }
}