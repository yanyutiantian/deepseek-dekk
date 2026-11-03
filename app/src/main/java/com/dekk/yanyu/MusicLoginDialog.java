package com.dekk.yanyu;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** 网易云登录对话框：短信验证码、粘贴 CK 两种入口。 */
final class MusicLoginDialog {
    private static final String DEFAULT_PHONE="";

    private MusicLoginDialog() { }

    interface Done { void done(NeteaseLogin.Result result); }

    static void showCaptcha(final Activity activity,final Done done){
        LinearLayout column=column(activity);
        final EditText phone=field(activity,column,"手机号",DEFAULT_PHONE,InputType.TYPE_CLASS_PHONE);
        final EditText code=field(activity,column,"短信验证码","",InputType.TYPE_CLASS_NUMBER);
        final TextView send=MiuixUi.smallButton(activity,ModuleTheme.dark(activity),"发送验证码",false,null);
        LinearLayout.LayoutParams sendParams=new LinearLayout.LayoutParams(-1,dp(activity,46));
        sendParams.topMargin=dp(activity,12);
        send.setLayoutParams(sendParams);
        send.setOnClickListener(new View.OnClickListener(){
            public void onClick(View view){
                send.setText("正在发送…");
                new Thread(new Runnable(){
                    public void run(){
                        final String phoneText=phone.getText().toString();
                        final NeteaseLogin.Result result=NeteaseLogin.sendCaptcha(phoneText);
                        activity.runOnUiThread(new Runnable(){
                            public void run(){
                                send.setText("发送验证码  ›");
                                if(result.ok){
                                    Toast.makeText(activity,"验证码已发送，请查看短信",Toast.LENGTH_LONG).show();
                                    return;
                                }
                                if(result.needCaptcha){
                                    // 发码也被要求安全验证：先弹滑块，验证通过后自动重新发码
                                    Toast.makeText(activity,"网易云要求安全验证：完成后再发验证码",
                                            Toast.LENGTH_LONG).show();
                                    NeteaseCaptchaDialog.show(activity,result.captchaUrl,
                                            new NeteaseCaptchaDialog.Retry(){
                                        public NeteaseLogin.Result attempt(String webCookie){
                                            NeteaseLogin.adoptWebSession(webCookie);
                                            return NeteaseLogin.sendCaptcha(phoneText);
                                        }
                                    },new NeteaseCaptchaDialog.Done(){
                                        public void done(NeteaseLogin.Result finished){
                                            if(finished!=null&&finished.ok){
                                                Toast.makeText(activity,"验证码已发送，请查看短信",
                                                        Toast.LENGTH_LONG).show();
                                            }else{
                                                Toast.makeText(activity,finished==null?"已取消验证"
                                                        :finished.message,Toast.LENGTH_LONG).show();
                                            }
                                        }
                                    });
                                    return;
                                }
                                Toast.makeText(activity,result.message,Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                },"dekk-music-sms").start();
            }
        });
        column.addView(send,sendParams);
        explain(activity,column,"验证码登录同样会先试 weapi 加密接口，失败再试明文接口。");
        final AlertDialog dialog=new ModuleDialog(activity)
                .setTitle("网易云登录 · 短信")
                .setView(column)
                .setNegativeButton("取消",null)
                .setPositiveButton("登录",null)
                .create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener(){
            public void onShow(DialogInterface which){
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener(){
                    public void onClick(View view){
                        run(activity,dialog,done,new Task(){
                            public NeteaseLogin.Result run(boolean fresh){
                                return NeteaseLogin.loginByCaptcha(activity,phone.getText().toString(),
                                        code.getText().toString(),fresh);
                            }
                        });
                    }
                });
            }
        });
        dialog.show();ModuleDialog.style(dialog);
    }

    static void showCookie(final Activity activity,final Done done){
        LinearLayout column=column(activity);
        final EditText cookie=field(activity,column,"粘贴包含 MUSIC_U= 的完整 CK","",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        explain(activity,column,"在网易云网页版登录后，从开发者工具里复制 Cookie 整串粘贴到这里即可。");
        final AlertDialog dialog=new ModuleDialog(activity)
                .setTitle("网易云登录 · 粘贴 CK")
                .setView(column)
                .setNegativeButton("取消",null)
                .setPositiveButton("保存",null)
                .create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener(){
            public void onShow(DialogInterface which){
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener(){
                    public void onClick(View view){
                        run(activity,dialog,done,new Task(){
                            public NeteaseLogin.Result run(boolean fresh){
                                return NeteaseLogin.loginByCookie(activity,cookie.getText().toString());
                            }
                        });
                    }
                });
            }
        });
        dialog.show();ModuleDialog.style(dialog);
    }

    private interface Task { NeteaseLogin.Result run(boolean fresh); }

    /** 滑块验证拿到的 CK：存下来并收尾（关掉登录弹窗、通知调用方刷新）。 */
    private static void finishLogin(final Activity activity,final AlertDialog dialog,final Done done,
                                    final NeteaseLogin.Result result){
        activity.runOnUiThread(new Runnable(){
            public void run(){
                if(result!=null&&result.ok)try{dialog.dismiss();}catch(Throwable ignored){ }
                String text=result==null?"已取消验证"
                        :(result.ok
                        ?"登录成功（安全验证）"+(result.nickname.length()==0?"":"："+result.nickname)
                        :result.message);
                Toast.makeText(activity,text,Toast.LENGTH_LONG).show();
                if(done!=null)done.done(result);
            }
        });
    }

    private static void run(final Activity activity,final AlertDialog dialog,final Done done,final Task task){
        Toast.makeText(activity,"正在登录…",Toast.LENGTH_SHORT).show();
        new Thread(new Runnable(){
            public void run(){
                final NeteaseLogin.Result result;
                try{result=task.run(true);}catch(Throwable failure){
                    final Throwable cause=failure;
                    activity.runOnUiThread(new Runnable(){
                        public void run(){Toast.makeText(activity,"登录异常："+cause.getClass().getSimpleName(),Toast.LENGTH_LONG).show();}
                    });
                    return;
                }
                activity.runOnUiThread(new Runnable(){
                    public void run(){
                        if(result.ok)dialog.dismiss();
                        String text=result.ok
                                ?"登录成功"+(result.nickname.length()==0?"":"："+result.nickname)+"（"+result.scheme+"）"
                                :result.message;
                        Toast.makeText(activity,text,Toast.LENGTH_LONG).show();
                        if(!result.ok&&result.needCaptcha){
                            // 风控要求安全验证：登录弹窗保持不动，把验证页弹在它上面，
                            // 验证期间用同一身份+同一会话 Cookie 自动重试登录。
                            Toast.makeText(activity,"网易云要求安全验证：请在上一层页面完成",
                                    Toast.LENGTH_LONG).show();
                            NeteaseCaptchaDialog.show(activity,result.captchaUrl,
                                    new NeteaseCaptchaDialog.Retry(){
                                public NeteaseLogin.Result attempt(String webCookie){
                                    NeteaseLogin.adoptWebSession(webCookie);
                                    return task.run(false);
                                }
                            },new NeteaseCaptchaDialog.Done(){
                                public void done(NeteaseLogin.Result finished){
                                    if(finished!=null&&finished.ok)finishLogin(activity,dialog,done,finished);
                                    else if(done!=null)done.done(finished==null?result:finished);
                                }
                            });
                            return;
                        }
                        if(done!=null)done.done(result);
                    }
                });
            }
        },"dekk-music-login").start();
    }

    private static LinearLayout column(Activity activity){
        LinearLayout column=new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        int padding=dp(activity,16);
        column.setPadding(padding,padding,padding,padding);
        return column;
    }

    /** 标题 13sp 灰色 + 灰色圆角输入框，与模块其它表单一致。 */
    private static EditText field(Activity activity,LinearLayout column,String hint,String initial,int inputType){
        boolean dark=ModuleTheme.dark(activity);
        TextView caption=new TextView(activity);
        caption.setText(hint);
        caption.setTextSize(13);
        caption.setTextColor(ModuleTheme.secondary(dark));
        caption.setIncludeFontPadding(false);
        caption.setPadding(0,dp(activity,12),0,dp(activity,7));
        column.addView(caption);
        EditText field=new EditText(activity);
        field.setInputType(inputType);
        field.setText(initial);
        field.setSingleLine((inputType&InputType.TYPE_TEXT_FLAG_MULTI_LINE)==0);
        field.setSelectAllOnFocus(false);
        field.setTextSize(16);
        field.setTextColor(ModuleTheme.text(dark));
        field.setHintTextColor(ModuleTheme.secondary(dark));
        field.setBackground(MiuixUi.shape(activity,ModuleTheme.field(dark),12));
        boolean multi=(inputType&InputType.TYPE_TEXT_FLAG_MULTI_LINE)!=0;
        field.setPadding(dp(activity,14),dp(activity,multi?12:14),dp(activity,14),dp(activity,12));
        column.addView(field,new LinearLayout.LayoutParams(-1,multi?dp(activity,120):dp(activity,52)));
        return field;
    }

    private static void explain(Activity activity,LinearLayout column,String text){
        TextView note=new TextView(activity);
        note.setText(text);
        note.setTextSize(13);
        note.setTextColor(ModuleTheme.secondary(ModuleTheme.dark(activity)));
        note.setIncludeFontPadding(false);
        note.setLineSpacing(dp(activity,4),1f);
        note.setGravity(Gravity.START);
        note.setPadding(0,dp(activity,10),0,0);
        column.addView(note);
    }

    private static int dp(Activity activity,int value){
        return (int)(value*activity.getResources().getDisplayMetrics().density+0.5f);
    }
}