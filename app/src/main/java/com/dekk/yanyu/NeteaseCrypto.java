package com.dekk.yanyu;

import android.util.Base64;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.Random;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 网易云 weapi 加密：文本做两层 AES-128-CBC，随机密钥用 RSA 加密成 encSecKey。
 * 全部用 JDK/Android 自带实现，不引入第三方库。
 */
final class NeteaseCrypto {
    private static final String IV="0102030405060708";
    private static final String PRESET_KEY="0CoJUm6Qyw8W8jud";
    private static final String RANDOM_POOL="abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String MODULUS="00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7"
            +"b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c9"
            +"3870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef5"
            +"2741d546b8e289dc6935b3ece0462db0a22b8e7";
    private static final String EXPONENT="010001";
    private static final Random RANDOM=new Random();

    private NeteaseCrypto() { }

    static String md5(String text){
        try{
            MessageDigest digest=MessageDigest.getInstance("MD5");
            byte[] bytes=digest.digest(text.getBytes("UTF-8"));
            StringBuilder hex=new StringBuilder(bytes.length*2);
            for(byte value:bytes){
                String part=Integer.toHexString(value&0xFF);
                if(part.length()==1)hex.append('0');
                hex.append(part);
            }
            return hex.toString();
        }catch(Throwable t){return "";}
    }

    static String randomSecretKey(){
        StringBuilder key=new StringBuilder(16);
        for(int i=0;i<16;i++)key.append(RANDOM_POOL.charAt(RANDOM.nextInt(RANDOM_POOL.length())));
        return key.toString();
    }

    private static String aes(String key,String text) throws Exception{
        Cipher cipher=Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key.getBytes("UTF-8"),"AES"),
                new IvParameterSpec(IV.getBytes("UTF-8")));
        return Base64.encodeToString(cipher.doFinal(text.getBytes("UTF-8")),Base64.NO_WRAP);
    }

    /** encSecKey = RSA(反序后的随机密钥)，固定模数与指数 010001。 */
    static String encSecKey(String secretKey) throws Exception{
        String reversed=new StringBuilder(secretKey).reverse().toString();
        BigInteger message=new BigInteger(1,reversed.getBytes("UTF-8"));
        BigInteger value=message.modPow(new BigInteger(EXPONENT,16),new BigInteger(MODULUS,16));
        StringBuilder hex=new StringBuilder(value.toString(16));
        while(hex.length()<256)hex.insert(0,'0');
        return hex.toString();
    }

    /** 一次 weapi 组装：params + encSecKey，用于表单提交。 */
    static String weapi(String json,boolean usePresetKeyOnly) throws Exception{
        String secretKey=usePresetKeyOnly?PRESET_KEY:randomSecretKey();
        String first=aes(PRESET_KEY,json);
        String params=aes(secretKey,first);
        return "params="+NeteaseHttp.encode(params)+"&encSecKey="+encSecKey(secretKey);
    }

    /** 便于自检：用固定随机密钥复现一次，返回 params 与 encSecKey。 */
    static String[] weapiWithKey(String json,String secretKey) throws Exception{
        return new String[]{aes(secretKey,aes(PRESET_KEY,json)),encSecKey(secretKey)};
    }

    /** 组装 weapi 表单（可带 csrf_token）。 */
    static java.util.Map<String,String> weapiForm(String json,String csrf) throws Exception{
        org.json.JSONObject object=new org.json.JSONObject(json);
        if(csrf!=null&&csrf.length()>0&&!object.has("csrf_token"))object.put("csrf_token",csrf);
        return pairs(weapi(object.toString(),false));
    }

    /** 把 "params=..&encSecKey=.." 还原成表单键值。 */
    static java.util.Map<String,String> pairs(String body){
        java.util.Map<String,String> values=new java.util.LinkedHashMap<String,String>();
        if(body==null)return values;
        for(String part:body.split("&")){
            int cut=part.indexOf('=');
            if(cut<=0)continue;
            String value=part.substring(cut+1);
            try{value=java.net.URLDecoder.decode(value,"UTF-8");}catch(Throwable ignored){ }
            values.put(part.substring(0,cut),value);
        }
        return values;
    }
}