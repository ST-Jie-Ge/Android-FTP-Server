package com.github.stjiege.ftpshare.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;

import androidx.appcompat.app.AlertDialog;

import com.github.stjiege.ftpshare.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;

/**
 * 「分块按钮」式单选对话框，供服务端设置页复用。
 *
 * 与 {@link IntSliderDialog} 的分工：
 * - 选项是**离散且数量少**的（档位名、64 KB / 256 KB、并发传输数 4–8）→ 用本类，一眼看全所有选项；
 * - 选项是**连续且跨度大**的（最大工作线程数 8–20，共 13 档）→ 仍用滑块，挤成 13 个分块没法点。
 *
 * 分块按钮**在布局里预置**（{@code dialog_segmented_choice.xml}），本类只负责填字、显示/隐藏、
 * 预选和回调。之所以不在代码里 {@code new MaterialButton(context, null, R.attr.materialButtonOutlinedStyle)}：
 * 本工程主题是没有控件样式映射的 Bridge 主题，那个 attr 解析不到，
 * 按钮会静默退回「实心填充」样式 —— 5 个块全一个色，看不出选中了谁。
 *
 * **点一下就生效**：选中某一块后立刻回调并关窗，没有确定按钮 —— 与改造前 AlertDialog
 * 单选列表的手感一致。因此本类不承担校验职责，越界提示由调用方在回调里自行处理。
 */
public final class SegmentedChoiceDialog {

    /** 用户点中某一档；index 为选项下标，调用方据此换算成真实数值。 */
    public interface OnPicked {
        void onPicked(int index);
    }

    /** 布局里预置的分块按钮 id，顺序即选项顺序；数组长度即分块数量上限。 */
    private static final int[] SEGMENT_IDS = {
            R.id.dialog_segmented_0, R.id.dialog_segmented_1, R.id.dialog_segmented_2,
            R.id.dialog_segmented_3, R.id.dialog_segmented_4};

    /** 单次最多能摆几块。要加档位，先在布局里补一个同结构的 MaterialButton 再改这个数组。 */
    public static final int MAX_SEGMENTS = SEGMENT_IDS.length;

    private SegmentedChoiceDialog() {
    }

    /**
     * @param checkedIndex 预选下标；传 -1 表示不预选（例如存的是不在预设档位里的自定义值）
     * @throws IllegalArgumentException 选项个数不在 1–{@link #MAX_SEGMENTS} 之间
     */
    public static AlertDialog show(Context context, int titleRes, CharSequence[] labels,
                                   int checkedIndex, OnPicked onPicked) {
        if (labels == null || labels.length == 0 || labels.length > MAX_SEGMENTS) {
            throw new IllegalArgumentException("分块按钮只支持 1–" + MAX_SEGMENTS + " 个选项，收到 "
                    + (labels == null ? "null" : String.valueOf(labels.length)));
        }
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_segmented_choice, null);
        MaterialButtonToggleGroup group = view.findViewById(R.id.dialog_segmented_group);

        for (int i = 0; i < SEGMENT_IDS.length; i++) {
            MaterialButton button = view.findViewById(SEGMENT_IDS[i]);
            if (i < labels.length) {
                button.setText(labels[i]);
                button.setVisibility(View.VISIBLE);
            } else {
                // 用不到的块藏起来：MaterialButtonToggleGroup 按「可见子项」算首尾圆角，
                // GONE 的块不会把圆角抢走。
                button.setVisibility(View.GONE);
            }
        }

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(titleRes)
                .setView(view)
                .setNegativeButton(R.string.dialog_button_cancel, null)
                .create();

        final int preCheckedId = checkedIndex >= 0 && checkedIndex < labels.length
                ? SEGMENT_IDS[checkedIndex] : View.NO_ID;
        // 预选必须发生在注册监听之前：check() 同样会触发选中回调，
        // 顺序反了会在对话框刚弹出时就把用户已存的档位当成一次点击提交掉。
        if (preCheckedId != View.NO_ID) {
            group.check(preCheckedId);
        }
        group.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            if (!isChecked) return;
            // 点中的就是当前已生效的那一档 → 等于没改，不回调也不关窗。
            // 顺带挡掉 selectionRequired 下「重新选中同一块」可能带来的重复回调。
            if (checkedId == preCheckedId) return;
            int index = indexOf(checkedId);
            if (index < 0 || index >= labels.length) return;
            if (onPicked != null) onPicked.onPicked(index);
            dialog.dismiss();
        });

        dialog.show();
        ModernDialogStyle.apply(dialog);
        return dialog;
    }

    private static int indexOf(int id) {
        for (int i = 0; i < SEGMENT_IDS.length; i++) {
            if (SEGMENT_IDS[i] == id) return i;
        }
        return -1;
    }
}
