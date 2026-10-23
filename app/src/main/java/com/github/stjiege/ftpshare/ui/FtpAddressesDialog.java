package com.github.stjiege.ftpshare.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.github.stjiege.ftpshare.Constants;
import com.github.stjiege.ftpshare.R;
import com.github.stjiege.ftpshare.adapers.FtpAddressesAdapter;
import com.github.stjiege.ftpshare.services.FtpService;
import com.github.stjiege.ftpshare.utils.CommonUtils;

public class FtpAddressesDialog extends AlertDialog {

    public FtpAddressesDialog(@NonNull Context context) {
        super(context);

        // IPv6 提示条：未开启「允许从IPv6访问」时列表只会有 IPv4，这里明确告知原因；
        // 开启了但绑定失败回退 IPv4 的场景用另一条文案（否则用户会以为开关没生效）。
        boolean ipv6Enabled = CommonUtils.getSettingSharedPreferences(context)
                .getBoolean(Constants.PreferenceConsts.IPV6_ENABLED,
                        Constants.PreferenceConsts.IPV6_ENABLED_DEFAULT);
        boolean ipv6Unavailable = !ipv6Enabled
                || (FtpService.isFTPServiceRunning() && !FtpService.isIpv6Bound());
        String hint = null;
        if (!ipv6Enabled) {
            hint = context.getResources().getString(R.string.ftp_addresses_ipv6_disabled);
        } else if (FtpService.isFTPServiceRunning() && !FtpService.isIpv6Bound()) {
            hint = context.getResources().getString(R.string.ftp_addresses_ipv6_fallback);
        }

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        if (hint != null) {
            TextView hintView = new TextView(context);
            hintView.setText(hint);
            hintView.setTextSize(12);
            hintView.setTextColor(Color.parseColor("#5A6672"));
            int pad = (int) (context.getResources().getDisplayMetrics().density * 14);
            hintView.setPadding(pad, pad / 2, pad, 0);
            container.addView(hintView);
        }

        RecyclerView recyclerView = new RecyclerView(context);
        ViewGroup.LayoutParams layoutParams = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        recyclerView.setLayoutParams(layoutParams);
        LinearLayoutManager linearLayoutManager = new LinearLayoutManager(getContext());
        linearLayoutManager.setOrientation(LinearLayoutManager.VERTICAL);
        recyclerView.setLayoutManager(linearLayoutManager);
        recyclerView.setAdapter(new FtpAddressesAdapter(context));
        setTitle(context.getResources().getString(R.string.item_ftp_addresses));
        container.addView(recyclerView);
        setView(container);
        setButton(AlertDialog.BUTTON_NEGATIVE, context.getResources().getString(R.string.dialog_button_cancel), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
            }
        });
    }
}

