package com.pigtv.ui.fragment;

import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.pigtv.R;
import com.pigtv.account.AccountApi;
import com.pigtv.account.AccountStore;
import com.pigtv.base.BaseLazyFragment;
import com.pigtv.server.ControlManager;
import com.pigtv.ui.activity.SettingActivity;
import com.pigtv.ui.tv.QRCodeGen;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.jessyan.autosize.utils.AutoSizeUtils;

/**
 * 配置中心左侧「我的」：会员卡 + 登录码 / 账号信息 + 按钮。
 *
 * <p>内容和独立的会员中心页（{@code VipActivity}）是同一份布局
 * {@code view_vip_body.xml}，逻辑也是同一套。为什么不直接复用 VipActivity：
 * 那一页是整屏 Activity，塞进 ViewPager 需要把生命周期全改一遍，
 * 而它的轮询/代次逻辑已经在真机上跑通过，不值得为省 200 行去动它。
 * 两份实现唯一必须保持一致的是「身份判定只看服务端下发的 vipActive /
 * vipExpired / vipDaysLeft」——这条在两边都写了注释。</p>
 *
 * <p>电视没有输入法，所以这一页不接受键盘输入：未登录时领一个 6 位数字码，
 * 手机 App 输一次就绑定完成。</p>
 */
public class SettingMeFragment extends BaseLazyFragment implements View.OnClickListener {

    /** 数字码有效期 5 分钟，2 秒一次 = 最多 150 次；多留几次余量 */
    private static final int MAX_POLL = 160;
    private static final long POLL_MS = 2000L;
    /** 服务端没下发 maxDevices 时的兜底值，和手机端保持一致 */
    private static final int DEVICE_LIMIT = 3;

    private TextView vipAvatar;
    private ImageView vipLogo;
    private TextView vipName;
    private TextView vipSub;
    private TextView vipDevNum;
    private TextView vipLimitNum;
    private View vipCodeBox;
    private View vipInfoBox;
    private ImageView vipQr;
    private TextView vipCode;
    private TextView vipCodeTip;
    private TextView vipCodeHint;
    private TextView vipInfoLine1;
    private TextView vipInfoLine2;
    private TextView vipBtnRefresh;
    private TextView vipBtnLogout;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExecutorService pool = Executors.newSingleThreadExecutor();

    /** 轮询代次：换码 / 离开页面时 +1，旧任务看到代次不符就自己结束 */
    private int pollGen = 0;
    /** 登录态检查的代次：和 pollGen 分开，各管一条链路 */
    private int watchGen = 0;
    private String deviceCode = "";

    public static SettingMeFragment newInstance() {
        return new SettingMeFragment();
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_setting_me;
    }

    @Override
    protected void init() {
        vipAvatar = findViewById(R.id.vipAvatar);
        vipLogo = findViewById(R.id.vipLogo);
        vipName = findViewById(R.id.vipName);
        vipSub = findViewById(R.id.vipSub);
        vipDevNum = findViewById(R.id.vipDevNum);
        vipLimitNum = findViewById(R.id.vipLimitNum);
        vipCodeBox = findViewById(R.id.vipCodeBox);
        vipInfoBox = findViewById(R.id.vipInfoBox);
        vipQr = findViewById(R.id.vipQr);
        vipCode = findViewById(R.id.vipCode);
        vipCodeTip = findViewById(R.id.vipCodeTip);
        vipCodeHint = findViewById(R.id.vipCodeHint);
        vipInfoLine1 = findViewById(R.id.vipInfoLine1);
        vipInfoLine2 = findViewById(R.id.vipInfoLine2);
        vipBtnRefresh = findViewById(R.id.vipBtnRefresh);
        vipBtnLogout = findViewById(R.id.vipBtnLogout);

        if (vipBtnRefresh != null) vipBtnRefresh.setOnClickListener(this);
        if (vipBtnLogout != null) vipBtnLogout.setOnClickListener(this);
        if (vipBtnRefresh != null) vipBtnRefresh.setOnKeyListener(navEscape);
        if (vipBtnLogout != null) vipBtnLogout.setOnKeyListener(navEscape);
        refresh();
    }

    /**
     * 按钮上方的所有东西都不可聚焦，按 ↑ 框架找不到落点，焦点会原地不动 ——
     * 用户会以为界面死了。这里直接把焦点送回左侧索引。
     *
     * <p>← 只在最左那颗按钮上拦（在「退出登录」上按 ← 应该退到「刷新」，
     * 那是正常的方向键行为，不能拦）。→ 在最右那颗上停住，别绕回屏幕另一头。</p>
     */
    private final View.OnKeyListener navEscape = new View.OnKeyListener() {
        @Override
        public boolean onKey(View v, int keyCode, KeyEvent event) {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                return escapeToSideNav();
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && v == vipBtnRefresh) {
                return escapeToSideNav();
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v == vipBtnLogout) {
                return true;
            }
            return false;
        }
    };

    private boolean escapeToSideNav() {
        if (getActivity() instanceof SettingActivity) {
            ((SettingActivity) getActivity()).focusSideNav();
            return true;
        }
        return false;
    }

    /**
     * 重新进这一页时刷一次。切换分组时 {@link #onFragmentPause()} 会把轮询停掉，
     * 回来必须重新开始，否则游客态会一直挂着上次那个可能已经过期的码。
     */
    @Override
    protected void onFragmentResume() {
        super.onFragmentResume();
        if (isViewCreated) refresh();
    }

    @Override
    protected void onFragmentPause() {
        super.onFragmentPause();
        pollGen++;
        watchGen++;
        ui.removeCallbacksAndMessages(null);
    }

    /** 给外面（左侧索引按右键）用：焦点落到第一个按钮上 */
    public void focusFirst() {
        View v = vipBtnRefresh != null && vipBtnRefresh.getVisibility() == View.VISIBLE
                ? vipBtnRefresh : vipBtnLogout;
        if (v != null && v.getVisibility() == View.VISIBLE) v.requestFocus();
    }

    // ------------------------------------------------------------------ 界面

    private void setAvatarGuest(boolean guest) {
        if (vipLogo != null) vipLogo.setVisibility(guest ? View.VISIBLE : View.GONE);
        if (vipAvatar != null) vipAvatar.setVisibility(guest ? View.GONE : View.VISIBLE);
    }

    /** 按当前登录态重画整块：登录态只有两个，不做中间态 */
    private void refresh() {
        if (vipName == null) return;
        boolean logged = AccountStore.logged();
        if (logged) {
            String name = AccountStore.name();
            setAvatarGuest(false);
            vipAvatar.setText(name.isEmpty() ? "我" : name.substring(0, 1).toUpperCase());
            vipName.setText(name.isEmpty() ? getString(R.string.vip_name_member) : name);
            /* 副标题只说「已登录」这个事实。以前这里等 profile 回来写「会员 / 普通用户」，
               但那套会员概念用户没要，现在固定一个中性状态，不再随会员状态变。 */
            vipSub.setText(R.string.vip_sub_logged);
            vipDevNum.setText(R.string.vip_num_dash);
            vipLimitNum.setText(R.string.vip_limit_default);
            vipCodeBox.setVisibility(View.GONE);
            vipInfoBox.setVisibility(View.VISIBLE);
            vipBtnRefresh.setVisibility(View.VISIBLE);
            vipBtnRefresh.setText(R.string.vip_btn_reload);
            vipBtnLogout.setVisibility(View.VISIBLE);
            vipInfoLine1.setText(getString(R.string.vip_account_fmt, name));
            vipInfoLine2.setText(R.string.vip_joined_dash);
            loadProfile();
        } else {
            setAvatarGuest(true);
            vipAvatar.setText("游");
            vipName.setText(R.string.vip_name_guest);
            vipSub.setText(R.string.vip_sub_guest);
            vipDevNum.setText(R.string.vip_num_dash);
            /* 游客没有账号，也就无所谓「可绑上限」—— 写个 3 出来只会让人以为
               自己名下已经有额度了，跟左边那个「—」也对不上 */
            vipLimitNum.setText(R.string.vip_num_dash);
            vipInfoBox.setVisibility(View.GONE);
            vipCodeBox.setVisibility(View.VISIBLE);
            vipBtnRefresh.setVisibility(View.VISIBLE);
            vipBtnRefresh.setText(R.string.vip_btn_refresh);
            vipBtnLogout.setVisibility(View.GONE);
            vipCodeTip.setText(R.string.vip_code_tip);
            paintQr();
            startDeviceCode();
            watchLogin();
        }
    }

    /**
     * 未登录时的登录二维码：内容是本机局域网页的地址。
     * 本地服务可能没起来（端口被占 / 系统回收），这时候宁可不显示码，
     * 让用户回到下面那串数字码 —— 显示一个扫不开的二维码比不显示更糟。
     */
    private void paintQr() {
        if (vipQr == null) return;
        String addr = "";
        try {
            ControlManager.get().startServer();
            addr = ControlManager.get().getAddress(false);
        } catch (Throwable ignored) {
        }
        if (addr == null) addr = "";
        addr = addr.trim();
        while (addr.endsWith("/")) {
            addr = addr.substring(0, addr.length() - 1);
        }
        if (addr.isEmpty()) {
            vipQr.setImageDrawable(null);
            return;
        }
        try {
            int px = AutoSizeUtils.mm2px(mContext, 150);
            vipQr.setImageBitmap(QRCodeGen.generateBitmap(addr + "/login", px, px, 2));
        } catch (Throwable th) {
            vipQr.setImageDrawable(null);
        }
    }

    /**
     * 每 1.5 秒看一眼有没有登录上。
     * 网页登录成功后 token 是 AccountStore 直接落的，设备码那边不会知道 ——
     * 两个入口必须有一个共同信号，这里选「本地有没有 token」。
     */
    private void watchLogin() {
        watchGen++;
        final int gen = watchGen;
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (gen != watchGen || !isViewCreated) return;
                if (AccountStore.logged()) {
                    refresh();
                    return;
                }
                ui.postDelayed(this, 1500);
            }
        }, 1500);
    }

    /**
     * 已登录时补一次服务端档案（设备数 / 注册时间 / 身份）。
     * 拿不到就停在「—」，不影响退出登录。
     */
    private void loadProfile() {
        vipDevNum.setText(R.string.vip_num_dash);
        pool.execute(() -> {
            final AccountApi.Res r = AccountApi.profile();
            ui.post(() -> {
                if (!isViewCreated || !AccountStore.logged() || vipDevNum == null) return;
                if (r.ok && r.data != null) {
                    long dev = Math.max(1, r.num("deviceCount", 1));
                    long limit = Math.max(1, r.num("maxDevices", DEVICE_LIMIT));
                    String joined = r.str("createdAt").replace('T', ' ');
                    if (joined.length() > 10) joined = joined.substring(0, 10);
                    vipDevNum.setText(String.valueOf(dev));
                    vipLimitNum.setText(String.valueOf(limit));

                    /* 这一页只负责「登录」，不做会员身份、不算到期、不显示有效期。
                       服务端 app_user 的 vip_level / vip_expire_at 字段仍在库里，
                       但客户端不读也不显示 —— 以后真要收费再启用，现在多一行都是噪音。 */
                    vipSub.setText(R.string.vip_sub_logged);
                    if (!joined.isEmpty()) {
                        vipInfoLine2.setText(getString(R.string.vip_joined_fmt, joined));
                    }
                } else {
                    vipInfoLine2.setText(R.string.vip_net_fail);
                }
            });
        });
    }

    // -------------------------------------------------------------- 数字码登录

    private void startDeviceCode() {
        pollGen++;
        final int gen = pollGen;
        deviceCode = "";
        vipCode.setText(R.string.vip_code_wait);
        vipCodeHint.setText(R.string.vip_code_hint);

        pool.execute(() -> {
            final AccountApi.Res r = AccountApi.deviceCode();
            ui.post(() -> {
                if (gen != pollGen || !isViewCreated || vipCode == null) return;
                if (!r.ok || r.data == null) {
                    vipCode.setText(R.string.vip_code_empty);
                    vipCodeHint.setText(R.string.vip_net_fail);
                    return;
                }
                deviceCode = r.str("deviceCode");
                String code = r.str("code");
                vipCode.setText(code.isEmpty() ? getString(R.string.vip_code_wait) : code);
                pollDevice(gen, 0);
            });
        });
    }

    private void pollDevice(final int gen, final int round) {
        if (gen != pollGen || round >= MAX_POLL) return;
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (gen != pollGen || !isViewCreated) return;
                pool.execute(() -> {
                    final AccountApi.Res r = AccountApi.devicePoll(deviceCode);
                    ui.post(() -> {
                        if (gen != pollGen || !isViewCreated) return;
                        String status = r.str("status");
                        if ("bound".equals(status)) {
                            String token = r.str("token");
                            if (!token.isEmpty()) {
                                AccountStore.save(token, r.num("userId", 0), r.str("username"));
                                Toast.makeText(mContext, R.string.vip_login_ok, Toast.LENGTH_SHORT).show();
                                refresh();
                                return;
                            }
                        }
                        /* expired：码过期了。别继续空转，把提示换成失败原因，
                           用户按「换一个码」就能重来。 */
                        if ("expired".equals(status)) {
                            vipCodeHint.setText(R.string.vip_net_fail);
                            return;
                        }
                        pollDevice(gen, round + 1);
                    });
                });
            }
        }, POLL_MS);
    }

    // ------------------------------------------------------------------ 交互

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.vipBtnRefresh) {
            /* 一颗按钮两种身份：未登录换码，已登录重拉档案 */
            if (AccountStore.logged()) {
                loadProfile();
            } else {
                startDeviceCode();
            }
        } else if (id == R.id.vipBtnLogout) {
            doLogout();
        }
    }

    private void doLogout() {
        pool.execute(() -> {
            try {
                AccountApi.logout();
            } catch (Throwable ignored) {
            }
            AccountStore.clear();
            ui.post(() -> {
                if (!isViewCreated) return;
                Toast.makeText(mContext, R.string.vip_logout_ok, Toast.LENGTH_SHORT).show();
                refresh();
            });
        });
    }

    @Override
    public void onDestroy() {
        pollGen++;
        watchGen++;
        ui.removeCallbacksAndMessages(null);
        pool.shutdownNow();
        super.onDestroy();
    }
}
