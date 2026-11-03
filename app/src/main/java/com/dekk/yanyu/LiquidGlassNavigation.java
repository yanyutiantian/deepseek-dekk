package com.dekk.yanyu;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 独立液态玻璃导航：不复用 MiuixGlassBar。API 21+ 原生实现。
 *
 * <p>选中态不再是一块"贴上去"的背景，而是由 {@link TabLiquid} 统一绘制的
 * <b>水滴胶囊</b>：切换时前沿先到、后沿慢半拍跟上，中途拉长并略扁，最后带轻微回弹停稳；
 * 新选中的图标先缩再弹回，文字颜色渐变。
 */
final class LiquidGlassNavigation extends FrameLayout {
    private final LinearLayout items;
    private final Paint fill=new Paint(3),line=new Paint(3),highlight=new Paint(3);
    private final List<LinearLayout> tabs=new ArrayList<LinearLayout>();
    private final List<Runnable> actions=new ArrayList<Runnable>();
    private final TabLiquid liquid;
    private View backdrop; private Bitmap sample; private long sampleAt; private boolean dark; private int selected=-1;

    LiquidGlassNavigation(Context c){super(c);setWillNotDraw(false);setLayerType(View.LAYER_TYPE_SOFTWARE,null);
        setPadding(dp(8),dp(8),dp(8),dp(8));
        items=new LinearLayout(c);items.setGravity(Gravity.CENTER);items.setPadding(0,0,0,0);
        addView(items,new LayoutParams(-2,-2,Gravity.CENTER));setElevation(dp(18));
        liquid=new TabLiquid(this);
    }
    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    void backdrop(View v){backdrop=v;}
    void dark(boolean v){dark=v;fill.setColor(v?0xD91D2228:0xDDF7F9FC);line.setColor(v?0x80FFFFFF:0xE6FFFFFF);highlight.setColor(v?0x22FFFFFF:0x99FFFFFF);
        liquid.setColor(v?0x42FFFFFF:0xFFFFFFFF,22f);invalidate();}
    int add(int icon,String text,final Runnable action){
        final int index=tabs.size();LinearLayout tab=new LinearLayout(getContext());tab.setOrientation(LinearLayout.VERTICAL);tab.setGravity(Gravity.CENTER);tab.setPadding(dp(18),dp(7),dp(18),dp(7));tab.setMinimumWidth(dp(84));
        MiuixIcon image=new MiuixIcon(getContext()).kind(icon).color(ModuleTheme.secondary(dark));tab.addView(image,new LinearLayout.LayoutParams(dp(23),dp(23)));
        TextView label=new TextView(getContext());label.setText(text);label.setTextSize(11);label.setGravity(Gravity.CENTER);label.setIncludeFontPadding(false);label.setTextColor(ModuleTheme.secondary(dark));label.setPadding(0,dp(4),0,0);tab.addView(label,new LinearLayout.LayoutParams(-2,-2));
        tab.setOnClickListener(new OnClickListener(){public void onClick(View v){select(index);if(action!=null)action.run();}});
        tabs.add(tab);actions.add(action);items.addView(tab);liquid.addItem(tab);return index;
    }

    /**
     * 选中某一项：胶囊流过去，图标先缩再弹回，文字颜色渐变。
     * 首次调用（还没有选中项）直接落位，不做流动。
     */
    void select(int index){
        if(index<0||index>=tabs.size())return;
        final int old=selected;
        if(old==index)return;
        selected=index;
        liquid.setColor(dark?0x42FFFFFF:0xFFFFFFFF,22f);
        if(old<0){
            applyColors(index,-1);
            liquid.setImmediate(index);
        }else{
            applyColors(index,old);
            liquid.animateTo(index);
            TabLiquid.popIn(tabs.get(index).getChildAt(0));
        }
        invalidate();
    }

    /** 图标取色（文字交给渐变，避免两套动画打架）。 */
    private void applyColors(int index,int previous){
        for(int i=0;i<tabs.size();i++){
            LinearLayout t=tabs.get(i);
            View icon=t.getChildAt(0);
            if(icon instanceof MiuixIcon)((MiuixIcon)icon).color(i==index?ModuleTheme.ACCENT:ModuleTheme.secondary(dark));
            if(i!=previous||previous<0){ // 参与渐变的两个由 fadeTextColor 负责
                ((TextView)t.getChildAt(1)).setTextColor(i==index?ModuleTheme.ACCENT:ModuleTheme.secondary(dark));
            }
        }
        if(previous>=0&&previous<tabs.size()){
            TabLiquid.fadeTextColor((TextView)tabs.get(previous).getChildAt(1),
                    (TextView)tabs.get(index).getChildAt(1),
                    ModuleTheme.secondary(dark),ModuleTheme.ACCENT,240L);
        }
    }

    void refresh(){sample=null;invalidate();}

    protected void onDraw(Canvas c){super.onDraw(c);int w=getWidth(),h=getHeight();if(w<=0||h<=0)return;float r=h/2f;RectF b=new RectF(0,0,w,h);Path p=new Path();p.addRoundRect(b,r,r,Path.Direction.CW);int save=c.save();c.clipPath(p);Bitmap bmap=capture();if(bmap!=null)c.drawBitmap(bmap,null,b,fill);c.drawRect(b,fill);
        // 水滴胶囊：画在玻璃底之上、高光之下（高光叠上去更像玻璃）
        liquid.setItemsOffset(items.getLeft(),items.getTop());
        liquid.drawCapsule(c);
        highlight.setShader(new LinearGradient(0,0,0,h,highlight.getColor(),0x00FFFFFF,Shader.TileMode.CLAMP));c.drawRect(0,0,w,h/2f,highlight);c.restoreToCount(save);line.setStyle(Paint.Style.STROKE);line.setStrokeWidth(dp(1));c.drawRoundRect(b,r,r,line);line.setStyle(Paint.Style.FILL);}
    private Bitmap capture(){long now=System.currentTimeMillis();if(sample!=null&&now-sampleAt<180)return sample;if(backdrop==null)return null;sample=MiuixBlur.sample(backdrop,this,4,4);sampleAt=now;return sample;}
}