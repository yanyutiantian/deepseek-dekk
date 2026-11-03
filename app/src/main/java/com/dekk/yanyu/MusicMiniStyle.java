package com.dekk.yanyu;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Compact vector controls and a glass-like capsule; no font-dependent emoji. */
final class MusicMiniStyle extends Drawable {
    private final Paint paint=new Paint(3);
    private final boolean dark;
    private final float density;
    private float progress;
    MusicMiniStyle(boolean night,float d){dark=night;density=d;}
    void progress(float value){progress=Math.max(0,Math.min(1,value));invalidateSelf();}
    public void draw(Canvas c){
        Rect b=getBounds();RectF r=new RectF(b.left+.5f,b.top+.5f,b.right-.5f,b.bottom-.5f);
        paint.setStyle(Paint.Style.FILL);paint.setColor(dark?0xF02B303A:0xF5F3F6FC);
        c.drawRoundRect(r,20*density,20*density,paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(density);paint.setColor(dark?0x335F7192:0x22758BB4);
        c.drawRoundRect(r,20*density,20*density,paint);
        if(progress>0){paint.setColor(0xFF6488E8);paint.setStrokeWidth(2*density);paint.setStrokeCap(Paint.Cap.ROUND);
            float left=r.left+18*density,right=r.right-18*density;
            c.drawLine(left,r.bottom-2*density,left+(right-left)*progress,r.bottom-2*density,paint);}
    }
    public void setAlpha(int a){} public void setColorFilter(ColorFilter f){} public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    static Drawable icon(final int kind,final int color){return new Drawable(){
        final Paint p=new Paint(3);
        public void draw(Canvas canvas){int save=canvas.save();Rect b=getBounds();canvas.translate(b.left,b.top);canvas.scale(b.width()/24f,b.height()/24f);
            p.setColor(color);p.setStyle(Paint.Style.FILL);
            if(kind==4||kind==5){
                p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.8f);p.setStrokeCap(Paint.Cap.ROUND);
                canvas.drawLine(5,7,19,7,p);canvas.drawLine(19,7,16,4,p);
                canvas.drawLine(19,17,5,17,p);canvas.drawLine(5,17,8,20,p);
                if(kind==4){canvas.drawLine(19,7,19,14,p);canvas.drawLine(5,10,5,17,p);
                    p.setStyle(Paint.Style.FILL);p.setTextSize(8);p.setTextAlign(Paint.Align.CENTER);canvas.drawText("1",12,15,p);}
                else canvas.drawLine(5,12,15,12,p);
            }
            else if(kind==1){canvas.drawRoundRect(new RectF(6,5,10,19),1,1,p);canvas.drawRoundRect(new RectF(14,5,18,19),1,1,p);}
            else if(kind==3){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);p.setStrokeCap(Paint.Cap.ROUND);canvas.drawArc(new RectF(4,4,20,20),180,180,false,p);canvas.drawLine(4,12,4,19,p);canvas.drawLine(20,12,20,19,p);}
            else{Path path=new Path();path.moveTo(7,5);path.lineTo(18,12);path.lineTo(7,19);path.close();canvas.drawPath(path,p);if(kind==2)canvas.drawRect(18,5,20,19,p);}
            canvas.restoreToCount(save);}
        public void setAlpha(int a){} public void setColorFilter(ColorFilter f){} public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    };}
}