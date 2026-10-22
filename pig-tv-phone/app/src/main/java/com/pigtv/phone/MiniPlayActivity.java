package com.pigtv.phone;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.pigtv.R;
import com.pigtv.bean.VodInfo;
import com.pigtv.cache.CacheManager;
import com.pigtv.player.MyVideoView;
import com.pigtv.player.controller.VodController;
import com.pigtv.ui.activity.PlayActivity;
import com.pigtv.util.MD5;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * 手机版「内联播放」：详情页点「立即播放」不再直接横屏全屏，
 * 而是在屏幕上方弹一个 16:9 的小窗播放器，下面的详情页照常可以滑动/选集。
 *
 * 复用了 PlayActivity 的全套解析 / 嗅探 / 多内核能力 —— 自己不碰播放链路，
 * 只做两件事：
 *   1. 把窗口缩成一个漂浮在顶部的小窗（主题 MiniPlayTheme 负责透明+floating）；
 *   2. 盖一层 dp 尺寸的极简控制条（原 VodController 是电视 mm 尺寸，塞不进小窗）。
 *
 * 全屏按钮走的还是原版 PlayActivity：先把进度写进缓存，再起一个正常的
 * 全屏播放页，进度能接上。这样"全屏"这条链路和以前完全一致，风险最低。
 */
public class MiniPlayActivity extends PlayActivity {

    private static WeakReference<MiniPlayActivity> sRef;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private MyVideoView video;
    private View bar;
    private ImageView btnPlay;
    private SeekBar seek;
    private TextView tvTime;
    private boolean dragging;
    private boolean barShown = true;

    /** 关掉可能还开着的小窗（详情页重新点播放 / 选集时调用） */
    public static void dismissIfAny() {
        try {
            MiniPlayActivity a = sRef == null ? null : sRef.get();
            if (a != null && !a.isFinishing()) a.finish();
        } catch (Throwable ignored) {
        }
        sRef = null;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sRef = new WeakReference<>(this);
        try {
            applyMiniWindow();
            hideOldController();
            buildMiniBar();
            startTick();
        } catch (Throwable ignored) {
            // 小窗搭不起来就退化成原来的全屏，别让用户点不动
        }
    }

    /** 小窗不能隐藏系统栏，否则播放器会盖到状态栏上 */
    @Override
    public void hideSystemUI(boolean shownavbar) {
        try {
            showSystemUI();
        } catch (Throwable ignored) {
        }
    }

    // ==================================================================
    //  窗口
    // ==================================================================

    private void applyMiniWindow() {
        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenW = Math.min(dm.widthPixels, dm.heightPixels);
        int width = screenW;
        int height = Math.round(width * 9f / 16f);

        WindowManager.LayoutParams lp = w.getAttributes();
        lp.width = width;
        lp.height = height;
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = 0;
        lp.dimAmount = 0f;
        w.setAttributes(lp);
    }

    /** 原版 VodController 是按电视毫米排版的，小窗里必须藏掉 */
    private void hideOldController() {
        try {
            View vv = findViewById(R.id.mVideoView);
            if (!(vv instanceof ViewGroup)) return;
            ViewGroup g = (ViewGroup) vv;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c instanceof VodController) c.setVisibility(View.GONE);
            }
        } catch (Throwable ignored) {
        }
    }

    // ==================================================================
    //  极简控制条（全部用 dp，跟随小窗尺寸）
    // ==================================================================

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildMiniBar() {
        video = findViewById(R.id.mVideoView);

        FrameLayout root = new FrameLayout(this);
        // 点空白处显示/隐藏控制条
        root.setOnClickListener(v -> toggleBar());

        // ---- 底部控制条 ----
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(dp(10), 0, dp(8), 0);
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xCC000000, 0x00000000});
        bottom.setBackground(bg);

        btnPlay = new ImageView(this);
        btnPlay.setImageResource(android.R.drawable.ic_media_pause);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(30), dp(30));
        btnPlay.setLayoutParams(plp);
        btnPlay.setPadding(dp(5), dp(5), dp(5), dp(5));
        btnPlay.setOnClickListener(v -> togglePlay());
        bottom.addView(btnPlay);

        tvTime = new TextView(this);
        tvTime.setTextColor(0xE6FFFFFF);
        tvTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tvTime.setText("00:00");
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(6);
        tlp.rightMargin = dp(6);
        tvTime.setLayoutParams(tlp);
        bottom.addView(tvTime);

        seek = new SeekBar(this);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, dp(32), 1f);
        seek.setLayoutParams(slp);
        seek.setPadding(dp(8), 0, dp(8), 0);
        seek.setMax(1000);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                dragging = true;
                showBar();
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                dragging = false;
                try {
                    if (video != null) {
                        long dur = video.getDuration();
                        if (dur > 0) video.seekTo(dur * sb.getProgress() / 1000);
                    }
                } catch (Throwable ignored) {
                }
            }
        });
        bottom.addView(seek);

        // 全屏按钮用文字，比系统菜单图标一眼就能看懂
        TextView btnFull = new TextView(this);
        btnFull.setText("全屏");
        btnFull.setTextColor(Color.WHITE);
        btnFull.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        btnFull.setGravity(Gravity.CENTER);
        GradientDrawable fbg = new GradientDrawable();
        fbg.setColor(0x33FFFFFF);
        fbg.setCornerRadius(dp(6));
        btnFull.setBackground(fbg);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(24));
        flp.leftMargin = dp(8);
        btnFull.setLayoutParams(flp);
        btnFull.setPadding(dp(9), 0, dp(9), 0);
        btnFull.setOnClickListener(v -> goFullscreen());
        bottom.addView(btnFull);

        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44), Gravity.BOTTOM);
        root.addView(bottom, blp);
        bar = bottom;

        // ---- 右上角关闭 ----
        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(Color.WHITE);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        close.setGravity(Gravity.CENTER);
        GradientDrawable cbg = new GradientDrawable();
        cbg.setColor(0x99000000);
        cbg.setCornerRadius(dp(14));
        close.setBackground(cbg);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                dp(28), dp(28), Gravity.TOP | Gravity.END);
        clp.topMargin = dp(6);
        clp.rightMargin = dp(6);
        close.setLayoutParams(clp);
        close.setOnClickListener(v -> closeMini());
        root.addView(close);

        addContentView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        startAutoHide();
    }

    private void togglePlay() {
        try {
            if (video == null) return;
            if (video.isPlaying()) {
                video.pause();
                btnPlay.setImageResource(android.R.drawable.ic_media_play);
                showBar();
            } else {
                video.start();
                btnPlay.setImageResource(android.R.drawable.ic_media_pause);
                startAutoHide();
            }
        } catch (Throwable ignored) {
        }
    }

    private void toggleBar() {
        if (barShown) hideBar();
        else showBar();
    }

    private void showBar() {
        barShown = true;
        if (bar != null) {
            bar.setVisibility(View.VISIBLE);
            bar.animate().alpha(1f).setDuration(140).start();
        }
    }

    private void hideBar() {
        barShown = false;
        if (bar != null) {
            bar.animate().alpha(0f).setDuration(180).withEndAction(() -> {
                if (bar != null && !barShown) bar.setVisibility(View.INVISIBLE);
            }).start();
        }
    }

    private void startAutoHide() {
        ui.removeCallbacks(autoHide);
        ui.postDelayed(autoHide, 3200);
    }

    private final Runnable autoHide = () -> {
        try {
            if (video != null && video.isPlaying() && !dragging) hideBar();
        } catch (Throwable ignored) {
        }
    };

    private void startTick() {
        ui.post(new Runnable() {
            @Override
            public void run() {
                syncProgress();
                ui.postDelayed(this, 500);
            }
        });
    }

    private void syncProgress() {
        try {
            if (video == null || seek == null) return;
            long dur = video.getDuration();
            long pos = video.getCurrentPosition();
            if (btnPlay != null) {
                btnPlay.setImageResource(video.isPlaying()
                        ? android.R.drawable.ic_media_pause
                        : android.R.drawable.ic_media_play);
            }
            if (!dragging) {
                if (dur > 0) {
                    seek.setProgress((int) (pos * 1000 / dur));
                    seek.setEnabled(true);
                } else {
                    seek.setProgress(0);
                    seek.setEnabled(false);
                }
            }
            if (tvTime != null) {
                tvTime.setText(fmt(pos) + " / " + (dur > 0 ? fmt(dur) : "--:--"));
            }
        } catch (Throwable ignored) {
        }
    }

    private String fmt(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long ss = s % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, ss);
        return String.format("%02d:%02d", m, ss);
    }

    // ==================================================================
    //  全屏 / 关闭
    // ==================================================================

    /** 存一下进度再交给全屏页，续播就不用从头找起 */
    private void saveProgress() {
        try {
            if (video == null) return;
            long pos = video.getCurrentPosition();
            if (pos < 3000) return;
            VodInfo vi = (VodInfo) getIntent().getSerializableExtra("VodInfo");
            if (vi == null || vi.seriesMap == null || vi.playFlag == null) return;
            List<VodInfo.VodSeries> list = vi.seriesMap.get(vi.playFlag);
            if (list == null) return;
            int idx = vi.getplayIndex();
            if (idx < 0 || idx >= list.size()) return;
            String url = list.get(idx).url;
            if (url == null || url.isEmpty()) return;
            CacheManager.save(MD5.string2MD5(url), pos);
        } catch (Throwable ignored) {
        }
    }

    private void goFullscreen() {
        try {
            saveProgress();
            Intent it = new Intent(this, PlayActivity.class);
            Bundle b = getIntent().getExtras();
            if (b != null) it.putExtras(b);
            startActivity(it);
        } catch (Throwable ignored) {
        }
        finish();
    }

    private void closeMini() {
        saveProgress();
        finish();
    }

    @Override
    public void onBackPressed() {
        closeMini();
    }

    /** 旋转 / 分屏：小窗尺寸按新屏幕宽度重算（manifest 里声明了 configChanges，不会重建） */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        try {
            applyMiniWindow();
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        try {
            if (sRef != null && sRef.get() == this) sRef = null;
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }
}
