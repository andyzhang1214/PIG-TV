package com.github.tvbox.osc.bean;

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
        // classes 可能为 null（个别爬虫的 homeContent 只有 list 没有 class，
        // 或者 class 不是对象数组导到 Gson 这边是空）——以前这里直接 NPE，
        // 整个分类解析返回 null，前端显示「分类解析不了」。
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
