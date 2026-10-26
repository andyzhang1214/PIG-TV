package com.github.tvbox.osc.phone;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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

/**
 * 手机版「网盘登录」。
 * <p>
 * 解决的是：玩偶哥哥那批 4K 站点要网盘凭据，源站只会弹一个「请输入 Token」的框，
 * 手机上以前只能手抓 Cookie 或者拿另一台手机扫。这里给两条路：
 * <ul>
 *   <li>打开网盘 App —— 手机上直接跳过去登录（App 自己不会回传凭据，回来还得走走网页那条）；</li>
 *   <li>网页登录 —— 内置 WebView 打开网盘官网，登录完点「完成」，
 *       用 CookieManager 把 Cookie（含 HttpOnly 的 __puus，浏览器 F12 也读不到那种）取出来，
 *       阿里云盘则注入 JS 读 localStorage 里的 refresh_token。</li>
 * </ul>
 * 取到后落盘到 {@link NetdiskStore#syncToSpider(Context)}，同时留在本机给「复制」用。
 */
public class NetdiskLoginActivity extends Activity {

    private static final int BG = 0xFF0E0B12;
    private static final int CARD = 0xFF1B1620;
    private static final int TX = 0xFFF3EEE7;
    private static final int SUB = 0xFF9C93A6;
    private static final int ACCENT = 0xFFFF7A2F;

    /** 阿里云盘网页版登录后 localStorage 里的 token JSON，refresh_token 就是爬虫要的 32 位 token */
    private static final String JS_ALI_TOKEN =
            "(function(){try{"
                    + "var raw=localStorage.getItem('token');"
                    + "if(raw){var o=JSON.parse(raw);if(o&&(o.refresh_token||o.access_token))return o.refresh_token||o.access_token;}"
                    + "for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);var v=localStorage.getItem(k)||'';"
                    + "if(v.indexOf('refresh_token')>=0){try{var j=JSON.parse(v);if(j.refresh_token)return j.refresh_token;}catch(e){}}}"
                    + "return '';}catch(e){return '';}})()";

    private FrameLayout container;
    private WebView web;
    private ProgressBar webProgress;
    private String curType;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(BG);
        }
        container = new FrameLayout(this);
        container.setBackgroundColor(BG);
        setContentView(container);
        showList();
    }

    // ---------------------------------------------------------------- 列表页

    private void showList() {
        curType = null;
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
                + "· 「网页登录」在 App 内打开网盘官网，登录完点右上角「完成」，凭据自动取走并保存。\n"
                + "· 「打开 App」只是帮你跳到网盘 App 登录，App 不会把凭据交出来 —— 想要免手填还是走网页登录。\n"
                + "· 取到的是网页版登录凭据，等同账号密码，只存在本机；源站要 Token 时点「复制凭据」粘进去也一样能用。");
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
        TextView st = text(TextUtils.isEmpty(saved) ? "未登录" : "已保存", 12,
                TextUtils.isEmpty(saved) ? SUB : 0xFF57C88A);
        top.addView(st);
        c.addView(top);

        if (!TextUtils.isEmpty(saved)) {
            TextView br = subText(NetdiskStore.brief(saved));
            br.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bp.topMargin = dp(6);
            c.addView(br, bp);
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(12);
        row.setLayoutParams(rp);

        row.addView(btn("打开 App", false, v -> {
            if (!launchApp(type)) {
                toast("没找到" + name + "，请先在应用商店安装");
            }
        }), weight(1, dp(8)));
        row.addView(btn("网页登录", true, v -> openWeb(type, name, url)), weight(1, dp(8)));

        if (!TextUtils.isEmpty(saved)) {
            row.addView(btn("复制", false, v -> copy(saved)), weight(0.8f, dp(8)));
            row.addView(btn("清除", false, v -> {
                NetdiskStore.clear(this, type);
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

    private void openWeb(String type, String name, String url) {
        curType = type;
        container.removeAllViews();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(0, statusBarHeight(), 0, 0);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF16121C);
        bar.setPadding(dp(10), dp(8), dp(10), dp(8));

        TextView back = text("‹", 26, TX);
        back.setPadding(dp(8), 0, dp(12), 0);
        back.setOnClickListener(v -> showList());
        bar.addView(back);

        TextView title = text(name + " 登录", 15, TX);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView done = text("完成", 15, ACCENT);
        done.setPadding(dp(12), dp(6), dp(8), dp(6));
        done.setOnClickListener(v -> capture());
        bar.addView(done);
        root.addView(bar);

        webProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        webProgress.setMax(100);
        root.addView(webProgress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // 阿里云盘的 token 在 localStorage 里
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
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
                done(NetdiskStore.ALI, token);
            });
            return;
        }
        String url = urlOf(curType);
        String ck = CookieManager.getInstance().getCookie(url);
        if (TextUtils.isEmpty(ck) || (NetdiskStore.QUARK.equals(curType) && !ck.contains("__puus") && !ck.contains("__pus") && !ck.contains("b-user-id"))) {
            toast("还没登录完成，先在页面上登录，再点「完成」");
            return;
        }
        done(curType, ck);
    }

    private void done(String type, String value) {
        NetdiskStore.save(this, type, value);
        toast(nameOf(type) + " 已保存");
        // 别在 WebView 自己的 JS 回调里把它拆了，下一帧再回列表
        container.post(this::showList);
    }

    // ---------------------------------------------------------------- 工具

    private boolean launchApp(String type) {
        PackageManager pm = getPackageManager();
        for (String pkg : packagesOf(type)) {
            try {
                Intent it = pm.getLaunchIntentForPackage(pkg);
                if (it != null) {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(it);
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        for (String scheme : schemesOf(type)) {
            try {
                Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(scheme));
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(it);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

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

    private static String[] packagesOf(String type) {
        if (NetdiskStore.QUARK.equals(type)) return new String[]{"com.quark.browser", "com.quark.browser.lite"};
        if (NetdiskStore.UC.equals(type)) return new String[]{"com.UCMobile", "com.ucmobile"};
        return new String[]{"com.alicloud.databox"};
    }

    private static String[] schemesOf(String type) {
        if (NetdiskStore.QUARK.equals(type)) return new String[]{"quark://", "quarkbrowser://"};
        if (NetdiskStore.UC.equals(type)) return new String[]{"uc://"};
        return new String[]{"alipan://", "alipans://"};
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
        t.setTextColor(primary ? 0xFF241005 : TX);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(9), 0, dp(9));
        t.setBackground(round(primary ? ACCENT : 0xFF2C2534, dp(11)));
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

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (web != null) {
                if (web.canGoBack()) {
                    web.goBack();
                    return true;
                }
                showList();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
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
        } catch (Throwable ignored) {
        }
        web = null;
    }
}
