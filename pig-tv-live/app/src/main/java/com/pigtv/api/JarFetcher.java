package com.pigtv.api;

import com.github.catvod.net.SSLCompat;
import com.pigtv.util.MD5;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

/**
 * spider 组件（jar）专用下载器。
 *
 * <p>为什么不用全局的 OkGo：全局客户端是为「小 JSON 请求」调的，参数对 jar 这种
 * 1MB 级二进制下载并不友好，实测有三处会直接把下载掐死：
 *
 * <ol>
 *   <li><b>readTimeout = 10s</b>。蜘蛛站常年是慢速海外/小机房线路，10 秒读不到数据就抛
 *       SocketTimeout，「下到一半没了」的第一嫌疑。</li>
 *   <li><b>允许 h2</b>。部分蜘蛛站的 HTTP/2 在传大文件时会 RST_STREAM ——
 *       所以会出现「配置 JSON 拉得下来，jar 永远下不完」这种诡异现象。这里强制 HTTP/1.1。</li>
 *   <li><b>不续传</b>。连接一断就从 0 重来，慢网下永远追不上。这里带 Range 续传。</li>
 * </ol>
 *
 * <h3>2026-10-22 第二轮：为什么要「分段」续传</h3>
 *
 * 真机现象是「组件没下完（256/1024KB），网络反复中断」，详情里 4 次全是
 * {@code SocketException: Connection reset}。也就是说：<b>每次连接都能正常拿到
 * 六十几 KB，然后被对端（或中间的网关）RST 掉，但每次都有净进度</b>，
 * 只是老代码写了 {@code MAX_ATTEMPT = 4}，4×64KB=256KB 就判定失败收工，
 * 而文件本身已经是可续传的半成品。
 *
 * <p>所以这里改成：
 * <ul>
 *   <li><b>学习断点位置</b>：某次只拿到 N 字节就断，下一轮就只请求 N 字节那么长的一段
 *       （{@code Range: bytes=a-b}）。请求段一短，连接在被打断之前就能正常结束，
 *       于是每一轮都必然有净进度 —— 这是「必能下完」的关键，而不是靠运气重试。</li>
 *   <li><b>重试次数按「有没有进展」算</b>，不再是一个固定的小数字：连续
 *       {@link #MAX_DRY} 次一个字节都拿不到才放弃，另有总时长兜底。</li>
 *   <li><b>进度实时推给 UI</b>（{@link Progress}），页面显示
 *       「正在下载播放组件 312/1024KB」，用户能看见它在动，而不是白等。</li>
 * </ul>
 *
 * <p>另外把「拿到的是网页」单独识别出来：接口被运营商/网关拦截时，返回的是一个
 * 十几 KB 的 HTML 提示页，长度对不上 md5，但以前只会报「下载失败」，看不出是拦截。
 */
public final class JarFetcher {

    /** 单次连接的最长静默时间。慢站一个包隔十几秒很常见，给足。 */
    private static final int READ_TIMEOUT_S = 30;
    private static final int CONNECT_TIMEOUT_S = 20;
    /** 硬上限（正常情况下靠「连续无进展」和「总时长」收尾，走不到这里） */
    private static final int MAX_ATTEMPT = 240;
    /**
     * 连续这么多次一个字节都没拿到才算真的下不动。
     * 不能给小：这类站常见「传一大段 → 触发限速 → 之后几分钟连不上」，
     * 前几次断线是正常的，第 3、4 次没进度不代表站点挂了。
     */
    private static final int MAX_DRY = 10;
    /** 一轮下载的总时长预算。超过就先收工，让页面把已有进度显示出来 */
    private static final long TOTAL_BUDGET_MS = 210_000L;
    /** 分段续传的起始粒度，以及能缩到的最小粒度 */
    private static final long CHUNK_START = 128 * 1024L;
    private static final long CHUNK_MIN = 16 * 1024L;
    /** 进度推送节流：至少隔这么久、或者涨了这么多字节才推一次 */
    private static final long PROGRESS_MS = 400L;
    private static final long PROGRESS_BYTES = 48 * 1024L;

    private static final String UA = "okhttp/3.15";
    private static final String ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8";

    /** 下载进度回调（在下载线程上调用，别在里面碰 UI） */
    public interface Progress {
        void onProgress(long got, long total);
    }

    private static volatile Progress sink;

    /** 注册进度监听；传 null 注销 */
    public static void setProgressSink(Progress p) {
        sink = p;
    }

    public static final class Result {
        /** md5 校验通过（或接口没给 md5） */
        public boolean ok;
        /** 拿到的文件（可能不完整，调用方可以再拿去试着 load 一次） */
        public File file;
        /** 给用户看的一句话 */
        public String err = "";
        /** 给排查用的细节：每次尝试的状态码 / 收到的字节 / 异常 */
        public String diag = "";
        /** 累计收到多少字节 */
        public long bytes;
        /** 服务器声明的总长度（拿不到就是 -1） */
        public long expected = -1;
        /** 实际用了几次连接 */
        public int attempts;
        /** 拿回来的是网页而不是 jar */
        public boolean looksHtml;
        /** 网页内容摘录（拦截页通常有「拦截/警告/诈骗」等字样） */
        public String htmlHint = "";
    }

    private static OkHttpClient client;

    private static synchronized OkHttpClient client() {
        if (client == null) {
            OkHttpClient.Builder b = new OkHttpClient.Builder()
                    .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
                    .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
                    .writeTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    // 只走 HTTP/1.1，见类注释第 2 条
                    .protocols(Collections.singletonList(Protocol.HTTP_1_1));
            try {
                b.sslSocketFactory(new SSLCompat(), SSLCompat.TM);
                b.hostnameVerifier((hostname, session) -> true);
            } catch (Throwable ignored) {
                // 用默认 TLS 也能跑，只是部分自签证书站会失败
            }
            client = b.build();
        }
        return client;
    }

    private JarFetcher() {
    }

    /**
     * 下载到 {@code dest}（存在则尝试续传）。
     *
     * @param md5 接口给的 md5，空串表示不校验
     */
    public static Result fetch(String url, File dest, String md5) {
        Result r = new Result();
        StringBuilder diag = new StringBuilder();
        String target = ApiConfig.toAsciiUrl(url);
        boolean canUpgradeHttps = target.startsWith("http://");

        long chunk = CHUNK_START;    // 分段粒度（会按实测断点缩小）
        boolean chunked = false;     // 是否已经切到分段模式
        boolean rangeOk = true;      // 服务器认不认 Range
        int dry = 0;                 // 连续无进展次数
        long best = dest.exists() ? dest.length() : 0;   // 历史最好成绩，用来判断"有没有进展"
        long deadline = System.currentTimeMillis() + TOTAL_BUDGET_MS;

        for (int attempt = 1; attempt <= MAX_ATTEMPT; attempt++) {
            r.attempts = attempt;
            long have = dest.exists() ? dest.length() : 0;
            if (r.expected > 0 && have >= r.expected) break;      // 长度够了，去校验
            if (System.currentTimeMillis() > deadline) {
                diag.append("总时长超过").append(TOTAL_BUDGET_MS / 1000).append("秒; ");
                break;
            }

            long begin = have;
            Request.Builder rb = new Request.Builder()
                    .url(target)
                    .header("User-Agent", UA)
                    .header("Accept", ACCEPT)
                    // 和 Range 一起用时必须显式关掉压缩，否则拿到的字节数和长度都对不上
                    .header("Accept-Encoding", "identity")
                    // 对端会把连接 RST 掉，别让下一次请求又落到池里的死连接上
                    .header("Connection", "close");
            if (have > 0 && rangeOk) {
                if (chunked) {
                    long end = have + chunk - 1;
                    if (r.expected > 0 && end > r.expected - 1) end = r.expected - 1;
                    if (end >= have) rb.header("Range", "bytes=" + have + "-" + end);
                } else {
                    rb.header("Range", "bytes=" + have + "-");
                }
            }

            Response resp = null;
            long got = 0;
            try {
                resp = client().newCall(rb.build()).execute();
                int code = resp.code();

                if (code == 416) {
                    // Range 越界：本地残包和服务器的文件对不上，只能从头来
                    dropFile(dest);
                    r.expected = -1;
                    diag.append("第").append(attempt).append("次 HTTP 416（本地残包不可用，从头下）; ");
                    sleep(300L);
                    continue;
                }

                if (code != 200 && code != 206) {
                    String head = peek(resp, 512);
                    diag.append("第").append(attempt).append("次 HTTP ").append(code);
                    if (looksLikeHtml(head)) {
                        r.looksHtml = true;
                        r.htmlHint = textHint(head);
                        diag.append("（返回网页）");
                        // 明文 http 被插了一个提示页 → 试一次 https，很多时候能绕开
                        if (canUpgradeHttps) {
                            target = "https://" + target.substring(7);
                            canUpgradeHttps = false;
                            diag.append(" → 改用 https 重试");
                        }
                    }
                    diag.append("; ");
                    sleep(600L * Math.min(attempt, 4));
                    continue;
                }

                boolean append = code == 206 && have > 0;
                long total = -1;
                if (code == 206) {
                    // Content-Range: bytes 100-999/1000
                    String cr = resp.header("Content-Range");
                    if (cr != null) {
                        int slash = cr.lastIndexOf('/');
                        if (slash > 0) {
                            try {
                                total = Long.parseLong(cr.substring(slash + 1).trim());
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                } else {
                    // 200：服务器不认 Range（或者我们本来就没发），只能从 0 重写
                    if (have > 0) {
                        if (rangeOk && chunked) {
                            // 明明发了分段 Range 却回全量 —— 这站不支持断点，别再来第二轮
                            rangeOk = false;
                            chunked = false;
                            diag.append("第").append(attempt).append("次 服务器不支持分段; ");
                        }
                        dropFile(dest);
                        have = 0;
                        begin = 0;
                    }
                    total = resp.body() != null ? resp.body().contentLength() : -1;
                }
                if (total > 0) r.expected = total;

                FileOutputStream fos = new FileOutputStream(dest, append);
                InputStream in = resp.body() != null ? resp.body().byteStream() : null;
                long lastPushAt = System.currentTimeMillis(), lastPushGot = 0;
                try {
                    if (in == null) throw new IOException("响应体为空");
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        fos.write(buf, 0, n);
                        got += n;
                        // 中途也推一次，别等整段下完才动一下
                        long now = System.currentTimeMillis();
                        if (got - lastPushGot >= PROGRESS_BYTES || now - lastPushAt >= PROGRESS_MS) {
                            lastPushAt = now;
                            lastPushGot = got;
                            notifyProgress(have + got, r.expected);
                        }
                    }
                    fos.flush();
                } finally {
                    try {
                        fos.close();
                    } catch (Throwable ignored) {
                    }
                    try {
                        if (in != null) in.close();
                    } catch (Throwable ignored) {
                    }
                }

                r.bytes += got;
                r.file = dest;
                long size = dest.length();
                notifyProgress(size, r.expected);

                // 长度已经对得上就先按长度判；拿不到长度才只能算 md5
                if (r.expected > 0 && size < r.expected) {
                    diag.append("第").append(attempt).append("次 中断 ").append(size / 1024)
                            .append("KB/").append(r.expected / 1024).append("KB; ");
                    // 学到「这次断在哪」：下次只要这么长，连接就能在被打断前正常结束
                    long step = size - begin;
                    if (step > 0) {
                        if (!chunked) chunked = true;
                        if (step < chunk) chunk = Math.max(CHUNK_MIN, step);
                    }
                    if (size > best) {
                        best = size;
                        dry = 0;
                    } else if (++dry >= MAX_DRY) {
                        diag.append("连续").append(MAX_DRY).append("次没有新数据; ");
                        break;
                    }
                    sleep(backoff(dry));
                    continue;
                }

                String gotMd5 = MD5.getFileMd5(dest);
                if (md5 == null || md5.isEmpty() || md5.equalsIgnoreCase(gotMd5)) {
                    r.ok = true;
                    diag.append("第").append(attempt).append("次 完成 ").append(size / 1024).append("KB; ");
                    r.diag = diag.toString();
                    return r;
                }

                diag.append("第").append(attempt).append("次 校验不符 ").append(size / 1024)
                        .append("KB(md5 ").append(shortMd5(gotMd5)).append("); ");
                // md5 不符且长度够了 —— 再下也是同样结果，除非是缓存脏了
                if (r.expected > 0 && size >= r.expected) {
                    // 服务器给的 md5 可能写错；文件完整就交出去，让上层 load 一次定生死
                    r.ok = false;
                    break;
                }
                sleep(backoff(attempt));
            } catch (Throwable ex) {
                long size = dest.exists() ? dest.length() : 0;
                diag.append("第").append(attempt).append("次 ")
                        .append(ex.getClass().getSimpleName()).append(": ")
                        .append(ex.getMessage()).append("; ");
                // 断了：这一轮拿到多少算多少，下次从这里接着来
                if (size > begin) {
                    long step = size - begin;
                    if (!chunked) chunked = true;
                    if (step < chunk) chunk = Math.max(CHUNK_MIN, step);
                }
                if (size > best) {
                    best = size;
                    dry = 0;
                } else if (++dry >= MAX_DRY) {
                    diag.append("连续").append(MAX_DRY).append("次没有新数据; ");
                    break;
                }
                notifyProgress(size, r.expected);
                // 明文 http 连不上（域名被劫持 / 端口被拦）时换 https 再试一次：
                // 很多蜘蛛站两种协议都开着，https 反而能过。
                if (canUpgradeHttps && size == 0) {
                    target = "https://" + target.substring(7);
                    canUpgradeHttps = false;
                    diag.append("→ 改用 https 重试 ");
                }
            } finally {
                if (resp != null) {
                    try {
                        resp.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
            sleep(backoff(attempt));
        }

        r.diag = diag.toString();
        if (dest.exists() && dest.length() > 0) r.file = dest;
        if (!r.ok) r.err = friendly(r);
        return r;
    }

    /** 纯文本下载（给 img+ 那种「jar 藏在图片地址里」的接口用） */
    public static String fetchText(String url) {
        Response resp = null;
        try {
            Request req = new Request.Builder()
                    .url(ApiConfig.toAsciiUrl(url))
                    .header("User-Agent", UA)
                    .header("Accept", ACCEPT)
                    .build();
            resp = client().newCall(req).execute();
            if (resp.body() == null) return "";
            return resp.body().string();
        } catch (Throwable th) {
            return "";
        } finally {
            if (resp != null) {
                try {
                    resp.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void notifyProgress(long got, long total) {
        Progress p = sink;
        if (p == null) return;
        try {
            p.onProgress(got, total);
        } catch (Throwable ignored) {
        }
    }

    /** 断了不是立刻重连：给对端和网关一点时间。
     *  没进展的时候退得更狠 —— 被限速的站，急着重连只会一直撞墙。 */
    private static long backoff(int n) {
        if (n <= 0) return 250L;
        long ms = 250L * Math.max(1, Math.min(n, 8));
        return Math.min(ms, 3000L);
    }

    private static String friendly(Result r) {
        if (r.looksHtml) {
            if (r.htmlHint != null && !r.htmlHint.isEmpty())
                return "组件地址返回的是网页（" + r.htmlHint + "），可能被网络拦截了";
            return "组件地址返回的是网页而不是文件，可能被网络拦截了";
        }
        if (r.expected > 0 && r.file != null && r.file.length() > 0 && r.file.length() < r.expected)
            return "组件只下到 " + r.file.length() / 1024 + "/" + r.expected / 1024
                    + "KB，网络一直断（已自动续传 " + r.attempts + " 次），再点一次会接着下";
        if (r.diag.contains("SocketTimeoutException"))
            return "组件下载超时，这台网络连该服务器太慢";
        if (r.diag.contains("UnknownHostException"))
            return "组件地址解析不了（域名失效或被拦截）";
        if (r.diag.contains("SSL") || r.diag.contains("Cert"))
            return "组件地址证书有问题，连不上";
        if (r.file != null && r.file.length() > 0)
            return "组件下载完整但校验值不对（站点可能刚更新，重试一次通常就好）";
        return "组件下载失败，服务器没有返回数据";
    }

    private static boolean looksLikeHtml(String head) {
        if (head == null) return false;
        String s = head.trim().toLowerCase();
        return s.startsWith("<!doctype") || s.startsWith("<html") || s.contains("<head>")
                || (s.startsWith("<") && s.contains("</"));
    }

    /** 从拦截页里抠出能认出来的一句话，给用户看 */
    private static String textHint(String html) {
        if (html == null) return "";
        try {
            String s = html.replaceAll("(?is)<script.*?</script>", " ")
                    .replaceAll("(?is)<style.*?</style>", " ")
                    .replaceAll("<[^>]+>", " ")
                    .replaceAll("\\s+", " ").trim();
            if (s.length() > 40) s = s.substring(0, 40);
            return s;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String shortMd5(String md5) {
        if (md5 == null) return "";
        return md5.length() > 8 ? md5.substring(0, 8) : md5;
    }

    private static String peek(Response resp, int max) {
        try {
            if (resp.body() == null) return "";
            InputStream in = resp.body().byteStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            while (bos.size() < max) {
                int n = in.read(buf, 0, Math.min(buf.length, max - bos.size()));
                if (n == -1) break;
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void dropFile(File f) {
        try {
            if (f.exists()) f.delete();
        } catch (Throwable ignored) {
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
