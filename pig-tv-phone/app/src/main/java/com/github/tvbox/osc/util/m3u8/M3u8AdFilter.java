package com.github.tvbox.osc.util.m3u8;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * m3u8 广告段过滤。
 *
 * <p>启发式规则来自对 dytt / rycjapi / 暴风(adjump) / phimgood 等采集站的实测归纳：
 * 采集站插广告有两种做法 —— 一是把一支固定广告整段插在片头（或中插），用
 * {@code #EXT-X-DISCONTINUITY} 与正片隔开；二是把广告分片放在带 {@code adjump} 之类
 * 特征的路径下。两种都能靠「分组结构 + 时长 + URL 特征」识别出来。
 *
 * <p>核心是**宁可漏杀不可错杀**：正片分段本身也可能只有 15~40s，一旦按时长猜就会
 * 把正片吃掉。所以每条规则都要求多个条件同时成立，拿不准就原样返回。
 *
 * <p>本类不依赖 Android，便于直接跑单元测试。
 */
public final class M3u8AdFilter {

    /** 片头插入段：总时长上限（超过就当成正常分段） */
    private static final int LEAD_AD_MAX_SECONDS = 90;
    /** 片头插入段：分片数上限 */
    private static final int LEAD_AD_MAX_SEGMENTS = 20;
    /** 中插广告段：段时长上限 */
    private static final int INTERSTITIAL_MAX_SECONDS = 30;
    /** 中插广告段：前后邻段至少要这么长，才算「两长夹一短」 */
    private static final int INTERSTITIAL_NEIGHBOR_MIN_SECONDS = 120;

    private static final Pattern EXTINF = Pattern.compile("^#EXTINF:\\s*([\\d.]+)");
    /** 广告分片 URL 特征：暴风等采集云把中插广告放在 /video/adjump/time/… 这类路径下 */
    private static final Pattern AD_URL = Pattern.compile("adjump|advert|/ads?/", Pattern.CASE_INSENSITIVE);

    private static final String DISC = "#EXT-X-DISCONTINUITY";
    private static final String KEY = "#EXT-X-KEY";
    private static final String MAP = "#EXT-X-MAP";

    private M3u8AdFilter() {
    }

    /**
     * 过滤入口。按可靠度依次执行：URL 特征中插段 → 纯时长型中插段 → 片头插入段，
     * 最后清掉首分片之前失去语义的 DISCONTINUITY。
     *
     * @return 过滤后的内容；不是播放列表、或过滤后一个分片都不剩（说明判断错了）时原样返回
     */
    public static String strip(String content) {
        if (content == null || content.isEmpty()) return content;
        // 不是 m3u8（可能是被误判成播放列表的普通文件），一个字都不要动
        if (!content.contains("#EXTM3U") || !content.contains("#EXTINF")) return content;

        String out = stripMarkedAdGroups(content);
        // 只有点播列表才做结构判断：直播是滚动窗口（常常只有 3~5 个分片、总时长 30s 左右），
        // 正好落在「片头短段」的判定区间，照规则走会把正在播的内容删掉。
        // 判据用 #EXT-X-ENDLIST —— 有它才是完整点播列表。
        boolean vod = content.contains("#EXT-X-ENDLIST");
        if (vod) {
            out = stripInterstitialAdGroups(out);
            out = stripLeadAdGroup(out);
        }
        out = stripLeadingDisc(out);
        // 兜底：万一规则误判把分片删光，退回原文，宁可看广告也不能不能播
        if (!hasSegment(out)) return content;
        return out;
    }

    // ------------------------------------------------------------------ 规则 1：URL 特征

    /**
     * 按 URL 特征剔除 DISCONTINUITY 段（片头/中插都覆盖）。
     *
     * <p>保守判定：段内**全部分片**都命中广告 URL 特征才整段剔除；只要混入一个正片
     * 分片就放过。剔除时保留段内 EXT-X-KEY / EXT-X-MAP（正片可能复用同一套解密配置，
     * 多声明一次无副作用；删了反而可能解不开）。
     */
    static String stripMarkedAdGroups(String content) {
        String[] lines = splitLines(content);
        boolean[] drop = new boolean[lines.length];
        boolean any = false;

        int i = 0;
        while (i < lines.length) {
            if (!isDisc(lines[i])) {
                i++;
                continue;
            }
            int j = i + 1;
            int count = 0;
            boolean allAd = true;
            while (j < lines.length && !isDisc(lines[j])) {
                if (isSegment(lines[j])) {
                    count++;
                    if (!AD_URL.matcher(lines[j]).find()) allAd = false;
                }
                j++;
            }
            if (count > 0 && allAd) {
                drop[i] = true;
                for (int k = i + 1; k < j; k++) {
                    String t = lines[k].trim();
                    if (t.startsWith(KEY) || t.startsWith(MAP)) continue;
                    drop[k] = true;
                }
                any = true;
            }
            i = j;
        }
        return any ? join(lines, drop) : content;
    }

    // ------------------------------------------------------- 规则 2：两长夹一短的超短段

    /**
     * 剔除「长正片段之间的超短插入段」（纯时长型中插广告）。
     *
     * <p>判定：段时长 ≤ 30s，且前后邻段时长都 ≥ 120s。dytt 式「全片都是 15~40s 短段」
     * 的源因为邻段不满足长段条件而完全不触发。首组交给片头规则处理，这里只看 k ≥ 1 的组。
     */
    static String stripInterstitialAdGroups(String content) {
        String[] lines = splitLines(content);
        List<int[]> groups = scanGroups(lines);   // {start, end, discLine, segCount, seconds, unused}
        boolean[] drop = new boolean[lines.length];
        boolean any = false;

        for (int k = 1; k < groups.size(); k++) {
            int[] g = groups.get(k);
            int[] prev = groups.get(k - 1);
            int[] next = k + 1 < groups.size() ? groups.get(k + 1) : null;
            int start = g[0], end = g[1], discLine = g[2], count = g[3], seconds = g[4];
            if (count == 0 || discLine < 0) continue;
            if (seconds <= 0 || seconds > INTERSTITIAL_MAX_SECONDS) continue;
            if (prev[4] < INTERSTITIAL_NEIGHBOR_MIN_SECONDS) continue;
            if (next == null || next[4] < INTERSTITIAL_NEIGHBOR_MIN_SECONDS) continue;

            drop[discLine] = true;
            for (int i = start; i < end; i++) {
                String t = lines[i].trim();
                if (t.startsWith(KEY) || t.startsWith(MAP)) continue;
                drop[i] = true;
            }
            any = true;
        }
        return any ? join(lines, drop) : content;
    }

    // ------------------------------------------------------------- 规则 3：片头插入段

    /**
     * 剔除片头插入的广告段。
     *
     * <p>采集站常把一支 15~60s 的广告当作首个 DISCONTINUITY 段混进所有视频。但它和
     * 「采集站按固定块切正片、每个衔接处都打 DISCONTINUITY」在结构上可能完全一样，
     * 所以先做同构检查：分组数 ≥ 4 且片头组大小与其余组中位数接近时，判定为封装边界，
     * 直接跳过（实测 rycjapi 每组 5 片、dytt 每组多为 5 片，都曾被误删过正片）。
     */
    static String stripLeadAdGroup(String content) {
        String[] lines = splitLines(content);
        List<Integer> discIdx = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) if (isDisc(lines[i])) discIdx.add(i);

        // 同构检查：片头组与其余分组大小一致 ⇒ 是封装边界，不是广告
        if (discIdx.size() >= 4) {
            int leadSize = segmentCount(lines, discIdx.get(0) + 1, discIdx.get(1));
            List<Integer> others = new ArrayList<>();
            for (int g = 1; g < discIdx.size(); g++) {
                int from = discIdx.get(g) + 1;
                int to = g + 1 < discIdx.size() ? discIdx.get(g + 1) : lines.length;
                others.add(segmentCount(lines, from, to));
            }
            List<Integer> sorted = new ArrayList<>(others);
            java.util.Collections.sort(sorted);
            int median = sorted.get(sorted.size() / 2);
            int tolerance = Math.max(3, (int) (median * 0.5));
            if (Math.abs(leadSize - median) <= tolerance) return content;
        }

        if (discIdx.isEmpty()) return content;
        int firstDisc = discIdx.get(0);
        // 首个 DISCONTINUITY 之前已经有分片 ⇒ 不是「片头插入段」结构
        for (int i = 0; i < firstDisc; i++) if (isSegment(lines[i])) return content;

        int nextDisc = -1;
        for (int i = 1; i < discIdx.size(); i++) {
            if (discIdx.get(i) > firstDisc) {
                nextDisc = discIdx.get(i);
                break;
            }
        }
        if (nextDisc < 0) return content;

        double seconds = 0;
        int count = 0;
        for (int i = firstDisc + 1; i < nextDisc; i++) {
            String t = lines[i].trim();
            Matcher m = EXTINF.matcher(t);
            if (m.find()) {
                seconds += parseDouble(m.group(1));
            } else if (isSegment(lines[i])) {
                count++;
            }
        }
        if (count == 0 || count > LEAD_AD_MAX_SEGMENTS) return content;
        if (seconds <= 0 || seconds > LEAD_AD_MAX_SECONDS) return content;

        boolean[] drop = new boolean[lines.length];
        for (int i = firstDisc; i < nextDisc; i++) {
            String t = lines[i].trim();
            if (i > firstDisc && (t.startsWith(KEY) || t.startsWith(MAP))) continue;
            drop[i] = true;
        }
        return join(lines, drop);
    }

    // ---------------------------------------------------------------- 收尾：首分片前的 DISC

    /** 清掉首个分片之前的 DISCONTINUITY：前面没有媒体内容，这个标记没有语义 */
    static String stripLeadingDisc(String content) {
        String[] lines = splitLines(content);
        int firstSeg = -1;
        for (int i = 0; i < lines.length; i++) {
            if (isSegment(lines[i])) {
                firstSeg = i;
                break;
            }
        }
        if (firstSeg <= 0) return content;
        boolean[] drop = new boolean[lines.length];
        boolean any = false;
        for (int i = 0; i < firstSeg; i++) {
            if (DISC.equals(lines[i].trim())) {
                drop[i] = true;
                any = true;
            }
        }
        return any ? join(lines, drop) : content;
    }

    // -------------------------------------------------------------------------- 工具方法

    private static String[] splitLines(String content) {
        return content.split("\n", -1);
    }

    private static boolean isDisc(String line) {
        return DISC.equals(line.trim());
    }

    private static boolean isSegment(String line) {
        String t = line.trim();
        return !t.isEmpty() && !t.startsWith("#");
    }

    private static boolean hasSegment(String content) {
        for (String line : splitLines(content)) if (isSegment(line)) return true;
        return false;
    }

    private static int segmentCount(String[] lines, int from, int to) {
        int n = 0;
        for (int i = from; i < to && i < lines.length; i++) if (isSegment(lines[i])) n++;
        return n;
    }

    /** 按 DISCONTINUITY 切组，返回 {start, end, discLine, segCount, seconds} */
    private static List<int[]> scanGroups(String[] lines) {
        List<Integer> discIdx = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) if (isDisc(lines[i])) discIdx.add(i);

        List<Integer> bounds = new ArrayList<>();
        bounds.add(0);
        bounds.addAll(discIdx);
        bounds.add(lines.length);

        List<int[]> groups = new ArrayList<>();
        for (int k = 0; k < bounds.size() - 1; k++) {
            int start = k == 0 ? 0 : bounds.get(k) + 1;
            int end = bounds.get(k + 1);
            int seconds = 0;
            int count = 0;
            for (int i = start; i < end; i++) {
                String t = lines[i].trim();
                Matcher m = EXTINF.matcher(t);
                if (m.find()) {
                    seconds += (int) parseDouble(m.group(1));
                } else if (isSegment(lines[i])) {
                    count++;
                }
            }
            groups.add(new int[]{start, end, k == 0 ? -1 : bounds.get(k), count, seconds});
        }
        return groups;
    }

    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String join(String[] lines, boolean[] drop) {
        StringBuilder sb = new StringBuilder(lines.length * 32);
        for (int i = 0; i < lines.length; i++) {
            if (drop[i]) continue;
            sb.append(lines[i]);
            if (i != lines.length - 1) sb.append('\n');
        }
        return sb.toString();
    }
}
