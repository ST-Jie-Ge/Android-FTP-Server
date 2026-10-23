package com.github.stjiege.ftpshare.adapers;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Paint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.github.stjiege.ftpshare.Constants;
import com.github.stjiege.ftpshare.R;
import com.github.stjiege.ftpshare.services.FtpService;
import com.github.stjiege.ftpshare.utils.CommonUtils;
import com.github.stjiege.ftpshare.utils.NetworkEnvironmentUtil;
import com.github.stjiege.ftpshare.utils.LogManager;

import java.util.ArrayList;
import java.util.List;

public class FtpAddressesAdapter extends RecyclerView.Adapter<FtpAddressesAdapter.ViewHolder> {

    private final ArrayList<String> ftpAddresses = new ArrayList<>();
    private final Context context;

    public FtpAddressesAdapter(@NonNull Context context) {
        this.context = context;
        // IPv6 地址只在「用户开启允许从IPv6访问」且服务真的绑定了双栈时才展示：
        // 服务没监听 v6 时那些地址全部连不上（绑定失败会自动回退 IPv4，见 FtpService）。
        boolean ipv6Enabled = CommonUtils.getSettingSharedPreferences(context)
                .getBoolean(Constants.PreferenceConsts.IPV6_ENABLED,
                        Constants.PreferenceConsts.IPV6_ENABLED_DEFAULT);
        boolean includeIpv6 = ipv6Enabled
                && (!FtpService.isFTPServiceRunning() || FtpService.isIpv6Bound());
        List<String> ips = NetworkEnvironmentUtil.getLocalIpAddresses(includeIpv6);
        for (String ip : ips) {
            ftpAddresses.add(CommonUtils.getFtpServiceAddress(context, ip));
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int i) {
        return new ViewHolder(LayoutInflater.from(context).inflate(R.layout.item_ftp_address, viewGroup, false));
    }

    @Override
    public void onBindViewHolder(@NonNull final ViewHolder viewHolder, int i) {
        viewHolder.tv.getPaint().setFlags(Paint.UNDERLINE_TEXT_FLAG);
        viewHolder.tv.setText(ftpAddresses.get(viewHolder.getAdapterPosition()));
        viewHolder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    ClipboardManager manager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    manager.setPrimaryClip(ClipData.newPlainText("message", viewHolder.tv.getText().toString()));
                    Toast.makeText(context, context.getResources().getString(R.string.attention_clipboard), Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    LogManager.warning("复制FTP地址失败: " + e.getClass().getSimpleName());
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return ftpAddresses.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private TextView tv;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tv = itemView.findViewById(R.id.tv_ftp_address);
        }
    }
}

