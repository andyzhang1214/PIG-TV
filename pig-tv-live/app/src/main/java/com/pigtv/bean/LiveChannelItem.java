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
}