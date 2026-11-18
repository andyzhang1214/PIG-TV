package com.pigtv.util;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 崩溃日志落盘。
 *
 * TV 版原来完全没有 UncaughtExceptionHandler —— 崩了只有一句「很抱歉，影视已停止运行」，
 * 拿不到任何堆栈，排查只能靠猜。这里把 Java 层未捕获异常写进文件，
 * 用户把文件内容发回来就能直接定位。
 *
 * 注意：native 崩溃（SIGSEGV，比如 quickjs 求值 ESM、加固壳解密）走不到这里，
 * 那种只能靠 adb logcat。
 */
public class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "CrashHandler";
    private static final String FILE_NAME = "猪猪影视-崩溃日志.txt";

    private final Context appContext;
    private final Thread.UncaughtExceptionHandler defaultHandler;

    private CrashHandler(Context ctx) {
        this.appContext = ctx.getApplicationContext();
        this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
    }

    public static void install(Context ctx) {
        if (ctx == null) return;
        Thread.UncaughtExceptionHandler cur = Thread.getDefaultUncaughtExceptionHandler();
        if (cur instanceof CrashHandler) return;
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(ctx));
    }

    @Override
    public void uncaughtException(Thread thread, Throwable ex) {
        try {
            String text = buildReport(thread, ex);
            Log.e(TAG, text);
            writeFile(text);
        } catch (Throwable ignored) {
            // 记日志本身不能再崩，否则就死循环了
        }
        // 交回系统默认处理：该弹「已停止运行」还是弹，但从这一刻起我们有日志了
        if (defaultHandler != null) {
            defaultHandler.uncaughtException(thread, ex);
        } else {
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(10);
        }
    }

    private String buildReport(Thread thread, Throwable ex) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        try {
            ex.printStackTrace(pw);
        } catch (Throwable ignored) {
        }
        pw.flush();
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date());
        return "\n================ 崩溃 " + time + " ================\n"
                + "线程: " + (thread == null ? "?" : thread.getName()) + "\n"
                + "机型: " + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "系统: Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
                + "版本: " + versionName() + "\n"
                + "堆栈:\n" + sw + "\n";
    }

    private String versionName() {
        try {
            return appContext.getPackageManager()
                    .getPackageInfo(appContext.getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
            return "?";
        }
    }

    private void writeFile(String text) {
        // 优先写 Download（用户能直接翻到）；不行退到 App 外部私有目录，最后退内部目录
        for (File f : candidates()) {
            if (f == null) continue;
            try {
                File parent = f.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                FileWriter fw = new FileWriter(f, true);
                fw.write(text);
                fw.flush();
                fw.close();
                Log.e(TAG, "崩溃日志已写入 " + f.getAbsolutePath());
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    private File[] candidates() {
        File dl = null;
        try {
            dl = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), FILE_NAME);
        } catch (Throwable ignored) {
        }
        File ext = appContext.getExternalFilesDir(null);
        File extFile = ext == null ? null : new File(ext, FILE_NAME);
        File data = new File(appContext.getFilesDir(), FILE_NAME);
        return new File[]{dl, extFile, data};
    }
}
