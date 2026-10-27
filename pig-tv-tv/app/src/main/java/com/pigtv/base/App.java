package com.pigtv.base;

import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import androidx.core.os.HandlerCompat;
import androidx.multidex.MultiDexApplication;

import com.github.catvod.crawler.JarLoader;
import com.github.catvod.crawler.JsLoader;
import com.pigtv.R;
import com.pigtv.callback.EmptyCallback;
import com.pigtv.callback.LiveEmptyCallback;
import com.pigtv.callback.LoadingCallback;
import com.pigtv.data.AppDataManager;
import com.pigtv.server.ControlManager;
import com.pigtv.util.CrashHandler;
import com.pigtv.util.EpgUtil;
import com.pigtv.util.FileUtils;
import com.pigtv.util.HawkConfig;
import com.pigtv.util.LocaleHelper;
import com.pigtv.util.LOG;
import com.pigtv.util.OkGoHelper;
import com.pigtv.util.PlayerHelper;
import com.pigtv.util.SubtitleHelper;
import com.hjq.permissions.XXPermissions;
import com.kingja.loadsir.core.LoadSir;
import com.orhanobut.hawk.Hawk;
import com.p2p.P2PClass;
import com.whl.quickjs.android.QuickJSLoader;
import com.yanzhenjie.andserver.AndServer;
import com.yanzhenjie.andserver.Server;

import java.io.File;
import java.util.concurrent.TimeUnit;

import io.github.inflationx.calligraphy3.CalligraphyConfig;
import io.github.inflationx.calligraphy3.CalligraphyInterceptor;
import io.github.inflationx.viewpump.ViewPump;
import me.jessyan.autosize.AutoSizeConfig;
import me.jessyan.autosize.unit.Subunits;

/**
 * @author pj567
 * @date :2020/12/17
 * @description:
 */
public class App extends MultiDexApplication {
    private static App instance;
    private static P2PClass p;
    public static String burl;
    private static String dashData;
    public static ViewPump viewPump = null;
    private static Server server = null;
    private final Handler handler;

    public App() {
        instance = this;
        handler = HandlerCompat.createAsync(Looper.getMainLooper());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        SubtitleHelper.initSubtitleColor(this);
        initParams();
        // takagen99 : Initialize Locale
        initLocale();
        // OKGo
        OkGoHelper.init();
        // 闭关检查模式
        XXPermissions.setCheckMode(false);
        // Get EPG Info
        EpgUtil.init();
        // 初始化Web服务器
        ControlManager.init(this);
        //初始化数据库
        AppDataManager.init();
        LoadSir.beginBuilder()
                .addCallback(new EmptyCallback())
                .addCallback(new LoadingCallback())
                // 直播页自己的空态（能说清「为什么没有内容」+ 按 OK 重试），
                // 必须在 Builder 里登记，否则 showCallback(LiveEmptyCallback.class) 取不到实例
                .addCallback(new LiveEmptyCallback())
                .commit();
        // 崩溃日志落盘（Download/猪猪影视-崩溃日志.txt）。
        // Java 层未捕获异常才有记录；native SIGSEGV 抓不到，只能靠 logcat
        CrashHandler.install(this);
        AutoSizeConfig.getInstance().setCustomFragment(true).getUnitsManager()
                .setSupportDP(false)
                .setSupportSP(false)
                .setSupportSubunits(Subunits.MM);
        // 修复 Android 14+：冷启动时 ScreenUtils 可能获取到竖屏宽度，导致 xdpi 计算错误、UI 变小
        int screenWidth = AutoSizeConfig.getInstance().getScreenWidth();
        int screenHeight = AutoSizeConfig.getInstance().getScreenHeight();
        if (screenWidth < screenHeight) {
            AutoSizeConfig.getInstance().setScreenWidth(screenHeight);
            AutoSizeConfig.getInstance().setScreenHeight(screenWidth);
        }
        PlayerHelper.init();

        // Delete Cache
        /*File dir = getCacheDir();
        FileUtils.recursiveDelete(dir);
        dir = getExternalCacheDir();
        FileUtils.recursiveDelete(dir);*/

        FileUtils.cleanPlayerCache();

        // Add JS support
        QuickJSLoader.init();

        // add font support, my tv embed font not include emoji
        String extStorageDir = Environment.getExternalStorageDirectory().getAbsolutePath();
        File fontFile = new File(extStorageDir + "/tvbox.ttf");
        if (fontFile.exists()) {
            viewPump = ViewPump.builder()
                    .addInterceptor(new CalligraphyInterceptor(
                            new CalligraphyConfig.Builder()
                                    .setDefaultFontPath(fontFile.getAbsolutePath())
                                    .setFontAttrId(R.attr.fontPath)
                                    .build()))
                    .build();
        }
    }

    public static P2PClass getp2p() {
        try {
            if (p == null) {
                p = new P2PClass(FileUtils.getExternalCachePath());
            }
            return p;
        } catch (Exception e) {
            LOG.e(e.toString());
            return null;
        }
    }


    private void initParams() {
        // Hawk
        Hawk.init(this).build();
        Hawk.put(HawkConfig.DEBUG_OPEN, false);

        // 首页选项
        putDefault(HawkConfig.HOME_SHOW_SOURCE, true);       //数据源显示: true=开启, false=关闭
        putDefault(HawkConfig.HOME_SEARCH_POSITION, false);  //按钮位置-搜索: true=上方, false=下方
        putDefault(HawkConfig.HOME_MENU_POSITION, true);     //按钮位置-设置: true=上方, false=下方
        putDefault(HawkConfig.HOME_REC, 1);                  //推荐: 0=豆瓣热播, 1=站点推荐, 2=观看历史
        putDefault(HawkConfig.HOME_NUM, 4);                  //历史条数: 0=20条, 1=40条, 2=60条, 3=80条, 4=100条
        // 播放器选项
        putDefault(HawkConfig.SHOW_PREVIEW, true);           //窗口预览: true=开启, false=关闭
        putDefault(HawkConfig.PLAY_SCALE, 0);                //画面缩放: 0=默认, 1=16:9, 2=4:3, 3=填充, 4=原始, 5=裁剪
        putDefault(HawkConfig.BACKGROUND_PLAY_TYPE, 0);      //后台：0=关闭, 1=开启, 2=画中画
        putDefault(HawkConfig.PLAY_TYPE, 1);                 //播放器: 0=系统, 1=IJK, 2=Exo, 3=MX, 4=Reex, 5=Kodi
        putDefault(HawkConfig.IJK_CODEC, "硬解码");           //IJK解码: 软解码, 硬解码
        // 系统选项
        putDefault(HawkConfig.HOME_LOCALE, 0);               //语言: 0=中文, 1=英文
        putDefault(HawkConfig.THEME_SELECT, 0);              //主题: 0=奈飞, 1=哆啦, 2=百事, 3=鸣人, 4=小黄, 5=八神, 6=樱花
        putDefault(HawkConfig.SEARCH_VIEW, 1);               //搜索展示: 0=文字列表, 1=缩略图
        putDefault(HawkConfig.PARSE_WEBVIEW, true);          //嗅探Webview: true=系统自带, false=XWalkView
        putDefault(HawkConfig.DOH_URL, 2);                   //安全DNS: 0=关闭, 1=腾讯, 2=阿里, 3=360, 4=Google, 5=AdGuard, 6=Quad9
                                                             //默认开阿里 DoH：运营商 DNS 会劫持 qist 等配置域名（返回拦截页），
                                                             //加密 DNS 不经过运营商解析，从根上避开劫持；用户仍可在设置里改

    }

    private void initLocale() {
        if (Hawk.get(HawkConfig.HOME_LOCALE, 0) == 0) {
            LocaleHelper.setLocale(App.this, "zh");
        } else {
            LocaleHelper.setLocale(App.this, "");
        }
    }

    public static App getInstance() {
        return instance;
    }

    private void putDefault(String key, Object value) {
        if (!Hawk.contains(key)) {
            Hawk.put(key, value);
        }
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        JsLoader.destroy();
    }

    public void setDashData(String data) {
        dashData = data;
    }

    public String getDashData() {
        return dashData;
    }

    public static void startWebserver() {
        if (server != null) return;
        server = AndServer
                .webServer(instance)
                .port(12345)
                .timeout(60, TimeUnit.SECONDS)
                .listener(new Server.ServerListener() {
                    @Override
                    public void onStarted() {

                    }

                    @Override
                    public void onStopped() {

                    }

                    @Override
                    public void onException(Exception e) {

                    }
                }).build();
        server.startup();
    }

    public static void post(Runnable runnable) {
        getInstance().handler.post(runnable);
    }

    public static void post(Runnable runnable, long delayMillis) {
        getInstance().handler.removeCallbacks(runnable);
        if (delayMillis >= 0) getInstance().handler.postDelayed(runnable, delayMillis);
    }
}
