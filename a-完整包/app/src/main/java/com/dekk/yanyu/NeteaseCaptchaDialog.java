package com.dekk.yanyu;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 网易云安全验证（滑块）弹窗。
 *
 * <p>真机实测：客户端身份的登录/发码会被回 <code>code=-462</code>，返回体里的
 * <code>data.url</code> 就是验证页（st.music.163.com/encrypt-pages?...）。
 * 只要服务端要验证就把页面显示给用户：
 * 1) 用 WebView 打开验证页（UA/Cookie 与请求身份一致）；
 * 2) 用户滑完后点「我已完成」——直接相信用户已完成，立刻执行原动作（登录 / 发验证码）；
 * 3) 结果以原请求的服务端响应为准，不做本地完成检测。
 */
final class NeteaseCaptchaDialog {
    /** 验证与重试使用同一网易云会话。 */
    private static final String ORIGIN="https://music.163.com";

    interface Retry {
        /** 用验证页当前的 Cookie 重试登录（在后台线程调用）。 */
        NeteaseLogin.Result attempt(String webCookie);
    }

    interface Done {
        /** 成功传登录结果；用户取消传 null。 */
        void done(NeteaseLogin.Result result);
    }

    private NeteaseCaptchaDialog() { }

    static void show(final Activity activity,String url,final Retry retry,final Done done){
        if(activity==null){
            if(done!=null)done.done(null);
            return;
        }
        final String target=url==null||url.length()==0?NeteaseLogin.defaultCaptchaUrl():url;
        final LinearLayout column=new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        int padding=dp(activity,12);
        column.setPadding(padding,padding,padding,padding);

        TextView hint=new TextView(activity);
        hint.setText("网易云要求安全验证（滑块）。请在下面的页面里把滑块滑完，"
                +"然后点「我已完成」继续（点一下就按已完成处理，直接继续下一步）。");
        hint.setTextSize(12);
        hint.setGravity(Gravity.START);
        hint.setPadding(0,0,0,dp(activity,8));
        column.addView(hint);

        // 验证进度提示
        final TextView status=new TextView(activity);
        status.setTextSize(11);
        status.setTextColor(0xFF8FB2FF);
        status.setGravity(Gravity.START);
        status.setPadding(0,0,0,dp(activity,6));
        status.setText("等你在页面上完成滑动，完成后点「我已完成」");
        column.addView(status);

        final WebView web=new WebView(activity);
        try{
            WebSettings settings=web.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setDatabaseEnabled(true);
            settings.setUseWideViewPort(true);
            settings.setLoadWithOverviewMode(true);
            settings.setBuiltInZoomControls(false);
            settings.setUserAgentString(NeteaseHttp.userAgent());
            try{settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);}catch(Throwable ignored){ }
            CookieManager manager=CookieManager.getInstance();
            manager.setAcceptCookie(true);
            try{manager.setAcceptThirdPartyCookies(web,true);}catch(Throwable ignored){ }
            // 把当前会话的身份 Cookie 灌进去，保证验证和登录是同一次会话。
            String cookie=NeteaseLogin.webCookie();
            if(cookie.length()>0)manager.setCookie(ORIGIN,cookie);
            web.setWebViewClient(new WebViewClient(){
                @Override
                public boolean shouldOverrideUrlLoading(WebView view,String link){
                    view.loadUrl(link);
                    return true;
                }

                @Override
                public void onReceivedError(WebView view,int errorCode,String description,String failingUrl){
                    if(status!=null)status.setText("验证页加载失败（"+description
                            +"）。可取消后改用「粘贴 CK」，或稍后重试。");
                }
            });
        }catch(Throwable t){
            HotBridge.log("[DEKK][一起听] 验证页初始化失败: "+t);
        }
        web.setBackgroundColor(Color.WHITE);
        column.addView(web,new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,dp(activity,380)));

        final AlertDialog dialog=new ModuleDialog(activity)
                .setTitle("网易云安全验证")
                .setView(column)
                .setNegativeButton("取消",null)
                .setPositiveButton("我已完成",null)
                .create();

        final Handler handler=new Handler(Looper.getMainLooper());
        final boolean[] finished={false};
        final boolean[] running={false};
        final int[] attempts={0};

        dialog.setOnShowListener(new DialogInterface.OnShowListener(){
            public void onShow(DialogInterface which){
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener(){
                    public void onClick(View view){
                        if(finished[0]||running[0])return;
                        // 直接相信用户已完成：立刻执行原本要做的动作（登录 / 发验证码）
                        if(status!=null)status.setText("按「已完成」继续…");
                        attempts[0]++;
                        attemptInBackground(activity,dialog,handler,retry,done,finished,running,status,attempts[0]);
                    }
                });
                try{web.loadUrl(target);}catch(Throwable t){
                    HotBridge.log("[DEKK][一起听] 验证页加载失败: "+t);
                }

            }
        });
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener(){
            public void onDismiss(DialogInterface which){
                finished[0]=true;
                handler.removeCallbacksAndMessages(null);
                try{
                    web.stopLoading();
                    web.loadUrl("about:blank");
                    if(web.getParent() instanceof ViewGroup)((ViewGroup)web.getParent()).removeView(web);
                    web.destroy();
                }catch(Throwable ignored){ }
            }
        });
        dialog.show();ModuleDialog.style(dialog);
    }

    /** 用验证页当前的 Cookie 重试登录/发码；成功就收尾，仍被拦就把状态写回弹窗。 */
    private static void attemptInBackground(final Activity activity,final AlertDialog dialog,
                                            final Handler handler,final Retry retry,final Done done,
                                            final boolean[] finished,final boolean[] running,
                                            final TextView status,final int attempt){
        running[0]=true;
        if(status!=null)status.setText("第 "+attempt+" 次确认中…（服务端仍要求验证时请再滑一次）");
        String webCookie=webCookie();
        NeteaseLogin.adoptWebSession(webCookie);
        final String cookie=webCookie;
        new Thread(new Runnable(){
            public void run(){
                NeteaseLogin.Result result=null;
                // 手动确认后无条件重试原动作，不检测页面或 Cookie 是否验证成功。
                if(retry!=null){
                    try{result=retry.attempt(cookie);}catch(Throwable t){
                        HotBridge.log("[DEKK][一起听] 验证后重试异常: "+t.getClass().getSimpleName());
                    }
                }
                final NeteaseLogin.Result found=result;
                activity.runOnUiThread(new Runnable(){
                    public void run(){
                        running[0]=false;
                        if(finished[0]||activity.isFinishing()||activity.isDestroyed())return;
                        if(found!=null&&found.ok){
                            close(activity,dialog,handler,done,finished,found,status);
                        }else if(found!=null&&found.needCaptcha){
                            // 服务端仍要求验证：留在弹窗里，让用户再滑一次/点我已完成
                            if(status!=null)status.setText("服务端仍要求安全验证"
                                    
                                    +"：请再滑一次，或点「我已完成」重试");
                        }else if(found!=null){
                            // 已经不是"要验证"了（比如密码错、验证码错）：把真实原因显示出来
                            close(activity,dialog,handler,done,finished,found,status);
                        }else{status.setText("请求未成功，请点「我已完成」重试");}
                    }
                });
            }
        },"dekk-music-verify").start();
    }

    private static void close(Activity activity,AlertDialog dialog,Handler handler,Done done,
                              boolean[] finished,NeteaseLogin.Result result,TextView status){
        if(finished[0])return;
        finished[0]=true;
        handler.removeCallbacksAndMessages(null);
        if(status!=null)status.setText(result!=null&&result.ok?"已通过，正在保存…":"验证未完成");
        try{dialog.dismiss();}catch(Throwable ignored){ }
        HotBridge.log("[DEKK][一起听] 安全验证结束："+(result!=null&&result.ok?"已放行":"未完成")
                +(result==null?"":"（code="+result.code+"）"));
        if(done!=null)done.done(result);
    }

    /** 验证页当前的 Cookie：music.163.com 与 st.music.163.com 都要收（验证状态可能落在后者）。 */
    private static String webCookie(){
        try{
            CookieManager manager=CookieManager.getInstance();
            java.util.LinkedHashMap<String,String> pairs=new java.util.LinkedHashMap<String,String>();
            for(String origin:new String[]{ORIGIN,"https://st.music.163.com","https://music.163.com"}){
                String cookie=manager.getCookie(origin);
                if(cookie==null)continue;
                for(String part:cookie.split(";")){
                    String pair=part.trim();
                    int cut=pair.indexOf('=');
                    if(cut<=0)continue;
                    pairs.put(pair.substring(0,cut),pair.substring(cut+1));
                }
            }
            return NeteaseCookie.build(pairs);
        }catch(Throwable t){
            return "";
        }
    }

    private static int dp(Activity activity,int value){
        return (int)(value*activity.getResources().getDisplayMetrics().density+0.5f);
    }
}