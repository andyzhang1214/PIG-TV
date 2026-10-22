package com.github.tvbox.osc.phone;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.orhanobut.hawk.Hawk;

import me.jessyan.autosize.internal.CancelAdapt;

/**
 * 手机版主界面：一个竖屏 WebView 承载手机 UI，数据全部走 PhoneBridge。
 * 播放交给原生 PlayActivity（保留 Box 全部的解析 / 嗅探 / 多播放器能力）。
 *
 * 实现 CancelAdapt 很重要：Box 全局用 autosize 以「物理毫米」为单位适配电视，
 * 一旦套到 WebView 上，WebView 的 density 会被改掉，CSS 像素会整体缩水。
 * 这里必须关掉，页面才会按手机真实的 devicePixelRatio 渲染。
 *
 * 另外两处防御（没有真机可测逼出来的）：
 *  - onCreate 整个包在 try/catch 里，出错时把堆栈画在屏幕上，而不是静默闪退；
 *  - CrashHandler 会在下次启动时把上一次的崩溃报告弹出来。
 */
public class PhoneActivity extends AppCompatActivity implements CancelAdapt {

    private WebView web;
    private PhoneBridge bridge;
    private SourceViewModel vm;
    private View splash;
    private TextView splashTip;
    private long lastBackAt = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 越早装越好，后面每一步崩了都能留下现场
        CrashHandler.install(this);

        try {
            initAll();
        } catch (Throwable t) {
            CrashHandler.logSoft("PhoneActivity.initAll", t);
            showFatal(t);
        }
    }

    // ==================================================================

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void initAll() {
        // 平板跟随重力感应旋转，手机仍然锁竖屏。
        // 判定用 smallestScreenWidthDp（不随旋转变化），阈值 600dp 是 Android 官方的平板分界。
        // 注意 configChanges 里已经有 orientation|screenSize，所以旋转不会重建 Activity，
        // WebView 里的列表、滚动位置、打开着的详情都原地保留，只换一套 CSS。
        try {
            Configuration cfg = getResources().getConfiguration();
            boolean isPad = cfg.smallestScreenWidthDp >= 600;
            setRequestedOrientation(isPad
                    ? ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                    : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        } catch (Throwable ignored) {
            // 个别 ROM 在窗口不透明性判定上有 bug，宁可锁不住方向也不能崩
        }

        // 沉浸式：内容延伸到状态栏 / 导航栏底下，安全区用 insets 补成 padding
        try {
            Window w = getWindow();
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (Build.VERSION.SDK_INT >= 21) {
                w.setStatusBarColor(Color.TRANSPARENT);
                w.setNavigationBarColor(Color.TRANSPARENT);
                w.getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            }
            if (Build.VERSION.SDK_INT >= 28) {
                WindowManager.LayoutParams lp = w.getAttributes();
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                w.setAttributes(lp);
            }
        } catch (Throwable ignored) {
        }

        // 手动选了浅色而系统是深色时，主题里的 windowBackground 会按 values-night 取到深色，
        // 冷启动第一帧会先黑一下再刷白。这里按解析后的实际主题提前改掉，早于 setContentView。
        try {
            getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(
                            isNightMode() ? 0xFF07070A : 0xFFF5F1EC));
        } catch (Throwable ignored) {
        }

        setContentView(R.layout.activity_phone);

        // ------------------------------------------------------------------
        // 原版只有 HomeActivity.init() 会起这个本地 HTTP 服务（127.0.0.1:9978）。
        // 手机版直接进 PhoneActivity，永远走不到那里，结果是：
        //   · jar 爬虫返回的 proxy:// 地址指向一个没人监听的端口 → 播放必失败
        //   · ApiConfig 里 clan://localhost/ 的本地配置文件也读不出来
        //   · IjkMediaPlayer.setDotPort() 没被调用，DoH 解析同样失效
        // 影视仓（TV 版）能播、这里不能播，差异就在这里。
        // ------------------------------------------------------------------
        try {
            ControlManager.get().startServer();
        } catch (Throwable th) {
            CrashHandler.logSoft("PhoneActivity.startServer", th);
        }

        splash = findViewById(R.id.splash);
        splashTip = findViewById(R.id.splashTip);
        web = findViewById(R.id.webView);
        if (web == null) throw new IllegalStateException("activity_phone.xml 里没有 webView");

        initWebSettings();
        applyThemeChrome(isNightMode());

        // 沉浸式下 WebView 会铺到状态栏/导航栏底下。
        // 上下不再用 padding：WebView 的 padding 管不到 CSS 里的 position:fixed 元素
        // （底部 Tab、详情弹层、搜索层全都 fixed），会让它们压到导航栏上。
        // 上下安全区改成把像素值注入成 CSS 变量 --sat / --sab，由样式统一消费；
        // 左右仍然用 padding —— 平板横屏时挖孔在侧边，这一条 WebView padding 就够。
        try {
            web.setOnApplyWindowInsetsListener((v, insets) -> {
                try {
                    if (Build.VERSION.SDK_INT >= 20) {
                        v.setPadding(
                                insets.getSystemWindowInsetLeft(),
                                0,
                                insets.getSystemWindowInsetRight(),
                                0);
                    }
                } catch (Throwable ignored) {
                }
                return insets;
            });
            web.requestApplyInsets();
        } catch (Throwable ignored) {
        }

        vm = new SourceViewModel();
        bridge = new PhoneBridge(this, web, vm);
        web.addJavascriptInterface(bridge, "AndroidPhone");

        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                hideSplash();
                injectInsets();
                injectTheme(isNightMode());
                loadConfig();
                showLastCrashIfAny();
            }
        });

        web.loadUrl("file:///android_asset/phone/index.html?theme=" + (isNightMode() ? "dark" : "light"));

        // 兜底：3 秒还没回调就把壳亮出来
        new android.os.Handler(getMainLooper()).postDelayed(this::hideSplash, 3000);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void initWebSettings() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadsImagesAutomatically(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setUseWideViewPort(false);
        s.setLoadWithOverviewMode(false);
        if (Build.VERSION.SDK_INT >= 21) {
            // 片源大量是 http，不放开混合内容会白屏
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        // 页面自己就有深浅两套配色（CSS 里 :root 声明了 color-scheme），
        // 绝不能让 WebView 再算法暗化一遍，否则深色会被翻两次、浅色会被强行涂黑。
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                s.setForceDark(WebSettings.FORCE_DARK_OFF);
            } catch (Throwable ignored) {
            }
        }
        web.setBackgroundColor(isNightMode() ? 0xFF07070A : 0xFFF5F1EC);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
    }

    // ==================================================================
    //  主题：跟随系统 / 强制深色 / 强制浅色
    // ==================================================================

    /** 用户选的模式：system / dark / light（脏值一律按 system 处理） */
    private String themeMode() {
        try {
            String m = Hawk.get(HawkConfig.PHONE_THEME_MODE, "system");
            if ("dark".equals(m) || "light".equals(m)) return m;
        } catch (Throwable ignored) {
        }
        return "system";
    }

    /** 系统当前是不是深色。UI_MODE_NIGHT_UNDEFINED 按深色处理，保持原来的观感。 */
    private boolean systemNight() {
        try {
            int m = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return m != Configuration.UI_MODE_NIGHT_NO;
        } catch (Throwable th) {
            return true;
        }
    }

    /**
     * 最终生效的深浅：用户手动选过就以用户为准，只有 system 才看系统。
     * 这是全 App 的唯一判据 —— 别再直接读 uiMode，否则手动模式会被系统覆盖。
     */
    private boolean isNightMode() {
        String mode = themeMode();
        if ("dark".equals(mode)) return true;
        if ("light".equals(mode)) return false;
        return systemNight();
    }

    /**
     * 供 PhoneBridge 调用：用户改了主题。
     * 只重刷原生 chrome + 给页面重新注入 data-theme，不重建 Activity
     * （重建会让 WebView 重新加载，当前 tab / 滚动位置全丢）。
     *
     * @return 切换后是否为深色
     */
    public boolean setThemeMode(String mode) {
        String m = ("dark".equals(mode) || "light".equals(mode)) ? mode : "system";
        Hawk.put(HawkConfig.PHONE_THEME_MODE, m);
        boolean dark = isNightMode();
        runOnUiThread(() -> {
            applyThemeChrome(dark);
            injectTheme(dark);
        });
        return dark;
    }

    public boolean isNightModeNow() {
        return isNightMode();
    }

    /**
     * 窗口 / 启动页 / WebView 的底色跟着主题走。
     * 这几处必须原生改：WebView 的底色在页面绘制之前就已经露出，
     * 浅色主题下如果还是 #07070A，冷启动会先黑一下再变白。
     */
    @SuppressWarnings("deprecation")
    private void applyThemeChrome(boolean dark) {
        // 色值必须自己算，不能读 R.color.phone_bg：
        // values-night 是按「系统」深浅选的，用户手动强制浅色而系统是深色时，
        // 资源会给出夜间色，正好把用户的选择反着做。手算才能让手动模式说了算。
        int bg = dark ? 0xFF07070A : 0xFFF5F1EC;
        int fg = dark ? 0xFFFFFFFF : 0xFF17181C;
        try {
            getWindow().getDecorView().setBackgroundColor(bg);
        } catch (Throwable ignored) {
        }
        if (splash != null) splash.setBackgroundColor(bg);
        try {
            TextView title = findViewById(R.id.splashTitle);
            if (title != null) title.setTextColor(fg);
        } catch (Throwable ignored) {
        }
        if (splashTip != null) splashTip.setTextColor(dark ? 0x66FFFFFF : 0x8A17181C);
        if (web != null) web.setBackgroundColor(bg);
        applySystemBarIcons(dark);
    }

    /** 浅色背景上系统栏要用深色图标，否则状态栏的白色时间/信号直接看不见 */
    @SuppressWarnings("deprecation")
    private void applySystemBarIcons(boolean dark) {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            View decor = getWindow().getDecorView();
            int vis = decor.getSystemUiVisibility();
            if (dark) {
                vis &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= 26) {
                    vis &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            } else {
                vis |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= 26) {
                    vis |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            }
            decor.setSystemUiVisibility(vis);
        } catch (Throwable ignored) {
        }
    }

    /** 把主题告诉前端：CSS 只认 data-theme，不做任何重排，只是换一套变量 */
    private void injectTheme(boolean dark) {
        if (web == null) return;
        String js = "(function(){try{var d=document.documentElement;"
                + "d.setAttribute('data-theme','" + (dark ? "dark" : "light") + "');"
                + "var m=document.querySelector('meta[name=theme-color]');"
                + "if(m)m.setAttribute('content','" + (dark ? "#07070A" : "#F5F1EC") + "');"
                + "}catch(e){}})()";
        try {
            web.evaluateJavascript(js, null);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 把状态栏 / 导航栏高度写成 CSS 变量交给前端。
     * Android WebView 的 env(safe-area-inset-*) 一直是 0，所以只能自己量。
     * 单位要换成 CSS 像素（= dp），除以 density。
     */
    private void injectInsets() {
        if (web == null) return;
        try {
            float d = getResources().getDisplayMetrics().density;
            if (d <= 0) d = 1f;
            int sat = Math.round(sysBarHeight(true) / d);
            int sab = Math.round(sysBarHeight(false) / d);
            String js = "(function(){try{var r=document.documentElement.style;"
                    + "r.setProperty('--sat','" + sat + "px');"
                    + "r.setProperty('--sab','" + sab + "px');}catch(e){}})()";
            web.evaluateJavascript(js, null);
        } catch (Throwable ignored) {
        }
    }

    /** 状态栏（top=true）/ 导航栏（top=false）高度，单位 px。拿不到时退回系统资源值。 */
    private int sysBarHeight(boolean top) {
        try {
            View decor = getWindow().getDecorView();
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsets wi = decor.getRootWindowInsets();
                if (wi != null) {
                    android.graphics.Insets in = wi.getInsets(top
                            ? WindowInsets.Type.statusBars()
                            : WindowInsets.Type.navigationBars());
                    int v = top ? in.top : in.bottom;
                    if (v > 0) return v;
                }
            }
            WindowInsets wi = decor.getRootWindowInsets();
            if (wi != null) {
                int v = top ? wi.getSystemWindowInsetTop() : wi.getSystemWindowInsetBottom();
                if (v > 0) return v;
            }
        } catch (Throwable ignored) {
        }
        try {
            int id = getResources().getIdentifier(
                    top ? "status_bar_height" : "navigation_bar_height", "dimen", "android");
            if (id > 0) {
                int v = getResources().getDimensionPixelSize(id);
                if (v > 0) return v;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private void hideSplash() {
        if (splash != null && splash.getVisibility() == View.VISIBLE) {
            splash.animate().alpha(0f).setDuration(220).withEndAction(() -> {
                if (splash != null) splash.setVisibility(View.GONE);
            }).start();
        }
    }

    /** 启动时加载一次配置，把结果告诉 Web */
    private void loadConfig() {
        try {
            ApiConfig.get().loadConfig(true, new ApiConfig.LoadConfigCallback() {
                @Override
                public void retry() {
                    notifyReady(false, "配置加载失败，请重试");
                }

                @Override
                public void success() {
                    notifyReady(true, "");
                }

                @Override
                public void error(String msg) {
                    boolean needConfig = msg == null || msg.equalsIgnoreCase("-1") || msg.contains("源地址为空");
                    notifyReady(false, needConfig ? "needConfig" : msg);
                }
            }, this);
        } catch (Throwable th) {
            notifyReady(false, "needConfig");
        }
    }

    private void notifyReady(final boolean ok, final String msg) {
        runOnUiThread(() -> {
            try {
                String js = "window.__onReady && window.__onReady(" + ok + ","
                        + org.json.JSONObject.quote(msg == null ? "" : msg) + ")";
                web.evaluateJavascript(js, null);
            } catch (Throwable ignored) {
            }
            hideSplash();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从播放器返回，刷新历史 / 收藏
        if (web != null) {
            injectInsets();
            injectTheme(isNightMode());
            web.postDelayed(() -> {
                try {
                    web.evaluateJavascript("window.__onResume && window.__onResume()", null);
                } catch (Throwable ignored) {
                }
            }, 120);
        }
    }

    /**
     * 旋转 / 分屏 / 自由窗口改尺寸都会走到这里（manifest 里声明了 configChanges，不会重建）。
     * 布局换挡完全由 pad.css 的媒体查询负责，这里只做两件事：
     * 重新要一次 insets（横竖屏的安全区不一样），再通知前端钳一下滚动位置。
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (web == null) return;
        try {
            web.requestApplyInsets();
        } catch (Throwable ignored) {
        }
        injectInsets();
        // 系统切深浅色也会走到这里（manifest 里声明了 uiMode，不会重建 Activity）。
        // 只有跟随系统模式才需要跟着刷；手动选了深/浅色时系统切换与用户无关，刷了反而闪一下。
        if ("system".equals(themeMode())) {
            boolean dark = isNightMode();
            applyThemeChrome(dark);
            injectTheme(dark);
        }
        web.postDelayed(() -> {
            try {
                web.evaluateJavascript("window.__onResize && window.__onResize()", null);
            } catch (Throwable ignored) {
            }
        }, 260);
    }

    @Override
    public void onBackPressed() {
        long now = System.currentTimeMillis();
        if (now - lastBackAt < 400) return;
        lastBackAt = now;
        if (web == null) {
            super.onBackPressed();
            return;
        }
        try {
            web.evaluateJavascript(
                    "(function(){try{return window.PAGEAPI&&window.PAGEAPI.onBack?String(window.PAGEAPI.onBack()):'false'}catch(e){return 'false'}})()",
                    value -> {
                        boolean handled = value != null && value.contains("true");
                        if (!handled) finish();
                    });
        } catch (Throwable t) {
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        try {
            if (web != null) {
                web.removeJavascriptInterface("AndroidPhone");
                ViewGroup p = (ViewGroup) web.getParent();
                if (p != null) p.removeView(web);
                web.destroy();
                web = null;
            }
        } catch (Throwable ignored) {
        }
        if (bridge != null) bridge.destroy();
        if (vm != null) {
            try {
                vm.destroyExecutor();
            } catch (Throwable ignored) {
            }
        }
        super.onDestroy();
    }

    // ==================================================================
    //  崩溃兜底：不闪退，把现场画出来
    // ==================================================================

    /**
     * 启动失败时不再闪退，改为在屏幕上显示完整堆栈。
     * 用户截个图就能定位，不用连电脑抓 logcat。
     */
    private void showFatal(Throwable t) {
        String report;
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            if (t != null) t.printStackTrace(pw);
            pw.flush();
            report = "机型: " + Build.MANUFACTURER + " " + Build.MODEL
                    + "\n系统: Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                    + "\nABI : " + java.util.Arrays.toString(Build.SUPPORTED_ABIS)
                    + "\n----------------------------------------\n"
                    + sw.toString();
        } catch (Throwable oom) {
            report = String.valueOf(t);
        }

        try {
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(Color.parseColor("#07070A"));
            int pad = dp(20);
            root.setPadding(pad, dp(36), pad, pad);

            TextView title = new TextView(this);
            title.setText("启动出错了");
            title.setTextColor(Color.parseColor("#FF7A7A"));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            title.getPaint().setFakeBoldText(true);
            root.addView(title);

            TextView tip = new TextView(this);
            tip.setText("把下面这段截图发给我就能定位。\n（也可以点最下面的按钮先进入电视版首页）");
            tip.setTextColor(Color.parseColor("#B0FFFFFF"));
            tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tip.setPadding(0, dp(10), 0, dp(10));
            root.addView(tip);

            ScrollView sv = new ScrollView(this);
            sv.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            TextView body = new TextView(this);
            body.setText(report);
            body.setTextColor(Color.parseColor("#DDFFFFFF"));
            body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            body.setTypeface(android.graphics.Typeface.MONOSPACE);
            body.setTextIsSelectable(true);
            sv.addView(body);
            root.addView(sv);

            final String toCopy = report;

            Button copy = new Button(this);
            copy.setText("复制日志");
            copy.setOnClickListener(v -> copyText(toCopy));
            root.addView(copy);

            Button fallback = new Button(this);
            fallback.setText("进入电视版首页（兼容模式）");
            fallback.setOnClickListener(v -> {
                try {
                    Intent it = new Intent();
                    it.setClassName(PhoneActivity.this,
                            "com.github.tvbox.osc.ui.activity.HomeActivity");
                    startActivity(it);
                    finish();
                } catch (Throwable e) {
                    copyText(String.valueOf(e));
                }
            });
            root.addView(fallback);

            Button quit = new Button(this);
            quit.setText("退出");
            quit.setOnClickListener(v -> finish());
            root.addView(quit);

            setContentView(root);
        } catch (Throwable ignored) {
            // 连兜底页都建不出来，就交给系统
        }
    }

    /** 上一次启动崩溃了的话，把报告弹出来（读完即删，不会天天烦人） */
    private void showLastCrashIfAny() {
        final String last = CrashHandler.consumeLast();
        if (TextUtils.isEmpty(last) || web == null) return;
        web.postDelayed(() -> {
            try {
                final String body = last.length() > 6000
                        ? last.substring(0, 6000) + "\n...(已截断)"
                        : last;
                new AlertDialog.Builder(PhoneActivity.this)
                        .setTitle("上次启动崩溃了")
                        .setMessage(body)
                        .setPositiveButton("复制", (d, w) -> copyText(last))
                        .setNegativeButton("知道了", null)
                        .show();
            } catch (Throwable ignored) {
            }
        }, 900);
    }

    private void copyText(String s) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("crash", s));
            android.widget.Toast.makeText(this, "已复制", android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
