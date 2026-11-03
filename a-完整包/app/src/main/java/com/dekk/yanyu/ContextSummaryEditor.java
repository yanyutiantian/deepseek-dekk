package com.dekk.yanyu;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import java.util.Locale;

/** Notes editor for the supplement block; content is authored by the user. */
final class ContextSummaryEditor {
    private ContextSummaryEditor() { }

    static void notes(final Activity activity, final Runnable onSaved) {
        final EditText input = new EditText(activity);
        input.setGravity(android.view.Gravity.TOP);
        input.setMinLines(4);
        input.setMaxLines(12);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(ContextSummaryStore.LIMIT)});
        input.setText(ContextSummaryStore.notes(activity));
        input.setHint("已确认事实：\n需求与约束：\n待讨论问题：");
        final AlertDialog dialog = new ModuleDialog(activity)
                .setTitle("已确认要点")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface d) {
                dialog.getButton(-1).setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        ContextSummaryStore.setNotes(activity, input.getText().toString());
                        if (onSaved != null) onSaved.run();
                        dialog.dismiss();
                    }
                });
            }
        });
        dialog.show();ModuleDialog.style(dialog);
    }

    static String summary(Activity activity) {
        String notes = ContextSummaryStore.notes(activity);
        if (notes.length() == 0) return "未填写";
        String flat = notes.replace('\n', ' ');
        return flat.length() <= 24 ? flat : String.format(Locale.getDefault(), "%s…", flat.substring(0, 24));
    }
}