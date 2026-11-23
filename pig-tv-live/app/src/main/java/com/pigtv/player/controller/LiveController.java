package com.pigtv.player.controller;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ProgressBar;

import com.pigtv.R;

import org.jetbrains.annotations.NotNull;

/**
 * 直播控制器
 */

public class LiveController extends BaseController {
    protected ProgressBar mLoading;
    private int minFlingDistance = 100;             //最小识别距离
    private int minFlingVelocity = 10;              //最小识别速度

    public LiveController(@NotNull Context context) {
        super(context);
    }

    @Override
    protected int getLayoutId() {
        return R.layout.player_live_control_view;
    }

    @Override
    protected void initView() {
        super.initView();
        mLoading = findViewById(R.id.loading);
        // 直播不显示码率块（和播放页顶栏时钟叠在同一个角），只留转圈
        mSpeedBlockEnabled = false;
        // 控制条里有两套「台名 + 码率」：缓冲用的 top_container_hide、
        // 暂停用的 top_container。两套都会在右上角印字，一并收掉，
        // 否则顶栏在的时候就是一个数字压在时钟上。
        hideSpeedBlock("top_container_hide");
        hideSpeedBlock("top_container");
    }

    private void hideSpeedBlock(String tag) {
        View v = findViewWithTag(tag);
        if (v != null) v.setVisibility(View.GONE);
    }

    public interface LiveControlListener {
        boolean singleTap(MotionEvent e);

        void longPress();

        void playStateChanged(int playState);

        void changeSource(int direction);
    }

    private LiveController.LiveControlListener listener = null;

    public void setListener(LiveController.LiveControlListener listener) {
        this.listener = listener;
    }

    @Override
    public boolean onSingleTapConfirmed(MotionEvent e) {
        if (listener.singleTap(e))
            return true;
        return super.onSingleTapConfirmed(e);
    }

    @Override
    public void onLongPress(MotionEvent e) {
        listener.longPress();
        super.onLongPress(e);
    }

    @Override
    protected void onPlayStateChanged(int playState) {
        super.onPlayStateChanged(playState);
        // 状态一变播放器就可能把这两套「台名 + 码率」重新翻出来，跟着再压一次
        hideSpeedBlock("top_container_hide");
        hideSpeedBlock("top_container");
        listener.playStateChanged(playState);
    }

    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
        if (e1.getX() - e2.getX() > minFlingDistance && Math.abs(velocityX) > minFlingVelocity) {
            listener.changeSource(-1);          //左滑
        } else if (e2.getX() - e1.getX() > minFlingDistance && Math.abs(velocityX) > minFlingVelocity) {
            listener.changeSource(1);           //右滑
        } else if (e1.getY() - e2.getY() > minFlingDistance && Math.abs(velocityY) > minFlingVelocity) {
        } else if (e2.getY() - e1.getY() > minFlingDistance && Math.abs(velocityY) > minFlingVelocity) {
        }
        return false;
    }
}
