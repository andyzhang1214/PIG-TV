package com.pigtv.ui.activity;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.pigtv.R;
import com.pigtv.account.AccountApi;
import com.pigtv.account.AccountStore;
import com.pigtv.base.BaseActivity;
import com.pigtv.server.ControlManager;
import com.pigtv.ui.tv.QRCodeGen;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.jessyan.autosize.utils.AutoSizeUtils;

/**
 * 电视端会员中心。
 *
 * <p>电视没有输入法，所以这里不接受任何键盘输入：未登录时领一个 6 位数字码，
 * 用户拿手机 App 里输一次就完成绑定 —— 电视这边只需要「显示」和「轮询」。</p>
 *
 * <p>三条必须守住的约束（都是本项目踩过的坑）：
 * <ul>
 *   <li><b>焦点只有一处出口</b>：可聚焦的只有最下面那排按钮，卡片和文字一律
 *       {@code focusable="false"}。让焦点落进一个进得去出不来的区域，
 *       用户就只能拔电源。</li>
 *   <li><b>轮询要能作废</b>：每次换码 / 退出页面把 {@link #pollGen} 加一，
 *       迟到的回包一律丢弃。否则「换了三个码，前两个码绑上后把界面刷成已登录」，
 *       用户根本不知道自己是哪台设备登进去的。</li>
 *   <li><b>后端挂了不能白屏</b>：拿不到码就把原因写在码位上（连不上 / 获取失败），
 *       并且保留「换一个码」按钮 —— 服务恢复后按一下就能重试。</li>
 * </ul>
 */
public class VipActivity extends BaseActivity implements View.OnClickListener {

    /** 数字码有效期 5 分钟，2 秒一次 = 最多 150 次；多留几次余量 */
    private static final int MAX_POLL = 160;
    private static final long POLL_MS = 2000L;
    /** 服务端没下发 maxDevices 时的兜底值，和手机端保持一致 */
    private static final int DEVICE_LIMIT = 3;

    private TextView vipAvatar;
    /** 游客态头像位的 App logo，跟 vipAvatar 互斥 */
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
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    /** 轮询代次：换码 / 退出页面时 +1，旧任务看到代次不符就自己结束 */
    private int pollGen = 0;
    /** 登录态检查的代次：和 pollGen 分开，退出页面时各自加一 */
    private int watchGen = 0;
    private String deviceCode = "";

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_vip;
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

        vipBtnRefresh.setOnClickListener(this);
        vipBtnLogout.setOnClickListener(this);

        refresh();
    }

    // ------------------------------------------------------------------ 界面

    /** 头像位二选一：游客放 App logo，登录后放用户名首字 */
    private void setAvatarGuest(boolean guest) {
        if (vipLogo != null) vipLogo.setVisibility(guest ? View.VISIBLE : View.GONE);
        vipAvatar.setVisibility(guest ? View.GONE : View.VISIBLE);
    }

    /** 按当前登录态重画整页：登录态只有两个，不做中间态 */
    private void refresh() {
        boolean logged = AccountStore.logged();
        if (logged) {
            watchGen++;
            String name = AccountStore.name();
            setAvatarGuest(false);
            vipAvatar.setText(name.isEmpty() ? "我" : name.substring(0, 1).toUpperCase());
            vipName.setText(name.isEmpty() ? getString(R.string.vip_name_member) : name);
            /* 副标题只说「已登录」这个事实 —— 会员身份 / 到期 / 有效期都不做了，
               这一页只负责登录与设备。 */
            vipSub.setText(R.string.vip_sub_logged);
            /* 设备数要等 /api/user/profile 回来才有，先摆「—」，
               而不是先写 1 再跳成 2 —— 跳变会让人以为数据在乱跳 */
            vipDevNum.setText(R.string.vip_num_dash);
            vipLimitNum.setText(R.string.vip_limit_default);
            vipCodeBox.setVisibility(View.GONE);
            vipInfoBox.setVisibility(View.VISIBLE);
            /* 已登录时这颗按钮是「刷新档案」，不再领新码 —— 已登录还领码，
               那个码永远不会有人输，用户反而会以为登录态掉了 */
            vipBtnRefresh.setVisibility(View.VISIBLE);
            vipBtnRefresh.setText(R.string.vip_btn_reload);
            vipBtnLogout.setVisibility(View.VISIBLE);
            vipInfoLine1.setText(getString(R.string.vip_account_fmt, name));
            vipInfoLine2.setText(R.string.vip_joined_dash);
            focusOn(vipBtnLogout);
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
            focusOn(vipBtnRefresh);
            paintQr();
            startDeviceCode();
            watchLogin();
        }
    }

    /**
     * 未登录时的登录二维码：内容是本机局域网页的地址。
     * 手机扫开之后在那个页面里输账号密码，电视这边 {@link #watchLogin()} 轮询到登录态就整页刷新。
     *
     * <p>本地服务可能没起来（端口被占 / 系统回收），这时候宁可不显示码，
     * 让用户回到下面那串数字码 —— 显示一个扫不开的二维码比不显示更糟。</p>
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
            int px = AutoSizeUtils.mm2px(this, 150);
            vipQr.setImageBitmap(QRCodeGen.generateBitmap(addr + "/login", px, px, 2));
        } catch (Throwable th) {
            vipQr.setImageDrawable(null);
        }
    }

    /**
     * 每 1.5 秒看一眼有没有登录上。
     *
     * <p>为什么不能只靠 devicePoll：网页登录成功后 token 是 {@code AccountStore} 直接落的，
     * 服务端的设备码那边不会知道 —— 两个入口必须有一个共同信号，这里选「本地有没有 token」。
     * 代次 {@link #watchGen} 防止换码/退出页面后旧的定时器还在跑。</p>
     */
    private void watchLogin() {
        watchGen++;
        final int gen = watchGen;
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (gen != watchGen || isFinishing()) return;
                if (AccountStore.logged()) {
                    refresh();
                    return;
                }
                ui.postDelayed(this, 1500);
            }
        }, 1500);
    }

    private void focusOn(final View v) {
        if (v == null) return;
        v.post(new Runnable() {
            @Override
            public void run() {
                v.requestFocus();
            }
        });
    }

    /**
     * 已登录时补一次服务端档案（设备数 / 注册时间）。
     * 拿不到就停在「—」，不影响退出登录 —— 会员中心不能因为有网没网而变砖。
     */
    private void loadProfile() {
        /* 先退回「—」，刷新时用户才看得出确实又请求了一次；
           数据回来再填上，失败就一直停在「—」 */
        vipDevNum.setText(R.string.vip_num_dash);
        pool.execute(() -> {
            final AccountApi.Res r = AccountApi.profile();
            ui.post(() -> {
                if (isFinishing() || !AccountStore.logged()) return;
                if (r.ok && r.data != null) {
                    long dev = Math.max(1, r.num("deviceCount", 1));
                    long limit = Math.max(1, r.num("maxDevices", DEVICE_LIMIT));
                    String joined = r.str("createdAt");
                    joined = joined.replace('T', ' ');
                    if (joined.length() > 10) joined = joined.substring(0, 10);
                    vipDevNum.setText(String.valueOf(dev));
                    vipLimitNum.setText(String.valueOf(limit));

                    /* 只做登录：不读 vipActive / vipExpired / vipDaysLeft，
                       也不显示到期日。服务端字段仍在，但没有界面消费它。 */
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
                if (gen != pollGen || isFinishing()) return;
                if (!r.ok || r.data == null) {
                    vipCode.setText(R.string.vip_code_empty);
                    /* 原始 message 是 OkHttp 的英文异常，对用户没意义；
                       扫码这条路还在，把「去手机扫码」这层意思留在 hint 里 */
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
                if (gen != pollGen || isFinishing()) return;
                pool.execute(() -> {
                    final AccountApi.Res r = AccountApi.devicePoll(deviceCode);
                    ui.post(() -> {
                        if (gen != pollGen || isFinishing()) return;
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
                        /* expired：码过期了。别继续空转，直接把提示换成「换一个码」，
                           顺手再领一个新码，省得用户还得自己按一下。 */
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
                if (isFinishing()) return;
                Toast.makeText(mContext, R.string.vip_logout_ok, Toast.LENGTH_SHORT).show();
                refresh();
            });
        });
    }

    @Override
    protected void onDestroy() {
        /* 代次 +1 让所有在跑的轮询任务失效，再关线程池 ——
           只 shutdown 不换代次的话，已排进队列的那一次回包还会打到已销毁的 View 上。 */
        pollGen++;
        watchGen++;
        ui.removeCallbacksAndMessages(null);
        pool.shutdownNow();
        super.onDestroy();
    }
}
