package com.dekk.yanyu;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.*;
import android.widget.*;

/**
 * Miuix（HyperOS）组件工厂：大标题导航、20dp 圆角分组卡片、带图标的列表行、
 * 灰色小节标题、状态卡与数据卡、填充按钮。
 *
 * <p>全部原生 View，Java 7 可编译；图标由 {@link MiuixIcon} 代码绘制。
 */
final class MiuixUi {
    /** 列表行：容器与主标题分开，便于调用方改标题颜色。 */
    static final class Row {
        final LinearLayout container;
        final TextView title;
        final TextView summary;
        Row(LinearLayout container,TextView title,TextView summary){
            this.container=container;this.title=title;this.summary=summary;
        }
        /** 更新行内说明文字（建行时带了 summary 才有效）。 */
        void setSummary(String text){ if(summary!=null)summary.setText(text); }
    }

    private MiuixUi(){}

    static int dp(Context c,float value){return (int)(value*c.getResources().getDisplayMetrics().density+0.5f);}
    static Typeface medium(){return Typeface.create("sans-serif-medium",Typeface.NORMAL);}

    static GradientDrawable shape(Context c,int color,float radius){
        GradientDrawable d=new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c,radius));
        return d;
    }

    static MiuixIcon icon(Activity a,int kind,int color){
        return new MiuixIcon(a).kind(kind).color(color);
    }

    /** 大标题导航：上方留白 + 34sp 标题，与 Miuix 一致。 */
    static View header(final Activity a,String title,boolean dark,final Runnable back){
        LinearLayout column=new LinearLayout(a);column.setOrientation(LinearLayout.VERTICAL);
        FrameLayout toolbar=new FrameLayout(a);
        column.addView(toolbar,new LinearLayout.LayoutParams(-1,dp(a,44)));
        if(back!=null){
            DekkSettingsPage.BackArrowView arrow=new DekkSettingsPage.BackArrowView(a);
            arrow.setColor(ModuleTheme.text(dark));
            arrow.setContentDescription("返回上一页");
            arrow.setBackground(new RippleDrawable(ColorStateList.valueOf(ModuleTheme.ripple(dark)),
                    null,shape(a,0xFF000000,22f)));
            toolbar.addView(arrow,new FrameLayout.LayoutParams(dp(a,38),dp(a,38),Gravity.START|Gravity.CENTER_VERTICAL));
            arrow.setOnClickListener(new View.OnClickListener(){public void onClick(View v){back.run();}});
        }
        TextView heading=new TextView(a);
        heading.setText(title);
        heading.setTextSize(32);
        heading.setTextColor(ModuleTheme.text(dark));
        heading.setTypeface(medium());
        heading.setIncludeFontPadding(false);
        heading.setLineSpacing(dp(a,4),1f);
        heading.setPadding(0,dp(a,8),0,dp(a,16));
        column.addView(heading,new LinearLayout.LayoutParams(-1,-2));
        return column;
    }

    /** 独立圆角卡片（20dp），内部行自带分隔线。 */
    static LinearLayout card(Activity a,boolean dark){
        LinearLayout card=new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(shape(a,ModuleTheme.surface(dark),20f));
        // 卡片自己提供左右内边距，行组件不再各自加，避免有的行贴边有的行不贴
        card.setPadding(dp(a,18),dp(a,5),dp(a,18),dp(a,5));
        return card;
    }

    /** 分组小标题：13sp 灰色，上下留白。 */
    static TextView section(Activity a,boolean dark,String title){
        TextView view=new TextView(a);
        view.setText(title);
        view.setTextSize(13);
        view.setTextColor(ModuleTheme.secondary(dark));
        view.setIncludeFontPadding(false);
        view.setPadding(0,dp(a,22),0,dp(a,10));
        return view;
    }

    static View divider(Activity a,boolean dark,int insetDp){
        View line=new View(a);
        line.setBackgroundColor(ModuleTheme.divider(dark));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,Math.max(1,dp(a,0.5f)));
        lp.leftMargin=dp(a,insetDp);lp.rightMargin=dp(a,insetDp);
        line.setLayoutParams(lp);
        return line;
    }

    static Row row(Activity a,LinearLayout container,boolean dark,String title,String summary,boolean chevron,View.OnClickListener click){
        return row(a,container,dark,-1,title,summary,chevron,click);
    }

    /**
     * 列表行。iconKind 为 -1 表示不带图标；summary 为空表示单行；chevron 控制右侧箭头。
     * 行内自带分隔线（卡片首行不加），整行可点。
     */
    static Row row(Activity a,LinearLayout container,boolean dark,int iconKind,String title,String summary,boolean chevron,View.OnClickListener click){
        if(container.getChildCount()>0)container.addView(divider(a,dark,0));
        LinearLayout line=new LinearLayout(a);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0,dp(a,13),0,dp(a,13));
        line.setMinimumHeight(dp(a,52));
        if(iconKind>=0){
            MiuixIcon icon=icon(a,iconKind,ModuleTheme.text(dark));
            LinearLayout.LayoutParams iconParams=new LinearLayout.LayoutParams(dp(a,23),dp(a,23));
            iconParams.rightMargin=dp(a,14);
            line.addView(icon,iconParams);
        }
        LinearLayout texts=new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView heading=new TextView(a);
        heading.setText(title);
        heading.setTextSize(17);
        heading.setTextColor(ModuleTheme.text(dark));
        heading.setIncludeFontPadding(false);
        texts.addView(heading);
        TextView summaryView=null;
        if(summary!=null&&summary.length()>0){
            TextView note=new TextView(a);
            note.setText(summary);
            note.setTextSize(13);
            note.setTextColor(ModuleTheme.secondary(dark));
            note.setIncludeFontPadding(false);
            note.setLineSpacing(dp(a,3),1f);
            note.setPadding(0,dp(a,5),0,0);
            texts.addView(note);
            summaryView=note;
        }
        line.addView(texts,new LinearLayout.LayoutParams(0,-2,1f));
        if(chevron){
            TextView arrow=new TextView(a);
            arrow.setText("›");
            arrow.setTextSize(20);
            arrow.setTextColor(ModuleTheme.secondary(dark));
            arrow.setIncludeFontPadding(false);
            arrow.setPadding(dp(a,12),0,0,0);
            line.addView(arrow);
        }
        container.addView(line,new LinearLayout.LayoutParams(-1,-2));
        if(click!=null){
            line.setClickable(true);
            line.setBackground(new RippleDrawable(ColorStateList.valueOf(ModuleTheme.ripple(dark)),
                    null,shape(a,0xFF000000,20f)));
            line.setOnClickListener(click);
            // 整行按下时轻微回缩：让"点到了"这件事有反馈
            Motion.pressable(line,Motion.ROW_PRESS_SCALE);
        }
        return new Row(line,heading,summaryView);
    }

    /** 卡片内说明文字（灰色多行）。 */
    static TextView caption(Activity a,LinearLayout card,boolean dark,String value){
        TextView view=new TextView(a);
        view.setText(value);
        view.setTextSize(13);
        view.setTextColor(ModuleTheme.secondary(dark));
        view.setIncludeFontPadding(false);
        view.setLineSpacing(dp(a,4),1f);
        view.setPadding(0,dp(a,6),0,dp(a,16));
        card.addView(view,new LinearLayout.LayoutParams(-1,-2));
        return view;
    }

    /** 列表行 + 右侧开关；整行可点。 */
    static M3SwitchView switchRow(Activity a,LinearLayout card,boolean dark,String title,boolean checked,M3SwitchView.OnCheckedChangeListener listener){
        return switchRow(a,card,dark,-1,title,checked,listener);
    }

    static M3SwitchView switchRow(Activity a,LinearLayout card,boolean dark,int iconKind,String title,boolean checked,M3SwitchView.OnCheckedChangeListener listener){
        MiuixUi.Row row=row(a,card,dark,iconKind,title,"",false,null);
        final LinearLayout line=row.container;
        final M3SwitchView toggle=new M3SwitchView(a);
        toggle.setContentDescription(title);
        toggle.setTrackColors(ModuleTheme.ACCENT,dark?0xFF4A4A4A:0xFFE2E2E2);
        toggle.setChecked(checked);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(a,46),dp(a,28));
        lp.leftMargin=dp(a,12);
        line.addView(toggle,lp);
        line.setClickable(true);
        line.setBackground(new RippleDrawable(ColorStateList.valueOf(ModuleTheme.ripple(dark)),
                null,shape(a,0xFF000000,20f)));
        line.setOnClickListener(new View.OnClickListener(){public void onClick(View v){toggle.performClick();}});
        // 整行按下时轻微回缩，跟点开关本体一样有反馈
        Motion.pressable(line,Motion.ROW_PRESS_SCALE);
        if(listener!=null)toggle.setOnCheckedChangeListener(listener);
        return toggle;
    }

    /** 首页状态卡：浅绿底 + 右侧对勾；未激活时用中性底色。 */
    static View statusCard(Activity a,boolean dark,String title,String subtitle,boolean ok){
        LinearLayout card=new LinearLayout(a);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(shape(a,ok?(dark?0xFF15271B:0xFFDDF5E4):ModuleTheme.surface(dark),20f));
        card.setPadding(dp(a,20),dp(a,20),dp(a,20),dp(a,20));
        LinearLayout texts=new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView heading=new TextView(a);
        heading.setText(title);
        heading.setTextSize(20);
        heading.setTextColor(ok?(dark?0xFF7DE8A4:0xFF0E7A38):ModuleTheme.text(dark));
        heading.setTypeface(medium());
        heading.setIncludeFontPadding(false);
        texts.addView(heading);
        TextView note=new TextView(a);
        note.setText(subtitle);
        note.setTextSize(13);
        note.setTextColor(ok?(dark?0xFF6FBF8C:0xFF3F9463):ModuleTheme.secondary(dark));
        note.setIncludeFontPadding(false);
        note.setPadding(0,dp(a,6),0,0);
        texts.addView(note);
        card.addView(texts,new LinearLayout.LayoutParams(0,-2,1f));
        FrameLayout badge=new FrameLayout(a);
        View ring=new View(a);
        ring.setBackground(shape(a,ok?(dark?0xFF1F3B29:0xFFC4ECD2):(dark?0xFF2A2A2A:0xFFEDEDED),40f));
        badge.addView(ring,new FrameLayout.LayoutParams(dp(a,56),dp(a,56)));
        MiuixIcon check=icon(a,MiuixIcon.CHECK,ok?(dark?0xFF5BD98D:0xFF1FA855):ModuleTheme.secondary(dark));
        FrameLayout.LayoutParams checkParams=new FrameLayout.LayoutParams(dp(a,30),dp(a,30),Gravity.CENTER);
        badge.addView(check,checkParams);
        card.addView(badge,new LinearLayout.LayoutParams(-2,-2));
        return card;
    }

    /** 首页数据卡：小标题 + 大数字。 */
    static View statCard(Activity a,boolean dark,String label,String value){
        LinearLayout box=new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(shape(a,ModuleTheme.surface(dark),20f));
        box.setPadding(dp(a,18),dp(a,16),dp(a,18),dp(a,16));
        box.setMinimumHeight(dp(a,86));
        TextView caption=new TextView(a);
        caption.setText(label);
        caption.setTextSize(13);
        caption.setTextColor(ModuleTheme.secondary(dark));
        caption.setIncludeFontPadding(false);
        box.addView(caption);
        TextView number=new TextView(a);
        number.setText(value);
        number.setTextSize(28);
        number.setTextColor(ModuleTheme.text(dark));
        number.setTypeface(medium());
        number.setIncludeFontPadding(false);
        number.setPadding(0,dp(a,8),0,0);
        box.addView(number);
        return box;
    }

    /** Miuix 分段标签页：灰色轨道 + 会流动的水滴胶囊。 */
    static final class Tabs {
        final LinearLayout view;
        final TextView[] items;
        final TabLiquid liquid;
        int current;
        Tabs(LinearLayout view,TextView[] items,TabLiquid liquid,int current){
            this.view=view;this.items=items;this.liquid=liquid;this.current=current;
        }
    }

    static Tabs tabs(Activity a,final boolean dark,String[] labels,int selected,final TabListener listener){
        return tabs(a,dark,labels,selected,listener,null);
    }

    /**
     * 分段标签。切换时：
     * 选中胶囊像水滴一样流过去（前沿先到、后沿慢半拍），新页签的字先缩再弹回，
     * 文字颜色渐变；若给了 {@code content}，内容还会沿点击方向轻推入。
     */
    static Tabs tabs(Activity a,final boolean dark,String[] labels,int selected,final TabListener listener,
                     final View content){
        final TabLiquid.LiquidTrack track=new TabLiquid.LiquidTrack(a);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setBackground(shape(a,dark?0xFF242424:0xFFEFEFEF,15f));
        track.setPadding(dp(a,4),dp(a,4),dp(a,4),dp(a,4));

        final TextView[] items=new TextView[labels.length];
        final TabLiquid liquid=new TabLiquid(track);
        // 胶囊在轨道内：颜色沿用原来的"选中白块"，夜间模式用深灰
        liquid.setColor(dark?0xFF3A3A3A:0xFFFFFFFF,12f);
        track.bind(liquid);
        final Tabs tabs=new Tabs(track,items,liquid,selected);

        for(int i=0;i<labels.length;i++){
            final int index=i;
            TextView item=new TextView(a);
            item.setText(labels[i]);
            item.setTextSize(14);
            item.setGravity(Gravity.CENTER);
            item.setIncludeFontPadding(false);
            item.setMinHeight(dp(a,38));
            LinearLayout.LayoutParams itemParams=new LinearLayout.LayoutParams(0,-2,1f);
            track.addView(item,itemParams);
            items[i]=item;
            liquid.addItem(item);
            // 点击时必须自己重画选中态，否则白块会一直停在第一个页签
            item.setClickable(true);
            item.setOnClickListener(new View.OnClickListener(){
                public void onClick(View v){
                    int old=tabs.current;
                    if(old==index)return;
                    tabs.current=index;
                    applySelection(dark,items,index,old);
                    liquid.animateTo(index);
                    TabLiquid.popIn(items[index]);
                    if(content!=null)FeedUi.pushIn(content,index>old?1:-1);
                    if(listener!=null)listener.onSelected(index);
                }
            });
        }
        applySelection(dark,items,selected,-1);
        liquid.setImmediate(selected);
        return tabs;
    }
/**
     * 立即把选中态放到某一项上（不动画）。
     *
     * <p>用途：分段标签切换会重建整个页面，新页面里的胶囊默认直接落在当前项上，
     * 看不到"流动"。所以先把选中态放到**上一个页签**的位置，隔一帧再 {@link #select}
     * 到当前项，就能看到胶囊从旧位置流过来。
     */
    static void jumpTo(Tabs tabs,boolean dark,int index){
        if(tabs==null)return;
        if(index<0||index>=tabs.items.length)return;
        tabs.current=index;
        applySelection(dark,tabs.items,index,-1);
        tabs.liquid.setImmediate(index);
    }

    /** 外部切换选中项（带动画）。 */
    static void select(Tabs tabs,boolean dark,int index){
        if(tabs==null)return;
        if(index<0||index>=tabs.items.length)return;
        int old=tabs.current;
        if(old==index)return;
        tabs.current=index;
        applySelection(dark,tabs.items,index,old);
        tabs.liquid.animateTo(index);
        TabLiquid.popIn(tabs.items[index]);
    }

    /** 选中样式：字体加粗 + 主文字色，其余次要色（立即生效）。 */
    static void applySelection(boolean dark,TextView[] items,int index){
        applySelection(dark,items,index,-1);
    }

    /**
     * 选中样式。给了 {@code previous} 时，只对"旧选中 / 新选中"两项做颜色渐变，
     * 其余项直接落色——这样切换时能看到颜色流动，而不是硬切。
     */
    static void applySelection(boolean dark,TextView[] items,int index,int previous){
        for(int i=0;i<items.length;i++){
            items[i].setTypeface(i==index?medium():Typeface.DEFAULT);
            if(previous<0||previous==index||(i!=previous&&i!=index)){
                items[i].setTextColor(i==index?ModuleTheme.text(dark):ModuleTheme.secondary(dark));
            }
        }
        if(previous>=0 && previous!=index && previous<items.length){
            TabLiquid.fadeTextColor(items[previous],items[index],
                    ModuleTheme.secondary(dark),ModuleTheme.text(dark),240L);
        }
    }

    interface TabListener { void onSelected(int index); }

    /** Miuix 搜索框：灰色圆角底 + 放大镜，返回可输入的 EditText。 */
    static EditText searchField(Activity a,boolean dark,String hint){
        LinearLayout box=new LinearLayout(a);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(shape(a,ModuleTheme.field(dark),14f));
        box.setPadding(dp(a,14),0,dp(a,14),0);
        box.setMinimumHeight(dp(a,46));
        MiuixIcon icon=new MiuixIcon(a).kind(MiuixIcon.SEARCH).color(ModuleTheme.secondary(dark));
        box.addView(icon,new LinearLayout.LayoutParams(dp(a,20),dp(a,20)));
        EditText input=new EditText(a);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setTextSize(15);
        input.setTextColor(ModuleTheme.text(dark));
        input.setHintTextColor(ModuleTheme.secondary(dark));
        input.setIncludeFontPadding(false);
        input.setBackground(null);
        LinearLayout.LayoutParams inputParams=new LinearLayout.LayoutParams(0,-2,1f);
        inputParams.leftMargin=dp(a,10);
        box.addView(input,inputParams);
        box.setTag(input);
        return input;
    }

    /** 列表卡片里的小按钮：filled=蓝底白字，否则浅底蓝字。 */
    static TextView smallButton(Activity a,boolean dark,String text,boolean filled,final Runnable click){
        TextView view=new TextView(a);
        view.setText(text);
        view.setTextSize(14);
        view.setTextColor(filled?0xFFFFFFFF:ModuleTheme.ACCENT);
        view.setGravity(Gravity.CENTER);
        view.setIncludeFontPadding(false);
        view.setPadding(dp(a,18),0,dp(a,18),0);
        view.setMinimumHeight(dp(a,38));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(filled?0x33FFFFFF:ModuleTheme.ripple(dark)),
                shape(a,filled?ModuleTheme.ACCENT:(dark?0xFF2A3243:0xFFEBF1FF),12f),shape(a,0xFF000000,12f)));
        if(click!=null)view.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v){if(v.isEnabled())click.run();}
        });
        // 按压反馈：按下轻微缩小，松手弹簧回位（与涟漪叠加，手感更"实"）
        Motion.pressable(view);
        return view;
    }
    /** Miuix 填充按钮：整行、52dp、圆角 14dp。 */
    static TextView button(Activity a,String text,final Runnable click){
        TextView view=new TextView(a);
        view.setText(text);
        view.setTextSize(16);
        view.setTextColor(0xFFFFFFFF);
        view.setGravity(Gravity.CENTER);
        view.setIncludeFontPadding(false);
        view.setMinimumHeight(dp(a,52));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
                shape(a,ModuleTheme.ACCENT,14f),shape(a,0xFF000000,14f)));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(a,52));
        lp.topMargin=dp(a,16);
        view.setLayoutParams(lp);
        if(click!=null)view.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v){if(v.isEnabled())click.run();}
        });
        Motion.pressable(view);
        return view;
    }
    /** Miuix 次级按钮：浅底、蓝色文字。 */
    static TextView textButton(Activity a,boolean dark,String text,final Runnable click){
        TextView view=new TextView(a);
        view.setText(text);
        view.setTextSize(15);
        view.setTextColor(ModuleTheme.ACCENT);
        view.setGravity(Gravity.CENTER);
        view.setIncludeFontPadding(false);
        view.setMinimumHeight(dp(a,44));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(ModuleTheme.ripple(dark)),
                shape(a,dark?0xFF262626:0xFFF0F0F0,12f),shape(a,0xFF000000,12f)));
        if(click!=null)view.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v){if(v.isEnabled())click.run();}
});
        Motion.pressable(view);
        return view;
    }
}