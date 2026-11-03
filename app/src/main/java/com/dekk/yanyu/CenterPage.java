package com.dekk.yanyu;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

/**
 * 模块内所有二级页面的统一容器，按 Miuix（HyperOS）规范重做：
 * 大标题导航、浅灰页面底色、独立圆角分组卡片、带分隔线的列表行。
 *
 * <p>页面层级、返回行为和对外 API 保持不变，只替换视觉实现。
 */
public final class CenterPage extends FrameLayout {
    private static final int ID=0x00DE0003;
    public final Activity activity;
    public final LinearLayout body;
    public final boolean night;
    public final int text, secondary;
    /** 被嵌到标签页容器里时置 true：此时没有父页面，但仍然算存活。 */
    public boolean embedded;
    /** 跳过"进入"动画（同层切换用：页签之间切换是层内变化，不是进新页）。 */
    private boolean noEnterAnimation;
    private Runnable back;
    private CenterPage previousPage;
    private String navigationKey;
    public CenterPage navigationKey(String key){navigationKey=key;return this;}

    static CenterPage top(Activity a){
        ViewGroup root=(ViewGroup)a.findViewById(android.R.id.content);
        for(int i=root.getChildCount()-1;i>=0;i--){
            View v=root.getChildAt(i);
            if(v instanceof CenterPage && v.getVisibility()==View.VISIBLE)return (CenterPage)v;
        }
        return null;
    }
    public static void clear(Activity a){
        ViewGroup root=(ViewGroup)a.findViewById(android.R.id.content);
        for(int i=root.getChildCount()-1;i>=0;i--){
            View v=root.getChildAt(i);
            if(v instanceof CenterPage){((CenterPage)v).previousPage=null;root.removeView(v);}
        }
    }
    public CenterPage(final Activity a,String title,final Runnable previous){
        super(a);activity=a;back=previous;setId(ID);
        night=ModuleTheme.dark(a);
        text=ModuleTheme.text(night);secondary=ModuleTheme.secondary(night);
        setBackgroundColor(ModuleTheme.background(night));
        setClickable(true);setFocusableInTouchMode(true);
        int pad=MiuixUi.dp(a,ModuleTheme.PAGE_PADDING);
        LinearLayout column=new LinearLayout(a);column.setOrientation(1);
        column.setPadding(pad,0,pad,0);
        addView(column,new FrameLayout.LayoutParams(-1,-1));
        ScrollView scroll=new ScrollView(a);
        scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        column.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
        LinearLayout content=new LinearLayout(a);content.setOrientation(1);
        scroll.addView(content);
        content.addView(MiuixUi.header(a,title,night,new Runnable(){public void run(){goBack();}}));
        body=new LinearLayout(a);body.setOrientation(1);
        body.setPadding(0,0,0,MiuixUi.dp(a,32));
        content.addView(body,new LinearLayout.LayoutParams(-1,-2));
        setOnApplyWindowInsetsListener(new OnApplyWindowInsetsListener(){public WindowInsets onApplyWindowInsets(View v,WindowInsets i){
            int[] pos=new int[2];v.getLocationInWindow(pos);
            v.setPadding(i.getSystemWindowInsetLeft(),Math.max(0,i.getSystemWindowInsetTop()-pos[1]),i.getSystemWindowInsetRight(),Math.max(0,pos[1]+v.getHeight()-(v.getRootView().getHeight()-i.getSystemWindowInsetBottom())));return i;
        }});
    }

    public void show(){
        ViewGroup root=(ViewGroup)activity.findViewById(android.R.id.content);
        CenterPage current=top(activity);
        if(current==this)return;
        //列表保存或删除后重新打开时替换已有路由，避免旧编辑页留在返回路径里。
        CenterPage target=current;
        while(target!=null && (navigationKey==null || !navigationKey.equals(target.navigationKey)))target=target.previousPage;
        if(target!=null){
            previousPage=target.previousPage;
            while(current!=null){
                CenterPage next=current.previousPage;
                root.removeView(current);current.previousPage=null;
                if(current==target)break;
                current=next;
            }
        }else{
            previousPage=current;
            if(current!=null)current.setVisibility(View.GONE);
        }
        root.addView(this,new ViewGroup.LayoutParams(-1,-1));
        if(noEnterAnimation){
            // 同一页内的页签切换：内容切换的是"层内的东西"，不该再表现为"进了新一层"，
            // 否则会和 Tab 胶囊流动 + 内容推入两层动画叠在一起，看起来很乱。
            setAlpha(1f);setTranslationX(0f);
        }else{
            // 进入：从右侧 18dp 处轻轻推进 + 淡入（表达"进了新一层"，不做大幅位移）
            setAlpha(0f);
            setTranslationX(MiuixUi.dp(activity,18));
            animate().alpha(1f).translationX(0f)
                    .setDuration(260L)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f))
                    .start();
        }
        requestFocus();requestApplyInsets();
    }

    /** 跳过"进入"动画（同层切换用，例如人设市场里的页签）。 */
    public CenterPage noEnterAnimation(){noEnterAnimation=true;return this;}
    /** 隐藏的上一层仍存活，允许正在加载的列表完成更新。 */
    public boolean active(){return embedded||getParent()!=null;}
    public void goBack(){
        if(top(activity)!=this)return;
        final CenterPage previous=previousPage;previousPage=null;
        // 上层页先露出来并从左侧"接住"（轻微位移，避免硬切）
        if(previous!=null && previous.active()){
            previous.setVisibility(View.VISIBLE);previous.bringToFront();
            previous.setAlpha(0.85f);
            previous.setTranslationX(-MiuixUi.dp(activity,10));
            previous.animate().alpha(1f).translationX(0f)
                    .setDuration(240L)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f))
                    .start();
        }
        // 当前页向右退出 + 淡出，动画结束后才真正移除（避免中间那一下白屏）
        setClickable(false);
        animate().alpha(0f).translationX(MiuixUi.dp(activity,18))
                .setDuration(220L)
                .setInterpolator(new android.view.animation.AccelerateInterpolator(1.2f))
                .withEndAction(new Runnable(){public void run(){
                    android.view.ViewParent parent=getParent();
                    if(parent instanceof ViewGroup)((ViewGroup)parent).removeView(CenterPage.this);
                    if(previous!=null && previous.active()){
                        previous.requestFocus();previous.requestApplyInsets();
                    }else if(back!=null)back.run();else DekkSettingsPage.show();
                }}).start();
    }
    public static boolean handleBack(Activity a){CenterPage page=top(a);if(page==null)return false;page.goBack();return true;}
    public boolean dispatchKeyEvent(KeyEvent e){if(e.getKeyCode()==KeyEvent.KEYCODE_BACK){if(e.getAction()==KeyEvent.ACTION_UP && !e.isCanceled())goBack();return true;}return super.dispatchKeyEvent(e);}

    public int dp(int n){return MiuixUi.dp(activity,n);}
    public GradientDrawable shape(int color,int radius){return MiuixUi.shape(activity,color,radius);}
    public TextView label(String s,int size){
        TextView t=new TextView(activity);
        t.setText(s);t.setTextSize(size);t.setTextColor(text);
        t.setIncludeFontPadding(false);t.setLineSpacing(dp(3),1f);
        return t;
    }

    /** 分组标题。 */
    public void section(String s){body.addView(MiuixUi.section(activity,night,s));}

    /** 独立圆角卡片；内部行由 row/caption/field 负责留白与分隔线。 */
    public LinearLayout card(){
        LinearLayout c=MiuixUi.card(activity,night);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.bottomMargin=dp(16);
        body.addView(c,lp);
        return c;
    }

    /**
     * 列表行。标题里原有的「›」会被转为右侧箭头，避免出现两个箭头。
     *
     * @return 标题 TextView，调用方仍可改颜色或字号。
     */
    public TextView row(LinearLayout c,String title,final Runnable click){
        String value=title==null?"":title.trim();
        boolean chevron=false;
        while(value.endsWith("›")){
            chevron=true;
            value=value.substring(0,value.length()-1).trim();
        }
        MiuixUi.Row row=MiuixUi.row(activity,c,night,value,"",chevron,click==null?null:new OnClickListener(){
            public void onClick(View v){click.run();}
        });
        return row.title;
    }

    /** 带图标的列表行（标题里原有的「›」转为右侧箭头）。 */
    public TextView rowIcon(LinearLayout c,int iconKind,String title,final Runnable click){
        String value=title==null?"":title.trim();
        boolean chevron=false;
        while(value.endsWith("›")){
            chevron=true;
            value=value.substring(0,value.length()-1).trim();
        }
        MiuixUi.Row row=MiuixUi.row(activity,c,night,iconKind,value,"",chevron,click==null?null:new OnClickListener(){
            public void onClick(View v){click.run();}
        });
        return row.title;
    }

    /** 卡片内的多行灰色说明。 */
    public TextView caption(LinearLayout card, String value){
        return MiuixUi.caption(activity,card,night,value);
    }

    public EditText field(LinearLayout c,String hint,int type,boolean multi){
        final EditText e=new EditText(activity);
        TextView cap=label(hint,13);
        cap.setTextColor(secondary);
        cap.setPadding(dp(16),dp(18),dp(16),dp(8));
        c.addView(cap);
        e.setTextColor(text);e.setHintTextColor(secondary);e.setHint(hint);e.setTextSize(16);
        e.setInputType(type);e.setSingleLine(!multi);
        e.setIncludeFontPadding(false);
        e.setBackground(shape(ModuleTheme.field(night),12));
        e.setPadding(dp(16),dp(16),dp(16),dp(16));
        if(multi){
            e.setGravity(Gravity.TOP);e.setHorizontallyScrolling(false);e.setVerticalScrollBarEnabled(true);
            e.setMovementMethod(android.text.method.ScrollingMovementMethod.getInstance());
            e.setOnTouchListener(new OnTouchListener(){
                float lastY;
                public boolean onTouch(View v,MotionEvent event){
                    int action=event.getActionMasked();
                    if(action==MotionEvent.ACTION_DOWN){lastY=event.getY();v.getParent().requestDisallowInterceptTouchEvent(true);}
                    else if(action==MotionEvent.ACTION_MOVE){float dy=lastY-event.getY();lastY=event.getY();v.getParent().requestDisallowInterceptTouchEvent(v.canScrollVertically(dy>0?1:-1));}
                    else if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL)v.getParent().requestDisallowInterceptTouchEvent(false);
                    return false;
                }
            });
        }
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(multi?200:56));
        lp.bottomMargin=dp(18);
        c.addView(e,lp);
        return e;
    }

    /** 页面主操作按钮。 */
    public TextView action(String title,final Runnable click){
        TextView t=MiuixUi.button(activity,title,click);
        body.addView(t,new LinearLayout.LayoutParams(-1,dp(52)));
        return t;
    }

    public void message(String s){Toast.makeText(activity,s,Toast.LENGTH_LONG).show();}
}