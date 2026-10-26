package com.pigtv.event;

import com.pigtv.bean.Movie;

/**
 * 某个分类的第一页回来了。
 *
 * 分类页的 hero 要跟着这个分类换片，但列表数据在 GridFragment 里，
 * Activity 拿不到 —— 让 Fragment 把「第一页的第一条」抛上来，谁在等谁接。
 * sortId 用来判重：用户可能已经切到别的分类了，旧分类的结果要丢掉。
 */
public class SortPageEvent {
    public final String sortId;
    public final Movie.Video first;

    public SortPageEvent(String sortId, Movie.Video first) {
        this.sortId = sortId;
        this.first = first;
    }
}
