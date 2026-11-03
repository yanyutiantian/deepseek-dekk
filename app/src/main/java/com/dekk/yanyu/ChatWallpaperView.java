package com.dekk.yanyu;
import android.app.Activity;
import android.graphics.Bitmap;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import java.lang.ref.WeakReference;

/** Activity-bottom image layer; never intercepts touch events. */
final class ChatWallpaperView {
    private static final String TAG="dekk.chat.wallpaper";
    private static WeakReference<Activity> current=new WeakReference<Activity>(null);
    private static Bitmap image;
    static void attach(Activity a){current=new WeakReference<Activity>(a);apply(a);}
    static void image(Bitmap bitmap){image=bitmap;final Activity a=current.get();if(a!=null)a.runOnUiThread(new Runnable(){public void run(){apply(a);}});}
    static boolean ready(){Activity a=current.get();return image!=null&&a!=null&&a.findViewById(android.R.id.content)!=null;}
    private static void apply(Activity a){
        if(a.isFinishing()||a.isDestroyed())return;
        View root=a.findViewById(android.R.id.content);if(!(root instanceof ViewGroup))return;
        ViewGroup group=(ViewGroup)root;ImageView view=(ImageView)group.findViewWithTag(TAG);
        if(image==null){if(view!=null)group.removeView(view);return;}
        if(view==null){view=new ImageView(a);view.setTag(TAG);view.setClickable(false);view.setFocusable(false);view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);view.setScaleType(ImageView.ScaleType.CENTER_CROP);group.addView(view,0,new ViewGroup.LayoutParams(-1,-1));}
        view.setImageBitmap(image);
    }
}