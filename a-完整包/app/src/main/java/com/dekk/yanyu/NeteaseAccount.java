package com.dekk.yanyu;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/**
 * 网易云账号本地存储：整份 JSON（CK + 昵称 + 头像 + userId）用 AndroidKeyStore 的
 * AES/GCM 加密后再落盘；KeyStore 不可用时退化为明文存储并记 log。
 */
final class NeteaseAccount {
    private static final String PREF="dekk_music";
    private static final String KEY_BLOB="account_blob";
    private static final String KEY_PLAIN="account_plain";
    private static final String KEY_ALIAS="dekk_netease_account_v1";
    private static final String TRANSFORM="AES/GCM/NoPadding";
    private static final int IV_LENGTH=12;
    private static final int TAG_BITS=128;

    private NeteaseAccount() { }

    static final class Info {
        String cookie="";
        String name="";
        String avatar="";
        long userId;
        long savedAt;
        boolean loggedIn(){return NeteaseCookie.valid(cookie);}
    }

    private static SharedPreferences prefs(Context context){
        return context.getSharedPreferences(PREF,0);
    }

    static Info load(Context context){
        Info info=new Info();
        if(context==null)return info;
        String text=null;
        String blob=prefs(context).getString(KEY_BLOB,null);
        if(blob!=null&&blob.length()>0){
            text=decrypt(blob);
            if(text==null){if(Log.once())Log.line("账号密文无法解密（可能换机或清过密钥），已忽略");}
        }
        if(text==null)text=prefs(context).getString(KEY_PLAIN,null);
        if(text==null||text.length()==0)return info;
        try{
            JSONObject json=new JSONObject(text);
            info.cookie=json.optString("cookie","");
            info.name=json.optString("name","");
            info.avatar=json.optString("avatar","");
            info.userId=json.optLong("userId",0L);
            info.savedAt=json.optLong("savedAt",0L);
        }catch(Throwable t){Log.line("账号读取失败: "+t.getClass().getSimpleName());}
        return info;
    }

    static void save(Context context,Info info){
        if(context==null||info==null)return;
        String text=toJson(info);
        String blob=encrypt(text);
        SharedPreferences.Editor editor=prefs(context).edit();
        if(blob!=null){
            editor.putString(KEY_BLOB,blob).remove(KEY_PLAIN);
            if(Log.once())Log.line("CK 已用 AndroidKeyStore 加密保存");
        }else{
            editor.putString(KEY_PLAIN,text);
            if(Log.once())Log.line("AndroidKeyStore 不可用，CK 明文保存（仅本机）");
        }
        editor.apply();
    }

    static void clear(Context context){
        if(context==null)return;
        prefs(context).edit().remove(KEY_BLOB).remove(KEY_PLAIN).apply();
    }

    static boolean loggedIn(Context context){return load(context).loggedIn();}
    static String cookie(Context context){return load(context).cookie;}
    static String name(Context context){return load(context).name;}
    static String avatar(Context context){return load(context).avatar;}

    private static String toJson(Info info){
        try{
            JSONObject json=new JSONObject();
            json.put("cookie",info.cookie);
            json.put("name",info.name);
            json.put("avatar",info.avatar);
            json.put("userId",info.userId);
            json.put("savedAt",System.currentTimeMillis());
            return json.toString();
        }catch(Throwable t){return "{}";}
    }

    private static SecretKey key() throws Exception{
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if(store.containsAlias(KEY_ALIAS))return (SecretKey)store.getKey(KEY_ALIAS,null);
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private static String encrypt(String text){
        try{
            Cipher cipher=Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE,key());
            byte[] iv=cipher.getIV();
            byte[] data=cipher.doFinal(text.getBytes("UTF-8"));
            byte[] joined=new byte[iv.length+data.length];
            System.arraycopy(iv,0,joined,0,iv.length);
            System.arraycopy(data,0,joined,iv.length,data.length);
            return Base64.encodeToString(joined,Base64.NO_WRAP);
        }catch(Throwable t){
            Log.line("加密失败: "+t.getClass().getSimpleName());
            return null;
        }
    }

    private static String decrypt(String blob){
        try{
            byte[] joined=Base64.decode(blob,Base64.NO_WRAP);
            if(joined.length<=IV_LENGTH)return null;
            Cipher cipher=Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(TAG_BITS,joined,0,IV_LENGTH));
            byte[] data=cipher.doFinal(joined,IV_LENGTH,joined.length-IV_LENGTH);
            return new String(data,"UTF-8");
        }catch(Throwable t){
            return null;
        }
    }

    /** 日志节流，避免每次读取都刷屏。 */
    static final class Log {
        private static boolean printed;
        static boolean once(){if(printed)return false;printed=true;return true;}
        static void line(String message){HotBridge.log("[DEKK][一起听] "+message);}
    }
}