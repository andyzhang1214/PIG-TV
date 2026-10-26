package com.github.tvbox.osc.callback;

import com.github.tvbox.osc.R;
import com.kingja.loadsir.callback.Callback;

/**
 * 直播页专用空态。
 * <p>
 * 和通用的 {@link EmptyCallback} 区别：那个只有一张图加「没找到数据」，
 * 说不清为什么没内容、也没法重试。直播源慢或者地址失效是很常见的场景，
 * 用户需要一个能直接重试、能看清原因的落点。
 */
public class LiveEmptyCallback extends Callback {
    @Override
    protected int onCreateView() {
        return R.layout.loadsir_live_empty_layout;
    }
}
