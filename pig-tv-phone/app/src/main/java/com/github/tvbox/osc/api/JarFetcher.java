package com.github.tvbox.osc.api;

import com.github.catvod.net.SSLCompat;
import com.github.tvbox.osc.util.MD5;

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
 * <h3>为什么不用全局 OkGo</h3>
 * 全局客户端是为小 JSON 请求调的：10 秒读超时、允许 h2、不续传。1MB 二进制用它会
 * 「配置 JSON 拉得下来，jar 永远下不完」。这里单独一个客户端：30 秒读超时、
 * 强制 HTTP/1.1、支持断点续传、识别拦截页。
 *
 * <h3>2026-10-22 第三轮：为什么会「到 400 多 KB 就从头开始」</h3>
 *
 * 真机现象是进度涨到 400 多 KB 后归零重来。用「模拟源站」把手上的逻辑原样跑了一遍，
 * 只有一种服务器行为能产生这个现象：
 *
 * <pre>
 * 服务器【支持】Range：384 → 512 → 640 → 768 → 896 → 1024KB   进度只增不减
 * 服务器【不支持】Range：384 → 384 → 384 → 320 → 384 → …      每轮归零
 * </pre>
 *
 * 原因是老代码在「发了 Range 却收到 200（全量）」时 {@code dropFile(dest)} 再
 * {@code new FileOutputStream(dest, false)}，等于把已经下好的 400KB 扔掉重写。
 * 而不支持 Range 的服务器**永远不会**给 206，所以每轮都归零，直到把重试次数耗光。
 *
 * <h3>现在的策略</h3>
 * <ol>
 *   <li><b>先探测</b>（1 字节的 {@code Range: bytes=0-0}）：206 说明认 Range，走分段续传；
 *       200 说明不认，直接进入整包模式。不再靠「撞了才知道」。</li>
 *   <li><b>不认 Range 时先试 https</b>：明文 http 被中间层（运营商代理 / VPN）改写掉
 *       Range 头是最常见的原因，TLS 里它改不了。这一步经常能直接救回来。</li>
 *   <li><b>真不认 Range 就整包下</b>：写进独立的 {@code .full} 临时文件，
 *       <b>绝不碰已有的残包</b>，进度按「历史最大」上报 —— 界面上的数字只会涨不会归零。</li>
 *   <li><b>彻底下不动时说人话</b>：「服务器不支持断点续传，每次只给 xxxKB」，
 *       而不是让人对着一个反复归零的进度条发呆。</li>
 * </ol>
 */
public final class JarFetcher {

    /** 单次连接的最长静默时间。慢站一个包隔十几秒很常见，给足。 */
    private static final int READ_TIMEOUT_S = 30;
    private static final int CONNECT_TIMEOUT_S = 20;
    /** 硬上限（正常情况下靠「连续无进展」和「总时长」收尾，走不到这里） */
    private static final int MAX_ATTEMPT = 240;
    /**
     * 连续这么多次一个字节都没拿到才算真的下不动。
     * 不能给小：这类站常见「传一大段 → 触发限速 → 之后连不上」。
     */
    private static final int MAX_DRY = 10;
    /** 整包模式下，连续这么多次都没超过历史最好成绩就认输（每次都是从头下，超过才有意义） */
    private static final int MAX_FULL_RUNS = 3;
    /** 一轮下载的总时长预算 */
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
        /**
         * @param noResume 服务器不支持断点续传（每次只能从 0 重下），界面要换文案
         */
        void onProgress(long got, long total, boolean noResume);
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
        /** 服务器不认 Range（不能断点续传） */
        public boolean noResume;
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
                    // 只走 HTTP/1.1：部分蜘蛛站的 h2 在传大文件时会 RST_STREAM
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
        boolean canHttps = target.startsWith("http://");

        // ---------------------------------------------------------------
        // 1. 先问一句：你认不认 Range？（1 个字节的代价）
        // ---------------------------------------------------------------
        int rangeState = probeRange(target, diag);
        if (rangeState == 0 && canHttps) {
            // http 上不认 Range 时，很可能是中间层改写了请求头 —— TLS 里它改不了
            String secure = "https://" + target.substring(7);
            int s2 = probeRange(secure, diag);
            diag.append(s2 == 1 ? "https 认 Range → 改用 https; " : "https 也不认 Range; ");
            if (s2 == 1) {
                target = secure;
                rangeState = 1;
            } else if (s2 == 0) {
                rangeState = 0;
            }
        }
        r.noResume = rangeState == 0;
        if (rangeState == 0) {
            diag.append("服务器不认断点续传（Range 请求回了 200）; ");
            fetchWhole(target, dest, md5, r, diag);
        } else {
            fetchRanged(target, dest, md5, r, diag);
        }
        r.diag = diag.toString();
        if (dest.exists() && dest.length() > 0) r.file = dest;
        if (!r.ok) r.err = friendly(r);
        return r;
    }

    /**
     * 探测服务器认不认 Range。
     *
     * @return 1 = 认（206），0 = 不认（200），-1 = 探不出来（网络问题，按乐观处理）
     */
    private static int probeRange(String target, StringBuilder diag) {
        Response resp = null;
        try {
            Request req = new Request.Builder()
                    .url(target)
                    .header("User-Agent", UA)
                    .header("Accept", ACCEPT)
                    .header("Accept-Encoding", "identity")
                    .header("Connection", "close")
                    .header("Range", "bytes=0-0")
                    .build();
            resp = client().newCall(req).execute();
            int code = resp.code();
            if (code == 206) return 1;
            if (code == 200) return 0;
            if (code == 416) {
                // 空文件或不支持这个区间，按「认」处理，交给后面的逻辑
                return 1;
            }
            diag.append("探测 Range 得到 HTTP ").append(code).append("; ");
            return -1;
        } catch (Throwable th) {
            diag.append("探测 Range 失败: ").append(th.getClass().getSimpleName()).append("; ");
            return -1;
        } finally {
            if (resp != null) {
                try {
                    resp.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ==================================================================
    //  模式 A：服务器认 Range —— 分段续传
    // ==================================================================

    private static void fetchRanged(String target, File dest, String md5,
                                    Result r, StringBuilder diag) {
        long chunk = CHUNK_START;
        int dry = 0;
        long best = dest.exists() ? dest.length() : 0;
        long deadline = System.currentTimeMillis() + TOTAL_BUDGET_MS;

        for (int attempt = 1; attempt <= MAX_ATTEMPT; attempt++) {
            r.attempts = attempt;
            long have = dest.exists() ? dest.length() : 0;
            if (r.expected > 0 && have >= r.expected) break;
            if (System.currentTimeMillis() > deadline) {
                diag.append("总时长超过").append(TOTAL_BUDGET_MS / 1000).append("秒; ");
                break;
            }

            long begin = have;
            Request.Builder rb = new Request.Builder()
                    .url(target)
                    .header("User-Agent", UA)
                    .header("Accept", ACCEPT)
                    // 和 Range 一起用必须关掉压缩，否则字节数和长度对不上
                    .header("Accept-Encoding", "identity")
                    // 对端会 RST，别让下一次请求落到池里的死连接上
                    .header("Connection", "close");
            if (have > 0) {
                long end = have + chunk - 1;
                if (r.expected > 0 && end > r.expected - 1) end = r.expected - 1;
                if (end >= have) rb.header("Range", "bytes=" + have + "-" + end);
            }

            Response resp = null;
            long got = 0;
            try {
                resp = client().newCall(rb.build()).execute();
                int code = resp.code();

                if (code == 416) {
                    // 本地残包比服务器的文件还长（跨版本残留），只能从头来
                    dropFile(dest);
                    r.expected = -1;
                    diag.append("第").append(attempt).append("次 HTTP 416（残包与服务器对不上，从头下）; ");
                    sleep(300L);
                    continue;
                }

                if (code != 200 && code != 206) {
                    diag.append("第").append(attempt).append("次 HTTP ").append(code);
                    String head = peek(resp, 512);
                    if (looksLikeHtml(head)) {
                        r.looksHtml = true;
                        r.htmlHint = textHint(head);
                        diag.append("（返回网页）");
                    }
                    diag.append("; ");
                    sleep(backoff(attempt));
                    continue;
                }

                if (code == 200 && have > 0) {
                    // 探测说是 206，这里却给 200：不删残包，直接转整包模式（宁可慢，不许归零）
                    diag.append("第").append(attempt).append("次 又回了 200; ");
                    r.noResume = true;
                    fetchWhole(target, dest, md5, r, diag);
                    return;
                }
                // 注意 have == 0 时的 200 是正常的：这一轮本来就没发 Range

                boolean append = code == 206 && have > 0;
                long total = -1;
                if (code == 206) {
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
                        long now = System.currentTimeMillis();
                        if (got - lastPushGot >= PROGRESS_BYTES || now - lastPushAt >= PROGRESS_MS) {
                            lastPushAt = now;
                            lastPushGot = got;
                            notifyProgress(have + got, r.expected, r.noResume);
                        }
                    }
                    fos.flush();
                } finally {
                    closeQuietly(fos);
                    closeQuietly(in);
                }

                r.bytes += got;
                r.file = dest;
                long size = dest.length();
                notifyProgress(size, r.expected, r.noResume);

                if (r.expected > 0 && size < r.expected) {
                    diag.append("第").append(attempt).append("次 中断 ").append(size / 1024)
                            .append("KB/").append(r.expected / 1024).append("KB; ");
                    // 学到「这次断在哪」，下次只请求这么长，保证每轮必有净进度
                    long step = size - begin;
                    if (step > 0 && step < chunk) chunk = Math.max(CHUNK_MIN, step);
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
                    return;
                }
                diag.append("第").append(attempt).append("次 校验不符 ").append(size / 1024)
                        .append("KB(md5 ").append(shortMd5(gotMd5)).append("); ");
                // 长度够了但 md5 不对：交给上层 load 一次定生死，别在这儿死磕
                break;
            } catch (Throwable ex) {
                long size = dest.exists() ? dest.length() : 0;
                diag.append("第").append(attempt).append("次 ")
                        .append(ex.getClass().getSimpleName()).append(": ")
                        .append(ex.getMessage()).append("; ");
                if (size > begin) {
                    long step = size - begin;
                    if (step < chunk) chunk = Math.max(CHUNK_MIN, step);
                }
                if (size > best) {
                    best = size;
                    dry = 0;
                } else if (++dry >= MAX_DRY) {
                    diag.append("连续").append(MAX_DRY).append("次没有新数据; ");
                    break;
                }
                notifyProgress(size, r.expected, r.noResume);
            } finally {
                if (resp != null) {
                    try {
                        resp.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
            sleep(backoff(dry));
        }
    }

    // ==================================================================
    //  模式 B：服务器不认 Range —— 整包下载，但绝不归零
    // ==================================================================

    /**
     * 每次尝试都从 0 拉全量，但写进独立的 {@code .full} 文件：
     * <ul>
     *   <li>已经下好的 {@code dest} 一个字节都不动，所以进度条不会倒退；</li>
     *   <li>{@code .full} 下完整了才顶替 {@code dest}，半截的不会污染残包。</li>
     * </ul>
     */
    private static void fetchWhole(String target, File dest, String md5,
                                   Result r, StringBuilder diag) {
        File full = new File(dest.getAbsolutePath() + ".full");
        long best = dest.exists() ? dest.length() : 0;
        int runs = 0;
        long deadline = System.currentTimeMillis() + TOTAL_BUDGET_MS;

        for (int attempt = 1; attempt <= MAX_ATTEMPT; attempt++) {
            r.attempts = attempt;
            if (System.currentTimeMillis() > deadline) {
                diag.append("总时长超过").append(TOTAL_BUDGET_MS / 1000).append("秒; ");
                break;
            }
            dropFile(full);
            long got = 0;
            long expected = -1;
            boolean complete = false;
            Response resp = null;
            try {
                Request req = new Request.Builder()
                        .url(target)
                        .header("User-Agent", UA)
                        .header("Accept", ACCEPT)
                        .header("Accept-Encoding", "identity")
                        .header("Connection", "close")
                        .build();
                resp = client().newCall(req).execute();
                int code = resp.code();
                if (code != 200) {
                    diag.append("第").append(attempt).append("次 HTTP ").append(code);
                    String head = peek(resp, 512);
                    if (looksLikeHtml(head)) {
                        r.looksHtml = true;
                        r.htmlHint = textHint(head);
                        diag.append("（返回网页）");
                    }
                    diag.append("; ");
                    sleep(backoff(attempt));
                    continue;
                }
                expected = resp.body() != null ? resp.body().contentLength() : -1;
                if (expected > 0) r.expected = expected;

                FileOutputStream fos = new FileOutputStream(full, false);
                InputStream in = resp.body() != null ? resp.body().byteStream() : null;
                long lastPushAt = System.currentTimeMillis(), lastPushGot = 0;
                try {
                    if (in == null) throw new IOException("响应体为空");
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        fos.write(buf, 0, n);
                        got += n;
                        long now = System.currentTimeMillis();
                        if (got - lastPushGot >= PROGRESS_BYTES || now - lastPushAt >= PROGRESS_MS) {
                            lastPushAt = now;
                            lastPushGot = got;
                            // 报「历史最大」，绝不因为这次从头下就把数字压回去
                            notifyProgress(Math.max(best, got), r.expected, r.noResume);
                        }
                    }
                    fos.flush();
                    complete = expected <= 0 || got >= expected;
                } finally {
                    closeQuietly(fos);
                    closeQuietly(in);
                }
                r.bytes = Math.max(r.bytes, got);
            } catch (Throwable ex) {
                diag.append("第").append(attempt).append("次 ")
                        .append(ex.getClass().getSimpleName()).append(": ")
                        .append(ex.getMessage()).append("; ");
            } finally {
                if (resp != null) {
                    try {
                        resp.close();
                    } catch (Throwable ignored) {
                    }
                }
            }

            long size = full.exists() ? full.length() : 0;
            if (complete && size > 0) {
                String gotMd5 = MD5.getFileMd5(full);
                boolean md5Ok = md5 == null || md5.isEmpty() || md5.equalsIgnoreCase(gotMd5);
                if (md5Ok) {
                    // 下完整了才顶替残包
                    dropFile(dest);
                    if (!full.renameTo(dest)) {
                        if (!copy(full, dest)) {
                            diag.append("落盘失败; ");
                            break;
                        }
                        dropFile(full);
                    }
                    r.ok = true;
                    diag.append("第").append(attempt).append("次 整包完成 ").append(size / 1024).append("KB; ");
                    notifyProgress(size, r.expected, r.noResume);
                    return;
                }
                diag.append("第").append(attempt).append("次 整包校验不符 ")
                        .append(size / 1024).append("KB(md5 ").append(shortMd5(gotMd5)).append("); ");
                dropFile(full);
                sleep(backoff(attempt));
                continue;
            }

            diag.append("第").append(attempt).append("次 整包中断 ").append(size / 1024).append("KB");
            if (r.expected > 0) diag.append("/").append(r.expected / 1024).append("KB");
            diag.append("; ");
            notifyProgress(Math.max(best, size), r.expected, r.noResume);

            // 每次都是从头下：只有超过历史最好成绩才算有进展
            if (size > best) {
                best = size;
                runs = 0;
            } else if (++runs >= MAX_FULL_RUNS) {
                diag.append("整包连续").append(MAX_FULL_RUNS).append("次都没超过")
                        .append(best / 1024).append("KB; ");
                break;
            }
            sleep(backoff(runs + 1));
        }
        dropFile(full);
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

    private static void notifyProgress(long got, long total, boolean noResume) {
        Progress p = sink;
        if (p == null) return;
        try {
            p.onProgress(got, total, noResume);
        } catch (Throwable ignored) {
        }
    }

    /** 断了不是立刻重连：给对端和网关一点时间。没进展的时候退得更狠 */
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
        long got = r.file == null ? 0 : r.file.length();
        if (r.noResume && got > 0 && r.expected > 0 && got < r.expected) {
            return "这个源站不支持断点续传，每次只肯给 " + got / 1024 + "KB（共 "
                    + r.expected / 1024 + "KB）就被掐断。换成 Wi-Fi、关掉手机上的代理/VPN 再试一次";
        }
        if (r.expected > 0 && got > 0 && got < r.expected)
            return "组件只下到 " + got / 1024 + "/" + r.expected / 1024
                    + "KB，网络一直断（已续传 " + r.attempts + " 次），再点一次会接着下";
        if (r.diag.contains("SocketTimeoutException"))
            return "组件下载超时，这台网络连该服务器太慢";
        if (r.diag.contains("UnknownHostException"))
            return "组件地址解析不了（域名失效或被拦截）";
        if (r.diag.contains("SSL") || r.diag.contains("Cert"))
            return "组件地址证书有问题，连不上";
        if (got > 0)
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

    private static void closeQuietly(java.io.Closeable c) {
        try {
            if (c != null) c.close();
        } catch (Throwable ignored) {
        }
    }

    private static boolean copy(File src, File dst) {
        java.io.FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new java.io.FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.flush();
            return true;
        } catch (Throwable th) {
            return false;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
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
