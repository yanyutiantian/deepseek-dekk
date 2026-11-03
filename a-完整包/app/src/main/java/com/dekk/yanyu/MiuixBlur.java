package com.dekk.yanyu;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

/**
 * 液态玻璃用到的背景采样：把指定 View 在目标区域的内容降采样后做盒式模糊。
 *
 * <p>不依赖 RenderScript / RenderEffect，API 21 起都可用；失败时返回 null，由调用方退化。
 */
final class MiuixBlur {
    private MiuixBlur(){}

    /** 采样 source 中与 target 重叠的区域，缩到 1/downscale 后模糊。 */
    static Bitmap sample(View source,View target,int downscale,int radius){
        try{
            int width=Math.max(1,target.getWidth()/downscale);
            int height=Math.max(1,target.getHeight()/downscale);
            int[] targetPos=new int[2];
            int[] sourcePos=new int[2];
            target.getLocationInWindow(targetPos);
            source.getLocationInWindow(sourcePos);
            Bitmap small=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
            Canvas canvas=new Canvas(small);
            canvas.scale(1f/downscale,1f/downscale);
            canvas.translate(targetPos[0]-sourcePos[0],targetPos[1]-sourcePos[1]);
            source.draw(canvas);
            // 采样失败（全透明）时返回 null，调用方退化成半透明底
            int probe=small.getPixel(width/2,height/2);
            if((probe>>>24)==0)return null;
            return blur(small,radius);
        }catch(Throwable ignored){
            return null;
        }
    }

    /** 两次横向 / 纵向盒式模糊，效果接近毛玻璃。 */
    static Bitmap blur(Bitmap source,int radius){
        int width=source.getWidth();
        int height=source.getHeight();
        int[] pixels=new int[width*height];
        source.getPixels(pixels,0,width,0,0,width,height);
        int[] temp=new int[width*height];
        boxPass(pixels,temp,width,height,radius,true);
        boxPass(temp,pixels,width,height,radius,false);
        Bitmap output=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        output.setPixels(pixels,0,width,0,0,width,height);
        return output;
    }

    private static void boxPass(int[] in,int[] out,int width,int height,int radius,boolean horizontal){
        for(int y=0;y<height;y++){
            for(int x=0;x<width;x++){
                int sumA=0,sumR=0,sumG=0,sumB=0,count=0;
                for(int k=-radius;k<=radius;k++){
                    int sx=horizontal?x+k:x;
                    int sy=horizontal?y:y+k;
                    if(sx<0||sy<0||sx>=width||sy>=height)continue;
                    int pixel=in[sy*width+sx];
                    sumA+=(pixel>>>24);
                    sumR+=(pixel>>16)&0xFF;
                    sumG+=(pixel>>8)&0xFF;
                    sumB+=pixel&0xFF;
                    count++;
                }
                if(count==0)count=1;
                out[y*width+x]=((sumA/count)<<24)|((sumR/count)<<16)|((sumG/count)<<8)|(sumB/count);
            }
        }
    }
}