package com.github.stjiege.ftpshare.data;

import androidx.annotation.NonNull;

import com.github.stjiege.ftpshare.utils.StorageUtil;

import java.io.Serializable;

public class AccountItem implements Serializable {
    public long id = -1;
    public String account = "";
    public String password = "";
    public String path = StorageUtil.getMainStoragePath();
    public boolean writable = true;
    /** Exposes the restricted virtual /device and /sdcard roots instead of path. */
    public boolean fullStorage = false;

    public AccountItem() { }

    public AccountItem(AccountItem source) {
        id = source.id;
        account = source.account;
        password = source.password;
        path = source.path;
        writable = source.writable;
        fullStorage = source.fullStorage;
    }

    @Override
    @NonNull
    public String toString() {
        return "AccountItem{" +
                "id=" + id +
                ", account='" + account + '\'' +
                ", password='***'" +
                ", path='" + path + '\'' +
                ", writable=" + writable +
                ", fullStorage=" + fullStorage +
                '}';
    }
}

