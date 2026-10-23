package com.github.stjiege.ftpshare.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.github.stjiege.ftpshare.R;

/**
 * 一个带 SeekBar 的整数选择对话框，供「工作线程数」「并发传输数」这类设置复用。
 * 值域 [min, max]；顶部实时显示当前值；可选的警告行在值越过策略阈值时显示。
 *
 * 用户点「确定」后通过 {@link OnValuePicked} 把所选值交给调用方，随后对话框自动关闭。
 * 保存与否、是否要前置校验（例如「服务必须完全停止」）都由调用方在回调里决定。
 */
public final class IntSliderDialog {

    public interface OnValuePicked {
        void onPicked(int value);
    }

    public interface WarningPolicy {
        boolean shouldWarn(int value);
    }

    private IntSliderDialog() {
    }

    /**
     * @param messageRes 兼容原调用参数；编辑界面不显示功能说明
     * @param warningRes 0 且 {@code warning == null} 时隐藏警告行
     */
    public static AlertDialog show(Context context, int titleRes, int messageRes,
                                   int current, int min, int max, int rangeHintRes,
                                   WarningPolicy warning, int warningRes, OnValuePicked onPicked) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_worker_threads, null);
        TextView valueView = view.findViewById(R.id.worker_threads_value);
        SeekBar slider = view.findViewById(R.id.worker_threads_slider);
        TextView warningView = view.findViewById(R.id.dialog_warning);


        boolean hasWarning = warning != null && warningRes != 0;
        if (hasWarning) warningView.setText(warningRes);

        slider.setMax(max - min);
        current = Math.max(min, Math.min(max, current));
        slider.setProgress(current - min);
        valueView.setText(String.valueOf(current));
        updateWarning(warningView, warning, current, hasWarning);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = progress + min;
                valueView.setText(String.valueOf(value));
                updateWarning(warningView, warning, value, hasWarning);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(titleRes)
                .setView(view)
                .setPositiveButton(R.string.dialog_button_confirm, null)
                .setNegativeButton(R.string.dialog_button_cancel, null);
        // Description/range arguments are retained for caller compatibility; keep the editor concise.
        AlertDialog dialog = builder.show();
        ModernDialogStyle.apply(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int value = slider.getProgress() + min;
            if (onPicked != null) onPicked.onPicked(value);
            dialog.dismiss();
        });
        return dialog;
    }

    private static void updateWarning(TextView view, WarningPolicy policy, int value, boolean hasWarning) {
        if (!hasWarning) {
            view.setVisibility(View.GONE);
            return;
        }
        view.setVisibility(policy.shouldWarn(value) ? View.VISIBLE : View.GONE);
    }
}
