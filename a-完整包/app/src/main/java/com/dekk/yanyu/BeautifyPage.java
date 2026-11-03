package com.dekk.yanyu;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** 聊天美化：壁纸（含透明度）、自定义 HTML UI、气泡与头像。 */
public final class BeautifyPage {

    private BeautifyPage() { }

    public static void show(final Activity a) {
        // KOOK 绑定门禁：未绑定则弹窗
        if(!BindGate.require(a,null))return;
        final CenterPage p = new CenterPage(a, "聊天美化", null).navigationKey("beautify");
        p.section("背景图片");
        LinearLayout card = p.card();
        p.rowIcon(card, MiuixIcon.IMAGE, "修改壁纸 ›", new Runnable() {
            public void run() { BackgroundImageImport.pick(a); }
        });
        p.rowIcon(card, MiuixIcon.DOWNLOAD, "恢复默认 ›", new Runnable() {
            public void run() { BeautifyStore.clear(a); show(a); }
        });

        final String path = BeautifyStore.get(a);
        if (path.length() > 0) {
            p.section("图片预览");
            final ImageView image = new ImageView(a);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            p.body.addView(image, new LinearLayout.LayoutParams(-1, p.dp(280)));
            new Thread(new Runnable() {
                public void run() {
                    Bitmap bitmap = null;
                    try {
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inSampleSize = 4;
                        bitmap = BitmapFactory.decodeFile(path, options);
                    } catch (Throwable ignored) { }
                    final Bitmap result = bitmap;
                    a.runOnUiThread(new Runnable() {
                        public void run() {
                            if (p.active() && result != null) {
                                image.setImageBitmap(result);
                            }
                        }
                    });
                }
            }, "DEKK-background-preview").start();
            addAlphaCard(p);
        } else {
            p.section("尚未选择图片");
        }

        BubbleSettingsCard.add(p);
        SegmentsSettingsCard.add(p);

        // 自定义 HTML UI 入口已隐藏（用户反馈暂不可用，功能已强制关闭）。
        // 需要恢复时把下面这段接回来：
        // p.section("自定义 UI");
        // LinearLayout htmlCard = p.card();
        // p.rowIcon(htmlCard, MiuixIcon.DOC, "自定义 HTML 聊天 UI（头像 / 壁纸 / 实时预览）›",
        //         new Runnable() { public void run() { HtmlUiPage.show(a); } });

        p.show();
    }

    /** 背景透明度：进度条拖动 + 手动输入百分比，两者双向同步。 */
    private static void addAlphaCard(final CenterPage p) {
        final Activity a = p.activity;
        p.section("背景透明度");
        final LinearLayout card = p.card();
        final LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, p.dp(6), 0, p.dp(2));
        final SeekBar bar = new SeekBar(a);
        bar.setMax(100);
        bar.setProgress(BeautifyStore.alpha(a));
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(ModuleTheme.ACCENT));
        bar.setThumbTintList(android.content.res.ColorStateList.valueOf(ModuleTheme.ACCENT));
        row.addView(bar, new LinearLayout.LayoutParams(0, p.dp(48), 1f));
        final EditText input = new EditText(a);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(BeautifyStore.alpha(a)));
        input.setGravity(Gravity.CENTER);
        input.setTextSize(15);
        input.setIncludeFontPadding(false);
        input.setTextColor(ModuleTheme.text(p.night));
        input.setBackground(MiuixUi.shape(a, ModuleTheme.field(p.night), 12));
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(p.dp(76), p.dp(46));
        inputParams.leftMargin = p.dp(12);
        row.addView(input, inputParams);
        card.addView(row);
        p.caption(card, "0% 完全透明（只显示底色），100% 完全不透明。只影响聊天背景图，不影响气泡与文字。");

        final boolean[] syncing = {false};
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (syncing[0]) {
                    return;
                }
                syncing[0] = true;
                input.setText(String.valueOf(progress));
                syncing[0] = false;
                if (fromUser) {
                    BeautifyStore.setAlpha(a, progress);
                }
            }
            public void onStartTrackingTouch(SeekBar seekBar) { }
            public void onStopTrackingTouch(SeekBar seekBar) {
                BeautifyStore.setAlpha(a, seekBar.getProgress());
            }
        });
        final Runnable readInput = new Runnable() {
            public void run() {
                if (syncing[0]) {
                    return;
                }
                int value;
                try {
                    value = Integer.parseInt(input.getText().toString().trim());
                } catch (Throwable ignored) {
                    value = BeautifyStore.alpha(a);
                }
                if (value < 0) value = 0;
                if (value > 100) value = 100;
                syncing[0] = true;
                input.setText(String.valueOf(value));
                bar.setProgress(value);
                syncing[0] = false;
                BeautifyStore.setAlpha(a, value);
            }
        };
        input.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) {
                    readInput.run();
                }
            }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    readInput.run();
                    return true;
                }
                return false;
            }
        });
    }
}