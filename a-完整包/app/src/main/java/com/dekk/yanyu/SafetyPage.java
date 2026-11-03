package com.dekk.yanyu;

import android.app.Activity;
import android.os.CountDownTimer;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;

/** 安全设置：显示当前账号、修改密码（邮箱验证码）、退出登录。 */
public final class SafetyPage {
    private SafetyPage() { }

    public static void show(final Activity activity){
        final CenterPage page=new CenterPage(activity,"安全设置",null);

        page.section("当前账号");LinearLayout account=page.card();
        TextView email=page.label(AccountStore.user(activity).optString("email","未登录"),16);
        account.addView(email);
        TextView nickname=page.label("昵称："+UserProfile.nickname(activity),13);
        nickname.setTextColor(page.secondary);
        nickname.setPadding(0,page.dp(6),0,0);account.addView(nickname);

        page.section("修改密码");LinearLayout form=page.card();
        final EditText code=page.field(form,"邮箱验证码（6位）",InputType.TYPE_CLASS_NUMBER,false);
        final EditText password=page.field(form,"新密码（8—12个字符）",
                InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD,false);
        final TextView send=page.row(form,"发送验证码  ›",new Runnable(){public void run(){}});
        final String address=AccountStore.user(activity).optString("email","");
        send.setOnClickListener(new android.view.View.OnClickListener(){
            public void onClick(android.view.View v){
                if(address.length()==0){page.message("请先登录账号");return;}
                send.setEnabled(false);
                PersonaApi.request(activity,"send_code",PersonaApi.object("email",address,"purpose","password"),
                        new PersonaApi.Callback(){public void done(JSONObject result,String error){
                    if(!page.active())return;
                    if(error!=null){send.setEnabled(true);page.message(error);return;}
                    page.message("验证码已发送到 "+address+"，5 分钟内有效");
                    new CountDownTimer(60000,1000){
                        public void onTick(long ms){if(!page.active()){cancel();return;}send.setText("重新发送（"+(ms/1000+1)+"秒）  ›");}
                        public void onFinish(){send.setEnabled(true);send.setText("发送验证码  ›");}
                    }.start();
                }});
            }
        });
        page.action("确认修改密码",new Runnable(){public void run(){
            if(address.length()==0){page.message("请先登录账号");return;}
            String entered=password.getText().toString();
            int length=entered.codePointCount(0,entered.length());
            if(length<8||length>12){page.message("新密码应为8—12个字符");return;}
            if(code.getText().toString().trim().length()!=6){page.message("请输入6位验证码");return;}
            PersonaApi.request(activity,"password",PersonaApi.object("email",address,
                    "password",entered,"code",code.getText().toString().trim()),
                    new PersonaApi.Callback(){public void done(JSONObject result,String error){
                if(!page.active())return;
                if(error!=null){page.message(error);return;}
                // 改密后服务端会清掉所有登录态，这里同步退出
                AccountStore.clear(activity.getApplicationContext());
                page.message("密码已修改，请重新登录");
                AccountPages.open(activity);
            }});
        }});

        page.section("登录状态");LinearLayout session=page.card();
        page.row(session,"退出登录  ›",new Runnable(){public void run(){
            PersonaApi.request(activity,"logout",null,new PersonaApi.Callback(){
                public void done(JSONObject result,String error){
                    AccountStore.clear(activity.getApplicationContext());
                    if(!page.active())return;
                    page.message(error==null?"已退出登录":"已清除本地登录状态");
                    AccountPages.open(activity);
                }});
        }}).setTextColor(0xFFD84A4A);
        TextView note=page.label("退出后需要重新登录才能使用人设、插件市场与个人中心。",12);
        note.setTextColor(page.secondary);note.setGravity(Gravity.START);session.addView(note);
        page.show();
    }
}