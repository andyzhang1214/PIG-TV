package com.github.tvbox.osc.phone;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.text.TextUtils;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 网盘凭据的落盘。
 * <p>
 * 分两半：
 * 1. 自己一份（SharedPreferences）——「我的 → 网盘登录」用来显示已登录状态、复制凭据；
 * 2. 给爬虫一份——玩偶哥哥那批 4K 站点靠 {@code Cloud-drive} 配置读网盘凭据，
 * 读的是下面这几个位置，格式是 {@code {"cookie":"","uccookie":"","token":"","tianyicookie":""}}。
 * <p>
 * 位置是从爬虫源码（Cloud.java + Path.java）里核实的：
 * {@code Path.tv()} = {@code /sdcard/TV}，标记文件 {@code .quark/.uc/.uctoken/.ali} 就放在那里；
 * 而配置里写的 {@code tvfan/Cloud-drive.txt} 是相对外部存储根目录的，
 * 所以两处都写一份，谁认哪个都不落空。
 */
public class NetdiskStore {

    public static final String QUARK = "quark";
    public static final String UC = "uc";
    public static final String ALI = "ali";

    private static final String SP = "netdisk_login";

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    public static String get(Context c, String type) {
        return sp(c).getString(type, "");
    }

    public static void save(Context c, String type, String value) {
        if (TextUtils.isEmpty(value)) return;
        sp(c).edit().putString(type, value).apply();
        syncToSpider(c);
    }

    public static void clear(Context c, String type) {
        sp(c).edit().remove(type).apply();
        syncToSpider(c);
    }

    /** 已登录的网盘个数，给前端徽章用 */
    public static int count(Context c) {
        int n = 0;
        if (!TextUtils.isEmpty(get(c, QUARK))) n++;
        if (!TextUtils.isEmpty(get(c, UC))) n++;
        if (!TextUtils.isEmpty(get(c, ALI))) n++;
        return n;
    }

    /**
     * 把三个网盘的凭据同步成爬虫能读的文件。
     * 写失败不抛（没授存储权限时就是写不进去，UI 那边还有「复制」兜底）。
     */
    public static void syncToSpider(Context c) {
        String quark = get(c, QUARK);
        String uc = get(c, UC);
        String ali = get(c, ALI);

        JSONObject o = new JSONObject();
        try {
            o.put("cookie", quark);        // 夸克
            o.put("uccookie", uc);         // UC
            o.put("token", ali);           // 阿里云盘（refresh_token，32 位）
            o.put("tianyicookie", "");     // 天翼，暂不支持
        } catch (Throwable ignored) {
        }
        String body = o.toString();

        File root = Environment.getExternalStorageDirectory();
        write(new File(root, "tvfan/Cloud-drive.txt"), body);
        write(new File(root, "TV/tvfan/Cloud-drive.txt"), body);

        File tv = new File(root, "TV");
        if (!TextUtils.isEmpty(quark)) write(new File(tv, ".quark"), quark);
        if (!TextUtils.isEmpty(uc)) {
            write(new File(tv, ".uc"), uc);
            write(new File(tv, ".uctoken"), "1");   // UC 要两个标记都在才会初始化
        }
        if (!TextUtils.isEmpty(ali)) write(new File(tv, ".ali"), ali);
    }

    /** 复制到剪贴板的文本（脱敏只用于展示，复制的是原文） */
    public static String brief(String s) {
        if (TextUtils.isEmpty(s)) return "";
        if (s.length() <= 14) return s;
        return s.substring(0, 8) + "…" + s.substring(s.length() - 6);
    }

    private static void write(File f, String body) {
        FileOutputStream fos = null;
        try {
            File p = f.getParentFile();
            if (p != null && !p.exists()) p.mkdirs();
            fos = new FileOutputStream(f, false);
            fos.write(body.getBytes("UTF-8"));
            fos.flush();
        } catch (Throwable ignored) {
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
