package com.dekk.yanyu;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

/** 头像/封面加载：小内存缓存 + 圆形裁剪，只用 HttpURLConnection。 */
final class MusicAvatar {
    private static final int MAX_CACHE=24;
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final Map<String,Bitmap> CACHE=new LinkedHashMap<String,Bitmap>() {
        protected boolean removeEldestEntry(Map.Entry<String,Bitmap> eldest){
            return size()>MAX_CACHE;
        }
    };

    private static final Map<ImageView,String> pending=java.util.Collections.synchronizedMap(new java.util.WeakHashMap<ImageView,String>());
    private static final Map<String,Long> failures=java.util.Collections.synchronizedMap(new java.util.HashMap<String,Long>());
    private MusicAvatar() { }

    interface Callback { void onBitmap(Bitmap bitmap); }

    static void load(final ImageView view,final String url,final boolean circle,final int sizePx){
        if(view==null)return;
        if(url==null||url.length()==0){
            pending.remove(view);
            view.setTag(null);
            view.setImageDrawable(null);
            return;
        }
        view.setTag(url);
        Bitmap cached; synchronized(CACHE){cached=CACHE.get(url+(circle?"#c":""));}
        if(cached!=null){
            view.setImageBitmap(cached);
            return;
        }
        if(url.equals(pending.get(view)))return;
        Long failed=failures.get(url);if(failed!=null&&System.currentTimeMillis()-failed<30000)return;
        pending.put(view,url);
        view.setImageDrawable(null);
        final Context context=view.getContext();
        Thread worker=new Thread(new Runnable(){
            public void run(){
                Bitmap bitmap=null;
                try{
                    HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();
TlsDebug.apply(connection);
                    connection.setConnectTimeout(8000);
                    connection.setReadTimeout(12000);
                    connection.setRequestProperty("User-Agent","Mozilla/5.0");
                    InputStream stream=connection.getInputStream();
                    try{
                        BitmapFactory.Options options=new BitmapFactory.Options();
                        options.inSampleSize=2;
                        bitmap=BitmapFactory.decodeStream(stream,null,options);
                    }finally{
                        try{stream.close();}catch(Throwable ignored){ }
                    }
                    connection.disconnect();
                }catch(Throwable ignored){ }
                final Bitmap result=circle&&bitmap!=null?circle(bitmap):bitmap;
                if(result!=null){synchronized(CACHE){CACHE.put(url+(circle?"#c":""),result);}failures.remove(url);}else failures.put(url,System.currentTimeMillis());
                MAIN.post(new Runnable(){
                    public void run(){
                        if(url.equals(pending.get(view)))pending.remove(view);
                        if(result!=null&&url.equals(view.getTag()))view.setImageBitmap(result);
                    }
                });
            }
        },"dekk-music-image");
        worker.setDaemon(true);
        worker.start();
    }

    /** 方形缩放成正方形再按圆角裁剪。 */
    static Bitmap circle(Bitmap source){
        int size=Math.min(source.getWidth(),source.getHeight());
        Bitmap output=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(output);
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Rect rect=new Rect(0,0,size,size);
        RectF rounded=new RectF(rect);
        canvas.drawARGB(0,0,0,0);
        paint.setColor(0xFFFFFFFF);
        canvas.drawRoundRect(rounded,size/2f,size/2f,paint);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        Rect source_rect=new Rect((source.getWidth()-size)/2,(source.getHeight()-size)/2,
                (source.getWidth()+size)/2,(source.getHeight()+size)/2);
        canvas.drawBitmap(source,source_rect,rect,paint);
        source.recycle();
        return output;
    }
}