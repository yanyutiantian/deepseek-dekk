package com.dekk.yanyu;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * Miuix 线性图标：24dp 视口、1.8dp 描边、圆头圆角。
 *
 * <p>模块以 dex 注入宿主，不方便携带图片资源，因此全部用 Path 代码绘制。
 */
public final class MiuixIcon extends View {
    public static final int PERSON=0,BELL=1,STAR=2,PLUG=3,PALETTE=4,MUSIC=5,HEART=6,CHAT=7;
    public static final int SHIELD=8,GEAR=9,PHONE=10,SEARCH=11,IMAGE=12,GAUGE=13,DOC=14,CHECK=15;
    public static final int HOME=16,CLOCK=17,DOWNLOAD=18,EDIT=19;

    private final Paint stroke=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path=new Path();
    private final RectF rect=new RectF();
    private int kind=PERSON;
    private int color=0xFF1A1A1A;

    public MiuixIcon(Context context){
        super(context);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
        stroke.setColor(color);
        fill.setColor(color);
    }

    public MiuixIcon kind(int value){kind=value;invalidate();return this;}
    public MiuixIcon color(int value){
        color=value;stroke.setColor(value);fill.setColor(value);invalidate();return this;
    }
    /** 图标在行内的固定尺寸（含内边距）。 */
    public static int size(Context context){return (int)(24*context.getResources().getDisplayMetrics().density+0.5f);}

    @Override protected void onMeasure(int widthMeasureSpec,int heightMeasureSpec){
        int fallback=size(getContext());
        setMeasuredDimension(resolve(widthMeasureSpec,fallback),resolve(heightMeasureSpec,fallback));
    }
    private static int resolve(int spec,int fallback){
        int mode=MeasureSpec.getMode(spec);
        int size=MeasureSpec.getSize(spec);
        if(mode==MeasureSpec.EXACTLY)return size;
        if(mode==MeasureSpec.AT_MOST)return Math.min(fallback,size);
        return fallback;
    }

    @Override protected void onDraw(Canvas canvas){
        float scale=getWidth()/24f;
        canvas.save();
        canvas.scale(scale,scale);
        stroke.setStrokeWidth(Math.max(1.5f,1.8f));
        switch(kind){
            case BELL: bell(canvas);break;
            case STAR: star(canvas);break;
            case PLUG: plug(canvas);break;
            case PALETTE: palette(canvas);break;
            case MUSIC: music(canvas);break;
            case HEART: heart(canvas);break;
            case CHAT: chat(canvas);break;
            case SHIELD: shield(canvas);break;
            case GEAR: gear(canvas);break;
            case PHONE: phone(canvas);break;
            case SEARCH: search(canvas);break;
            case IMAGE: image(canvas);break;
            case GAUGE: gauge(canvas);break;
            case DOC: doc(canvas);break;
            case CHECK: check(canvas);break;
            case HOME: home(canvas);break;
            case CLOCK: clock(canvas);break;
            case DOWNLOAD: download(canvas);break;
            case EDIT: edit(canvas);break;
            default: person(canvas);break;
        }
        canvas.restore();
    }

    private void person(Canvas canvas){
        path.reset();path.addCircle(12,8.6f,3.7f,Path.Direction.CW);canvas.drawPath(path,stroke);
        path.reset();path.moveTo(5.8f,19.8f);
        path.cubicTo(5.8f,16f,8.6f,14.3f,12f,14.3f);
        path.cubicTo(15.4f,14.3f,18.2f,16f,18.2f,19.8f);
        canvas.drawPath(path,stroke);
    }
    private void bell(Canvas canvas){
        path.reset();path.moveTo(12,4.2f);
        path.cubicTo(9,4.2f,6.9f,6.6f,6.9f,9.9f);
        path.lineTo(6.9f,13.4f);path.lineTo(5.2f,16.5f);path.lineTo(18.8f,16.5f);
        path.lineTo(17.1f,13.4f);path.lineTo(17.1f,9.9f);
        path.cubicTo(17.1f,6.6f,15,4.2f,12,4.2f);path.close();
        canvas.drawPath(path,stroke);
        path.reset();path.moveTo(10.2f,18.5f);
        path.cubicTo(10.6f,19.6f,11.3f,20.2f,12,20.2f);
        path.cubicTo(12.7f,20.2f,13.4f,19.6f,13.8f,18.5f);
        canvas.drawPath(path,stroke);
    }
    private void star(Canvas canvas){
        path.reset();
        for(int i=0;i<10;i++){
            double radius=(i%2==0)?7.4:3.2;
            double angle=-Math.PI/2+i*Math.PI/5;
            float x=(float)(12+Math.cos(angle)*radius);
            float y=(float)(12+Math.sin(angle)*radius);
            if(i==0)path.moveTo(x,y);else path.lineTo(x,y);
        }
        path.close();canvas.drawPath(path,stroke);
    }
    private void plug(Canvas canvas){
        rect.set(8.2f,11.2f,15.8f,17.2f);
        path.reset();path.addRoundRect(rect,2.2f,2.2f,Path.Direction.CW);canvas.drawPath(path,stroke);
        canvas.drawLine(10.4f,7.2f,10.4f,11.2f,stroke);
        canvas.drawLine(13.6f,7.2f,13.6f,11.2f,stroke);
        canvas.drawLine(12f,17.2f,12f,20.4f,stroke);
    }
    private void palette(Canvas canvas){
        path.reset();path.addCircle(12,12,8.2f,Path.Direction.CW);canvas.drawPath(path,stroke);
        canvas.drawCircle(9.1f,10.1f,1.15f,fill);
        canvas.drawCircle(14.9f,10.1f,1.15f,fill);
        canvas.drawCircle(9.1f,15.1f,1.15f,fill);
    }
    private void music(Canvas canvas){
        canvas.drawCircle(7.6f,17.2f,2.3f,stroke);
        canvas.drawCircle(16.4f,15.2f,2.3f,stroke);
        canvas.drawLine(9.9f,17.2f,9.9f,8.2f,stroke);
        canvas.drawLine(18.7f,15.2f,18.7f,6.2f,stroke);
        canvas.drawLine(9.9f,8.2f,18.7f,6.2f,stroke);
    }
    private void heart(Canvas canvas){
        path.reset();path.moveTo(12,19.7f);
        path.cubicTo(12,19.7f,4.4f,15.2f,4.4f,9.9f);
        path.cubicTo(4.4f,7.4f,6.3f,5.6f,8.6f,5.6f);
        path.cubicTo(10.2f,5.6f,11.4f,6.5f,12,7.6f);
        path.cubicTo(12.6f,6.5f,13.8f,5.6f,15.4f,5.6f);
        path.cubicTo(17.7f,5.6f,19.6f,7.4f,19.6f,9.9f);
        path.cubicTo(19.6f,15.2f,12,19.7f,12,19.7f);
        path.close();canvas.drawPath(path,stroke);
    }
    private void chat(Canvas canvas){
        rect.set(4.2f,5f,19.8f,16.2f);
        path.reset();path.addRoundRect(rect,3.4f,3.4f,Path.Direction.CW);canvas.drawPath(path,stroke);
        path.reset();path.moveTo(8.6f,16.2f);path.lineTo(8.6f,20.2f);path.lineTo(12.6f,16.2f);
        canvas.drawPath(path,stroke);
    }
    private void shield(Canvas canvas){
        path.reset();path.moveTo(12,3.9f);path.lineTo(19.1f,6.5f);path.lineTo(19.1f,11.6f);
        path.cubicTo(19.1f,16.1f,16.1f,19.2f,12,20.4f);
        path.cubicTo(7.9f,19.2f,4.9f,16.1f,4.9f,11.6f);
        path.lineTo(4.9f,6.5f);path.close();canvas.drawPath(path,stroke);
    }
    private void gear(Canvas canvas){
        canvas.drawCircle(12,12,3.2f,stroke);
        for(int i=0;i<8;i++){
            double angle=i*Math.PI/4;
            float x1=(float)(12+Math.cos(angle)*5.6);
            float y1=(float)(12+Math.sin(angle)*5.6);
            float x2=(float)(12+Math.cos(angle)*8.5);
            float y2=(float)(12+Math.sin(angle)*8.5);
            canvas.drawLine(x1,y1,x2,y2,stroke);
        }
    }
    private void phone(Canvas canvas){
        rect.set(7f,3.6f,17f,20.4f);
        path.reset();path.addRoundRect(rect,2.6f,2.6f,Path.Direction.CW);canvas.drawPath(path,stroke);
        canvas.drawLine(10.8f,17.6f,13.2f,17.6f,stroke);
    }
    private void search(Canvas canvas){
        canvas.drawCircle(11,11,5.4f,stroke);
        canvas.drawLine(15f,15f,18.8f,18.8f,stroke);
    }
    private void image(Canvas canvas){
        rect.set(4.2f,5f,19.8f,19f);
        path.reset();path.addRoundRect(rect,3f,3f,Path.Direction.CW);canvas.drawPath(path,stroke);
        canvas.drawCircle(8.8f,9.8f,1.3f,fill);
        path.reset();path.moveTo(4.9f,17.4f);path.lineTo(10.2f,12f);path.lineTo(13.6f,15.4f);
        path.lineTo(16f,13f);path.lineTo(19.3f,16.4f);canvas.drawPath(path,stroke);
    }
    private void gauge(Canvas canvas){
        rect.set(4.4f,6.4f,19.6f,21.6f);
        path.reset();path.addArc(rect,190f,160f);canvas.drawPath(path,stroke);
        canvas.drawLine(12f,14f,15.6f,9.6f,stroke);
        canvas.drawCircle(12f,14f,1.15f,fill);
    }
    private void doc(Canvas canvas){
        rect.set(6f,4f,18f,20f);
        path.reset();path.addRoundRect(rect,2.4f,2.4f,Path.Direction.CW);canvas.drawPath(path,stroke);
        canvas.drawLine(9f,9f,15f,9f,stroke);
        canvas.drawLine(9f,13f,15f,13f,stroke);
        canvas.drawLine(9f,17f,12.6f,17f,stroke);
    }
    private void home(Canvas canvas){
        path.reset();path.moveTo(3.6f,11.2f);path.lineTo(12,4.2f);path.lineTo(20.4f,11.2f);
        canvas.drawPath(path,stroke);
        path.reset();path.moveTo(6.2f,9.9f);path.lineTo(6.2f,19.6f);path.lineTo(17.8f,19.6f);
        path.lineTo(17.8f,9.9f);canvas.drawPath(path,stroke);
        path.reset();path.moveTo(10.2f,19.6f);path.lineTo(10.2f,14.4f);path.lineTo(13.8f,14.4f);
        path.lineTo(13.8f,19.6f);canvas.drawPath(path,stroke);
    }
    private void clock(Canvas canvas){
        path.reset();path.addCircle(12,12,8.2f,Path.Direction.CW);canvas.drawPath(path,stroke);
        canvas.drawLine(12,7.4f,12,12f,stroke);
        canvas.drawLine(12,12f,15.6f,14f,stroke);
    }
    private void download(Canvas canvas){
        canvas.drawLine(12,4.4f,12,14.6f,stroke);
        path.reset();path.moveTo(7.6f,10.4f);path.lineTo(12,14.8f);path.lineTo(16.4f,10.4f);
        canvas.drawPath(path,stroke);
        path.reset();path.moveTo(4.8f,17.4f);path.lineTo(4.8f,19.6f);path.lineTo(19.2f,19.6f);
        path.lineTo(19.2f,17.4f);canvas.drawPath(path,stroke);
    }
    private void edit(Canvas canvas){
        path.reset();path.moveTo(5f,19f);path.lineTo(5.6f,15.2f);path.lineTo(15.8f,5f);
        path.lineTo(19f,8.2f);path.lineTo(8.8f,18.4f);path.close();canvas.drawPath(path,stroke);
        canvas.drawLine(13.6f,7.2f,16.8f,10.4f,stroke);
    }
    private void check(Canvas canvas){
        path.reset();path.moveTo(6.2f,12.6f);path.lineTo(10.4f,16.8f);path.lineTo(17.8f,8.2f);
        canvas.drawPath(path,stroke);
    }
}