package com.dekk.yanyu;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.view.View;
import android.widget.EditText;
import android.widget.NumberPicker;
final class BubbleStyleEditor {
    static void color(final CenterPage page,final String key,int value){
        final EditText input=new EditText(page.activity);input.setSingleLine();input.setText(String.format(java.util.Locale.US,"#%06X",value&0xffffff));
        final AlertDialog dialog=new ModuleDialog(page.activity).setTitle("气泡颜色 #RRGGBB").setView(input).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener(){public void onShow(DialogInterface d){dialog.getButton(-1).setOnClickListener(new View.OnClickListener(){public void onClick(View v){String text=input.getText().toString().trim();if(!text.matches("#[0-9a-fA-F]{6}")){input.setError("请输入六位颜色，例如 #9BEA68");return;}BubbleStyle.prefs().edit().putInt(key,Color.parseColor(text)).apply();dialog.dismiss();page.message("已保存，重新进入会话生效");}});}});dialog.show();ModuleDialog.style(dialog);
    }
    static void radius(final CenterPage page){final NumberPicker picker=new NumberPicker(page.activity);picker.setMinValue(0);picker.setMaxValue(32);picker.setValue(BubbleStyle.radius());new ModuleDialog(page.activity).setTitle("气泡圆角（dp）").setView(picker).setNegativeButton("取消",null).setPositiveButton("保存",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){BubbleStyle.prefs().edit().putInt("bubble_radius",picker.getValue()).apply();page.message("已保存，重新进入会话生效");}}).show();}
}