package com.pigtv.ui.adapter;

import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.pigtv.R;
import com.pigtv.api.ApiConfig;
import com.pigtv.bean.Movie;
import com.pigtv.bean.SourceBean;
import com.pigtv.util.DefaultConfig;
import com.pigtv.util.HawkConfig;
import com.pigtv.util.ImgUtil;
import com.pigtv.util.VodText;
import com.orhanobut.hawk.Hawk;

import java.util.ArrayList;
import me.jessyan.autosize.utils.AutoSizeUtils;
public class HomeHotVodAdapter extends BaseQuickAdapter<Movie.Video, BaseViewHolder> {
    private int defaultWidth;
    private final ImgUtil.Style style;
    private String  tvYearValue;

    /**
     * style 数据结构：ratio 指定宽高比（宽 / 高），type 表示风格（例如 rect、list）
     */
    public HomeHotVodAdapter(ImgUtil.Style style,String tvYear) {
        super(R.layout.item_user_hot_vod, new ArrayList<>());
        if(style!=null){
            this.defaultWidth=ImgUtil.getStyleDefaultWidth(style);
        }
        this.style=style;
        this.tvYearValue=tvYear;
    }

    @Override
    protected void convert(BaseViewHolder helper, Movie.Video item) {

        // takagen99: Add Delete Mode
        FrameLayout tvDel = helper.getView(R.id.delFrameLayout);
        if (HawkConfig.hotVodDelete) {
            tvDel.setVisibility(View.VISIBLE);
        } else {
            tvDel.setVisibility(View.GONE);
        }

        // 角标只在"有信息量"时出现：观看历史模式显示来源站点名（每张卡不同）；
        // 站点推荐/豆瓣热播模式下每张卡都是同一句话，纯属视觉噪音，不显示
        TextView tvYear = helper.getView(R.id.tvYear);
        if (Hawk.get(HawkConfig.HOME_REC, 0) == 2) {
            SourceBean source = ApiConfig.get().getSource(item.sourceKey);
            tvYear.setText(source != null ? source.getName() : "搜");
            tvYear.setVisibility(View.VISIBLE);
        } else {
            tvYear.setVisibility(View.GONE);
        }
        TextView tvRate = helper.getView(R.id.tvNote);
        String note = VodText.showableNote(item.note);
        if (note == null) {
            tvRate.setVisibility(View.GONE);
        } else {
            tvRate.setText(note);
            tvRate.setVisibility(View.VISIBLE);
        }
        helper.setText(R.id.tvName, item.name);

        ImageView ivThumb = helper.getView(R.id.ivThumb);

        int newWidth = ImgUtil.defaultWidth;
        int newHeight = ImgUtil.defaultHeight;
        if(style!=null){
            newWidth = defaultWidth;
            newHeight = (int)(newWidth / style.ratio);
        }
        //由于部分电视机使用glide报错
        if (!TextUtils.isEmpty(item.pic)) {
            // takagen99 : Use Glide instead
            ImgUtil.load(item.pic, ivThumb, 14);
        } else {
            ivThumb.setImageResource(R.drawable.img_loading_placeholder);
        }
        applyStyleToImage(ivThumb);//动态设置宽高   
    }
    /**
     * 根据传入的 style 动态设置 ImageView 的高度：高度 = 宽度 / ratio
     */
    private void applyStyleToImage(final ImageView ivThumb) {
        if(style!=null){
            ViewGroup container = (ViewGroup) ivThumb.getParent();
            int width = defaultWidth;
            int height = (int) (width / style.ratio);
            ViewGroup.LayoutParams containerParams = container.getLayoutParams();
            containerParams.height = AutoSizeUtils.mm2px(mContext, height); // 高度
            containerParams.width = AutoSizeUtils.mm2px(mContext, width); // 宽度
            container.setLayoutParams(containerParams);
        }
    }
}
