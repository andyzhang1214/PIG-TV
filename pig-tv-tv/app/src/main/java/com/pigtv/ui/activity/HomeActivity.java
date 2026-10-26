package com.pigtv.ui.activity;

import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager.widget.ViewPager;

import com.pigtv.R;
import com.pigtv.api.ApiConfig;
import com.pigtv.base.App;
import com.pigtv.base.BaseActivity;
import com.pigtv.base.BaseLazyFragment;
import com.pigtv.bean.AbsSortXml;
import com.pigtv.bean.AbsXml;
import com.pigtv.bean.Movie;
import com.pigtv.bean.MovieSort;
import com.pigtv.bean.SourceBean;
import com.pigtv.bean.VodInfo;
import com.pigtv.cache.RoomDataManger;
import com.pigtv.event.RefreshEvent;
import com.pigtv.event.SortPageEvent;
import com.pigtv.server.ControlManager;
import com.pigtv.ui.adapter.HomeCatAdapter;
import com.pigtv.ui.adapter.HomeLineAdapter;
import com.pigtv.ui.adapter.HomePageAdapter;
import com.pigtv.ui.adapter.SelectDialogAdapter;
import com.pigtv.ui.dialog.SelectDialog;
import com.pigtv.ui.dialog.TipDialog;
import com.pigtv.ui.fragment.GridFragment;
import com.pigtv.ui.fragment.BlankFragment;
import com.pigtv.ui.tv.widget.DefaultTransformer;
import com.pigtv.ui.tv.widget.FixedSpeedScroller;
import com.pigtv.ui.tv.widget.NoScrollViewPager;
import com.pigtv.util.AppManager;
import com.pigtv.util.DefaultConfig;
import com.pigtv.util.HawkConfig;
import com.pigtv.util.ImgUtil;
import com.pigtv.util.LOG;
import com.pigtv.util.LivePreload;
import com.pigtv.util.V8TopBar;
import com.pigtv.util.VodText;
import com.pigtv.viewmodel.SourceViewModel;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import me.jessyan.autosize.utils.AutoSizeUtils;

/**
 * 首页 v8
 * ============================================================
 * 对应 ui-design/tv-home-v8.html。和 v6.1 的区别不是皮肤，是信息架构：
 *
 *   v6.1：一个预览卡 + 一排入口 → 用户看完这一屏就没了，
 *         想专门找「电影」里的某一类，没有任何入口。
 *   v8  ：4 行内容流（每行一个分类，可横滑，行尾「查看全部」）
 *         + 分类页（左分类列 + 筛选 + 海报墙），两条路径互相打通。
 *
 * 焦点模型（一根 D-pad 走完全程）：
 *
 *   顶栏导航 ──↓──→ hero 两个按钮 ──↓──→ 第 1 行
 *      ↑                                    │
 *      └────────────（行内按 ↑）─────────────┘
 *
 *   行与行之间：按 ↑↓ 由本类的 dispatchKeyEvent 统一接管。没有用嵌套
 *   RecyclerView 的默认 focusSearch——垂直 RV 套 4 个横向 RV 时，
 *   方向键经常跳到隔壁行的中间位置，那是 TV 端最经典的坑。
 *
 * 首屏 / 浏览态：
 *   焦点在顶栏 → hero 完整、内容流从 240mm 开始，只露 1.8 行（刻意的）
 *   焦点进内容行 → hero 上移淡出、内容流整体上推到 144mm，露出 2.3 行
 *   这就是「页面会推上来」的来源，Apple TV 的真实行为。
 */
public class HomeActivity extends BaseActivity {

    // ------------------------------------------------------------------
    //  常量
    // ------------------------------------------------------------------
    /** 首屏内容流起点（mm，物理毫米，与 dimens_v8 同一套换算） */
    private static final float MM_LINE_TOP_FIRST = 240f;
    /** 浏览态内容流起点（整体上推 96mm） */
    private static final float MM_LINE_TOP_BROWSE = 144f;
    /** 内容流被顶栏压住的上限 */
    private static final float MM_LINE_TOP_MIN = 66f;
    /** 只有一块内容时，内容底边离屏幕底边的距离（底部那行按键提示的高度） */
    private static final float MM_LINES_BOTTOM_PAD = 26f;
    /** 首页最多放几行 */
    private static final int MAX_LINES = 4;
    /** 首页推荐拉空时，最多再换几个分类试（每个都要走一次网络，别无限试） */
    private static final int HOME_REC_MAX_TRY = 4;
    /** 每行最多取几张，避免一次拉几百张图 */
    private static final int MAX_ITEMS_PER_LINE = 24;
    /**
     * 顶部观看历史海报墙的列数。
     * 5 列时列宽 ≈ (1218 - 2×45.6 - 4×12) / 5 ≈ 215.8mm，
     * 配 322mm 的卡高正好 2:3，海报不会被裁成方块；一行就把首屏填满了。
     * 墙固定只露 1 行（WALL_MAX = WALL_SPAN），改列数要同步 dimens 的 v8_wall_h。
     */
    private static final int WALL_SPAN = 5;
    /**
     * 墙最多放几部历史 —— 正好 1 行。
     * 曾经放 2 行（21 部），但 219mm 的卡一叠两行就吃掉首屏 500mm，
     * 下面的推荐内容被压得只剩一条缝，所以只留一行；更多记录走「观看历史」页。
     */
    private static final int WALL_MAX = WALL_SPAN;
    /**
     * 推荐行少于这么多张时，从第一个真实分类补一批。
     *
     * 不少源的首页推荐位只给一两部（运营位），直接铺出来就是「一部片 + 一整屏黑」，
     * 看着像没加载完。补到两屏（WALL_SPAN × 2）才有左右滑的必要。
     */
    private static final int REC_FILL_MIN = WALL_SPAN * 2;
    /**
     * 从 App 进首页算起，最多等这么久就给结论。
     *
     * 不能写成「摆好这一行之后 N 秒」：sort 结果推两次就会重建两次，每次重建都把计时重置；
     * 也不能把配置、jar、sort、分类列表各算各的，加起来就是一分钟白等。
     */
    private static final long HOME_LOAD_DEADLINE = 25000L;

    // ------------------------------------------------------------------
    //  顶栏项
    // ------------------------------------------------------------------
    /** 推荐：首页内容流（一行大卡） */
    private static final int NT_REC = 0;
    /** 内容分类：切 ViewPager 里的某一页（电影/电视剧/…） */
    private static final int NT_CAT = 1;
    /** 搜索：独立页面，焦点路过不跳，要按 OK */
    private static final int NT_SEARCH = 2;
    /** 历史：本地库，焦点停住就地切 */
    private static final int NT_HIST = 3;
    /** 直播：独立播放器，焦点路过只预热 */
    private static final int NT_LIVE = 4;
    /** 收藏：独立页面 */
    private static final int NT_FAV = 5;
    /** 更多：站点分类超过 6 个时出现，点开是完整的分类列表 */
    private static final int NT_MORE = 6;

    /**
     * 顶栏一项。
     *
     * 分类会从「1 个」变成「电影 / 电视剧 / 综艺 / 动漫 / 少儿 / 纪录片」若干个，
     * 数量取决于站点给了哪些分类，所以顶栏不能再写死 6 个下标 —— 这里用类型 + sort 下标描述。
     */
    private static class NavItem {
        final int type;
        /** 只对 NT_CAT 有意义：在 sorts 里的下标 */
        final int sortIndex;
        /**
         * 顶栏显示用的名字。归并到大类的分类用大类名（电影 / 电视剧 / …），
         * 没归类的留 null，到时候直接显示站点原名。
         */
        final String label;

        NavItem(int type) {
            this(type, -1, null);
        }

        NavItem(int type, int sortIndex) {
            this(type, sortIndex, null);
        }

        NavItem(int type, int sortIndex, String label) {
            this.type = type;
            this.sortIndex = sortIndex;
            this.label = label;
        }
    }

    /** 站点分类归并成大类用的别名表（顺序就是顶栏里的出现顺序） */
    private static final String[][] CAT_ALIAS = {
            {"电影", "电影片"},
            {"电视剧", "连续剧", "电视", "剧集"},
            {"综艺", "综艺片", "真人秀"},
            {"动漫", "动漫片", "动画", "番剧", "国漫"},
            {"少儿", "儿童", "亲子", "益智"},
            {"纪录片", "记录片", "纪实", "纪录"}
    };
    /** 顶栏最多放几个分类 tab，超出的收进「更多」 */
    private static final int MAX_CAT_TABS = 6;

    private static Resources res;

    // ------------------------------------------------------------------
    //  视图
    // ------------------------------------------------------------------
    private View launcher;
    private FrameLayout linesViewport;
    private LinearLayout linesBox;
    private ImageView bgArtA;
    private ImageView bgArtB;
    /** 当前正在显示的那张背景（A/B 交替做交叉淡入） */
    private ImageView bgFront;

    private LinearLayout heroInfo;
    private LinearLayout heroActions;
    private LinearLayout navBox;
    private LinearLayout chipBox;
    /** 分类页容器（v10 起是 FrameLayout，里面只有 ViewPager） */
    private View contentLayout;

    private TextView tvHeroTag;
    private TextView tvHeroTitle;
    private TextView tvHeroMeta;
    private TextView tvHeroDesc;
    private TextView tvTime;
    private TextView tvDate;
    private TextView tvPillSource;
    private TextView tvFavLabel;
    private TextView hintBar;
    /** 底部渐隐遮罩：只在「内容比屏幕多、被硬切」时才需要，首页只有历史一行时隐藏 */
    private View bottomFade;

    private View pillSource;
    private View pillConfig;
    private View btnPlay;
    private View btnFav;

    private NoScrollViewPager mViewPager;
    /** 站点没有真实分类时盖住分类页的提示层 */
    private View catEmpty;

    /** 观看历史（v10）：一面 5 列大卡墙 + 空态 */
    private View histLayout;
    private TvRecyclerView histGrid;
    private HomeLineAdapter histAdapter;
    private View histEmpty;

    // ------------------------------------------------------------------
    //  数据
    // ------------------------------------------------------------------
    private SourceViewModel sourceViewModel;
    private HomePageAdapter pageAdapter;
    private final List<BaseLazyFragment> fragments = new ArrayList<>();
    private final List<MovieSort.SortData> sorts = new ArrayList<>();
    private final List<Line> lines = new ArrayList<>();
    private final List<View> navViews = new ArrayList<>();
    /** 顶栏的项（顺序 = 屏幕上的顺序），分类 tab 的数量随站点变 */
    private final List<NavItem> navItems = new ArrayList<>();
    /** 归并出来的分类 tab */
    private final List<NavItem> catTabs = new ArrayList<>();
    /** 站点还有分类没进顶栏 → 末尾那一项是「更多」 */
    private boolean hasMoreCats = false;
    /** 当前分类页对应的 sort id，用来过滤「别的分类回来的第一页」 */
    private String curCatSortId = "";
    /** 每个分类的第一部（hero 用），切回来不用等网络再拉一次 */
    private final java.util.Map<String, Movie.Video> catHeroCache = new java.util.HashMap<>();
    /** 是否在历史页（和 catMode 互斥） */
    private boolean histMode = false;
    private final List<Movie.Video> histVideos = new ArrayList<>();
    /** 历史卡的观看进度（0~1），key 同 videoKey() */
    private final java.util.Map<String, Float> histProgress = new java.util.HashMap<>();
    /** 站点首页推荐（配置里的 absXml.videoList），首页第二块「推荐内容」用它铺，不用再请求 */
    private final List<Movie.Video> homeRecommend = new ArrayList<>();
    /** 上一次构建首页时的播放记录指纹，用来判断从播放页回来要不要重建 */
    private String lastHistorySig = "";

    /** 当前 hero 展示的影片，用于判重（同一张卡反复获得焦点时别重复淡入） */
    private Movie.Video currentHero;
    /** 已经确认这个源没有内容：重建首页时别再闪回「正在读取」 */
    private boolean heroNone = false;
    /** 首页第一次开始拉内容的时刻（0 = 还没开始），给「多久给结论」算绝对截止时间 */
    private long homeLoadStartAt = 0L;
    private int navIndex = 0;
    /** 从二级页面顶栏点「分类」跳进来时，等 sort 加载完再切过去 */
    private boolean pendingOpenCat;
    private int catIndex = 0;

    private boolean catMode = false;
    private boolean browseMode = false;
    private boolean launcherReady = false;

    /**
     * 顶栏焦点「停留」后才真正切换下方内容。
     * <p>
     * 用 110ms 的短延时而不是立即切：遥控器按住 → 连发 6 次「推荐→分类→直播→收藏→历史→搜索」，
     * 立即切会让分类页在 200 毫秒里闪 3 次（还各自触发一次 switchCat 请求），观感是乱抖。
     * 只是「划过」的那几项会被取消，真正停下来的那一项照样是即时响应——用户感知不到这 110ms。
     */
    private Runnable pendingNavSwitch;
    private static final int NAV_SWITCH_DELAY = 110;

    private final Handler mHandler = new Handler();
    private long mExitTime = 0;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_home;
    }

    @Override
    protected void init() {
        res = getResources();
        // 首页「多久给结论」从这里算起：配置、jar、sort、分类列表全都算在同一个 30 秒里，
        // 否则每一段各自计时，加起来就是一分钟的白等
        homeLoadStartAt = System.currentTimeMillis();
        EventBus.getDefault().register(this);
        ControlManager.get().startServer();
        App.startWebserver();
        initView();
        initViewModel();
        useCacheConfig = false;
        Intent intent = getIntent();
        if (intent != null && intent.getExtras() != null) {
            useCacheConfig = intent.getExtras().getBoolean("useCache", false);
        }
        // 从收藏/历史/搜索页的顶栏点「分类」跳过来：数据就绪后直接落分类页
        pendingOpenCat = intent != null && intent.getBooleanExtra(V8TopBar.EXTRA_OPEN_CAT, false);
        initData();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        boolean openCat = intent != null && intent.getBooleanExtra(V8TopBar.EXTRA_OPEN_CAT, false);
        if (openCat) {
            pendingOpenCat = true;
            openCatIfReady();
        } else {
            // 点「首页」从二级页面回来：退回首屏，焦点交给顶栏
            pendingOpenCat = false;
            if (browseMode) exitBrowse();
            if (catMode || histMode) showRecPage();
            focusNavType(NT_REC);
        }
    }

    /** 顶栏「分类」进来的延后动作：分类还没加载完就等加载完再执行 */
    private void openCatIfReady() {
        if (!pendingOpenCat || sorts.isEmpty()) return;
        pendingOpenCat = false;
        showCatPage(isRealSort(catIndex) ? catIndex : firstRealSortIndex());
    }

    public static Resources getRes() {
        return res;
    }

    // =====================================================================
    //  初始化
    // =====================================================================

    private void initView() {
        launcher = findViewById(R.id.launcher);
        linesViewport = findViewById(R.id.linesViewport);
        linesBox = findViewById(R.id.linesBox);

        bgArtA = findViewById(R.id.bgArtA);
        bgArtB = findViewById(R.id.bgArtB);
        bgFront = bgArtA;

        heroInfo = findViewById(R.id.heroInfo);
        heroActions = findViewById(R.id.heroActions);
        navBox = findViewById(R.id.navBox);
        chipBox = findViewById(R.id.chipBox);
        contentLayout = findViewById(R.id.contentLayout);

        tvHeroTag = findViewById(R.id.tvHeroTag);
        tvHeroTitle = findViewById(R.id.tvHeroTitle);
        tvHeroMeta = findViewById(R.id.tvHeroMeta);
        tvHeroDesc = findViewById(R.id.tvHeroDesc);
        tvTime = findViewById(R.id.tvTime);
        tvDate = findViewById(R.id.tvDate);
        tvPillSource = findViewById(R.id.tvPillSource);
        tvFavLabel = findViewById(R.id.tvFavLabel);
        hintBar = findViewById(R.id.hintBar);
        bottomFade = findViewById(R.id.bottomFade);

        pillSource = findViewById(R.id.pillSource);
        pillConfig = findViewById(R.id.pillConfig);
        btnPlay = findViewById(R.id.btnPlay);
        btnFav = findViewById(R.id.btnFav);
        // 两个 hero 按钮之间左右互跳；不显式指定的话系统 focusSearch 会挑到
        // 右上方的顶栏导航（几何上最近），从「立即播放」按右直接飞到「分类」上。
        btnPlay.setNextFocusRightId(R.id.btnFav);
        btnFav.setNextFocusLeftId(R.id.btnPlay);

        mViewPager = findViewById(R.id.mViewPager);
        catEmpty = findViewById(R.id.catEmpty);
        histLayout = findViewById(R.id.histLayout);
        histGrid = findViewById(R.id.histGrid);
        histEmpty = findViewById(R.id.histEmpty);

        buildNav();
        initPills();
        initHeroActions();
        initHistoryWall();

        setLoadSir(this.launcher);

        // 首页只有一行内容，底部那条渐隐永远是噪音（用户专门问过「下面这条黑的是什么」）
        bottomFade.setVisibility(View.GONE);
        // XML 里那句「↑↓ 切换行」是给多行时代的，现在只有一行，先换成单行提示
        hintBar.setText(R.string.v8_hint_rec);

        updateClock();
        mHandler.postDelayed(mClockRunnable, 1000);
        linesViewport.post(new Runnable() {
            @Override
            public void run() {
                launcherReady = true;
                layoutLines(false);
            }
        });
    }

    private final Runnable mClockRunnable = new Runnable() {
        @Override
        public void run() {
            updateClock();
            mHandler.postDelayed(this, 1000);
        }
    };

    private void updateClock() {
        Calendar c = Calendar.getInstance();
        int hour = c.get(Calendar.HOUR_OF_DAY);
        int minute = c.get(Calendar.MINUTE);
        int hour12 = hour % 12;
        if (hour12 == 0) hour12 = 12;
        tvTime.setText((hour12 < 10 ? " " : "") + hour12 + ":" + (minute < 10 ? "0" + minute : String.valueOf(minute)));
        String[] weeks = {"日", "一", "二", "三", "四", "五", "六"};
        tvDate.setText((c.get(Calendar.MONTH) + 1) + "月" + c.get(Calendar.DAY_OF_MONTH) + "日 周"
                + weeks[c.get(Calendar.DAY_OF_WEEK) - 1]);
    }

    // ---------------- 顶栏导航 ----------------

    private void buildNav() {
        navItems.clear();
        navItems.add(new NavItem(NT_REC));
        navItems.add(new NavItem(NT_SEARCH));
        navItems.add(new NavItem(NT_HIST));
        navItems.addAll(catTabs);
        if (hasMoreCats) navItems.add(new NavItem(NT_MORE));
        navItems.add(new NavItem(NT_LIVE));
        navItems.add(new NavItem(NT_FAV));
        inflateNav();
    }

    /** 顶栏分三组：常用入口 ‖ 内容分类 ‖ 工具，组间加一条 1mm 竖线 */
    private int groupOf(NavItem it) {
        if (it.type == NT_CAT || it.type == NT_MORE) return 1;
        if (it.type == NT_LIVE || it.type == NT_FAV) return 2;
        return 0;
    }

    private CharSequence labelOf(NavItem it) {
        switch (it.type) {
            case NT_REC:
                return getString(R.string.home_app_home);
            case NT_SEARCH:
                return getString(R.string.home_app_search);
            case NT_HIST:
                return getString(R.string.v8_nav_hist);
            case NT_LIVE:
                return getString(R.string.home_app_live);
            case NT_FAV:
                return getString(R.string.home_app_fav);
            case NT_MORE:
                return getString(R.string.v8_nav_more);
            case NT_CAT:
                // 归并过的分类直接用大类名：站点叫「玩偶电影」「玩偶剧集」，
                // 顶栏上就该是「电影」「电视剧」，别让站点的前缀占满一排标签
                if (it.label != null) return it.label;
                MovieSort.SortData s = sortAt(it.sortIndex);
                return s == null || TextUtils.isEmpty(s.name) ? "" : s.name;
        }
        return "";
    }

    /**
     * 按 navItems 重新铺一遍顶栏。
     *
     * 分类 tab 的数量由站点决定，所以「分类回来了」这件事会让顶栏项数变一次 ——
     * 重建时必须把焦点留在原来那一项上，否则用户停在「搜索」上等数据，数据一到焦点就飞回「推荐」。
     */
    private void inflateNav() {
        boolean hadFocus = navBox.hasFocus() || getCurrentFocus() == null;
        navBox.removeAllViews();
        navViews.clear();

        LayoutInflater inflater = LayoutInflater.from(this);
        int lastGroup = -1;
        for (int i = 0; i < navItems.size(); i++) {
            final int index = i;
            NavItem it = navItems.get(i);
            int group = groupOf(it);
            if (lastGroup >= 0 && group != lastGroup) {
                View sep = new View(this);
                sep.setBackgroundColor(0x24FFFFFF);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(mm(0.8f), mm(17f));
                slp.setMarginStart(mm(5f));
                slp.setMarginEnd(mm(5f));
                slp.gravity = android.view.Gravity.CENTER_VERTICAL;
                sep.setLayoutParams(slp);
                navBox.addView(sep);
            }
            lastGroup = group;

            View item = inflater.inflate(R.layout.item_nav, navBox, false);
            item.setId(View.generateViewId());
            ((TextView) item.findViewById(R.id.tvNav)).setText(labelOf(it));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
            if (i > 0) lp.setMarginStart(mm(1.2f));
            item.setLayoutParams(lp);

            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onNavClick(index);
                }
            });
            // 焦点在顶栏 = 首屏态：「焦点走到哪，下面的内容就换到哪」，不用再按 OK 确认
            item.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        navIndex = index;
                        scheduleNavSwitch(index);
                    } else {
                        cancelNavSwitch();
                    }
                }
            });
            navBox.addView(item);
            navViews.add(item);
        }

        if (navViews.isEmpty()) return;

        // 最后一格往右应该落到「当前片源」，而不是直接跳到齿轮
        navViews.get(navViews.size() - 1).setNextFocusRightId(R.id.pillSource);
        pillSource.setNextFocusRightId(R.id.pillConfig);
        pillConfig.setNextFocusRightId(R.id.pillSource);
        pillSource.setNextFocusLeftId(navViews.get(navViews.size() - 1).getId());

        if (navIndex < 0 || navIndex >= navItems.size()) navIndex = 0;
        syncNavSelected();
        if (hadFocus) navViews.get(navIndex).requestFocus();
    }

    /** 顶栏高亮：当前在哪一页 */
    private void syncNavSelected() {
        boolean catInBar = false;
        if (catMode) {
            for (NavItem it : catTabs) {
                if (it.sortIndex == catIndex) {
                    catInBar = true;
                    break;
                }
            }
        }
        for (int i = 0; i < navViews.size() && i < navItems.size(); i++) {
            NavItem it = navItems.get(i);
            boolean sel = false;
            if (it.type == NT_REC) sel = !catMode && !histMode;
            else if (it.type == NT_CAT) sel = catMode && it.sortIndex == catIndex;
            else if (it.type == NT_MORE) sel = catMode && !catInBar;
            else if (it.type == NT_HIST) sel = histMode;
            navViews.get(i).setSelected(sel);
        }
    }

    private int indexOfNavType(int type) {
        for (int i = 0; i < navItems.size(); i++) {
            if (navItems.get(i).type == type) return i;
        }
        return -1;
    }

    /** 把焦点送回顶栏对应的那一项 */
    private void focusNavType(int type) {
        int i = indexOfNavType(type);
        if (i >= 0 && i < navViews.size()) navViews.get(i).requestFocus();
    }

    /** 回到当前页面对应的顶栏项（分类页要按当前分类找，不能按类型一锅端） */
    private void focusNavOfCurrent() {
        if (catMode) {
            for (int i = 0; i < navItems.size(); i++) {
                NavItem it = navItems.get(i);
                if (it.type == NT_CAT && it.sortIndex == catIndex && i < navViews.size()) {
                    navViews.get(i).requestFocus();
                    return;
                }
            }
            // 当前分类是从「更多」里选的，顶栏上没有它那一格
            focusNavType(NT_MORE);
            return;
        }
        focusNavType(histMode ? NT_HIST : NT_REC);
    }

    /**
     * 焦点落到顶栏某一项：安排一次「下方内容跟着换」。
     * <p>
     * 推荐 / 历史 / 各分类都是同一个页面里的几层，能就地切；
     * 搜索 / 直播 / 收藏是独立页面，焦点路过时不去跳转（会像抽风），
     * 直播顺手把频道表预热好，按 OK 进去的那一下是秒开的。
     */
    private void scheduleNavSwitch(final int index) {
        cancelNavSwitch();
        if (index < 0 || index >= navItems.size()) return;
        final NavItem it = navItems.get(index);
        if (it.type == NT_LIVE) {
            LivePreload.warmUp(this);
            return;
        }
        if (it.type == NT_SEARCH || it.type == NT_FAV || it.type == NT_MORE) return;
        pendingNavSwitch = new Runnable() {
            @Override
            public void run() {
                pendingNavSwitch = null;
                switch (it.type) {
                    case NT_REC:
                        if (catMode || histMode || browseMode) showRecPage();
                        break;
                    case NT_HIST:
                        showHistoryPage(false);
                        break;
                    case NT_CAT:
                        showCatPage(it.sortIndex, false);
                        break;
                }
            }
        };
        mHandler.postDelayed(pendingNavSwitch, NAV_SWITCH_DELAY);
    }

    private void cancelNavSwitch() {
        if (pendingNavSwitch != null) {
            mHandler.removeCallbacks(pendingNavSwitch);
            pendingNavSwitch = null;
        }
    }

    private void fadeIn(View v) {
        if (v == null) return;
        v.setAlpha(0f);
        v.animate().alpha(1f).setDuration(180L)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private void onNavClick(int index) {
        if (index < 0 || index >= navItems.size()) return;
        NavItem it = navItems.get(index);
        switch (it.type) {
            case NT_REC:
                showRecPage();
                focusNavType(NT_REC);
                break;
            case NT_CAT:
                showCatPage(it.sortIndex, true);
                break;
            case NT_MORE:
                showMoreCats();
                break;
            case NT_HIST:
                showHistoryPage(true);
                break;
            case NT_LIVE:
                jumpActivity(LivePlayActivity.class);
                break;
            case NT_FAV:
                jumpActivity(CollectActivity.class);
                break;
            case NT_SEARCH:
                jumpActivity(SearchActivity.class);
                break;
        }
    }

    // ---------------- 右上：换源 / 配置 / 时钟 ----------------

    private void initPills() {
        pillSource.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSiteSwitch();
            }
        });
        pillSource.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                reloadHome();
                return true;
            }
        });
        pillConfig.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                jumpActivity(SettingActivity.class);
            }
        });
    }

    // ---------------- hero 按钮 ----------------

    private void initHeroActions() {
        btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Movie.Video hero = firstVideoOfLines();
                if (hero != null) openDetail(hero);
            }
        });
        btnFav.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleHeroCollect();
            }
        });
    }

    // ---------------- 观看历史墙 ----------------

    /**
     * 历史页：和分类页同一个骨架 —— 上面 hero（上次看到的那部），下面 5 列大卡墙。
     * 卡片复用推荐页那一版（item_home_wall），只是多一条进度和一行「看到 32:18」。
     */
    private void initHistoryWall() {
        histAdapter = new HomeLineAdapter(new HomeLineAdapter.Listener() {
            @Override
            public void onPosterClick(Movie.Video video, int position) {
                openDetail(video);
            }

            @Override
            public void onPosterFocus(Movie.Video video, int position) {
                if (!browseMode) enterBrowse();
                setHero(video, true, getString(R.string.v8_hist_tag));
            }

            @Override
            public void onMoreClick() {
            }
        });
        histAdapter.setGridMode(true);
        histAdapter.setHistoryMode(true);
        histGrid.setLayoutManager(new V7GridLayoutManager(this, WALL_SPAN));
        histGrid.setSpacingWithMargins(mm(6f), mm(6.5f));
        histGrid.setAdapter(histAdapter);
        histGrid.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                if (itemView != null) itemView.setAlpha(1f);
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
            }
        });
    }

    /** 切分类：ViewPager 跟内容一起走，不带动画（TV 上翻页动画只是噪音） */
    private void switchCat(int position, boolean takeFocus) {
        if (position < 0 || position >= sorts.size()) return;
        if (pageAdapter != null) mViewPager.setCurrentItem(position, false);
        MovieSort.SortData sort = sortAt(position);
        curCatSortId = (sort == null || sort.id == null) ? "" : sort.id;
        rebuildChips(sort, position);
        // hero 换成这个分类最新的一部：缓存里有就直接上，没有就先写「正在读取」，
        // 等 GridFragment 把第一页抛上来（SortPageEvent）再确认一次
        Movie.Video cached = catHeroCache.get(curCatSortId);
        if (cached != null) {
            setHero(cached, true, getString(R.string.v8_cat_tag, sort == null ? "" : sort.name));
        } else {
            paintHeroConnecting(getString(R.string.v8_cat_tag, sort == null ? "" : sort.name));
        }
        syncNavSelected();
        if (takeFocus) focusPosterWall();
    }

    private void rebuildChips(final MovieSort.SortData sort, final int position) {
        chipBox.removeAllViews();
        if (sort == null || sort.filters == null || sort.filters.isEmpty()) return;

        LayoutInflater inflater = LayoutInflater.from(this);
        int max = Math.min(3, sort.filters.size());
        for (int i = 0; i < max; i++) {
            final MovieSort.SortFilter filter = sort.filters.get(i);
            String value = sort.filterSelect == null ? null : sort.filterSelect.get(filter.key);
            // 站点给的条件名本身可能就是「全部」，再拼一次就成「全部 · 全部」了
            String text = (TextUtils.isEmpty(value) || filter.name.equals(value))
                    ? filter.name
                    : filter.name + " · " + value;
            View chip = inflater.inflate(R.layout.item_home_chip, chipBox, false);
            ((TextView) chip).setText(text);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, mm(19f));
            if (i > 0) lp.setMarginStart(mm(5f));
            chip.setLayoutParams(lp);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    BaseLazyFragment f = fragmentAt(position);
                    if (f instanceof GridFragment) ((GridFragment) f).showFilter();
                }
            });
            chipBox.addView(chip);
        }
        // 多出来的条件收进「更多」，统一走原筛选弹层
        if (sort.filters.size() > max) {
            View chip = inflater.inflate(R.layout.item_home_chip, chipBox, false);
            ((TextView) chip).setText(getString(R.string.v8_filter) + " +" + (sort.filters.size() - max));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, mm(19f));
            lp.setMarginStart(mm(5f));
            chip.setLayoutParams(lp);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    BaseLazyFragment f = fragmentAt(position);
                    if (f instanceof GridFragment) ((GridFragment) f).showFilter();
                }
            });
            chipBox.addView(chip);
        }
    }

    private void focusPosterWall() {
        if (mViewPager == null) return;
        View page = mViewPager.getChildAt(mViewPager.getCurrentItem());
        if (page instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) page;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View child = vg.getChildAt(i);
                if (child instanceof RecyclerView) {
                    final RecyclerView rv = (RecyclerView) child;
                    rv.post(new Runnable() {
                        @Override
                        public void run() {
                            View first = rv.getLayoutManager() == null ? null
                                    : rv.getLayoutManager().findViewByPosition(0);
                            if (first != null) first.requestFocus();
                            else rv.requestFocus();
                        }
                    });
                    return;
                }
            }
        }
    }

    // =====================================================================
    //  内容行
    // =====================================================================

    /** 一行内容。sort == null 表示「观看历史」（来自本地播放记录） */
    private static class Line {
        int index;
        String title;
        String sub;
        MovieSort.SortData sort;
        List<Movie.Video> videos = new ArrayList<>();
        View rowView;
        TvRecyclerView rv;
        HomeLineAdapter adapter;
        int lastFocus = 0;
        boolean loading = false;
        boolean loaded = false;
        /** 1 = 横滑行；>1 = 网格（历史海报墙），值就是列数 */
        int span = 1;
        /** 是不是首页「推荐」那一块（决定用大卡布局 + 空了要自动换下一个分类） */
        boolean wall = false;
        /** 首页推荐行：拉空时自动换 homeRecPool 里的下一个分类 */
        boolean homeRec = false;
        /** 推荐行正在从别的分类补数据（防重复请求） */
        boolean filling = false;

        boolean isGrid() {
            return span > 1;
        }
    }

    /**
     * 首页内容：只有「推荐」一块（导航第一项也叫「推荐」）。
     *
     * 数据优先级：
     *   1. 站点首页推荐 absXml.videoList（通常进 App 时已经在内存里，秒出）
     *   2. 没有就退到「首页推荐」分类（my0），现拉一页
     *   3. 再没有就用第一个能用的分类
     *
     * 卡片是横滑的大卡（item_home_wall，215×322mm），一屏 5 张、左右能翻完整个列表。
     */
    private void buildLines() {
        lines.clear();
        linesBox.removeAllViews();
        if (homeLoadStartAt == 0L) homeLoadStartAt = System.currentTimeMillis();

        // ---- 1. 站点首页推荐 ----
        List<Movie.Video> rec = homeRecommend;
        if (rec.size() > MAX_ITEMS_PER_LINE) {
            rec = new ArrayList<>(rec.subList(0, MAX_ITEMS_PER_LINE));
        }
        if (!rec.isEmpty()) {
            Line l = newLine(getString(R.string.v8_rec_title),
                    getString(R.string.v8_count_unit, rec.size()), null);
            l.wall = true;
            l.span = 1;
            l.videos = rec;
            l.loaded = true;
            bindLineView(l);
            l.adapter.setData(rec, false, null);
            adjustWallHeight(l);
            layoutLines(false);
            // 用户可能已经切到分类/历史页了，别把他们的 hero 抢回推荐那部
            if (onRecPage()) setHero(rec.get(0), false);
            finishSingleLine();
            // 站点只放了一两部：先秒出这一两部，再从分类里补满，别让首屏就一张卡
            if (rec.size() < REC_FILL_MIN) fillRecLine(l);
            return;
        }

        // ---- 2/3. 站点没给推荐：从分类里现拉一页 ----
        // 排一个候选队列：首页推荐(my0) 优先，后面接各分类。
        // 前一个分类拉空（不少源首页就是不返内容）就自动试下一个，
        // 否则首页会停在「这个分类暂时没有内容」，等于什么都不给看。
        homeRecPool.clear();
        for (MovieSort.SortData s : sorts) {
            if (s != null && "my0".equals(s.id)) homeRecPool.add(s);
        }
        for (MovieSort.SortData s : sorts) {
            if (s != null && !"my0".equals(s.id) && !TextUtils.isEmpty(s.id)) homeRecPool.add(s);
        }
        homeRecTry = 1;

        if (!homeRecPool.isEmpty()) {
            MovieSort.SortData sort = homeRecPool.get(0);
            Line l = newLine(getString(R.string.v8_rec_title),
                    getString(R.string.v8_rec_from, sort.name), sort);
            // 和上面的推荐行用同一套大卡，视觉别因为数据来源不同而变
            l.wall = true;
            l.span = 1;
            l.homeRec = true;
            bindLineView(l);
            adjustWallHeight(l);
            layoutLines(false);
            if (!heroNone) paintHeroIdle();
            finishSingleLine();
            loadLine(l);
            // 降级链要一个一个分类试过去，源站慢的时候能拖上半分钟。
            // 用「从第一次开始拉」算的绝对截止时间，重建多少次都还是同一个点给结论。
            final Line fl = l;
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    long left = HOME_LOAD_DEADLINE
                            - (System.currentTimeMillis() - homeLoadStartAt);
                    if (left > 0) {
                        mHandler.postDelayed(this, left);
                        return;
                    }
                    if (!lines.contains(fl)) return;
                    if (fl.videos != null && !fl.videos.isEmpty()) return;
                    applyLineSub(fl, "");
                    paintHeroNone();
                    hintBar.setText(R.string.v8_hint_home_none);
                }
            });
            return;
        }

        // 连分类都没有（还没配置片源）
        if (!heroNone) paintHeroIdle();
        finishSingleLine();
    }

    /** 首页只有一块内容时的收尾：底部不需要渐隐，提示语也不写「↑↓ 切换行」 */
    private void finishSingleLine() {
        // 只有推荐页才该动这两样：分类/历史页有自己的提示语和渐隐
        if (catMode || histMode) return;
        if (bottomFade != null) bottomFade.setVisibility(View.GONE);
        hintBar.setText(R.string.v8_hint_rec);
    }

    /** 换下一行的副标题（推荐行自动换源时会变） */
    private void applyLineSub(Line line, String sub) {
        line.sub = sub;
        if (line.rowView == null) return;
        TextView tv = line.rowView.findViewById(R.id.tvLineSub);
        if (tv == null) return;
        if (TextUtils.isEmpty(sub)) {
            tv.setVisibility(View.GONE);
        } else {
            tv.setText(sub);
            tv.setVisibility(View.VISIBLE);
        }
    }

    /** 推荐的兜底候选：首页推荐(my0) 在前，其余分类在后；空了就往下一个试 */
    private final List<MovieSort.SortData> homeRecPool = new ArrayList<>();
    /** 下次要试 homeRecPool 里的第几个 */
    private int homeRecTry = 1;

    /**
     * 墙高度按 XML 的 v8_wall_h 走（1 行卡高 + 上下留白），不再随条数变。
     * 之前按行数动态撑高，2 行时首屏被压掉一半，现在固定 1 行就没有这个问题。
     */
    private void adjustWallHeight(Line l) {
        if (l == null || l.rv == null) return;
        int h = getResources().getDimensionPixelSize(R.dimen.v8_wall_h);
        ViewGroup.LayoutParams lp = l.rv.getLayoutParams();
        if (lp != null && lp.height != h) {
            lp.height = h;
            l.rv.setLayoutParams(lp);
        }
    }

    private Line newLine(String title, String sub, MovieSort.SortData sort) {
        Line l = new Line();
        l.index = lines.size();
        l.title = title;
        l.sub = sub;
        l.sort = sort;
        lines.add(l);
        return l;
    }

    private void bindLineView(final Line line) {
        View row = LayoutInflater.from(this).inflate(
                line.wall ? R.layout.item_home_line_wall : R.layout.item_home_line,
                linesBox, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = mm(line.wall ? 20f : 15f);
        row.setLayoutParams(lp);

        ((TextView) row.findViewById(R.id.tvLineTitle)).setText(line.title);
        TextView sub = row.findViewById(R.id.tvLineSub);
        if (TextUtils.isEmpty(line.sub)) {
            sub.setVisibility(View.GONE);
        } else {
            sub.setText(line.sub);
        }

        TvRecyclerView rv = row.findViewById(R.id.rvLine);
        if (line.wall) {
            // 大卡行：横向滑动，卡宽写死在 item_home_wall 里（不是网格，不靠均分）
            rv.setLayoutManager(new V7LinearLayoutManager(this, 1, false));
            rv.setSpacingWithMargins(mm(12f), 0);
        } else {
            rv.setLayoutManager(new V7LinearLayoutManager(this, 0, false));
            rv.setSpacingWithMargins(mm(11f), 0);
        }
        rv.setHasFixedSize(false);

        line.adapter = new HomeLineAdapter(new HomeLineAdapter.Listener() {
            @Override
            public void onPosterClick(Movie.Video video, int position) {
                openDetail(video);
            }

            @Override
            public void onPosterFocus(Movie.Video video, int position) {
                line.lastFocus = position;
                if (!onRecPage()) return;
                if (!browseMode) enterBrowse();
                setHero(video, true);
            }

            @Override
            public void onMoreClick() {
                int idx = sorts.indexOf(line.sort);
                if (idx >= 0) showCatPage(idx);
            }
        });
        line.adapter.setGridMode(line.wall);
        rv.setAdapter(line.adapter);
        rv.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                if (itemView != null) itemView.setAlpha(1f);
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                // 同行其它卡压到 66%：亮色海报上光加白环是看不清的，降亮度才有效。
                // 但没拿到焦点的行也会收到一次 selected 回调（TvRecyclerView 默认选中第 0 项），
                // 不判断就会把整行压暗 —— 用户看到的是「这一行没加载出来」。
                if (itemView != null && !parent.hasFocus()) {
                    itemView.setAlpha(1f);
                    return;
                }
                // 海报墙不压暗：墙里有十几张卡，整墙变灰会像「没加载完」，
                // 焦点靠白环 + 放大 7% 已经足够突出。
                if (line.wall) return;
                dimRow(parent, itemView);
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
            }
        });
        rv.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                // 焦点离开这一行，把压暗的兄弟卡全部还原
                if (!hasFocus) dimRow(rv, null);
            }
        });

        line.rowView = row;
        line.rv = rv;
        linesBox.addView(row);
    }

    private void loadLine(final Line line) {
        if (line.sort == null || line.loading || line.loaded) return;
        line.loading = true;
        sourceViewModel.getList(line.sort, 1, new SourceViewModel.ListCallback() {
            @Override
            public void done(final AbsXml absXml) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        line.loading = false;
                        line.loaded = true;
                        List<Movie.Video> list = (absXml != null && absXml.movie != null)
                                ? absXml.movie.videoList : null;
                        if (list == null || list.isEmpty()) {
                            // 首页推荐行：这个分类空了就换下一个，别让首页停在「没有内容」
                            if (line.homeRec && homeRecTry < homeRecPool.size()
                                    && homeRecTry <= HOME_REC_MAX_TRY) {
                                MovieSort.SortData next = homeRecPool.get(homeRecTry++);
                                line.sort = next;
                                line.loaded = false;
                                line.loading = false;
                                applyLineSub(line, getString(R.string.v8_rec_from, next.name));
                                loadLine(line);
                                return;
                            }
                            line.adapter.setData(new ArrayList<Movie.Video>(), false, null);
                            // 首页那一行：hero 已经把话说完了（这个片源没有内容），
                            // 行里再挂一句「这个分类暂时没有内容」只是在同一屏重复两遍
                            if (line.index == 0 && currentHero == null) {
                                applyLineSub(line, "");
                                paintHeroNone();
                            } else {
                                showLineState(line, getString(R.string.v8_line_empty));
                            }
                            return;
                        }
                        if (list.size() > MAX_ITEMS_PER_LINE) {
                            list = new ArrayList<>(list.subList(0, MAX_ITEMS_PER_LINE));
                        }
                        line.videos = list;
                        // 大卡行是横滑的，末尾不塞「查看全部」小卡（尺寸对不上）
                        line.adapter.setData(list, !line.wall,
                                getString(R.string.v8_view_all_count, String.valueOf(list.size())));
                        adjustWallHeight(line);
                        layoutLines(false);
                        // 第一行回来时把 hero 换成真实内容（只在推荐页才动 hero）
                        if (line.index == 0 && currentHero == null && onRecPage()) {
                            setHero(list.get(0), true);
                        }
                    }
                });
            }
        });
    }

    /**
     * 推荐行内容太少时，从第一个真实分类拉一页补在后面。
     *
     * 只追加、不替换：站点给的推荐位排在最前面，本来就是运营想推的，
     * 补进来的只是把空出来的位置填满，不让首屏孤零零一张卡。
     */
    private void fillRecLine(final Line line) {
        if (line == null || line.filling) return;
        final MovieSort.SortData sort = firstFillableSort();
        if (sort == null) return;
        line.filling = true;
        sourceViewModel.getList(sort, 1, new SourceViewModel.ListCallback() {
            @Override
            public void done(final AbsXml absXml) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        line.filling = false;
                        // 期间可能换了源 / 重建了首页，这时 line 已经不在列表里
                        if (!lines.contains(line) || line.adapter == null) return;
                        List<Movie.Video> extra = (absXml != null && absXml.movie != null)
                                ? absXml.movie.videoList : null;
                        if (extra == null || extra.isEmpty()) return;

                        List<Movie.Video> merged = new ArrayList<>(line.videos);
                        Set<String> seen = new HashSet<>();
                        for (Movie.Video v : merged) seen.add(videoKey(v));
                        for (Movie.Video v : extra) {
                            if (v == null) continue;
                            String k = videoKey(v);
                            if (TextUtils.isEmpty(v.id) || seen.contains(k)) continue;
                            seen.add(k);
                            merged.add(v);
                            if (merged.size() >= MAX_ITEMS_PER_LINE) break;
                        }
                        if (merged.size() <= line.videos.size()) return;

                        line.videos = merged;
                        line.adapter.setData(merged, false, null);
                        adjustWallHeight(line);
                        layoutLines(false);
                        applyLineSub(line, getString(R.string.v8_count_unit, merged.size()));
                    }
                });
            }
        });
    }

    private String videoKey(Movie.Video v) {
        if (v == null) return "";
        return (v.id == null ? "" : v.id) + "@"
                + (v.sourceKey == null ? "" : v.sourceKey);
    }

    /** 补推荐用哪个分类：先挑真实分类，没有再退回 my0（它的分类内容往往比首页推荐位给得多） */
    private MovieSort.SortData firstFillableSort() {
        for (MovieSort.SortData s : sorts) {
            if (s != null && !TextUtils.isEmpty(s.id) && !"my0".equals(s.id)) return s;
        }
        for (MovieSort.SortData s : sorts) {
            if (s != null && !TextUtils.isEmpty(s.id)) return s;
        }
        return null;
    }

    /** 某行没内容时，用一张占位卡告诉用户「不是坏了，是这个分类空着」 */
    private void showLineState(Line line, String text) {
        if (line.rowView == null) return;
        TextView tv = line.rowView.findViewWithTag("state");
        if (tv == null) {
            tv = new TextView(this);
            tv.setTag("state");
            tv.setTextColor(0x66FFFFFF);
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_MM, 9.5f);
            tv.setPadding(mm(45.6f), mm(4f), 0, 0);
            ((ViewGroup) line.rowView).addView(tv);
        }
        tv.setText(text);
    }

    private String sortSub(MovieSort.SortData sort) {
        return null;
    }

    /** 播放记录指纹：id + 进度，任一项变了就说明用户又看过东西 */
    private String historySignature(List<Movie.Video> list) {
        StringBuilder sb = new StringBuilder();
        for (Movie.Video v : list) {
            if (v == null) continue;
            sb.append(v.id).append('|').append(TextUtils.isEmpty(v.note) ? "" : v.note).append(',');
        }
        return sb.toString();
    }

    /**
     * 首页现在是「推荐」，不再展示播放记录，所以回来时不需要重建内容。
     * 只把指纹更新一下，避免下次还拿旧值去比。
     */
    private void refreshWallIfChanged() {
        lastHistorySig = historySignature(loadHistory(WALL_MAX));
    }

    /** 本地播放记录 → 历史墙的数据 */
    private List<Movie.Video> loadHistory(int max) {
        List<Movie.Video> out = new ArrayList<>();
        if (max <= 0) return out;
        try {
            List<VodInfo> all = RoomDataManger.getAllVodRecord(max);
            for (VodInfo info : all) {
                if (info == null || TextUtils.isEmpty(info.id)) continue;
                Movie.Video v = new Movie.Video();
                v.id = info.id;
                v.name = info.name;
                v.pic = info.pic;
                v.sourceKey = info.sourceKey;
                v.year = info.year;
                v.type = info.type;
                v.area = info.area;
                // 角标放「第 12 集」这种短标记，卡下那行由 adapter 拼成「看到 第 12 集」
                v.note = TextUtils.isEmpty(info.playNote) ? info.note : info.playNote;
                out.add(v);
                // 顺手算个进度：集数进度 / 总集数。算不出来就不画那条进度，别编一个假的
                float prog = progressOf(info);
                if (prog >= 0) histProgress.put(videoKey(v), prog);
                if (out.size() >= max) break;
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 观看进度（0~1）；没有分集信息就返回 -1，卡上不画进度条 */
    private float progressOf(VodInfo info) {
        if (info == null || info.seriesMap == null || info.seriesMap.isEmpty()) return -1f;
        int total = 0;
        for (java.util.Map.Entry<String, ?> e : info.seriesMap.entrySet()) {
            Object v = e.getValue();
            if (v instanceof List) total += ((List<?>) v).size();
        }
        if (total <= 1) return -1f;
        float p = (info.playIndex + 1f) / total;
        if (p < 0f) p = 0f;
        if (p > 1f) p = 1f;
        return p;
    }

    // =====================================================================
    //  内容流滚动 / 首屏 ↔ 浏览态
    // =====================================================================

    private void enterBrowse() {
        if (browseMode) return;
        browseMode = true;
        heroInfo.animate().alpha(0f).translationY(mm(-36f)).setDuration(260).start();
        heroActions.animate().alpha(0f).translationY(mm(-36f)).setDuration(260).start();
        heroActions.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (browseMode) heroActions.setVisibility(View.INVISIBLE);
            }
        }, 260);
        // 分类页的筛选 chip 和 hero 是一层的，hero 走了它也得走
        chipBox.animate().alpha(0f).setDuration(200).start();
        // 进了内容行就不需要按键提示了，留着只会压在卡片片名上
        hintBar.animate().alpha(0f).setDuration(200).start();
        if (catMode || histMode) layoutCat(true);
        else layoutLines(true);
    }

    private void exitBrowse() {
        if (!browseMode) return;
        browseMode = false;
        // 没有影片时这两个按钮是无对象的，露出来只会让人点了没反应
        heroActions.setVisibility(currentHero == null ? View.GONE : View.VISIBLE);
        heroInfo.animate().alpha(1f).translationY(0).setDuration(300).start();
        heroActions.animate().alpha(1f).translationY(0).setDuration(300).start();
        chipBox.animate().alpha(1f).setDuration(220).start();
        hintBar.animate().alpha(1f).setDuration(220).start();
        if (catMode || histMode) layoutCat(true);
        else layoutLines(true);
    }

    /**
     * 把内容流摆到该在的位置。
     *
     * 首屏：固定 240mm，只露 1.8 行（露半行是刻意的，它在说「下面还有」）。
     * 浏览：整体上推到 144mm；如果当前焦点行比这个位置更靠下，
     *       再继续往上顶，保证整行（含片名）完整可见。
     *
     * 行高用「相邻两行 top 之差」而不是 rowView.getHeight()：
     * wrap_content 的容器在动画期间 getHeight 可能是旧值或者 0，
     * 拿它算滚动位置会算飞（第一版就是这么把第一行顶到顶栏下面的）。
     */
    private void layoutLines(boolean animate) {
        layoutLinesFor(null, animate);
    }

    private void layoutLinesFor(Line ref, boolean animate) {
        if (!launcherReady) return;
        int vpH = linesViewport.getHeight();
        if (vpH <= 0) return;

        // 全屏只有「观看历史」一块时：贴着屏幕底部放。
        // 固定 240mm 的定位是给「上面 hero、下面还有好几行」用的，
        // 只剩一行还按它摆，底下就空出小半个屏幕，看着像没加载完。
        if (lines.size() == 1 && lines.get(0).rowView != null) {
            final View row = lines.get(0).rowView;
            // 还没量过高度（刚 bind 完那一下）→ 等一帧再算，否则会按 0 摆到底部
            if (row.getHeight() <= 0) {
                row.post(new Runnable() {
                    @Override
                    public void run() {
                        layoutLinesFor(ref, animate);
                    }
                });
                return;
            }
            int contentH = rowHeightOf(lines.get(0));
            int px = vpH - contentH - mm(MM_LINES_BOTTOM_PAD);
            // 兜底：内容再高也别把标题顶到顶栏里去
            if (px < mm(MM_LINE_TOP_MIN)) px = mm(MM_LINE_TOP_MIN);
            applyLinesOffset(px, animate);
            return;
        }

        // 多行时才需要「浏览态上推 96mm」给下面的行让位
        float target = browseMode ? MM_LINE_TOP_BROWSE : MM_LINE_TOP_FIRST;

        if (browseMode) {
            Line cur = (ref != null) ? ref : focusedLine();
            if (cur != null && cur.rowView != null) {
                int rowTop = cur.rowView.getTop();
                // 不能统一用 rowStride：顶部历史墙比普通行高得多，用错会把墙顶出屏幕
                int rowH = rowHeightOf(cur);
                int bottomLimit = vpH - mm(12f);

                // 行底刚好吃进屏幕需要的位移（负 = 往上顶）
                float byBottom = (bottomLimit - rowTop - rowH) / (float) mm(1f);
                // 行顶刚好压在顶栏下沿允许的位移（再往上就钻到顶栏底下了）
                float byTop = MM_LINE_TOP_MIN - rowTop / (float) mm(1f);

                if (byBottom < target) {
                    // 行底超出屏幕 → 往上顶，但最多顶到「行顶贴住顶栏」为止。
                    // 少了这个 max()，历史墙这种「比屏幕还高」的行会算出几百毫米的负值，
                    // 整块内容被推到屏幕上方，看起来像首页全空。
                    target = Math.max(byBottom, byTop);
                }
                LOG.i("echo-v8-layout vpH=" + vpH + " rowH=" + rowH
                        + " rowTop=" + rowTop + " target=" + target);
            }
        }

        applyLinesOffset(mm(target), animate);
    }

    private void applyLinesOffset(int px, boolean animate) {
        if (animate) {
            linesBox.animate().translationY(px).setDuration(280).start();
        } else {
            linesBox.animate().cancel();
            linesBox.setTranslationY(px);
        }
    }

    /** 把一行里除 focused 外的卡片压到 66%（focused 传 null = 全部还原） */
    private void dimRow(ViewGroup row, View focused) {
        if (row == null) return;
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            c.setAlpha(c == focused ? 1f : (focused == null ? 1f : 0.66f));
        }
    }

    /** 一行的纵向步进（行高 + 行间距），取相邻两行的 top 差 */
    private int rowStride() {
        if (lines.size() >= 2) {
            View a = lines.get(0).rowView;
            View b = lines.get(1).rowView;
            if (a != null && b != null && b.getTop() > a.getTop()) {
                return b.getTop() - a.getTop();
            }
        }
        return mm(256f);
    }

    /**
     * 某一行的实际高度（含行间距）。顶部历史墙是 492mm 的网格，普通行只有 240mm，
     * 统一按 rowStride 算会把墙当成普通行，导致墙底被屏幕切掉。
     */
    private int rowHeightOf(Line l) {
        if (l == null || l.rowView == null) return rowStride();
        int h = l.rowView.getHeight();
        if (h <= 0) return rowStride();
        ViewGroup.LayoutParams lp = l.rowView.getLayoutParams();
        int bm = (lp instanceof ViewGroup.MarginLayoutParams)
                ? ((ViewGroup.MarginLayoutParams) lp).bottomMargin : 0;
        return h + bm;
    }

    private Line focusedLine() {
        for (Line l : lines) {
            if (l.rv != null && isInside(l.rv)) return l;
        }
        return null;
    }

    private boolean isInside(View target) {
        View f = getCurrentFocus();
        while (f != null) {
            if (f == target) return true;
            ViewParent p = f.getParent();
            f = (p instanceof View) ? (View) p : null;
        }
        return false;
    }

    /** 把焦点送到某行的某一列 */
    private void focusRow(int rowIndex, int col) {
        if (lines.isEmpty()) return;
        final Line line = lines.get(Math.max(0, Math.min(rowIndex, lines.size() - 1)));
        final int pos = Math.max(0, Math.min(col, Math.max(0, line.adapter.cellCount() - 1)));
        line.lastFocus = pos;
        if (!browseMode) enterBrowse();
        layoutLinesFor(line, true);
        line.rv.post(new Runnable() {
            @Override
            public void run() {
                View v = line.rv.getLayoutManager() == null ? null
                        : line.rv.getLayoutManager().findViewByPosition(pos);
                LOG.i("echo-v8-focus row=" + line.index + " pos=" + pos
                        + " v=" + (v == null ? "null" : "ok"));
                if (v != null) {
                    v.requestFocus();
                } else {
                    line.rv.setSelection(pos);
                    line.rv.requestFocus();
                }
            }
        });
        // 网格墙里 TvRecyclerView 拿到焦点后会按自己的选中逻辑再挑一次卡，
        // 第一下经常落到第二行第一张。隔一帧纠正回来。
        line.rv.postDelayed(new Runnable() {
            @Override
            public void run() {
                View v = line.rv.getLayoutManager() == null ? null
                        : line.rv.getLayoutManager().findViewByPosition(pos);
                if (v != null && !v.hasFocus()) {
                    v.requestFocus();
                    line.rv.setSelection(pos);
                }
            }
        }, 120);
    }

    private void scrollToRow(Line line) {
        layoutLinesFor(line, true);
    }

    // =====================================================================
    //  hero（背景 + 文案）
    // =====================================================================

    private void setHero(Movie.Video v, boolean animate) {
        setHero(v, animate, null);
    }

    private void setHero(Movie.Video v, boolean animate, String tag) {
        if (v == null) return;
        // 同一部片不重复淡入，但标签要跟着页面走（分类页写「最新电影」，历史页写「上次看到」）
        if (tag != null) tvHeroTag.setText(tag);
        if (v == currentHero) return;
        currentHero = v;
        heroNone = false;
        heroActions.setVisibility(View.VISIBLE);
        heroActions.setAlpha(1f);

        tvHeroTitle.setText(TextUtils.isEmpty(v.name) ? "" : v.name);
        if (tag == null) tvHeroTag.setText(getString(R.string.home_hero_tag_hot));

        // note 有两种：豆瓣的「7.8」这种短评分，和站点给的「更新至12集」这种短备注。
        // 评分进 meta 行，备注进 meta 行的最后一段；真正的长简介才占 desc 那块。
        String note = VodText.showableNote(v.note);
        boolean noteIsShort = note != null && note.length() <= 8;

        StringBuilder meta = new StringBuilder();
        if (v.year > 0) meta.append(v.year);
        appendMeta(meta, v.area);
        appendMeta(meta, v.type);
        if (noteIsShort) appendMeta(meta, "★ " + note);
        tvHeroMeta.setText(meta.toString());

        if (note != null && !noteIsShort) {
            tvHeroDesc.setText(note);
        } else {
            tvHeroDesc.setText(getString(R.string.v8_hero_desc_default));
        }

        if (!TextUtils.isEmpty(v.pic)) {
            crossFadeBg(v.pic.trim(), animate);
        }
        updateHeroFavState(v);
    }

    private void appendMeta(StringBuilder sb, String s) {
        if (TextUtils.isEmpty(s)) return;
        if (sb.length() > 0) sb.append("  ·  ");
        sb.append(s);
    }

    /** 背景剧照的最终亮度。不满 1.0 是有意的：数据里只有竖版海报，
     *  centerCrop 放大后画面中间常压着一行大字，压到 66% 才不至于跟片名咬在一起。 */
    private static final float BG_ALPHA = 0.66f;

    /** 两层 ImageView 交替，实现真正的交叉淡入（单层只能闪一下） */
    private void crossFadeBg(String pic, boolean animate) {
        final ImageView incoming = (bgFront == bgArtA) ? bgArtB : bgArtA;
        final ImageView outgoing = bgFront;
        bgFront = incoming;

        incoming.setAlpha(0f);
        ImgUtil.load(pic, incoming, 0);

        if (!animate) {
            incoming.setAlpha(BG_ALPHA);
            outgoing.setAlpha(0f);
            return;
        }
        incoming.postDelayed(new Runnable() {
            @Override
            public void run() {
                incoming.animate().alpha(BG_ALPHA).setDuration(520).start();
                outgoing.animate().alpha(0f).setDuration(520).start();
            }
        }, 60);
    }

    private void paintHeroIdle() {
        tvHeroTag.setText(getString(R.string.home_hero_tag_hot));
        tvHeroTitle.setText(getString(R.string.home_app_home));
        tvHeroMeta.setText("");
        tvHeroDesc.setText(getString(R.string.home_hero_loading));
    }

    /**
     * 切到某个分类、但第一页还没回来。
     * 这里必须先把 currentHero 清掉，否则上一部片的片名会一直挂在那儿，
     * 用户会以为「电影」和「电视剧」推的是同一部。
     */
    private void paintHeroConnecting(String tag) {
        currentHero = null;
        heroNone = false;
        tvHeroTag.setText(tag == null ? "" : tag);
        tvHeroTitle.setText("");
        tvHeroMeta.setText("");
        tvHeroDesc.setText(getString(R.string.v8_cat_connecting));
        heroActions.setVisibility(View.GONE);
    }

    /** hero 主按钮文案：推荐/分类是「立即播放」，历史是「继续播放」 */
    private void setPlayLabel(boolean resume) {
        TextView tv = findViewById(R.id.tvPlayLabel);
        if (tv != null) {
            tv.setText(resume ? getString(R.string.v8_hist_continue) : getString(R.string.v8_play_now));
        }
    }

    /** 彻底拉不到内容：写清楚是「源没给」，不是「还在转」 */
    private void paintHeroNone() {
        heroNone = true;
        tvHeroTag.setText(getString(R.string.v8_hero_none_tag));
        tvHeroTitle.setText(getString(R.string.v8_hero_none_title));
        tvHeroMeta.setText("");
        tvHeroDesc.setText(getString(R.string.v8_hero_none_desc));
        heroActions.setVisibility(View.GONE);
    }

    private void updateHeroFavState(Movie.Video v) {
        boolean collected = false;
        try {
            collected = RoomDataManger.isVodCollect(sourceKeyOf(v), v.id);
        } catch (Throwable ignored) {
        }
        tvFavLabel.setText(collected ? getString(R.string.v8_collected) : getString(R.string.v8_collect));
    }

    private void toggleHeroCollect() {
        Movie.Video v = currentHero != null ? currentHero : firstVideoOfLines();
        if (v == null || TextUtils.isEmpty(v.id)) return;
        try {
            String key = sourceKeyOf(v);
            VodInfo info = new VodInfo();
            info.id = v.id;
            info.name = v.name;
            info.pic = v.pic;
            info.sourceKey = key;
            info.year = v.year;
            info.type = v.type;
            info.area = v.area;
            if (RoomDataManger.isVodCollect(key, v.id)) {
                RoomDataManger.deleteVodCollect(key, info);
                Toast.makeText(this, getString(R.string.det_fav_del), Toast.LENGTH_SHORT).show();
            } else {
                RoomDataManger.insertVodCollect(key, info);
                Toast.makeText(this, getString(R.string.det_fav_add), Toast.LENGTH_SHORT).show();
            }
            updateHeroFavState(v);
        } catch (Throwable th) {
            Toast.makeText(this, "收藏失败", Toast.LENGTH_SHORT).show();
        }
    }

    private String sourceKeyOf(Movie.Video v) {
        if (v != null && !TextUtils.isEmpty(v.sourceKey)) return v.sourceKey;
        SourceBean bean = ApiConfig.get().getHomeSourceBean();
        return bean == null ? "" : bean.getKey();
    }

    private Movie.Video firstVideoOfLines() {
        for (Line l : lines) {
            if (l.videos != null && !l.videos.isEmpty()) return l.videos.get(0);
        }
        return null;
    }

    private void openDetail(Movie.Video v) {
        if (v == null) return;
        Bundle b = new Bundle();
        b.putString("id", v.id);
        b.putString("sourceKey", sourceKeyOf(v));
        b.putString("title", v.name);
        jumpActivity(DetailActivity.class, b);
    }

    // =====================================================================
    //  推荐 / 分类 / 历史 —— 三个页面共用一个 hero
    // =====================================================================

    private void showCatPage(int index) {
        showCatPage(index, true);
    }

    /**
     * 切到某个分类页。
     *
     * @param takeFocus true  = 按 OK 进来的，焦点直接落到海报墙；
     *                  false = 只是「焦点停在顶栏」顺手把内容换过来，焦点必须留在顶栏，
     *                          否则用户往右移一格就掉进墙里，再也点不到下一个标签。
     */
    private void showCatPage(int index, boolean takeFocus) {
        if (realSortCount() == 0) {
            showCatEmpty();
            return;
        }
        histMode = false;
        catMode = true;
        browseMode = false;
        histLayout.setVisibility(View.GONE);
        catEmpty.setVisibility(View.GONE);
        contentLayout.setVisibility(View.VISIBLE);
        linesViewport.setVisibility(View.GONE);
        heroInfo.setVisibility(View.VISIBLE);
        heroInfo.setAlpha(1f);
        heroInfo.setTranslationY(0);
        heroActions.setAlpha(1f);
        heroActions.setTranslationY(0);
        chipBox.setVisibility(View.VISIBLE);
        chipBox.setAlpha(1f);
        hintBar.setVisibility(View.VISIBLE);
        hintBar.setText(R.string.v8_hint_grid);
        hintBar.setAlpha(1f);
        bottomFade.setVisibility(View.VISIBLE);
        setPlayLabel(false);

        catIndex = Math.max(0, Math.min(index, sorts.size() - 1));
        switchCat(catIndex, false);
        layoutCat(false);
        fadeIn(contentLayout);
        if (takeFocus) focusPosterWall();
    }

    /** 站点只给了「首页推荐」、没有任何真实分类：停在空白上只会让人以为加载挂了 */
    private void showCatEmpty() {
        catMode = true;
        histMode = false;
        browseMode = false;
        contentLayout.setVisibility(View.GONE);
        histLayout.setVisibility(View.GONE);
        linesViewport.setVisibility(View.GONE);
        heroInfo.setVisibility(View.GONE);
        heroActions.setVisibility(View.GONE);
        chipBox.setVisibility(View.GONE);
        catEmpty.setVisibility(View.VISIBLE);
        hintBar.setVisibility(View.GONE);
        syncNavSelected();
    }

    /**
     * 回推荐页。
     * 内容流一直在 linesBox 里没动过，切回来只是把两层换回去，不重新请求。
     */
    private void showRecPage() {
        histMode = false;
        catMode = false;
        browseMode = false;
        contentLayout.setVisibility(View.GONE);
        histLayout.setVisibility(View.GONE);
        catEmpty.setVisibility(View.GONE);
        chipBox.setVisibility(View.GONE);
        linesViewport.setVisibility(View.VISIBLE);
        heroInfo.setVisibility(View.VISIBLE);
        heroInfo.setAlpha(1f);
        heroInfo.setTranslationY(0);
        heroActions.setAlpha(1f);
        heroActions.setTranslationY(0);
        heroActions.setVisibility(currentHero == null ? View.GONE : View.VISIBLE);
        hintBar.setVisibility(View.VISIBLE);
        hintBar.setText(lines.size() > 1 ? R.string.v8_hint_home : R.string.v8_hint_rec);
        hintBar.setAlpha(1f);
        bottomFade.setVisibility(View.GONE);
        setPlayLabel(false);

        Movie.Video first = firstVideoOfLines();
        if (first != null) {
            setHero(first, true, getString(R.string.home_hero_tag_hot));
        } else if (!heroNone) {
            paintHeroIdle();
        }
        syncNavSelected();
        layoutLines(false);
    }

    /**
     * 历史页。
     *
     * 和分类页同一个骨架：上面 hero（上次看到的那部）、下面同一面 5 列大卡墙。
     * 数据全在本地库，切过来不用等网络，所以它也吃「焦点停一下就切」。
     */
    private void showHistoryPage(boolean takeFocus) {
        histMode = true;
        catMode = false;
        browseMode = false;
        contentLayout.setVisibility(View.GONE);
        catEmpty.setVisibility(View.GONE);
        chipBox.setVisibility(View.GONE);
        histLayout.setVisibility(View.VISIBLE);
        linesViewport.setVisibility(View.GONE);
        hintBar.setVisibility(View.VISIBLE);
        hintBar.setText(R.string.v8_hint_hist);
        hintBar.setAlpha(1f);
        setPlayLabel(true);
        buildHistoryWall();
        layoutCat(false);
        syncNavSelected();
        if (takeFocus && !histVideos.isEmpty()) {
            histGrid.post(new Runnable() {
                @Override
                public void run() {
                    histGrid.requestFocus();
                }
            });
        }
    }

    /** 历史墙：本地播放记录 → 5 列大卡（卡底一条进度，卡下「看到 32:18」） */
    private void buildHistoryWall() {
        histVideos.clear();
        histProgress.clear();
        histVideos.addAll(loadHistory(60));

        boolean empty = histVideos.isEmpty();
        histEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        histGrid.setVisibility(empty ? View.GONE : View.VISIBLE);
        histAdapter.setProgressMap(histProgress);
        histAdapter.setData(histVideos, false, null);

        heroInfo.setVisibility(empty ? View.GONE : View.VISIBLE);
        heroActions.setVisibility(empty ? View.GONE : View.VISIBLE);
        heroInfo.setAlpha(1f);
        heroActions.setAlpha(1f);
        bottomFade.setVisibility(histVideos.size() > WALL_SPAN * 2 ? View.VISIBLE : View.GONE);

        if (empty) {
            currentHero = null;
            hintBar.setText(R.string.v8_hist_empty_hint);
            return;
        }
        setHero(histVideos.get(0), true, getString(R.string.v8_hist_tag));
    }

    /** 现在是不是停在「推荐」这一页 */
    private boolean onRecPage() {
        return !catMode && !histMode;
    }

    /** 有内容的真实分类个数（my0 不算） */
    private int realSortCount() {        int n = 0;
        for (int i = 0; i < sorts.size(); i++) {
            if (isRealSort(i)) n++;
        }
        return n;
    }

    // ---------------- 分类页 / 历史页的位置（首屏 ↔ 浏览） ----------------

    /**
     * 首屏：墙从 240mm 起（和推荐页内容流同一个起点），hero 在上面。
     * 浏览：墙顶到 66mm（顶栏下沿），hero 淡出，一屏能看到将近两行。
     *
     * 用 topMargin 而不是 translationY：translationY 只是把整个控件挪走，
     * 控件高度不变，上移之后底部会空出一块、最后一行永远滚不出来。
     */
    private void layoutCat(boolean animate) {
        int target = browseMode ? mm(MM_LINE_TOP_MIN) : mm(MM_LINE_TOP_FIRST);
        applyTopMargin(mViewPager, target, animate);
        applyTopMargin(histGrid, target, animate);
    }

    private void applyTopMargin(View v, int target, boolean animate) {
        if (v == null) return;
        ViewGroup.LayoutParams p = v.getLayoutParams();
        if (!(p instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) p;
        int cur = lp.topMargin;
        if (cur == target) return;
        v.animate().cancel();
        if (!animate) {
            v.setTranslationY(0);
            lp.topMargin = target;
            v.setLayoutParams(lp);
            return;
        }
        // 先把边距落到目标位置（视口一次到位变高/变矮），
        // 再用 translationY 把视觉位置从旧处滑回来 —— 看到的是「推上来」，不是「跳一下」
        v.setTranslationY(cur - target);
        lp.topMargin = target;
        v.setLayoutParams(lp);
        v.animate().translationY(0).setDuration(240)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    /** 「更多」：站点分类比顶栏放得下的多，弹一个完整列表，一个都不丢 */
    private void showMoreCats() {
        final List<MovieSort.SortData> items = new ArrayList<>();
        for (int i = 0; i < sorts.size(); i++) {
            if (isRealSort(i)) items.add(sorts.get(i));
        }
        if (items.isEmpty()) return;

        SelectDialog<MovieSort.SortData> dialog = new SelectDialog<>(HomeActivity.this);
        int spanCount = items.size() / 10;
        if (spanCount <= 1) spanCount = 1;
        if (spanCount >= 4) spanCount = 4;
        TvRecyclerView tvRecyclerView = dialog.findViewById(R.id.list);
        tvRecyclerView.setLayoutManager(new V7GridLayoutManager(dialog.getContext(), spanCount));
        if (spanCount != 1) {
            android.view.ViewGroup.LayoutParams clp =
                    dialog.findViewById(R.id.cl_root).getLayoutParams();
            clp.width = AutoSizeUtils.mm2px(dialog.getContext(), 720);
        }
        dialog.setTip(getString(R.string.v8_more_title));
        dialog.setAdapter(tvRecyclerView, new SelectDialogAdapter.SelectDialogInterface<MovieSort.SortData>() {
            @Override
            public void click(MovieSort.SortData value, int pos) {
                int idx = sorts.indexOf(value);
                if (idx >= 0) {
                    showCatPage(idx, false);
                    focusNavType(NT_MORE);
                }
            }

            @Override
            public String getDisplay(MovieSort.SortData val) {
                return val.name;
            }
        }, new DiffUtil.ItemCallback<MovieSort.SortData>() {
            @Override
            public boolean areItemsTheSame(@NonNull @NotNull MovieSort.SortData a,
                                           @NonNull @NotNull MovieSort.SortData b) {
                return a == b;
            }

            @Override
            public boolean areContentsTheSame(@NonNull @NotNull MovieSort.SortData a,
                                              @NonNull @NotNull MovieSort.SortData b) {
                return Objects.equals(a.id, b.id);
            }
        }, items, 0);
        dialog.show();
    }

    private MovieSort.SortData sortAt(int position) {
        if (position < 0 || position >= sorts.size()) return null;
        return sorts.get(position);
    }

    /** 「首页推荐」(my0) 是特殊分类，顶栏不出现它 */
    private boolean isRealSort(int position) {
        MovieSort.SortData s = sortAt(position);
        return s != null && !"my0".equals(s.id);
    }

    private int firstRealSortIndex() {
        for (int i = 0; i < sorts.size(); i++) {
            if (isRealSort(i)) return i;
        }
        return 0;
    }

    private BaseLazyFragment fragmentAt(int position) {
        if (position < 0 || position >= fragments.size()) return null;
        return fragments.get(position);
    }

    /** 焦点交给海报墙 */
    private void requestGridFocus() {
        BaseLazyFragment f = fragmentAt(mViewPager.getCurrentItem());
        if (f instanceof GridFragment) {
            ((GridFragment) f).requestGridFocus();
        } else {
            mViewPager.requestFocus();
        }
    }

    /** 当前页面正在显示的那个网格（分类页在 ViewPager 里，历史页是直接一个 RV） */
    private RecyclerView currentGrid() {
        if (histMode) return histGrid;
        if (!catMode) return null;
        View page = mViewPager.getChildAt(mViewPager.getCurrentItem());
        if (page instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) page;
            for (int i = 0; i < vg.getChildCount(); i++) {
                if (vg.getChildAt(i) instanceof RecyclerView) return (RecyclerView) vg.getChildAt(i);
            }
        }
        return null;
    }

    // =====================================================================
    //  按键
    // =====================================================================

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
            if (keyCode == KeyEvent.KEYCODE_MENU) {
                showSiteSwitch();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (handleVerticalKey(keyCode == KeyEvent.KEYCODE_DPAD_UP)) return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * 分类页 / 历史页的上下键。
     *
     * 这两个页面没有内容流，只有 hero（上）+ 一面墙（下），所以逻辑很短：
     *   顶栏 ↓ → hero 按钮 → 墙；焦点已经在墙里就交给 RecyclerView 自己换行
     *   墙里第一行 ↑ → 顶栏当前项（回去换标签）
     */
    private boolean handleVerticalInPage(boolean up) {
        RecyclerView rv = currentGrid();
        boolean inGrid = rv != null && isInside(rv);

        if (up) {
            if (isInside(btnPlay) || isInside(btnFav)) {
                exitBrowse();
                focusNavOfCurrent();
                return true;
            }
            if (inGrid) {
                View f = getCurrentFocus();
                int pos = (rv == null || f == null) ? -1 : rv.getChildAdapterPosition(f);
                // 第一行还往上 → 回顶栏；后面的行交给 RecyclerView 自己翻
                if (pos >= 0 && pos < WALL_SPAN) {
                    exitBrowse();
                    focusNavOfCurrent();
                    return true;
                }
                return false;
            }
            return false;
        }

        if (inGrid) return false;   // 墙内往下翻交给 RecyclerView
        if (currentHero != null && heroActions.getVisibility() == View.VISIBLE) {
            btnPlay.requestFocus();
            enterBrowse();
            return true;
        }
        if (histMode) {
            if (!histVideos.isEmpty()) {
                histGrid.requestFocus();
                enterBrowse();
                return true;
            }
            return true;
        }
        focusPosterWall();
        enterBrowse();
        return true;
    }

    /**
     * 上下键统一在这里接管。
     * 返回 true 表示已经处理，事件不再往下传（否则 RecyclerView 的
     * focusSearch 会自己找一个「看起来在下面」的 View，跳到隔壁行中间）。
     */
    private boolean handleVerticalKey(boolean up) {
        if (catMode || histMode) return handleVerticalInPage(up);
        if (lines.isEmpty()) return false;
        Line cur = focusedLine();

        if (cur == null) {
            // 焦点在 hero 按钮上
            if (isInside(btnPlay) || isInside(btnFav)) {
                if (up) {
                    exitBrowse();
                    focusNavType(NT_REC);
                    return true;
                }
                // 回到上次停的那一列，别每次都甩到第 1 张
                focusRow(0, Math.max(0, lines.get(0).lastFocus));
                return true;
            }
            // 焦点已经在顶栏：往上没什么可去的，左右让系统自己走
            if (up) return false;
            // 顶栏 ↓ → hero 按钮（有片可播时）→ 第一行
            if (currentHero != null) {
                btnPlay.requestFocus();
                enterBrowse();
            } else {
                focusRow(0, Math.max(0, lines.get(0).lastFocus));
            }
            return true;
        }

        // 焦点在顶部历史海报墙里：先墙内换行，换到最后一行再往下切推荐内容
        if (cur.isGrid() && wallVertical(cur, up)) return true;

        if (up) {
            if (cur.index == 0) {
                if (currentHero != null) {
                    exitBrowse();
                    btnPlay.requestFocus();
                } else {
                    navViews.get(navIndex).requestFocus();
                }
                return true;
            }
            focusRow(cur.index - 1, crossCol(cur));
            return true;
        }

        if (cur.index >= lines.size() - 1) {
            return true;   // 已经是最后一行，不再吃按键（让系统自己处理或者不动）
        }
        focusRow(cur.index + 1, crossCol(cur));
        return true;
    }

    /**
     * 海报墙里按上下：先在墙内换一行（±列数），换到头了才交给跨行逻辑。
     * 提前 return true 是为了别让 RecyclerView 自己去 focusSearch ——
     * 它会在网格里找一个「几何上在下面」的格子，斜着跳，看着很乱。
     *
     * 位置用 getChildAdapterPosition 现取，不用 line.lastFocus：
     * lastFocus 是「上一次手动设过的位置」，TvRecyclerView 自己也会改选中项，
     * 两者一对不上就会「明明是第 3 张，按 ↓ 却直接跳到下一行」。
     */
    private boolean wallVertical(Line cur, boolean up) {
        if (cur.adapter == null) return false;
        int pos = focusPosOf(cur);
        int np = pos + (up ? -cur.span : cur.span);
        LOG.i("echo-v8-wall pos=" + pos + " np=" + np + " span=" + cur.span
                + " count=" + cur.adapter.cellCount());
        if (np < 0 || np >= cur.adapter.cellCount()) return false;
        focusRow(cur.index, np);
        return true;
    }

    /** 焦点落在这一行的第几个格子（拿不到就用最后一次记录的） */
    private int focusPosOf(Line line) {
        View f = getCurrentFocus();
        if (f == null || line.rv == null) return line.lastFocus;
        ViewParent p = f.getParent();
        while (p != null) {
            if (p == line.rv) {
                int pos = line.rv.getChildAdapterPosition(f);
                if (pos != RecyclerView.NO_POSITION) return pos;
                break;
            }
            p = p.getParent();
        }
        return line.lastFocus;
    }

    /** 跨行时带过去的列号：墙里第 17 张（7 列）落在第 2 列 */
    private int crossCol(Line cur) {
        int pos = focusPosOf(cur);
        return cur.isGrid() ? (pos % cur.span) : pos;
    }

    @Override
    public void onBackPressed() {
        if (isLoading()) {
            refreshEmpty();
            return;
        }
        // 分类 / 历史：第一次 BACK 先回到首屏态（hero 出来），再按一次才回推荐
        if (catMode || histMode) {
            if (browseMode) {
                exitBrowse();
                focusNavOfCurrent();
            } else {
                showRecPage();
                focusNavType(NT_REC);
            }
            return;
        }
        if (browseMode) {
            exitBrowse();
            if (navIndex < navViews.size()) navViews.get(navIndex).requestFocus();
            return;
        }
        Line cur = focusedLine();
        if (cur != null) {
            exitBrowse();
            focusNavType(NT_REC);
            return;
        }
        doExit();
    }

    private void doExit() {
        if (System.currentTimeMillis() - mExitTime < 2000) {
            AppManager.getInstance().finishAllActivity();
            EventBus.getDefault().unregister(this);
            ControlManager.get().stopServer();
            finish();
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(0);
        } else {
            mExitTime = System.currentTimeMillis();
            Toast.makeText(mContext, getString(R.string.hm_exit), Toast.LENGTH_SHORT).show();
        }
    }

    // =====================================================================
    //  数据
    // =====================================================================

    private void initViewModel() {
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);

        sourceViewModel.sortResult.observe(this, new Observer<AbsSortXml>() {
            @Override
            public void onChanged(AbsSortXml absXml) {
                if (skipNextUpdate) {
                    skipNextUpdate = false;
                    return;
                }
                showSuccess();
                sorts.clear();
                if (absXml != null && absXml.classes != null && absXml.classes.sortList != null) {
                    sorts.addAll(DefaultConfig.adjustSort(
                            ApiConfig.get().getHomeSourceBean().getKey(), absXml.classes.sortList, true));
                } else {
                    sorts.addAll(DefaultConfig.adjustSort(
                            ApiConfig.get().getHomeSourceBean().getKey(), new ArrayList<MovieSort.SortData>(), true));
                }
                initViewPager(absXml);
                SourceBean home = ApiConfig.get().getHomeSourceBean();
                if (home != null && home.getName() != null && !home.getName().isEmpty()) {
                    tvPillSource.setText(home.getName());
                }
                // 顺手把直播频道表预热掉（源站只给 127.0.0.1 代理地址时要另外拉一次）。
                // 这样用户点「直播」是秒开，而不是盯着转圈十几秒。
                LivePreload.warmUp(HomeActivity.this);
            }
        });
    }

    private boolean skipNextUpdate = false;
    private boolean dataInitOk = false;
    private boolean jarInitOk = false;

    /** 骨架已经放出来但接口一直不回：到点把「正在连接」改成实话 */
    private final Runnable mInitTimeoutRun = new Runnable() {
        @Override
        public void run() {
            long left = HOME_LOAD_DEADLINE
                    - (System.currentTimeMillis() - homeLoadStartAt);
            if (left > 0) {
                mHandler.postDelayed(this, left);
                return;
            }
            // 已经有内容就什么都不用做；没有就给结论（别再写「正在连接」）
            if (currentHero != null) return;
            paintHeroNone();
            hintBar.setText(R.string.v8_hint_home_none);
            hintBar.setVisibility(View.VISIBLE);
        }
    };

    private void initData() {
        if (dataInitOk && jarInitOk) {
            sourceViewModel.getSort(ApiConfig.get().getHomeSourceBean().getKey());
            if (Hawk.get(HawkConfig.HOME_DEFAULT_SHOW, false)) {
                jumpActivity(LivePlayActivity.class);
            }
            return;
        }
        // 不整屏转圈：源站的 spider jar 常挂在海外小机房，冷启动十几秒很常见，
        // 全屏一个转圈圈跟卡死没区别。先把 v8 骨架放出来，hero 写「正在读取」，
        // 数据到了再填 —— 用户至少能看见顶栏、能先去直播/搜索。
        showSuccess();
        heroNone = false;
        tvHeroTag.setText(getString(R.string.home_hero_tag_hot));
        tvHeroTitle.setText(getString(R.string.home_app_home));
        tvHeroMeta.setText("");
        tvHeroDesc.setText(getString(R.string.v8_hero_connecting));
        // 还没有影片时「立即播放 / 收藏」是无对象的按钮，先收起来
        heroActions.setVisibility(View.GONE);
        // 骨架放出来了就得有个头：接口一直不回时不能永远写着「正在连接」
        mHandler.removeCallbacks(mInitTimeoutRun);
        mHandler.post(mInitTimeoutRun);
        if (dataInitOk && !jarInitOk) {
            if (!ApiConfig.get().getSpider().isEmpty()) {
                ApiConfig.get().loadJar(useCacheConfig, ApiConfig.get().getSpider(), new ApiConfig.LoadConfigCallback() {
                    @Override
                    public void success() {
                        jarInitOk = true;
                        mHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (!useCacheConfig) {
                                    Toast.makeText(HomeActivity.this, getString(R.string.hm_ok), Toast.LENGTH_SHORT).show();
                                }
                                initData();
                            }
                        }, 50);
                    }

                    @Override
                    public void retry() {
                    }

                    @Override
                    public void error(String msg) {
                        jarInitOk = true;
                        dataInitOk = true;
                        LOG.e("echo---HomeActivity loadJar error msg=[" + msg + "]");
                        final String real = (msg == null || msg.isEmpty())
                                ? ApiConfig.get().getJarError() : msg;
                        final String shown = (real == null || real.isEmpty())
                                ? "组件加载失败：" + ApiConfig.get().getJarDiag() : real;
                        mHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(HomeActivity.this, shown, Toast.LENGTH_LONG).show();
                                initData();
                            }
                        }, 50);
                    }
                });
            }
            return;
        }
        ApiConfig.get().loadConfig(useCacheConfig, new ApiConfig.LoadConfigCallback() {
            TipDialog dialog = null;

            @Override
            public void retry() {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        initData();
                    }
                });
            }

            @Override
            public void success() {
                dataInitOk = true;
                if (ApiConfig.get().getSpider().isEmpty()) jarInitOk = true;
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        initData();
                    }
                }, 50);
            }

            @Override
            public void error(String msg) {
                if (msg.equalsIgnoreCase("-1")) {
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            dataInitOk = true;
                            jarInitOk = true;
                            initData();
                        }
                    });
                    return;
                }
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (dialog == null)
                            dialog = new TipDialog(HomeActivity.this, msg, getString(R.string.hm_retry), getString(R.string.hm_cancel), new TipDialog.OnListener() {
                                @Override
                                public void left() {
                                    mHandler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            initData();
                                            dialog.hide();
                                        }
                                    });
                                }

                                @Override
                                public void right() {
                                    dataInitOk = true;
                                    jarInitOk = true;
                                    mHandler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            initData();
                                            dialog.hide();
                                        }
                                    });
                                }

                                @Override
                                public void cancel() {
                                    dataInitOk = true;
                                    jarInitOk = true;
                                    mHandler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            initData();
                                            dialog.hide();
                                        }
                                    });
                                }
                            });
                        if (!dialog.isShowing()) dialog.show();
                    }
                });
            }
        }, this);
    }

    boolean useCacheConfig = false;

    private void initViewPager(AbsSortXml absXml) {
        fragments.clear();
        // 站点首页推荐单独存一份，首页「推荐内容」那一行直接铺它
        homeRecommend.clear();
        if (absXml != null && absXml.videoList != null) {
            homeRecommend.addAll(absXml.videoList);
        }
        if (!sorts.isEmpty()) {
            // 「首页推荐(my0)」不再给原版 UserFragment 的快捷入口页：
            // 历史/直播/搜索/收藏/推送/文件这六个入口 v8 顶栏已经全有了，
            // 再露一遍就是两套 UI 撞在一起。这里塞空白页占位，保证下标和 sort 列表对齐。
            for (MovieSort.SortData data : sorts) {
                if ("my0".equals(data.id)) {
                    fragments.add(BlankFragment.newInstance());
                } else {
                    fragments.add(GridFragment.newInstance(data));
                }
            }
            pageAdapter = new HomePageAdapter(getSupportFragmentManager(), fragments);
            try {
                Field field = ViewPager.class.getDeclaredField("mScroller");
                field.setAccessible(true);
                FixedSpeedScroller scroller = new FixedSpeedScroller(mContext,
                        new android.view.animation.AccelerateInterpolator());
                field.set(mViewPager, scroller);
                scroller.setmDuration(300);
            } catch (Exception ignored) {
            }
            mViewPager.setPageTransformer(true, new DefaultTransformer());
            mViewPager.setAdapter(pageAdapter);
            // 默认落在第一个真实分类上，别停在被占位的 my0 那页（会是一片空白）
            mViewPager.setCurrentItem(Math.max(0, firstRealSortIndex()), false);
        }
        catTabs.clear();
        catTabs.addAll(buildCatTabs());
        // 顶栏项数变了（分类回来了）：重建一次，焦点留在原来那一项上
        buildNav();

        buildLines();

        // 从二级页面顶栏点「分类」过来的，分类这就绪了，直接切过去
        openCatIfReady();

        // 首次拿到分类后把焦点交给顶栏「推荐」，用户一按 ↓ 就能进内容行。
        // 但用户要是在数据回来之前已经移到别的栏目上了，别把焦点抢回来。
        navBox.post(new Runnable() {
            @Override
            public void run() {
                if (!catMode && !histMode && !browseMode) {
                    focusNavType(NT_REC);
                }
            }
        });
    }

    /**
     * 把站点的分类归并成顶栏那几个大类。
     *
     * 站点的 sort 名字五花八门（「电影片」「连续剧」「动漫片」「纪录」），
     * 直接铺到顶栏就是十几个长得差不多的标签。这里按别名表归到 6 个大类，
     * 顺序固定在 CAT_ALIAS 里；站点没有哪个大类那个 tab 就不出现，不留空位。
     *
     * 一个都没匹配上（比如站点只有「4K专区」「体育」）时，退回「原样列出站点分类」，
     * 最多 6 个 —— 总比顶栏一个分类都没有强。
     */
    private List<NavItem> buildCatTabs() {
        List<NavItem> out = new ArrayList<>();
        hasMoreCats = false;
        if (sorts.isEmpty()) return out;

        boolean[] used = new boolean[sorts.size()];
        for (String[] alias : CAT_ALIAS) {
            int hit = -1;
            for (int i = 0; i < sorts.size(); i++) {
                if (used[i] || !isRealSort(i)) continue;
                String name = sorts.get(i).name;
                if (name == null) continue;
                for (String a : alias) {
                    if (name.contains(a)) {
                        hit = i;
                        break;
                    }
                }
                if (hit >= 0) break;
            }
            if (hit >= 0) {
                used[hit] = true;
                out.add(new NavItem(NT_CAT, hit, alias[0]));
            }
        }

        // 没归到任何大类的分类，先按原顺序补在后面（顶栏还有位置的话）
        for (int i = 0; i < sorts.size() && out.size() < MAX_CAT_TABS; i++) {
            if (used[i] || !isRealSort(i)) continue;
            used[i] = true;
            out.add(new NavItem(NT_CAT, i));
        }
        // 还有剩下的 → 记一笔，末尾那一项变成「更多」（弹层里是完整分类列表）
        for (int i = 0; i < sorts.size(); i++) {
            if (!used[i] && isRealSort(i)) {
                hasMoreCats = true;
                break;
            }
        }
        return out;
    }

    @Override
    protected void onResume() {
        super.onResume();
        SourceBean home = ApiConfig.get().getHomeSourceBean();
        if (home != null && home.getName() != null && !home.getName().isEmpty()) {
            tvPillSource.setText(home.getName());
        }
        updateClock();
        refreshWallIfChanged();
        // 从播放页回来：历史页要按最新记录重排（刚看的那部应该跑到第一格）
        if (histMode) buildHistoryWall();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacksAndMessages(null);
    }

    /**
     * 某个分类的第一页回来了 → 把 hero 换成这个分类最新的一部。
     * 只认「当前正在看的那个分类」，别的分类的结果先缓存着，切过去时直接用。
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onSortPage(SortPageEvent event) {
        if (event == null || event.first == null) return;
        String id = event.sortId == null ? "" : event.sortId;
        catHeroCache.put(id, event.first);
        if (catMode && id.equals(curCatSortId)) {
            MovieSort.SortData sort = sortAt(catIndex);
            setHero(event.first, true, getString(R.string.v8_cat_tag, sort == null ? "" : sort.name));
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_PUSH_URL) {
            if (ApiConfig.get().getSource("push_agent") != null) {
                Intent newIntent = new Intent(mContext, DetailActivity.class);
                newIntent.putExtra("id", (String) event.obj);
                newIntent.putExtra("sourceKey", "push_agent");
                newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                HomeActivity.this.startActivity(newIntent);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
        AppManager.getInstance().appExit(0);
        ControlManager.get().stopServer();
    }

    // =====================================================================
    //  切源 / 重载
    // =====================================================================

    void showSiteSwitch() {
        List<SourceBean> sites = new ArrayList<>();
        for (SourceBean sb : ApiConfig.get().getSourceBeanList()) {
            if (sb.getHide() == 0) sites.add(sb);
        }
        if (sites.size() > 0) {
            SelectDialog<SourceBean> dialog = new SelectDialog<>(HomeActivity.this);

            int spanCount = (int) Math.floor(sites.size() / 10);
            if (spanCount <= 1) spanCount = 1;
            if (spanCount >= 4) spanCount = 4;

            TvRecyclerView tvRecyclerView = dialog.findViewById(R.id.list);
            tvRecyclerView.setLayoutManager(new V7GridLayoutManager(dialog.getContext(), spanCount));
            android.view.ViewGroup.LayoutParams clp = dialog.findViewById(R.id.cl_root).getLayoutParams();
            if (spanCount != 1) {
                // 固定 720mm：站点多的时候靠列数吸收，不要按「400+260*n」把弹层撑到满屏
                clp.width = AutoSizeUtils.mm2px(dialog.getContext(), 720);
            }

            dialog.setTip(getString(R.string.dia_source));
            dialog.setAdapter(tvRecyclerView, new SelectDialogAdapter.SelectDialogInterface<SourceBean>() {
                @Override
                public void click(SourceBean value, int pos) {
                    ApiConfig.get().setSourceBean(value);
                    reloadHome();
                }

                @Override
                public String getDisplay(SourceBean val) {
                    return val.getName();
                }
            }, new DiffUtil.ItemCallback<SourceBean>() {
                @Override
                public boolean areItemsTheSame(@NonNull @NotNull SourceBean oldItem, @NonNull @NotNull SourceBean newItem) {
                    return oldItem == newItem;
                }

                @Override
                public boolean areContentsTheSame(@NonNull @NotNull SourceBean oldItem, @NonNull @NotNull SourceBean newItem) {
                    return oldItem.getKey().equals(newItem.getKey());
                }
            }, sites, sites.indexOf(ApiConfig.get().getHomeSourceBean()));
            dialog.show();
        }
    }

    void reloadHome() {
        Intent intent = new Intent(getApplicationContext(), HomeActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Bundle bundle = new Bundle();
        bundle.putBoolean("useCache", true);
        intent.putExtras(bundle);
        HomeActivity.this.startActivity(intent);
    }

    public static void homeRecf() {
        int homeRec = Hawk.get(HawkConfig.HOME_REC, -1);
        int limit = 2;
        if (homeRec == limit) homeRec = -1;
        homeRec++;
        Hawk.put(HawkConfig.HOME_REC, homeRec);
    }

    public static boolean reHome(android.content.Context appContext) {
        Intent intent = new Intent(appContext, HomeActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Bundle bundle = new Bundle();
        bundle.putBoolean("useCache", true);
        intent.putExtras(bundle);
        appContext.startActivity(intent);
        return true;
    }

    private void refreshEmpty() {
        skipNextUpdate = true;
        showSuccess();
        sorts.clear();
        sorts.addAll(DefaultConfig.adjustSort(
                ApiConfig.get().getHomeSourceBean().getKey(), new ArrayList<MovieSort.SortData>(), true));
        initViewPager(null);
    }

    // =====================================================================
    //  工具
    // =====================================================================

    private int mm(float v) {
        return AutoSizeUtils.mm2px(this, v);
    }
}
