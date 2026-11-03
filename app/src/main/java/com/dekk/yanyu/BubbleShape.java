package com.dekk.yanyu;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

final class BubbleShape {
    static void draw(Canvas canvas,float w,float h,float density,int kind,boolean dark){
        float tail=kind==3?0:6*density,radius=Math.min(6*density,h/2);
        float left=kind==2?tail:0,right=kind==1?w-tail:w;
        if(right<=left)return;
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(kind==1?(dark?0xFF285D32:0xFF95EC69):(dark?0xFF292929:0xFFFFFFFF));
        canvas.drawRoundRect(new RectF(left,0,right,h),radius,radius,paint);
        if(tail>0){float y=Math.min(22*density,h/2),half=Math.min(5*density,h/4);Path path=new Path();float x=kind==1?right:left;
            path.moveTo(x,y-half);path.lineTo(kind==1?w:0,y);path.lineTo(x,y+half);path.close();canvas.drawPath(path,paint);
        }
    }
}