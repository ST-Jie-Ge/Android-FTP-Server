package com.github.stjiege.ftpshare.filesystem;

import com.github.stjiege.ftpshare.Constants;

/**
 * 单次 read/write 的传输块大小，FTP 主界面可在 64 KB / 256 KB 两档之间切换。
 *
 * 与 {@link TransferSlots} 同类：{@code IODataConnection} 在 Apache 包里，读不到 App 的
 * SharedPreferences，所以由一个静态持有者中转。volatile 保证传输线程立刻看到新值。
 *
 * 只影响**下一次**传输：块大小在 transfer() 开传前读一次，正在飞的传输不会中途换块，
 * 因此不需要先停服务。
 */
public final class TransferBuffer {

    private static volatile int bytes = Constants.PreferenceConsts.FTP_TRANSFER_BUFFER_DEFAULT;

    private TransferBuffer() {
    }

    /**
     * 钳制到已知的两档之一。存了一个非法值（例如旧版本残留）时退回 64 KB，
     * 而不是照单全收——一个几 MB 的块会在每路传输上直接吃掉等量的堆。
     */
    public static void configure(int value) {
        bytes = value == Constants.PreferenceConsts.FTP_TRANSFER_BUFFER_LARGE
                ? Constants.PreferenceConsts.FTP_TRANSFER_BUFFER_LARGE
                : Constants.PreferenceConsts.FTP_TRANSFER_BUFFER_SMALL;
    }

    public static int bytes() {
        return bytes;
    }
}
