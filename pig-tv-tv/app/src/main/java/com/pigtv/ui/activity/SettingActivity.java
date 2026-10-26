package com.pigtv.ui.activity;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.viewpager.widget.ViewPager;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.pigtv.R;
import com.pigtv.api.ApiConfig;
import com.pigtv.base.BaseActivity;
import com.pigtv.base.BaseLazyFragment;
import com.pigtv.ui.adapter.SettingMenuAdapter;
import com.pigtv.ui.adapter.SettingPageAdapter;
import com.pigtv.ui.fragment.ModelSettingFragment;
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
 * @description:
 */
public class SettingActivity extends BaseActivity {
    private TvRecyclerView mGridView;
    private ViewPager mViewPager;
    private SettingMenuAdapter sortAdapter;
    private SettingPageAdapter pageAdapter;
    private List<BaseLazyFragment> fragments = new ArrayList<>();
    private boolean sortChange = false;
    private int defaultSelected = 0;
    private int sortFocused = 0;
    private Handler mHandler = new Handler();
    private String homeSourceKey;
    private String currentApi;
    private String currentLive;
    private int homeRec;
    private int dnsOpt;

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
        // 左侧分组只是索引：点一下把焦点送到右侧对应的设置项，
        // 不切页（右侧本来就是一整页，切页会让用户以为设置丢了一半）
        sortAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                sortFocused = position;
                sortAdapter.setSelected(position);
                focusSection(position);
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
                sortChange = true;
                sortFocused = position;
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {

            }
        });
    }

    /** 把焦点送到右侧某个分组的第一项 */
    private void focusSection(int position) {
        if (fragments.isEmpty()) return;
        BaseLazyFragment f = fragments.get(0);
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
        sortList.add(getString(R.string.v8_sec_api));
        sortList.add(getString(R.string.v8_sec_play));
        sortList.add(getString(R.string.v8_sec_display));
        sortList.add(getString(R.string.v8_sec_adv));
        sortAdapter.setNewData(sortList);
        initViewPager();
    }

    private void initViewPager() {
        fragments.add(ModelSettingFragment.newInstance());
        pageAdapter = new SettingPageAdapter(getSupportFragmentManager(), fragments);
        mViewPager.setAdapter(pageAdapter);
        mViewPager.setCurrentItem(0);
        // 焦点先落在左侧「接口」分组，用户一眼知道这里有 4 个分区
        mGridView.post(new Runnable() {
            @Override
            public void run() {
                View v = mGridView.getLayoutManager() == null ? null
                        : mGridView.getLayoutManager().findViewByPosition(0);
                if (v != null) v.requestFocus();
                else mGridView.requestFocus();
            }
        });
    }

    private final Runnable mDataRunnable = new Runnable() {
        @Override
        public void run() {
            if (sortChange) {
                sortChange = false;
                if (sortFocused != defaultSelected) {
                    defaultSelected = sortFocused;
                    mViewPager.setCurrentItem(sortFocused, false);
                }
            }
        }
    };

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
            mHandler.removeCallbacks(mDataRunnable);
            int keyCode = event.getKeyCode();
            switch (keyCode) {
                case KeyEvent.KEYCODE_0:
                    mHandler.removeCallbacks(mDevModeRun);
                    devMode += "0";
                    mHandler.postDelayed(mDevModeRun, 200);
                    if (devMode.length() >= 4) {
                        if (callback != null) {
                            callback.onChange();
                        }
                    }
                    break;
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            mHandler.postDelayed(mDataRunnable, 200);
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