package com.dekk.yanyu;
import android.app.Activity;
import android.os.CountDownTimer;
import android.widget.*;
import org.json.JSONObject;

public final class AccountPages {
    private AccountPages(){}
    public static void open(Activity a){if(AccountStore.token(a).length()==0)form(a,"login");else UserCenterPage.open(a);}
    private static void form(final Activity a,final String mode){
        final boolean login=mode.equals("login"),register=mode.equals("register");
        final CenterPage p=new CenterPage(a,login?"登录":register?"注册":"修改密码",login?null:new Runnable(){public void run(){open(a);}});
        // 标题已经是大标题「登录 / 注册 / 修改密码」，正文不再重复一遍
        TextView subtitle=p.label(login?"登录账号，同步你的人设":register?"创建账号，收藏与分享人设":"验证邮箱，设置新密码",14);
        subtitle.setTextColor(p.secondary);
        subtitle.setLineSpacing(p.dp(4),1f);
        subtitle.setPadding(0,0,0,p.dp(18));
        p.body.addView(subtitle);
        LinearLayout c=p.card();
        final EditText email=p.field(c,"QQ邮箱",33,false);email.setText(AccountStore.user(a).optString("email",""));
        final EditText password=p.field(c,login?"密码":"密码（8—12字符）",129,false);
        final EditText nickname=register?p.field(c,"昵称",1,false):null;
        final EditText code=login?null:p.field(c,"6位验证码",2,false);
        final TextView[] send=new TextView[1];
        if(!login)send[0]=p.rowIcon(c,MiuixIcon.DOC,"发送验证码",new Runnable(){public void run(){
            send[0].setEnabled(false);
            PersonaApi.request(a,"send_code",PersonaApi.object("email",email.getText().toString().trim(),"purpose",register?"register":"password"),new PersonaApi.Callback(){public void done(JSONObject r,String e){
                if(!p.active())return;if(e!=null){send[0].setEnabled(true);p.message(e);return;}
                p.message("验证码已发送，5分钟内有效");
                new CountDownTimer(60000,1000){public void onTick(long ms){if(!p.active()){cancel();return;}send[0].setText("重新发送（"+(ms/1000+1)+"秒）");}public void onFinish(){send[0].setEnabled(true);send[0].setText("发送验证码");}}.start();
            }});
        }});
        final String submitTitle=login?"登录":register?"注册":"确认修改";
        final int[] attempt={0};
        final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
        final TextView[] submit=new TextView[1];submit[0]=p.action(submitTitle,new Runnable(){public void run(){
            android.util.Log.i("DEKK-Account","submit clicked: "+mode);
            String address=email.getText().toString().trim();
            if(address.length()==0 || password.getText().length()==0){p.message("请填写邮箱和密码");return;}
            String entered=password.getText().toString();int length=entered.codePointCount(0,entered.length());
            if(!login && (length<8 || length>12)){p.message("新密码应为8—12个字符");return;}
            final int requestId=++attempt[0];
            JSONObject b=PersonaApi.object("email",email.getText().toString().trim(),"password",password.getText().toString());
            try{if(code!=null)b.put("code",code.getText().toString().trim());if(nickname!=null)b.put("nickname",nickname.getText().toString().trim());}catch(Exception e){return;}
            submit[0].setEnabled(false);submit[0].setText(submitTitle+"中…");submit[0].setAlpha(0.6f);
            final Runnable timeout=new Runnable(){public void run(){
                if(!p.active() || attempt[0]!=requestId)return;
                attempt[0]++;submit[0].setEnabled(true);submit[0].setText(submitTitle);submit[0].setAlpha(1f);
                p.message("请求超时，请稍后重试；注册结果不确定时可先尝试登录");
            }};
            handler.postDelayed(timeout,45000);
            PersonaApi.request(a,mode,b,new PersonaApi.Callback(){public void done(JSONObject r,String e){
                handler.removeCallbacks(timeout);
                if(!p.active() || attempt[0]!=requestId)return;
                android.util.Log.i("DEKK-Account","submit finished: "+mode+" success="+(e==null));
                submit[0].setEnabled(true);submit[0].setText(submitTitle);submit[0].setAlpha(1f);if(e!=null){p.message(e);return;}
                try{if(login)AccountStore.save(a,r);else if(!register)AccountStore.clear(a);}catch(Exception ex){p.message("登录状态保存失败");return;}
                password.setText("");if(login)UserCenterPage.open(a);else{p.message(register?"注册成功，请登录":"密码已修改，旧登录已失效");form(a,"login");}
            }});
        }});
        if(login){p.section("账号服务");LinearLayout links=p.card();p.rowIcon(links,MiuixIcon.PERSON,"注册账号 ›",new Runnable(){public void run(){form(a,"register");}});p.rowIcon(links,MiuixIcon.DOC,"忘记密码 ›",new Runnable(){public void run(){form(a,"password");}});}
        p.show();
    }
}