package com.pigtv.ui.dialog;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.pigtv.R;
import com.pigtv.util.ChannelLogo;
import com.pigtv.util.LogoStore;
import com.pigtv.util.UiTheme;

import java.io.File;

/**
 * 频道详情弹层：频道墙上按 MENU 时放大当前这一格。
 *
 * <p>卡面为了「一屏多放几台」把台名、分组、地址全让位了，只剩台标和两颗角标。
 * 用户想确认「这一格到底是哪个源、几路线」时没有任何入口 —— 这一层就是那个入口，
 * 顺便把收藏做成看得见的按钮，不用再记「长按 OK」这种隐藏手势。
 *
 * <p>这一层只管显示，不改收藏：收藏动作由 Activity 执行（它才持有
 * {@link com.pigtv.util.FavoriteStore} 的坐标，也知道收藏档要不要重铺），
 * 做完再回调 {@link #setFav(boolean)} 把按钮上的星和文案换过来。
 * 把它做进弹层会变成两处各存一份状态，收藏档里删掉一格之后弹层还亮着金星。
 */
public class LiveChannelDetailDialog extends BaseDialog {

    /** 按钮动作都交回 Activity；弹层自己不决定收藏成不成功 */
    public interface OnAction {
        /** 按了「收藏本台 / 取消收藏」 */
        void onFavToggle();

        /** 按了「立即播放」 */
        void onPlay();
    }

    private final ImageView ivLogo;
    private final TextView tvLogoName;
    private final TextView tvName;
    private final TextView tvTag;
    private final TextView tvGroup;
    private final TextView tvAddr;
    private final TextView btnFav;

    /** 台标加载要用的原始名（带画质标记，查台标库用），和卡面一致 */
    private final String rawName;
    /** 源自带的台标（m3u 的 tvg-logo），没有就是空串 —— 和卡面走同一条优先级 */
    private final String srcLogo;

    private OnAction action;
    /** 关闭后不再接收台标回调；不能用 isShowing() 代替（构造期同步回调尚未 show） */
    private boolean disposed = false;

    /**
     * @param rawName 带画质标记的原台名（查台标用）
     * @param name    卡面显示的台名（已去标记）
     * @param tag     画质角标文案，可空
     * @param is4k    4K 台：角标走金色
     * @param group   分组名，可空
     * @param url     首路地址，可空
     * @param fav     当前是否已收藏
     * @param logoUrl 源自带台标（tvg-logo），可空
     */
    public LiveChannelDetailDialog(Context ctx, String rawName, String name, String tag,
                                   boolean is4k, String group, String url, boolean fav,
                                   String logoUrl) {
        super(ctx);
        setContentView(R.layout.dialog_live_channel_detail);
        this.rawName = rawName == null ? "" : rawName;
        this.srcLogo = logoUrl == null ? "" : logoUrl;

        ivLogo = findViewById(R.id.ivLogo);
        tvLogoName = findViewById(R.id.tvLogoName);
        tvName = findViewById(R.id.tvName);
        tvTag = findViewById(R.id.tvTag);
        tvGroup = findViewById(R.id.tvGroup);
        tvAddr = findViewById(R.id.tvAddr);
        btnFav = findViewById(R.id.btnFav);

        tvName.setText(TextUtils.isEmpty(name) ? rawName : name);
        tvName.setVisibility(TextUtils.isEmpty(name) && TextUtils.isEmpty(rawName)
                ? View.GONE : View.VISIBLE);

        if (TextUtils.isEmpty(tag)) {
            tvTag.setVisibility(View.GONE);
        } else {
            tvTag.setText(tag);
            tvTag.setTextColor(ContextCompat.getColorStateList(ctx,
                    is4k ? R.color.v8_live_tag_gold : R.color.v8_live_tag_tx));
            tvTag.setVisibility(View.VISIBLE);
        }

        tvGroup.setText(TextUtils.isEmpty(group) ? ""
                : ctx.getString(R.string.v8_live_detail_group, group));
        tvGroup.setVisibility(TextUtils.isEmpty(group) ? View.GONE : View.VISIBLE);

        // 地址对用户唯一的价值是「这一路不通时知道它指向哪」，超长中间省略就够
        tvAddr.setText(TextUtils.isEmpty(url) ? "" : url);
        tvAddr.setVisibility(TextUtils.isEmpty(url) ? View.GONE : View.VISIBLE);

        setFav(fav);
        bindLogo(name);

        TextView btnPlay = findViewById(R.id.btnPlay);
        TextView btnClose = findViewById(R.id.btnClose);
        btnFav.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (action != null) action.onFavToggle();
            }
        });
        btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
                if (action != null) action.onPlay();
            }
        });
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });

        // 关闭走 BACK 时也要先关这一层（Dialog 自己会处理），焦点回 Activity 由它自己兜
        setCanceledOnTouchOutside(true);

        // 初始焦点给收藏按钮：按 MENU 进来的动机十有八九是「收了这个台」。
        // 必须重试：弹层窗口刚 show 出来时这一棵 View 还没 attach 完，
        // requestFocus 会安静地返回 false，那样遥控器在弹层里按什么都没反应。
        focusFirst(0);
    }

    private void focusFirst(final int attempt) {
        btnFav.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (disposed || !isShowing() || btnFav.isFocused()) return;
                if (btnFav.requestFocus()) return;
                if (attempt < 6) focusFirst(attempt + 1);
            }
        }, 100);
    }

    public void setOnAction(OnAction a) {
        this.action = a;
    }

    /**
     * 收藏状态变了：换星、换文案。
     *
     * <p>收藏动作是真异步不了的那一种（Activity 里同步改完就能读回来），所以
     * 不需要「点了先假装成功」的乐观更新 —— 那样一旦上限满了被拒，按钮会和卡面说反话。
     */
    public void setFav(boolean fav) {
        if (btnFav == null) return;
        btnFav.setText(fav ? R.string.v8_live_detail_fav_del : R.string.v8_live_detail_fav_add);
        Drawable star = ContextCompat.getDrawable(btnFav.getContext(),
                fav ? R.drawable.ic_fav_star : R.drawable.ic_fav_star_outline);
        if (star != null) {
            int side = dimen(btnFav, R.dimen.v8_live_dlg_star);
            star.setBounds(0, 0, side, side);
        }
        // 用 Relative 版本：跟着布局方向走，RTL 下星在右边
        btnFav.setCompoundDrawablesRelative(star, null, null, null);
    }

    // =====================================================================
    //  台标
    // =====================================================================

    /**
     * 台标：和卡面同一份文件（{@link LogoStore} 已有缓存就直接回），放大了画。
     *
     * <p>先写台名、图到手再收，规则跟卡面一致 —— 图要么是「还没下完」、
     * 要么是「库里没有」，两种情况都该看见字，而不是一块空底板。
     */
    private void bindLogo(String name) {
        final Context ctx = getContext();
        showName(name);
        String[] urls = ChannelLogo.candidates(ctx, rawName, srcLogo);
        if (urls.length == 0) return;
        LogoStore.load(ctx, urls, UiTheme.isLight(ctx), new LogoStore.Callback() {
            @Override
            public void onResult(File file) {
                // 不能在这里判 isShowing()：台标命中本地缓存时 LogoStore 是**同步**回调的，
                // 那一刻 show() 还没执行（弹层还在构造里），判了就永远只在「已经下载过
                // 但这次是异步返回」的情况下才显示台标 —— 实测就是卡面有图、弹层只有文字。
                if (disposed || file == null) return;
                Glide.with(ivLogo)
                        .load(file)
                        .dontAnimate()
                        .listener(new RequestListener<Drawable>() {
                            @Override
                            public boolean onLoadFailed(@Nullable GlideException e, Object model,
                                                        Target<Drawable> target, boolean isFirst) {
                                return false;
                            }

                            @Override
                            public boolean onResourceReady(Drawable resource, Object model,
                                                           Target<Drawable> target, DataSource source,
                                                           boolean isFirst) {
                                if (!disposed) tvLogoName.setVisibility(View.GONE);
                                return false;
                            }
                        })
                        .into(ivLogo);
            }
        });
    }

    /** 台名顶替台标：没图时那块底板里写的字 */
    private void showName(String name) {
        String text = TextUtils.isEmpty(name) ? rawName : name;
        if (TextUtils.isEmpty(text)) {
            tvLogoName.setVisibility(View.GONE);
            return;
        }
        tvLogoName.setText(text);
        tvLogoName.setVisibility(View.VISIBLE);
    }

    @Override
    public void dismiss() {
        // 先落标记再关窗：即使下载回调已经排在主线程队列里，也不能再碰旧弹层 View
        disposed = true;
        if (ivLogo != null) Glide.with(ivLogo).clear(ivLogo);
        super.dismiss();
    }

    private static int dimen(View v, int res) {
        return (int) v.getResources().getDimension(res);
    }
}
