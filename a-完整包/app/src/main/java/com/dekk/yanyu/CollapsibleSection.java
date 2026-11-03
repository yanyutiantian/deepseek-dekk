package com.dekk.yanyu;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 可折叠区块：默认收起，点标题展开 / 收起（详情页、创作者主页共用）。 */
final class CollapsibleSection {
    /** root 放进容器，内容加在 content 里。 */
    static final class Result {
        LinearLayout root;
        LinearLayout content;
    }

    private CollapsibleSection() { }

    static Result add(final Activity activity,final CenterPage page,
                      LinearLayout container,final String label){
        return add(activity,page,container,label,true,-1);
    }

    static Result add(final Activity activity,final CenterPage page,
                      LinearLayout container,final String label,final int iconKind){
        return add(activity,page,container,label,true,iconKind);
    }

    /** @param startOpen true=一开始就展开（需要先看到内容时用） */
    static Result add(final Activity activity,final CenterPage page,
                      LinearLayout container,final String label,final boolean startOpen){
        return add(activity,page,container,label,startOpen,-1);
    }

    static Result add(final Activity activity,final CenterPage page,
                      LinearLayout container,final String label,final boolean startOpen,final int iconKind){
        final Result result=new Result();
        final LinearLayout root=new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);

        // 标题也是一行 Miuix 列表行（可带图标），右侧放展开箭头
        final MiuixUi.Row row=iconKind>=0
                ? MiuixUi.row(activity,root,page.night,iconKind,label,"",false,null)
                : MiuixUi.row(activity,root,page.night,label,"",false,null);
        final TextView header=row.title;
        final TextView arrow=new TextView(activity);
        arrow.setText(startOpen?"\u25be":"\u25b8");
        arrow.setTextSize(14);
        arrow.setTextColor(ModuleTheme.secondary(page.night));
        arrow.setIncludeFontPadding(false);
        arrow.setPadding(page.dp(10),0,0,0);
        row.container.addView(arrow);

        final LinearLayout content=new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0,0,0,page.dp(14));
        content.setVisibility(startOpen?View.VISIBLE:View.GONE);
        root.addView(content,new LinearLayout.LayoutParams(-1,-2));

        row.container.setClickable(true);
        row.container.setOnClickListener(new View.OnClickListener(){public void onClick(View v){
            boolean open=content.getVisibility()==View.VISIBLE;
            content.setVisibility(open?View.GONE:View.VISIBLE);
            arrow.setText(open?"\u25b8":"\u25be");
        }});
        container.addView(root,new LinearLayout.LayoutParams(-1,-2));
        result.root=root;
        result.content=content;
        return result;
    }
}
