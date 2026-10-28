package com.pigtv.ui.activity;

import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
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
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager.widget.ViewPager;

import com.chad.library.adapter.base.BaseQuickAdapter;
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
 * 首页
 * ============================================================
 * 三个页面，一套骨架（都是「5 列竖向封面墙」）：
 *
 *   推荐   hero（首屏）+ 站点推荐位铺成的墙，滑到底自动翻分类下一页
 *   影视   顶栏「影视」展开的二级菜单（电影/电视剧/…）+ 封面墙，没有 hero
 *   历史   本地播放记录 + 卡底进度条
 *
 * 封面上的信息只有左下角一行，取值口径和手机端 .pcB 完全一致（VodText.coverStat）：
 * 有「更新至 12 集 / 评分 7.5」就显示它，没有就「类型 · 年份 · 地区」。
 *
 * 焦点模型（一根 D-pad 走完全程，反向对称）：
 *
 *   顶栏 ──↓──→ 二级菜单 ──↓──→ hero 按钮 ──↓──→ 封面墙
 *     ↑            ↑                               │
 *     └────────────┴──────（墙第一行按 ↑）───────────┘
 *
 *   影视页没有 hero，台阶少一层：顶栏 → 二级菜单 → 墙。
 *
 * 墙里的上下移动由本类的 dispatchKeyEvent 统一接管，没有交给 RecyclerView
 * 的 focusSearch —— 那个在「下一行只露半截」时经常返回 null，焦点就卡住不动。
 *
 * 首屏 / 浏览态：
 *   推荐、历史：屏上有 hero，墙从 240mm 起；焦点进墙 → hero 淡出、墙推到 96mm
 *   影视：没有 hero，墙固定从 114mm（二级菜单下沿）起，焦点进出都不动
 */
public class HomeActivity extends BaseActivity {

    // ------------------------------------------------------------------
    //  常量
    // ------------------------------------------------------------------
    /** 首屏墙起点（mm，物理毫米，与 dimens_v8 同一套换算） */
    private static final float MM_LINE_TOP_FIRST = 240f;
    /**
     * 浏览态（焦点进墙）的墙起点。
     * 顶栏放大到 38mm 之后底边到了 64mm，这个值还停在 66 → 浏览态海报墙直接顶到标签底下，
     * 卡片顶部的角标被压掉一截。留出约 20mm 的呼吸位（实测 78 还是只差 4px）。
     */
    private static final float MM_LINE_TOP_MIN = 96f;
    /**
     * 影视页的墙起点（mm）：那一页没有 hero，二级菜单下面直接就是封面。
     * 顶栏 26+38=64 到底，二级菜单 72~102mm，再留 12mm 呼吸位 = 114mm。
     * 改顶栏或二级菜单的高度，这个数要跟着改。
     */
    private static final float MM_WALL_TOP_CAT = 114f;
    /** 推荐/影视/历史三面墙的列数（同一套卡：215×322mm，5 列正好铺满版心） */
    private static final int WALL_SPAN = 5;
    /**
     * 推荐墙少于这么多张时，从第一个真实分类补一批。
     *
     * 不少源的首页推荐位只给一两部（运营位），直接铺出来就是「一部片 + 一整屏黑」，
     * 看着像没加载完。补到两屏（WALL_SPAN × 2）才有往下滑的必要。
     */
    private static final int REC_FILL_MIN = WALL_SPAN * 2;
    /** 历史墙一屏放几部（只用来算「播放记录有没有变」的指纹窗口） */
    private static final int WALL_MAX = WALL_SPAN;
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
    /** 更多：站点分类超过上限时出现在二级菜单末尾，点开是完整的分类列表 */
    private static final int NT_MORE = 6;
    /** 影视：电影/电视剧/综艺/… 全部收进这一个标签，选中后下面才展开二级菜单 */
    private static final int NT_FILM = 7;

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
    private static final int MAX_CAT_TABS = 7;

    private static Resources res;

    // ------------------------------------------------------------------
    //  视图
    // ------------------------------------------------------------------
    private View launcher;
    /** 推荐页：竖向封面墙（v11）。以前那一行横滑大卡已经拆掉 —— 按 ↓ 永远滑不下去 */
    private View recLayout;
    private TvRecyclerView recGrid;
    private HomeLineAdapter recAdapter;
    private ImageView bgArtA;
    private ImageView bgArtB;
    /** 当前正在显示的那张背景（A/B 交替做交叉淡入） */
    private ImageView bgFront;

    private LinearLayout heroInfo;
    private LinearLayout heroActions;
    private LinearLayout navBox;
    private LinearLayout subNavBox;
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
    private final List<View> navViews = new ArrayList<>();
    /** 二级菜单的项（顺序 = 屏幕上的顺序），= catTabs（+ 末尾「更多」） */
    private final List<View> subNavViews = new ArrayList<>();
    /** 顶栏的项（顺序 = 屏幕上的顺序），分类全部收进「影视」后只有 6 项 */
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
    /** 站点首页推荐（配置里的 absXml.videoList），推荐页封面墙的第一批就是它 */
    private final List<Movie.Video> homeRecommend = new ArrayList<>();

    // ---- 推荐页封面墙的分页状态（「一直往下滑」靠这几个字段） ----
    /** 墙上实际的条目（首页推荐 + 后面按分类翻页补进来的） */
    private final List<Movie.Video> recItems = new ArrayList<>();
    /** 补内容/翻页用的分类：站点推荐位通常只有一页，滑到底就接着翻它 */
    private MovieSort.SortData recSort;
    /** 下一次要取第几页（首页推荐已经占掉的一页不重复取） */
    private int recPage = 1;
    /** 该分类一共几页（0 = 源站没说，按「还能取到」算） */
    private int recPageCount = 0;
    /** 正在取页：getList 只有一条 LiveData，重入必串台 */
    private boolean recPageLoading = false;
    /** 已经翻到底，别再请求了 */
    private boolean recNoMore = false;
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

    /** 二级菜单「焦点停一下就切」用的延时。同理，但分类切换要发网络请求，留足 220ms */
    private Runnable pendingCatSwitch;
    private static final int SUB_SWITCH_DELAY = 220;
    /** 二级菜单胶囊之间的间距（mm），和 dimens_v8 的 v8_subnav_gap 保持一致 */
    private static final float SUB_GAP_MM = 5f;

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
        recLayout = findViewById(R.id.recLayout);
        recGrid = findViewById(R.id.recGrid);

        bgArtA = findViewById(R.id.bgArtA);
        bgArtB = findViewById(R.id.bgArtB);
        bgFront = bgArtA;

        heroInfo = findViewById(R.id.heroInfo);
        heroActions = findViewById(R.id.heroActions);
        navBox = findViewById(R.id.navBox);
        subNavBox = findViewById(R.id.subNavBox);
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
        initRecWall();

        setLoadSir(this.launcher);

        // 封面墙比屏幕高，底部那条渐隐把被屏底切断的那一行溶进黑底
        bottomFade.setVisibility(View.VISIBLE);
        hintBar.setText(R.string.v8_hint_rec);

        updateClock();
        mHandler.postDelayed(mClockRunnable, 1000);
        recLayout.post(new Runnable() {
            @Override
            public void run() {
                launcherReady = true;
                layoutCat(false);
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
        // 分类不再各自占一个顶栏标签（以前 6 个分类 + 更多 = 一排 11 项，
        // 3 米外根本扫不过来）：全部收进「影视」，选中后下面才展开二级菜单
        if (!catTabs.isEmpty()) navItems.add(new NavItem(NT_FILM));
        navItems.add(new NavItem(NT_LIVE));
        navItems.add(new NavItem(NT_FAV));
        inflateNav();
        inflateSubNav();
    }

    /** 顶栏分三组：常用入口 ‖ 影视 ‖ 工具，组间加一条竖线 */
    private int groupOf(NavItem it) {
        if (it.type == NT_FILM || it.type == NT_CAT || it.type == NT_MORE) return 1;
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
            case NT_FILM:
                return getString(R.string.v8_nav_film);
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
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(mm(1.2f), mm(25f));
                slp.setMarginStart(mm(7.5f));
                slp.setMarginEnd(mm(7.5f));
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
            if (i > 0) lp.setMarginStart(mm(1.8f));
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

    // ---------------- 二级菜单（影视 → 电影 / 电视剧 / …） ----------------

    /**
     * 把 catTabs 铺成顶栏下面那一行胶囊。
     *
     * 顶栏本身只剩一个「影视」，真正的分类在这里。数量由站点决定，
     * 分类回来之前是空的（那时顶栏连「影视」都不显示）。
     */
    private void inflateSubNav() {
        subNavBox.removeAllViews();
        subNavViews.clear();
        if (catTabs.isEmpty()) {
            subNavBox.setVisibility(View.GONE);
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < catTabs.size(); i++) {
            final NavItem it = catTabs.get(i);
            View item = inflater.inflate(R.layout.item_nav_sub, subNavBox, false);
            item.setId(View.generateViewId());
            ((TextView) item.findViewById(R.id.tvSubNav)).setText(labelOf(it));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
            if (i > 0) lp.setMarginStart(mm(SUB_GAP_MM));
            item.setLayoutParams(lp);
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cancelCatSwitch();
                    // OK 打在标签上 = 开筛选面板（年份 / 地区 / 类型 …）。
                    // 内容是「焦点停 220ms」时就已经切好的，所以这里通常只差一个弹层；
                    // 万一手快、切还没发生，先把分类补切过去再开。
                    if (!catMode || it.sortIndex != catIndex) showCatPage(it.sortIndex, false);
                    openFilterFor(it.sortIndex);
                }
            });
            // 和顶栏一套规矩：焦点停住就切，划过不算
            item.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) scheduleCatSwitch(it.sortIndex);
                    else cancelCatSwitch();
                }
            });
            subNavBox.addView(item);
            subNavViews.add(item);
        }
        // 分类比能放下的多 → 末尾一个「更多」，点开还是原来那个完整分类弹层
        if (hasMoreCats) {
            View more = inflater.inflate(R.layout.item_nav_sub, subNavBox, false);
            more.setId(View.generateViewId());
            ((TextView) more.findViewById(R.id.tvSubNav)).setText(getString(R.string.v8_nav_more));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
            lp.setMarginStart(mm(SUB_GAP_MM));
            more.setLayoutParams(lp);
            more.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showMoreCats();
                }
            });
            subNavBox.addView(more);
        }
        syncSubSelected();
    }

    /** 二级菜单的选中态：当前分类那一格亮描边 */
    private void syncSubSelected() {
        for (int i = 0; i < subNavViews.size() && i < catTabs.size(); i++) {
            subNavViews.get(i).setSelected(catTabs.get(i).sortIndex == catIndex);
        }
    }

    /** 二级菜单是否正显示着（只有影视页会显示） */
    private boolean subNavShowing() {
        return catMode && subNavBox != null
                && subNavBox.getVisibility() == View.VISIBLE
                && !subNavViews.isEmpty();
    }

    /** 焦点送到二级菜单里当前分类那一格 */
    private void focusSubNavCurrent() {
        if (subNavViews.isEmpty()) return;
        int idx = 0;
        for (int i = 0; i < catTabs.size(); i++) {
            if (catTabs.get(i).sortIndex == catIndex) {
                idx = i;
                break;
            }
        }
        final View target = subNavViews.get(Math.min(idx, subNavViews.size() - 1));
        // 刚从推荐页切过来时二级菜单是这一帧才可见的，直接 requestFocus 会失败
        if (!target.requestFocus()) {
            target.post(new Runnable() {
                @Override
                public void run() {
                    target.requestFocus();
                }
            });
        }
    }

    /** 焦点停在某个分类上 220ms 才真去切，手快划过一排不会连着刷 */
    private void scheduleCatSwitch(final int sortIndex) {
        cancelCatSwitch();
        if (sortIndex == catIndex && catMode) return;
        pendingCatSwitch = new Runnable() {
            @Override
            public void run() {
                pendingCatSwitch = null;
                if (catMode) showCatPage(sortIndex, false);
            }
        };
        mHandler.postDelayed(pendingCatSwitch, SUB_SWITCH_DELAY);
    }

    private void cancelCatSwitch() {
        if (pendingCatSwitch != null) {
            mHandler.removeCallbacks(pendingCatSwitch);
            pendingCatSwitch = null;
        }
    }

    /**
     * 打开某个分类的筛选面板。
     *
     * 为什么延后一帧：刚从别的分类切过来时，ViewPager 的 Fragment 这一帧才
     * attach，mContext 还没赋值，直接 new Dialog 会 NPE。
     * 180ms 在电视上感知不到，但足够它 attach 完。
     */
    private void openFilterFor(final int sortIndex) {
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!catMode) return;
                BaseLazyFragment f = fragmentAt(sortIndex);
                if (f instanceof GridFragment) ((GridFragment) f).showFilter();
            }
        }, 180);
    }

    /**
     * 影视页底部那行提示。
     *
     * 筛选 chip 撤掉之后，光看海报墙不知道「现在这本片子是被筛过的」——
     * 用户筛了 2024 年的电影，过一会儿回来只会以为这个站内容少。
     * 所以有条件时把条件写进提示行：一眼知道为什么只有这些，也知道去哪改。
     */
    private void updateCatHint() {
        if (!catMode) return;
        String sum = filterSummary(sortAt(catIndex));
        hintBar.setText(TextUtils.isEmpty(sum)
                ? getString(R.string.v8_hint_grid)
                : getString(R.string.v8_hint_filter, sum));
    }

    /** 当前分类已生效的筛选条件 → 「年份 2024 · 地区 内地」 */
    private String filterSummary(MovieSort.SortData sort) {
        if (sort == null || sort.filters == null || sort.filterSelect == null
                || sort.filterSelect.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (MovieSort.SortFilter f : sort.filters) {
            if (f == null || f.values == null) continue;
            String v = sort.filterSelect.get(f.key);
            if (TextUtils.isEmpty(v)) continue;
            // 提交值是 value，屏上要显示的是 key（值可能是一串看不出含义的 id）
            String show = v;
            for (java.util.Map.Entry<String, String> e : f.values.entrySet()) {
                if (v.equals(e.getValue())) {
                    show = e.getKey();
                    break;
                }
            }
            if (sb.length() > 0) sb.append(" · ");
            sb.append(TextUtils.isEmpty(f.name) ? "" : f.name).append(" ").append(show);
            if (sb.length() > 48) break;
        }
        return sb.toString();
    }

    /** 进「影视」时落在哪个分类：上次停的那个，没有就第一个 */
    private int entryCatIndex() {
        for (NavItem it : catTabs) {
            if (it.sortIndex == catIndex) return catIndex;
        }
        if (!catTabs.isEmpty()) return catTabs.get(0).sortIndex;
        return Math.max(0, firstRealSortIndex());
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
            else if (it.type == NT_FILM) sel = catMode;
            else if (it.type == NT_CAT) sel = catMode && it.sortIndex == catIndex;
            else if (it.type == NT_MORE) sel = catMode && !catInBar;
            else if (it.type == NT_HIST) sel = histMode;
            navViews.get(i).setSelected(sel);
        }
        syncSubSelected();
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
        if (i >= 0 && i < navViews.size()) {
            // 焦点离开封面墙就必须把 hero 还原：开 App 那一瞬间系统会把焦点先丢给
            // 第一个能聚焦的 View（就是海报墙），触发一次 enterBrowse；顶栏马上把焦点
            // 抢回来，但没人负责把 hero 放回去 —— 结果就是首屏没有 hero、墙顶在屏顶。
            if (browseMode) exitBrowse();
            navViews.get(i).requestFocus();
        }
    }

    /** 回到当前页面对应的顶栏项（分类页一律回「影视」，分类本身在二级菜单里） */
    private void focusNavOfCurrent() {
        if (catMode) {
            focusNavType(NT_FILM);
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
                    case NT_FILM:
                        // 焦点停在「影视」上：落到上次那个分类，二级菜单跟着这一页出现
                        showCatPage(entryCatIndex(), false);
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
            case NT_FILM:
                // 按 OK：进影视页并把焦点交给二级菜单，让用户先挑分类
                showCatPage(entryCatIndex(), false);
                focusSubNavCurrent();
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

    /** 焦点交给当前页面的封面墙（推荐 / 影视 / 历史通用） */
    private void focusPosterWall() {
        final RecyclerView rv = currentGrid();
        if (rv == null) return;
        rv.post(new Runnable() {
            @Override
            public void run() {
                View first = rv.getLayoutManager() == null ? null
                        : rv.getLayoutManager().findViewByPosition(0);
                if (first != null) first.requestFocus();
                else rv.requestFocus();
            }
        });
    }

    // =====================================================================
    //  推荐页：竖向封面墙（v11）
    // =====================================================================

    /**
     * 推荐页 =「hero + 一面竖向封面墙」。
     *
     * 以前这里是「一行大卡横滑」：按 ↓ 走不出那一行 —— 下面本来就没有第二行，
     * 用户看到的就是「滑不下去」。现在换成和影视页同一面 5 列墙：
     * 焦点往下一行走一行，走到倒数第一行就把下一页取回来接着长。
     *
     * 数据先铺站点首页推荐（absXml.videoList，进 App 时已在内存里，秒出）；
     * 不够一屏就按第一个真实分类翻页往后补 —— 站点推荐位通常只有一页，
     * 「一直往下滑」的增量只能来自分类翻页。
     */
    private void initRecWall() {
        recAdapter = new HomeLineAdapter(new HomeLineAdapter.Listener() {
            @Override
            public void onPosterClick(Movie.Video video, int position) {
                openDetail(video);
            }

            @Override
            public void onPosterFocus(Movie.Video video, int position) {
                if (!onRecPage()) return;
                if (!browseMode) enterBrowse();
                setHero(video, true, getString(R.string.home_hero_tag_hot));
            }

            @Override
            public void onMoreClick() {
            }
        });
        recAdapter.setGridMode(true);
        recGrid.setLayoutManager(new V7GridLayoutManager(this, WALL_SPAN));
        recGrid.setSpacingWithMargins(mm(6f), mm(6.5f));
        recGrid.setAdapter(recAdapter);
        recGrid.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                if (itemView != null) itemView.setAlpha(1f);
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                // 电视是方向键移焦点，不产生滚动事件，只能按焦点位置判断「快到尾巴了」
                maybeLoadRecMore(position);
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
            }
        });
        // 焦点一旦离开墙面（回顶栏、去片子源胶囊），hero 就得回来，
        // 否则会停在「hero 已淡出、墙顶在 96mm」的浏览态，看着像首屏缺了一半
        recGrid.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus && browseMode) exitBrowse();
            }
        });
    }

    /** 重建封面墙（换源、刷新首页都会走这里） */
    private void buildRecWall() {
        recItems.clear();
        if (homeLoadStartAt == 0L) homeLoadStartAt = System.currentTimeMillis();
        recPage = 1;
        recPageCount = 0;
        recPageLoading = false;
        recNoMore = false;
        if (recSort == null) recSort = firstFillableSort();

        // ---- 1. 站点首页推荐（已经在内存里，先秒出这一批） ----
        Set<String> seen = new HashSet<>();
        for (Movie.Video v : homeRecommend) {
            if (v == null || TextUtils.isEmpty(v.id)) continue;
            if (seen.add(videoKey(v))) recItems.add(v);
        }
        recAdapter.setData(recItems, false, null);
        if (onRecPage() && !recItems.isEmpty()) setHero(recItems.get(0), false);

        // ---- 2. 不够一屏（或者压根没有推荐位）→ 从第一个真实分类补一页 ----
        if (recItems.size() >= REC_FILL_MIN) return;
        if (recSort == null) {
            if (!heroNone) paintHeroIdle();
            return;
        }
        fetchRecMore(true);

        // 源站慢的时候降级链能拖上半分钟。用「从第一次开始拉」算的绝对截止时间，
        // 重建多少次都还是同一个点给结论，别让用户对着「正在读取」干等。
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!recItems.isEmpty() || !onRecPage()) return;
                paintHeroNone();
                hintBar.setText(R.string.v8_hint_home_none);
            }
        }, HOME_LOAD_DEADLINE);
    }

    /**
     * 取一页补到墙尾。
     *
     * @param initial true  = 首屏补足，固定取第 1 页（不推进 recPage）；
     *                false = 焦点滑到尾巴了，取下一页
     */
    private void fetchRecMore(final boolean initial) {
        if (recSort == null || recPageLoading || recNoMore) return;
        final int page = initial ? 1 : recPage;
        if (!initial && recPageCount > 0 && page > recPageCount) {
            recNoMore = true;
            return;
        }
        recPageLoading = true;
        sourceViewModel.getList(recSort, page, new SourceViewModel.ListCallback() {
            @Override
            public void done(final AbsXml absXml) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        recPageLoading = false;
                        List<Movie.Video> list = (absXml != null && absXml.movie != null)
                                ? absXml.movie.videoList : null;
                        if (list == null || list.isEmpty()) {
                            recNoMore = true;
                            if (recItems.isEmpty() && onRecPage()) {
                                paintHeroNone();
                                hintBar.setText(R.string.v8_hint_home_none);
                            }
                            return;
                        }
                        if (absXml.movie.pagecount > 0) recPageCount = absXml.movie.pagecount;
                        if (!initial) {
                            recPage = page + 1;
                            if (recPageCount > 0 && recPage > recPageCount) recNoMore = true;
                        }

                        Set<String> seen = new HashSet<>();
                        for (Movie.Video v : recItems) seen.add(videoKey(v));
                        int before = recItems.size();
                        for (Movie.Video v : list) {
                            if (v == null || TextUtils.isEmpty(v.id)) continue;
                            if (!seen.add(videoKey(v))) continue;
                            recItems.add(v);
                        }
                        // 整页都是墙上已有的（站点推荐位和分类第一页经常重合）：
                        // 不当成「到底」，但没带来新东西就别刷一遍列表
                        if (recItems.size() == before) return;
                        recAdapter.setData(new ArrayList<>(recItems), false, null);
                        if (onRecPage() && currentHero == null) setHero(recItems.get(0), true);
                    }
                });
            }
        });
    }

    /** 焦点走到倒数第一行 → 提前取下一页，视觉上就是「一直有内容往下走」 */
    private void maybeLoadRecMore(int position) {
        if (catMode || histMode || recAdapter == null) return;
        int total = recItems.size();
        if (total <= 0) return;
        if (position < total - WALL_SPAN) return;
        fetchRecMore(false);
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
    //  首屏 ↔ 浏览态（焦点进墙就让 hero 让位）
    // =====================================================================

    /**
     * 焦点进墙：hero 和按键提示淡出，墙整体上推到 96mm，一屏多露半行。
     *
     * 「影视」页不走这套 —— 那一页压根没有 hero（用户要的是直接看封面），
     * 二级菜单也留着：它同时是分类选择器，藏起来用户不知道自己在哪一档。
     */
    private void enterBrowse() {
        if (browseMode || catMode) return;
        browseMode = true;
        heroInfo.animate().alpha(0f).translationY(-mm(36f)).setDuration(260).start();
        heroActions.animate().alpha(0f).translationY(-mm(36f)).setDuration(260).start();
        heroActions.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (browseMode) heroActions.setVisibility(View.INVISIBLE);
            }
        }, 260);
        // 进了墙就不需要按键提示了，留着只会压在卡片片名上
        hintBar.animate().alpha(0f).setDuration(200).start();
        layoutCat(true);
    }

    private void exitBrowse() {
        if (!browseMode) return;
        browseMode = false;
        // 没有影片时这两个按钮是无对象的，露出来只会让人点了没反应
        heroActions.setVisibility(currentHero == null ? View.GONE : View.VISIBLE);
        heroInfo.animate().alpha(1f).translationY(0f).setDuration(300).start();
        heroActions.animate().alpha(1f).translationY(0f).setDuration(300).start();
        hintBar.animate().alpha(1f).setDuration(220).start();
        layoutCat(true);
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

    // =====================================================================
    //  hero（背景 + 文案）
    // =====================================================================

    // =====================================================================
    //  hero 片名字号：自适应
    //
    //  先记一条实测结论（1080p TV 模拟器上量到的）：本项目 mm 不是物理毫米，
    //  AutoSize 在 Subunits.MM 模式会把 DisplayMetrics.xdpi 覆盖成"设计稿口径"，
    //  使得 1mm ≈ 屏幕宽/1217.6 px（1080p 上约 1.58px）。所以 38mm 渲染出来是 60px，
    //  而不是物理的 479px。好处是分辨率一变（4K）整套 UI 自动等比放大；
    //  坏处是"屏幕物理尺寸"这个信息在 App 里已经拿不到了 —— 别再去读 xdpi 算物理宽
    //  （盒子和模拟器上报的 xdpi 大量是假的，1080p TV 模拟器报 320dpi = 6 寸）。
    //
    //  所以这里只做两件真能生效的事：
    //    1) 短标题（≤5 字）放大一档：42mm → 46mm，视觉上更大气
    //    2) 长标题按实际可用宽度逐 0.5mm 收回，直到这一行放得下（下限 26mm）
    //  「随屏幕分辨率自适应」由 1mm 的归一关系天然给出，不需要额外乘系数。
    // =====================================================================

    /** 常规片名字号（1080p 上约 66px） */
    private static final float HERO_TITLE_BASE_MM = 42f;
    /** 短片名（≤5 字）再大一档，避免"四两拨千斤"式的空荡 */
    private static final float HERO_TITLE_SHORT_MM = 46f;
    /** 超过这个字数就不再额外加成 */
    private static final int HERO_TITLE_SHORT_LEN = 5;
    /** 缩放下限：再小就看不清了，宁可让它 ellipsize */
    private static final float HERO_TITLE_MIN_MM = 26f;
    /** hero 文案列宽（dimens_v8 的 v8_hero_w），布局还没量好时兜底用 */
    private static final float HERO_INFO_W_MM = 560f;

    private void setHeroTitle(CharSequence text) {
        if (tvHeroTitle == null) return;
        String txt = text == null ? "" : text.toString().trim();
        tvHeroTitle.setText(txt);
        fitHeroTitle(txt);
    }

    private void fitHeroTitle(final String txt) {
        if (tvHeroTitle == null) return;
        tvHeroTitle.post(new Runnable() {
            @Override
            public void run() {
                // 连切几张卡时会有多个 post 排队，只处理"当前真正显示的那一条"
                if (tvHeroTitle == null) return;
                CharSequence now = tvHeroTitle.getText();
                if (now == null || !txt.equals(now.toString().trim())) return;
                applyHeroTitleSize(txt);
            }
        });
    }

    private void applyHeroTitleSize(String txt) {
        final TextView tv = tvHeroTitle;
        if (tv == null) return;
        if (TextUtils.isEmpty(txt)) {
            tv.setTextSize(TypedValue.COMPLEX_UNIT_MM, HERO_TITLE_BASE_MM);
            return;
        }

        // 可用宽度：优先取实测列宽（布局可能比 560mm 窄），再受屏幕宽度约束
        int avail = tv.getWidth() - tv.getPaddingLeft() - tv.getPaddingRight();
        if (avail <= 0) avail = AutoSizeUtils.mm2px(this, HERO_INFO_W_MM);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int edge = AutoSizeUtils.mm2px(this, 45.6f);   // dimens_home 的 h_edge
        if (screenW - edge * 2 < avail) avail = screenW - edge * 2;
        if (avail <= 0) return;

        float maxMm = txt.length() <= HERO_TITLE_SHORT_LEN
                ? HERO_TITLE_SHORT_MM : HERO_TITLE_BASE_MM;
        float sizePx = AutoSizeUtils.mm2px(this, maxMm);
        float minPx = AutoSizeUtils.mm2px(this, HERO_TITLE_MIN_MM);
        float stepPx = Math.max(1f, AutoSizeUtils.mm2px(this, 0.5f));

        TextPaint probe = new TextPaint(tv.getPaint());
        probe.setTypeface(tv.getTypeface());
        while (sizePx > minPx) {
            probe.setTextSize(sizePx);
            if (probe.measureText(txt) <= avail) break;
            sizePx -= stepPx;
        }
        if (sizePx < minPx) sizePx = minPx;
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx);
        android.util.Log.i("HeroFit", "title=" + txt + " len=" + txt.length()
                + " maxMm=" + maxMm + " avail=" + avail + " sizePx=" + sizePx
                + " viewW=" + tv.getWidth() + " screenW=" + screenW);
    }

    private void setHero(Movie.Video v, boolean animate) {
        setHero(v, animate, null);
    }

    private void setHero(Movie.Video v, boolean animate, String tag) {
        if (v == null) return;
        // 同一部片不重复淡入，但标签要跟着页面走（推荐页写「热门」，历史页写「上次看到」）
        if (tag != null) tvHeroTag.setText(tag);
        if (v == currentHero) return;
        currentHero = v;
        heroNone = false;
        // 影视页把 hero 整块藏了；浏览态（焦点在墙里）hero 按钮已经淡出，
        // 这两处都别把按钮又露出来 —— 否则会在卡片上盖一颗「立即播放」的幽灵
        if (catMode) {
            heroActions.setVisibility(View.GONE);
        } else if (!browseMode) {
            heroActions.setVisibility(View.VISIBLE);
        }
        heroActions.setAlpha(1f);

        setHeroTitle(v.name);
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
        setHeroTitle(getString(R.string.home_app_home));
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
        setHeroTitle("");
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
        setHeroTitle(getString(R.string.v8_hero_none_title));
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

    /** 推荐页第一张卡：hero 两个按钮在没拿到焦点时按它走 */
    private Movie.Video firstVideoOfLines() {
        if (!recItems.isEmpty()) return recItems.get(0);
        return currentHero;
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
     * 切到某个分类页（影视）。
     *
     * v11 起这一页没有 hero：片名 / 评分 / 简介 / 「立即播放 + 收藏」整块都撤了，
     * 二级菜单下面直接就是封面墙（用户明确要求「直接展示视频的封面」）。
     * 背景剧照还留着 —— 换分类时整页背景跟着换，比纯黑底有生气。
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
        // 从推荐页 / 历史页进来才淡入；在影视页里左右切分类不该整页闪一下
        boolean fromOtherPage = !catMode;
        histMode = false;
        catMode = true;
        browseMode = false;
        histLayout.setVisibility(View.GONE);
        recLayout.setVisibility(View.GONE);
        catEmpty.setVisibility(View.GONE);
        contentLayout.setVisibility(View.VISIBLE);
        heroInfo.setVisibility(View.GONE);
        heroActions.setVisibility(View.GONE);
        // 影视页才有的二级菜单
        if (catTabs.isEmpty()) {
            subNavBox.setVisibility(View.GONE);
        } else {
            subNavBox.setAlpha(1f);
            subNavBox.setVisibility(View.VISIBLE);
        }
        hintBar.setVisibility(View.VISIBLE);
        hintBar.setAlpha(1f);
        bottomFade.setVisibility(View.VISIBLE);
        setPlayLabel(false);

        catIndex = Math.max(0, Math.min(index, sorts.size() - 1));
        switchCat(catIndex, false);
        // 切完分类才知道这个分类有没有生效中的筛选条件，提示行放在这之后写
        updateCatHint();
        layoutCat(false);
        if (fromOtherPage) fadeIn(contentLayout);
        if (takeFocus) focusPosterWall();
    }

    /** 站点只给了「首页推荐」、没有任何真实分类：停在空白上只会让人以为加载挂了 */
    private void showCatEmpty() {
        catMode = true;
        histMode = false;
        browseMode = false;
        contentLayout.setVisibility(View.GONE);
        histLayout.setVisibility(View.GONE);
        recLayout.setVisibility(View.GONE);
        heroInfo.setVisibility(View.GONE);
        heroActions.setVisibility(View.GONE);
        subNavBox.setVisibility(View.GONE);
        catEmpty.setVisibility(View.VISIBLE);
        hintBar.setVisibility(View.GONE);
        syncNavSelected();
    }

    /**
     * 回推荐页。
     * 封面墙一直在 recGrid 里没动过，切回来只是把几层换回去，不重新请求。
     */
    private void showRecPage() {
        histMode = false;
        catMode = false;
        browseMode = false;
        contentLayout.setVisibility(View.GONE);
        histLayout.setVisibility(View.GONE);
        catEmpty.setVisibility(View.GONE);
        subNavBox.setVisibility(View.GONE);
        recLayout.setVisibility(View.VISIBLE);
        heroInfo.setVisibility(View.VISIBLE);
        heroInfo.setAlpha(1f);
        heroInfo.setTranslationY(0);
        heroActions.setAlpha(1f);
        heroActions.setTranslationY(0);
        heroActions.setVisibility(currentHero == null ? View.GONE : View.VISIBLE);
        hintBar.setVisibility(View.VISIBLE);
        hintBar.setText(R.string.v8_hint_rec);
        hintBar.setAlpha(1f);
        bottomFade.setVisibility(View.VISIBLE);
        setPlayLabel(false);

        Movie.Video first = firstVideoOfLines();
        if (first != null) {
            setHero(first, true, getString(R.string.home_hero_tag_hot));
        } else if (!heroNone) {
            paintHeroIdle();
        }
        syncNavSelected();
        layoutCat(false);
    }

    /**
     * 历史页。
     *
     * 和推荐页同一个骨架：上面 hero（上次看到的那部）、下面同一面 5 列封面墙。
     * 数据全在本地库，切过来不用等网络，所以它也吃「焦点停一下就切」。
     */
    private void showHistoryPage(boolean takeFocus) {
        histMode = true;
        catMode = false;
        browseMode = false;
        contentLayout.setVisibility(View.GONE);
        catEmpty.setVisibility(View.GONE);
        subNavBox.setVisibility(View.GONE);
        histLayout.setVisibility(View.VISIBLE);
        recLayout.setVisibility(View.GONE);
        // 从影视页切过来时别把上一页的状态带进来
        heroInfo.setTranslationY(0);
        heroActions.setTranslationY(0);
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

    // ---------------- 三个页面的墙位置（首屏 ↔ 浏览） ----------------

    /**
     * 墙上边距，三个页面共用一个函数：
     * <pre>
     *   影视（没有 hero） → 固定在二级菜单下沿（114mm），焦点进出都不动
     *   推荐 / 历史       → 首屏 240mm（hero 在上面），焦点进墙后 96mm
     * </pre>
     *
     * 用 topMargin 而不是 translationY：translationY 只是把整个控件挪走，
     * 控件高度不变，上移之后底部会空出一块、最后一行永远滚不出来。
     */
    private void layoutCat(boolean animate) {
        if (!launcherReady) return;
        int target;
        if (catMode) {
            target = mm(MM_WALL_TOP_CAT);
        } else if (browseMode) {
            target = mm(MM_LINE_TOP_MIN);
        } else {
            target = mm(MM_LINE_TOP_FIRST);
        }
        applyTopMargin(mViewPager, target, animate);
        applyTopMargin(histGrid, target, animate);
        applyTopMargin(recGrid, target, animate);
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
                    // 顶栏只剩「影视」，选完回它那一格（分类在二级菜单里，看得见选中态）
                    focusNavType(NT_FILM);
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

    /** 当前页面正在显示的那个网格（推荐 / 历史各一个 RV，影视在 ViewPager 里） */
    private RecyclerView currentGrid() {
        if (histMode) return histGrid;
        if (!catMode) return recGrid;
        // ① 焦点已经在网格里：顺着父链找最准（LoadSir 会套一层，层级不固定）
        View f = getCurrentFocus();
        while (f != null) {
            ViewParent p = f.getParent();
            if (p instanceof RecyclerView) return (RecyclerView) p;
            f = (p instanceof View) ? (View) p : null;
        }
        // ② 焦点还在顶栏 / hero：直接问分类页的 Fragment 要。
        //    以前是用 mViewPager.getChildAt(getCurrentItem()) 扫直接子 View，
        //    实测拿到的是一个 kids=0 的空 FrameLayout（page 缓存/复用时索引对不上），
        //    所以一直返回 null → inGrid 判成 false → 分类页按 ↓ 被当成推荐页的下移处理，
        //    焦点没动、按键又被吃掉，表现就是「永远只有第一页 10 部，按下键毫无反应」。
        BaseLazyFragment bf = fragmentAt(mViewPager.getCurrentItem());
        if (bf instanceof GridFragment) return ((GridFragment) bf).gridRecycler();
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
     * 网格里上下移一行（分类页 / 历史墙）。
     *
     * 不用 RecyclerView 自带的 focusSearch：TvRecyclerView 的几何搜索在
     * 「下一行只露出半截」的时候经常返回 null，焦点就卡在原地不动，
     * 而 Activity 那边又把按键消费掉了 —— 表现就是「按下键完全没反应」。
     * 这里按 position ± 列数直接定位，跟屏幕露出多少无关。
     *
     * @return true 表示焦点已经移过去了
     */
    private boolean gridMoveRow(RecyclerView rv, boolean up) {
        if (rv == null) return false;
        int pos = gridPosOf(rv, getCurrentFocus());
        if (pos == RecyclerView.NO_POSITION) return false;
        RecyclerView.Adapter a = rv.getAdapter();
        if (a == null) return false;
        int count = a.getItemCount();
        // BaseQuickAdapter 的 loadMoreView(footer) 也占一个 position，别把焦点送进去
        if (a instanceof BaseQuickAdapter) {
            try {
                count = ((BaseQuickAdapter) a).getData().size();
            } catch (Throwable ignored) {
            }
        }
        int np = pos + (up ? -spanOf(rv) : spanOf(rv));
        if (np < 0 || np >= count) return false;
        return focusGridPos(rv, np);
    }

    /**
     * 焦点所在的格子是第几个。
     *
     * 坑：getChildAdapterPosition 只认 RecyclerView 的**直接子 View**，
     * 但焦点常常落在卡片内部的子 View 上（这时强行传进去会抛
     * ClassCastException: LinearLayout$LayoutParams → RecyclerView$LayoutParams）。
     * 所以先顺着父链爬到 rv 的直接子 View。
     */
    private int gridPosOf(RecyclerView rv, View f) {
        View v = f;
        while (v != null) {
            ViewParent p = v.getParent();
            if (p == rv) return rv.getChildAdapterPosition(v);
            v = (p instanceof View) ? (View) p : null;
        }
        return RecyclerView.NO_POSITION;
    }

    private int spanOf(RecyclerView rv) {
        RecyclerView.LayoutManager lm = rv.getLayoutManager();
        if (lm instanceof GridLayoutManager) return ((GridLayoutManager) lm).getSpanCount();
        return 1;
    }

    /** 把焦点送到网格第 pos 格。目标格还没渲染就先滚过去再取，取不到下一帧再试。 */
    private boolean focusGridPos(final RecyclerView rv, final int pos) {
        final RecyclerView.LayoutManager lm = rv.getLayoutManager();
        if (lm == null) return false;
        View target = lm.findViewByPosition(pos);
        if (target == null) {
            rv.scrollToPosition(pos);
            target = lm.findViewByPosition(pos);
        }
        if (target != null) return target.requestFocus();
        rv.post(new Runnable() {
            @Override
            public void run() {
                View t = lm.findViewByPosition(pos);
                if (t != null) t.requestFocus();
            }
        });
        return true;
    }

    /**
     * 焦点是不是落在墙里的某张卡上（而不是 RecyclerView 自己）。
     *
     * 分开判断是必须的：焦点挂在 RV 上时，position 无从得知，硬按 ±列数 去跳
     * 只会算错；这时候把按键还给 TvRecyclerView，它按自己的选中项走，
     * 顺带把焦点落回子项上（自愈）。
     */
    private boolean focusOnCard(RecyclerView rv) {
        View f = getCurrentFocus();
        if (f == null || rv == null || f == rv) return false;
        ViewParent p = f.getParent();
        while (p != null) {
            if (p == rv) return true;
            p = p.getParent();
        }
        return false;
    }

    /**
     * 上下键统一在这里接管。
     * 返回 true 表示已经处理，事件不再往下传（否则 RecyclerView 的
     * focusSearch 会自己找一个「看起来在下面」的 View，跳到隔壁行中间）。
     */
    private boolean handleVerticalKey(boolean up) {
        return handleVerticalInPage(up);
    }

    /**
     * 页面内上下：顶栏 → 二级菜单 → hero 按钮 → 墙，反向对称。
     *
     *   推荐 / 历史：顶栏 ↓ → hero 按钮 → 墙；墙第一行 ↑ → hero 按钮 → 顶栏
     *   影视（无 hero）：顶栏 ↓ → 二级菜单 → 墙；墙第一行 ↑ → 二级菜单 → 顶栏
     *
     * 墙里用 gridMoveRow 按 position ± 列数定位，不靠几何 focusSearch
     * （下一行只露半截时光学搜索经常返回 null，焦点就卡在原地不动）。
     */
    private boolean handleVerticalInPage(boolean up) {
        RecyclerView rv = currentGrid();
        boolean inGrid = rv != null && isInside(rv);
        boolean inSub = subNavShowing() && isInside(subNavBox);
        boolean onHeroBtn = isInside(btnPlay) || isInside(btnFav);
        // 影视页把 hero 整块藏了：按钮还在视图树里，但看不见就不能算一层台阶
        boolean heroStep = !catMode && currentHero != null
                && heroActions.getVisibility() == View.VISIBLE;

        if (up) {
            if (inGrid) {
                if (gridMoveRow(rv, true)) return true;
                // 已经在墙的第一行，退回上一层
                exitBrowse();
                if (heroStep) {
                    btnPlay.requestFocus();
                    return true;
                }
                if (subNavShowing()) {
                    focusSubNavCurrent();
                    return true;
                }
                focusNavOfCurrent();
                return true;
            }
            if (onHeroBtn) {
                exitBrowse();
                if (subNavShowing()) {
                    focusSubNavCurrent();
                    return true;
                }
                focusNavOfCurrent();
                return true;
            }
            if (inSub) {
                focusNavType(NT_FILM);
                return true;
            }
            // 焦点在顶栏：往上没有更上层了，别吃键（左右交给系统）
            return false;
        }

        // ---------------- ↓ ----------------
        if (inGrid) {
            if (gridMoveRow(rv, false)) return true;
            // 焦点挂在 RV 自己身上（滚动时那张卡被回收了）：别吃键，
            // 交回 TvRecyclerView 自己走一格，它会把焦点落回子项上
            if (!focusOnCard(rv)) return false;
            // 已经在最后一行：推荐页顺手把下一页取回来，视觉上就是「还能一直往下滑」
            if (!catMode && !histMode) fetchRecMore(false);
            return true;
        }
        if (inSub) {
            if (heroStep) {
                btnPlay.requestFocus();
                return true;
            }
            focusPosterWall();
            enterBrowse();
            return true;
        }
        if (onHeroBtn) {
            focusPosterWall();
            enterBrowse();
            return true;
        }
        // 焦点在顶栏
        if (subNavShowing()) {
            focusSubNavCurrent();
            return true;
        }
        if (heroStep) {
            btnPlay.requestFocus();
            return true;
        }
        if (histMode && histVideos.isEmpty()) return true;
        focusPosterWall();
        enterBrowse();
        return true;
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
        setHeroTitle(getString(R.string.home_app_home));
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

        // 分类变了，推荐墙翻页用的分类也要重新挑一个
        recSort = null;
        buildRecWall();

        // 从二级页面顶栏点「分类」过来的，分类这就绪了，直接切过去
        openCatIfReady();

        // 首次拿到分类后把焦点交给顶栏「推荐」，用户一按 ↓ 就能进封面墙。
        //
        // 这里不能再判 browseMode：开 App 的头几帧，系统会把焦点丢给第一个能聚焦的
        // View（就是刚刚铺好的海报墙），enterBrowse 已经把 hero 淡出了 —— 如果因为
        // browseMode=true 就跳过这一步，首屏会永远停在「没有 hero、墙顶在屏顶」的半残状态。
        // focusNavType 会把 hero 顺手还原，所以直接调就行。
        navBox.post(new Runnable() {
            @Override
            public void run() {
                if (!catMode && !histMode) {
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
        } else if (event.type == RefreshEvent.TYPE_FILTER_CHANGE) {
            // GridFragment 每次重拉第一页都会报一次条件条数（含 0），
            // 正好拿来刷新底部提示 —— 筛选 chip 撤掉之后，这里是唯一的条件回显
            updateCatHint();
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
