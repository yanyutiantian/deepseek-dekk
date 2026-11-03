package com.dekk.yanyu;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 壁纸选图与导入。
 *
 * <p>以前用 {@code android.app.Fragment} 收结果，但宿主 Activity 一旦覆写
 * {@code onActivityResult} 又不调用 super，结果就永远收不到（"某些用户用不了壁纸"的原因之一）。
 * 现在改成：Activity 直接 startActivityForResult，结果由 {@link ChatAvatarHook} 统一转发回来。
 */
final class BackgroundImageImport {
    static final int REQUEST=7001;

    private BackgroundImageImport() { }

    static void pick(Activity activity){
        if(activity==null)return;
        // 修补框架可能在 Activity 创建后加载模块，选图前补挂实际 Activity。
        ChatAvatarHook.hookOverride(activity.getClass());
        String[] actions=android.os.Build.VERSION.SDK_INT>=33
                ?new String[]{"android.provider.action.PICK_IMAGES",Intent.ACTION_OPEN_DOCUMENT,Intent.ACTION_GET_CONTENT}
                :new String[]{Intent.ACTION_OPEN_DOCUMENT,Intent.ACTION_GET_CONTENT};
        for(String action:actions){
            Intent intent=new Intent(action).setType("image/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if(!"android.provider.action.PICK_IMAGES".equals(action))intent.addCategory(Intent.CATEGORY_OPENABLE);
            try{activity.startActivityForResult(intent,REQUEST);return;}
            catch(android.content.ActivityNotFoundException ignored){}
            catch(SecurityException ignored){}
        }
        Toast.makeText(activity,"无法打开系统图片选择器",Toast.LENGTH_LONG).show();
    }

    /** 宿主 onActivityResult 转发到这里；返回 true 表示这个结果由我们消费。 */
    static boolean handleResult(final Activity activity,int request,int result,Intent data){
        if(request!=REQUEST)return false;
        if(result!=Activity.RESULT_OK||data==null||data.getData()==null)return true;
        final Uri uri=data.getData();
        if(!"content".equals(uri.getScheme())){
            Toast.makeText(activity,"请选择系统图片提供器中的图片",Toast.LENGTH_LONG).show();
            return true;
        }
        new Thread(new Runnable(){
            public void run(){
                File temp=null,output=null;
                Bitmap bitmap=null;
                String error=null;
                try{
                    temp=File.createTempFile("dekk-image-",".tmp",activity.getCacheDir());
                    InputStream in=activity.getContentResolver().openInputStream(uri);
                    if(in==null)throw new IOException("open");
                    try{
                        FileOutputStream out=new FileOutputStream(temp);
                        try{
                            byte[] buffer=new byte[8192];
                            int read,total=0;
                            while((read=in.read(buffer))!=-1){
                                total+=read;
                                if(total>8*1024*1024)throw new IOException("size");
                                out.write(buffer,0,read);
                            }
                        }finally{out.close();}
                    }finally{in.close();}
                    BitmapFactory.Options options=new BitmapFactory.Options();
                    options.inJustDecodeBounds=true;
                    BitmapFactory.decodeFile(temp.getPath(),options);
                    if(options.outWidth<=0||options.outHeight<=0
                            ||(long)options.outWidth*options.outHeight>100000000L)throw new IOException("dimensions");
                    options.inJustDecodeBounds=false;
                    options.inSampleSize=1;
                    options.inPreferredConfig=Bitmap.Config.RGB_565;
                    while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>2048)options.inSampleSize*=2;
                    bitmap=BitmapFactory.decodeFile(temp.getPath(),options);
                    if(bitmap==null)throw new IOException("decode");
                    // 落到宿主应用专属目录 .../files/dekk-wallpaper.jpg，无需外部存储权限
                    output=WallpaperFile.write(activity,bitmap);
                    BeautifyStore.setPath(activity,output.getAbsolutePath());
                    output=null;   // 交给 WallpaperFile 管理，finally 里不要删
                }catch(Exception e){
                    HotBridge.log("[DEKK][背景] 导入失败: "+e);
                    error="导入失败：请选择有效图片（最大 8MB）";
                }catch(OutOfMemoryError e){
                    error="图片过大，内存不足";
                }finally{
                    if(bitmap!=null)bitmap.recycle();
                    if(temp!=null)temp.delete();
                    if(output!=null)output.delete();
                }
                final String message=error;
                activity.runOnUiThread(new Runnable(){
                    public void run(){
                        if(activity.isFinishing()||activity.isDestroyed())return;
                        Toast.makeText(activity,message==null?"壁纸已保存":message,Toast.LENGTH_LONG).show();
                        if(message==null){
                            ChatBackgroundHook.reload(activity);
                            BeautifyPage.show(activity);
                        }
                    }
                });
            }
        },"DEKK-background-import").start();
        return true;
    }
}