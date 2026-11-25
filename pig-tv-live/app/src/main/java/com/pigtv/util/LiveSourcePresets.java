package com.pigtv.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 「推荐源」清单：几个公开维护的聚合 m3u，源管理页里一键添加。
 *
 * <p>为什么要有这个：内置的 {@code assets/live_builtin.txt} 是逐条手测出来的，
 * 稳定但只有 368 行；用户想要更多台（地方台、咪咕赛事、4K）就只能自己去网上找
 * 一条 m3u 地址，再用遥控器一个字一个字敲进去 —— 电视上这基本做不到。
 * 这里把几条自己实测过、维护活跃的源写死，点一下就能加。
 *
 * <p>地址统一走 {@code gcore.jsdelivr.net} 镜像而不是 raw.githubusercontent.com：
 * 国内网络下 raw 域名大多连不上，jsdelivr 能通（实测 2026-11-25，全部 200）。
 * 镜像上的内容是 CDN 缓存的，上游更新后几分钟到几小时会跟上，对直播源来说够用。
 *
 * <p>没放 iptv-org 的 cn.m3u：它 522 条线路全是英文台名、没有 group-title，
 * 解析出来只剩一个「未分组」分组，和本应用按分组浏览的交互对不上，
 * 用户点进去会看到一长串 "Anhui TV (1080p)"，不如不给。
 *
 * <p>这里是纯数据，不碰 Hawk 也不做网络请求 —— 添加、切换、重启都由
 * {@code LiveSourceActivity} 走 {@link LiveSourceStore} 完成，和手动添加同一条路径。
 */
public final class LiveSourcePresets {

    /** 一条推荐源 */
    public static class Preset {
        public final String name;
        public final String url;
        /** 一句话说明：有哪些台、多少分组，用户据此判断要不要换 */
        public final String desc;
        /**
         * 台名是否规整（{@code CCTV-1} / {@code 湖南卫视} 这种写法）。
         *
         * <p>规整才谈得上台标和节目单：本应用的 EPG 走的是 epg.112114.xyz 的
         * 「台名查节目单」接口，台名叫「CCTV1 高清」和叫「CCTV-1」查到的是两条不同的东西。
         * 注意 m3u 里自带的 {@code x-tvg-url} 我们**没有**解析 —— 那是 XMLTV 格式，
         * 和现在这套 JSON 接口不兼容，所以这里只保证「名字对得上」，不是「自带 EPG」。
         */
        public final boolean nameStandard;

        Preset(String name, String url, String desc, boolean nameStandard) {
            this.name = name;
            this.url = url;
            this.desc = desc;
            this.nameStandard = nameStandard;
        }
    }

    private static List<Preset> cache;

    private LiveSourcePresets() {
    }

    /**
     * 全部推荐源，按「推荐程度」排序 —— 第一条是综合源，绝大多数用户到这里就够了。
     *
     * <p>条目数都是按 {@code #EXTINF} 去重台名后的实测值（2026-11-25），
     * 不是线路条数：同一个台往往有五六条线路，按线路数写会让用户以为有几千个台。
     */
    public static List<Preset> all() {
        if (cache != null) return cache;
        List<Preset> list = new ArrayList<>();
        list.add(new Preset(
                "综合频道表",
                "https://gcore.jsdelivr.net/gh/Guovin/iptv-api@gd/output/result.m3u",
                "约 590 个频道 / 40 个分组：央视、卫视、31 省地方台，外加体育、电影、动画。台名规范，台标和节目单能自动对上。",
                true));
        list.add(new Preset(
                "4K 与咪咕",
                "https://gcore.jsdelivr.net/gh/suxuang/myIPTV@main/ipv4.m3u",
                "约 900 个频道：4K 专区、咪咕央视与赛事、港澳台、数字频道。画质优先，线路偏少；部分台名带「4K」后缀，节目单可能对不上。",
                false));
        list.add(new Preset(
                "赛事与轮播",
                "https://gcore.jsdelivr.net/gh/YanG-1989/m3u@main/Gather.m3u",
                "约 120 个频道：咪咕游戏与赛事、影视轮播。条目少但补充性强，适合和上面两条对照着用。",
                false));
        cache = list;
        return cache;
    }
}
