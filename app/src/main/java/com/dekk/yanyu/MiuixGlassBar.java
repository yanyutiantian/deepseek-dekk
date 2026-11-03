package com.dekk.yanyu;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * 液态玻璃悬浮导航胶囊。
 *
 * <p>背景 = 把下方内容降采样后盒式模糊 + 半透明玻璃色 + 顶部高光 + 高光描边，
 * 外层用 outline 投影，滚动时隔 150ms 重新采样。采样失败时退化为半透明底，不会崩。
 *
 * <p>Android 没有 View 级背景模糊 API（RenderEffect 只能模糊自身，背景模糊是窗口级的），
 * 所以这里自己采样：只画内容容器（不含本胶囊），避免越滚越白。
 */
public final class MiuixGlassBar extends FrameLayout {
    private static final int DOWNSCALE = 6;
    private static final long REFRESH_MS = 150;

    private final List<Tab> tabs = new ArrayList<Tab>();
    private final List<Runnable> actions = new ArrayList<Runnable>();
    private final LinearLayout row;
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF bounds = new RectF();
    private View backdrop;
    private Bitmap blurred;
    private long capturedAt;
    private boolean dark;
    private int selected;

    private static final class Tab {
        LinearLayout view;
        MiuixIcon icon;
        TextView label;
    }

    public MiuixGlassBar(Context context){
        super(context);
        setWillNotDraw(false);
        tintPaint.setStyle(Paint.Style.FILL);
        sheenPaint.setStyle(Paint.Style.FILL);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1));
        setOutlineProvider(new ViewOutlineProvider(){
            @Override public void getOutline(View view, Outline outline){
                outline.setRoundRect(0,0,view.getWidth(),view.getHeight(),view.getHeight()/2f);
            }
        });
        setClipToOutline(false);
        row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        int pad = dp(6);
        row.setPadding(dp(8), dp(7), dp(8), dp(7));
        setPadding(0, 0, 0, 0);
        setBackgroundColor(android.graphics.Color.TRANSPARENT);
        addView(row, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        setMinimumHeight(dp(62));
        setDark(false);
    }

    private int dp(float value){
        return (int)(value*getResources().getDisplayMetrics().density+0.5f);
    }

    /** 指定被模糊的内容容器（通常是页面里的 ScrollView）。 */
    public void backdrop(View view){backdrop=view;}

    public void setDark(boolean value){
        dark=value;
        tintPaint.setColor(value?0xC92A2A2A:0xD9F7F8FA);
        borderPaint.setColor(value?0x66FFFFFF:0xCCFFFFFF);
        sheenPaint.setShader(new LinearGradient(0,0,0,dp(34),
                value?0x26FFFFFF:0x8CFFFFFF,0x00FFFFFF,Shader.TileMode.CLAMP));
        setElevation(dp(10));
        invalidate();
    }

    /** 内容滚动或切换后调用；内部有节流。 */
    public void refresh(){
        blurred=null;
        invalidate();
    }

    public int addTab(int iconKind,String text,Runnable click){
        final Tab tab=new Tab();
        tab.view=new LinearLayout(getContext());
        tab.view.setOrientation(LinearLayout.VERTICAL);
        tab.view.setGravity(Gravity.CENTER);
        tab.view.setPadding(dp(18),dp(6),dp(18),dp(6));
        tab.view.setMinimumWidth(dp(84));
        tab.icon=new MiuixIcon(getContext()).kind(iconKind).color(ModuleTheme.secondary(dark));
        tab.view.addView(tab.icon,new LinearLayout.LayoutParams(dp(22),dp(22)));
        tab.label=new TextView(getContext());
        tab.label.setText(text);
        tab.label.setTextSize(11);
        tab.label.setIncludeFontPadding(false);
        tab.label.setPadding(0,dp(3),0,0);
        tab.view.addView(tab.label);
        final int index=tabs.size();
        tab.view.setClickable(true);
        tab.view.setOnClickListener(new OnClickListener(){
            public void onClick(View v){
                select(index);
                Runnable action=actions.get(index);
                if(action!=null)action.run();
            }
        });
        tabs.add(tab);
        actions.add(click);
        row.addView(tab.view,new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT,LayoutParams.WRAP_CONTENT));
        return index;
    }

    public void select(int index){
        selected=index;
        for(int i=0;i<tabs.size();i++){
            Tab tab=tabs.get(i);
            boolean active=i==index;
            tab.icon.color(active?ModuleTheme.ACCENT:ModuleTheme.secondary(dark));
            tab.label.setTextColor(active?ModuleTheme.ACCENT:ModuleTheme.secondary(dark));
            tab.view.setBackground(active
                    ? MiuixUi.shape(getContext(),dark?0x42FFFFFF:0xFFFFFFFF,18f)
                    : null);
        }
    }

    @Override protected void onDraw(Canvas canvas){
        int width=getWidth();
        int height=getHeight();
        if(width<=0||height<=0)return;
        float radius=height/2f;
        bounds.set(0,0,width,height);
        clip.reset();
        clip.addRoundRect(bounds,radius,radius,Path.Direction.CW);
        int saved=canvas.save();
        canvas.clipPath(clip);
        Bitmap bitmap=sample(width,height);
        if(bitmap!=null){
            imagePaint.setAlpha(255);
            canvas.drawBitmap(bitmap,null,bounds,imagePaint);
        }
        canvas.drawRect(bounds,tintPaint);
        if(sheenPaint.getShader()!=null){
            canvas.drawRect(bounds,sheenPaint);
        }
        canvas.restoreToCount(saved);
        borderPaint.setColor(dark?0x40FFFFFF:0x99FFFFFF);
        canvas.drawRoundRect(bounds,radius,radius,borderPaint);
    }

    private Bitmap sample(int width,int height){
        long now=System.currentTimeMillis();
        if(blurred!=null&&now-capturedAt<REFRESH_MS)return blurred;
        if(backdrop==null)return null;
        Bitmap result=MiuixBlur.sample(backdrop,this,DOWNSCALE,2);
        capturedAt=now;
        if(result!=null){
            blurred=result;
        }
        return blurred;
    }
}