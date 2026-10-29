package com.github.tvbox.osc.phone;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

/**
 * 手机版「网盘登录」。
 * <p>
 * 解决的是：玩偶哥哥那批 4K 站点要网盘凭据，源站只会弹一个「请输入 Token」的框，
 * 手机上以前只能手抓 Cookie 或者拿另一台手机扫。
 * <p>
 * 只有一条路：内置 WebView 打开网盘官网，登录成功后自动把凭据取走 ——
 * 用 CookieManager 读 Cookie（含 HttpOnly 的 __puus，浏览器 F12 也读不到那种），
 * 阿里云盘则注入 JS 读 localStorage 里的 refresh_token。
 * <p>
 * 为什么不做「打开网盘 App」：App 的登录态是它自己沙盒里的，既不会把 Cookie/Token
 * 交给别的应用，也不会写进本机 WebView 的 CookieManager —— 跳过去登录完回来照样「未登录」，
 * 用户看到的就是「点了没反应」。手机上还想用 App，只有「另一台设备扫网页上的二维码」这一种，
 * 单机就用手机号 + 验证码。
 * <p>
 * 取到后落盘到 {@link NetdiskStore#syncToSpider(Context)}，同时留在本机给「复制」用。
 */
public class NetdiskLoginActivity extends Activity {

    /**
     * 色板跟着 App 的主题设置走（我的 → 主题：跟随系统 / 深色 / 浅色），
     * 不再写死深色 —— 外面整页是浅色、点进来突然变黑一片，看着像另一个 App。
     * 浅色那套直接对齐 assets/phone/app.css 里 html[data-theme="light"] 的变量值，
     * 否则原生页和 WebView 页会是两种不同的浅色。
     */
    private int BG, CARD, TX, SUB, ACCENT, BAR, BTN, PRI_INK, OKC, WARN;

    /** 阿里云盘网页版登录后 localStorage 里的 token JSON，refresh_token 就是爬虫要的 32 位 token */
    private static final String JS_ALI_TOKEN =
            "(function(){try{"
                    + "var raw=localStorage.getItem('token');"
                    + "if(raw){var o=JSON.parse(raw);if(o&&(o.refresh_token||o.access_token))return o.refresh_token||o.access_token;}"
                    + "for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);var v=localStorage.getItem(k)||'';"
                    + "if(v.indexOf('refresh_token')>=0){try{var j=JSON.parse(v);if(j.refresh_token)return j.refresh_token;}catch(e){}}}"
                    + "return '';}catch(e){return '';}})()";

    /**
     * 必须用桌面 UA。三家网盘的移动网页都被官方锁成「立即下载」落地页
     * （夸克甚至把 pan.quark.cn/* 全部 302 到 h5.sm.cn 的推广页），
     * 页面上只有下载按钮，没有任何登录入口 —— 这就是「跳过去没动作」的原因。
     * 换成桌面 UA 服务端才给真正的网页版（登录页 + 扫码/手机号）。
     */
    private static final String UA_DESKTOP =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    private FrameLayout container;
    private WebView web;
    private ProgressBar webProgress;
    private String curType;
    /** 当前登录页用的是不是桌面 UA（见 desktopByDefault） */
    private boolean desktopUa;
    /** 这一轮已经自动存过凭据，轮询不用再查 */
    private boolean autoSaved;
    /** 登录页打开后定时静默查一次凭据 —— 登录成功自动保存，用户不用惦记着点「完成」 */
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable pollTask;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean dark = applyTheme();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(BG);
        }
        // 浅色底要深色的状态栏图标，不然时间和电量在米色背景上等于看不见
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            View decor = getWindow().getDecorView();
            int flags = decor.getSystemUiVisibility();
            if (dark) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            else flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            decor.setSystemUiVisibility(flags);
        }
        container = new FrameLayout(this);
        container.setBackgroundColor(BG);
        setContentView(container);
        showList();
    }

    /**
     * 和 PhoneActivity.isNightMode() 同一口径（用户手动选的压过系统），
     * 两边判定不一致的话，改完主题会出现「页面变了、这个弹层还是老样子」。
     *
     * @return 当前是否为深色
     */
    private boolean applyTheme() {
        String mode = "system";
        try {
            String m = Hawk.get(HawkConfig.PHONE_THEME_MODE, "system");
            if ("dark".equals(m) || "light".equals(m)) mode = m;
        } catch (Throwable ignored) {
        }
        boolean dark;
        if ("dark".equals(mode)) {
            dark = true;
        } else if ("light".equals(mode)) {
            dark = false;
        } else {
            int m = getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            dark = m != android.content.res.Configuration.UI_MODE_NIGHT_NO;
        }
        if (dark) {
            BG = 0xFF0E0B12;
            CARD = 0xFF1B1620;
            TX = 0xFFF3EEE7;
            SUB = 0xFF9C93A6;
            ACCENT = 0xFFFF7A2F;
            BAR = 0xFF16121C;
            BTN = 0xFF2C2534;
            PRI_INK = 0xFF241005;
            OKC = 0xFF57C88A;
            WARN = 0xFFE0A33A;
        } else {
            // 跟着 app.css 的浅色（冷白 + 橙红）走，别再退回旧的米色那套 ——
            // 外面整页是冷白，这里一片暖米色，看着像两个 App 拼起来的。
            BG = 0xFFFAFBFE;
            CARD = 0xFFFFFFFF;
            TX = 0xFF14161C;
            SUB = 0xFF6A7180;
            ACCENT = 0xFFE8551B;
            BAR = 0xFFF6F7FC;
            BTN = 0xFFEDEFF6;
            PRI_INK = 0xFFFFFFFF;
            OKC = 0xFF2E9E5B;
            WARN = 0xFFB8791F;
        }
        return dark;
    }

    // ---------------------------------------------------------------- 列表页

    private void showList() {
        curType = null;
        stopPoll();
        destroyWeb();          // 回列表就把登录页收掉，否则返回键会被它吃掉
        container.removeAllViews();

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), statusBarHeight() + dp(6), dp(18), dp(28));
        sv.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(10), 0, dp(14));
        TextView back = text("‹", 26, TX);
        back.setPadding(dp(6), 0, dp(14), 0);
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        bar.addView(text("网盘登录", 20, TX));
        root.addView(bar);

        root.addView(subText("4K 站点要的是你的网盘凭据。在这里登录一次，之后自动保存，不用再手抓 Cookie。"));

        root.addView(card(NetdiskStore.QUARK));
        root.addView(card(NetdiskStore.UC));
        root.addView(card(NetdiskStore.ALI));

        TextView tip = subText("说明\n"
                + "· 不用装网盘 App，也别去 App 里登录：App 的登录态在它自己的沙盒里，不会把 Cookie/Token 交给别的应用，跳过去登录完回来照样是「未登录」。\n"
                + "· 「网页登录」在 App 内打开网盘官网，用「手机号 + 验证码」登录即可；登录成功会自动保存并返回，不用再点「完成」。\n"
                + "· 手边有另一台手机或电脑的话，也可以用网盘 App 扫页面上的二维码登录。\n"
                + "· 网盘会按浏览器类型给不同页面：手机版可能只剩「立即下载」，看着像点了没反应。这时点右上角「桌面版 / 手机版」换一个再试，夸克、UC 默认用桌面版。\n"
                + "· 取到的是网页版登录凭据，等同账号密码，只存在本机；源站要 Token 时点「复制」粘进去也一样能用。");
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(18);
        tip.setLineSpacing(0, 1.35f);
        root.addView(tip, tp);

        container.addView(sv, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private View card(String type) {
        final String name = nameOf(type);
        final String url = urlOf(type);
        final String saved = NetdiskStore.get(this, type);
        // 存过不等于登好了：手动「照样保存」可能存下一份访客 Cookie，
        // 界面上必须标出来，否则用户以为已登录，进 4K 站点还是播不了。
        final boolean savedOk = looksValid(type, saved);

        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(CARD, dp(16)));
        c.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView nm = text(name, 16, TX);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        top.addView(nm, np);
        TextView st = text(TextUtils.isEmpty(saved) ? "未登录" : (savedOk ? "已保存" : "可能无效"), 12,
                TextUtils.isEmpty(saved) ? SUB : (savedOk ? OKC : WARN));
        top.addView(st);
        c.addView(top);

        if (!TextUtils.isEmpty(saved)) {
            TextView br = subText(NetdiskStore.brief(saved) + (savedOk ? "" : "　（没带登录态，建议重新登录）"));
            br.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            br.setTextColor(savedOk ? SUB : WARN);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bp.topMargin = dp(6);
            c.addView(br, bp);
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(12);
        row.setLayoutParams(rp);

        row.addView(btn(TextUtils.isEmpty(saved) ? "网页登录" : "重新登录", true,
                v -> openWeb(type, name, url)), weight(1, dp(8)));

        if (!TextUtils.isEmpty(saved)) {
            row.addView(btn("复制", false, v -> copy(saved)), weight(0.8f, dp(8)));
            row.addView(btn("清除", false, v -> {
                NetdiskStore.clear(this, type);
                // 光清凭据不清 Cookie 的话，下次点「重新登录」会立刻被自动保存逻辑
                // 捡回上一次的登录态，等于根本换不了账号。
                try {
                    CookieManager.getInstance().removeAllCookies(null);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        CookieManager.getInstance().flush();
                    }
                } catch (Throwable ignored) {
                }
                toast(name + " 凭据已清除");
                showList();
            }), weight(0.8f, 0));
        }
        c.addView(row);

        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(12);
        c.setLayoutParams(cp);
        return c;
    }

    // ---------------------------------------------------------------- 网页登录

    /**
     * 每个网盘默认用哪种 UA —— 不是拍脑袋，是实测出来的：
     * 夸克、UC 的移动网页都被官方锁成「立即下载」推广落地页（夸克甚至 302 到 h5.sm.cn），
     * 页面上只有下载按钮，一个登录入口都没有，必须桌面 UA 才是网页版；
     * 阿里云盘反过来，手机 UA 就是标准登录页（手机号 + 验证码）。
     * 哪个不对，页面右上角还有按钮随时切。
     */
    private static boolean desktopByDefault(String type) {
        return NetdiskStore.QUARK.equals(type) || NetdiskStore.UC.equals(type);
    }

    private void openWeb(String type, String name, String url) {
        openWeb(type, name, url, desktopByDefault(type));
    }

    private void openWeb(String type, String name, String url, boolean desktop) {
        curType = type;
        desktopUa = desktop;
        autoSaved = false;
        stopPoll();            // 换 UA 会重开一轮，旧轮询先掐掉
        destroyWeb();          // 切 UA 会整页重建，旧的必须先销毁，否则 WebView 一直堆着
        container.removeAllViews();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(0, statusBarHeight(), 0, 0);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(BAR);
        bar.setPadding(dp(10), dp(8), dp(10), dp(8));

        TextView back = text("‹", 26, TX);
        back.setPadding(dp(8), 0, dp(12), 0);
        back.setOnClickListener(v -> showList());
        bar.addView(back);

        TextView title = text(name + " 登录", 15, TX);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        // 桌面/手机版切换：服务端按 UA 给不同页面，哪边没登录入口就切另一边。
        // 必须整页重建 —— 只 reload 的话 UA 改了也常拿到缓存里的老页面。
        TextView uaBtn = text(desktopUa ? "手机版" : "桌面版", 13, SUB);
        uaBtn.setPadding(dp(10), dp(6), dp(10), dp(6));
        uaBtn.setOnClickListener(v -> openWeb(type, name, url, !desktopUa));
        bar.addView(uaBtn);

        TextView done = text("完成", 15, ACCENT);
        done.setPadding(dp(12), dp(6), dp(8), dp(6));
        done.setOnClickListener(v -> capture());
        bar.addView(done);
        root.addView(bar);

        // 登录页上方常驻一行提示：告诉用户「登完会自动返回」，否则他会一直等、
        // 或者反复点「完成」，体感就是「跳过去以后没动静」。
        TextView hint = text("登录成功后会自动保存并返回，不用手动点「完成」", 12, SUB);
        hint.setPadding(dp(14), dp(7), dp(14), dp(7));
        hint.setBackgroundColor(BAR);
        root.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        webProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        webProgress.setMax(100);
        root.addView(webProgress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // 阿里云盘的 token 在 localStorage 里
        s.setDatabaseEnabled(true);
        s.setUserAgentString(desktopUa ? UA_DESKTOP : null);   // null = 系统默认（手机 UA）
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        // 桌面版页面是给宽屏排的，手机上必须能双指放大，否则登录框点不准
        s.setSupportZoom(desktopUa);
        s.setBuiltInZoomControls(desktopUa);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cm.setAcceptThirdPartyCookies(web, true);
        }
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String u) {
                if (desktopUa) fitDesktop(v);
                // 登录成功后页面会跳转/重载，这里顺手捡一次凭据
                tryAutoCapture(v);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String u) {
                // 非 http 的自定义 scheme（网盘 App 的深链）交给系统，别在 WebView 里卡住
                if (!u.startsWith("http")) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
                        return true;
                    } catch (Throwable ignored) {
                        return true;
                    }
                }
                return false;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                if (webProgress != null) {
                    webProgress.setProgress(p);
                    webProgress.setVisibility(p >= 100 ? View.INVISIBLE : View.VISIBLE);
                }
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        container.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.loadUrl(url);
        startPoll();
    }

    // ---------------------------------------------------------------- 自动保存

    /**
     * 每 3 秒静默查一次凭据，最多查 5 分钟。
     * 阿里云盘登录完是 SPA 内跳转，不会再触发 onPageFinished，只能靠轮询。
     * 查到了就自动存并回列表 —— 用户不用再点「完成」。
     */
    private void startPoll() {
        stopPoll();
        pollTask = new Runnable() {
            private int tick;

            @Override
            public void run() {
                if (web == null || curType == null || autoSaved) return;
                tryAutoCapture(web);
                tick++;
                if (tick < 100 && web != null && !autoSaved) ui.postDelayed(this, 3000);
            }
        };
        ui.postDelayed(pollTask, 2500);
    }

    private void stopPoll() {
        if (pollTask != null) {
            ui.removeCallbacks(pollTask);
            pollTask = null;
        }
    }

    /** 拿得到「真登录过」的凭据就存下来；拿不到什么都不做（不能打扰正在输验证码的人）。 */
    private void tryAutoCapture(WebView v) {
        if (v == null || curType == null || autoSaved) return;
        if (NetdiskStore.ALI.equals(curType)) {
            v.evaluateJavascript(JS_ALI_TOKEN, value -> {
                String token = unquote(value);
                if (!autoSaved && !TextUtils.isEmpty(token) && token.length() >= 20) {
                    autoSaved = true;
                    done(NetdiskStore.ALI, token, true);
                }
            });
            return;
        }
        String ck = CookieManager.getInstance().getCookie(urlOf(curType));
        if (looksLoggedIn(ck)) {
            autoSaved = true;
            done(curType, ck, true);
        }
    }

    /**
     * 桌面版的坑：有的站（UC 网盘）桌面页自带 {@code width=device-width}，
     * 布局按手机宽度排却用桌面尺寸，右侧直接被裁掉 —— 「登录」按钮就在被裁的那半边，
     * 表现就是「跳过去以后没东西可点」。
     * <p>
     * 试过改 viewport meta，页面自己会重置回去；改成给根元素加 CSS zoom，
     * 按 1280 的桌面宽度等比缩进屏幕。没有 device-width 的页面（夸克桌面版）
     * 本来就能正常显示，不碰。
     */
    private void fitDesktop(WebView v) {
        v.evaluateJavascript("(function(){try{"
                + "var m=document.querySelector('meta[name=viewport]');"
                + "if(!m||(m.content||'').indexOf('device-width')<0)return 'skip';"
                + "var w=window.innerWidth||document.documentElement.clientWidth||0;"
                + "if(!w)return 'now';"
                + "var z=w/1280;if(z>=0.98)return 'fit';"
                + "document.documentElement.style.zoom=z.toFixed(3);"
                + "return 'zoom'+z;}catch(e){return 'err';}})()", null);
    }

    /** 从当前登录页取凭据 */
    private void capture() {
        if (web == null || curType == null) return;
        if (NetdiskStore.ALI.equals(curType)) {
            web.evaluateJavascript(JS_ALI_TOKEN, value -> {
                String token = unquote(value);
                if (TextUtils.isEmpty(token) || token.length() < 20) {
                    toast("还没读到凭据，请确认已经登录完成，再点一次「完成」");
                    return;
                }
                done(NetdiskStore.ALI, token, true);
            });
            return;
        }
        String url = urlOf(curType);
        String ck = CookieManager.getInstance().getCookie(url);
        if (TextUtils.isEmpty(ck)) {
            toast("还没登录完成，先在页面上登录，再点「完成」");
            return;
        }
        if (looksLoggedIn(ck)) {
            done(curType, ck, true);
            return;
        }
        // 未登录时网盘也会下发 cookie（UC 就是发一个 ctoken），
        // 只看「cookie 非空」会把这种半成品当成登录成功存下来，
        // 之后 4K 站点照样播不了，用户还以为自己登好了。这里问一句。
        new android.app.AlertDialog.Builder(this)
                .setTitle("没检测到登录状态")
                .setMessage("只拿到了普通的站点 Cookie，没有登录凭据。\n"
                        + "通常是还没登录完（或二维码没扫）。\n\n"
                        + "确定已登录的话可以照样保存，但很可能在站点里用不了。")
                .setPositiveButton("照样保存", (d, w) -> done(curType, ck, false))
                .setNegativeButton("再去登录", null)
                .show();
    }

    /**
     * cookie 里有没有「真的登录过」的标记。
     * 只能认 __puus / __pus —— 这是阿里系（夸克、UC 同账号体系）登录后才下发的 HttpOnly 会话。
     * 别拿 b-user-id 当依据：实测未登录的 UC 页面就已经发了 b-user-id、ctoken、
     * UDRIVE_TRANSFER_SESS 几个访客 cookie，按它判断会把「没登录」当成功保存。
     */
    private static boolean looksLoggedIn(String ck) {
        if (TextUtils.isEmpty(ck)) return false;
        String s = ck.toLowerCase();
        return s.contains("__puus") || s.contains("__pus");
    }

    /** 已保存的凭据像不像「真的登录过」。只用于界面提示，不拦着用户存。 */
    private static boolean looksValid(String type, String value) {
        if (TextUtils.isEmpty(value)) return false;
        if (NetdiskStore.ALI.equals(type)) return value.length() >= 20;
        return looksLoggedIn(value);
    }

    private void done(String type, String value, boolean verified) {
        NetdiskStore.save(this, type, value);
        // 爬虫是启动/重建站点时读文件的，存完不重进一次看不到效果 —— 不说清楚，
        // 用户会以为「保存了但没生效」，又跑回来问一遍。
        Toast.makeText(this, nameOf(type) + (verified ? " 登录成功，凭据已保存" : " 凭据已保存")
                + "，回源站重进 4K 分类即可生效", Toast.LENGTH_LONG).show();
        // 别在 WebView 自己的 JS 回调里把它拆了，下一帧再回列表
        container.post(this::showList);
    }

    // ---------------------------------------------------------------- 工具

    private static String nameOf(String type) {
        if (NetdiskStore.QUARK.equals(type)) return "夸克网盘";
        if (NetdiskStore.UC.equals(type)) return "UC 网盘";
        return "阿里云盘";
    }

    private static String urlOf(String type) {
        if (NetdiskStore.QUARK.equals(type)) return "https://pan.quark.cn/";
        if (NetdiskStore.UC.equals(type)) return "https://drive.uc.cn/";
        return "https://www.alipan.com/drive";
    }

    private void copy(String s) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("netdisk", s));
            toast("已复制，粘到源站的输入框即可");
        } catch (Throwable th) {
            toast("复制失败");
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private TextView subText(String s) {
        TextView t = text(s, 13, SUB);
        t.setLineSpacing(0, 1.3f);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(4);
        t.setLayoutParams(p);
        return t;
    }

    private View btn(String label, boolean primary, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTextColor(primary ? PRI_INK : TX);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(9), 0, dp(9));
        t.setBackground(round(primary ? ACCENT : BTN, dp(11)));
        t.setOnClickListener(l);
        return t;
    }

    private LinearLayout.LayoutParams weight(float w, int rightMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
        p.rightMargin = rightMargin;
        return p;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private static String unquote(String s) {
        if (s == null) return "";
        String v = s.trim();
        if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
            v = v.substring(1, v.length() - 1);
            v = v.replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return "null".equals(v) ? "" : v;
    }

    /**
     * 返回键必须在这里拦。
     * <p>
     * 只在 Activity 的 onKeyDown 里判断是拦不住的：有焦点的 WebView 会先把 BACK 消费掉
     * （页面内部做 history 回退），Activity 的 onKeyDown 根本收不到 ——
     * 表现就是「按返回没反应，得连按两三次才出来」。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (web != null) {
                    if (web.canGoBack()) web.goBack();
                    else showList();
                } else {
                    finish();
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 去短信抄验证码、或者切到别的应用再回来时，登录态可能已经好了 ——
        // 立刻补一次，别让用户干等着 3 秒轮询。
        if (web != null && curType != null && !autoSaved) tryAutoCapture(web);
    }

    @Override
    protected void onDestroy() {
        stopPoll();
        destroyWeb();
        super.onDestroy();
    }

    /** 销毁 WebView。cookie 已经落到 CookieManager 的持久库里，不跟着销毁。 */
    private void destroyWeb() {
        if (web == null) return;
        try {
            web.stopLoading();
            web.setWebChromeClient(null);
            web.setWebViewClient(null);
            if (container != null) container.removeView(web);
            web.destroy();
            // 登录成功立刻回列表时，内存里的 cookie 可能还没落盘；
            // 不 flush 的话下次打开网页又要重新登一次。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                CookieManager.getInstance().flush();
            }
        } catch (Throwable ignored) {
        }
        web = null;
    }
}
