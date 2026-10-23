package com.github.stjiege.ftpshare.services;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import androidx.core.app.NotificationCompat;

import com.github.stjiege.ftpshare.R;
import com.github.stjiege.ftpshare.activities.MainActivity;
import com.github.stjiege.ftpshare.utils.LogManager;

@TargetApi(24)
public class MyTileService extends TileService implements FtpService.OnFTPServiceStatusChangedListener {

    @Override
    public void onStartListening() {
        super.onStartListening();
        FtpService.addOnFtpServiceStatusChangedListener(this);
        refreshTileState();
    }

    @Override
    public void onStopListening() {
        super.onStopListening();
        FtpService.removeOnFtpServiceStatusChangedListener(this);
    }

    @Override
    public void onDestroy() {
        // The system does not always deliver onStopListening (tile removed, service torn down),
        // so this is the only place guaranteed to run. Without it the instance stays referenced
        // by the static listener list forever, together with this service context.
        FtpService.removeOnFtpServiceStatusChangedListener(this);
        super.onDestroy();
    }

    @Override
    public void onClick() {
        super.onClick();
        Tile tile = getQsTile();
        if (tile == null) return;
        switch (tile.getState()) {
            default:
                break;
            case Tile.STATE_INACTIVE: {
                tile.setState(Tile.STATE_UNAVAILABLE);
                tile.updateTile();
                if (!FtpService.startService(this)) {
                    showNotification(getResources().getString(R.string.notification_ftp_start_error_title), getResources().getString(R.string.notification_ftp_start_errer_message));
                    tile.setState(Tile.STATE_INACTIVE);
                    tile.updateTile();
                }
            }
            break;
            case Tile.STATE_ACTIVE: {
                tile.setState(Tile.STATE_UNAVAILABLE);
                tile.updateTile();
                FtpService.stopService();
            }
            break;
        }
    }

    @Override
    public void onFTPServiceStarted() {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(Tile.STATE_ACTIVE);
        tile.updateTile();
        try {
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).cancel(2);
        } catch (Exception e) {
            LogManager.warning("清理快捷设置通知失败: " + e.getClass().getSimpleName());
        }
    }

    @Override
    public void onFTPServiceStartError(Exception e) {
        showNotification(getResources().getString(R.string.notification_ftp_start_error_title), getResources().getString(R.string.notification_ftp_start_errer_message));
    }

    @Override
    public void onFTPServiceDestroyed() {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(Tile.STATE_INACTIVE);
        tile.updateTile();
    }

    private void refreshTileState() {
        Tile tile = getQsTile();
        // getQsTile() is null whenever the service is not listening; updating it would throw.
        if (tile == null) return;
        tile.setState(FtpService.isFTPServiceRunning() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }

    private void showNotification(String title, String content) {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel("attention"
                    , getResources().getString(R.string.notification_channel_attention)
                    , NotificationManager.IMPORTANCE_DEFAULT);
            notificationManager.createNotificationChannel(channel);
        }
        int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) pendingIntentFlags |= PendingIntent.FLAG_IMMUTABLE;
        Notification notification = new NotificationCompat.Builder(this, "attention")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(String.valueOf(title))
                .setContentText(String.valueOf(content))
                .setAutoCancel(true)
                .setOngoing(false)
                .setContentIntent(PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class), pendingIntentFlags))
                .build();
        notificationManager.notify(2, notification);
    }

}
