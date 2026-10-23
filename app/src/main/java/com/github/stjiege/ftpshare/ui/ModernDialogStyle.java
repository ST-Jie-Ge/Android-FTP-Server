package com.github.stjiege.ftpshare.ui;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.Window;
import android.widget.Button;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import com.github.stjiege.ftpshare.R;

/**
 * 对话框外观精调：宽度、遮罩浓度、阴影、按钮文字样式。
 *
 * 2026-09-30 改动说明（重要）：圆角背景改由主题统一提供 —— 见 styles.xml 里的
 * AppAlertDialog（ThemeOverlay.AppCompat.Dialog.Alert）配 drawable/bg_dialog_rounded.xml。
 * 这样连直接用 AlertDialog.Builder 创建的老对话框（账户、IP 白名单、新建文件夹等）
 * 也会自动带上 24dp 圆角，不必每处都记得调用本类。
 * 因此这里删掉了原来手工 setBackgroundDrawable 铺白底圆角的那段，
 * 只保留主题管不到的几项：对话框宽度、遮罩浓度、阴影高度、按钮文字外观。
 */
public final class ModernDialogStyle {
    private ModernDialogStyle() { }
    public static void apply(AlertDialog dialog) {
        float density = dialog.getContext().getResources().getDisplayMetrics().density;
        Window window = dialog.getWindow();
        if (window == null) return;
        int width = Math.min((int)(400 * density),
                dialog.getContext().getResources().getDisplayMetrics().widthPixels - (int)(48 * density));
        window.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        window.setDimAmount(0.38f);
        window.getDecorView().setElevation(12 * density);
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE}) {
            Button button = dialog.getButton(which);
            if (button == null) continue;
            button.setAllCaps(false);
            button.setTextSize(14);
            button.setMinHeight((int)(48 * density));
            button.setTextColor(ContextCompat.getColor(dialog.getContext(),
                    which == AlertDialog.BUTTON_POSITIVE ? R.color.colorAccent : R.color.color_text_secondary));
            button.setTypeface(button.getTypeface(), Typeface.BOLD);
        }
    }
}
