# FTP Server G2 ProGuard/R8 rules
# These rules are intentionally conservative because the app embeds shaded Apache
# FtpServer/MINA jars that use class-name lookups and reflection internally.

# 诊断用：把 R8 实际移除的类/成员写到 mapping 目录，便于核对裁剪结果（不进入 APK）。
-printusage build/outputs/mapping/release/usage.txt

# WebView JavaScript bridge (desk clock page). The page calls these methods by name, so R8
# must not rename or remove them.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep debugging info for release crash stacks.
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, Exceptions

# Kotlin 运行时支撑。
# 原规则是 -keep class kotlin.** { *; }，把整个 stdlib（922KB dex）原样钉进包，
# 其中 kotlin.collections.unsigned(93KB)、kotlin.sequences(53KB)、kotlin.time(30KB)、
# kotlin.io.path(36KB)、kotlin.reflect(14KB) 等本工程根本没有引用。
# 本工程可安全放开整包保留的依据：
#   1) 依赖里没有 kotlin-reflect（只有 kotlin-stdlib-jdk8），不存在走反射的 KClass 体系；
#   2) 全工程没有 Class.forName 指向任何 kotlin 类，所有 Xxx::class.java 都是编译期静态引用
#      （Intent/ComponentName 用），R8 的可达性分析能精确覆盖；
#   3) 没有 kotlinx-coroutines、没有 kotlinx-serialization、没有 Room —— 这些是"注解在运行时
#      被反射读取"的主要来源，本工程一个都没有。
# 只钉住编译器生成代码真正依赖的两块，其余交回 R8 裁剪。
-keep class kotlin.Metadata { *; }
-keep class kotlin.jvm.internal.** { *; }
-dontwarn kotlin.**

# Keep all application components and entry points referenced by AndroidManifest.xml / system.
-keep public class com.github.stjiege.ftpshare.activities.** { public *; }
-keep public class com.github.stjiege.ftpshare.services.** { public *; }
-keep public class com.github.stjiege.ftpshare.receivers.** { public *; }
-keep public class com.github.stjiege.ftpshare.widgets.** { public *; }
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.appwidget.AppWidgetProvider
-keep public class * extends android.service.quicksettings.TileService
-keep public class * extends androidx.fragment.app.Fragment

# Keep Parcelable / Serializable models used across IPC / storage.
-keep public class com.github.stjiege.ftpshare.data.** { *; }
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Keep AIDL-generated Binder stubs and interfaces (Shizuku user service).
-keep class com.github.stjiege.ftpshare.shizuku.IShizukuCommandService { *; }
-keep class com.github.stjiege.ftpshare.shizuku.IShizukuCommandService$* { *; }
-keep class * extends android.os.Binder { *; }
-keep class * implements android.os.IInterface { *; }

# Keep classes that may be accessed by name through reflection or from XML/resources.
-keep public class com.github.stjiege.ftpshare.adapers.** { public *; }
-keep public class com.github.stjiege.ftpshare.ui.** { public *; }
-keep public class com.github.stjiege.ftpshare.views.** { public *; }
-keep public class com.github.stjiege.ftpshare.utils.** {
    public <fields>;
    public <methods>;
}
-keep public class com.github.stjiege.ftpshare.filesystem.** { public *; }
-keep public class com.github.stjiege.ftpshare.shizuku.** { public *; }

# Constants inner classes are referenced in preference code; keep them intact.
-keep public class com.github.stjiege.ftpshare.Constants { *; }
-keep public class com.github.stjiege.ftpshare.Constants$* { *; }

# Keep R fields that may be read by name.
-keepclassmembers class **.R$* { public static <fields>; }

# Apache FtpServer, ftplet-api and Apache MINA: keep everything.
# These jars register listeners/commands/ftplets by class name and use reflection.
-keep class org.apache.ftpserver.** { *; }
-keep class org.apache.ftplet.** { *; }
-keep class org.apache.mina.** { *; }
-dontwarn org.apache.ftpserver.**
-dontwarn org.apache.ftplet.**
-dontwarn org.apache.mina.**
# org.apache.commons.** 的两条规则随 commons-logging-1.2.jar 一并移除（2026-10-02）：
# 该 jar 已删除，且经全量扫描确认无任何引用者。保留 -keep 只会让 R8 找不到目标、
# 保留 -dontwarn 则会掩盖未来真正缺失类的告警。

# Logging facades used by Apache FtpServer.
-keep class org.slf4j.** { *; }
# log4j 1.2.17 按实际使用面逐包保留，不再整包 keep。
# 依据：本工程没有任何 log4j 配置文件（build 目录外不存在 log4j.properties / log4j.xml），
# 日志链路是 MyApplication 里硬编码装配 —— DEBUG 时 new Log4jLogcatAppender() 挂到 root。
# 而下面这些子包只有在「配置文件里写类名 + 反射加载」时才可能被用到，运行时不可能触达：
#   lf5(52KB, Swing 日志查看器 GUI) / chainsaw(12KB) / net(24KB, Socket·JMS·Syslog appender)
#   jmx(12KB, MBean，Android 没有 JMX) / nt(1KB, Windows 事件日志) / jdbc(2KB, JDBCAppender)
# 写法要点：ProGuard 里单个 * 只匹配本包的直接类、不跨包，双 ** 才递归 —— 正好用逐包列举收口。
-keep class org.apache.log4j.* { *; }
-keep class org.apache.log4j.spi.* { *; }
-keep class org.apache.log4j.helpers.* { *; }
-keep class org.apache.log4j.or.* { *; }
-keep class org.apache.log4j.config.* { *; }
-keep class org.apache.log4j.varia.* { *; }
-keep class org.apache.log4j.xml.* { *; }
-keep class org.apache.log4j.pattern.* { *; }
-keep class org.apache.log4j.rewrite.* { *; }
-dontwarn org.slf4j.**
-dontwarn org.apache.log4j.**

# Shizuku (AAR) keeps its public API and IPC classes.
-keep class rikka.shizuku.** { *; }
-keep class rikka.sui.** { *; }
-keep class moe.shizuku.** { *; }
-keep class moe.shizuku.api.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn rikka.sui.**
-dontwarn moe.shizuku.**

# AndroidX / Material：不再整包保留，只保留本项目真正用到的组件。
# 2026-09-30 改动说明（重要）：原来这里写的是
#     -keep class androidx.appcompat.** { *; }
#     -keep class androidx.core.** { *; }
#     -keep class com.google.android.material.** { *; }
# 三条「整包保留」规则会让 R8 完全放弃裁剪这三个库。material 从 1.0.0 抬到 1.11.0 后，
# 三库的类数从约 1000 涨到约 2200，全部原样打进 dex，APK 因此无谓地涨了约 1.6MB（+56%）。
# AndroidX 各库自带消费级 keep 规则，AGP 也会为布局 XML 里引用的自定义 View 自动生成 keep 规则，
# 所以整包保留没有必要。这里改成只钉住项目实际引用的那几个组件，其余交回 R8 裁剪。
-keep class com.google.android.material.bottomnavigation.BottomNavigationView { *; }
-keep class com.google.android.material.snackbar.Snackbar { *; }
-keep class com.google.android.material.snackbar.SnackbarManager { *; }
-keep class com.google.android.material.textfield.TextInputLayout { *; }
-keep class com.google.android.material.textfield.TextInputEditText { *; }
-keep class com.google.android.material.dialog.MaterialAlertDialogBuilder { *; }
-keep class com.google.android.material.button.MaterialButton { *; }
-dontwarn androidx.appcompat.**
-dontwarn androidx.core.**
-dontwarn com.google.android.material.**
-keep class com.github.stjiege.ftpshare.filesystem.NativeMove { *; }
-keep class com.github.stjiege.ftpshare.filesystem.DirectoryNames$Native { *; }
