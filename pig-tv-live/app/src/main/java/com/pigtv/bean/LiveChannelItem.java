package com.pigtv.bean;

import java.util.ArrayList;

/**
 * @author pj567
 * @date :2021/1/12
 * @description:
 */
public class LiveChannelItem {
    /**
     * channelIndex : 频道索引号
     * channelNum : 频道名称
     * channelSourceNames : 频道源名称
     * channelUrls : 频道源地址
     * sourceIndex : 频道源索引
     * sourceNum : 频道源总数
     */
    private int channelIndex;
    private int channelNum;
    private String channelName;
    private ArrayList<String> channelSourceNames;
    private ArrayList<String> channelUrls;
    public int sourceIndex = 0;
    public int sourceNum = 0;
    public boolean include_back = false;

    public void setinclude_back(boolean include_back) {
        this.include_back = include_back;
    }

    public boolean getinclude_back() {
        return include_back;
    }


    public void setChannelIndex(int channelIndex) {
        this.channelIndex = channelIndex;
    }

    public int getChannelIndex() {
        return channelIndex;
    }

    public void setChannelNum(int channelNum) {
        this.channelNum = channelNum;
    }

    public int getChannelNum() {
        return channelNum;
    }

    public void setChannelName(String channelName) {
        this.channelName = channelName;
    }

    public String getChannelName() {
        return channelName;
    }

    public ArrayList<String> getChannelUrls() {
        return channelUrls;
    }

    public void setChannelUrls(ArrayList<String> channelUrls) {
        this.channelUrls = channelUrls;
        sourceNum = channelUrls.size();
    }
    public void preSource() {
        sourceIndex--;
        if (sourceIndex < 0) sourceIndex = sourceNum - 1;
    }
    public void nextSource() {
        sourceIndex++;
        if (sourceIndex == sourceNum) sourceIndex = 0;
    }

    public void setSourceIndex(int sourceIndex) {
        this.sourceIndex = sourceIndex;
    }

    public int getSourceIndex() {
        return sourceIndex;
    }

    public String getUrl() {
        return channelUrls.get(sourceIndex);
    }

    public int getSourceNum() {
        return sourceNum;
    }

    public ArrayList<String> getChannelSourceNames() {
        return channelSourceNames;
    }

    public void setChannelSourceNames(ArrayList<String> channelSourceNames) {
        this.channelSourceNames = channelSourceNames;
    }

    public String getSourceName() {
        return channelSourceNames.get(sourceIndex);
    }

    /**
     * 按给定顺序重排线路（{@code ordered} 必须是当前这批 URL 的一个排列）。
     *
     * <p>判定「是不是同一批」用的是逐个匹配 + used 标记，不是简单下标 ——
     * 一个频道里偶尔真会有两条一模一样的地址（不同源给了同一条线），
     * 按下标搬会把台名和地址错位，用户看到「线路 2 是 CCTV1」点开却是别的台。
     *
     * <p>{@link #sourceIndex} 会跟着原来那条线一起挪，所以调完这个方法，
     * 当前正在播的还是同一条地址，只是它的序号变了。
     *
     * @return 是否真的重排了（顺序没变 / 数据对不上都返回 false）
     */
    public boolean applyOrder(java.util.List<String> ordered) {
        if (ordered == null || channelUrls == null) return false;
        if (ordered.size() != channelUrls.size()) return false;

        ArrayList<String> oldUrls = new ArrayList<>(channelUrls);
        ArrayList<String> oldNames = channelSourceNames;
        String cur = null;
        try {
            cur = getUrl();
        } catch (Throwable ignored) {
        }

        boolean[] used = new boolean[oldUrls.size()];
        ArrayList<String> newUrls = new ArrayList<>(oldUrls.size());
        ArrayList<String> newNames = oldNames != null && oldNames.size() == oldUrls.size()
                ? new ArrayList<String>(oldUrls.size()) : null;

        for (String u : ordered) {
            int idx = -1;
            for (int i = 0; i < oldUrls.size(); i++) {
                if (!used[i] && oldUrls.get(i).equals(u)) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0) return false;          // 不是同一批，一个字节都别动
            used[idx] = true;
            newUrls.add(u);
            if (newNames != null) newNames.add(oldNames.get(idx));
        }

        boolean changed = !newUrls.equals(oldUrls);
        channelUrls = newUrls;
        if (newNames != null) channelSourceNames = newNames;
        sourceNum = newUrls.size();

        int ni = 0;
        if (cur != null) {
            int idx = newUrls.indexOf(cur);
            if (idx >= 0) ni = idx;
        }
        sourceIndex = ni;
        return changed;
    }

    // =====================================================================
    //  线路健康状态（只影响面板上那一行怎么显示，不参与播放决策）
    // =====================================================================

    /**
     * 本台所有线路都试完且全挂的时刻（{@link android.os.SystemClock#elapsedRealtime()}）。
     *
     * <p>以前这种情况是静默跳到下一个台：用户按了确定、画面黑一下就换了个频道，
     * 既不知道「刚才那个台挂了」，也不知道「是我按错了还是它自己跳的」。
     * 记下这个时刻，面板上那一行就能挂一行「线路暂不可用」的副标题。
     *
     * <p>用 elapsedRealtime 而不是墙钟：机顶盒校时、跨时区、用户手动改时间都不影响它。
     * transient：这个状态跟设备此刻的网络有关，不该跟着收藏或源表被序列化出去。
     */
    private transient volatile long allSourceFailAt = 0L;

    /** 刚把本台所有线路试完，全失败 */
    public void markAllSourceFailed() {
        allSourceFailAt = android.os.SystemClock.elapsedRealtime();
    }

    /** 播通了，把失败标记清掉 */
    public void clearAllSourceFailed() {
        allSourceFailAt = 0L;
    }

    /**
     * 最近 {@code windowMs} 毫秒内是否「所有线路都失败过」。
     *
     * <p>加时间窗而不是永久置位：线路是别人家的服务器，几分钟后可能自己活了，
     * 一直挂着「不可用」会变成误导。
     */
    public boolean isAllSourceFailed(long windowMs) {
        long at = allSourceFailAt;
        if (at <= 0L) return false;
        return android.os.SystemClock.elapsedRealtime() - at < windowMs;
    }
}