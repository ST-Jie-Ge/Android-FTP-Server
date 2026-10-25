# FTP Server G2

FTP Server G2 可以把 Android 手机/嵌入式设备变成 FTP 文件服务器，方便在同一网络内用电脑或其他设备访问手机文件。

![FTP Server G2 界面展示](宣传图.jpg)

## 主要功能

- 提供 FTP 文件服务，并支持 FTPS 加密连接。
- 创建多个账户，为每个账户设置访问目录和读写权限，也可按需启用匿名访问。
- 查看连接状态、当前连接数、资源占用及运行日志，便于了解服务情况和排查问题。
- 配置服务端口、并发连接数、同时传输数和传输分块大小。
- 支持 IPv6，并提供访问控制选项，按需限制可连接的设备。

## 外部控制接口
<details>
<summary><strong>👉 点击展开</strong></summary>


### 权限与动作

调用方需要在 `AndroidManifest.xml` 中声明权限：

```xml
<uses-permission android:name="com.github.stjiege.ftpshare.permission.CONTROL_FTP" />
```

| 用途 | 广播 |
| --- | --- |
| 启动 FTP | `com.github.stjiege.ftpshare.action.START_FTP` |
| 停止 FTP | `com.github.stjiege.ftpshare.action.STOP_FTP` |

接收器组件为 `com.github.stjiege.ftpshare.receivers.FtpControlReceiver`。

### Android Java 调用示例

```java
private static final String APP_ID = "com.github.stjiege.ftpshare";
private static final String EXTRA_STATUS = APP_ID + ".extra.CONTROL_STATUS";
private static final String EXTRA_MESSAGE = APP_ID + ".extra.CONTROL_MESSAGE";

private void requestFtp(Context context, boolean start) {
    String action = APP_ID + (start ? ".action.START_FTP" : ".action.STOP_FTP");
    Intent request = new Intent(action).setComponent(new ComponentName(
            APP_ID, APP_ID + ".receivers.FtpControlReceiver"));

    context.sendOrderedBroadcast(
            request,
            null,
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    Bundle result = getResultExtras(false);
                    String status = result == null ? null : result.getString(EXTRA_STATUS);
                    String message = result == null ? null : result.getString(EXTRA_MESSAGE);
                    int resultCode = getResultCode();
                    // resultCode == 1 表示请求已接受；0 表示拒绝或失败。
                    Log.i("FtpControl", "code=" + resultCode
                            + ", status=" + status + ", message=" + message);
                }
            },
            null,
            0,
            null,
            null);
}
```

| 返回结果码 | `CONTROL_STATUS` | 含义 |
| --- | --- | --- |
| `1` | `accepted` | 启动或停止请求已接受 |
| `0` | `interface_disabled` | 应用设置中未开启外部控制 |
| `0` | `attack_lockdown` | 暴力破解防护处于特殊停服状态，拒绝启动 |
| `0` | `unknown_action` | 动作不受支持 |
| `0` | `rejected` | 请求未被接受 |
| `0` | `failed` | 处理请求时发生错误 |

`CONTROL_MESSAGE` 是供日志或调试显示的说明文本；集成逻辑应依据结果码和 `CONTROL_STATUS` 判断，不要解析说明文本。`accepted` 代表请求已提交，FTP 的实际启动或停止是异步过程，不代表监听端口已经就绪或服务已完全停止。

如果调用方没有声明 `CONTROL_FTP` 权限，系统会在接收器执行前拒绝该调用，因此回调可能只有默认结果、没有 `CONTROL_STATUS`。
</details>
