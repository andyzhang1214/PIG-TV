package com.pigtv.ui.activity;

import android.content.Intent;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.pigtv.R;
import com.pigtv.api.ApiConfig;
import com.pigtv.base.BaseActivity;
import com.pigtv.bean.LiveChannelGroup;
import com.pigtv.bean.LiveChannelItem;
import com.pigtv.ui.adapter.FilterPillAdapter;
import com.pigtv.ui.adapter.LiveChannelCardAdapter;
import com.pigtv.util.JavaUtil;
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
 *   <li>上面一行分组胶囊，第一项固定「全部」；</li>
 *   <li>下面频道墙，全部视图里每格显示「分组名 · 几路源」，选了分组就显示「频道 N」；</li>
 *   <li>点一格直接进播放器并定位到那个台。</li>
 * </ul>
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

    private TvRecyclerView mChipRow;
    private TvRecyclerView mChannelGrid;
    private TextView tvLiveStat;
    private TextView tvEmptyTitle;
    private TextView tvEmptyDesc;
    private TextView btnEmptyAction;
    private View liveEmptyBox;
    private View navCurrent;

    private FilterPillAdapter chipAdapter;
    private LiveChannelCardAdapter cardAdapter;

    /** 有频道的分组，对象里的 groupIndex 就是它在 ApiConfig 列表里的原始下标 */
    private final List<LiveChannelGroup> groups = new ArrayList<>();
    /** 全部频道（跨分组），按「分组顺序 + 组内顺序」排列 */
    private final List<Raw> rawAll = new ArrayList<>();

    /** -1 = 全部，否则是分组的 groupIndex */
    private int curGroup = -1;
    private boolean hasProxy = false;

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
        final boolean locked;

        Raw(int gi, int ci, String name, String groupName, int num, int srcs, boolean locked) {
            this.gi = gi;
            this.ci = ci;
            this.name = name;
            this.groupName = groupName;
            this.num = num;
            this.srcs = srcs;
            this.locked = locked;
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
        navCurrent = V8TopBar.attach(this, V8TopBar.NAV_LIVE);

        tvLiveStat = findViewById(R.id.tvLiveStat);
        tvEmptyTitle = findViewById(R.id.tvEmptyTitle);
        tvEmptyDesc = findViewById(R.id.tvEmptyDesc);
        btnEmptyAction = findViewById(R.id.btnEmptyAction);
        liveEmptyBox = findViewById(R.id.liveEmptyBox);

        mChipRow = findViewById(R.id.mChipRow);
        mChipRow.setHasFixedSize(true);
        mChipRow.setLayoutManager(new V7LinearLayoutManager(mContext, LinearLayoutManager.HORIZONTAL, false));
        chipAdapter = new FilterPillAdapter();
        mChipRow.setAdapter(chipAdapter);

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
        // 焦点在第一排按 ↑ 回分组胶囊：列表默认的 focusSearch 会一直往上找，
        // 越过胶囊落到顶栏去，用户就得多按好几次才回得到分组。
        mChannelGrid.setOnInBorderKeyEventListener(new TvRecyclerView.OnInBorderKeyEventListener() {
            @Override
            public boolean onInBorderKeyEvent(int direction, View focused) {
                if (direction == View.FOCUS_UP) return focusCurrentChip();
                return false;
            }
        });

        chipAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FilterPillAdapter.Opt opt = chipAdapter.getItem(position);
                if (opt == null) return;
                curGroup = opt.value.isEmpty() ? -1 : parseInt(opt.value, -1);
                // 不 notify，焦点留在原胶囊上；否则选中一下焦点就跳回第一个
                chipAdapter.setSelectedQuiet(mChipRow, opt.value);
                renderCards();
            }
        });
    }

    // =====================================================================
    //  数据
    // =====================================================================

    /**
     * 读频道表。
     *
     * <p>优先用 ApiConfig 里已经解析好的那份（首页/预热时拉过）；整份是空的话拿
     * assets 里内置的直播源兜底 —— 内置源不依赖任何外网地址，至少让这一页有台可点，
     * 而不是一片空白让人以为直播坏了。
     */
    private void loadChannels() {
        List<LiveChannelGroup> gl = ApiConfig.get().getChannelGroupList();
        if ((gl == null || gl.isEmpty()) && ApiConfig.get().loadBuiltinLives()) {
            gl = ApiConfig.get().getChannelGroupList();
        }

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
                            it.getChannelUrls() == null ? 0 : it.getChannelUrls().size(),
                            locked));
                }
            }
        }

        tvLiveStat.setText(getString(R.string.v8_live_stat, rawAll.size(), groups.size()));
        buildChips();

        if (rawAll.isEmpty()) {
            showLiveEmpty();
        } else {
            liveEmptyBox.setVisibility(View.GONE);
            mChipRow.setVisibility(View.VISIBLE);
            mChannelGrid.setVisibility(View.VISIBLE);
            renderCards();
        }
    }

    private void buildChips() {
        List<FilterPillAdapter.Opt> opts = new ArrayList<>();
        opts.add(new FilterPillAdapter.Opt(getString(R.string.v8_live_all), ""));
        for (LiveChannelGroup g : groups) {
            String name = g.getGroupName();
            if (TextUtils.isEmpty(name)) name = String.valueOf(g.getGroupIndex() + 1);
            opts.add(new FilterPillAdapter.Opt(name, String.valueOf(g.getGroupIndex())));
        }
        curGroup = -1;
        chipAdapter.setSelected("");
        chipAdapter.setNewData(opts);
    }

    /** 按当前分组把频道墙铺出来 */
    private void renderCards() {
        List<LiveChannelCardAdapter.Cell> cells = new ArrayList<>();
        int selectedPos = -1;

        for (Raw r : rawAll) {
            if (curGroup >= 0 && r.gi != curGroup) continue;
            String sub = curGroup >= 0
                    ? getString(R.string.v8_live_sub_num, r.num)
                    : r.groupName;
            if (r.srcs > 1) sub = sub + " · " + getString(R.string.v8_live_sub_srcs, r.srcs);
            if (r.locked) sub = sub + " · " + getString(R.string.v8_live_locked);

            LiveChannelCardAdapter.Cell cell = new LiveChannelCardAdapter.Cell(r.gi, r.ci, r.name, sub);
            if (r.gi == lastGroup && r.ci == lastChannel) {
                cell.selected = true;
                selectedPos = cells.size();
            }
            cells.add(cell);
        }

        cardAdapter.setNewData(cells);
        hoverSelected(selectedPos);
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
        } else {
            tvEmptyTitle.setText(R.string.v8_live_empty_none_title);
            tvEmptyDesc.setText(R.string.v8_live_empty_none_desc);
            btnEmptyAction.setText(R.string.v8_live_btn_retry);
            btnEmptyAction.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    loadChannels();
                    focusInitial(0);
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
    protected void onResume() {
        super.onResume();
        // 从播放器回来：焦点还给刚才点的那一格，顺手把描边挪过去
        if (returnPos < 0 || cardAdapter == null) return;
        final int pos = returnPos;
        returnPos = -1;
        cardAdapter.setSelectedQuiet(mChannelGrid, pos);
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
        if (rawAll.isEmpty()) return;
        mChannelGrid.post(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) return;
                // 焦点已经在「频道墙 / 分组胶囊」里就别抢：切分组时用户正停在胶囊上，
                // 这时候把焦点拽到卡片上，想连着切两组就得再走回来。
                // 但进页面时焦点通常被顶栏 tab 占着 —— 那不是用户的选择，得抢回来。
                if (isInside(mChannelGrid) || isInside(mChipRow)) return;

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
        if (hasWindowFocus && !isInside(mChannelGrid) && !isInside(mChipRow) && !rawAll.isEmpty()) {
            focusInitial(0);
        }
    }

    /** 上次看的台在当前视图里的下标，不在就 -1 */
    private int posOfLast() {
        List<LiveChannelCardAdapter.Cell> data = cardAdapter.getData();
        for (int i = 0; i < data.size(); i++) {
            LiveChannelCardAdapter.Cell c = data.get(i);
            if (c.groupIndex == lastGroup && c.channelIndex == lastChannel) return i;
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

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }
}
