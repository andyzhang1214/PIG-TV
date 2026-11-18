package com.pigtv.ui.adapter;

import android.text.TextUtils;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.pigtv.R;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 直播频道墙的适配器。
 *
 * <p>一格一个频道，按「全部」时是全站频道平铺（几十到几百格），选了分组就只留那一组。
 * 分组不做成多个列表：源站给的分组顺序没有意义，用户也记不住哪个台在第几组，
 * 平铺 + 顶上一排胶囊是手机上验证过的找台方式。
 *
 * <p>台标用文字方块（CCTV-5 → C5），和手机端 {@code liveLogo()} 同一套规则 ——
 * 两端口径一致，用户换个设备看到的还是同一个方块，不会以为走错了 App。
 */
public class LiveChannelCardAdapter extends BaseQuickAdapter<LiveChannelCardAdapter.Cell, BaseViewHolder> {

    /** CCTV5 / CCTV-5 / cctv 5 都算，取数字 */
    private static final Pattern CCTV = Pattern.compile("cctv[\\s\\-]*(\\d+)", Pattern.CASE_INSENSITIVE);

    /** 名字里这些词对认台没帮助，取首字前先剔掉 */
    private static final Pattern NOISE = Pattern.compile("高清|标清|超清|蓝光|频道|电视台|卫视|综合|[\\s\\-（）()]");

    /**
     * 一个频道格。
     *
     * <p>{@code groupIndex} / {@code channelIndex} 必须是「在 ApiConfig.channelGroupList 里的原始下标」：
     * LivePlayActivity 拿它们直接 {@code list.get(groupIndex)} / {@code channels.get(channelIndex)}，
     * 自己重新编号就会播到隔壁那个台。
     */
    public static class Cell {
        public final int groupIndex;
        public final int channelIndex;
        public final String name;
        public final String sub;
        public final String logo;
        /** 上次看的那个台，进页面时描一圈边 */
        public boolean selected = false;

        public Cell(int groupIndex, int channelIndex, String name, String sub) {
            this.groupIndex = groupIndex;
            this.channelIndex = channelIndex;
            this.name = name == null ? "" : name;
            this.sub = sub == null ? "" : sub;
            this.logo = logoOf(name);
        }
    }

    public LiveChannelCardAdapter() {
        super(R.layout.item_live_channel_card, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder helper, Cell item) {
        helper.setText(R.id.tvLogo, item.logo);
        helper.setText(R.id.tvName, item.name);
        helper.setText(R.id.tvSub, item.sub);
        helper.itemView.setSelected(item.selected);
    }

    /** 上次看的那个台换了位置，只改这两格的 selected，不整表 notify（电视上一 notify 焦点就丢） */
    public void setSelectedQuiet(androidx.recyclerview.widget.RecyclerView rv, int position) {
        for (int i = 0; i < getData().size(); i++) {
            getData().get(i).selected = (i == position);
        }
        if (rv == null) {
            notifyDataSetChanged();
            return;
        }
        for (int i = 0; i < rv.getChildCount(); i++) {
            android.view.View child = rv.getChildAt(i);
            int pos = rv.getChildAdapterPosition(child);
            if (pos < 0 || pos >= getData().size()) continue;
            child.setSelected(getData().get(pos).selected);
        }
    }

    /** 频道名 → 台标方块里那两三个字 */
    public static String logoOf(String name) {
        String s = name == null ? "" : name.trim();
        if (s.isEmpty()) return "·";
        Matcher m = CCTV.matcher(s);
        if (m.find()) {
            String num = m.group(1);
            return num == null || num.isEmpty() ? "C" : "C" + num;
        }
        String t = NOISE.matcher(s).replaceAll("").trim();
        if (TextUtils.isEmpty(t)) t = s;
        // 有些台名以数字/符号开头（"1 频道"、"· 测试"），挑第一个汉字或字母当台标更好认
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isLetterOrDigit(c)) return String.valueOf(c);
        }
        return String.valueOf(t.charAt(0));
    }
}
