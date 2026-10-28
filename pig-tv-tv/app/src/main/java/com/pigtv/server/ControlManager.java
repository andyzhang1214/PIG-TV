package com.pigtv.server;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.pigtv.event.RefreshEvent;
import com.pigtv.receiver.DetailReceiver;
import com.pigtv.receiver.SearchReceiver;
import com.pigtv.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import org.greenrobot.eventbus.EventBus;

import java.io.IOException;

import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/**
 * @author pj567
 * @date :2021/1/4
 * @description:
 */
public class ControlManager {
    private static ControlManager instance;
    private RemoteServer mServer = null;
    public static Context mContext;
    /** 扫码改配置后只重启一次，避免连点两个保存把自己重启两遍 */
    private volatile boolean restarting = false;

    private ControlManager() {

    }

    public static ControlManager get() {
        if (instance == null) {
            synchronized (ControlManager.class) {
                if (instance == null) {
                    instance = new ControlManager();
                }
            }
        }
        return instance;
    }

    public static void init(Context context) {
        mContext = context;
    }

    public String getAddress(boolean local) {
        return local ? mServer.getLoadAddress() : mServer.getServerAddress();
    }

    public void startServer() {
        if (mServer != null) {
            return;
        }
        do {
            mServer = new RemoteServer(RemoteServer.serverPort, mContext);
            mServer.setDataReceiver(new DataReceiver() {
                @Override
                public void onTextReceived(String text) {
                    if (!TextUtils.isEmpty(text)) {
                        Intent intent = new Intent();
                        Bundle bundle = new Bundle();
                        bundle.putString("title", text);
                        intent.setAction(SearchReceiver.action);
                        intent.setPackage(mContext.getPackageName());
                        intent.setComponent(new ComponentName(mContext, SearchReceiver.class));
                        intent.putExtras(bundle);
                        mContext.sendBroadcast(intent);
                    }
                }

                @Override
                public void onApiReceived(String url, String name) {
                    EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_API_URL_CHANGE, url));
                    applyAndRestart(HawkConfig.API_URL, HawkConfig.API_NAME, url, name);
                }

                @Override
                public void onLiveReceived(String url, String name) {
                    EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_LIVE_URL_CHANGE, url));
                    applyAndRestart(HawkConfig.LIVE_URL, HawkConfig.LIVE_NAME, url, name);
                }

                @Override
                public void onEpgReceived(String url) {
                    EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_EPG_URL_CHANGE, url));
                }

                @Override
                public void onProxysReceived(String url) {
                    EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_PROXYS_CHANGE, url));
                }

                @Override
                public void onPushReceived(String url) {
                    EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_PUSH_URL, url));
                }

                @Override
                public void onMirrorReceived(String id, String sourceKey) {
                    if (!TextUtils.isEmpty(id) && !TextUtils.isEmpty(sourceKey)) {
                        Intent intent = new Intent();
                        Bundle bundle = new Bundle();
                        bundle.putString("id", id);
                        bundle.putString("sourceKey", sourceKey);
                        intent.setAction(DetailReceiver.action);
                        intent.setPackage(mContext.getPackageName());
                        intent.setComponent(new ComponentName(mContext, DetailReceiver.class));
                        intent.putExtras(bundle);
                        mContext.sendBroadcast(intent);
                    }
                }
            });
            try {
                mServer.start();
                IjkMediaPlayer.setDotPort(Hawk.get(HawkConfig.DOH_URL, 0) > 0, RemoteServer.serverPort);
                break;
            } catch (IOException ex) {
                RemoteServer.serverPort++;
                mServer.stop();
            }
        } while (RemoteServer.serverPort < 9999);
    }

    public void stopServer() {
        if (mServer != null && mServer.isStarting()) {
            mServer.stop();
        }
    }

    /**
     * 扫码页填的显示名。null = 调用方没带这个参数（老版本网页 / 程序内调用），保持原值；
     * 空串 = 用户主动清空，删掉名字回退成显示地址。
     */
    private static void saveName(String key, String name) {
        if (name == null) return;
        try {
            if (name.trim().isEmpty()) Hawk.delete(key);
            else Hawk.put(key, name.trim());
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /**
     * 扫码改完地址要立刻生效：先把地址和名字落盘，再重启自身让首页重新走一遍初始化。
     * 延迟 1.2 秒是为了让 HTTP 的响应先回到手机，否则页面会显示「保存失败」。
     */
    private void applyAndRestart(String urlKey, String nameKey, String url, String name) {
        try {
            if (url != null) Hawk.put(urlKey, url);
            saveName(nameKey, name);
        } catch (Throwable th) {
            th.printStackTrace();
        }
        if (restarting) return;
        restarting = true;
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent i = mContext.getPackageManager().getLaunchIntentForPackage(mContext.getPackageName());
                    if (i != null) {
                        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        mContext.startActivity(i);
                    }
                } catch (Throwable th) {
                    th.printStackTrace();
                }
                System.exit(0);
            }
        }, 1200);
    }
}