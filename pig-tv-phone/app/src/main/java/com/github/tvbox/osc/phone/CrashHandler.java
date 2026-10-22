package com.github.tvbox.osc.phone;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * 手机版崩溃记录器。
 *
 * 目的很单纯：这个版本没有真机验证环境，一旦启动就闪退，用户什么都看不到，
 * 只能反复描述"打不开"。所以把堆栈落到能拿到的地方：
 *   1. 内部存储 files/last_crash.txt —— 下次启动直接读出来展示
 *   2. 外部 files/last_crash.txt   —— 用户可以用文件管理器翻出来发给我
 *
 * 注意：这里绝不 swallow 异常。记完之后一定要交还给系统默认处理，
 * 否则进程会带着半死不活的状态继续跑，比直接崩更难查。
 */
public final class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "PHONE_CRASH";
    private static final String FILE_NAME = "last_crash.txt";

    private static Context appCtx;
    private static Thread.UncaughtExceptionHandler previous;
    private static volatile boolean installed = false;

    private CrashHandler() {
    }

    public static void install(Context ctx) {
        if (installed || ctx == null) return;
        try {
            appCtx = ctx.getApplicationContext();
            previous = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new CrashHandler());
            installed = true;
        } catch (Throwable ignored) {
            // 装不上也不能让它自己成为新的崩溃源
        }
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        try {
            String text = buildReport(t, e);
            Log.e(TAG, text);
            if (appCtx != null) {
                write(new File(appCtx.getFilesDir(), FILE_NAME), text);
                File ext = appCtx.getExternalFilesDir(null);
                if (ext != null) write(new File(ext, FILE_NAME), text);
                saveToDownloads(text);
            }
        } catch (Throwable ignored) {
            // 写日志失败也必须继续下面的流程
        }
        if (previous != null) {
            previous.uncaughtException(t, e);
        }
    }

    /** 手动记录非致命异常（启动流程里被我们 catch 住的那种），同样能在下次启动看到 */
    public static void logSoft(String where, Throwable e) {
        try {
            String text = where + "\r\n" + buildReport(Thread.currentThread(), e);
            Log.e(TAG, text);
            if (appCtx == null) return;
            write(new File(appCtx.getFilesDir(), FILE_NAME), text);
            File ext = appCtx.getExternalFilesDir(null);
            if (ext != null) write(new File(ext, FILE_NAME), text);
            saveToDownloads(text);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 再往系统「下载」目录扔一份。
     *
     * 为什么非要多这一份：应用内目录 /sdcard/Android/data/<包名>/files 在不少国产 ROM 的
     * 文件管理器里要么看不到、要么得翻好几层。放到「下载」里，用户打开文件管理一眼就能找到，
     * 截图或发文件都方便 —— 这是把「点开就闪退」变成「可诊断」的关键一步。
     */
    private static void saveToDownloads(String text) {
        if (Build.VERSION.SDK_INT < 29) return;   // 29 以下写公共目录要运行时权限，崩溃时来不及申请
        java.io.OutputStream os = null;
        try {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "猪猪影视崩溃日志.txt");
            cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS);
            android.net.Uri uri = appCtx.getContentResolver()
                    .insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) return;
            os = appCtx.getContentResolver().openOutputStream(uri, "wt");
            if (os == null) return;
            os.write(text.getBytes("UTF-8"));
            os.flush();
        } catch (Throwable ignored) {
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 读出上次崩溃内容，读完即删（避免每次启动都弹） */
    public static String consumeLast() {
        if (appCtx == null) return null;
        try {
            File f = new File(appCtx.getFilesDir(), FILE_NAME);
            if (!f.exists() || f.length() == 0) return null;
            String s = read(f);
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return s != null && s.trim().length() > 0 ? s : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 外部存储里的日志路径，用来告诉用户去哪儿拿文件 */
    public static String externalPath() {
        if (appCtx == null) return "";
        try {
            File ext = appCtx.getExternalFilesDir(null);
            return ext == null ? "" : new File(ext, FILE_NAME).getAbsolutePath();
        } catch (Throwable t) {
            return "";
        }
    }

    // ------------------------------------------------------------------

    private static String buildReport(Thread t, Throwable e) {
        StringBuilder sb = new StringBuilder();
        sb.append("========== 猪猪影视 手机版 崩溃报告 ==========\r\n");
        sb.append("时间    : ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append("\r\n");
        sb.append("机型    : ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("\r\n");
        sb.append("系统    : Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(") ").append(Build.BRAND).append("\r\n");
        sb.append("ABI     : ").append(Arrays.toString(Build.SUPPORTED_ABIS)).append("\r\n");
        sb.append("版本    : ").append(versionName()).append("\r\n");
        sb.append("线程    : ").append(t == null ? "?" : t.getName()).append("\r\n");
        sb.append("--------------------------------------------\r\n");

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        if (e != null) e.printStackTrace(pw);
        pw.flush();
        sb.append(sw.toString());
        sb.append("\r\n========== 报告结束 ==========\r\n");
        return sb.toString();
    }

    private static String versionName() {
        try {
            PackageInfo pi = appCtx.getPackageManager().getPackageInfo(appCtx.getPackageName(), 0);
            return pi.versionName + " (" + pi.versionCode + ")";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static void write(File f, String text) {
        FileOutputStream os = null;
        try {
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            os = new FileOutputStream(f, false);
            os.write(text.getBytes("UTF-8"));
            os.flush();
        } catch (Throwable ignored) {
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String read(File f) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
