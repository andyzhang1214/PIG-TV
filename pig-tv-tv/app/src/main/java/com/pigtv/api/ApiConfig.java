package com.pigtv.api;

import static com.pigtv.util.RegexUtils.getPattern;
import android.app.Activity;
import android.net.Uri;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Base64;

import com.github.catvod.crawler.JarLoader;
import com.github.catvod.crawler.JsLoader;
import com.github.catvod.crawler.pyLoader;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.python.IPyLoader;
import com.pigtv.R;
import com.pigtv.base.App;
import com.pigtv.bean.IJKCode;
import com.pigtv.bean.LiveChannelGroup;
import com.pigtv.bean.LiveChannelItem;
import com.pigtv.bean.ParseBean;
import com.pigtv.bean.SourceBean;
import com.pigtv.server.ControlManager;
import com.pigtv.ui.activity.HomeActivity;
import com.pigtv.util.AES;
import com.pigtv.util.AdBlocker;
import com.pigtv.util.DefaultConfig;
import com.pigtv.util.HawkConfig;
import com.pigtv.util.M3U8;
import com.pigtv.util.MD5;
import com.pigtv.util.FileUtils;
import com.pigtv.util.VideoParseRuler;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.orhanobut.hawk.Hawk;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.pigtv.util.LOG;
/**
 * @author pj567
 * @date :2020/12/18
 * @description:
 */
public class ApiConfig {
    private static ApiConfig instance;
    private final LinkedHashMap<String, SourceBean> sourceBeanList;
    private SourceBean mHomeSource;
    private ParseBean mDefaultParse;
    private final List<LiveChannelGroup> liveChannelGroupList;
    private final List<ParseBean> parseBeanList;
    private List<String> vipParseFlags;
    private List<IJKCode> ijkCodes;
    private String spider = null;
    public String wallpaper = "";
    public JsonArray livePlayHeaders;
    private final SourceBean emptyHome = new SourceBean();

    private final JarLoader jarLoader = new JarLoader();
    private final JsLoader jsLoader = new JsLoader();
    private final IPyLoader pyLoader =  new pyLoader();
    private final String userAgent = "okhttp/3.15";

    private final String requestAccept = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9";

    private ApiConfig() {
        clearLoader();
        sourceBeanList = new LinkedHashMap<>();
        liveChannelGroupList = new ArrayList<>();
        parseBeanList = new ArrayList<>();
    }

    public static ApiConfig get() {
        if (instance == null) {
            synchronized (ApiConfig.class) {
                if (instance == null) {
                    instance = new ApiConfig();
                }
            }
        }
        return instance;
    }

    public static String FindResult(String json, String configKey) {
        String content = json;
        try {
            if (AES.isJson(content)) return content;
            Pattern pattern = getPattern("[A-Za-z0]{8}\\*\\*");
            Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                content = content.substring(content.indexOf(matcher.group()) + 10);
                content = new String(Base64.decode(content, Base64.DEFAULT));
            }
            if (content.startsWith("2423")) {
                String data = content.substring(content.indexOf("2324") + 4, content.length() - 26);
                content = new String(AES.toBytes(content)).toLowerCase();
                String key = AES.rightPadding(content.substring(content.indexOf("$#") + 2, content.indexOf("#$")), "0", 16);
                String iv = AES.rightPadding(content.substring(content.length() - 13), "0", 16);
                json = AES.CBC(data, key, iv);
            } else if (configKey != null && !AES.isJson(content)) {
                json = AES.ECB(content, configKey);
            } else {
                json = content;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return json;
    }

    private static byte[] getImgJar(String body) {
        Pattern pattern = getPattern("[A-Za-z0]{8}\\*\\*");
        Matcher matcher = pattern.matcher(body);
        if (matcher.find()) {
            body = body.substring(body.indexOf(matcher.group()) + 10);
            return Base64.decode(body, Base64.DEFAULT);
        }
        return "".getBytes();
    }

    public void loadConfig(boolean useCache, LoadConfigCallback callback, Activity activity) {
        // Embedded Source : Update in Strings.xml if required
        String apiUrl = Hawk.get(HawkConfig.API_URL, HomeActivity.getRes().getString(R.string.app_source));
        if (apiUrl.isEmpty()) {
            callback.error("源地址为空");
            return;
        }
        File cache = new File(App.getInstance().getFilesDir().getAbsolutePath() + "/" + MD5.encode(apiUrl));
        if (useCache && cache.exists()) {
            try {
                parseJson(apiUrl, cache);
                callback.success();
                return;
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
        String TempKey = null, configUrl = "", pk = ";pk;";
        if (apiUrl.contains(pk)) {
            String[] a = apiUrl.split(pk);
            TempKey = a[1];
            if (apiUrl.startsWith("clan")) {
                configUrl = clanToAddress(a[0]);
            } else if (apiUrl.startsWith("http")) {
                configUrl = a[0];
            } else {
                configUrl = "http://" + a[0];
            }
        } else if (apiUrl.startsWith("clan")) {
            configUrl = clanToAddress(apiUrl);
        } else if (!apiUrl.startsWith("http")) {
            configUrl = "http://" + configUrl;
        } else {
            configUrl = apiUrl;
        }
        System.out.println("API URL :" + configUrl);
        String configKey = TempKey;
        OkGo.<String>get(configUrl)
                .headers("User-Agent", userAgent)
                .headers("Accept", requestAccept)
                .execute(new AbsCallback<String>() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        try {
                            String json = response.body();
                            parseJson(apiUrl, json);
                            try {
                                File cacheDir = cache.getParentFile();
                                if (!cacheDir.exists())
                                    cacheDir.mkdirs();
                                if (cache.exists())
                                    cache.delete();
                                FileOutputStream fos = new FileOutputStream(cache);
                                fos.write(json.getBytes("UTF-8"));
                                fos.flush();
                                fos.close();
                            } catch (Throwable th) {
                                th.printStackTrace();
                            }
                            callback.success();
                        } catch (Throwable th) {
                            th.printStackTrace();
                            callback.error("解析配置失败");
                        }
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        if (cache.exists()) {
                            try {
                                parseJson(apiUrl, cache);
                                callback.success();
                                return;
                            } catch (Throwable th) {
                                th.printStackTrace();
                            }
                        }
                        callback.error("拉取配置失败\n" + (response.getException() != null ? response.getException().getMessage() : ""));
                    }

                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        String result = "";
                        if (response.body() == null) {
                            result = "";
                        } else {
                            result = FindResult(response.body().string(), configKey);
                        }
                        if (apiUrl.startsWith("clan")) {
                            result = clanContentFix(clanToAddress(apiUrl), result);
                        }
                        result = fixContentPath(apiUrl, result);
                        return result;
                    }
                });
    }

    /** 最近一次 jar 下载的技术细节（给首页空态展示「为什么没内容」） */
    private volatile String lastJarDiag = "";
    /** 最近一次 jar 失败的一句话原因；成功时清空 */
    private volatile String lastJarError = null;
    /** 同一时刻只允许一轮 jar 下载 */
    private static final Object JAR_DL_LOCK = new Object();
    private static boolean jarDownloading = false;

    public String getJarDiag() {
        return lastJarDiag;
    }

    public String getJarError() {
        return lastJarError;
    }

    /**
     * 随 APK 一起带上的组件包，按「配置里写的 md5」索引。
     * <p>
     * 为什么需要：有些源把 1~2MB 的 jar 放在 Cloudflare（qist → 172.67.202.242）
     * 或自家限流节点（肥猫 → 每次只给几十 KB 就 RST）上，配置 JSON 几十 KB 能秒回，
     * jar 却永远下不完 —— 用户看到的就是「接口连上了、就是没内容」。
     * 这些包内容是固定的，与其让每台电视都去海外节点重下一遍，
     * 不如直接塞进 APK：md5 对得上就免下载。
     * <p>
     * 只按 md5 精确匹配，绝不做「猜」—— 拿错包会让爬虫类找不到而报
     * NoClassDefFoundError，比下载失败更难排查。
     */
    private static final String[][] BUILTIN_JARS = {
            // md5, assets 里的文件名, 来源
            {"ea98a70ee6967e433c24e4941ede7fd8", "spider_qist.jar", "qist"},
            {"b63a0eb8852bb7ab06500c424ccc3dae", "spider_boxmi.jar", "盒子迷"},
    };

    /** 配置里的 md5 命中内置包时返回 assets 文件名，否则返回空串 */
    private static String builtinJarFor(String md5) {
        if (md5 == null || md5.trim().isEmpty()) return "";
        String m = md5.trim();
        for (String[] e : BUILTIN_JARS) {
            if (e[0].equalsIgnoreCase(m)) return e[1];
        }
        return "";
    }

    /** 把 assets 里的组件包复制出来（assets 不能直接给 DexClassLoader 用） */
    private static boolean copyAsset(String name, File dst) {
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = App.getInstance().getAssets().open(name);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.flush();
            return dst.length() > 0;
        } catch (Throwable th) {
            LOG.e("echo---copyAsset fail: " + name + " " + th.getMessage());
            return false;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
            try {
                if (out != null) out.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 用户手动放进 Download 根目录的组件包（微信「保存到手机」也落在附近），
     * 文件名固定几个常见写法；64KB 以下肯定是坏包/占位文件，别拿去 load。
     */
    private File findImportedJar() {
        String[] names = {"spider.jar", "tvbox_spider.jar", "tv_spider.jar", "spider(1).jar", "spider_1.jar"};
        try {
            File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dl != null) {
                for (String n : names) {
                    File f = new File(dl, n);
                    if (f.exists() && f.isFile() && f.length() > 64 * 1024) return f;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            File dir = App.getInstance().getExternalFilesDir(null);
            if (dir != null) {
                File f = new File(dir, "spider.jar");
                if (f.exists() && f.isFile() && f.length() > 64 * 1024) return f;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 加载 spider 组件。
     *
     * <p>缓存命中就直接用；接口换过包（md5 对不上）时<b>先把旧包降级跑起来</b>，
     * 后台再悄悄换新包 —— 否则接口一升级 jar，所有老用户当场白屏。
     *
     * <p>真正的下载交给 {@link JarFetcher}：那个客户端专门为「1MB 二进制、慢站、
     * 会断线」调过（30 秒读超时 / 强制 HTTP/1.1 / Range 续传 / 拦截页识别），
     * 全局 OkGo 那套 10 秒超时的配置下不完这种文件。
     */
    public void loadJar(boolean useCache, String spider, LoadConfigCallback callback) {
        String[] urls = spider.split(";md5;");
        String jarUrl = urls[0].trim();
        final String md5 = urls.length > 1 ? urls[1].trim() : "";
        final File cache = new File(App.getInstance().getFilesDir().getAbsolutePath()
                + "/csp/" + MD5.string2MD5(jarUrl) + ".jar");
        final File part = new File(cache.getAbsolutePath() + ".part");
        final File dir = cache.getParentFile();
        if (dir != null && !dir.exists()) dir.mkdirs();

        // ---------------------------------------------------------------
        // 1. 缓存
        // ---------------------------------------------------------------
        boolean degraded = false;
        if (cache.exists()) {
            boolean md5Match = md5.isEmpty()
                    ? !FileUtils.isWeekAgo(cache)     // 接口没给版本号：一周内算新
                    : md5.equalsIgnoreCase(MD5.getFileMd5(cache));
            boolean loaded = jarLoader.load(cache.getAbsolutePath());
            if (loaded && (useCache || md5Match)) {
                lastJarDiag = "命中缓存 " + (cache.length() / 1024) + "KB";
                lastJarError = null;
                callback.success();
                return;
            }
            if (loaded) {
                degraded = true;                       // 旧包能用，先让页面有内容
                lastJarDiag = "版本已过期，先用旧包（" + (cache.length() / 1024) + "KB）顶上";
                callback.success();
            } else {
                cache.delete();                        // 缓存本身坏了，留着只会反复失败
            }
        }

        // ---------------------------------------------------------------
        // 1.2 内置组件（随 APK 打的）
        // ---------------------------------------------------------------
        // 源站的 jar 放在 Cloudflare / 限流节点上时，配置几十 KB 能秒回、
        // 1~2MB 的包却永远下不完，表现就是「接口连上了但没内容」。
        // 这里按 md5 精确命中内置包，命中就落成标准缓存，下次启动直接走 1.。
        final String builtinName = builtinJarFor(md5);
        if (!builtinName.isEmpty()) {
            // 放在 cache 同目录、用 cache 路径派生的文件名，避免 dir 为 null
            File bt = new File(cache.getAbsolutePath() + ".builtin");
            boolean ready = bt.exists() && bt.length() > 0;
            if (!ready) ready = copyAsset(builtinName, bt);
            if (ready) {
                String got = MD5.getFileMd5(bt);
                if (md5.equalsIgnoreCase(got) && jarLoader.load(bt.getAbsolutePath())) {
                    copy(bt, cache);
                    lastJarDiag = "使用内置组件（" + (bt.length() / 1024) + "KB，无需下载）";
                    lastJarError = null;
                    post(callback::success);
                    return;
                }
                // 内置包坏了或与配置对不上（理论上不该发生），删掉别再试
                bt.delete();
                LOG.e("echo---builtin jar bad: " + builtinName + " got=" + got + " want=" + md5);
            }
        }

        // ---------------------------------------------------------------
        // 1.5 本地导入的组件
        // ---------------------------------------------------------------
        // 源站一旦对 jar 做「限速 + 短连接」，代码层怎么续传都没用 ——
        // 给用户留一条「自己把 jar 放进 Download 目录」的路，
        // 比让他对着空首页、还以为是 App 坏了要强。
        File imported = findImportedJar();
        if (imported != null) {
            if (jarLoader.load(imported.getAbsolutePath())) {
                if (copy(imported, cache)) {
                    lastJarDiag = "使用本地导入的组件（" + (imported.length() / 1024) + "KB，已写入缓存）";
                } else {
                    lastJarDiag = "使用本地导入的组件（" + (imported.length() / 1024) + "KB，写入缓存失败）";
                }
                lastJarError = null;
                post(callback::success);
                return;
            } else {
                lastJarDiag = "Download 里的 spider.jar 加载不了（很可能没下完整）";
            }
        }

        // ---------------------------------------------------------------
        // 2. 下载（放独立线程，不能占着调用方）
        // ---------------------------------------------------------------
        final boolean useOld = degraded;
        final boolean isImg = jarUrl.startsWith("img+");
        final String realUrl = isImg ? jarUrl.substring(4) : jarUrl;

        // 已经有一轮在下载：等它，绝不并发拉同一个 .part
        synchronized (JAR_DL_LOCK) {
            if (jarDownloading) {
                long deadline = System.currentTimeMillis() + 90_000L;
                while (jarDownloading && System.currentTimeMillis() < deadline) {
                    try {
                        JAR_DL_LOCK.wait(500L);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                }
                final String m = lastJarError;
                if (m == null) post(callback::success);
                else if (!useOld) post(() -> callback.error(m));
                return;
            }
            jarDownloading = true;
        }

        Thread t = new Thread(() -> {
            JarFetcher.Result r;
            if (isImg) {
                r = new JarFetcher.Result();
                try {
                    String txt = JarFetcher.fetchText(realUrl);
                    byte[] data = getImgJar(txt);
                    if (data == null || data.length == 0) {
                        r.err = "组件地址里没有解出有效数据";
                    } else {
                        FileOutputStream fos = new FileOutputStream(part);
                        fos.write(data);
                        fos.flush();
                        fos.close();
                        r.file = part;
                        r.bytes = data.length;
                        r.expected = data.length;
                        r.ok = md5.isEmpty() || md5.equalsIgnoreCase(MD5.getFileMd5(part));
                    }
                } catch (Throwable th) {
                    r.err = "组件数据解包失败：" + th.getMessage();
                }
            } else {
                // 残包跨天就别续了：站点可能已经换了新包，接在旧字节后面拼出来的文件
                // 一定是坏的，还会一直校验不过。
                if (part.exists()
                        && System.currentTimeMillis() - part.lastModified() > 12 * 3600_000L) {
                    part.delete();
                }
                r = JarFetcher.fetch(realUrl, part, md5);
            }

            lastJarDiag = r.diag == null ? "" : r.diag;
            LOG.i("echo---jar fetch: ok=" + r.ok + " bytes=" + r.bytes
                    + " expected=" + r.expected + " " + lastJarDiag);

            // -----------------------------------------------------------
            // 3. 落地：先用 JarLoader 真的 load 一遍，能跑才替换缓存。
            //    不先信 md5：站点把 md5 写错 / 缓存页脏了的情况很常见，
            //    包本身是好的却因为一个字符串被丢掉，用户只能干瞪眼。
            // -----------------------------------------------------------
            boolean installed = false;
            if (r.file != null && r.file.exists() && r.file.length() > 0) {
                String got = MD5.getFileMd5(r.file);
                boolean md5Ok = md5.isEmpty() || md5.equalsIgnoreCase(got);
                try {
                    installed = jarLoader.load(r.file.getAbsolutePath());
                } catch (Throwable th) {
                    LOG.e("echo---jar Loader threw: " + th.getMessage());
                }
                if (installed) {
                    if (!md5Ok)
                        LOG.i("echo---jar md5 不符但能加载，接受 want=" + md5 + " got=" + got);
                    File old = cache.exists() ? cache : null;
                    if (old != null) old.delete();
                    if (!r.file.renameTo(cache)) {
                        // 极少数机型跨挂载点 rename 会失败，退化成复制
                        if (copy(r.file, cache)) r.file.delete();
                        else {
                            installed = false;
                            LOG.e("echo---jar 写入缓存失败");
                        }
                    }
                    if (installed) lastJarDiag = lastJarDiag + " 已生效";
                } else {
                    // 关键：**半截的包要留着**。下一轮 Range 续传就靠它，
                    // 删掉等于每次失败都从 0 开始，慢网永远下不完。
                    boolean complete = r.expected > 0 && r.file.length() >= r.expected;
                    if (complete) {
                        r.file.delete();                   // 长度够了还 load 不起来：确实是坏包
                    } else {
                        lastJarDiag = lastJarDiag + " 残包保留(" + r.file.length() / 1024
                                + "KB)，下次接着下";
                    }
                }
            }

            final boolean ok = installed;
            final String msg = ok ? "" : (r.err == null || r.err.isEmpty() ? "组件下载失败" : r.err);
            lastJarError = ok ? null : msg;
            synchronized (JAR_DL_LOCK) {
                jarDownloading = false;
                JAR_DL_LOCK.notifyAll();
            }
            post(() -> {
                if (ok) {
                    LOG.i("echo---jar 新包已生效");
                    if (!useOld) callback.success();
                } else if (!useOld) {
                    callback.error(msg);
                }
            });
        }, "jar-fetch");
        t.setDaemon(true);
        t.start();
    }

    private static void post(Runnable r) {
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
        } catch (Throwable th) {
            r.run();
        }
    }

    private static boolean copy(File src, File dst) {
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.flush();
            return true;
        } catch (Throwable th) {
            return false;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
            try {
                if (out != null) out.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 中文域名（http://肥猫.net/tv）必须先转 punycode，否则 OkHttp 抛
     * IllegalArgumentException，用户看到的就是「加载失败」。
     */
    public static String toAsciiUrl(String url) {
        if (url == null || url.length() == 0) return "";
        int i = url.indexOf("://");
        if (i <= 0) return url;
        String head = url.substring(0, i + 3);
        String rest = url.substring(i + 3);
        int slash = rest.indexOf('/');
        String hostPort = slash < 0 ? rest : rest.substring(0, slash);
        String tail = slash < 0 ? "" : rest.substring(slash);
        String host = hostPort;
        String port = "";
        int colon = hostPort.lastIndexOf(':');
        if (colon > 0 && colon > hostPort.lastIndexOf(']')) {
            host = hostPort.substring(0, colon);
            port = hostPort.substring(colon);
        }
        boolean ascii = true;
        for (int k = 0; k < host.length(); k++) {
            if (host.charAt(k) > 127) {
                ascii = false;
                break;
            }
        }
        if (ascii) return url;
        try {
            host = java.net.IDN.toASCII(host);
        } catch (Throwable ignored) {
        }
        return head + host + port + tail;
    }

    private void parseJson(String apiUrl, File f) throws Throwable {
        System.out.println("从本地缓存加载" + f.getAbsolutePath());
        BufferedReader bReader = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String s = "";
        while ((s = bReader.readLine()) != null) {
            sb.append(s + "\n");
        }
        bReader.close();
        parseJson(apiUrl, sb.toString());
    }

    private static  String jarCache ="true";
    private void parseJson(String apiUrl, String jsonStr) {
//        pyLoader.setConfig(jsonStr);
        JsonObject infoJson = new Gson().fromJson(jsonStr, JsonObject.class);
        jarCache = DefaultConfig.safeJsonString(infoJson, "jarCache", "true");
        // spider
        spider = DefaultConfig.safeJsonString(infoJson, "spider", "");
        // wallpaper
        wallpaper = DefaultConfig.safeJsonString(infoJson, "wallpaper", "");
        // 直播播放请求头
        livePlayHeaders = infoJson.getAsJsonArray("livePlayHeaders");
        // 远端站点源
        // 换接口（设置→接口设置里扫码/推送新地址）时必须先清空，否则旧接口的站点
        // 会留在 sourceBeanList 里：Hawk 的 HOME_API 还是旧 key 时 getSource(home)
        // 仍能命中旧站点，首页就继续走旧接口，而 jar 已经换成新的 → 全空。
        sourceBeanList.clear();
        mHomeSource = null;
        SourceBean firstSite = null;
        JsonArray sites = infoJson.has("video") ? infoJson.getAsJsonObject("video").getAsJsonArray("sites") : infoJson.get("sites").getAsJsonArray();
        for (JsonElement opt : sites) {
            JsonObject obj = (JsonObject) opt;
            SourceBean sb = new SourceBean();
            String siteKey = obj.get("key").getAsString().trim();
            sb.setKey(siteKey);
            sb.setName(obj.has("name")?obj.get("name").getAsString().trim():siteKey);
            sb.setType(obj.get("type").getAsInt());
            sb.setApi(obj.get("api").getAsString().trim());
            sb.setSearchable(DefaultConfig.safeJsonInt(obj, "searchable", 1));
            sb.setQuickSearch(DefaultConfig.safeJsonInt(obj, "quickSearch", 1));
            if(siteKey.startsWith("py_")){
                sb.setFilterable(1);
            }else {
                sb.setFilterable(DefaultConfig.safeJsonInt(obj, "filterable", 1));
            }
            sb.setHide(DefaultConfig.safeJsonInt(obj, "hide", 0));
            sb.setPlayerUrl(DefaultConfig.safeJsonString(obj, "playUrl", ""));
            sb.setExt(DefaultConfig.safeJsonString(obj, "ext", ""));
            sb.setJar(DefaultConfig.safeJsonString(obj, "jar", ""));
            sb.setPlayerType(DefaultConfig.safeJsonInt(obj, "playerType", -1));
            sb.setCategories(DefaultConfig.safeJsonStringList(obj, "categories"));
            sb.setClickSelector(DefaultConfig.safeJsonString(obj, "click", ""));
            sb.setStyle(DefaultConfig.safeJsonString(obj, "style", ""));
            if (firstSite == null && sb.getHide() == 0)
                firstSite = sb;
            sourceBeanList.put(siteKey, sb);
        }
        if (sourceBeanList != null && sourceBeanList.size() > 0) {
            String home = Hawk.get(HawkConfig.HOME_API, "");
            SourceBean sh = getSource(home);
            if (sh == null || sh.getHide() == 1)
                setSourceBean(firstSite);
            else
                setSourceBean(sh);
        }
        // 需要使用vip解析的flag
        vipParseFlags = DefaultConfig.safeJsonStringList(infoJson, "flags");
        // 解析地址
        parseBeanList.clear();
        if (infoJson.has("parses")) {
            JsonArray parses = infoJson.get("parses").getAsJsonArray();
            for (JsonElement opt : parses) {
                JsonObject obj = (JsonObject) opt;
                ParseBean pb = new ParseBean();
                pb.setName(obj.get("name").getAsString().trim());
                pb.setUrl(obj.get("url").getAsString().trim());
                String ext = obj.has("ext") ? obj.get("ext").getAsJsonObject().toString() : "";
                pb.setExt(ext);
                pb.setType(DefaultConfig.safeJsonInt(obj, "type", 0));
                parseBeanList.add(pb);
            }
            if(!parseBeanList.isEmpty()){
                addSuperParse();
            }
        }
        // 获取默认解析
        if (parseBeanList != null && parseBeanList.size() > 0) {
            String defaultParse = Hawk.get(HawkConfig.DEFAULT_PARSE, "");
            if (!TextUtils.isEmpty(defaultParse))
                for (ParseBean pb : parseBeanList) {
                    if (pb.getName().equals(defaultParse))
                        setDefaultParse(pb);
                }
            if (mDefaultParse == null)
                setDefaultParse(parseBeanList.get(0));
        }

        // takagen99: Check if Live URL is setup in Settings, if no, get from File Config
        liveChannelGroupList.clear();           //修复从后台切换重复加载频道列表
        String liveURL = Hawk.get(HawkConfig.LIVE_URL, "");
        String epgURL  = Hawk.get(HawkConfig.EPG_URL, "");

        String liveURL_final = null;
        try {
            if (infoJson.has("lives") && infoJson.get("lives").getAsJsonArray() != null) {
                JsonObject livesOBJ = infoJson.get("lives").getAsJsonArray().get(0).getAsJsonObject();
                String lives = livesOBJ.toString();
                int index = lives.indexOf("proxy://");
                if (index != -1) {
                    int endIndex = lives.lastIndexOf("\"");
                    String url = lives.substring(index, endIndex);
                    url = DefaultConfig.checkReplaceProxy(url);

                    //clan
                    String extUrl = Uri.parse(url).getQueryParameter("ext");
                    if (extUrl != null && !extUrl.isEmpty()) {
                        String extUrlFix;
                        if (extUrl.startsWith("http") || extUrl.startsWith("clan://")) {
                            extUrlFix = extUrl;
                        } else {
                            extUrlFix = new String(Base64.decode(extUrl, Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP), "UTF-8");
                        }
                        if (extUrlFix.startsWith("clan://")) {
                            extUrlFix = clanContentFix(clanToAddress(apiUrl), extUrlFix);
                        }

                        // takagen99: Capture Live URL into Config
                        System.out.println("Live URL :" + extUrlFix);
                        putLiveHistory(extUrlFix);
                        // Overwrite with Live URL from Settings
                        if (StringUtils.isBlank(liveURL)) {
                            Hawk.put(HawkConfig.LIVE_URL, extUrlFix);
                        } else {
                            extUrlFix = liveURL;
                        }

                        // Final Live URL
                        liveURL_final = extUrlFix;

//                    // Encoding the Live URL
//                    extUrlFix = Base64.encodeToString(extUrlFix.getBytes("UTF-8"), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP);
//                    url = url.replace(extUrl, extUrlFix);
                    }

                    // takagen99 : Getting EPG URL from File Config & put into Settings
                    if (livesOBJ.has("epg")) {
                        String epg = livesOBJ.get("epg").getAsString();
                        System.out.println("EPG URL :" + epg);
                        putEPGHistory(epg);
                        // Overwrite with EPG URL from Settings
                        if (StringUtils.isBlank(epgURL)) {
                            Hawk.put(HawkConfig.EPG_URL, epg);
                        } else {
                            Hawk.put(HawkConfig.EPG_URL, epgURL);
                        }
                    }

//                // Populate Live Channel Listing
//                LiveChannelGroup liveChannelGroup = new LiveChannelGroup();
//                liveChannelGroup.setGroupName(url);
//                liveChannelGroupList.add(liveChannelGroup);

                } else {

                    // if FongMi Live URL Formatting exists
                    if (!lives.contains("type")) {
                        loadLives(infoJson.get("lives").getAsJsonArray());
                    } else {
                        JsonObject fengMiLives = infoJson.get("lives").getAsJsonArray().get(0).getAsJsonObject();
                        Hawk.put(HawkConfig.LIVE_PLAYER_TYPE, DefaultConfig.safeJsonInt(fengMiLives, "playerType", -1));
                        String type = fengMiLives.get("type").getAsString();
                        if (type.equals("0")) {
                            String url = fengMiLives.get("url").getAsString();

                            // takagen99 : Getting EPG URL from File Config & put into Settings
                            if (fengMiLives.has("epg")) {
                                String epg = fengMiLives.get("epg").getAsString();
                                System.out.println("EPG URL :" + epg);
                                putEPGHistory(epg);
                                // Overwrite with EPG URL from Settings
                                if (StringUtils.isBlank(epgURL)) {
                                    Hawk.put(HawkConfig.EPG_URL, epg);
                                } else {
                                    Hawk.put(HawkConfig.EPG_URL, epgURL);
                                }
                            }

                            if (url.startsWith("http")) {
                                // takagen99: Capture Live URL into Settings
                                System.out.println("Live URL :" + url);
                                putLiveHistory(url);
                                // Overwrite with Live URL from Settings
                                if (StringUtils.isBlank(liveURL)) {
                                    Hawk.put(HawkConfig.LIVE_URL, url);
                                } else {
                                    url = liveURL;
                                }

                                // Final Live URL
                                liveURL_final = url;

//                            url = Base64.encodeToString(url.getBytes("UTF-8"), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP);
                            }
                        }
                    }
                }

                // takagen99: Load Live Channel from settings URL (WIP)
                if (StringUtils.isBlank(liveURL_final)) {
                    liveURL_final = liveURL;
                }
                liveURL_final = Base64.encodeToString(liveURL_final.getBytes("UTF-8"), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP);
                liveURL_final = "http://127.0.0.1:9978/proxy?do=live&type=txt&ext=" + liveURL_final;
                LiveChannelGroup liveChannelGroup = new LiveChannelGroup();
                liveChannelGroup.setGroupName(liveURL_final);
                liveChannelGroupList.add(liveChannelGroup);
            }


        } catch (Throwable th) {
            th.printStackTrace();
        }

        // Video parse rule for host
        if (infoJson.has("rules")) {
            VideoParseRuler.clearRule();
            for(JsonElement oneHostRule : infoJson.getAsJsonArray("rules")) {
                JsonObject obj = (JsonObject) oneHostRule;
                //嗅探过滤规则
                if (obj.has("host")) {
                    String host = obj.get("host").getAsString();
                    if (obj.has("rule")) {
                        JsonArray ruleJsonArr = obj.getAsJsonArray("rule");
                        ArrayList<String> rule = new ArrayList<>();
                        for (JsonElement one : ruleJsonArr) {
                            String oneRule = one.getAsString();
                            rule.add(oneRule);
                        }
                        if (rule.size() > 0) {
                            VideoParseRuler.addHostRule(host, rule);
                        }
                    }
                    if (obj.has("filter")) {
                        JsonArray filterJsonArr = obj.getAsJsonArray("filter");
                        ArrayList<String> filter = new ArrayList<>();
                        for (JsonElement one : filterJsonArr) {
                            String oneFilter = one.getAsString();
                            filter.add(oneFilter);
                        }
                        if (filter.size() > 0) {
                            VideoParseRuler.addHostFilter(host, filter);
                        }
                    }
                }
                //广告过滤规则
                if (obj.has("hosts") && obj.has("regex")) {
                    ArrayList<String> rule = new ArrayList<>();
                    ArrayList<String> ads = new ArrayList<>();
                    JsonArray regexArray = obj.getAsJsonArray("regex");
                    for (JsonElement one : regexArray) {
                        String regex = one.getAsString();
                        if (M3U8.isAd(regex)) ads.add(regex);
                        else rule.add(regex);
                    }
                    JsonArray array = obj.getAsJsonArray("hosts");
                    for (JsonElement one : array) {
                        String host = one.getAsString();
                        VideoParseRuler.addHostRule(host, rule);
                        VideoParseRuler.addHostRegex(host, ads);
                    }
                }
                //嗅探脚本规则 如 click
                if (obj.has("hosts") && obj.has("script")) {
                    ArrayList<String> scripts = new ArrayList<>();
                    JsonArray scriptArray = obj.getAsJsonArray("script");
                    for (JsonElement one : scriptArray) {
                        String script = one.getAsString();
                        scripts.add(script);
                    }
                    JsonArray array = obj.getAsJsonArray("hosts");
                    for (JsonElement one : array) {
                        String host = one.getAsString();
                        VideoParseRuler.addHostScript(host, scripts);
                    }
                }
            }
        }

        String defaultIJKADS = "{\"ijk\":[{\"options\":[{\"name\":\"opensles\",\"category\":4,\"value\":\"0\"},{\"name\":\"overlay-format\",\"category\":4,\"value\":\"842225234\"},{\"name\":\"framedrop\",\"category\":4,\"value\":\"0\"},{\"name\":\"soundtouch\",\"category\":4,\"value\":\"1\"},{\"name\":\"start-on-prepared\",\"category\":4,\"value\":\"1\"},{\"name\":\"http-detect-rangeupport\",\"category\":1,\"value\":\"0\"},{\"name\":\"fflags\",\"category\":1,\"value\":\"fastseek\"},{\"name\":\"skip_loop_filter\",\"category\":2,\"value\":\"48\"},{\"name\":\"reconnect\",\"category\":4,\"value\":\"1\"},{\"name\":\"enable-accurate-seek\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-auto-rotate\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-handle-resolution-change\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-hevc\",\"category\":4,\"value\":\"0\"},{\"name\":\"dns_cache_timeout\",\"category\":1,\"value\":\"600000000\"}],\"group\":\"软解码\"},{\"options\":[{\"name\":\"opensles\",\"category\":4,\"value\":\"0\"},{\"name\":\"overlay-format\",\"category\":4,\"value\":\"842225234\"},{\"name\":\"framedrop\",\"category\":4,\"value\":\"0\"},{\"name\":\"soundtouch\",\"category\":4,\"value\":\"1\"},{\"name\":\"start-on-prepared\",\"category\":4,\"value\":\"1\"},{\"name\":\"http-detect-rangeupport\",\"category\":1,\"value\":\"0\"},{\"name\":\"fflags\",\"category\":1,\"value\":\"fastseek\"},{\"name\":\"skip_loop_filter\",\"category\":2,\"value\":\"48\"},{\"name\":\"reconnect\",\"category\":4,\"value\":\"1\"},{\"name\":\"enable-accurate-seek\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-auto-rotate\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-handle-resolution-change\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-hevc\",\"category\":4,\"value\":\"1\"},{\"name\":\"dns_cache_timeout\",\"category\":1,\"value\":\"600000000\"}],\"group\":\"硬解码\"}],\"ads\":[\"mimg.0c1q0l.cn\",\"www.googletagmanager.com\",\"www.google-analytics.com\",\"mc.usihnbcq.cn\",\"mg.g1mm3d.cn\",\"mscs.svaeuzh.cn\",\"cnzz.hhttm.top\",\"tp.vinuxhome.com\",\"cnzz.mmstat.com\",\"www.baihuillq.com\",\"s23.cnzz.com\",\"z3.cnzz.com\",\"c.cnzz.com\",\"stj.v1vo.top\",\"z12.cnzz.com\",\"img.mosflower.cn\",\"tips.gamevvip.com\",\"ehwe.yhdtns.com\",\"xdn.cqqc3.com\",\"www.jixunkyy.cn\",\"sp.chemacid.cn\",\"hm.baidu.com\",\"s9.cnzz.com\",\"z6.cnzz.com\",\"um.cavuc.com\",\"mav.mavuz.com\",\"wofwk.aoidf3.com\",\"z5.cnzz.com\",\"xc.hubeijieshikj.cn\",\"tj.tianwenhu.com\",\"xg.gars57.cn\",\"k.jinxiuzhilv.com\",\"cdn.bootcss.com\",\"ppl.xunzhuo123.com\",\"xomk.jiangjunmh.top\",\"img.xunzhuo123.com\",\"z1.cnzz.com\",\"s13.cnzz.com\",\"xg.huataisangao.cn\",\"z7.cnzz.com\",\"xg.huataisangao.cn\",\"z2.cnzz.com\",\"s96.cnzz.com\",\"q11.cnzz.com\",\"thy.dacedsfa.cn\",\"xg.whsbpw.cn\",\"s19.cnzz.com\",\"z8.cnzz.com\",\"s4.cnzz.com\",\"f5w.as12df.top\",\"ae01.alicdn.com\",\"www.92424.cn\",\"k.wudejia.com\",\"vivovip.mmszxc.top\",\"qiu.xixiqiu.com\",\"cdnjs.hnfenxun.com\",\"cms.qdwght.com\"]}";
        JsonObject defaultJson = new Gson().fromJson(defaultIJKADS, JsonObject.class);
        // 广告地址
        if(AdBlocker.isEmpty()){
            //默认广告拦截
            for (JsonElement host : defaultJson.getAsJsonArray("ads")) {
                AdBlocker.addAdHost(host.getAsString());
            }
            //追加的广告拦截
            if(infoJson.has("ads")){
                for (JsonElement host : infoJson.getAsJsonArray("ads")) {
                    if(!AdBlocker.hasHost(host.getAsString())){
                        AdBlocker.addAdHost(host.getAsString());
                    }
                }
            }
        }
        // IJK解码配置
        if (ijkCodes == null) {
            ijkCodes = new ArrayList<>();
            boolean foundOldSelect = false;
            String ijkCodec = Hawk.get(HawkConfig.IJK_CODEC, "");
            JsonArray ijkJsonArray = infoJson.has("ijk") ? infoJson.get("ijk").getAsJsonArray() : defaultJson.get("ijk").getAsJsonArray();
            for (JsonElement opt : ijkJsonArray) {
                JsonObject obj = (JsonObject) opt;
                String name = obj.get("group").getAsString();
                LinkedHashMap<String, String> baseOpt = new LinkedHashMap<>();
                for (JsonElement cfg : obj.get("options").getAsJsonArray()) {
                    JsonObject cObj = (JsonObject) cfg;
                    String key = cObj.get("category").getAsString() + "|" + cObj.get("name").getAsString();
                    String val = cObj.get("value").getAsString();
                    baseOpt.put(key, val);
                }
                IJKCode codec = new IJKCode();
                codec.setName(name);
                codec.setOption(baseOpt);
                if (name.equals(ijkCodec) || TextUtils.isEmpty(ijkCodec)) {
                    codec.selected(true);
                    ijkCodec = name;
                    foundOldSelect = true;
                } else {
                    codec.selected(false);
                }
                ijkCodes.add(codec);
            }
            if (!foundOldSelect && ijkCodes.size() > 0) {
                ijkCodes.get(0).selected(true);
            }
        }
    }

    private void putLiveHistory(String url) {
        if (!url.isEmpty()) {
            ArrayList<String> liveHistory = Hawk.get(HawkConfig.LIVE_HISTORY, new ArrayList<String>());
            if (!liveHistory.contains(url))
                liveHistory.add(0, url);
            if (liveHistory.size() > 20)
                liveHistory.remove(20);
            Hawk.put(HawkConfig.LIVE_HISTORY, liveHistory);
        }
    }

    public static void putEPGHistory(String url) {
        if (!url.isEmpty()) {
            ArrayList<String> epgHistory = Hawk.get(HawkConfig.EPG_HISTORY, new ArrayList<String>());
            if (!epgHistory.contains(url))
                epgHistory.add(0, url);
            if (epgHistory.size() > 20)
                epgHistory.remove(20);
            Hawk.put(HawkConfig.EPG_HISTORY, epgHistory);
        }
    }

    public void loadLives(JsonArray livesArray) {
        liveChannelGroupList.clear();
        int groupIndex = 0;
        int channelIndex = 0;
        int channelNum = 0;
        for (JsonElement groupElement : livesArray) {
            LiveChannelGroup liveChannelGroup = new LiveChannelGroup();
            liveChannelGroup.setLiveChannels(new ArrayList<LiveChannelItem>());
            liveChannelGroup.setGroupIndex(groupIndex++);
            String groupName = ((JsonObject) groupElement).get("group").getAsString().trim();
            String[] splitGroupName = groupName.split("_", 2);
            liveChannelGroup.setGroupName(splitGroupName[0]);
            if (splitGroupName.length > 1)
                liveChannelGroup.setGroupPassword(splitGroupName[1]);
            else
                liveChannelGroup.setGroupPassword("");
            channelIndex = 0;
            for (JsonElement channelElement : ((JsonObject) groupElement).get("channels").getAsJsonArray()) {
                JsonObject obj = (JsonObject) channelElement;
                LiveChannelItem liveChannelItem = new LiveChannelItem();
                liveChannelItem.setChannelName(obj.get("name").getAsString().trim());
                liveChannelItem.setChannelIndex(channelIndex++);
                liveChannelItem.setChannelNum(++channelNum);
                ArrayList<String> urls = DefaultConfig.safeJsonStringList(obj, "urls");
                ArrayList<String> sourceNames = new ArrayList<>();
                ArrayList<String> sourceUrls = new ArrayList<>();
                int sourceIndex = 1;
                for (String url : urls) {
                    String[] splitText = url.split("\\$", 2);
                    sourceUrls.add(splitText[0]);
                    if (splitText.length > 1)
                        sourceNames.add(splitText[1]);
                    else
                        sourceNames.add("源" + sourceIndex);
                    sourceIndex++;
                }
                liveChannelItem.setChannelSourceNames(sourceNames);
                liveChannelItem.setChannelUrls(sourceUrls);
                liveChannelGroup.getLiveChannels().add(liveChannelItem);
            }
            liveChannelGroupList.add(liveChannelGroup);
        }
    }

    public String getSpider() {
        return spider;
    }

    public Spider getCSP(SourceBean sourceBean) {
        if (sourceBean.getApi().endsWith(".js") || sourceBean.getApi().contains(".js?")){
            return jsLoader.getSpider(sourceBean.getKey(), sourceBean.getApi(), sourceBean.getExt(), sourceBean.getJar());
        }else if (sourceBean.getApi().contains(".py")) {
            return pyLoader.getSpider(sourceBean.getKey(), sourceBean.getApi(), sourceBean.getExt());
        } else {
            return jarLoader.getSpider(sourceBean.getKey(), sourceBean.getApi(), sourceBean.getExt(), sourceBean.getJar());
        }
    }

    public Spider getPyCSP(String url) {
        return pyLoader.getSpider(MD5.string2MD5(url), url, "");
    }
	
    public Object[] proxyLocal(Map<String,String> param) {
        if ("js".equals(param.get("do"))) {
            return jsLoader.proxyInvoke(param);
        }
        SourceBean sourceBean = ApiConfig.get().getHomeSourceBean(); 
        String apiString = sourceBean.getApi();
        return apiString.contains(".py") ? pyLoader.proxyInvoke(param) : jarLoader.proxyInvoke(param);
    }

    public JSONObject jsonExt(String key, LinkedHashMap<String, String> jxs, String url) {
        return jarLoader.jsonExt(key, jxs, url);
    }

    public JSONObject jsonExtMix(String flag, String key, String name, LinkedHashMap<String, HashMap<String, String>> jxs, String url) {
        return jarLoader.jsonExtMix(flag, key, name, jxs, url);
    }

    public interface LoadConfigCallback {
        void success();

        void retry();

        void error(String msg);
    }

    public interface FastParseCallback {
        void success(boolean parse, String url, Map<String, String> header);

        void fail(int code, String msg);
    }

    public SourceBean getSource(String key) {
        if (!sourceBeanList.containsKey(key))
            return null;
        return sourceBeanList.get(key);
    }

    public void setSourceBean(SourceBean sourceBean) {
        this.mHomeSource = sourceBean;
        Hawk.put(HawkConfig.HOME_API, sourceBean.getKey());
    }

    public void setDefaultParse(ParseBean parseBean) {
        if (this.mDefaultParse != null)
            this.mDefaultParse.setDefault(false);
        this.mDefaultParse = parseBean;
        Hawk.put(HawkConfig.DEFAULT_PARSE, parseBean.getName());
        parseBean.setDefault(true);
    }

    public ParseBean getDefaultParse() {
        return mDefaultParse;
    }

    public List<SourceBean> getSourceBeanList() {
        return new ArrayList<>(sourceBeanList.values());
    }

    public List<ParseBean> getParseBeanList() {
        return parseBeanList;
    }

    public List<String> getVipParseFlags() {
        return vipParseFlags;
    }

    public SourceBean getHomeSourceBean() {
        return mHomeSource == null ? emptyHome : mHomeSource;
    }

    public List<LiveChannelGroup> getChannelGroupList() {
        return liveChannelGroupList;
    }

    public List<IJKCode> getIjkCodes() {
        return ijkCodes;
    }

    public IJKCode getCurrentIJKCode() {
        String codeName = Hawk.get(HawkConfig.IJK_CODEC, "");
        return getIJKCodec(codeName);
    }

    public IJKCode getIJKCodec(String name) {
        for (IJKCode code : ijkCodes) {
            if (code.getName().equals(name))
                return code;
        }
        return ijkCodes.get(0);
    }

    public JsonArray getLivePlayHeaders() {
        return livePlayHeaders;
    }

    String clanToAddress(String lanLink) {
        if (lanLink.startsWith("clan://localhost/")) {
            return lanLink.replace("clan://localhost/", ControlManager.get().getAddress(true) + "file/");
        } else {
            String link = lanLink.substring(7);
            int end = link.indexOf('/');
            return "http://" + link.substring(0, end) + "/file/" + link.substring(end + 1);
        }
    }

    String clanContentFix(String lanLink, String content) {
        String fix = lanLink.substring(0, lanLink.indexOf("/file/") + 6);
        return content.replace("clan://", fix);
    }

    String fixContentPath(String url, String content) {
        if (content.contains("\"./")) {
            url=url.replace("file://","clan://localhost/");
            if (!url.startsWith("http") && !url.startsWith("clan://")) {
                url = "http://" + url;
            }
            if (url.startsWith("clan://")) url = clanToAddress(url);
            content = content.replace("./", url.substring(0, url.lastIndexOf("/") + 1));
        }
        return content;
    }

    public void clearJarLoader()
    {
        jarLoader.clear();
    }
    private void addSuperParse(){
        ParseBean superPb = new ParseBean();
        superPb.setName("超级解析");
        superPb.setUrl("SuperParse");
        superPb.setExt("");
        superPb.setType(4);
        parseBeanList.add(0, superPb);
    }
    public void clearLoader(){
        jarLoader.clear();
        pyLoader.clear();
        jsLoader.clear();
    }
    String miTV(String url) {
        if (url.startsWith("p") || url.startsWith("mitv")) {

        }
        return url;
    }

}
