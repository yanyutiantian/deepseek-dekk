package com.dekk.yanyu;

import android.app.*;
import android.content.*;
import android.view.*;
import android.widget.*;

/** 赞助入口、收款码与可永久关闭的提示。 */
final class SponsorPage {
    private static final String PREF="dekk_sponsor";
    static View entry(final Activity a,boolean night,int color){
        LinearLayout card=MiuixUi.card(a,night);
        MiuixUi.row(a,card,night,MiuixIcon.HEART,"赞助开发者","开发者与技术支持",true,new View.OnClickListener(){
            public void onClick(View v){show(a);}
        });
        return card;
    }

    static void show(final Activity a){
        final CenterPage page=new CenterPage(a,"赞助",null).navigationKey("sponsor");
        LinearLayout card=page.card();
        option(page,card,"开发者","https://dekk.example.com/effect/yxy.png");
        option(page,card,"技术支持 A","https://dekk.example.com/effect/xiaoyu.jpg");
        option(page,card,"技术支持 B","https://dekk.example.com/effect/fq.jpg");
        page.show();
    }
    private static void option(final CenterPage page,LinearLayout card,final String title,final String url){
        page.rowIcon(card,MiuixIcon.HEART,title+" ›",new Runnable(){public void run(){
            final CenterPage code=new CenterPage(page.activity,title,null);
            LinearLayout box=code.card();final ImageView image=new ImageView(page.activity);
            image.setContentDescription(title+"赞助码");image.setAdjustViewBounds(true);image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            box.addView(image,new LinearLayout.LayoutParams(-1,code.dp(360)));
            final TextView status=code.label("正在加载赞助码…",13);
            status.setTextColor(code.secondary);status.setGravity(Gravity.CENTER);
            status.setPadding(0,code.dp(10),0,code.dp(4));box.addView(status);
            Motion.breath(status);
            TextView thanks=code.label("感谢老板打赏",18);
            thanks.setTypeface(MiuixUi.medium());thanks.setGravity(Gravity.CENTER);
            thanks.setPadding(0,code.dp(16),0,code.dp(16));box.addView(thanks);
            code.rowIcon(box,MiuixIcon.DOWNLOAD,"重新加载 ›",new Runnable(){public void run(){load(code,image,status,url);}});
            code.show();load(code,image,status,url);
        }});
    }
    private static void load(final CenterPage page,final ImageView image,final TextView status,final String url){
        status.setText("正在加载赞助码…");
        Motion.breath(status);
        new Thread(new Runnable(){public void run(){android.graphics.Bitmap result=null;java.net.HttpURLConnection c=null;
            try{c=(java.net.HttpURLConnection)new java.net.URL(url).openConnection();c.setConnectTimeout(10000);c.setReadTimeout(15000);TlsDebug.apply(c);
                java.io.InputStream in=c.getInputStream();try{result=android.graphics.BitmapFactory.decodeStream(in);}finally{in.close();}
            }catch(Exception ignored){}finally{if(c!=null)c.disconnect();}
            final android.graphics.Bitmap bitmap=result;page.activity.runOnUiThread(new Runnable(){public void run(){
                if(!page.active()){if(bitmap!=null)bitmap.recycle();return;}
                Motion.stopBreath(status);
                if(bitmap==null)status.setText("加载失败，请点重新加载");else{image.setImageBitmap(bitmap);status.setText("");}
            }});
        }},"dekk-sponsor-image").start();
    }
    static void prompt(final Activity a){
        if(a.isFinishing()||a.isDestroyed()||a.getSharedPreferences(PREF,0).getBoolean("disabled",false))return;
        new ModuleDialog(a).setTitle("支持模块开发").setMessage("是否愿意赞助开发者或技术支持？感谢你的支持。")
            .setNeutralButton("永久关闭",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){a.getSharedPreferences(PREF,0).edit().putBoolean("disabled",true).apply();}})
            .setNegativeButton("关闭",null)
            .setPositiveButton("赞助",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){show(a);}}).show();
    }
}