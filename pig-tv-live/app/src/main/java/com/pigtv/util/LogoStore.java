package com.pigtv.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 台标图片的下载与本地缓存。
 *
 * <p>为什么不直接把 URL 丢给 Glide 加载：台标站点的 **AAAA 记录在国内是被污染的**
 * （解出来是 Facebook 的 {@code 2a03:2880:…:face:b00c}，连上去必然等到超时才报错，
 * 阿里 DNS 也一样），而 Android 的 HttpURLConnection 会先试 IPv6。
 * 结果就是「一半台标能出来、一半永远转圈」，用户看到的是一整面空白方块。
 *
 * <p>所以这里自己走 OkHttp，并在 DNS 层把 IPv6 全部过滤掉 —— 只保留 A 记录，
 * 连接不再有那 10 秒的假等待。下载完落到 {@code cache/channel_logo/} 下，
 * 再交给 Glide 解码显示（Glide 只负责解码和内存缓存，不负责网络）。
 *
 * <p>缓存按**文件名**存（CCTV4.png 而不是台名）：同一个台在源里可能叫
 * 「CCTV-4 中文国际」「CCTV-4」两个名字，但台标是同一个文件，不该下两遍。
 */
public final class LogoStore {

    /** 三个线程：够铺满一屏，又不会把上行打满（台标站点对并发也不友好） */
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    /**
     * 预热专用池，和 {@link #POOL} 分开。
     *
     * <p>刚装上时预热会一口气排 246 个台标进去。早先这些任务和前台按需加载
     * 共用一个池，结果队列被预热填满、首屏那二三十格排到最后才轮到，
     * 用户看到的是整面墙的灰占位图（等半分钟才陆续变成台标）。
     * 两个池各管一头，前台永远不用排队。
     */
    private static final ExecutorService PRELOAD_POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** 正在下载的文件名，避免同一个台标被几格同时请求时下好几份 */
    private static final Set<String> IN_FLIGHT =
            Collections.synchronizedSet(new HashSet<String>());

    /**
     * 同一个台标正在下载时，后来要它的那几格挂在这里，下完一起通知。
     *
     * <p>为什么不能像以前那样直接回 null：上层 {@code bindLogo} 把 null 当成
     * 「这个台没有台标」，会记进 {@link ChannelLogo#markMissing}，之后这个台**永久**
     * 显示占位图。预热和首屏同时要同一个台标是常态（前几十个台两边都会碰上），
     * 老写法一装上就有几十个台被误判成没台标。
     */
    private static final Map<String, List<Callback>> WAITERS = new HashMap<>();

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .dns(new Ipv4Only())
            .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .build();

    private LogoStore() {
    }

    // ------------------------------------------------------------------
    //  黑版台标（浅色主题用）
    // ------------------------------------------------------------------
    //  CCTV 系台标是「白字 + 透明底」，白字落在浅色卡片上会整块消失。
    //  官方其实有黑字版（黑字母 + 红 C + 黑块白数字），它和白版就是同一张图
    //  的亮度反转 —— 所以不找第二个图源，直接把缓存里的白版反相一份存下来。

    /** 已经生成好的黑版（key = 白版文件的绝对路径） */
    private static final Map<String, File> DARK_CACHE =
            Collections.synchronizedMap(new HashMap<String, File>());
    /** 判定过「不是白字标，不需要黑版」的文件 */
    private static final Set<String> NO_DARK =
            Collections.synchronizedSet(new HashSet<String>());

    /**
     * 统一入口：拿到这一格**最终该显示**的台标文件。
     *
     * <p>深色主题直接回白版（本地有就同步回调，行为和 {@link #cached} 一致）；
     * 浅色主题先看这个台标是不是白字标（不透明像素里亮度 &gt; 200 的占比 &gt; 55%），
     * 是的话第一次会在后台把黑版生成出来再回调，之后直接命中。
     *
     * <p>{@code file == null} 表示这几条地址都拿不到台标。
     */
    public static void load(Context ctx, String[] urls, boolean light, Callback cb) {
        File c = cached(ctx, urls);
        if (c != null) {
            if (!light) {
                if (cb != null) cb.onResult(c);
                return;
            }
            File d = DARK_CACHE.get(c.getPath());
            if (d != null && d.exists()) {
                if (cb != null) cb.onResult(d);
                return;
            }
            if (NO_DARK.contains(c.getPath())) {
                if (cb != null) cb.onResult(c);
                return;
            }
            finishDark(ctx.getApplicationContext(), c, cb);
            return;
        }
        final Context app = ctx.getApplicationContext();
        final boolean wantDark = light;
        fetch(ctx, urls, new Callback() {
            @Override
            public void onResult(File file) {
                if (file == null || !wantDark) {
                    post(cb, file);
                    return;
                }
                finishDark(app, file, cb);
            }
        });
    }

    /** 白版的黑版判定 + 生成，在后台线程跑，完了主线程回调 */
    private static void finishDark(final Context app, final File white, final Callback cb) {
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                // 两格同时要同一个台标的黑版是常事（同一屏里 CCTV 系一片）：
                // 按文件路径串行化，避免两个线程写同一个 .part 把图写坏
                File d;
                synchronized (white.getPath().intern()) {
                    d = buildDark(app, white);
                }
                if (d == null) {
                    NO_DARK.add(white.getPath());
                    post(cb, white);
                } else {
                    DARK_CACHE.put(white.getPath(), d);
                    post(cb, d);
                }
            }
        });
    }

    /**
     * 反相出黑版文件。不是白字标（白像素占比不超 55%）返回 null。
     *
     * <p>规则和设计稿 build.py 里那版完全一致：
     * 红色像素（红 C）原样保留，其余亮度反转（白→黑、黑→白，抗锯齿灰阶跟着走，
     * 色相不变 —— 按反转后的亮度等比缩放 RGB）。
     * 文件名前缀 {@code dark_}，和白版并排放在同一个缓存目录里。
     */
    private static File buildDark(Context ctx, File white) {
        try {
            Bitmap bm = BitmapFactory.decodeFile(white.getPath());
            if (bm == null || bm.getWidth() == 0 || bm.getHeight() == 0) return null;
            int w = bm.getWidth(), h = bm.getHeight();
            int[] px = new int[w * h];
            bm.getPixels(px, 0, w, 0, 0, w, h);

            // 先判定：不透明像素里有多亮算「白像素」
            int solid = 0, bright = 0;
            for (int p : px) {
                int a = (p >>> 24) & 0xFF;
                if (a <= 128) continue;
                solid++;
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                if ((r * 299 + g * 587 + b * 114) / 1000 > 200) bright++;
            }
            if (solid == 0 || bright * 100 / solid <= 55) {
                bm.recycle();
                return null;
            }

            for (int i = 0; i < px.length; i++) {
                int p = px[i];
                int a = (p >>> 24) & 0xFF;
                if (a == 0) continue;
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                // 红色（红 C）不动：整数乘 10 避免浮点
                if (r > 120 && r * 10 > g * 18 && r * 10 > b * 18) continue;
                int lum = (r * 299 + g * 587 + b * 114) / 1000;
                int inv = 255 - lum;
                if (lum > 0) {
                    r = (int) Math.min(255, (long) r * inv / lum);
                    g = (int) Math.min(255, (long) g * inv / lum);
                    b = (int) Math.min(255, (long) b * inv / lum);
                } else {
                    r = g = b = inv;
                }
                px[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }
            Bitmap dark = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
            bm.recycle();

            File out = new File(white.getParentFile(), "dark_" + white.getName());
            File part = new File(out.getPath() + ".part");
            OutputStream os = null;
            try {
                os = new FileOutputStream(part);
                if (!dark.compress(Bitmap.CompressFormat.PNG, 100, os)) {
                    dark.recycle();
                    part.delete();
                    return null;
                }
                if (out.exists()) out.delete();
                if (!part.renameTo(out)) {
                    part.delete();
                    dark.recycle();
                    return null;
                }
                return out;
            } finally {
                dark.recycle();
                if (os != null) try {
                    os.close();
                } catch (IOException ignored) {
                }
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** 已经下好的直接返回，没有就返回 null（不用等网络） */
    public static File cached(Context ctx, String[] urls) {
        for (String url : urls) {
            File f = fileOf(ctx, url);
            if (f.exists() && f.length() > 0) return f;
        }
        return null;
    }

    /** 下载结果回调，一定在主线程；{@code file == null} 表示这几条地址都拿不到 */
    public interface Callback {
        void onResult(File file);
    }

    /** 按候选地址顺序下载，第一个成功的就停 */
    public static void fetch(Context ctx, String[] urls, Callback cb) {
        if (urls == null || urls.length == 0) {
            post(cb, null);
            return;
        }
        final Context app = ctx.getApplicationContext();
        final String lock = keyOf(urls[0]);
        if (!IN_FLIGHT.add(lock)) {
            // 这一份已经在下了：挂到等待队列，下完拿同一份结果回调。
            // 直接回 null 会被上层当成「没这个台标」而永久拉黑
            synchronized (WAITERS) {
                List<Callback> list = WAITERS.get(lock);
                if (list == null) {
                    list = new ArrayList<>();
                    WAITERS.put(lock, list);
                }
                list.add(cb);
            }
            return;
        }
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                File done;
                try {
                    done = fetchSync(app, urls);
                } finally {
                    IN_FLIGHT.remove(lock);
                }
                post(cb, done);
                // 先解开锁再叫醒等待者，否则中途新来的请求会排到已清空的队列里
                flushWaiters(lock, done);
            }
        });
    }

    /** 把挂在这一份台标上的等待者全部叫醒（下载已经结束了，好的坏的都一样回调） */
    private static void flushWaiters(String lock, File done) {
        List<Callback> waiters;
        synchronized (WAITERS) {
            waiters = WAITERS.remove(lock);
        }
        if (waiters == null || waiters.isEmpty()) return;
        for (Callback cb : waiters) post(cb, done);
    }

    /**
     * 把一批台名的台标全部下到本地（阻塞，放后台线程调）。已有的跳过。
     *
     * <p>用途：首屏只会拉看得到的二三十个，剩下的等用户滚到才拉 ——
     * 台标加起来不过几 MB，一次把 246 个台全备好，之后滚动、换主题、断网都是秒开。
     * 台与台之间停 80ms：后台下载不能把前台够图要用的带宽吃干净。
     */
    public static void preload(Context ctx, List<String> channelNames, boolean light) {
        preload(ctx, channelNames, null, light);
    }

    /**
     * 同上，但带上「台名 → 源自带台标」的映射。
     *
     * <p>不带这份映射的预热会去下「按台名猜出来的那张」；前台实际要的是源给的那张
     * （见 {@link ChannelLogo#candidates(Context, String, String)} 的第 1 级），
     * 于是同一个台要下两遍图，还都占缓存 —— 589 个台里能差出两百多份。
     */
    public static void preload(Context ctx, List<String> channelNames,
                               final Map<String, String> sourceLogos, boolean light) {
        if (channelNames == null || channelNames.isEmpty()) return;
        final Context app = ctx.getApplicationContext();
        // 逐张串行 + 80ms 间隔的老做法跑完 246 个台要一分半，用户头一分钟看到的
        // 全是灰占位图，等于「这个 App 没有台标」。改成丢进线程池并发跑：
        // 池子是 4 个线程的 FIFO 队列，先提交的前几十个（首屏看得见的那些）先下完。
        for (final String name : channelNames) {
            if (name == null || name.isEmpty()) continue;
            PRELOAD_POOL.execute(new Runnable() {
                @Override
                public void run() {
                    String srcLogo = sourceLogos == null ? null : sourceLogos.get(name);
                    String[] urls = ChannelLogo.candidates(app, name, srcLogo);
                    if (urls.length == 0) return;
                    File have = cached(app, urls);
                    if (have != null) {
                        if (light) ensureDark(app, have);
                        return;
                    }
                    // 和 fetch() 共用同一把锁：前台若后来挂在 WAITERS 上，
                    // 预热线程完成时必须负责叫醒它，否则卡面会永远停在台名。
                    final String lock = keyOf(urls[0]);
                    if (!IN_FLIGHT.add(lock)) return;
                    File f;
                    try {
                        f = fetchSync(app, urls);
                    } finally {
                        IN_FLIGHT.remove(lock);
                    }
                    flushWaiters(lock, f);
                    if (f != null && light) ensureDark(app, f);
                }
            });
        }
    }

    /** 浅色主题要的黑版：没做过就做一份，做不出来记下来别反复试 */
    private static void ensureDark(Context app, File white) {
        if (white == null) return;
        String path = white.getPath();
        if (DARK_CACHE.get(path) != null || NO_DARK.contains(path)) return;
        // 预热和前台可能同时处理同一文件；与 finishDark() 共用同一把路径锁
        synchronized (path.intern()) {
            if (DARK_CACHE.get(path) != null || NO_DARK.contains(path)) return;
            File d = buildDark(app, white);
            if (d == null) NO_DARK.add(path);
            else DARK_CACHE.put(path, d);
        }
    }

    /** 阻塞版：按候选地址顺序试，返回最终落盘的文件（没有就 null） */
    private static File fetchSync(Context app, String[] urls) {
        for (String url : urls) {
            File target = fileOf(app, url);
            if (target.exists() && target.length() > 0) return target;
            if (download(url, target)) return target;
        }
        return null;
    }

    private static boolean download(String url, File target) {
        Response resp = null;
        try {
            Request req = new Request.Builder()
                    .url(url)
                    // 有些图床对空 UA / 非浏览器 UA 直接 403
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android) PigTV")
                    .build();
            resp = CLIENT.newCall(req).execute();
            if (!resp.isSuccessful() || resp.body() == null) return false;

            File dir = target.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) return false;
            // 先写 .part 再改名：中途失败留下的半张图会被当成「已经下好」，
            // 之后这个台永远显示个破图
            File part = new File(target.getPath() + ".part");
            InputStream in = resp.body().byteStream();
            OutputStream out = new FileOutputStream(part);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
            if (part.length() == 0) {
                part.delete();
                return false;
            }
            // 有些站 404 不返 404，而是 200 加一页 HTML（或被劫持的跳转页）。
            // 这种文件存下来永远解不出来，还会把「这个台没图」变成「这个台有个破图」，
            // 顺手用文件头挡掉：不是 PNG/JPEG/GIF/WEBP/BMP 就当这次没下到。
            if (!looksLikeImage(part)) {
                part.delete();
                return false;
            }
            if (target.exists()) target.delete();
            return part.renameTo(target);
        } catch (Throwable th) {
            // 半截的 .part 留着没意义，还会一直占着目录
            File part = new File(target.getPath() + ".part");
            if (part.exists()) part.delete();
            return false;
        } finally {
            if (resp != null) resp.close();
        }
    }

    /** 一个地址对应哪个本地文件：按 URL 里的文件名存，两个域名共用一份 */
    private static File fileOf(Context ctx, String url) {
        return new File(dir(ctx), keyOf(url) + ".img");
    }

    /**
     * 文件头是不是图片。
     *
     * <p>只认这五种：PNG / JPEG / GIF / WebP / BMP —— 台标库和源给的图床都不会超出这个范围，
     * 而误伤正常台标的代价（这个台永远没图）比漏掉一张冷门格式高得多，所以判据放得很宽。
     */
    private static boolean looksLikeImage(File f) {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            byte[] h = new byte[12];
            int n = 0;
            while (n < h.length) {
                int r = in.read(h, n, h.length - n);
                if (r <= 0) break;
                n += r;
            }
            if (n < 4) return false;
            // PNG: 89 50 4E 47
            if ((h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') return true;
            // JPEG: FF D8 FF
            if ((h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) return true;
            // GIF: "GIF8"
            if (h[0] == 'G' && h[1] == 'I' && h[2] == 'F' && h[3] == '8') return true;
            // BMP: "BM"
            if (h[0] == 'B' && h[1] == 'M') return true;
            // WEBP: "RIFF" .... "WEBP"
            if (n >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                    && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') return true;
            return false;
        } catch (Throwable t) {
            return false;
        } finally {
            if (in != null) try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String keyOf(String url) {
        if (url == null || url.isEmpty()) return "empty";
        String name = url.substring(url.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        // 文件名里可能有空格、括号之类不适合直接当路径的字符，统一换成下划线；
        // 中文留着（ext4 存 UTF-8 没问题），这样在设备上排查缓存时一眼能认出是哪个台
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    private static File dir(Context ctx) {
        // 放 filesDir 而不是 cacheDir：台标是「下过一次就一直用」的资源，
        // 系统清缓存时不该把它清掉（清了下一次冷启动整面墙又是灰的）
        File d = new File(ctx.getFilesDir(), "channel_logo");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static void post(final Callback cb, final File file) {
        if (cb == null) return;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                cb.onResult(file);
            }
        });
    }

    /**
     * 只认 IPv4 的 DNS。
     *
     * <p>台标站点的 AAAA 记录被污染成 Facebook 的地址（{@code 2a03:2880:…:face:b00c}），
     * 一旦走 IPv6 就是 10 秒连接超时。A 记录是好的，所以直接不解析 AAAA。
     * 万一某个域名真的只有 IPv6，退回完整列表，行为跟原来一致。
     */
    private static final class Ipv4Only implements Dns {
        @Override
        public List<InetAddress> lookup(String hostname) throws UnknownHostException {
            List<InetAddress> all = Dns.SYSTEM.lookup(hostname);
            List<InetAddress> v4 = new ArrayList<>(all.size());
            for (InetAddress a : all) {
                if (a instanceof Inet4Address) v4.add(a);
            }
            return v4.isEmpty() ? all : v4;
        }
    }
}
