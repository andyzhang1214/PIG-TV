package com.pigtv.ui.adapter;

import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.pigtv.R;
import com.pigtv.bean.Movie;
import com.pigtv.util.DefaultConfig;
import com.pigtv.util.ImgUtil;
import com.pigtv.util.VodText;
import java.util.ArrayList;
import me.jessyan.autosize.utils.AutoSizeUtils;
/**
 * GridAdapter 支持传入 style 来设置图片的宽高比例，
 * 如果不传 style 则保留旧的默认风格（XML 中 item_grid.xml 定义的尺寸）。
 */
public class GridAdapter extends BaseQuickAdapter<Movie.Video, BaseViewHolder> {
    private boolean mShowList ;
    private int defaultWidth;
    public ImgUtil.Style style; // 动态风格，传入时调整图片宽高比



    /**
     * 如果 style 传 null，则采用 item_grid.xml 中的默认尺寸
     */
    public GridAdapter(boolean showList, ImgUtil.Style style) {
        this(showList, style, 0);
    }

    /**
     * @param layoutRes 网格模式下的 item 布局；0 表示用默认的 item_grid。
     *                  首页 v8 的分类页要塞 6 列，用的是 item_grid_v8（小卡），
     *                  搜索/收藏/历史页仍用大卡，互不影响。
     */
    public GridAdapter(boolean showList, ImgUtil.Style style, int layoutRes) {
        super(showList ? R.layout.item_list
                : (layoutRes != 0 ? layoutRes : R.layout.item_grid), new ArrayList<>());
        this.mShowList = showList;
        if (style != null) {
            if (style.type.equals("list")) this.mShowList = true;
            this.defaultWidth = ImgUtil.getStyleDefaultWidth(style);
        }
        this.style = style;
    }

    @Override
    protected void convert(BaseViewHolder helper, Movie.Video item) {
        // 焦点动效：与首页入口卡同一套规范（1.06 / 180ms），全站手感统一。
        // 复用可能残留上一格的放大态，先归位。
        final View root = helper.itemView;
        root.setScaleX(1f);
        root.setScaleY(1f);
        root.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                v.animate()
                        .scaleX(hasFocus ? 1.06f : 1f)
                        .scaleY(hasFocus ? 1.06f : 1f)
                        .setDuration(hasFocus ? 180 : 120)
                        .start();
            }
        });

        if (this.mShowList) {
            helper.setText(R.id.tvNote, item.note);
            helper.setText(R.id.tvName, item.name);
            ImageView ivThumb = helper.getView(R.id.ivThumb);
            //由于部分电视机使用glide报错
            if (!TextUtils.isEmpty(item.pic)) {
            	item.pic=item.pic.trim();
                ImgUtil.load(item.pic, ivThumb, 14);
            } else {
                ivThumb.setImageResource(R.drawable.img_loading_placeholder);
            }
            return;
        }

        TextView tvYear = helper.getView(R.id.tvYear);
        if (item.year <= 0) {
            tvYear.setVisibility(View.GONE);
        } else {
            tvYear.setText(String.valueOf(item.year));
            tvYear.setVisibility(View.VISIBLE);
        }
        TextView tvLang = helper.getView(R.id.tvLang);
        tvLang.setVisibility(View.GONE);
        /*if (TextUtils.isEmpty(item.lang)) {
            tvLang.setVisibility(View.GONE);
        } else {
            tvLang.setText(item.lang);
            tvLang.setVisibility(View.VISIBLE);
        }*/
        TextView tvArea = helper.getView(R.id.tvArea);
        tvArea.setVisibility(View.GONE);
        /*if (TextUtils.isEmpty(item.area)) {
            tvArea.setVisibility(View.GONE);
        } else {
            tvArea.setText(item.area);
            tvArea.setVisibility(View.VISIBLE);
        }*/
        String note = VodText.showableNote(item.note);
        if (note == null) {
            helper.setVisible(R.id.tvNote, false);
        } else {
            helper.setVisible(R.id.tvNote, true);
            helper.setText(R.id.tvNote, note);
        }
        helper.setText(R.id.tvName, item.name);
        helper.setText(R.id.tvActor, item.actor);
        ImageView ivThumb = helper.getView(R.id.ivThumb);
        int newWidth = ImgUtil.defaultWidth;
        int newHeight = ImgUtil.defaultHeight;
        if(style!=null){
            newWidth = defaultWidth;
            newHeight = (int)(newWidth / style.ratio);
        }
        //由于部分电视机使用glide报错
        if (!TextUtils.isEmpty(item.pic)) {
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
