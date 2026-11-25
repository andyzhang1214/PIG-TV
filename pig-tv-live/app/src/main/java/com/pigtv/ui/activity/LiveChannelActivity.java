package com.pigtv.ui.activity;

import android.content.Intent;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.pigtv.R;
import com.pigtv.api.ApiConfig;
import com.pigtv.base.BaseActivity;
import com.pigtv.bean.LiveChannelGroup;
import com.pigtv.bean.LiveChannelItem;
import com.pigtv.ui.adapter.LiveRailAdapter;
import com.pigtv.ui.adapter.LiveChannelCardAdapter;
import com.pigtv.ui.dialog.LiveChannelDetailDialog;
import com.pigtv.util.FavoriteStore;
import com.pigtv.util.JavaUtil;
import com.pigtv.util.LiveSourceStore;
import com.pigtv.util.LogoStore;
import com.pigtv.util.UiTheme;
import com.pigtv.util.V8TopBar;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 直播频道表。
 *
 * <p>点顶栏「直播」先进这一页，看清楚有哪些台，挑一个才进播放器。以前是直接进
 * {@link LivePlayActivity} 播上次那个台，常见结果是：那个台已经失效，屏幕一片黑，
 * 顶上也没有任何提示，用户得先按 MENU 把左侧列表叫出来才知道能看什么。
 *
 * <p>版式和手机端直播页对齐，用户在两个端上看到的是同一套东西：
 * <ul>
 *   <li>上面一行分组胶囊，第一项固定「全部」，紧跟一档「★ 收藏」；</li>
 *   <li>下面频道墙，全部视图里每格显示「分组名 · 几路源」，选了分组就显示「频道 N」；</li>
 *   <li>点一格直接进播放器并定位到那个台；<b>长按 OK 直接收藏 / 取消收藏</b>，
 *       <b>按 MENU 放大这一格</b>（台名、分组、地址 + 收藏按钮，见 {@link LiveChannelDetailDialog}）。</li>
 * </ul>
 *
 * <p>收藏为什么做成「长按」而不是格子上再挂一颗按钮：一格只有 180mm 宽，
 * 台名本来就只剩五六个字，多一颗可点的按钮会把名字全挤没；而频道卡点一下就是播放，
 * 这是最高频的动作，不能动。长按是遥控器上唯一「不占版面、不抢单击」的位置。
 * 入口的发现性由常驻的「★ 收藏」胶囊兜着：点进去没内容时会明说长按可以收藏。
 * 需要看得见的收藏按钮时按 MENU —— 那一层是「停下来看清楚」的地方，摆得下按钮。
 *
 * <p>几种「没有频道」的情况分开说，别都写一句「没有直播源」：用户自己填了直播源时
 * ApiConfig 只留了一个 127.0.0.1 的代理占位组，真正的频道要播放器去拉，
 * 这一页给不出来 —— 那就引导他进播放器，而不是让他去「配置直播源」。
 */
public class LiveChannelActivity extends BaseActivity {

    /** 配置里只有一个 http://127.0.0.1:9978/proxy?do=live 占位组 */
    private static final String PROXY_HOST = "http://127.0.0.1";

    /** 抢焦点的最大重试次数（每轮 120ms，约 1 秒；窗口焦点来得比这晚就交给 onWindowFocusChanged） */
    private static final int MAX_FOCUS_TRY = 8;

    /**
     * 「4K 超清」这个胶囊的提交值。
     *
     * <p>必须是个真分组下标取不到的值：4K 是跨分组的聚合视图，不是源里的一组台，
     * 拿 "0" 之类的数字去标会和真实分组撞车，点「4K」结果只看到央视频道那 13 个台。
     */
    private static final String K4_VALUE = "4k";

    /**
     * 「收藏」胶囊的提交值。和 {@link #K4_VALUE} 一样必须是个真分组下标取不到的值 ——
     * 收藏同样是跨分组的聚合视图，用 "0" 之类的数字会跟真实分组撞车。
     */
    private static final String FAV_VALUE = "fav";

    private TvRecyclerView mChipRow;
    private TvRecyclerView mChannelGrid;
    private TextView tvLiveStat;
    private TextView tvEmptyTitle;
    private TextView tvEmptyDesc;
    private TextView btnEmptyAction;
    private View liveEmptyBox;
    private View navCurrent;
    /** 收藏档为空时的提示（不借用 liveEmptyBox：那个会把胶囊行一起藏掉，用户就退不出收藏档了） */
    private View favHintBox;
    private TextView tvFavHintTitle;
    private TextView tvFavHintDesc;

    private LiveRailAdapter chipAdapter;
    private LiveChannelCardAdapter cardAdapter;
    /** 当前的频道详情弹层（按 MENU 放大那一格），同一时刻只留一个 */
    private LiveChannelDetailDialog detailDlg;
    /**
     * 弹层里取消了收藏，而当前正停在「收藏」档：这一格本该立刻从墙上消失，
     * 但弹层还开着，先把底下的卡抽走会让用户看着它「在弹层后面凭空消失」。
     * 记个标记，等弹层关掉再重铺并把焦点接回来。
     */
    private boolean detailReflow = false;

    /** 整表台标后台补齐只在第一份频道表时跑一次（换源会重建列表，不强求重跑） */
    private boolean logoWarmed = false;
    /** 自定义源请求代数：重试、切源或页面销毁后，旧回调一律不得再写 ApiConfig */
    private int loadGeneration = 0;

    /** 有频道的分组，对象里的 groupIndex 就是它在 ApiConfig 列表里的原始下标 */
    private final List<LiveChannelGroup> groups = new ArrayList<>();
    /** 全部频道（跨分组），按「分组顺序 + 组内顺序」排列 */
    private final List<Raw> rawAll = new ArrayList<>();

    /** -1 = 全部，否则是分组的 groupIndex（看 4K 视图时恒为 -1，靠 only4k 区分） */
    private int curGroup = -1;
    /**
     * 当前是不是「4K 超清」聚合视图。
     *
     * <p>不做成「往 ApiConfig 里插一个分组」：那一层被播放页按坐标取值
     * （{@code list.get(groupIndex)} / {@code channels.get(channelIndex)}），
     * 插一组会把后面所有分组的下标顶掉，收藏和「上次看到哪」全部错位到隔壁台。
     * 这里只在 UI 层筛一遍，卡片仍然指回原始坐标。
     */
    private boolean only4k = false;
    /** 当前是不是「收藏」聚合视图（同样只是 UI 层筛，卡片仍指回原始坐标） */
    private boolean onlyFav = false;
    /** 4K 频道条数，0 的时候连胶囊都不出现 —— 自定义源里没 4K 台时不该给个空分类 */
    private int k4Count = 0;
    /** 「M 个分组」里的 M，收藏档的统计文案要按普通视图还原，所以存下来 */
    private int catCount = 0;
    private boolean hasProxy = false;
    /**
     * 这一次按下已经处理过「长按收藏」了。
     *
     * <p>遥控器按住 OK 会连发 ActionDown（repeatCount 1,2,3…），不拦的话
     * 按住一秒能来回收藏取消十几次，最后落到什么状态全看手抖。
     */
    private boolean longPressDone = false;
    /**
     * 上一次收藏动作的时间戳。
     *
     * <p>同一次长按会从两条路各来一次（Activity 的按键层 + 卡片自己的 OnItemLongClick），
     * 350ms 内的第二次直接忽略 —— 不然用户按住一秒，收藏刚亮就被自己取消了。
     */
    private long lastFavToggleAt = 0;
    /**
     * 详情弹层刚关掉的时间戳。
     *
     * <p>弹层是独立窗口，BACK 的 DOWN 被它吃掉（关窗），同一段的 UP 却会落到这一页上，
     * 触发 {@code onBackPressed()} 把频道墙也关掉 —— 用户按一次 BACK 直接退到桌面。
     * 实测是「有时」而不是「每次」，取决于 UP 到时窗口焦点有没有换完，所以按时间窗兜住。
     */
    private long dlgDismissedAt = 0;

    /** 用户自定义的源拉不到、也没有缓存：空态要说这件事，而不是让他再去「配一个源」 */
    private boolean srcFailed = false;
    private String srcFailReason = "";

    private int lastGroup = -1;
    private int lastChannel = -1;
    /** 从播放器回来时把焦点还给刚才点的那一格 */
    private int returnPos = -1;

    /** 一条频道的原始信息，sub 在过滤时才算（全部视图给分组名，分组视图给频道号） */
    private static class Raw {
        final int gi;
        final int ci;
        final String name;
        final String groupName;
        final int num;
        final int srcs;
        /** 第一路地址，4K 视图靠它认「两个名字不同、其实是同一条流」的台 */
        final String firstUrl;
        /** 源自带的台标地址（m3u 的 tvg-logo），没有就是空串 */
        final String logoUrl;
        final boolean locked;
        /** 台名里带 4K / 2160 / UHD，判断规则见 LiveChannelCardAdapter.is4k() */
        final boolean is4k;

        Raw(int gi, int ci, String name, String groupName, int num, List<String> urls, boolean locked,
            String logoUrl) {
            this.gi = gi;
            this.ci = ci;
            this.name = name;
            this.groupName = groupName;
            this.num = num;
            this.srcs = urls == null ? 0 : urls.size();
            this.firstUrl = (urls == null || urls.isEmpty() || urls.get(0) == null) ? "" : urls.get(0);
            this.logoUrl = logoUrl == null ? "" : logoUrl;
            this.locked = locked;
            this.is4k = LiveChannelCardAdapter.is4k(name);
        }
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_live_channel;
    }

    @Override
    protected void init() {
        initView();
        bindLastChannel();
        loadChannels();
        focusInitial(0);
    }

    private void initView() {
        // 独立直播 app：没有首页/分类/收藏/历史/搜索可去，整排导航隐藏，只留右侧时钟。
        // 页面自己的大标题 + 频道数已经说清了这是什么页，不必再放一个「直播」胶囊。
        V8TopBar.attachClockOnly(this);
        navCurrent = null;

        tvLiveStat = findViewById(R.id.tvLiveStat);
        tvEmptyTitle = findViewById(R.id.tvEmptyTitle);
        tvEmptyDesc = findViewById(R.id.tvEmptyDesc);
        btnEmptyAction = findViewById(R.id.btnEmptyAction);
        liveEmptyBox = findViewById(R.id.liveEmptyBox);
        favHintBox = findViewById(R.id.favHintBox);
        tvFavHintTitle = findViewById(R.id.tvFavHintTitle);
        tvFavHintDesc = findViewById(R.id.tvFavHintDesc);

        mChipRow = findViewById(R.id.mChipRow);
        mChipRow.setHasFixedSize(true);
        // 左栏竖排（设计稿）：12 个分组横着排要一路按到屏幕外才找得到「地方频道」，
        // 换成竖排一屏就能看全，也不用记「往右按几下」
        mChipRow.setLayoutManager(new V7LinearLayoutManager(mContext, LinearLayoutManager.VERTICAL, false));
        chipAdapter = new LiveRailAdapter();
        mChipRow.setAdapter(chipAdapter);
        // 列表滚到「地方频道」后再按 ↓ 要落到「设置」上：底部这两颗按钮不在
        // RecyclerView 里，默认的 focusSearch 会横着跳去右边频道墙，
        // 结果就是「设置」永远按不到（进了播放页才发现播了某个台）。
        mChipRow.setOnInBorderKeyEventListener(new TvRecyclerView.OnInBorderKeyEventListener() {
            @Override
            public boolean onInBorderKeyEvent(int direction, View focused) {
                if (direction != View.FOCUS_DOWN) return false;
                View btn = findViewById(R.id.btnSettings);
                return btn != null && btn.requestFocus();
            }
        });

        mChannelGrid = findViewById(R.id.mChannelGrid);
        mChannelGrid.setHasFixedSize(true);
        // 电视 16:9 一屏 6 列；竖屏/平板上少一列，卡片不会被挤成一条
        mChannelGrid.setLayoutManager(new V7GridLayoutManager(mContext, isBaseOnWidth() ? 5 : 6));
        cardAdapter = new LiveChannelCardAdapter();
        mChannelGrid.setAdapter(cardAdapter);
        cardAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                LiveChannelCardAdapter.Cell cell = cardAdapter.getItem(position);
                if (cell != null) openPlayer(cell, position);
            }
        });
        // 触屏长按（平板 / 手机截图时用的模拟操作），以及某些遥控器把长按直接
        // 变成 performLongClick 的情况。遥控器长按的按键路径在 dispatchKeyEvent 里，
        // 两条路都进 toggleFavAt，靠时间戳防重
        cardAdapter.setOnItemLongClickListener(new BaseQuickAdapter.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(BaseQuickAdapter adapter, View view, int position) {
                return toggleFavAt(position);
            }
        });
        // 焦点在频道墙最左一列时按 ← 回左栏：列表默认的 focusSearch 会一直往左找，
        // 越过左栏落到空白里，用户就得多按好几次才回得到分组
        mChannelGrid.setOnInBorderKeyEventListener(new TvRecyclerView.OnInBorderKeyEventListener() {
            @Override
            public boolean onInBorderKeyEvent(int direction, View focused) {
                if (direction == View.FOCUS_LEFT) return focusCurrentChip();
                return false;
            }
        });

        chipAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                LiveRailAdapter.Row opt = chipAdapter.getItem(position);
                if (opt == null || opt.label) return;
                // 四种视图：全部 / 收藏聚合 / 4K 聚合 / 某个真实分组。
                // 后两种聚合视图也是「从全部台里筛出来的」，所以 curGroup 同样给 -1，
                // 不然 renderCards 会再按分组条件筛一遍，筛完是空的
                onlyFav = FAV_VALUE.equals(opt.value);
                only4k = K4_VALUE.equals(opt.value);
                curGroup = (opt.value.isEmpty() || only4k || onlyFav) ? -1 : parseInt(opt.value, -1);
                // 不 notify，焦点留在原胶囊上；否则选中一下焦点就跳回第一个
                chipAdapter.setSelectedQuiet(mChipRow, opt.value);
                renderCards();
                // 收藏档靠长按操作，从胶囊进来时焦点应该直接落在频道墙上，
                // 不然用户点完「收藏」还得再按一下 ↓ 才够得到台
                if (onlyFav && cardAdapter.getItemCount() > 0) focusInitial(0);
            }
        });

        // 直播源配置入口。切换源要重启整个 app（见 LiveSourceActivity 里的说明），
        // 所以从这里过去之后不会再回到这一页 —— 不需要在 onResume 里比对源有没有变。
        View btnConfig = findViewById(R.id.btnConfig);
        if (btnConfig != null) {
            btnConfig.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openSourceConfig();
                }
            });
        }
        // 应用设置。进这里是「界面明暗」那类全局项，直播版之前压根没有入口 ——
        // SettingActivity 在清单里注册着，但全工程没有一处 startActivity 跳过来。
        View btnSettings = findViewById(R.id.btnSettings);
        if (btnSettings != null) {
            btnSettings.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(mContext, SettingActivity.class));
                    overridePendingTransition(0, 0);
                }
            });
        }

    }

    private void openSourceConfig() {
        Intent intent = new Intent(mContext, LiveSourceActivity.class);
        startActivity(intent);
        overridePendingTransition(0, 0);
    }

    // =====================================================================
    //  数据
    // =====================================================================

    /**
     * 读频道表。
     *
     * <p>用户自己配了直播源（{@code Hawk(LIVE_URL)} 非空）就以它为唯一数据来源 ——
     * 这条地址是用户在「配置」里明确选的，界面还继续显示内置的 215 个台，
     * 等于选择没生效。原版这一页只读 {@code ApiConfig} 里那份（由配置接口算出来），
     * 自定义源的处理全在播放页，所以这里必须自己拉一次。
     *
     * <p>没配源（或显式选回内置）时保持原逻辑：先用 ApiConfig 里已解析的，
     * 整份是空就拿 assets 里的内置表兜底 —— 内置源不依赖任何外网地址，
     * 至少让这一页有台可点，而不是一片空白让人以为直播坏了。
     */
    private void loadChannels() {
        final int generation = ++loadGeneration;
        srcFailed = false;
        String custom = LiveSourceStore.currentUrl();
        if (!TextUtils.isEmpty(custom)) {
            loadFromUrl(custom, generation);
            return;
        }

        List<LiveChannelGroup> gl = ApiConfig.get().getChannelGroupList();
        if ((gl == null || gl.isEmpty()) && ApiConfig.get().loadBuiltinLives()) {
            gl = ApiConfig.get().getChannelGroupList();
        }
        renderFromApi();
    }

    /**
     * 按用户选的地址拉一份频道表。
     *
     * <p>失败时按「缓存 → 空态」两级降级：缓存里是上次同一地址拉到的正文，
     * 断网重开还能看；连缓存都没有就老实说打不开，让用户重试或换源，
     * 而不是偷偷换成内置表 —— 那样用户会以为自己选的源有 215 个台。
     */
    private void loadFromUrl(final String url, final int generation) {
        // 先清干净，避免加载期间旧频道还挂在墙上被误点
        groups.clear();
        rawAll.clear();
        hasProxy = false;
        cardAdapter.setNewData(new ArrayList<LiveChannelCardAdapter.Cell>());
        tvLiveStat.setText(getString(R.string.v8_src_loading));
        liveEmptyBox.setVisibility(View.GONE);
        mChipRow.setVisibility(View.GONE);
        mChannelGrid.setVisibility(View.VISIBLE);

        OkGo.<String>get(url).tag(this).execute(new AbsCallback<String>() {

            @Override
            public String convertResponse(okhttp3.Response response) throws Throwable {
                return response.body().string();
            }

            @Override
            public void onSuccess(Response<String> response) {
                if (!isCurrentLoad(url, generation)) return;
                String txt = response.body();
                if (ApiConfig.get().loadLivesFromTxt(txt)) {
                    LiveSourceStore.saveCache(url, txt);
                    renderFromApi();
                    focusInitial(0);
                } else {
                    fallbackToCache(url, getString(R.string.v8_src_empty_content), generation);
                }
            }

            @Override
            public void onError(Response<String> response) {
                if (!isCurrentLoad(url, generation)) return;
                super.onError(response);
                Throwable t = response == null ? null : response.getException();
                String why = t == null ? "" : "（" + t.getClass().getSimpleName() + "）";
                fallbackToCache(url, getString(R.string.v8_src_unreachable, why), generation);
            }
        });
    }

    /** 拉取失败/内容空：有同地址缓存就用缓存，没有就进失败空态 */
    private void fallbackToCache(String url, String why, int generation) {
        if (!isCurrentLoad(url, generation)) return;
        String cached = LiveSourceStore.readCache(url);
        if (cached != null && ApiConfig.get().loadLivesFromTxt(cached)) {
            renderFromApi();
            focusInitial(0);
            Toast.makeText(mContext, R.string.v8_src_use_cache, Toast.LENGTH_LONG).show();
            return;
        }
        srcFailed = true;
        srcFailReason = why;
        renderFromApi();
    }

    /** 回调仍属于本页当前这一次、同一个源的请求才允许改全局频道表和 View */
    private boolean isCurrentLoad(String url, int generation) {
        return generation == loadGeneration && !isFinishing() && !isDestroyed()
                && TextUtils.equals(url, LiveSourceStore.currentUrl());
    }

    /** 把 ApiConfig 里那份列表摊到界面上（唯一入口，内置/自定义/缓存都走这） */
    private void renderFromApi() {
        List<LiveChannelGroup> gl = ApiConfig.get().getChannelGroupList();

        groups.clear();
        rawAll.clear();
        hasProxy = false;

        if (gl != null) {
            for (int gi = 0; gi < gl.size(); gi++) {
                LiveChannelGroup g = gl.get(gi);
                if (g == null) continue;
                String gname = g.getGroupName() == null ? "" : g.getGroupName().trim();
                if (isProxyGroup(gname)) {
                    // 用户自定义直播源留下的占位组：没有频道，也不能把这条长 URL 当分组名
                    hasProxy = true;
                    continue;
                }
                ArrayList<LiveChannelItem> chs = g.getLiveChannels();
                if (chs == null || chs.isEmpty()) continue;

                boolean locked = !TextUtils.isEmpty(g.getGroupPassword());
                groups.add(g);
                for (int ci = 0; ci < chs.size(); ci++) {
                    LiveChannelItem it = chs.get(ci);
                    if (it == null) continue;
                    rawAll.add(new Raw(gi, ci,
                            it.getChannelName() == null ? "" : it.getChannelName(),
                            gname,
                            it.getChannelNum(),
                            it.getChannelUrls(),
                            locked,
                            it.getLogoUrl()));
                }
            }
        }

        k4Count = 0;
        for (Raw r : rawAll) {
            if (r.is4k) k4Count++;
        }
        // 「★ 收藏」常驻一个胶囊，不管里面有没有东西 —— 它是这个功能的唯一入口，
        // 藏起来等于没人知道能收藏；「4K 超清」只在真有 4K 台时出现（空分类没有意义）。
        // 两个聚合档都算进「M 个分类」，否则用户数着胶囊、上面写着十个分组，会以为少显示了一组
        catCount = groups.size() + 1 + (k4Count > 0 ? 1 : 0);
        updateStat(rawAll.size());
        buildChips();

        if (rawAll.isEmpty()) {
            showLiveEmpty();
        } else {
            liveEmptyBox.setVisibility(View.GONE);
            mChipRow.setVisibility(View.VISIBLE);
            mChannelGrid.setVisibility(View.VISIBLE);
            renderCards();
            warmUpLogos();
        }
    }

    /**
     * 把整份频道表的台标在后台补齐。
     *
     * <p>首屏只拉得到看得到的二三十个，其余等滚到才拉 —— 台标一共不过几 MB，
     * 一次全备好之后滚动、切主题、断网都是秒开。只在第一份频道表铺出来时跑一次，
     * 已经有本地文件的不重复下载（{@link LogoStore#preload} 内部判重）。
     */
    private void warmUpLogos() {
        if (logoWarmed || rawAll.isEmpty()) return;
        logoWarmed = true;
        final List<String> names = new ArrayList<>();
        // 台名 → 源自带台标。预热走的是同一套 {@link ChannelLogo#candidates}，
        // 不把 tvg-logo 一起带下去的话，预热下的是「猜出来的那张」，
        // 前台要的是「源给的那张」，两张都得下一遍，白烧一倍流量
        final java.util.Map<String, String> logos = new java.util.HashMap<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Raw r : rawAll) {
            if (TextUtils.isEmpty(r.name) || !seen.add(r.name)) continue;
            names.add(r.name);
            if (!TextUtils.isEmpty(r.logoUrl)) logos.put(r.name, r.logoUrl);
        }
        final boolean light = UiTheme.isLight(mContext);
        new Thread(new Runnable() {
            @Override
            public void run() {
                LogoStore.preload(mContext, names, logos, light);
            }
        }, "logo-preload").start();
    }

    private void buildChips() {
        List<LiveRailAdapter.Row> rows = new ArrayList<>();
        rows.add(LiveRailAdapter.Row.entry(getString(R.string.v8_live_all), "",
                String.valueOf(rawAll.size()), R.drawable.ic_rail_grid, false));
        // 收藏排在第二：「追的那几个台」是打开直播后最先想看的东西，比 4K 还靠前。
        // 这一档一直在，哪怕里面是空的 —— 空的时候进去有提示，告诉用户长按就能收藏
        rows.add(LiveRailAdapter.Row.entry(getString(R.string.v8_live_fav), FAV_VALUE,
                String.valueOf(FavoriteStore.count()), R.drawable.ic_fav_star_outline, false));
        // 超清专区紧跟「收藏」：它是挑台时最先想筛的一档（大屏电视买来就是为了看超高清）
        if (k4Count > 0) {
            rows.add(LiveRailAdapter.Row.entry(getString(R.string.v8_live_group_4k), K4_VALUE,
                    String.valueOf(k4Count), 0, true));
        }
        // 上面三个是「视图」，下面是源里的真实分组 —— 设计稿里用一行小标题把它们分开
        rows.add(LiveRailAdapter.Row.label(getString(R.string.v8_live_rail_group)));
        for (LiveChannelGroup g : groups) {
            String name = g.getGroupName();
            if (TextUtils.isEmpty(name)) name = String.valueOf(g.getGroupIndex() + 1);
            int n = g.getLiveChannels() == null ? 0 : g.getLiveChannels().size();
            rows.add(LiveRailAdapter.Row.entry(name, String.valueOf(g.getGroupIndex()),
                    String.valueOf(n), 0, false));
        }
        curGroup = -1;
        only4k = false;
        onlyFav = false;
        chipAdapter.setSelected("");
        chipAdapter.setNewData(rows);
    }

    /**
     * 标题行那行统计。
     *
     * @param shown 当前视图里实际铺出来的格数 —— 收藏档要显示「对上几个 / 一共收藏几个」，
     *              只写「3 个频道」的话用户会以为收藏丢了（其实是对不上当前这个源）
     */
    private void updateStat(int shown) {
        if (onlyFav) {
            tvLiveStat.setText(getString(R.string.v8_fav_stat, shown, FavoriteStore.count()));
        } else {
            tvLiveStat.setText(getString(R.string.v8_live_stat, rawAll.size(), catCount));
        }
    }

    /** 按当前分组把频道墙铺出来 */
    private void renderCards() {
        List<LiveChannelCardAdapter.Cell> cells = new ArrayList<>();
        int selectedPos = -1;

        if (onlyFav) {
            // 收藏档按「收藏先后」排，不按源里的顺序：用户自己攒出来的顺序是唯一稳定的东西，
            // 按源顺序排的话换一次源、加一个台，整排位置全变，遥控器上的肌肉记忆就废了。
            // 先收藏的在前 —— 「最近收藏的置顶」听着好，实际是每收一个台全排位移一格。
            java.util.Set<String> used = new java.util.HashSet<>();
            for (FavoriteStore.Fav f : FavoriteStore.list()) {
                Raw r = findByFav(f);
                if (r == null) continue;
                // 台名和地址各救一半的匹配规则会带来一种情况：两条收藏（名字改过之前和之后）
                // 指向同一个台，格子里就会出现两格一模一样的，用坐标去一次重
                if (!used.add(r.gi + ":" + r.ci)) continue;
                LiveChannelCardAdapter.Cell cell = newCell(r);
                if (r.gi == lastGroup && r.ci == lastChannel) {
                    cell.selected = true;
                    selectedPos = cells.size();
                }
                cells.add(cell);
            }
        } else {
            // 只有 4K 视图去重。源里同一个超高清台常在几个分组各挂一条：
            // 「苏州4K」在卫视频道、地方频道各一条（名字一个 4K 一个 4k），
            // 「河北4K」和「Hebei TV」名字完全不同、地址却是同一条流。
            // 不筛的话精挑出来 8 个台里有 3 格是同一个，看着像源坏了。
            // 分组视图不筛：那里的重复是源的真实样子，用户点的是一个具体分组。
            java.util.Set<String> seenName = only4k ? new java.util.HashSet<String>() : null;
            java.util.Set<String> seenUrl = only4k ? new java.util.HashSet<String>() : null;

            for (Raw r : rawAll) {
                if (only4k) {
                    if (!r.is4k) continue;
                    String key = LiveChannelCardAdapter.cleanName(r.name).toLowerCase();
                    if (!seenName.add(key)) continue;
                    if (!r.firstUrl.isEmpty() && !seenUrl.add(normUrl(r.firstUrl))) continue;
                } else if (curGroup >= 0 && r.gi != curGroup) {
                    continue;
                }
                LiveChannelCardAdapter.Cell cell = newCell(r);
                if (r.gi == lastGroup && r.ci == lastChannel) {
                    cell.selected = true;
                    selectedPos = cells.size();
                }
                cells.add(cell);
            }
        }

        cardAdapter.setNewData(cells);
        hoverSelected(selectedPos);
        updateStat(cells.size());
        updateFavHint(cells.size());
    }

    private LiveChannelCardAdapter.Cell newCell(Raw r) {
        LiveChannelCardAdapter.Cell cell =
                new LiveChannelCardAdapter.Cell(r.gi, r.ci, r.name, tagOf(r), r.firstUrl, r.logoUrl);
        // 收藏状态铺表时一次算好，适配器只管按这个标记画金星还是空星
        cell.fav = FavoriteStore.isFav(r.name, r.firstUrl);
        return cell;
    }

    /**
     * 卡片右下角角标：画质 + 源数 + 加密，拿「 · 」串起来。
     *
     * <p>画质标记从台名挪到了这里：主标题因此短一截、不再被省略号吃掉，信息一点没少。
     * 分组名不再上卡片（右上角那枚胶囊去掉了），只在顶上筛选行里体现。
     */
    private String tagOf(Raw r) {
        StringBuilder sb = new StringBuilder();
        String quality = LiveChannelCardAdapter.qualityOf(r.name);
        if (!TextUtils.isEmpty(quality)) sb.append(quality);
        if (r.srcs > 1) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(getString(R.string.v8_live_sub_srcs, r.srcs));
        }
        if (r.locked) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(getString(R.string.v8_live_locked));
        }
        return sb.toString();
    }

    /** 收藏能不能在当前这份频道表里找到对应的台；找不到说明换了个对不上的源 */
    private Raw findByFav(FavoriteStore.Fav f) {
        for (Raw r : rawAll) {
            if (FavoriteStore.match(f, r.name, r.firstUrl)) return r;
        }
        return null;
    }

    /** 收藏档的空提示：区分「还没收过」和「收过的台这个源里没有」，这两种情况该做的事完全不同 */
    private void updateFavHint(int shown) {
        if (favHintBox == null) return;
        if (!onlyFav || shown > 0) {
            favHintBox.setVisibility(View.GONE);
            return;
        }
        int total = FavoriteStore.count();
        if (total == 0) {
            tvFavHintTitle.setText(R.string.v8_fav_empty_title);
            tvFavHintDesc.setText(R.string.v8_fav_empty_desc);
        } else {
            tvFavHintTitle.setText(R.string.v8_fav_missing_title);
            tvFavHintDesc.setText(getString(R.string.v8_fav_missing_desc, total));
        }
        favHintBox.setVisibility(View.VISIBLE);
    }

    /** 上次看的台如果在当前视图里，描边 + 自动滚过去 */
    private void hoverSelected(final int position) {
        if (position < 0) return;
        mChannelGrid.post(new Runnable() {
            @Override
            public void run() {
                if (position < cardAdapter.getItemCount()) {
                    mChannelGrid.scrollToPosition(position);
                }
            }
        });
    }

    /** 「上次看到哪个台」只用来描边和初始焦点，取不到就当没有，不影响进页面 */
    private void bindLastChannel() {
        try {
            List<LiveChannelGroup> gl = ApiConfig.get().getChannelGroupList();
            if (gl == null || gl.isEmpty()) return;
            kotlin.Pair<Integer, Integer> kp = JavaUtil.findLiveLastChannel(gl);
            if (kp == null) return;
            lastGroup = kp.getFirst() == null ? -1 : kp.getFirst();
            lastChannel = kp.getSecond() == null ? -1 : kp.getSecond();
        } catch (Throwable ignored) {
            // 取不到「上次看到哪」只影响高亮，不影响进页面
        }
    }

    // =====================================================================
    //  空态
    // =====================================================================

    private void showLiveEmpty() {
        mChannelGrid.setVisibility(View.GONE);
        mChipRow.setVisibility(View.GONE);
        liveEmptyBox.setVisibility(View.VISIBLE);
        // 整页都没频道时，收藏档的提示没有意义（连胶囊行都藏了），一起收掉
        if (favHintBox != null) favHintBox.setVisibility(View.GONE);

        if (hasProxy) {
            tvEmptyTitle.setText(R.string.v8_live_empty_proxy_title);
            tvEmptyDesc.setText(R.string.v8_live_empty_proxy_desc);
            btnEmptyAction.setText(R.string.v8_live_btn_player);
            btnEmptyAction.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openPlayer(null, -1);
                }
            });
        } else if (srcFailed) {
            // 用户自己配的源打不开：说清是哪条地址、什么错，给他「重试」和「换一条」两条路
            tvEmptyTitle.setText(R.string.v8_src_fail_title);
            tvEmptyDesc.setText(getString(R.string.v8_src_fail_desc,
                    LiveSourceStore.suggestName(LiveSourceStore.currentUrl()), srcFailReason));
            btnEmptyAction.setText(R.string.v8_live_btn_retry);
            btnEmptyAction.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    tvEmptyTitle.setText(R.string.v8_src_loading);
                    loadChannels();
                }
            });
        } else {
            tvEmptyTitle.setText(R.string.v8_live_empty_none_title);
            tvEmptyDesc.setText(R.string.v8_live_empty_none_desc);
            // 「重新读取」只能救内置表读失败的情况；没源的时候用户真正要做的是加一条，
            // 所以这里直接给配置入口 —— 进了那一页顺手也能切回内置源。
            btnEmptyAction.setText(R.string.v8_live_btn_config_src);
            btnEmptyAction.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openSourceConfig();
                }
            });
        }
        btnEmptyAction.post(new Runnable() {
            @Override
            public void run() {
                btnEmptyAction.requestFocus();
            }
        });
    }

    // =====================================================================
    //  收藏
    // =====================================================================

    /**
     * 遥控器上的收藏入口：长按 OK。
     *
     * <p>为什么在 Activity 这一层拦，而不是给卡片挂 OnLongClickListener：
     * 卡片能收到的按键是 TvRecyclerView 处理过一层之后的，它自己那套按键逻辑
     * （滚动、寻焦、点击）在长按期间会先插手；在 dispatchKeyEvent 拿到的是最原始的事件，
     * 还能把整段长按连发一次性吃掉，不会「收藏一次、顺手播了那个台」。
     *
     * <p>MENU 键：不再是「另一个收藏键」，而是放大当前这一格（{@link LiveChannelDetailDialog}）。
     * 一颗键一个语义：长按是速记式的快捷动作，MENU 是「把这一格摊开看清楚」。
     * 以前两个键都做收藏，用户按 MENU 以为会看到信息，结果只弹了一个「已收藏」提示。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int code = event.getKeyCode();
            boolean okKey = code == KeyEvent.KEYCODE_DPAD_CENTER
                    || code == KeyEvent.KEYCODE_ENTER
                    || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
            if (code == KeyEvent.KEYCODE_MENU) {
                // 按住不放会连发 ACTION_DOWN，全吃掉 —— 不然一秒能叠出十几个弹层，
                // 关的时候得按十几次 BACK
                if (event.getRepeatCount() == 0) openDetailOfFocus();
                return true;
            }
            // repeatCount > 0 表示「这个键已经按住了一会儿」，遥控器上没有别的长按语义，
            // 所以这就是长按
            if (okKey && event.getRepeatCount() > 0) {
                if (!longPressDone) {
                    if (toggleFavOfFocus()) {
                        longPressDone = true;
                        return true;
                    }
                } else {
                    // 按住不放的后续连发全吃掉，否则一秒能来回收藏取消十几次
                    return true;
                }
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            boolean wasLong = longPressDone;
            longPressDone = false;
            // 长按时 View 没收到 ACTION_DOWN，本来不会当点击；但 TvRecyclerView 的点击
            // 走的是它自己的 OnItemListener（吃 UP），所以这里把 UP 一并吞掉
            if (wasLong) return true;
            // 弹层刚吃掉同一个 BACK 的 DOWN：这段 UP 不能再当「退出页面」处理
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                    && System.currentTimeMillis() - dlgDismissedAt < 300) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * 收藏 / 取消收藏焦点所在的那个台。
     *
     * @return 焦点不在频道墙上（在胶囊、标题行按钮、空态按钮上）时返回 false，
     *         把按键让回给原来的处理逻辑，不要在这里静默吞键
     */
    private boolean toggleFavOfFocus() {
        if (mChannelGrid == null || cardAdapter == null || rawAll.isEmpty()) return false;
        View f = getCurrentFocus();
        if (f == null || !isInside(mChannelGrid)) return false;
        RecyclerView.ViewHolder vh = mChannelGrid.findContainingViewHolder(f);
        if (vh == null) return false;
        return toggleFavAt(vh.getAdapterPosition());
    }

    /**
     * 收藏 / 取消收藏第 {@code pos} 格。
     *
     * <p>两条通路都会走到这里：Activity 的按键层（遥控器长按 OK / MENU）和卡片的
     * OnItemLongClickListener（触屏长按，以及某些遥控器把长按直接变成 performLongClick）。
     * 同一次长按有可能两边都响，所以这里按时间戳去一次重 —— 不然一次长按收藏完立刻又取消，
     * 用户看到的是「闪了一下就没了」。
     */
    private boolean toggleFavAt(int pos) {
        if (cardAdapter == null || pos < 0 || pos >= cardAdapter.getItemCount()) return false;
        long now = System.currentTimeMillis();
        if (now - lastFavToggleAt < 350) return true;
        lastFavToggleAt = now;

        LiveChannelCardAdapter.Cell cell = cardAdapter.getItem(pos);
        if (cell == null) return false;
        Raw r = rawOf(cell);
        if (r == null) return false;

        applyFav(pos, cell, r, !FavoriteStore.isFav(r.name, r.firstUrl), false);
        return true;
    }

    /**
     * 把第 {@code pos} 格的收藏状态落到 {@code want}。
     *
     * <p>为什么是「设成某个状态」而不是「翻转」：弹层里的按钮文案是根据当前状态写死的
     * （没收藏时写「收藏本台」），按钮上写着什么就得做什么。翻转式接口在连点两下时
     * 会让文案和实际状态错位（第二下按的是「收藏本台」，实际执行了取消）。
     *
     * @param deferReflow true = 当前有弹层盖着，收藏档的重铺等弹层关掉再做
     * @return 操作后的实际状态（收藏夹满了想加也加不上，这时回的还是原状态）
     */
    private boolean applyFav(int pos, LiveChannelCardAdapter.Cell cell, Raw r,
                             boolean want, boolean deferReflow) {
        boolean cur = FavoriteStore.isFav(r.name, r.firstUrl);
        if (want == cur) return cur;
        if (want && FavoriteStore.isFull()) {
            Toast.makeText(mContext, getString(R.string.v8_fav_toast_full, FavoriteStore.MAX),
                    Toast.LENGTH_SHORT).show();
            return cur;
        }

        boolean now = FavoriteStore.toggle(r.name, r.firstUrl, r.groupName);
        if (now != want) {
            // 写盘失败：toggle 返回的是从持久层重新读出的真实状态，不弹假成功 Toast
            Toast.makeText(mContext, R.string.v8_fav_toast_save_failed, Toast.LENGTH_SHORT).show();
            syncFavMarks();
            updateFavHint(cardAdapter.getItemCount());
            return now;
        }
        Toast.makeText(mContext,
                now ? getString(R.string.v8_fav_toast_add, cell.name)
                        : getString(R.string.v8_fav_toast_del, cell.name),
                Toast.LENGTH_SHORT).show();

        // 收藏身份允许「同名或同地址」匹配，一次操作可能改变好几张卡；
        // 全部安静同步（含左栏计数），不能只换被按的那一颗星。
        syncFavMarks();

        if (onlyFav && !now) {
            // 在收藏档里取消收藏 = 这一格要从墙上消失。整表重铺后焦点必须自己接回来，
            // 否则焦点挂在一个已经被移除的 View 上，遥控器按什么都没反应
            if (deferReflow) {
                detailReflow = true;
            } else {
                renderCards();
                focusAfterRemoval(pos);
            }
        } else {
            updateFavHint(cardAdapter.getItemCount());
        }
        return now;
    }

    // =====================================================================
    //  详情弹层（MENU 放大那一格）
    // =====================================================================

    /**
     * 放大焦点所在的那一格。
     *
     * @return 焦点不在频道墙上时返回 false：MENU 在左栏/按钮上没有合理语义，
     *         交给系统（本页也没注册任何菜单，等于什么都不发生）比静默吞掉更容易排查
     */
    private boolean openDetailOfFocus() {
        if (mChannelGrid == null || cardAdapter == null || rawAll.isEmpty()) return false;
        View f = getCurrentFocus();
        if (f == null || !isInside(mChannelGrid)) return false;
        RecyclerView.ViewHolder vh = mChannelGrid.findContainingViewHolder(f);
        if (vh == null) return false;
        return openDetail(vh.getAdapterPosition());
    }

    private boolean openDetail(final int pos) {
        if (cardAdapter == null || pos < 0 || pos >= cardAdapter.getItemCount()) return false;
        if (detailDlg != null && detailDlg.isShowing()) return true;

        final LiveChannelCardAdapter.Cell cell = cardAdapter.getItem(pos);
        if (cell == null) return false;
        final Raw r = rawOf(cell);
        if (r == null) return false;

        detailDlg = new LiveChannelDetailDialog(mContext, cell.rawName, cell.name, cell.tag,
                cell.is4k, r.groupName, cell.firstUrl,
                FavoriteStore.isFav(r.name, r.firstUrl), cell.logoUrl);
        detailDlg.setOnAction(new LiveChannelDetailDialog.OnAction() {
            @Override
            public void onFavToggle() {
                // 按钮上写的是什么就做什么：状态从库里现读，不信弹层手里那份
                boolean want = !FavoriteStore.isFav(r.name, r.firstUrl);
                boolean now = applyFav(pos, cell, r, want, true);
                if (detailDlg != null) detailDlg.setFav(now);
            }

            @Override
            public void onPlay() {
                openPlayer(cell, pos);
            }
        });
        detailDlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface dialog) {
                detailDlg = null;
                // 记一笔：同一段 BACK 的 UP 还会落到这一页上（见 dlgDismissedAt）
                dlgDismissedAt = System.currentTimeMillis();
                if (detailReflow) {
                    // 收藏档里取消后又重收会让它移到末尾：按频道坐标找，不能按旧下标
                    detailReflow = false;
                    renderCards();
                    int current = indexOfCell(r.gi, r.ci);
                    if (current >= 0) focusCard(current);
                    else focusAfterRemoval(pos);
                    return;
                }
                focusCard(pos);
            }
        });
        detailDlg.show();
        return true;
    }

    /** 弹层关掉后把焦点还给原来那一格（那一格可能已经滚出屏幕，所以先滚过去） */
    private void focusCard(final int pos) {
        if (mChannelGrid == null || cardAdapter == null) return;
        if (pos < 0 || pos >= cardAdapter.getItemCount()) return;
        mChannelGrid.post(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) return;
                mChannelGrid.scrollToPosition(pos);
                RecyclerView.ViewHolder h = mChannelGrid.findViewHolderForAdapterPosition(pos);
                if (h != null) h.itemView.requestFocus();
                else mChannelGrid.requestFocus();
            }
        });
    }

    /**
     * 把墙上每一格左下角的星对齐到收藏库的当前状态。
     *
     * <p>只换头像（{@link LiveChannelCardAdapter#setFavQuiet}），不重铺：从播放页回来时
     * 焦点刚从播放器还到某一格上，这时候 notifyDataSetChanged 会把焦点甩掉。
     *
     * <p>屏幕上没显示的那几格也一并更新了 cell 上的标记，滚回来绑定时自然是对的。
     */
    private void syncFavMarks() {
        if (cardAdapter == null || mChannelGrid == null) return;
        for (int i = 0; i < cardAdapter.getItemCount(); i++) {
            LiveChannelCardAdapter.Cell cell = cardAdapter.getItem(i);
            if (cell == null) continue;
            Raw r = rawOf(cell);
            if (r == null) continue;
            boolean fav = FavoriteStore.isFav(r.name, r.firstUrl);
            if (cell.fav != fav) cardAdapter.setFavQuiet(mChannelGrid, i, fav);
        }
        if (mChipRow != null) {
            chipAdapter.setNumQuiet(mChipRow, FAV_VALUE, String.valueOf(FavoriteStore.count()));
        }
    }

    private Raw rawOf(LiveChannelCardAdapter.Cell c) {
        for (Raw r : rawAll) {
            if (r.gi == c.groupIndex && r.ci == c.channelIndex) return r;
        }
        return null;
    }

    /** 取消收藏后把焦点落到原位置（末尾删掉了就落到新的末尾），收藏清空了就回胶囊行 */
    private void focusAfterRemoval(final int pos) {
        mChannelGrid.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) return;
                if (cardAdapter.getItemCount() == 0) {
                    // 一格都不剩：焦点留在频道墙里等于失联，交回「★ 收藏」胶囊
                    focusCurrentChip();
                    return;
                }
                int target = Math.min(pos, cardAdapter.getItemCount() - 1);
                mChannelGrid.scrollToPosition(target);
                RecyclerView.ViewHolder h = mChannelGrid.findViewHolderForAdapterPosition(target);
                if (h != null) h.itemView.requestFocus();
                else mChannelGrid.requestFocus();
            }
        }, 60);
    }

    // =====================================================================
    //  跳转
    // =====================================================================

    /**
     * 进播放器。
     *
     * @param cell     null = 不带定位（自定义直播源的频道只有播放器自己能拉）
     * @param position 在频道墙里的下标，回来时把焦点还给它
     */
    private void openPlayer(LiveChannelCardAdapter.Cell cell, int position) {
        Intent intent = new Intent(mContext, LivePlayActivity.class);
        if (cell != null) {
            // 这两个 key 是 LivePlayActivity.initLiveState() 认的参数，不能改名
            intent.putExtra("groupIndex", cell.groupIndex);
            intent.putExtra("channelIndex", cell.channelIndex);
            lastGroup = cell.groupIndex;
            lastChannel = cell.channelIndex;
        }
        returnPos = position;
        startActivity(intent);
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        // 取消网络只是提速，isCurrentLoad() 仍会兜住已经进入队列的旧回调
        loadGeneration++;
        OkGo.getInstance().cancelTag(this);
        if (detailDlg != null) {
            // 销毁过程不再做焦点恢复或收藏档重铺
            detailDlg.setOnDismissListener(null);
            detailDlg.dismiss();
            detailDlg = null;
        }
        detailReflow = false;
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 空态从「配置」页按 BACK 回来时，焦点会丢：原来那个按钮在对面的页面里，
        // 本页的频道墙又是 GONE 的，遥控器按什么都没反应（实测按 OK 不重试）。
        // 空态一律把焦点送回唯一那个按钮。
        if (rawAll.isEmpty() && btnEmptyAction != null) {
            btnEmptyAction.post(new Runnable() {
                @Override
                public void run() {
                    if (!isFinishing() && !isDestroyed()) btnEmptyAction.requestFocus();
                }
            });
        }
        // 从别的页面（设置 / 播放器）回来时，胶囊的选中标记要和实际分组对齐。
        // 原来只在点击那一刻设 selected，回来以后 curGroup 还在、标记却没了，
        // 表现是「内容明明停在央视频道，胶囊行一个都不亮」—— 用户不知道自己在哪一档。
        if (chipAdapter != null && mChipRow != null) {
            String chipValue = onlyFav ? FAV_VALUE
                    : only4k ? K4_VALUE
                    : (curGroup < 0 ? "" : String.valueOf(curGroup));
            chipAdapter.setSelectedQuiet(mChipRow, chipValue);
        }
        // 收藏可能是在播放页里改的（播放页侧栏那颗星），回到列表得重算：
        // 收藏档要重铺（格数变了），别的视图只把变过的那几格星换掉 ——
        // 不整表 notify 是为了保住刚从播放器还回来的焦点
        if (cardAdapter != null) {
            if (onlyFav) {
                renderCards();
            } else {
                syncFavMarks();
            }
        }
        // 从播放器回来：收藏档可能已重排，按原始频道坐标找，不用旧 position
        if (returnPos < 0 || cardAdapter == null) return;
        final int oldPos = returnPos;
        returnPos = -1;
        if (cardAdapter.getItemCount() == 0) {
            focusCurrentChip();
            return;
        }
        int selectedPos = posOfLast();
        final int pos = selectedPos >= 0
                ? selectedPos : Math.min(oldPos, cardAdapter.getItemCount() - 1);
        // 原台已从收藏档消失时只恢复焦点，不把隔壁那格伪标成「上次播放」
        if (selectedPos >= 0) cardAdapter.setSelectedQuiet(mChannelGrid, selectedPos);
        mChannelGrid.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) return;
                if (pos < cardAdapter.getItemCount()) {
                    mChannelGrid.scrollToPosition(pos);
                    RecyclerView.ViewHolder holder = mChannelGrid.findViewHolderForAdapterPosition(pos);
                    if (holder != null) holder.itemView.requestFocus();
                }
            }
        }, 120);
    }

    // =====================================================================
    //  焦点
    // =====================================================================

    /**
     * 进页面、切分组、读完频道之后调：焦点落到「上次看的台」，没有就第一格。
     *
     * <p>必须能「失败重试」：{@code onCreate} 里发起的 post 跑到时窗口往往还没拿到焦点，
     * 这时 {@code requestFocus()} 会安静地返回 false（视图已经绑好、isFocusable 也是 true，
     * 只是窗口这一层没就绪）。以前只在 holder 为 null 时才重试，holder 拿到就 return，
     * 结果一进页面哪儿都没有焦点，遥控器按下去一点反应都没有。
     */
    private void focusInitial(final int attempt) {
        // 一格没铺出来时不要抢焦点：收藏档空着的时候整块墙是空的，
        // 硬去 requestFocus 会把焦点从「★ 收藏」胶囊上抢到一个空列表里，遥控器当场失联
        if (rawAll.isEmpty() || cardAdapter == null || cardAdapter.getItemCount() == 0) return;
        mChannelGrid.post(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) return;
                // 焦点已经在频道墙里就别抢（从播放页回来时用户停在哪张卡就是哪张）。
                // 但不能再放过「焦点在分组胶囊上」的情况 —— 独立 app 里顶栏没有了，
                // 窗口一开默认焦点就落在第一个胶囊「全部」上，那样进页面永远停在胶囊，
                // 方向键第一下还得先往下走一格才看得见选中效果。
                if (isInside(mChannelGrid)) return;

                int target = posOfLast();
                if (target < 0) target = 0;

                if (attempt >= MAX_FOCUS_TRY) {
                    // 实在点不上卡片，至少让整块频道墙拿着焦点，方向键还能用
                    mChannelGrid.requestFocus();
                    return;
                }

                RecyclerView.ViewHolder holder = mChannelGrid.findViewHolderForAdapterPosition(target);
                if (holder != null && holder.itemView.requestFocus()) return;

                mChannelGrid.scrollToPosition(target);
                mChannelGrid.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        focusInitial(attempt + 1);
                    }
                }, 120);
            }
        });
    }

    /** 当前焦点是否落在 {@code box} 这棵子树里 */
    private boolean isInside(android.view.View box) {
        android.view.View f = getCurrentFocus();
        if (f == null || box == null) return false;
        android.view.View p = f;
        while (p != null) {
            if (p == box) return true;
            android.view.ViewParent vp = p.getParent();
            p = vp instanceof android.view.View ? (android.view.View) vp : null;
        }
        return false;
    }

    /** 窗口拿到焦点时再兜一次：这是「现在真的可以要焦点了」的确定信号 */
    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus && detailDlg == null && !isInside(mChannelGrid) && !rawAll.isEmpty()) {
            // 详情弹层开关窗口时由 onDismiss 把焦点还给原格，别让初始焦点逻辑抢一次
            focusInitial(0);
        }
    }

    /** 上次看的台在当前视图里的下标，不在就 -1 */
    private int posOfLast() {
        return indexOfCell(lastGroup, lastChannel);
    }

    /** 用 ApiConfig 原始坐标找卡，收藏档重排后也不会认错台 */
    private int indexOfCell(int groupIndex, int channelIndex) {
        List<LiveChannelCardAdapter.Cell> data = cardAdapter.getData();
        for (int i = 0; i < data.size(); i++) {
            LiveChannelCardAdapter.Cell c = data.get(i);
            if (c.groupIndex == groupIndex && c.channelIndex == channelIndex) return i;
        }
        return -1;
    }

    /** 焦点回当前分组那个胶囊 */
    private boolean focusCurrentChip() {
        int idx = chipAdapter.selectedIndex();
        View chip = mChipRow.getLayoutManager() == null
                ? null : mChipRow.getLayoutManager().findViewByPosition(idx);
        if (chip != null && chip.requestFocus()) return true;
        mChipRow.scrollToPosition(idx);
        mChipRow.post(new Runnable() {
            @Override
            public void run() {
                int i = chipAdapter.selectedIndex();
                View v = mChipRow.getLayoutManager() == null
                        ? null : mChipRow.getLayoutManager().findViewByPosition(i);
                if (v != null) v.requestFocus();
                else mChipRow.requestFocus();
            }
        });
        return true;
    }

    // =====================================================================

    private static boolean isProxyGroup(String groupName) {
        return groupName != null && groupName.startsWith(PROXY_HOST) && groupName.contains("/proxy?do=live");
    }

    /**
     * 地址归一，只给「这两条是不是同一条流」做比较用。
     *
     * <p>规则放在 {@link FavoriteStore#normUrl} 里，因为收藏认台用的是同一套：
     * 两处各写一份的话，4K 视图去重和收藏匹配迟早会出现「界面上算同一个台、
     * 收藏却不认」的错位。
     */
    private static String normUrl(String u) {
        return FavoriteStore.normUrl(u);
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }
}
