package com.dekk.yanyu;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** ilke 虚拟转账协议的 Java 7 实现；仅识别独立协议段。 */
final class VirtualTransferProtocol {
    private static final String PREFIX = "[转账&";
    private static final Pattern TOKEN = Pattern.compile("\\[转账&([0-9]{1,12}(?:\\.[0-9]{1,2})?)\\]");
    private static final Pattern EVENT = Pattern.compile("\\[ilke-transfer:([0-9a-f-]{36})\\]");
    static String amount(String text) {
        if (text == null || indentedCode(text)) return null;
        Matcher match = TOKEN.matcher(text.trim());
        if (!match.matches()) return null;
        BigDecimal value = new BigDecimal(match.group(1));
        return value.signum() > 0 ? value.setScale(2).toPlainString() : null;
    }
    static boolean incomplete(String text) {
        if (text == null || indentedCode(text)) return false;
        String value = text.trim();
        if (value.length() == 0) return false;
        return PREFIX.startsWith(value) || (value.startsWith(PREFIX)
                && value.length() <= PREFIX.length() + 15
                && value.substring(PREFIX.length()).matches("[0-9]{0,12}(?:\\.[0-9]{0,2})?"));
    }
    static String eventToken(String text) {
        if (text == null) return null;
        Matcher match = EVENT.matcher(text);
        return match.find() ? match.group(1) : null;
    }
    private static boolean indentedCode(String text) {
        int i = 0;
        while (i < text.length() && text.charAt(i) <= ' ') i++;
        int start = text.lastIndexOf('\n', Math.max(0, i - 1)) + 1;
        return i - start >= 4 || text.substring(start, i).indexOf('\t') >= 0;
    }
    static final class Id {
        final String session, amount, key;
        final int message, fragment, segment;
        Id(String session, int message, int fragment, int segment, String amount) {
            if (session == null || session.length() == 0 || message <= 0 || fragment < 0
                    || segment < 0 || amount == null || !amount.equals(amount(PREFIX + amount + "]")))
                throw new IllegalArgumentException("Invalid virtual transfer identity");
            this.session = session; this.message = message; this.fragment = fragment;
            this.segment = segment; this.amount = amount;
            this.key = digest(session.length() + ":" + session + ":" + message + ":"
                    + fragment + ":" + segment + ":" + amount);
        }
    }
    private static String digest(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(Charset.forName("UTF-8")));
            char[] hex = "0123456789abcdef".toCharArray();
            StringBuilder result = new StringBuilder(64);
            for (byte b : bytes) { result.append(hex[(b & 255) >>> 4]); result.append(hex[b & 15]); }
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private VirtualTransferProtocol() { }
}
