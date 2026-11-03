package com.dekk.yanyu;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.DialogInterface;
import android.widget.DatePicker;
import android.widget.TimePicker;
import android.widget.Toast;
import java.util.Calendar;

final class FakeBanDeadlinePicker {
    static void show(final Activity a,final Runnable update){
        final Calendar selected=Calendar.getInstance();
        selected.setTimeInMillis(FakeBanStore.active(a)?FakeBanStore.until(a):System.currentTimeMillis()+3600000);
        DatePickerDialog date=new DatePickerDialog(a,new DatePickerDialog.OnDateSetListener(){
            public void onDateSet(DatePicker view,int year,int month,int day){
                selected.set(Calendar.YEAR,year);selected.set(Calendar.MONTH,month);selected.set(Calendar.DAY_OF_MONTH,day);
                TimePickerDialog time=new TimePickerDialog(a,new TimePickerDialog.OnTimeSetListener(){
                    public void onTimeSet(TimePicker view,int hour,int minute){
                        selected.set(Calendar.HOUR_OF_DAY,hour);selected.set(Calendar.MINUTE,minute);selected.set(Calendar.SECOND,0);selected.set(Calendar.MILLISECOND,0);
                        final long end=selected.getTimeInMillis();
                        if(end<=System.currentTimeMillis()){Toast.makeText(a,"请选择未来的截止时间",Toast.LENGTH_LONG).show();update.run();return;}
                        String text=new java.text.SimpleDateFormat("yyyy年MM月dd日 HH:mm",java.util.Locale.getDefault()).format(selected.getTime());
                        new ModuleDialog(a).setTitle("开启本地伪封号").setMessage("禁言至 "+text+"。聊天页将显示宿主原生禁言提示；可在模块设置提前关闭。").setNegativeButton("取消",null).setPositiveButton("开启",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){FakeBanStore.startUntil(a,end);update.run();}}).show();
                    }
                },selected.get(Calendar.HOUR_OF_DAY),selected.get(Calendar.MINUTE),true);
                time.setTitle("截止时间（24小时制）");time.setOnDismissListener(new DialogInterface.OnDismissListener(){public void onDismiss(DialogInterface d){update.run();}});time.show();
            }
        },selected.get(Calendar.YEAR),selected.get(Calendar.MONTH),selected.get(Calendar.DAY_OF_MONTH));
        date.setTitle("选择禁言截止日期");date.setOnDismissListener(new DialogInterface.OnDismissListener(){public void onDismiss(DialogInterface d){update.run();}});date.show();
    }
}