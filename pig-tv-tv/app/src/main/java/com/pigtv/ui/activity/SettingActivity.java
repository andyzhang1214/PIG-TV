package com.pigtv.ui.activity;

import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.View;

import androidx.viewpager.widget.ViewPager;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.pigtv.R;
import com.pigtv.api.ApiConfig;
import com.pigtv.base.BaseActivity;
import com.pigtv.base.BaseLazyFragment;
import com.pigtv.ui.adapter.SettingMenuAdapter;
import com.pigtv.ui.adapter.SettingPageAdapter;
import com.pigtv.ui.fragment.ModelSettingFragment;
import com.pigtv.ui.fragment.SettingMeFragment;
import com.pigtv.util.AppManager;
import com.pigtv.util.HawkConfig;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import java.util.ArrayList;
import java.util.List;

/**
 * @author pj567
 * @date :2020/12/23
 * @description: 配置中心
 *
 * <p>左侧 5 项索引：我的 / 接口 / 播放 / 显示 / 高级。</p>
 *
 * <p>「我的」是真正的第二页（{@link SettingMeFragment}），其余四项仍然只是
 * 一整页设置里的锚点（{@link ModelSettingFragment#focusSection(int)}）。
 * 为什么不把设置也拆成 4 页：那一页 800 多行、上百个控件互相依赖，
 * 拆页面的收益只有「标题看起来更整齐」，风险却是把设置项搞丢。</p>
 */
public class SettingActivity extends BaseActivity {
    private TvRecyclerView mGridView;
    private ViewPager mViewPager;
    private SettingMenuAdapter sortAdapter;
    private SettingPageAdapter pageAdapter;
    private List<BaseLazyFragment> fragments = new ArrayList<>();
    private Handler mHandler = new Handler();
    private String homeSourceKey;
    private String currentApi;
    private String currentLive;
    private int homeRec;
    private int dnsOpt;

    /** 左侧当前停在哪一项：从右侧按 ← 逃回左侧时用它定位 */
    private int curSection = 1;

    /** 右侧第几页是「我的」 */
    private static final int PAGE_ME = 0;
    /** 右侧第几页是设置列表 */
    private static final int PAGE_SETTING = 1;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_setting;
    }

    @Override
    protected void init() {
        initView();
        initData();
    }

    private void initView() {
        mGridView = findViewById(R.id.mGridView);
        mViewPager = findViewById(R.id.mViewPager);
        sortAdapter = new SettingMenuAdapter();
        mGridView.setAdapter(sortAdapter);
        mGridView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));
        mGridView.setSpacingWithMargins(0, 0);
        /* 左侧只是索引：按 OK 才切页 / 跳锚点。
           焦点只是路过某一项时不动右侧 —— 不然按一下 ↓ 右侧就整块换掉，
           用户会以为自己误触了什么。 */
        sortAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                pickSection(position);
            }
        });
        mGridView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                if (itemView != null) {
                    itemView.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                }
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                if (itemView == null) return;
                itemView.animate().scaleX(1.03f).scaleY(1.03f).setDuration(140).start();
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {

            }
        });
    }

    /**
     * 左侧某一项被按下 OK。
     *
     * <p>「我的」切到第 0 页并把焦点交给那一页的按钮；其余四项切回设置列表，
     * 再把焦点送到对应分组的第一行。切页是同步的，但 fragment 的 show/hide
     * 走的是 commitAllowingStateLoss，焦点得等下一帧才找得到 —— 所以延 60ms。</p>
     */
    private void pickSection(int position) {
        curSection = position;
        sortAdapter.setSelected(position);
        if (position == 0) {
            mViewPager.setCurrentItem(PAGE_ME, false);
            mViewPager.post(new Runnable() {
                @Override
                public void run() {
                    if (fragments.isEmpty()) return;
                    if (fragments.get(0) instanceof SettingMeFragment) {
                        ((SettingMeFragment) fragments.get(0)).focusFirst();
                    }
                }
            });
        } else {
            mViewPager.setCurrentItem(PAGE_SETTING, false);
            mViewPager.postDelayed(new Runnable() {
                @Override
                public void run() {
                    focusSection(position - 1);
                }
            }, 60);
        }
    }

    /**
     * 从右侧内容区把焦点送回左侧索引。
     *
     * <p>为什么必须手动送：右侧那页从上往下是「不可聚焦的卡 → 不可聚焦的信息 →
     * 一排按钮」，按钮上面什么都没有，按 ↑ 焦点原地不动 —— 用户会以为界面卡死。
     * 这里跟顶栏那个「第一个标签按左自锁」是同一类问题：框架的几何焦点搜索
     * 在「这个方向上没有可聚焦元素」时不会帮我们跨容器跳。</p>
     */
    public void focusSideNav() {
        View v = mGridView.getLayoutManager() == null ? null
                : mGridView.getLayoutManager().findViewByPosition(curSection);
        if (v != null) {
            v.requestFocus();
        } else {
            mGridView.requestFocus();
        }
    }

    /** 把焦点送到设置列表里某个分组的第一项 */
    private void focusSection(int position) {
        if (fragments.size() <= PAGE_SETTING) return;
        BaseLazyFragment f = fragments.get(PAGE_SETTING);
        if (f instanceof ModelSettingFragment) {
            ((ModelSettingFragment) f).focusSection(position);
        }
    }

    private void initData() {
        currentApi = Hawk.get(HawkConfig.API_URL, "");
        currentLive = Hawk.get(HawkConfig.LIVE_URL, "");
        homeSourceKey = ApiConfig.get().getHomeSourceBean().getKey();
        homeRec = Hawk.get(HawkConfig.HOME_REC, 0);
        dnsOpt = Hawk.get(HawkConfig.DOH_URL, 0);
        List<String> sortList = new ArrayList<>();
        sortList.add(getString(R.string.set_tab_me));
        sortList.add(getString(R.string.v8_sec_api));
        sortList.add(getString(R.string.v8_sec_play));
        sortList.add(getString(R.string.v8_sec_display));
        sortList.add(getString(R.string.v8_sec_adv));
        sortAdapter.setNewData(sortList);
        initViewPager();
    }

    private void initViewPager() {
        fragments.add(SettingMeFragment.newInstance());
        fragments.add(ModelSettingFragment.newInstance());
        pageAdapter = new SettingPageAdapter(getSupportFragmentManager(), fragments);
        mViewPager.setAdapter(pageAdapter);
        /* 默认停在设置列表：这一页叫「配置中心」，进来多半是要改配置。
           「我的」放在第一项，按 ↑ 就到，不占默认焦点。 */
        mViewPager.setCurrentItem(PAGE_SETTING, false);
        sortAdapter.setSelected(1);
        mGridView.post(new Runnable() {
            @Override
            public void run() {
                View v = mGridView.getLayoutManager() == null ? null
                        : mGridView.getLayoutManager().findViewByPosition(1);
                if (v != null) v.requestFocus();
                else mGridView.requestFocus();
            }
        });
    }

    private final Runnable mDevModeRun = new Runnable() {
        @Override
        public void run() {
            devMode = "";
        }
    };


    public interface DevModeCallback {
        void onChange();
    }

    public static DevModeCallback callback = null;

    String devMode = "";

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_0) {
                mHandler.removeCallbacks(mDevModeRun);
                devMode += "0";
                mHandler.postDelayed(mDevModeRun, 200);
                if (devMode.length() >= 4) {
                    if (callback != null) {
                        callback.onChange();
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        if ((homeSourceKey != null && !homeSourceKey.equals(Hawk.get(HawkConfig.HOME_API, ""))) ||
                !currentApi.equals(Hawk.get(HawkConfig.API_URL, "")) || !currentLive.equals(Hawk.get(HawkConfig.LIVE_URL, "")) ||
                homeRec != Hawk.get(HawkConfig.HOME_REC, 0) ||
                dnsOpt != Hawk.get(HawkConfig.DOH_URL, 0)) {
            AppManager.getInstance().finishAllActivity();
            if (currentApi.equals(Hawk.get(HawkConfig.API_URL, "")) & (currentLive.equals(Hawk.get(HawkConfig.LIVE_URL, "")))) {
                Bundle bundle = new Bundle();
                bundle.putBoolean("useCache", true);
                jumpActivity(HomeActivity.class, bundle);
            } else {
                jumpActivity(HomeActivity.class);
            }
        } else {
            super.onBackPressed();
        }
    }
}
