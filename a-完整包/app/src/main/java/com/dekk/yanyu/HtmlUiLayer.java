package com.dekk.yanyu;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

/**
 * HTML 渲染层的容器。
 *
 * <p>全屏模式下渲染层会铺满整屏（视觉上「HTML 在输入框下面」），
 * 底部输入区那一条在 {@link #dispatchTouchEvent} 里**透传给宿主的视图**，
 * 所以输入框既不会被挡住、也能正常点到。
 */
final class HtmlUiLayer extends FrameLayout {

    /** 宿主的视图（content 下除我们之外的那个），触摸透传给它。 */
    private final View host;
    /** 底部透传区域高度（像素），0 表示不透传。 */
    private final int passThrough;

    HtmlUiLayer(Context context, View web, View host, int passThrough) {
        super(context);
        this.host = host;
        this.passThrough = passThrough < 0 ? 0 : passThrough;
        setBackgroundColor(0);   // 容器本身透明，露出的部分交给页面自己画
        addView(web, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (passThrough > 0 && host != null && event.getY() >= getHeight() - passThrough) {
            try {
                // 坐标原点与本层一致（都是 content 的直接子视图），所以可以直接转发
                MotionEvent copy = MotionEvent.obtain(event);
                boolean handled = host.dispatchTouchEvent(copy);
                copy.recycle();
                return handled;
            } catch (Throwable ignored) {
            }
        }
        return super.dispatchTouchEvent(event);
    }
}