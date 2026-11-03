package com.dekk.yanyu;

import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
/** 用户中心头像的显示与压缩：圆形裁剪 + 上传前压缩到最长边 512 的 JPEG。 */
final class AvatarUi {
    static final int REQUEST=0x4456;
    private static final int MAX_PX=512;
    private static final int QUALITY=86;
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static WeakReference<Bitmap> cache=new WeakReference<Bitmap>(null);
    private static String cachedUrl="";
    private static android.graphics.drawable.Drawable cachedDefault;

    private AvatarUi() { }

    /**
     * 模块内置默认头像。
     *
     * <p>模块代码是跑在宿主进程里的，直接用 {@code R.drawable.avatar_default} 会在宿主的
     * Resources 里找（找不到就空白），所以必须用模块自己的包上下文去取。
     */
    static android.graphics.drawable.Drawable defaultDrawable(Context host){
        if(cachedDefault!=null)return cachedDefault;
        if(host==null)return null;
        String pkg=BuildConfig.APPLICATION_ID;
        try{
            Context module=host.createPackageContext(pkg,Context.CONTEXT_IGNORE_SECURITY);
            int id=module.getResources().getIdentifier("avatar_default","drawable",pkg);
            if(id!=0)cachedDefault=module.getResources().getDrawable(id);
            if(cachedDefault==null)HotBridge.log("[DEKK][头像] 默认头像资源未找到: "+pkg);
        }catch(Throwable t){
            HotBridge.log("[DEKK][头像] 默认头像读取失败: "+t.getClass().getSimpleName());
        }
        return cachedDefault;
    }


    /** 圆形头像（居中正方裁剪）。 */
    static Bitmap circle(Bitmap source){
        if(source==null)return null;
        int side=Math.min(source.getWidth(),source.getHeight());
        if(side<=0)return source;
        Bitmap output=Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(output);
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        float radius=side/2f;
        canvas.drawCircle(radius,radius,radius,paint);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        int left=(source.getWidth()-side)/2,top=(source.getHeight()-side)/2;
        canvas.drawBitmap(source,new Rect(left,top,left+side,top+side),new RectF(0,0,side,side),paint);
        paint.setXfermode(null);
        return output;
    }

    /** 先显示模块内置默认头像，再异步拉取远程头像（带内存缓存）。 */
    static void show(final ImageView view,final String url){
        if(view==null)return;
        android.graphics.drawable.Drawable fallback=defaultDrawable(view.getContext());
        if(fallback!=null)view.setImageDrawable(fallback);
        if(url==null||url.length()==0)return;
        Bitmap memory=cache.get();
        if(url.equals(cachedUrl)&&memory!=null&&!memory.isRecycled()){view.setImageBitmap(memory);return;}
        new Thread(new Runnable(){
            public void run(){
                Bitmap bitmap=null;
                try{
                    java.net.HttpURLConnection connection=(java.net.HttpURLConnection)new java.net.URL(url).openConnection();
TlsDebug.apply(connection);
                    connection.setConnectTimeout(8000);connection.setReadTimeout(12000);
                    InputStream in=connection.getInputStream();
                    try{
                        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                        byte[] buffer=new byte[8192];int read,total=0;
                        while((read=in.read(buffer))!=-1){
                            total+=read;
                            if(total>2*1024*1024)throw new Exception("too large");
                            bytes.write(buffer,0,read);
                        }
                        bitmap=BitmapFactory.decodeByteArray(bytes.toByteArray(),0,bytes.size());
                    }finally{in.close();}
                }catch(Throwable t){
                    HotBridge.log("[DEKK][用户中心] 头像下载失败: "+t.getClass().getSimpleName());
                }
                final Bitmap decoded=bitmap==null?null:circle(bitmap);
                if(decoded==null)return;
                cache=new WeakReference<Bitmap>(decoded);
                cachedUrl=url;
                MAIN.post(new Runnable(){public void run(){view.setImageBitmap(decoded);}});
            }
        },"dekk-profile-avatar").start();
    }

    /** 选图后先压缩（最长边 512、JPEG 86），返回可直接上传的字节。 */
    static byte[] compress(Context context,Uri uri)throws Exception{
        BitmapFactory.Options bounds=new BitmapFactory.Options();
        bounds.inJustDecodeBounds=true;
        InputStream probe=context.getContentResolver().openInputStream(uri);
        if(probe==null)throw new Exception("open");
        try{BitmapFactory.decodeStream(probe,new Rect(),bounds);}finally{probe.close();}
        if(bounds.outWidth<=0||bounds.outHeight<=0)throw new Exception("decode bounds");
        BitmapFactory.Options options=new BitmapFactory.Options();
        options.inSampleSize=1;
        int longest=Math.max(bounds.outWidth,bounds.outHeight);
        while(longest/options.inSampleSize>MAX_PX*2)options.inSampleSize*=2;
        options.inPreferredConfig=Bitmap.Config.ARGB_8888;
        InputStream in=context.getContentResolver().openInputStream(uri);
        if(in==null)throw new Exception("open");
        Bitmap decoded;
        try{decoded=BitmapFactory.decodeStream(in,new Rect(),options);}finally{in.close();}
        if(decoded==null)throw new Exception("decode");
        int width=decoded.getWidth(),height=decoded.getHeight();
        int max=Math.max(width,height);
        if(max>MAX_PX){
            float scale=(float)MAX_PX/max;
            Bitmap scaled=Bitmap.createScaledBitmap(decoded,Math.max(1,Math.round(width*scale)),
                    Math.max(1,Math.round(height*scale)),true);
            if(scaled!=decoded)decoded.recycle();
            decoded=scaled;
        }
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        if(!decoded.compress(Bitmap.CompressFormat.JPEG,QUALITY,out)){decoded.recycle();throw new Exception("compress");}
        int sourceSize=decoded.getByteCount();
        decoded.recycle();
        HotBridge.log("[DEKK][用户中心] 头像已压缩：原图 "+width+"x"+height
                +"（"+sourceSize+" 字节）→ 上传 "+out.size()+" 字节");
        return out.toByteArray();
    }

    /** 本地已选头像的预览（保存成功后立刻生效）。 */
    static void remember(Bitmap bitmap){
        if(bitmap==null)return;
        cache=new WeakReference<Bitmap>(bitmap);
        cachedUrl="";
    }
}