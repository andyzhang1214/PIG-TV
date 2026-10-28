package com.pigtv.ui.adapter;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.pigtv.R;
import com.pigtv.bean.Movie;
import com.pigtv.util.ImgUtil;
import com.pigtv.util.VodText;

import java.util.ArrayList;
import java.util.List;

/**
 * 首页一行内容的横向列表（v8）
 *
 * 两种卡：
 *   poster —— 竖版海报 + 卡外片名，焦点白环 + 轻微放大
 *   more   —— 行尾「查看全部」，按 OK 落到分类页的对应分类
 *
 * 只负责自己这一行；跨行跳焦点由 HomeActivity 统一接管（见其 dispatchKeyEvent），
 * 所以这里不处理上下键，避免两个地方抢同一件事。
 */
public class HomeLineAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int TYPE_POSTER = 0;
    public static final int TYPE_MORE = 1;

    public interface Listener {
        void onPosterClick(Movie.Video video, int position);

        void onPosterFocus(Movie.Video video, int position);

        void onMoreClick();
    }

    private final List<Movie.Video> items = new ArrayList<>();
    private final Listener listener;
    private boolean withMore = false;
    private boolean gridMode = false;
    /** 历史模式：卡下多一行「看到 第 12 集」，海报底部按进度拉一条 */
    private boolean historyMode = false;
    private java.util.Map<String, Float> progressMap;
    private String moreCount = "";

    public HomeLineAdapter(Listener listener) {
        this.listener = listener;
        setHasStableIds(false);
    }

    /**
     * 网格模式：卡片宽度交给 GridLayoutManager 均分（观看历史海报墙用）。
     * 横滑行不调用，保持写死的 146mm 卡宽。
     */
    public void setGridMode(boolean grid) {
        this.gridMode = grid;
    }

    public void setHistoryMode(boolean history) {
        this.historyMode = history;
    }

    /** 卡底进度：key 与 HomeActivity.videoKey() 一致；没有条目就不画进度条 */
    public void setProgressMap(java.util.Map<String, Float> map) {
        this.progressMap = map;
    }

    private Float progressOf(Movie.Video v) {
        if (progressMap == null || v == null) return null;
        String k = (v.id == null ? "" : v.id) + "@" + (v.sourceKey == null ? "" : v.sourceKey);
        return progressMap.get(k);
    }

    public void setData(List<Movie.Video> list, boolean withMore, String moreCount) {
        items.clear();
        if (list != null) items.addAll(list);
        this.withMore = withMore && !items.isEmpty();
        this.moreCount = moreCount == null ? "" : moreCount;
        notifyDataSetChanged();
    }

    public Movie.Video getVideo(int position) {
        if (position < 0 || position >= items.size()) return null;
        return items.get(position);
    }

    public int posterCount() {
        return items.size();
    }

    /** 行内有多少个可聚焦项（含「查看全部」） */
    public int cellCount() {
        return items.size() + (withMore ? 1 : 0);
    }

    @Override
    public int getItemViewType(int position) {
        return (withMore && position == items.size()) ? TYPE_MORE : TYPE_POSTER;
    }

    @Override
    public int getItemCount() {
        return cellCount();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_MORE) {
            return new MoreVH(inflater.inflate(R.layout.item_home_more, parent, false));
        }
        return new PosterVH(inflater.inflate(
                gridMode ? R.layout.item_home_wall : R.layout.item_home_poster, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof PosterVH) {
            bindPoster((PosterVH) holder, position);
        } else {
            bindMore((MoreVH) holder);
        }
    }

    private void bindPoster(final PosterVH h, final int position) {
        final Movie.Video v = items.get(position);
        h.tvName.setText(TextUtils.isEmpty(v.name) ? "" : v.name);

        // 图上左下角一行（v11）：和手机端 .pcB 同一口径 ——
        // 有「更新至 12 集 / 评分 7.5」这类备注就用它，没有就「类型 · 年份 · 地区」
        String badge = VodText.coverStat(v.note, v.type, v.year, v.area);
        if (TextUtils.isEmpty(badge)) {
            h.tvBadge.setVisibility(View.GONE);
        } else {
            h.tvBadge.setText(badge);
            h.tvBadge.setVisibility(View.VISIBLE);
        }

        // 复用时会带着上一张卡的焦点态，先归位
        h.vRing.setVisibility(View.GONE);
        h.tvName.setTextColor(0xA6FFFFFF);
        h.itemView.setScaleX(1f);
        h.itemView.setScaleY(1f);

        // 历史卡：底部进度条 + 「看到 第 12 集」
        if (h.progressBar != null) {
            Float prog = historyMode ? progressOf(v) : null;
            if (prog == null) {
                h.progressBar.setVisibility(View.GONE);
            } else {
                h.progressBar.setVisibility(View.VISIBLE);
                final View fill = h.progressFill;
                final FrameLayout track = h.progressBar;
                final float p = prog;
                // 宽度依赖轨道实际宽度，得等这一帧量完再设，直接按图片宽度算会算成 0
                track.post(new Runnable() {
                    @Override
                    public void run() {
                        ViewGroup.LayoutParams lp = fill.getLayoutParams();
                        lp.width = Math.round(track.getWidth() * p);
                        fill.setLayoutParams(lp);
                    }
                });
            }
        }
        if (h.tvSub != null) {
            String sub = "";
            // 这里要的是「看到 第12集」，只认真正的备注；
            // badge 现在可能是「动作片 · 2024」那种元信息，套进去读不通
            String played = VodText.showableNote(v.note);
            if (historyMode && !TextUtils.isEmpty(played)) {
                sub = h.itemView.getContext().getString(R.string.v8_hist_sub, played);
            }
            if (TextUtils.isEmpty(sub)) {
                h.tvSub.setVisibility(View.GONE);
            } else {
                h.tvSub.setText(sub);
                h.tvSub.setVisibility(View.VISIBLE);
            }
        }

        if (!TextUtils.isEmpty(v.pic)) {
            ImgUtil.load(v.pic.trim(), h.ivThumb, 0);
        } else {
            h.ivThumb.setImageResource(R.drawable.img_loading_placeholder);
        }

        h.itemView.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean focused) {
                h.vRing.setVisibility(focused ? View.VISIBLE : View.GONE);
                h.tvName.setTextColor(focused ? 0xFFFFFFFF : 0xA6FFFFFF);
                view.animate()
                        .scaleX(focused ? 1.07f : 1f)
                        .scaleY(focused ? 1.07f : 1f)
                        .setDuration(focused ? 180 : 140)
                        .start();
                if (focused && listener != null) listener.onPosterFocus(v, position);
            }
        });

        h.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (listener != null) listener.onPosterClick(v, position);
            }
        });
    }

    private void bindMore(MoreVH h) {
        h.tvCount.setText(moreCount);
        h.itemView.setScaleX(1f);
        h.itemView.setScaleY(1f);
        h.itemView.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean focused) {
                view.animate()
                        .scaleX(focused ? 1.07f : 1f)
                        .scaleY(focused ? 1.07f : 1f)
                        .setDuration(focused ? 180 : 140)
                        .start();
            }
        });
        h.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (listener != null) listener.onMoreClick();
            }
        });
    }

    static class PosterVH extends RecyclerView.ViewHolder {
        final ImageView ivThumb;
        final View vRing;
        final TextView tvBadge;
        final TextView tvName;
        /** 只有 item_home_wall 有这两个（历史卡）；横滑行取出来是 null，用前要判 */
        final TextView tvSub;
        final FrameLayout progressBar;
        final View progressFill;

        PosterVH(View v) {
            super(v);
            ivThumb = v.findViewById(R.id.ivThumb);
            vRing = v.findViewById(R.id.vRing);
            tvBadge = v.findViewById(R.id.tvBadge);
            tvName = v.findViewById(R.id.tvName);
            tvSub = v.findViewById(R.id.tvSub);
            progressBar = v.findViewById(R.id.progressBar);
            progressFill = v.findViewById(R.id.progressFill);
        }
    }

    static class MoreVH extends RecyclerView.ViewHolder {
        final TextView tvCount;

        MoreVH(View v) {
            super(v);
            tvCount = v.findViewById(R.id.tvCount);
        }
    }
}
