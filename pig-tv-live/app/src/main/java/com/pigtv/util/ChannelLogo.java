package com.pigtv.util;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 频道台标（logo）地址解析。
 *
 * <p>三级来源，逐级降级：
 * <ol>
 *   <li>本地表：{@code assets/epg_data.json} 里 615 条「台名 → 台标地址」的映射，
 *       覆盖央视、卫视和各省台，离线可用、命中即准；</li>
 *   <li>按命名规则猜：台标库 {@code live.fanmingming.com/tv/} 的文件名基本等于台名
 *       （CCTV5.png / 湖南卫视.png），把台名里的画质标记、空格、连接符去掉就能猜中一大半；</li>
 *   <li>对索引：{@code assets/logo_index.txt} 是那个台标库的**全部 930 个文件名**，
 *       猜不中时先按归一化名字精确对一遍，再按前缀做一次模糊匹配
 *       （「浙江教科」→「浙江教科影视」、「TVBS-Asia」→「TVBS」）。</li>
 * </ol>
 *
     * <p>拿不到时由卡面显示完整台名，不猜缩写，也不继续盲发一串必然 404 的请求。
 *
 * <p>同一次会话内按台名缓存结果；整条链路都 404 过的台名记到 {@link #markMissing} 里，
 * 滚动回去不重新发请求 —— 电视上一个屏能放 30 多格，逐个重试会白烧流量。
 */
public final class ChannelLogo {

    /**
     * 首选镜像：jsDelivr 的 gcore 节点。
     *
     * <p>实测（国内家宽）：主站 live.fanmingming.com 的 A 记录会在 Cloudflare 的
     * 104.31.x.x 和 202.53.x.x 之间变，前者连不上；AAAA 更是被污染成 Facebook 的地址。
     * //cdn.jsdelivr.net 和 fastly.jsdelivr.net 也不通，只有 gcore 节点稳定 1 秒内返回。
     *
     * <p>备用站（源站自己）已下线不用：它在这个网络上连不通，排在镜像后面时
     * 每个台标要白白多等 6 秒连接超时，而它几乎从不成功——纯亏损。
     */
    private static final String MIRROR = "https://gcore.jsdelivr.net/gh/fanmingming/live@main/tv/";

    /** 台名 → 候选地址（空数组表示已经确认拿不到，不要再试） */
    private static final Map<String, String[]> CACHE =
            java.util.Collections.synchronizedMap(new HashMap<String, String[]>());
    /**
     * 每个台最多试四个地址。
     *
     * <p>以前是八个（四个文件名 × 两个域名），而 jsDelivr 上 404 要 1.5~3.7 秒才返回：
     * 一个库里根本没有的台，光试错就是一十多秒，表现出来就是「一半格子永远在转圈」。
     * 现在文件名先过索引（见 {@link #build}），加上只用一个域名，
     * 没台标的台是 0 个候选、立刻显示台名。
     */
    private static final int MAX_CANDIDATES = 4;

    private static final Pattern TAG = Pattern.compile("\\[(?:\\d{3,4}[pP]|[248][kK])\\]");
    /** 结尾的画质词，以及 [Not 24/7] 这种噪声标记 */
    private static final Pattern TAIL = Pattern.compile("(?:高清|超清|蓝光|标清)$");
    private static final Pattern NOISE_TAG = Pattern.compile("\\[[^\\]]{1,12}\\]");
    /** 台名里对台标文件名没贡献的字符 */
    private static final Pattern STRIP = Pattern.compile("[\\s\\-_·|（）()]+");
    /** 猜不到就去掉的画质后缀：东方卫视4K → 东方卫视 */
    private static final Pattern UHD = Pattern.compile("(?:4[kK]|8[kK]|[uU][hH][dD]|[hH][dD])$");

    /** 台标库文件名索引：归一化名 → 原始文件名（由 {@link #ensureIndex} 一次性读满） */
    private static volatile Map<String, String> index;
    private static final Object INDEX_LOCK = new Object();
    /** 索引读不到时的替身：让调用方能安全地当「空表」用，不用到处判 null */
    private static final Map<String, String> EMPTY_INDEX = new HashMap<>(0);

    private ChannelLogo() {
    }

    /** 这个台名可以试哪几个台标地址，可能为空 */
    public static String[] candidates(Context ctx, String channelName) {
        if (channelName == null || channelName.trim().isEmpty()) return new String[0];
        String[] hit = CACHE.get(channelName);
        if (hit != null) return hit;
        String[] built = build(ctx, channelName);
        CACHE.put(channelName, built);
        return built;
    }

    /** 候选地址全部加载失败：记下来，这个台名以后直接走占位图 */
    public static void markMissing(String channelName) {
        if (channelName != null) CACHE.put(channelName, new String[0]);
    }

    private static String[] build(Context ctx, String channelName) {
        // 先把「可能是哪几个文件名」凑出来。
        //
        // 以前这里把「猜出来的名字」不加验证就当成候选：台标库里根本没有这个文件，
        // 也只能发出去等 404（jsDelivr 返回 404 要 1.5~3.7 秒），一个不存在台标的台
        // 白等十几秒才轮到兜底台名。所以现在只有**索引里确实存在**的文件名才值得试。
        List<String> files = new ArrayList<>(4);

        // ensureIndex() 内部已加锁，拿到的一定是读完的索引，不会是 null。
        // 以前这里在索引还没读完时返回 0 个候选，而 candidates() 会把空结果按台名缓存，
        // 首屏那几十格就永久停在台名上 —— 现象是「上面几排没台标、下面几排有」，
        // 取决于那一格赶在索引读完前还是后绑定。
        Map<String, String> idx = ensureIndex(ctx);

        String mapped = EpgUtil.getLogo(channelName);
        if (mapped != null && !mapped.isEmpty()) {
            String f = fileNameOf(mapped);
            // 本地表也可能比台标库旧，指向一个已删的文件：也过一遍索引
            if (indexExact(idx, norm(f)) != null) addFile(files, f);
        }

        String guess = guess(channelName);
        String noUhd = UHD.matcher(guess).replaceAll("");
        addFile(files, indexExact(idx, norm(guess)));
        addFile(files, indexExact(idx, norm(noUhd)));
        addFile(files, fuzzy(idx, norm(noUhd).isEmpty() ? norm(guess) : norm(noUhd)));

        // 索引一条都没读到（assets 异常）：退回老办法，把猜出来的名字试一次。
        // 不这么做的话这种设备上会「整面墙一个台标都没有」，比多发一次 404 更糟。
        if (files.isEmpty() && idx.isEmpty()) {
            addFile(files, guess);
            if (!noUhd.equals(guess)) addFile(files, noUhd);
        }

        List<String> out = new ArrayList<>(MAX_CANDIDATES);
        for (String file : files) {
            addUrl(out, file);
        }
        return out.toArray(new String[0]);
    }

    private static void addFile(List<String> files, String file) {
        if (file == null || file.isEmpty()) return;
        if (!files.contains(file)) files.add(file);
    }

    /** 同一份台标只拼镜像地址（原因见 {@link #MIRROR}） */
    private static void addUrl(List<String> out, String file) {
        if (file == null || file.isEmpty()) return;
        if (out.size() >= MAX_CANDIDATES) return;
        String url = MIRROR + Uri.encode(file) + ".png";
        if (!out.contains(url)) out.add(url);
    }

    /** {@code https://…/tv/CCTV4.png} → {@code CCTV4} */
    private static String fileNameOf(String url) {
        if (url == null) return "";
        String name = url.substring(url.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * 台名 → 台标文件名（不含扩展名）。规则来自台标库的实际命名：CCTV5、湖南卫视、CHC动作电影
     *
     * <p>[Not 24/7]、[1080P] 这类方括号标记一律剥掉 —— 它们是源里的元信息，
     * 台标库里不可能有叫「苏州生活资讯[Not24/7]」的文件。
     */
    static String guess(String channelName) {
        String s = channelName == null ? "" : channelName.trim();
        s = NOISE_TAG.matcher(s).replaceAll("").trim();
        s = TAIL.matcher(s).replaceAll("");
        s = STRIP.matcher(s).replaceAll("");
        if (s.isEmpty()) return "";
        // 台标库里 CCTV5 是全大写；中文台名不受影响
        return s.toUpperCase(Locale.ROOT);
    }

    /** 索引用的归一化：全大写、去掉所有连接符/空白/括号 */
    private static String norm(String s) {
        if (s == null) return "";
        return STRIP.matcher(s.toUpperCase(Locale.ROOT)).replaceAll("");
    }

    private static String indexExact(Map<String, String> idx, String normalized) {
        if (normalized.isEmpty() || idx == null) return null;
        return idx.get(normalized);
    }

    /**
     * 前缀模糊：只认「差三四个字以内、且多出来的一段不是纯数字」的情况。
     *
     * <p>不设这条限制的话 CCTV15 会匹到 CCTV1、CCTV1 又会匹到 CCTV11 ——
     * 中央台的台标全靠数字区分，认错比没有更糟。
     */
    private static String fuzzy(Map<String, String> idx, String n) {
        if (idx == null || idx.isEmpty() || n.length() < 3) return null;
        String best = null;
        int bestLen = 0;
        for (Map.Entry<String, String> e : idx.entrySet()) {
            String k = e.getKey();
            if (k.length() >= 3 && n.startsWith(k)) {
                String extra = n.substring(k.length());
                if (extra.length() <= 4 && !allDigits(extra) && k.length() > bestLen) {
                    best = e.getValue();
                    bestLen = k.length();
                }
            } else if (k.startsWith(n)) {
                String extra = k.substring(n.length());
                if (extra.length() <= 3 && !allDigits(extra) && n.length() > bestLen) {
                    best = e.getValue();
                    bestLen = n.length();
                }
            }
        }
        return best;
    }

    private static boolean allDigits(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    /** 索引只读一次（930 行，约 11KB） */
    /**
     * 读索引，返回**读完的**那张表（读不到就返回空表，不会返回 null）。
     *
     * <p>为什么必须加锁串行：首屏一次要绑几十格，预热的三个线程也会同时进来。
     * 老写法是「谁先到谁置 indexTried=true，其余人立刻拿 null 返回」——
     * 于是那些赶在读完之前的格子算出 0 个候选，而 {@link #candidates} 又会把这个
     * 空结果按台名缓存住，索引读完也不会再算一遍。表现出来就是**上面几排没台标、
     * 下面几排有**，而且重启也一样（每次启动都要重新赛一次）。
     *
     * <p>现在后来的人在这里等几十毫秒，拿到的一定是同一张完整的表。
     */
    private static Map<String, String> ensureIndex(Context ctx) {
        Map<String, String> ready = index;
        if (ready != null) return ready;
        if (ctx == null) return EMPTY_INDEX;
        synchronized (INDEX_LOCK) {
            if (index != null) return index;
            Map<String, String> m = new HashMap<>(2048);
            BufferedReader br = null;
            try {
                br = new BufferedReader(new InputStreamReader(
                        ctx.getAssets().open("logo_index.txt"), "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    m.put(norm(line), line);
                }
            } catch (Throwable ignored) {
                // 索引读不到就退回「只靠猜」，不影响主流程
            } finally {
                if (br != null) try {
                    br.close();
                } catch (Throwable ignored) {
                }
            }
            index = m;
            return m;
        }
    }
}
