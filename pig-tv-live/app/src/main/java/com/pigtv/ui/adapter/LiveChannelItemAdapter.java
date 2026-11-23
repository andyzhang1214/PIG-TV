package com.pigtv.ui.adapter;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.pigtv.R;
import com.pigtv.bean.LiveChannelItem;
import com.pigtv.util.ChannelLogo;
import com.pigtv.util.FavoriteStore;
import com.pigtv.util.LogoStore;

import java.io.File;
import java.util.ArrayList;

/**
 * 播放页切换面板里的频道列表。
 *
 * <p>和频道墙（{@link LiveChannelCardAdapter}）是两种读法：墙是「扫」，一屏几十格、
 * 靠台标图认台；播放页这个列表是「读」，一行一个台，台名必须完整写出来 ——
 * 用户此刻正在看画面，抬头只是想换一个，不是在逛。
 *
 * <p>一行显示：台标白牌 + 台名 + 「N 条线路」+ 收藏星 + 正在播的红「直播」徽标。
 * 线路数只在 2 条以上才显示，1 条的时候写「1 条线路」是纯噪音。
 *
 * <p>台标仍然走 {@link LogoStore}（先上台名、图到手再换），并且固定按「浅色」处理：
 * 台标挂在白色牌子上，白字台标（CCTV 系）不反相就看不见。
 */
public class LiveChannelItemAdapter extends BaseQuickAdapter<LiveChannelItem, BaseViewHolder> {

    /** 当前正在播的台号（channelIndex，不是列表下标）—— 徽标挂在这一行上 */
    private int selectedChannelIndex = -1;
    /** 焦点所在的列表下标；保留是因为外部（顶部栏焦点桥、滚动）还在用它做判断 */
    private int focusedChannelIndex = -1;

    public LiveChannelItemAdapter() {
        super(R.layout.item_live_channel_row, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder holder, LiveChannelItem item) {
        final String rawName = item.getChannelName() == null ? "" : item.getChannelName();
        final String name = LiveChannelCardAdapter.cleanName(rawName);
        holder.setText(R.id.tvRowName, name);
        holder.itemView.setContentDescription(name);

        // 线路数：多线路是「这个台不容易挂」的唯一直观信号，值得占一行副标题
        TextView sub = holder.getView(R.id.tvRowSub);
        int lines = item.getChannelUrls() == null ? 0 : item.getChannelUrls().size();
        if (lines > 1) {
            sub.setText(mContext.getString(R.string.v8_live_row_lines, lines));
            sub.setVisibility(View.VISIBLE);
        } else {
            sub.setVisibility(View.GONE);
        }

        holder.getView(R.id.tvRowFav).setVisibility(
                FavoriteStore.isFav(item) ? View.VISIBLE : View.GONE);
        // 「直播」徽标 = 正在播的这个台。注意这个库的 setGone(id, true) 语义是反的，一律直接 setVisibility
        holder.getView(R.id.tvRowLive).setVisibility(
                item.getChannelIndex() == selectedChannelIndex ? View.VISIBLE : View.GONE);

        bindLogo(holder.getView(R.id.tvRowLogo), holder.getView(R.id.tvRowLogoName), name, rawName);
    }

    /**
     * 台标白牌里的兜底文字。
     *
     * 白牌只有 28mm 见方，扣掉内边距只剩 22mm（1080p 下约 44px），
     * 写全名会折成「CCT / V-4K」这种被切碎的两行（实测很难看，而且和右边的台名重复）。
     * 这里只取最有辨识度的一小段：
     *   1. 去掉所有台名都一样的 CCTV 前缀；
     *   2. 取到第一个空格或汉字为止 —— 「CCTV-4 中文国际（欧）」→「4」，能一眼分清代号；
     *   3. 整段都是汉字（「东方卫视」）时退化成前两个字 ——「东方」。
     * 单行显示，不再折行。
     */
    private static String tileLabel(String name) {
        String s = name.replaceFirst("(?i)^cctv[-\\s]*", "");
        if (TextUtils.isEmpty(s)) s = name;
        int n = 0;
        while (n < s.length() && n < 3) {
            char c = s.charAt(n);
            if (c == ' ' || Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) break;
            n++;
        }
        if (n == 0) n = Math.min(2, s.length());
        return s.substring(0, n);
    }

    // =====================================================================
    //  台标
    // =====================================================================

    /**
     * 给这一行加载台标。先写台名、图到手再换（播放页每次弹出都要重新绑定，
     * 等图的那几百毫秒里不能是一排空白牌）。
     */
    private void bindLogo(final ImageView iv, final TextView fallback,
                          final String name, final String rawName) {
        final Context ctx = iv.getContext();
        final String[] urls = ChannelLogo.candidates(ctx, rawName);
        // 异步回来时这一行可能已经复用给别的台了，用它判断「这张图还是不是这一行的」
        iv.setTag(rawName);
        Glide.with(iv).clear(iv);
        iv.setImageDrawable(null);
        if (TextUtils.isEmpty(name)) {
            fallback.setText("");
            fallback.setVisibility(View.GONE);
        } else {
            fallback.setText(tileLabel(name));
            fallback.setVisibility(View.VISIBLE);
        }
        if (urls.length == 0) return;
        LogoStore.load(ctx, urls, true, new LogoStore.Callback() {
            @Override
            public void onResult(File file) {
                if (file == null || !rawName.equals(iv.getTag())) return;
                Glide.with(iv)
                        .load(file)
                        .dontAnimate()
                        .listener(new RequestListener<Drawable>() {
                            @Override
                            public boolean onLoadFailed(@Nullable GlideException e, Object model,
                                                        Target<Drawable> target, boolean isFirstResource) {
                                // 解不出来就留着台名，总比一块空白牌强
                                return false;
                            }

                            @Override
                            public boolean onResourceReady(Drawable resource, Object model,
                                                           Target<Drawable> target,
                                                           DataSource source, boolean isFirstResource) {
                                fallback.setVisibility(View.GONE);
                                return false;
                            }
                        })
                        .into(iv);
            }
        });
    }

    // =====================================================================
    //  状态
    // =====================================================================

    public void setSelectedChannelIndex(int selectedChannelIndex) {
        if (selectedChannelIndex == this.selectedChannelIndex) return;
        int preSelectedChannelIndex = this.selectedChannelIndex;
        this.selectedChannelIndex = selectedChannelIndex;
        if (preSelectedChannelIndex != -1)
            notifyItemChanged(preSelectedChannelIndex);
        if (this.selectedChannelIndex != -1)
            notifyItemChanged(this.selectedChannelIndex);
    }

    public void setFocusedChannelIndex(int focusedChannelIndex) {
        int preFocusedChannelIndex = this.focusedChannelIndex;
        this.focusedChannelIndex = focusedChannelIndex;
        if (preFocusedChannelIndex != -1)
            notifyItemChanged(preFocusedChannelIndex);
        if (this.focusedChannelIndex != -1)
            notifyItemChanged(this.focusedChannelIndex);
        else if (this.selectedChannelIndex != -1)
            notifyItemChanged(this.selectedChannelIndex);
    }
}
