package com.dekk.yanyu;

import android.app.*;
import android.content.*;
import android.webkit.*;
import android.widget.*;
import android.view.*;
import android.net.Uri;

/** 官网 OAuth 页面处理二维码与人机验证，不模拟或绕过验证码。 */
final class QqMusicLogin {
    static void cookie(final Activity a){
        final EditText input=new EditText(a);input.setHint("粘贴 QQ 音乐 Cookie");input.setInputType(0x81);
        final AlertDialog dialog=new ModuleDialog(a).setTitle("QQ 音乐 CK 登录").setView(input).setNegativeButton("取消",null).setPositiveButton("验证并保存",null).create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener(){public void onShow(DialogInterface ignored){dialog.getButton(-1).setOnClickListener(new View.OnClickListener(){public void onClick(View v){save(a,input.getText().toString().trim(),dialog);}});}});dialog.show();ModuleDialog.style(dialog);
    }
    static void save(final Activity a,final String cookie,final AlertDialog dialog){
        if(!QqMusicApi.valid(cookie)){Toast.makeText(a,"CK 缺少 uin 或音乐密钥",1).show();return;}
        new Thread(new Runnable(){public void run(){String error=null;try{
            QqMusicAccount.Info info=QqMusicProfile.fetch(cookie);QqMusicAccount.save(a,info);
        }catch(Exception e){error="登录未通过验证："+e.getMessage();}
        final String message=error;a.runOnUiThread(new Runnable(){public void run(){if(a.isFinishing())return;Toast.makeText(a,message==null?"QQ音乐登录成功":message,1).show();if(message==null)dialog.dismiss();}});
        }},"dekk-qq-login").start();
    }
    static void web(final Activity a,boolean wechat){
        final WebView web=new WebView(a);web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);
        web.getSettings().setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        web.getSettings().setUseWideViewPort(true);web.getSettings().setLoadWithOverviewMode(true);
        CookieManager.getInstance().setAcceptCookie(true);CookieManager.getInstance().setAcceptThirdPartyCookies(web,true);
        web.setWebViewClient(new WebViewClient(){public boolean shouldOverrideUrlLoading(WebView v,String url){Uri u=Uri.parse(url);String h=u.getHost();return !"https".equals(u.getScheme())||h==null||!(h.equals("qq.com")||h.endsWith(".qq.com"));}});
        final AlertDialog dialog=new ModuleDialog(a).setTitle(wechat?"微信扫码登录":"QQ扫码登录").setView(web).setNegativeButton("取消",null).setPositiveButton("已完成登录 / 验证",null).create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener(){public void onShow(DialogInterface d){dialog.getButton(-1).setOnClickListener(new View.OnClickListener(){public void onClick(View v){String c=CookieManager.getInstance().getCookie("https://y.qq.com/");save(a,c==null?"":c,dialog);}});}});
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener(){public void onDismiss(DialogInterface d){web.stopLoading();web.destroy();}});
        dialog.show();ModuleDialog.style(dialog);web.setLayoutParams(new android.widget.FrameLayout.LayoutParams(-1,(int)(a.getResources().getDisplayMetrics().heightPixels*0.65f)));
        String home="https://y.qq.com/portal/player.html";
        String redirect="https://y.qq.com/portal/wx_redirect.html?login_type="+(wechat?"2":"1")+"&surl="+Uri.encode(home);
        String url=wechat?"https://open.weixin.qq.com/connect/qrconnect?appid=wx48db31d50e334801&redirect_uri="+Uri.encode(redirect)+"&response_type=code&scope=snsapi_login&state=STATE#wechat_redirect"
            :"https://graph.qq.com/oauth2.0/authorize?response_type=code&client_id=YOUR_QQ_APPID&redirect_uri="+Uri.encode(redirect+"&use_customer_cb=0")+"&state=state&display=pc";
        web.loadUrl(url);
        Toast.makeText(a,"扫码授权；如出现人机验证，请在此页面手动完成",1).show();
    }
}