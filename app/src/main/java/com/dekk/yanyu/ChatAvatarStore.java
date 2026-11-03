package com.dekk.yanyu;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.util.AtomicFile;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;

/**
 * 聊天头像：用户 / AI 各一张，存在宿主 App 的 filesDir/dekk/ 下，
 * 保存前统一缩放为 ≤512 的方形位图并做圆形裁剪，绘制时直接用缓存。
 *
 * <p>移植自 ilke 的头像功能（OPEN_DOCUMENT 选图 + AtomicFile 落盘 + 内存缓存）。
 */
final class ChatAvatarStore {
    static final int REQUEST_USER=0x4454;
    static final int REQUEST_AI=0x4455;

    private static final String DIR="dekk";
    private static final int MAX_PX=512;
    private static final WeakReference<Bitmap>[] CACHE=new WeakReference[2];
    private static final boolean[] IMPORTING=new boolean[2];

    private ChatAvatarStore() { }

    private static int index(boolean user){return user?1:0;}

    /** 头像解码完成后需要让聊天页重新测量一次，否则"进会话时还没解码好"的那一侧永远补不上头像。 */
    private static volatile boolean notified;

    /** 提前把两张头像解码进缓存，避免进入会话时布局取不到图。 */
    static void preload(){
        final Context context=HotApp.currentApplication();
        if(context==null||!enabled(context))return;
        new Thread(new Runnable(){
            public void run(){
                try{
                    bitmap(context,true);
                    bitmap(context,false);
                }catch(Throwable t){
                    log("头像预热失败: "+t.getClass().getSimpleName());
                }
            }
        },"dekk-avatar-preload").start();
    }

    /** 让当前页面重新测量一次（头像位需要重新占位）。 */
    private static void requestChatLayout(){
        if(notified)return;
        notified=true;
        final android.app.Activity activity=DekkSettingsPage.currentActivity();
        if(activity==null)return;
        activity.runOnUiThread(new Runnable(){
            public void run(){
                try{
                    activity.getWindow().getDecorView().requestLayout();
                }catch(Throwable ignored){ }
            }
        });
    }

    private static void log(String message){
        HotBridge.log("[DEKK][头像] "+message);
    }

    static boolean enabled(Context context){
        return context!=null&&BubbleStyle.prefs().getBoolean("chat_avatars",false);
    }

    static void setEnabled(Context context,boolean value){
        if(context==null)return;
        BubbleStyle.prefs().edit().putBoolean("chat_avatars",value).apply();
    }

    static File file(Context context,boolean user){
        if(context==null)return null;
        File dir=new File(context.getFilesDir(),DIR);
        if(!dir.isDirectory()&&!dir.mkdirs()&&!dir.isDirectory())return null;
        return new File(dir,user?"avatar_user.png":"avatar_ai.png");
    }

    static boolean has(Context context,boolean user){
        if(context==null)return false;
        File target=file(context,user);
        return target!=null&&target.isFile();
    }

    /** 取圆形头像位图；没有或未开启头像功能返回 null。 */
    static Bitmap bitmap(Context context,boolean user){
        if(context==null||!enabled(context))return null;
        int i=index(user);
        WeakReference<Bitmap> reference=CACHE[i];
        Bitmap cached=reference==null?null:reference.get();
        if(cached!=null&&!cached.isRecycled())return cached;
        File target=file(context,user);
        if(target==null||!target.isFile())return null;
        try{
            Bitmap raw=BitmapFactory.decodeFile(target.getAbsolutePath());
            if(raw==null)return null;
            Bitmap round=circle(raw);
            if(round!=raw)raw.recycle();
            CACHE[i]=new WeakReference<Bitmap>(round);
            requestChatLayout();
            return round;
        }catch(Throwable t){
            log("头像读取失败: "+t.getClass().getSimpleName());
            return null;
        }
    }

    static void pick(Activity activity,boolean user){
        if(activity==null)return;
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        try{
            activity.startActivityForResult(intent,user?REQUEST_USER:REQUEST_AI);
        }catch(Throwable t){
            Toast.makeText(activity,"未找到图片选择器",Toast.LENGTH_SHORT).show();
        }
    }

    /** 宿主 Activity 的 onActivityResult 转发到这里；返回 true 表示这个结果由我们消费。 */
    static boolean handleResult(final Activity activity,int requestCode,int resultCode,Intent data){
        if(requestCode!=REQUEST_USER&&requestCode!=REQUEST_AI)return false;
        final boolean user=requestCode==REQUEST_USER;
        if(resultCode!=Activity.RESULT_OK||data==null||data.getData()==null)return true;
        final int i=index(user);
        if(IMPORTING[i]){
            Toast.makeText(activity,"图片正在导入",Toast.LENGTH_SHORT).show();
            return true;
        }
        IMPORTING[i]=true;
        Toast.makeText(activity,"正在导入头像…",Toast.LENGTH_SHORT).show();
        final Uri uri=data.getData();
        new Thread(new Runnable(){
            public void run(){
                final boolean ok=save(activity,user,uri);
                activity.runOnUiThread(new Runnable(){
                    public void run(){
                        IMPORTING[i]=false;
                        Toast.makeText(activity,ok?"头像已保存，重新进入会话生效":"头像导入失败，请换一张图片",
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        },"dekk-avatar-import").start();
        return true;
    }

    /** 解码 → 缩放到 ≤512 → 圆形裁剪后写入本地。 */
    static boolean save(Context context,boolean user,Uri uri){
        InputStream in=null;
        try{
            BitmapFactory.Options options=new BitmapFactory.Options();
            options.inJustDecodeBounds=true;
            in=context.getContentResolver().openInputStream(uri);
            if(in==null)return false;
            BitmapFactory.decodeStream(in,new Rect(),options);
            in.close();
            in=null;
            int max=Math.max(options.outWidth,options.outHeight);
            options.inSampleSize=1;
            while(max/options.inSampleSize>MAX_PX*2)options.inSampleSize*=2;
            options.inJustDecodeBounds=false;
            in=context.getContentResolver().openInputStream(uri);
            Bitmap decoded=BitmapFactory.decodeStream(in,new Rect(),options);
            if(in!=null)in.close();
            if(decoded==null)return false;
            Bitmap round=circle(decoded);
            if(round!=decoded)decoded.recycle();
            File target=file(context,user);
            if(target==null)return false;
            AtomicFile atomic=new AtomicFile(target);
            FileOutputStream out=null;
            try{
                out=atomic.startWrite();
                if(!round.compress(Bitmap.CompressFormat.PNG,100,out))return false;
                atomic.finishWrite(out);
            }catch(Throwable t){
                if(out!=null)atomic.failWrite(out);
                return false;
            }
            CACHE[index(user)]=new WeakReference<Bitmap>(round);
            notified=false;
            requestChatLayout();
            log("头像已保存（"+(user?"用户":"AI")+"）");
            return true;
        }catch(Throwable t){
            log("头像导入失败: "+t);
            return false;
        }finally{
            try{if(in!=null)in.close();}catch(Throwable ignored){ }
        }
    }

    static void clear(Context context,boolean user){
        try{
            File target=file(context,user);
            if(target!=null&&target.isFile())new AtomicFile(target).delete();
        }catch(Throwable ignored){ }
        CACHE[index(user)]=null;
        notified=false;
        requestChatLayout();
    }

    /** 居中正方裁剪 + 圆形（带抗锯齿）。 */
    private static Bitmap circle(Bitmap source){
        int side=Math.min(source.getWidth(),source.getHeight());
        if(side<=0)return source;
        int left=(source.getWidth()-side)/2,top=(source.getHeight()-side)/2;
        Bitmap output=Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(output);
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        float radius=side/2f;
        canvas.drawCircle(radius,radius,radius,paint);
        paint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(source,new Rect(left,top,left+side,top+side),new RectF(0,0,side,side),paint);
        paint.setXfermode(null);
        return output;
    }
}