package com.pigtv.bean;

import com.google.gson.annotations.SerializedName;

import java.io.Serializable;
import java.util.ArrayList;

public class AbsSortJson implements Serializable {

    @SerializedName(value = "class")
    public ArrayList<AbsJsonClass> classes;

    @SerializedName(value = "list")
    public ArrayList<AbsJson.AbsJsonVod> list;

    public AbsSortXml toAbsSortXml() {
        AbsSortXml absSortXml = new AbsSortXml();
        MovieSort movieSort = new MovieSort();
        movieSort.sortList = new ArrayList<>();
        // 有的接口只给首页推荐、不给 class（classes 为 null）。
        // 原来这里直接 for-each 会抛 NPE，异常被 sortJson() 的 catch 吞掉后
        // 整个配置解析成 null —— 表现是首页一片空，连顶栏「影视」都不出现。
        if (classes != null) {
            for (AbsJsonClass cls : classes) {
                if (cls == null) continue;
                MovieSort.SortData sortData = new MovieSort.SortData();
                sortData.id = cls.type_id;
                sortData.name = cls.type_name;
                sortData.flag = cls.type_flag;
                movieSort.sortList.add(sortData);
            }
        }
        if (list != null && !list.isEmpty()) {
            Movie movie = new Movie();
            ArrayList<Movie.Video> videos = new ArrayList<>();
            for (AbsJson.AbsJsonVod vod : list) {
                videos.add(vod.toXmlVideo());
            }
            movie.videoList = videos;
            absSortXml.list = movie;
        } else {
            absSortXml.list = null;
        }
        absSortXml.classes = movieSort;
        return absSortXml;
    }

    public class AbsJsonClass implements Serializable {
        public String type_id;
        public String type_name;
        public String type_flag;
    }

}
