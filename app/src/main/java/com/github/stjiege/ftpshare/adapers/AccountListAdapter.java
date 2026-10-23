package com.github.stjiege.ftpshare.adapers;

import android.app.Activity;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.stjiege.ftpshare.R;
import com.github.stjiege.ftpshare.Constants;
import com.github.stjiege.ftpshare.activities.AnonymousAccountActivity;
import com.github.stjiege.ftpshare.activities.EditAccountActivity;
import com.github.stjiege.ftpshare.data.AccountItem;
import com.github.stjiege.ftpshare.utils.CommonUtils;
import com.github.stjiege.ftpshare.utils.StorageUtil;

import java.util.ArrayList;
import java.util.List;

public class AccountListAdapter extends BaseAdapter implements AdapterView.OnItemClickListener {

    private final ArrayList<AccountItem> accountItems = new ArrayList<>();
    private final Activity activity;

    public AccountListAdapter(@NonNull Activity activity, @NonNull List<AccountItem> items, @NonNull ListView listView) {
        super();
        this.activity = activity;
        accountItems.addAll(items);
        listView.setOnItemClickListener(this);
        listView.setDivider(null);
    }

    @Override
    public int getCount() {
        return accountItems.size() + 1;
    }

    @Override
    public Object getItem(int position) {
        return position == 0 ? null : accountItems.get(position - 1);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(activity).inflate(R.layout.item_account, parent, false);
        }
        ViewGroup viewGroup = convertView.findViewById(R.id.item_account_root);
        TextView tv_account = convertView.findViewById(R.id.text_account);
        TextView tv_path = convertView.findViewById(R.id.text_path);
        viewGroup.setVisibility(View.VISIBLE);
        // 匿名账户与自建账户用同一套样式：第一行"状态 · 读写"，第二行主路径。
        final String status;
        final String path;
        if (position == 0) {
            tv_account.setText(R.string.account_anonymous);
            boolean enabled = CommonUtils.getSettingSharedPreferences(activity).getBoolean(Constants.PreferenceConsts.ANONYMOUS_MODE, Constants.PreferenceConsts.ANONYMOUS_MODE_DEFAULT);
            boolean writableEnabled = CommonUtils.getSettingSharedPreferences(activity).getBoolean(Constants.PreferenceConsts.ANONYMOUS_MODE_WRITABLE, Constants.PreferenceConsts.ANONYMOUS_MODE_WRITABLE_DEFAULT);
            status = activity.getString(enabled ? R.string.account_enabled : R.string.account_disabled)
                    + " · " + activity.getString(writableEnabled ? R.string.account_read_write : R.string.account_read_only);
            path = StorageUtil.resolveHomeDirectory(activity,
                    CommonUtils.getSettingSharedPreferences(activity).getString(
                            Constants.PreferenceConsts.ANONYMOUS_MODE_PATH,
                            Constants.PreferenceConsts.ANONYMOUS_MODE_PATH_DEFAULT));
        } else {
            final AccountItem accountItem = accountItems.get(position - 1);
            tv_account.setText(accountItem.account);
            // 自建账户没有启用开关，始终可登录，且固定拥有读写权限。
            status = activity.getString(R.string.account_enabled)
                    + " · " + activity.getString(R.string.account_read_write);
            path = accountItem.fullStorage
                    ? activity.getString(R.string.account_full_storage_value)
                    : accountItem.path;
        }
        tv_path.setText(status + "\n" + path);
        // 图标按账户类型区分：匿名账户、开了完整存储的账户、普通账户各一张。
        ImageView icon = convertView.findViewById(R.id.item_icon);
        if (position == 0) {
            icon.setImageResource(R.drawable.icon_account_anonymous);
        } else {
            icon.setImageResource(accountItems.get(position - 1).fullStorage
                    ? R.drawable.icon_account_full_access
                    : R.drawable.icon_account);
        }
        return convertView;
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        Intent intent;
        if (position == 0) {
            intent = new Intent(activity, AnonymousAccountActivity.class);
        } else {
            intent = new Intent(activity, EditAccountActivity.class);
            intent.putExtra(EditAccountActivity.EXTRA_SERIALIZED_ACCOUNT_ITEM, accountItems.get(position - 1));
        }
        activity.startActivityForResult(intent, 1);
    }

    public ArrayList<AccountItem> getAccountItems() {
        return accountItems;
    }
}

