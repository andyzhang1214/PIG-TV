package com.pigtv.callback;

import com.pigtv.R;
import com.kingja.loadsir.callback.Callback;

/**
 * 直播页专用的空态/失败态视图。
 *
 * <p>比 {@link EmptyCallback} 多一行「按 OK 重新加载 · 按返回键退出」——
 * 遥控器上用户只可能按这两个键，得先告诉他。
 *
 * <p>文案不在这里写：LoadSir 1.3.8 的 Callback 视图是构建期一次性 inflate 的，
 * onAttach / onViewCreate 内部都没有调用点，塞静态字段刷新是刷不进去的。
 * 统一由 LivePlayActivity 在显示之后 findViewById 直接写。
 */
public class LiveEmptyCallback extends Callback {

    @Override
    protected int onCreateView() {
        return R.layout.loadsir_live_empty_layout;
    }
}
