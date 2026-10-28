package com.pigtv.ui.dialog;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.pigtv.R;
import com.pigtv.bean.MovieSort;
import com.pigtv.ui.adapter.FilterPillAdapter;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 影视页的筛选弹层。
 *
 * 之前筛选是二级菜单右边那排 chip：一屏只放得下 3 组，第 4 组以后还要再点
 * 「筛选 +N」；而且 chip 和标签挤在同一行，谁也说不清点哪个会动内容。
 * 现在整块改成这个面板 —— 二级标签上按 OK 就开，所有组一次列全。
 *
 * 交互约定（都是电视上验证过的）：
 * · 选中值立刻写进 sortData.filterSelect，关掉面板才真正重拉一次列表 ——
 *   每点一下都发一次请求会把源站打烦，也让人看不见列表的变化。
 * · 再点一次已选中的值 = 取消它（回到「全部」），不用专门去找「全部」那一项。
 * · 「清空」只清这个分类的条件，别的分类记的条件不动（分类是按 sort id 分开记的）。
 */
public class TvFilterDialog extends BaseDialog {

    private LinearLayout filterRoot;
    private TextView tvCat;
    private TextView tvCount;
    private TextView btnClear;

    private final List<TvRecyclerView> rows = new ArrayList<>();
    private final List<FilterPillAdapter> adapters = new ArrayList<>();

    private MovieSort.SortData sortData;
    private Runnable onApply;
    /** 用户动过条件：关掉时才需要重拉 */
    private boolean changed;
    /** 已经回调过一次，别重复刷 */
    private boolean applied;

    /** 源站常用英文键当组名，电视上全是英文很难认 */
    private static final String[][] CN = {
            {"year", "年份"}, {"years", "年份"}, {"yearrange", "年份"},
            {"type", "类型"}, {"class", "类型"}, {"category", "类型"}, {"cate", "类型"},
            {"genres", "类型"}, {"kind", "类型"}, {"typename", "类型"},
            {"area", "地区"}, {"zone", "地区"}, {"region", "地区"},
            {"by", "排序"}, {"order", "排序"}, {"sort", "排序"},
            {"lang", "语言"}, {"language", "语言"},
            {"letter", "字母"}, {"index", "字母"}, {"initial", "字母"},
            {"week", "星期"}, {"weekday", "星期"},
            {"duration", "时长"}, {"version", "版本"}, {"resource", "资源"},
            {"state", "剧情"}, {"plot", "剧情"}, {"story", "剧情"},
            {"actor", "演员"}, {"star", "演员"}, {"director", "导演"},
            {"status", "状态"}, {"season", "季"}, {"quality", "清晰度"}
    };

    public TvFilterDialog(@NonNull @NotNull Context context) {
        super(context);
        setCanceledOnTouchOutside(false);
        setCancelable(true);
        setContentView(R.layout.dialog_filter_tv);

        filterRoot = findViewById(R.id.filterRoot);
        tvCat = findViewById(R.id.tvFltCat);
        tvCount = findViewById(R.id.tvFltCount);
        btnClear = findViewById(R.id.btnFltClear);

        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clearAll();
            }
        });
        findViewById(R.id.btnFltDone).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
    }

    /** 关掉面板后触发（只在条件真的变了时才回调） */
    public void setOnApply(Runnable r) {
        onApply = r;
    }

    /**
     * 铺数据。每次都重建：同一个面板会被不同分类复用，
     * 组数、组名、选项都不一样，留着上一轮的 View 只会串台。
     */
    public void setData(MovieSort.SortData sd) {
        sortData = sd;
        filterRoot.removeAllViews();
        rows.clear();
        adapters.clear();
        changed = false;
        applied = false;

        tvCat.setText(sd == null || TextUtils.isEmpty(sd.name) ? "" : sd.name);

        if (sd == null || sd.filters == null || sd.filters.isEmpty()) {
            updateCount();
            return;
        }

        Context ctx = getContext();
        LayoutInflater inflater = LayoutInflater.from(ctx);

        for (MovieSort.SortFilter f : sd.filters) {
            if (f == null || f.values == null || f.values.isEmpty()) continue;

            View row = inflater.inflate(R.layout.item_filter_group, filterRoot, false);
            ((TextView) row.findViewById(R.id.tvFilterGroup)).setText(cnName(f.name, f.key));

            final TvRecyclerView rv = row.findViewById(R.id.rvFilterVals);
            rv.setHasFixedSize(true);
            rv.setLayoutManager(new V7LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false));

            final FilterPillAdapter ad = new FilterPillAdapter();
            rv.setAdapter(ad);

            List<FilterPillAdapter.Opt> opts = new ArrayList<>();
            boolean hasAll = false;
            for (Map.Entry<String, String> e : f.values.entrySet()) {
                String name = e.getKey() == null ? "" : e.getKey();
                String val = e.getValue() == null ? "" : e.getValue();
                // 源站自己也常给一项「全部 / 不限」，但提交值不是空串（有的写成 "全部"）。
                // 那种值发过去，站方多半当成一个普通分类，筛出来是空的 ——
                // 所以统一按「不筛」处理，并且不再额外插我们自己那一项，
                // 否则会出现两个「全部」，点哪个都一样，看着就是 bug。
                if (isAllName(name)) {
                    val = "";
                }
                if (val.isEmpty()) hasAll = true;
                opts.add(new FilterPillAdapter.Opt(name, val));
            }
            // 第一项永远是「全部 = 空串」：不少源自己就带一项值为空的「全部」，
            // 有就不重复插，否则会看到两个「全部」，点哪个都一样但看着像 bug
            if (!hasAll) opts.add(0, new FilterPillAdapter.Opt(ctx.getString(R.string.v8_flt_all), ""));

            String cur = (sd.filterSelect == null) ? "" : sd.filterSelect.get(f.key);
            ad.setNewData(opts);
            ad.setSelected(cur);

            final String key = f.key;
            ad.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
                @Override
                public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                    FilterPillAdapter.Opt opt = ad.getItem(position);
                    if (opt == null) return;
                    String curSel = (sd.filterSelect == null) ? "" : sd.filterSelect.get(key);
                    if (curSel == null) curSel = "";
                    String next = opt.value;
                    if (TextUtils.equals(curSel, next)) {
                        // 再点一次已选中的值 = 取消；「全部」本来就是空，点了没意义
                        if (next.isEmpty()) return;
                        next = "";
                    }
                    if (sd.filterSelect == null) sd.filterSelect = new java.util.HashMap<>();
                    if (next.isEmpty()) sd.filterSelect.remove(key);
                    else sd.filterSelect.put(key, next);
                    ad.setSelectedQuiet(rv, next);
                    changed = true;
                    updateCount();
                }
            });

            filterRoot.addView(row);
            rows.add(rv);
            adapters.add(ad);
        }

        updateCount();
    }

    private void clearAll() {
        if (sortData == null) return;
        boolean dirty = sortData.filterSelect != null && !sortData.filterSelect.isEmpty();
        if (sortData.filterSelect != null) sortData.filterSelect.clear();
        for (int i = 0; i < adapters.size(); i++) {
            adapters.get(i).setSelectedQuiet(i < rows.size() ? rows.get(i) : null, "");
        }
        if (dirty) changed = true;
        updateCount();
    }

    private void updateCount() {
        int n = (sortData == null || sortData.filterSelect == null) ? 0 : sortData.filterSelect.size();
        tvCount.setText(n > 0 ? getContext().getString(R.string.v8_flt_count, n) : "");
        // 没条件可清时按钮压暗：还能聚焦（不然焦点会在标题行断掉），但一眼看出没得清
        btnClear.setAlpha(n > 0 ? 1f : 0.4f);
    }

    @Override
    public void show() {
        changed = false;
        applied = false;
        super.show();

        Window w = getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            w.setAttributes(lp);
        }
        focusFirstPill(0);
    }

    /** 焦点先落在当前选中的那个值上，用户一眼看到「现在选的是哪个」 */
    private void focusFirstPill(final int attempt) {
        if (rows.isEmpty()) {
            return;
        }
        final TvRecyclerView rv = rows.get(0);
        rv.post(new Runnable() {
            @Override
            public void run() {
                int idx = adapters.get(0).selectedIndex();
                View target = rv.getLayoutManager() == null
                        ? null : rv.getLayoutManager().findViewByPosition(idx);
                if (target != null) {
                    target.requestFocus();
                } else if (attempt < 3) {
                    // 第一帧还没量完，等一下再试；实在拿不到就把焦点给整行
                    focusFirstPill(attempt + 1);
                } else {
                    rv.requestFocus();
                }
            }
        });
    }

    @Override
    public void dismiss() {
        boolean showing = isShowing();
        super.dismiss();
        if (!showing || applied) return;
        applied = true;
        if (changed && onApply != null) onApply.run();
    }

    /** 「全部 / 不限 / 所有」这类选项等价于「不筛」 */
    private boolean isAllName(String name) {
        if (TextUtils.isEmpty(name)) return false;
        String n = name.trim();
        return n.equals("全部") || n.equals("不限") || n.equals("所有")
                || n.equalsIgnoreCase("all") || n.equals("默认");
    }

    /** 组名中文化：本身就是中文的原样放行，映射表里没有的也原样放行（硬翻容易翻错） */
    private String cnName(String name, String key) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) n = key == null ? "" : key.trim();
        if (n.isEmpty()) return "";
        for (int i = 0; i < n.length(); i++) {
            if (n.charAt(i) >= 0x4E00) return n;   // 含汉字
        }
        String k = n.toLowerCase().replaceAll("[\\s_-]+", "");
        for (String[] pair : CN) {
            if (pair[0].equals(k)) return pair[1];
        }
        return n;
    }
}
