package com.pigtv.ui.activity;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.constraintlayout.widget.ConstraintLayout;
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
import com.pigtv.event.RefreshEvent;
import com.pigtv.server.ControlManager;
import com.pigtv.ui.adapter.HomeAppAdapter;
import com.pigtv.ui.adapter.HomePageAdapter;
import com.pigtv.ui.adapter.SelectDialogAdapter;
import com.pigtv.ui.adapter.SortAdapter;
import com.pigtv.ui.dialog.SelectDialog;
import com.pigtv.ui.dialog.TipDialog;
import com.pigtv.ui.fragment.GridFragment;
import com.pigtv.ui.fragment.UserFragment;
import com.pigtv.ui.tv.widget.DefaultTransformer;
import com.pigtv.ui.tv.widget.FixedSpeedScroller;
import com.pigtv.ui.tv.widget.NoScrollViewPager;
import com.pigtv.util.AppManager;
import com.pigtv.util.DefaultConfig;
import com.pigtv.util.FileUtils;
import com.pigtv.util.HawkConfig;
import com.pigtv.util.ImgUtil;
import com.pigtv.util.LOG;
import com.pigtv.util.MD5;
import com.pigtv.viewmodel.SourceViewModel;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import me.jessyan.autosize.utils.AutoSizeUtils;

/**
 * 首页 v6.1
 *
 * 布局对应 ui-design/tv-home.html：上面是预览卡，下面是功能入口。
 *
 * 两层互斥：
 * launcherMode=true  启动器层（时钟 / 胶囊 / 预览卡 / 入口行）
 * launcherMode=false 二级页（分类 Tab + ViewPager 网格）
 *
 * 点入口卡进入二级页，返回键退回启动器；二级页内的分类切换仍走 mViewPager，
 * 每个分类对应一个 GridFragment，数据层与原版完全一致。
 */
public class HomeActivity extends BaseActivity {

    // takagen99: Added to allow read string
    private static Resources res;

    // ---------- 启动器层 ----------
    private View launcher;
    private LinearLayout contentLayout;
    private TextView tvGreet;
    private TextView tvTime;
    private TextView tvAmpm;
    private TextView tvDate;
    private LinearLayout pillSource;
    private LinearLayout pillConfig;
    private LinearLayout pillWifi;
    private TextView tvPillSource;
    private TextView tvNet;
    private TextView tvNetSub;
    private ImageView ivNet;
    private View vNetDot;
    private CardView hero;
    private ImageView heroArt;
    private ImageView heroPosterImg;
    private TextView heroPosterLetter;
    private TextView heroTag;
    private TextView heroTitle;
    private TextView heroMeta;
    private TextView heroDesc;
    private TextView heroHint;
    private TvRecyclerView appsRow;

    // ---------- 二级页 ----------
    private TvRecyclerView mGridView;
    private NoScrollViewPager mViewPager;

    private SourceViewModel sourceViewModel;
    private SortAdapter sortAdapter;
    private HomePageAdapter pageAdapter;
    private HomeAppAdapter appAdapter;
    private final List<BaseLazyFragment> fragments = new ArrayList<>();

    private boolean isDownOrUp = false;
    private boolean sortChange = false;
    private int currentSelected = 0;
    private int sortFocused = 0;
    public View sortFocusView = null;
    private final Handler mHandler = new Handler();
    private long mExitTime = 0;

    /** true=显示启动器；false=显示分类内容页 */
    private boolean launcherMode = true;
    /** 上次停留的入口下标，从二级页返回时回到原位 */
    private int lastAppIndex = 0;

    // ---------- 预览卡数据 ----------
    /** 分类 id → 该分类第一部的缓存，避免来回移动焦点时重复请求 */
    private final Map<String, Movie.Video> heroCache = new HashMap<>();
    /** 正在为哪个分类拉预览数据 */
    private MovieSort.SortData pendingHeroSort = null;
    private HomeAppAdapter.Entry currentHeroEntry = null;
    /** 入口行是否已经抢过一次焦点（避免数据刷新时把用户正在操作的焦点抢走） */
    private boolean appsFocusedOnce = false;

    private final Runnable mClockRunnable = new Runnable() {
        @Override
        public void run() {
            updateClock();
            mHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_home;
    }

    boolean useCacheConfig = false;

    @Override
    protected void init() {
        res = getResources();

        EventBus.getDefault().register(this);
        ControlManager.get().startServer();
        App.startWebserver();
        initView();
        initViewModel();
        useCacheConfig = false;
        Intent intent = getIntent();
        if (intent != null && intent.getExtras() != null) {
            Bundle bundle = intent.getExtras();
            useCacheConfig = bundle.getBoolean("useCache", false);
        }
        initData();
    }

    public static Resources getRes() {
        return res;
    }

    // =====================================================================
    //  初始化视图
    // =====================================================================

    private void initView() {
        launcher = findViewById(R.id.launcher);
        contentLayout = findViewById(R.id.contentLayout);

        tvGreet = findViewById(R.id.tvGreet);
        tvTime = findViewById(R.id.tvTime);
        tvAmpm = findViewById(R.id.tvAmpm);
        tvDate = findViewById(R.id.tvDate);

        pillSource = findViewById(R.id.pillSource);
        pillConfig = findViewById(R.id.pillConfig);
        pillWifi = findViewById(R.id.pillWifi);
        tvPillSource = findViewById(R.id.tvPillSource);
        tvNet = findViewById(R.id.tvNet);
        tvNetSub = findViewById(R.id.tvNetSub);
        ivNet = findViewById(R.id.ivNet);
        vNetDot = findViewById(R.id.vNetDot);

        hero = findViewById(R.id.hero);
        heroArt = findViewById(R.id.heroArt);
        heroPosterImg = findViewById(R.id.heroPosterImg);
        heroPosterLetter = findViewById(R.id.heroPosterLetter);
        heroTag = findViewById(R.id.heroTag);
        heroTitle = findViewById(R.id.heroTitle);
        heroMeta = findViewById(R.id.heroMeta);
        heroDesc = findViewById(R.id.heroDesc);
        heroHint = findViewById(R.id.heroHint);

        appsRow = findViewById(R.id.appsRow);
        mGridView = findViewById(R.id.mGridViewCategory);
        mViewPager = findViewById(R.id.mViewPager);

        initClock();
        initPills();
        initAppsRow();
        initGridView();

        setLoadSir(this.launcher);

        // 分类数据还没回来时入口行是空的，先把焦点放在胶囊上，避免整屏无焦点
        pillWifi.post(new Runnable() {
            @Override
            public void run() {
                if (!appsFocusedOnce) pillWifi.requestFocus();
            }
        });
    }

    private void initClock() {
        updateClock();
    }

    private void updateClock() {
        Calendar c = Calendar.getInstance();
        int hour = c.get(Calendar.HOUR_OF_DAY);
        int minute = c.get(Calendar.MINUTE);

        int hour12 = hour % 12;
        if (hour12 == 0) hour12 = 12;
        tvTime.setText(hour12 + ":" + (minute < 10 ? "0" + minute : String.valueOf(minute)));
        tvAmpm.setText(hour < 12 ? "上午" : "下午");

        if (hour >= 5 && hour < 11) {
            tvGreet.setText(R.string.home_greet_morning);
        } else if (hour >= 11 && hour < 13) {
            tvGreet.setText(R.string.home_greet_noon);
        } else if (hour >= 13 && hour < 18) {
            tvGreet.setText(R.string.home_greet_afternoon);
        } else {
            tvGreet.setText(R.string.home_greet_evening);
        }

        String[] weeks = {"日", "一", "二", "三", "四", "五", "六"};
        tvDate.setText((c.get(Calendar.MONTH) + 1) + "月" + c.get(Calendar.DAY_OF_MONTH) + "日 星期"
                + weeks[c.get(Calendar.DAY_OF_WEEK) - 1]);
    }

    private void initPills() {
        // 切换源：点按切源，长按刷新首页（承接原 tvName 的两个功能）
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

        pillWifi.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
                } catch (Exception ignored) {
                }
            }
        });

        // 胶囊向下 → 回到入口行
        View.OnKeyListener downToApps = new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    focusApps(lastAppIndex);
                    return true;
                }
                return false;
            }
        };
        pillSource.setOnKeyListener(downToApps);
        pillConfig.setOnKeyListener(downToApps);
        pillWifi.setOnKeyListener(downToApps);
    }

    private void initAppsRow() {
        appAdapter = new HomeAppAdapter(new HomeAppAdapter.OnItemClick() {
            @Override
            public void onItemClick(HomeAppAdapter.Entry entry, int position) {
                onAppClick(entry, position);
            }
        });
        appAdapter.setOnItemFocus(new HomeAppAdapter.OnItemFocus() {
            @Override
            public void onFocus(HomeAppAdapter.Entry entry, int position) {
                lastAppIndex = position;
                updateHero(entry);
            }
        });

        appsRow.setLayoutManager(new V7LinearLayoutManager(this, 0, false));
        appsRow.setSpacingWithMargins(AutoSizeUtils.mm2px(this, 15.2f), 0);
        appsRow.setAdapter(appAdapter);
        appsRow.setOnInBorderKeyEventListener(new TvRecyclerView.OnInBorderKeyEventListener() {
            @Override
            public boolean onInBorderKeyEvent(int direction, View view) {
                if (direction == View.FOCUS_UP) {
                    pillWifi.requestFocus();
                    return true;
                }
                return false;
            }
        });
    }

    private void initGridView() {
        this.sortAdapter = new SortAdapter();
        this.mGridView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 0, false));
        this.mGridView.setSpacingWithMargins(0, AutoSizeUtils.dp2px(this.mContext, 10.0f));
        this.mGridView.setAdapter(this.sortAdapter);
        sortAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                // 只在二级页抢焦点，否则会把启动器上的焦点抢走
                if (launcherMode) return;
                mGridView.post(new Runnable() {
                    @Override
                    public void run() {
                        View firstChild = Objects.requireNonNull(mGridView.getLayoutManager()).findViewByPosition(0);
                        if (firstChild != null) {
                            mGridView.setSelectedPosition(0);
                            firstChild.requestFocus();
                        }
                    }
                });
            }
        });
        this.mGridView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            public void onItemPreSelected(TvRecyclerView tvRecyclerView, View view, int position) {
                if (view != null && !HomeActivity.this.isDownOrUp) {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(250).start();
                    TextView textView = view.findViewById(R.id.tvTitle);
                    textView.getPaint().setFakeBoldText(false);
                    textView.setTextColor(HomeActivity.this.getResources().getColor(R.color.color_FFFFFF_70));
                    textView.invalidate();
                    view.findViewById(R.id.tvFilter).setVisibility(View.GONE);
                }
            }

            public void onItemSelected(TvRecyclerView tvRecyclerView, View view, int position) {
                if (view != null) {
                    HomeActivity.this.sortFocusView = view;
                    HomeActivity.this.isDownOrUp = false;
                    HomeActivity.this.sortChange = true;
                    view.animate().scaleX(1.1f).scaleY(1.1f).setDuration(250).start();
                    TextView textView = view.findViewById(R.id.tvTitle);
                    textView.getPaint().setFakeBoldText(true);
                    textView.setTextColor(HomeActivity.this.getResources().getColor(R.color.color_FFFFFF));
                    textView.invalidate();
                    if (position == -1) {
                        position = 0;
                        HomeActivity.this.mGridView.setSelection(0);
                    }
                    MovieSort.SortData sortData = sortAdapter.getItem(position);
                    if (null != sortData && !sortData.filters.isEmpty()) {
                        showFilterIcon(sortData.filterSelectCount());
                    }
                    HomeActivity.this.sortFocused = position;
                    mHandler.removeCallbacks(mDataRunnable);
                    mHandler.postDelayed(mDataRunnable, 200);
                }
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                if (itemView != null && currentSelected == position) {
                    BaseLazyFragment baseLazyFragment = fragments.get(currentSelected);
                    if ((baseLazyFragment instanceof GridFragment) && !sortAdapter.getItem(position).filters.isEmpty()) {
                        ((GridFragment) baseLazyFragment).showFilter();
                    } else if (baseLazyFragment instanceof UserFragment) {
                        showSiteSwitch();
                    }
                }
            }
        });
        this.mGridView.setOnInBorderKeyEventListener(new TvRecyclerView.OnInBorderKeyEventListener() {
            public boolean onInBorderKeyEvent(int direction, View view) {
                if (direction == View.FOCUS_UP) {
                    BaseLazyFragment baseLazyFragment = fragments.get(sortFocused);
                    if ((baseLazyFragment instanceof GridFragment)) {
                        ((GridFragment) baseLazyFragment).forceRefresh();
                    }
                }
                if (direction != View.FOCUS_DOWN) {
                    return false;
                }
                BaseLazyFragment baseLazyFragment = fragments.get(sortFocused);
                if (!(baseLazyFragment instanceof GridFragment)) {
                    return false;
                }
                return !((GridFragment) baseLazyFragment).isLoad();
            }
        });
    }

    // =====================================================================
    //  启动器 ↔ 二级页
    // =====================================================================

    private void focusApps(int index) {
        if (appsRow == null || appAdapter == null || appAdapter.size() == 0) return;
        final int pos = Math.max(0, Math.min(index, appAdapter.size() - 1));
        appsRow.post(new Runnable() {
            @Override
            public void run() {
                appsRow.setSelection(pos);
                if (appsRow.getLayoutManager() != null) {
                    View v = appsRow.getLayoutManager().findViewByPosition(pos);
                    if (v != null) {
                        v.requestFocus();
                        return;
                    }
                }
                appsRow.requestFocus();
            }
        });
    }

    private void showContent(int sortIndex) {
        launcherMode = false;
        launcher.setVisibility(View.GONE);
        contentLayout.setVisibility(View.VISIBLE);
        currentSelected = sortIndex;
        sortFocused = sortIndex;
        if (pageAdapter != null) mViewPager.setCurrentItem(sortIndex, false);

        mGridView.post(new Runnable() {
            @Override
            public void run() {
                mGridView.setSelection(sortFocused);
                if (mGridView.getLayoutManager() != null) {
                    View v = mGridView.getLayoutManager().findViewByPosition(sortFocused);
                    if (v != null) {
                        v.requestFocus();
                        return;
                    }
                }
                mGridView.requestFocus();
            }
        });
    }

    private void showLauncher() {
        launcherMode = true;
        contentLayout.setVisibility(View.GONE);
        launcher.setVisibility(View.VISIBLE);
        updateHero(appAdapter.getItem(lastAppIndex));
        focusApps(lastAppIndex);
    }

    // =====================================================================
    //  入口点击
    // =====================================================================

    private void onAppClick(HomeAppAdapter.Entry e, int position) {
        if (e == null) return;
        lastAppIndex = position;
        switch (e.kind) {
            case HomeAppAdapter.KIND_FUNC:
                if ("hist".equals(e.key)) {
                    jumpActivity(HistoryActivity.class);
                } else if ("fav".equals(e.key)) {
                    jumpActivity(CollectActivity.class);
                } else {
                    jumpActivity(SearchActivity.class);
                }
                break;
            case HomeAppAdapter.KIND_SORT:
                if (e.sortIndex >= 0 && e.sortIndex < fragments.size()) {
                    showContent(e.sortIndex);
                } else {
                    Toast.makeText(this, getString(R.string.home_hero_loading), Toast.LENGTH_SHORT).show();
                }
                break;
            case HomeAppAdapter.KIND_LIVE:
                jumpActivity(LivePlayActivity.class);
                break;
        }
    }

    // =====================================================================
    //  预览卡
    // =====================================================================

    private void updateHero(HomeAppAdapter.Entry e) {
        if (e == null) return;
        currentHeroEntry = e;
        if (e.kind == HomeAppAdapter.KIND_SORT && e.sort != null) {
            Movie.Video cached = heroCache.get(e.sort.id);
            if (cached != null) {
                paintHeroVideo(e, cached);
            } else {
                paintHeroLoading(e);
                requestHero(e.sort);
            }
            return;
        }
        paintHeroFunc(e);
    }

    private void requestHero(MovieSort.SortData sort) {
        if (sort == null || sourceViewModel == null) return;
        if (pendingHeroSort != null && sort.id != null && sort.id.equals(pendingHeroSort.id)) return;
        pendingHeroSort = sort;
        sourceViewModel.getList(sort, 1);
    }

    private void paintHeroVideo(HomeAppAdapter.Entry e, Movie.Video v) {
        heroPosterLetter.setVisibility(View.GONE);
        heroPosterImg.setBackground(null);
        heroArt.setBackground(null);
        heroArt.setAlpha(0.5f);

        if (!TextUtils.isEmpty(v.pic)) {
            String pic = v.pic.trim();
            ImgUtil.load(pic, heroArt, 0);
            ImgUtil.load(pic, heroPosterImg, 14);
        } else {
            heroArt.setImageDrawable(null);
            heroPosterImg.setImageDrawable(null);
            heroPosterLetter.setVisibility(View.VISIBLE);
            heroPosterLetter.setTextSize(TypedValue.COMPLEX_UNIT_MM, 69.7f);
            heroPosterLetter.setTextColor(0x59FFFFFF);
            heroPosterLetter.setText(firstChar(v.name));
        }

        heroTag.setText(getString(R.string.home_hero_tag_hot));
        heroTitle.setText(TextUtils.isEmpty(v.name) ? "" : v.name);

        StringBuilder meta = new StringBuilder();
        if (v.year > 0) meta.append(v.year).append(" 年");
        appendMeta(meta, v.type);
        appendMeta(meta, v.area);
        heroMeta.setText(meta.toString());

        heroDesc.setText(TextUtils.isEmpty(v.note)
                ? getString(R.string.home_hero_func_desc, TextUtils.isEmpty(v.name) ? e.name : v.name)
                : v.note);

        heroHint.setText(R.string.home_hint_enter);
        heroFadeIn();
    }

    private void appendMeta(StringBuilder sb, String s) {
        if (TextUtils.isEmpty(s)) return;
        if (sb.length() > 0) sb.append("  ·  ");
        sb.append(s);
    }

    private void paintHeroLoading(HomeAppAdapter.Entry e) {
        heroTag.setText(getString(R.string.home_hero_tag_hot));
        heroTitle.setText(e.name);
        heroMeta.setText(R.string.home_app_sort_sub);
        heroDesc.setText(R.string.home_hero_loading);
        heroHint.setText(R.string.home_hint_enter);
        heroPosterLetter.setVisibility(View.VISIBLE);
        heroPosterLetter.setTextSize(TypedValue.COMPLEX_UNIT_MM, 69.7f);
        heroPosterLetter.setTextColor(0x40FFFFFF);
        heroPosterLetter.setText(firstChar(e.name));
        heroArt.setImageDrawable(null);
        heroPosterImg.setImageDrawable(null);
        heroArt.setBackgroundColor(0xFF0D0D13);
        heroArt.setAlpha(1f);
    }

    private void paintHeroFunc(HomeAppAdapter.Entry e) {
        heroTag.setText(e.name);
        heroTitle.setText(e.name);
        heroMeta.setText(e.sub);
        heroDesc.setText(getString(R.string.home_hero_func_desc, e.name));
        heroHint.setText(R.string.home_hint_enter);
        heroPosterLetter.setVisibility(View.VISIBLE);
        heroPosterLetter.setTextSize(TypedValue.COMPLEX_UNIT_MM, 69.7f);
        heroPosterLetter.setTextColor(0x40FFFFFF);
        heroPosterLetter.setText(firstChar(e.name));
        heroArt.setImageDrawable(null);
        heroPosterImg.setImageDrawable(null);
        heroArt.setBackgroundColor(0xFF0D0D13);
        heroArt.setAlpha(1f);
    }

    private static String firstChar(String s) {
        if (TextUtils.isEmpty(s)) return "";
        return s.substring(0, 1);
    }

    private void heroFadeIn() {
        heroArt.setAlpha(0.15f);
        heroArt.animate().alpha(1f).setDuration(260).start();
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
                if (absXml != null && absXml.classes != null && absXml.classes.sortList != null) {
                    sortAdapter.setNewData(DefaultConfig.adjustSort(ApiConfig.get().getHomeSourceBean().getKey(), absXml.classes.sortList, true));
                } else {
                    sortAdapter.setNewData(DefaultConfig.adjustSort(ApiConfig.get().getHomeSourceBean().getKey(), new ArrayList<>(), true));
                }
                initViewPager(absXml);
                SourceBean home = ApiConfig.get().getHomeSourceBean();
                if (home != null && home.getName() != null && !home.getName().isEmpty()) {
                    tvPillSource.setText(home.getName());
                }
            }
        });

        // 预览卡：只认自己发出去的请求，GridFragment 用的是 fragment 作用域的 ViewModel，不会串
        sourceViewModel.listResult.observe(this, new Observer<AbsXml>() {
            @Override
            public void onChanged(AbsXml absXml) {
                if (pendingHeroSort == null) return;
                MovieSort.SortData sort = pendingHeroSort;
                pendingHeroSort = null;

                List<Movie.Video> list = (absXml != null && absXml.movie != null) ? absXml.movie.videoList : null;
                Movie.Video video = (list != null && !list.isEmpty()) ? list.get(0) : null;
                if (sort.id != null) heroCache.put(sort.id, video);

                HomeAppAdapter.Entry e = currentHeroEntry;
                if (e == null || e.sort == null || sort.id == null || !sort.id.equals(e.sort.id)) {
                    return; // 焦点已经移走，别把旧结果画上去
                }
                if (video != null) {
                    paintHeroVideo(e, video);
                } else {
                    paintHeroFunc(e);
                }
            }
        });
    }

    private boolean skipNextUpdate = false;

    private boolean dataInitOk = false;
    private boolean jarInitOk = false;

    boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = cm.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnectedOrConnecting();
    }

    private void initData() {
        updateNetPill();

        if (dataInitOk && jarInitOk) {
            sourceViewModel.getSort(ApiConfig.get().getHomeSourceBean().getKey());
            if (hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                LOG.e("有");
            } else {
                LOG.e("无");
            }
            if (Hawk.get(HawkConfig.HOME_DEFAULT_SHOW, false)) {
                jumpActivity(LivePlayActivity.class);
            }
            return;
        }
        showLoading();
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
                        // 定位用：error("") 的空消息到底谁发的，把调用栈打出来
                        LOG.e("echo---HomeActivity loadJar error msg=[" + msg + "] stack=\n"
                                + android.util.Log.getStackTraceString(new Throwable("error-trace")));
                        // 上游偶尔会给空消息（时序竞态），原样 toast 出来用户只能看到
                        // 一句没头没尾的「加载失败」。空的时候用最近一次 jar 诊断兜底，
                        // 宁可多打几个字，也不能让用户对着黑盒猜。
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
                if (ApiConfig.get().getSpider().isEmpty()) {
                    jarInitOk = true;
                }
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
                        if (!dialog.isShowing())
                            dialog.show();
                    }
                });
            }
        }, this);
    }

    /** 网络胶囊：图标随连接方式变，断网时状态点变灰 */
    private void updateNetPill() {
        try {
            if (!isNetworkAvailable()) {
                ivNet.setImageDrawable(res.getDrawable(R.drawable.hm_wifi_no));
                tvNet.setText(R.string.home_pill_net);
                tvNetSub.setText("未连接");
                vNetDot.setVisibility(View.GONE);
                return;
            }
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            int type = cm.getActiveNetworkInfo().getType();
            if (type == ConnectivityManager.TYPE_WIFI) {
                ivNet.setImageDrawable(res.getDrawable(R.drawable.hm_wifi));
                tvNet.setText("WiFi");
            } else if (type == ConnectivityManager.TYPE_MOBILE) {
                ivNet.setImageDrawable(res.getDrawable(R.drawable.hm_mobile));
                tvNet.setText("移动网络");
            } else if (type == ConnectivityManager.TYPE_ETHERNET) {
                ivNet.setImageDrawable(res.getDrawable(R.drawable.hm_lan));
                tvNet.setText("网线");
            }
            tvNetSub.setText(R.string.home_pill_net_sub);
            vNetDot.setVisibility(View.VISIBLE);
        } catch (Exception ignored) {
        }
    }

    private void initViewPager(AbsSortXml absXml) {
        fragments.clear();
        if (sortAdapter.getData().size() > 0) {
            for (MovieSort.SortData data : sortAdapter.getData()) {
                if (data.id.equals("my0")) {
                    if (Hawk.get(HawkConfig.HOME_REC, 0) == 1 && absXml != null && absXml.videoList != null && absXml.videoList.size() > 0) {
                        fragments.add(UserFragment.newInstance(absXml.videoList));
                    } else {
                        fragments.add(UserFragment.newInstance(null));
                    }
                } else {
                    fragments.add(GridFragment.newInstance(data));
                }
            }
            pageAdapter = new HomePageAdapter(getSupportFragmentManager(), fragments);
            try {
                Field field = ViewPager.class.getDeclaredField("mScroller");
                field.setAccessible(true);
                FixedSpeedScroller scroller = new FixedSpeedScroller(mContext, new android.view.animation.AccelerateInterpolator());
                field.set(mViewPager, scroller);
                scroller.setmDuration(300);
            } catch (Exception e) {
            }
            mViewPager.setPageTransformer(true, new DefaultTransformer());
            mViewPager.setAdapter(pageAdapter);
            mViewPager.setCurrentItem(currentSelected, false);
        }
        buildAppEntries();
    }

    /**
     * 底部入口行，对齐手机端的信息密度：只留 5 个入口。
     * 首页（进分类，预览第一个分类的热片）+ 观看历史 + 收藏 + 搜索 + 直播。
     * 站点分类不再平铺——进「首页」后顶部分类 Tab 可达全部分类；
     * 问 AI 入口删除（手机端 2026-10-22 已整页下线 AI）。
     */
    private void buildAppEntries() {
        List<HomeAppAdapter.Entry> list = new ArrayList<>();

        // 「首页」：复用 KIND_SORT 通道，预览卡能直接展示热片海报；进内容层后可切全部分类
        List<MovieSort.SortData> sorts = sortAdapter.getData();
        if (!sorts.isEmpty()) {
            HomeAppAdapter.Entry home = new HomeAppAdapter.Entry();
            home.kind = HomeAppAdapter.KIND_SORT;
            home.key = "home";
            home.name = getString(R.string.home_app_home);
            home.sub = getString(R.string.home_app_sort_sub);
            home.icon = R.drawable.h_ic_home;
            home.tint = 0xFF5A8CFF;
            home.sortIndex = 0;
            home.sort = sorts.get(0);
            list.add(home);
        }

        list.add(funcEntry("hist", getString(R.string.home_app_hist), getString(R.string.home_app_hist_sub),
                R.drawable.h_ic_hist, 0xFFFF7A45));
        list.add(funcEntry("fav", getString(R.string.home_app_fav), getString(R.string.home_app_fav_sub),
                R.drawable.h_ic_fav, 0xFFFFB454));
        list.add(funcEntry("search", getString(R.string.home_app_search), getString(R.string.home_app_search_sub),
                R.drawable.h_ic_search, 0xFF5A96FF));

        HomeAppAdapter.Entry live = new HomeAppAdapter.Entry();
        live.kind = HomeAppAdapter.KIND_LIVE;
        live.key = "live";
        live.name = getString(R.string.home_app_live);
        live.sub = getString(R.string.home_app_live_sub);
        live.icon = R.drawable.h_ic_live;
        live.tint = 0xFFFF463C;
        list.add(live);

        appAdapter.setData(list);

        lastAppIndex = Math.min(lastAppIndex, list.size() - 1);
        if (lastAppIndex < 0) lastAppIndex = 0;
        updateHero(appAdapter.getItem(lastAppIndex));

        // 只在首次拿到分类数据时自动把焦点交给入口行；
        // 后续刷新（切源、清缓存）时用户可能正在操作胶囊，不能抢
        if (!appsFocusedOnce && launcherMode && list.size() > 0) {
            appsFocusedOnce = true;
            focusApps(lastAppIndex);
        }
    }

    private HomeAppAdapter.Entry funcEntry(String key, String name, String sub, int icon, int tint) {
        HomeAppAdapter.Entry e = new HomeAppAdapter.Entry();
        e.kind = HomeAppAdapter.KIND_FUNC;
        e.key = key;
        e.name = name;
        e.sub = sub;
        e.icon = icon;
        e.tint = tint;
        return e;
    }

    // =====================================================================
    //  返回 / 退出
    // =====================================================================

    @Override
    public void onBackPressed() {
        if (isLoading()) {
            refreshEmpty();
            return;
        }
        if (HawkConfig.hotVodDelete) {
            HawkConfig.hotVodDelete = false;
            UserFragment.homeHotVodAdapter.notifyDataSetChanged();
            return;
        }

        // 二级页：先让 GridFragment 处理它自己的子级返回（文件夹模式）
        if (!launcherMode) {
            if (sortFocused >= 0 && sortFocused < fragments.size()) {
                BaseLazyFragment f = fragments.get(sortFocused);
                if (f instanceof GridFragment && ((GridFragment) f).restoreView()) {
                    return;
                }
            }
            showLauncher();
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

    @Override
    protected void onResume() {
        super.onResume();
        SourceBean home = ApiConfig.get().getHomeSourceBean();
        if (home != null && home.getName() != null && !home.getName().isEmpty()) {
            tvPillSource.setText(home.getName());
        }
        updateNetPill();
        mHandler.post(mClockRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacksAndMessages(null);
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
            // 筛选变更的回调保留，图标刷新在下一次进入分类时自然生效
        }
    }

    private void showFilterIcon(int count) {
        boolean activated = count > 0;
        if (sortFocusView == null) return;
        View filter = sortFocusView.findViewById(R.id.tvFilter);
        if (filter == null) return;
        filter.setVisibility(View.VISIBLE);
        ImageView imgView = (ImageView) filter;
        imgView.setColorFilter(activated ? this.getThemeColor() : Color.WHITE);
    }

    private final Runnable mDataRunnable = new Runnable() {
        @Override
        public void run() {
            if (sortChange) {
                sortChange = false;
                if (sortFocused != currentSelected) {
                    currentSelected = sortFocused;
                    mViewPager.setCurrentItem(sortFocused, false);
                }
            }
        }
    };

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
                showSiteSwitch();
            }
        }
        return super.dispatchKeyEvent(event);
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
            if (spanCount >= 3) spanCount = 3;

            TvRecyclerView tvRecyclerView = dialog.findViewById(R.id.list);
            tvRecyclerView.setLayoutManager(new V7GridLayoutManager(dialog.getContext(), spanCount));
            ConstraintLayout cl_root = dialog.findViewById(R.id.cl_root);
            android.view.ViewGroup.LayoutParams clp = cl_root.getLayoutParams();
            if (spanCount != 1) {
                clp.width = AutoSizeUtils.mm2px(dialog.getContext(), 400 + 260 * (spanCount - 1));
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

    /** 首页推荐模式轮换（UserFragment 的「换一批」在用） */
    public static void homeRecf() {
        int homeRec = Hawk.get(HawkConfig.HOME_REC, -1);
        int limit = 2;
        if (homeRec == limit) homeRec = -1;
        homeRec++;
        Hawk.put(HawkConfig.HOME_REC, homeRec);
    }

    public static boolean reHome(Context appContext) {
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
        sortAdapter.setNewData(DefaultConfig.adjustSort(ApiConfig.get().getHomeSourceBean().getKey(), new ArrayList<>(), true));
        initViewPager(null);
    }
}
