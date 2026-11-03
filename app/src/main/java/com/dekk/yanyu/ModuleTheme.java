package com.dekk.yanyu;

import android.content.Context;
import android.content.res.Configuration;

/**
 * Miuix（HyperOS）设计规范的原生色板与尺寸。
 *
 * <p>Miuix 官方库（top.yukonga.miuix.kmp）基于 Compose Multiplatform，本工程只支持 Java 7
 * 且以 dex 注入宿主进程，不能引入 Compose 运行时，因此按其规范用原生 View 复刻。
 */
final class ModuleTheme {
    /** HyperOS 蓝。 */
    static final int ACCENT=0xFF3482FF;
    /** 卡片圆角。 */
    static final int CARD_RADIUS=16;
    /** 列表行最小高度。 */
    static final int ROW_MIN_HEIGHT=56;
    /** 页面左右留白。 */
    static final int PAGE_PADDING=16;

    static int background(boolean dark){return dark?0xFF101010:0xFFF5F5F5;}
    static int surface(boolean dark){return dark?0xFF1E1E1E:0xFFFFFFFF;}
    static int text(boolean dark){return dark?0xFFF0F0F0:0xFF1A1A1A;}
    static int secondary(boolean dark){return dark?0xFF9A9A9A:0xFF8C8C8C;}
    static int divider(boolean dark){return dark?0xFF2C2C2C:0xFFEDEDED;}
    static int field(boolean dark){return dark?0xFF2A2A2A:0xFFF0F0F0;}
    static int ripple(boolean dark){return dark?0x33FFFFFF:0x1F3482FF;}

    static boolean dark(Context context){
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
    }
    private ModuleTheme(){}
}