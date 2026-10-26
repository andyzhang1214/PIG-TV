package com.pigtv.util;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.pigtv.R;
import com.pigtv.ui.activity.CollectActivity;
import com.pigtv.ui.activity.HistoryActivity;
import com.pigtv.ui.activity.HomeActivity;
import com.pigtv.ui.activity.LivePlayActivity;
import com.pigtv.ui.activity.SearchActivity;

import java.util.Calendar;

import me.jessyan.autosize.utils.AutoSizeUtils;

/**
 * 二级页面（收藏 / 观看历史 / 搜索 / 直播）的 v8 顶栏。
 *
 * 这几个页面原来是 FongMi 的老 UI：左上角一根彩条 + 超大标题，
 * 从首页跳过来时顶上那排标签整个变了样，看着像另一个 App。
 * 这里统一按首页的规格铺一遍：品牌（已隐藏）+ 导航药丸 + 右侧时钟。
 *
 * 导航项是可以点的 —— 点哪个就去哪个页面，当前页高亮但不响应点击。
 * 直播页另外开了 focusSwitch：焦点落到哪一格就直接切过去，
 * 跟首页「焦点即切换」的手感对齐，不用先按 OK 确认。
 */
public class V8TopBar {

    public static final int NAV_HOME = 0;
    public static final int NAV_CAT = 1;
    public static final int NAV_LIVE = 2;
    public static final int NAV_FAV = 3;
    public static final int NAV_HIST = 4;
    public static final int NAV_SEARCH = 5;

    /** 首页的 extra：进来后直接落到「分类」页 */
    public static final String EXTRA_OPEN_CAT = "v8_open_cat";

    /** 直播页的 extra：进来后把频道列表连同顶栏一起亮出来 */
    public static final String EXTRA_OPEN_CHANNEL_LIST = "v8_open_channel_list";

    private static final Handler CLOCK = new Handler(Looper.getMainLooper());

    /** 焦点切换的防抖通道，和 CLOCK 分开 —— CLOCK 每 20 秒会清一次自己的队列 */
    private static final Handler NAV = new Handler(Looper.getMainLooper());
    private static final long NAV_SWITCH_DELAY = 110L;

    /**
     * 给当前页铺顶栏（用标准的 navBox / tvTime / tvDate）。
     *
     * @param act     宿主 Activity（布局里要有 navBox / tvTime / tvDate）
     * @param current 当前页对应的 NAV_xxx，用来高亮
     * @return 当前页对应的那个导航项（从列表按 ↑ 时把焦点还给它），失败返回 null
     */
    public static View attach(final Activity act, int current) {
        return attach(act, current, R.id.navBox, R.id.tvTime, R.id.tvDate, false);
    }

    /**
     * 同上，但可以指定视图 id —— 直播页有播放器自己的 tvTime（码流时间），
     * 跟顶栏的 tvTime 撞 id，只能用另一组。
     *
     * @param focusSwitch true = 焦点落到导航项上就直接切页（首页手感）
     */
    public static View attach(final Activity act, final int current,
                              int navBoxId, int timeId, int dateId, final boolean focusSwitch) {
        if (act == null) return null;

        View currentItem = null;
        final LinearLayout navBox = act.findViewById(navBoxId);
        if (navBox != null && navBox.getChildCount() == 0) {
            int[] labels = {
                    R.string.home_app_home,
                    R.string.v8_nav_cat,
                    R.string.home_app_live,
                    R.string.home_app_fav,
                    R.string.home_app_hist,
                    R.string.home_app_search
            };
            LayoutInflater inflater = LayoutInflater.from(act);
            for (int i = 0; i < labels.length; i++) {
                final int index = i;
                View item = inflater.inflate(R.layout.item_nav, navBox, false);
                ((TextView) item.findViewById(R.id.tvNav)).setText(labels[i]);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
                if (i > 0) lp.setMarginStart(AutoSizeUtils.mm2px(act, 1.6f));
                item.setLayoutParams(lp);
                item.setSelected(index == current);
                item.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (index == current) return;
                        go(act, index);
                    }
                });
                if (focusSwitch) {
                    item.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                        @Override
                        public void onFocusChange(View v, boolean hasFocus) {
                            if (hasFocus) {
                                scheduleSwitch(act, index, current);
                            } else {
                                NAV.removeCallbacksAndMessages(null);
                            }
                        }
                    });
                }
                navBox.addView(item);
                if (index == current) currentItem = item;
            }
        }

        refreshClock(act, timeId, dateId);
        return currentItem;
    }

    /** 焦点停在某一格上 110ms 就真的切过去；只是路过（继续左右按）会被下一次取消掉 */
    private static void scheduleSwitch(final Activity act, final int index, final int current) {
        NAV.removeCallbacksAndMessages(null);
        if (index == current) return;
        NAV.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (act.isFinishing() || act.isDestroyed()) return;
                go(act, index);
            }
        }, NAV_SWITCH_DELAY);
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

    /** 跳到另一个页面并把自己关掉，免得栈越来越深 */
    private static void go(Activity act, int nav) {
        Intent intent;
        switch (nav) {
            case NAV_HOME:
                intent = new Intent(act, HomeActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                break;
            case NAV_CAT:
                intent = new Intent(act, HomeActivity.class);
                intent.putExtra(EXTRA_OPEN_CAT, true);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                break;
            case NAV_LIVE:
                intent = new Intent(act, LivePlayActivity.class);
                break;
            case NAV_FAV:
                intent = new Intent(act, CollectActivity.class);
                break;
            case NAV_HIST:
                intent = new Intent(act, HistoryActivity.class);
                break;
            case NAV_SEARCH:
                intent = new Intent(act, SearchActivity.class);
                break;
            default:
                return;
        }
        act.startActivity(intent);
        // 标签之间是「就地切换」，不要 Activity 的滑入淡出 —— 有转场就像跳页了
        act.overridePendingTransition(0, 0);
        act.finish();
    }
}
