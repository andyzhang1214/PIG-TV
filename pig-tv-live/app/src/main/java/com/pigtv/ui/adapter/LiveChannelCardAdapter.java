package com.pigtv.ui.adapter;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.pigtv.R;
import com.pigtv.util.ChannelLogo;
import com.pigtv.util.LogoStore;
import com.pigtv.util.UiTheme;

import java.io.File;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 直播频道墙的适配器。
 *
 * <p>一格一个频道，按「全部」时是全站频道平铺（几十到几百格），选了分组就只留那一组。
 * 分组不做成多个列表：源站给的分组顺序没有意义，用户也记不住哪个台在第几组，
 * 平铺 + 顶上一排胶囊是手机上验证过的找台方式。
 *
 * <p>卡片只有三样东西：左边完整的台标（见 {@link ChannelLogo}，拿不到的画统一占位图标，
 * 不退回「C5」这种缩写）、底部左边那颗收藏星、底部右边的画质角标。
 *
 * <p>卡面不写台名。台标图本身几乎都带台名（「延边卫视」「湖南卫视」），
 * 底下再排一遍同样的字，重复又没有信息量；名字让位给更大的台标图，认台反而更快。
 * 台名仍然挂在根 View 的 contentDescription 上，读屏和调试都还拿得到。
 *
 * <p>浅色主题下白字台标（CCTV 系）由 {@link LogoStore} 反相成黑版再显示。
 */
public class LiveChannelCardAdapter extends BaseQuickAdapter<LiveChannelCardAdapter.Cell, BaseViewHolder> {

    /**
     * 画质标记：{@code [1080P]} / {@code [720P]} / {@code [2160P]} / {@code [4K]} / {@code [8K]}。
     *
     * <p>只认这几种 —— 源里还见过 {@code [Not 24/7]}、{@code [%E6%99%B4%E5%9B%AD]}（URL 编码残留），
     * 那些有的是台名的一部分、有的根本在地址里，用「去掉所有方括号」的宽规则会一起吃掉。
     */
    private static final Pattern QUALITY_TAG = Pattern.compile("\\[(?:\\d{3,4}[pP]|[248][kK])\\]");

    /** 台名末尾的画质词（「CCTV-4K 高清」的「高清」），只在结尾剥，中间的不动（「高清频道」是台名） */
    private static final Pattern QUALITY_WORD = Pattern.compile("(?:高清|超清|蓝光|标清)$");

    /**
     * 4K 判定。{@code (?<![0-9])} 是必需的：「CCTV-40K」这种不是 4K 频道，
     * 没有这个前置否定会被当成 40 + K 里的那个 4 匹配上。
     */
    private static final Pattern UHD = Pattern.compile("(?<![0-9])(?:4[kK]|2160)|[uU][hH][dD]");

    /**
     * 占位图相对文字暗色的透明度：只是一块「这里本来该有图」的提示，不该抢眼。
     *
     * <p>卡面不写台名之后，占位图是这一格唯一的内容，70 太淡（整格看着像空的），
     * 提到 96 —— 还是明显是「缺图」的灰，但至少让人看出这儿有张卡。
     */
    private static final int PLACEHOLDER_ALPHA = 96;

    /** 占位图要染的颜色，按主题解析一次就够（同一次会话里界面明暗不会变，切了会重建 Activity） */
    private int placeholderTint = 0;

    /** 当前是不是浅色主题：浅色下白字台标（CCTV 系）要换成反相出的黑版才看得见 */
    private boolean themeResolved = false;
    private boolean lightTheme = false;

    /**
     * 一个频道格。
     *
     * <p>{@code groupIndex} / {@code channelIndex} 必须是「在 ApiConfig.channelGroupList 里的原始下标」：
     * LivePlayActivity 拿它们直接 {@code list.get(groupIndex)} / {@code channels.get(channelIndex)}，
     * 自己重新编号就会播到隔壁那个台。
     */
    public static class Cell {
        public final int groupIndex;
        public final int channelIndex;
        /** 原台名（含画质标记）：查台标用，显示另走 {@link #name} */
        public final String rawName;
        public final String name;
        /** 右下角角标：画质 · 几路源 · 加密，一样都没有就整个不显示 */
        public final String tag;
        /** 4K 频道：画质角标走金色（跟别的画质区分开） */
        public final boolean is4k;
        /** 首路地址：收藏按「名字 + 地址」认台，格子里得带着它 */
        public final String firstUrl;
        /** 源自带的台标（m3u 的 tvg-logo），没有就是空串 —— 见 {@link ChannelLogo#candidates} */
        public final String logoUrl;
        /** 上次看的那个台，进页面时描一圈边 */
        public boolean selected = false;
        /** 这一格是不是已收藏：决定左下角那颗星是金色实心还是空轮廓 */
        public boolean fav = false;

        public         Cell(int groupIndex, int channelIndex, String name, String tag, String firstUrl,
                            String logoUrl, boolean is4k) {
            this.groupIndex = groupIndex;
            this.channelIndex = channelIndex;
            this.rawName = name == null ? "" : name;
            // 台名里的 [1080P] 之类是「这条源的信息」，不是台名本身。
            // 卡片宽度有限，留着这几个字，后半截台名必被省略号吃掉。
            this.name = cleanName(name);
            this.tag = tag == null ? "" : tag;
            // 由调用方按「台名 + 分组 + 地址」算好传进来：同一个判定在
            // LiveChannelActivity.Raw 里已经算过一遍，两边各算一次迟早会分叉
            this.is4k = is4k;
            this.firstUrl = firstUrl == null ? "" : firstUrl;
            this.logoUrl = logoUrl == null ? "" : logoUrl;
        }
    }

    public LiveChannelCardAdapter() {
        super(R.layout.item_live_channel_card, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder helper, Cell item) {
        // 卡面不写台名（台标图里自带），但名字不能真丢：挂在根 View 上供读屏和调试用
        helper.itemView.setContentDescription(item.name);

        // 左下角收藏星：两枚矢量同星形，只换填充方式，切换时位置不跳
        ImageView star = helper.getView(R.id.tvStar);
        star.setImageResource(item.fav ? R.drawable.ic_fav_star : R.drawable.ic_fav_star_outline);

        // 右下角画质角标：4K 金色、普通画质走主题强调色；两个都是 selector，
        // 焦点卡反白时跟着翻色（否则金色落在白底上看不见）。
        // 注意这个 fork 的 BaseQuickAdapter 里 setGone(id, true) 实际是 VISIBLE，
        // 方向是反的，可见性一律直接 setVisibility。
        TextView tag = helper.getView(R.id.tvTag);
        if (TextUtils.isEmpty(item.tag)) {
            tag.setVisibility(View.GONE);
        } else {
            tag.setText(item.tag);
            tag.setTextColor(ContextCompat.getColorStateList(helper.itemView.getContext(),
                    item.is4k ? R.color.v8_live_tag_gold : R.color.v8_live_tag_tx));
            tag.setVisibility(View.VISIBLE);
        }

        // 焦点卡的底色在深浅两套主题下都是浅的（深色主题里是一整块白），
        // 白字台标落上去就没了 —— 所以「焦点」本身就要换成反相黑版，
        // 等价于浅色主题。焦点变化时重绑一次，除焦点卡外其余格子不受影响。
        final boolean lightTheme = isLightTheme(helper.itemView.getContext());
        bindLogo(helper.getView(R.id.tvLogo), helper.getView(R.id.tvFallbackName),
                item.name, item.rawName, item.logoUrl, lightTheme || helper.itemView.isFocused(), false);
        helper.itemView.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                // keepCurrent=true：焦点移动时保持现有图，等黑版就位再换，
                // 否则每挪一格焦点，刚亮起来的那张卡都要先闪一下台名
                bindLogo(v.findViewById(R.id.tvLogo), v.findViewById(R.id.tvFallbackName),
                        item.name, item.rawName, item.logoUrl, lightTheme || hasFocus, true);
            }
        });

        helper.itemView.setSelected(item.selected);
    }

    // =====================================================================
    //  台标
    // =====================================================================

    /**
     * 给这一格加载台标。
     *
     * <p>下载不走 Glide（原因见 {@link LogoStore}：台标站点的 AAAA 记录被污染，
     * Glide 用的 HttpURLConnection 会先试 IPv6、白等十秒）。下载和「黑版反相」
     * 都交给 {@link LogoStore#load} 统一处理，回来的是这一格最终该显示的那份文件，
     * Glide 只做解码显示。
     *
     * <p>先上台名、后上台标：台标要联网拿，快的几百毫秒、慢的几秒，
     * 这期间卡面不该是一块灰相框（用户根本认不出是哪个台）。名字先顶着，
     * 图到手再换成图；库里压根没有这个台的，就一直是名字。
     *
     * @param fallback 写台名的 TextView
     * @param name     台名（已去掉画质标记）
     * @param srcLogo  源自带的台标地址（m3u 的 tvg-logo），可为空
     */
    private void bindLogo(final ImageView iv, final TextView fallback,
                          final String name, final String rawName, final String srcLogo,
                          final boolean light, final boolean keepCurrent) {
        final Context ctx = iv.getContext();
        final String[] urls = ChannelLogo.candidates(ctx, rawName, srcLogo);
        // 记住这一格现在该显示谁：加载是异步的，等它回来时这个 View 可能已经被
        // 滚出去复用给别的台了，那时再往上面塞图就成了「CCTV-5 的位置贴着湖南卫视的标」
        iv.setTag(rawName);
        if (!keepCurrent) showName(iv, fallback, name);
        if (urls.length == 0) return;
        LogoStore.load(ctx, urls, light, new LogoStore.Callback() {
            @Override
            public void onResult(File file) {
                if (!rawName.equals(iv.getTag())) return;
                // 拿不到就保持台名，不拉黑 —— 下次滚回来或下次启动再试一遍
                if (file == null) return;
                showLogo(iv, fallback, file);
            }
        });
    }

    /** 把卡面切回「只有台名」的状态 */
    private void showName(ImageView iv, TextView fallback, String name) {
        // 复用过来的 View 还挂着上一格的图：先撒图片请求再清 drawable，
        // 否则上一格的图可能在几毫秒后落到这一格上
        Glide.with(iv).clear(iv);
        iv.setImageDrawable(null);
        if (TextUtils.isEmpty(name)) {
            // 连台名都没有的极端情况：留住占位图，至少说明这儿有张卡
            iv.setImageDrawable(placeholder(iv.getContext()));
            fallback.setVisibility(View.GONE);
            return;
        }
        fallback.setText(name);
        fallback.setVisibility(View.VISIBLE);
    }

    /**
     * 本地文件 → ImageView。解码缓存交给 Glide，位图不用自己管。
     *
     * <p>台名在**图真的画上去之后**才收（见 {@code onResourceReady}）。
     * 以前是拿到文件就先收，遇到解不出来的图（浅色主题下反相失败退回白版、
     * 白字落在白卡上）就成了一块完全空白的卡 —— 既没图也没字，认不出是哪个台。
     */
    private void showLogo(final ImageView iv, final TextView fallback, File file) {
        Glide.with(iv)
                .load(file)
                .dontAnimate()
                .listener(new RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object model,
                                                Target<Drawable> target, boolean isFirstResource) {
                        // 解不出来：台名留着，总比空白卡强
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(Drawable resource, Object model,
                                                   Target<Drawable> target, DataSource source,
                                                   boolean isFirstResource) {
                        fallback.setVisibility(View.GONE);
                        return false;
                    }
                })
                .into(iv);
    }

    /**
     * 占位图：一个电视机轮廓，按主题的次要文字色染淡。
     *
     * <p>每次新建一份而不是复用同一个 Drawable 实例 —— 同一个 Drawable 不能同时挂在
     * 几十个 ImageView 上，bounds 会互相覆盖，结果是所有格子的图标挤成一小团。
     */
    private Drawable placeholder(Context ctx) {
        Drawable d = ContextCompat.getDrawable(ctx, R.drawable.img_logo_placeholder);
        if (d == null) return null;
        d = d.mutate();
        d.setTint(tint(ctx));
        d.setAlpha(PLACEHOLDER_ALPHA);
        return d;
    }

    /** 主题里的次要文字色（深色主题是半透明白，浅色主题是灰） */
    private int tint(Context ctx) {
        if (placeholderTint != 0) return placeholderTint;
        TypedValue tv = new TypedValue();
        Resources.Theme theme = ctx.getTheme();
        if (theme != null && theme.resolveAttribute(R.attr.uiTextDim, tv, true)) {
            placeholderTint = tv.data;
        } else {
            placeholderTint = 0x66FFFFFF;
        }
        return placeholderTint;
    }

    /** 浅色主题判定：解析一次记住（主题切换会重建 Activity） */
    private boolean isLightTheme(Context ctx) {
        if (themeResolved) return lightTheme;
        lightTheme = UiTheme.isLight(ctx);
        themeResolved = true;
        return lightTheme;
    }

    // =====================================================================
    //  选中态 / 收藏态
    // =====================================================================

    /**
     * 收藏状态变了，只换那一格左下角的星。
     *
     * <p>不走 notifyItemChanged：重绑会让 RecyclerView 走一遍布局，焦点挂在
     * 这一格的 itemView 上时会被弹掉 —— 遥控器长按收藏完，焦点跑到屏幕外去了。
     * 所以跟 {@link #setSelectedQuiet} 一样，只捞屏幕上那块 View 直接改图。
     */
    public void setFavQuiet(RecyclerView rv, int position, boolean fav) {
        if (position < 0 || position >= getData().size()) return;
        getData().get(position).fav = fav;
        if (rv == null) {
            notifyDataSetChanged();
            return;
        }
        for (int i = 0; i < rv.getChildCount(); i++) {
            View child = rv.getChildAt(i);
            if (rv.getChildAdapterPosition(child) != position) continue;
            ImageView star = child.findViewById(R.id.tvStar);
            if (star != null) {
                star.setImageResource(fav ? R.drawable.ic_fav_star : R.drawable.ic_fav_star_outline);
            }
            return;
        }
    }

    /** 上次看的那个台换了位置，只改这两格的 selected，不整表 notify（电视上一 notify 焦点就丢） */
    public void setSelectedQuiet(RecyclerView rv, int position) {
        for (int i = 0; i < getData().size(); i++) {
            getData().get(i).selected = (i == position);
        }
        if (rv == null) {
            notifyDataSetChanged();
            return;
        }
        for (int i = 0; i < rv.getChildCount(); i++) {
            android.view.View child = rv.getChildAt(i);
            int pos = rv.getChildAdapterPosition(child);
            if (pos < 0 || pos >= getData().size()) continue;
            child.setSelected(getData().get(pos).selected);
        }
    }

    // =====================================================================
    //  台名
    // =====================================================================

    /**
     * 这一格是不是 4K 频道（只看台名）。
     *
     * <p>名字里的「4K / 2160 / UHD」是最直接的信号。名字标了 4K 但实际推 1080P 的源也有
     * （央视那几条就是），这种情况以名字为准 —— 用户按名字挑台，看到的和挑的一致才不困惑。
     */
    public static boolean is4k(String name) {
        return name != null && UHD.matcher(name).find();
    }

    /**
     * 4K 判定（台名 + 分组名 + 地址三条线索，任一命中即算）。
     *
     * <p>只看台名不够，实测两份公开源各有各的写法：
     * <ul>
     *   <li>suxuang 把超高清单独归到 {@code group-title="4K频道"}，里面大半台名里
     *       根本不带 4K（{@code Love Nature}、{@code RHK 31}），只看台名会漏掉 16 个；</li>
     *   <li>Guovin 的台名规整成 {@code CCTV-4}，可它的线路是 {@code cctv4k.m3u8} ——
     *       名字看不出是 4K，地址写着。</li>
     * </ul>
     *
     * <p>地址这一条<b>必须</b>沿用台名那套「前面不能是数字」的正则、且只比路径不比查询串：
     * 蜻蜓fm 的音频线路全是 {@code /live/1650/64k.m3u8}，宽松地找 {@code 4k} 会把
     * 13 条广播当成 4K 频道塞进专区（实测过一次，全是「黑龙江新闻」这种）。
     */
    public static boolean is4k(String name, String group, String url) {
        if (is4k(name)) return true;
        if (group != null && UHD.matcher(group).find()) return true;
        if (url != null) {
            int q = url.indexOf('?');
            return UHD.matcher(q >= 0 ? url.substring(0, q) : url).find();
        }
        return false;
    }

    /**
     * 台名里去掉画质标记，留给卡片主标题。
     *
     * <p>「CCTV-4 中文国际 [1080P]」在 6 列布局里只能显示到「CCTV-4 中文国际 [...]」，
     * 括号里的东西用户看不到、还白占了十几个字符的位置。画质没丢，由
     * {@link #qualityOf} 取出来塞到副标题里。
     *
     * <p>剥完可能剩个尾巴（「CCTV-5 - 」），一起清掉；万一整名都是画质词就退回原名，
     * 不能让卡片变成空白。
     */
    public static String cleanName(String name) {
        String raw = name == null ? "" : name.trim();
        if (raw.isEmpty()) return "";
        String s = QUALITY_TAG.matcher(raw).replaceAll("").trim();
        s = QUALITY_WORD.matcher(s).replaceAll("").trim();
        while (s.length() > 1 && "-·|_ ".indexOf(s.charAt(s.length() - 1)) >= 0) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s.isEmpty() ? raw : s;
    }

    /**
     * 台名里带的画质，给副标题用；没有就返回空串。
     *
     * <p>4K 优先：名字里既有「4K」又有「[1080P]」时（央视那几条），用户关心的是前者。
     */
    public static String qualityOf(String name) {
        String s = name == null ? "" : name;
        if (is4k(s)) return "4K";
        Matcher m = QUALITY_TAG.matcher(s);
        if (m.find()) {
            String tag = m.group();
            return tag.substring(1, tag.length() - 1).toUpperCase();
        }
        // cleanName() 会剥掉末尾的「高清 / 超清」等词，画质必须转交到角标，
        // 否则既不在台名里、也不在右下角，信息被凭空吃掉。
        Matcher word = QUALITY_WORD.matcher(s.trim());
        return word.find() ? word.group() : "";
    }
}
