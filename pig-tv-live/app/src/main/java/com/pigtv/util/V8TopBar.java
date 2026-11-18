package com.pigtv.util;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import com.pigtv.R;

import java.util.Calendar;

/**
 * 独立直播 app 的顶栏 —— 只有右侧的时钟和日期，没有任何导航。
 *
 * <p>原版是 FongMi 那套「首页 / 分类 / 直播 / 收藏 / 历史 / 搜索」六格导航。
 * 这个 app 只有直播一件事，那排每一项点下去都跳不到任何地方（对应页面已经随
 * 点播层一起删了），留着只会让用户以为还有别的功能，所以整排拆掉，
 * 只保留时钟刷新这一件事。
 *
 * <p>两个入口保留不同签名，是因为调用点本来就用两套控件 id：
 * 频道表用 navBox/tvTime/tvDate，播放页要避开播放器自己的 tvTime（码流时间），
 * 用 liveNavBox/liveTopTime/liveTopDate。
 */
public class V8TopBar {

    /** 保留常量：调用点还会传它，虽然现在没有导航项可高亮 */
    public static final int NAV_LIVE = 2;

    /** 直播页的 extra：进来后把频道列表连同顶栏一起亮出来 */
    public static final String EXTRA_OPEN_CHANNEL_LIST = "v8_open_channel_list";

    private static final Handler CLOCK = new Handler(Looper.getMainLooper());

    /** 频道表首页：导航栏藏掉，只刷时钟 */
    public static void attachClockOnly(final Activity act) {
        if (act == null) return;
        View navBox = act.findViewById(R.id.navBox);
        if (navBox != null) navBox.setVisibility(View.GONE);
        refreshClock(act, R.id.tvTime, R.id.tvDate);
    }

    /**
     * 播放页用：同样只刷时钟，导航容器直接 GONE。
     *
     * @return 恒为 null —— 已经没有可聚焦的导航项了，调用点都做了 null 检查
     *         （按 UP 时回不到导航格，就停在顶栏容器上）。
     */
    public static View attach(final Activity act, int current,
                              int navBoxId, int timeId, int dateId, boolean focusSwitch) {
        if (act == null) return null;
        View navBox = act.findViewById(navBoxId);
        if (navBox != null) navBox.setVisibility(View.GONE);
        refreshClock(act, timeId, dateId);
        return null;
    }

    /** 右上角时钟，20 秒刷一次就够了 */
    private static void refreshClock(final Activity act, int timeId, int dateId) {
        final TextView tvTime = act.findViewById(timeId);
        final TextView tvDate = act.findViewById(dateId);
        if (tvTime == null) return;

        Runnable tick = new Runnable() {
            @Override
            public void run() {
                // 页面已经关掉就别再循环了（不能用 getWindowToken，init 阶段还没 attach 会一直是 null）
                if (!tvTime.isAttachedToWindow()) return;
                Calendar c = Calendar.getInstance();
                int hour = c.get(Calendar.HOUR_OF_DAY);
                int hour12 = hour % 12;
                if (hour12 == 0) hour12 = 12;
                int minute = c.get(Calendar.MINUTE);
                tvTime.setText((hour12 < 10 ? " " : "") + hour12 + ":"
                        + (minute < 10 ? "0" + minute : String.valueOf(minute)));
                if (tvDate != null) {
                    String[] weeks = {"日", "一", "二", "三", "四", "五", "六"};
                    tvDate.setText((c.get(Calendar.MONTH) + 1) + "月" + c.get(Calendar.DAY_OF_MONTH) + "日 周"
                            + weeks[c.get(Calendar.DAY_OF_WEEK) - 1]);
                }
                CLOCK.postDelayed(this, 20000);
            }
        };
        CLOCK.removeCallbacksAndMessages(null);
        // 稍等一下再画，init 阶段 view 还没挂到窗口上
        CLOCK.postDelayed(tick, 120);
    }
}
