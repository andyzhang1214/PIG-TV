package com.pigtv.ui.fragment;
import android.content.res.TypedArray;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.BounceInterpolator;
import androidx.core.content.ContextCompat;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import com.blankj.utilcode.util.GsonUtils;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.pigtv.R;
import com.pigtv.api.ApiConfig;
import com.pigtv.base.BaseLazyFragment;
import com.pigtv.bean.AbsXml;
import com.pigtv.bean.Movie;
import com.pigtv.bean.MovieSort;
import com.pigtv.bean.SourceBean;
import com.pigtv.event.RefreshEvent;
import com.pigtv.event.SortPageEvent;
import com.pigtv.ui.activity.DetailActivity;
import com.pigtv.ui.activity.FastSearchActivity;
import com.pigtv.ui.activity.SearchActivity;
import com.pigtv.ui.adapter.GridAdapter;
import com.pigtv.ui.adapter.GridFilterKVAdapter;
import com.pigtv.ui.dialog.GridFilterDialog;
import com.pigtv.ui.tv.widget.LoadMoreView;
import com.pigtv.util.ImgUtil;
import com.pigtv.util.FastClickCheckUtil;
import com.pigtv.util.LOG;
import com.pigtv.util.HawkConfig;
import com.pigtv.viewmodel.SourceViewModel;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import java.util.ArrayList;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.greenrobot.eventbus.EventBus;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Stack;

/**
 * @author pj567
 * @date :2020/12/21
 * @description:
 */
public class GridFragment extends BaseLazyFragment {
    private MovieSort.SortData sortData = null;
    private TvRecyclerView mGridView;
    private SourceViewModel sourceViewModel;
    private GridFilterDialog gridFilterDialog;
    private GridAdapter gridAdapter;
    private int page = 1;
    private int maxPage = 1;
    private boolean isLoad = false;
    private boolean isTop = true;
    private View focusedView = null;
    /**
     * 一页几列。既是布局参数，也是「翻页」的判据：
     * 焦点走到倒数第一行（position >= 总数 - 列数）就该取下一页了。
     */
    private int spanCount = 5;
    /** 正在取下一页。getList 没有并发保护，回包只走一条 LiveData，重入必串台 */
    private boolean pageLoading = false;
    /** 已经翻到底（服务端 pagecount 到顶），别再请求了 */
    private boolean noMorePage = false;

    private static class GridInfo{
        public String sortID="";
        public TvRecyclerView mGridView;
        public GridAdapter gridAdapter;
        public int page = 1;
        public int maxPage = 1;
        public boolean isLoad = false;
        public View focusedView = null;
    }

    Stack<GridInfo> mGrids = new Stack<GridInfo>(); //ui栈

    public static GridFragment newInstance(MovieSort.SortData sortData) {
        return new GridFragment().setArguments(sortData);
    }

    public GridFragment setArguments(MovieSort.SortData sortData) {
        this.sortData = sortData;
        return this;
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_grid;
    }
    
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null && this.sortData == null) {
            this.sortData = GsonUtils.fromJson(savedInstanceState.getString("sortDataJson"), MovieSort.SortData.class);
        }
    }

    @Override
    protected void init() {
        initView();
        initViewModel();
        initData();
    }
    
    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("sortDataJson", GsonUtils.toJson(sortData));        
    }

    private void changeView(String id,Boolean isFolder){
        if(isFolder){
            this.sortData.flag =style==null?"1":"2"; // 修改sortData.flag
        }else {
            this.sortData.flag ="2"; // 修改sortData.flag
        }
        initView();
        this.sortData.id = id; // 修改sortData.id为新的ID
        initViewModel();
        initData();
    }

    public boolean isFolederMode() {
        return (getUITag() == '1');
    }

    // 获取当前页面UI的显示模式 ‘0’ 正常模式 '1' 文件夹模式 '2' 显示缩略图的文件夹模式
    public char getUITag() {
        return (sortData == null || sortData.flag == null || sortData.flag.length() == 0) ? '0' : sortData.flag.charAt(0);
    }

    // 是否允许聚合搜索 sortData.flag的第二个字符为‘1’时允许聚搜
    public boolean enableFastSearch() {  return sortData.flag == null || sortData.flag.length() < 2 || (sortData.flag.charAt(1) == '1'); }
    //public boolean enableFastSearch() {  return (sortData.flag == null || sortData.flag.length() < 2) ? true : (sortData.flag.charAt(1) == '1'); }

    // 保存当前页面
    private void saveCurrentView() {
        if (this.mGridView == null) return;
        GridInfo info = new GridInfo();
        info.sortID = this.sortData.id;
        info.mGridView = this.mGridView;
        info.gridAdapter = this.gridAdapter;
        info.page = this.page;
        info.maxPage = this.maxPage;
        info.isLoad = this.isLoad;
        info.focusedView = this.focusedView;
        this.mGrids.push(info);
    }

    // 丢弃当前页面，将页面还原成上一个保存的页面
    public boolean restoreView() {
        if (mGrids.empty()) return false;
        this.showSuccess();
        ((ViewGroup) mGridView.getParent()).removeView(this.mGridView); // 重父窗口移除当前控件
        GridInfo info = mGrids.pop();// 还原上次保存的控件
        this.sortData.id = info.sortID;
        this.mGridView = info.mGridView;
        this.gridAdapter = info.gridAdapter;
        this.page = info.page;
        this.maxPage = info.maxPage;
        this.isLoad = info.isLoad;
        this.focusedView = info.focusedView;
        this.mGridView.setVisibility(View.VISIBLE);
//        if(this.focusedView != null){ this.focusedView.requestFocus(); }
        if (mGridView != null) mGridView.requestFocus();
        return true;
    }

    private ImgUtil.Style style;
    // 更改当前页面
    private void createView() {
        this.saveCurrentView(); // 保存当前页面
        if (mGridView == null) { // 从layout中拿view
            mGridView = findViewById(R.id.mGridView);
        } else { // 复制当前view
            TvRecyclerView v3 = new TvRecyclerView(this.mContext);
            v3.setSpacingWithMargins(10, 10);
            v3.setLayoutParams(mGridView.getLayoutParams());
            v3.setPadding(mGridView.getPaddingLeft(), mGridView.getPaddingTop(), mGridView.getPaddingRight(), mGridView.getPaddingBottom());
            v3.setClipToPadding(mGridView.getClipToPadding());
            ((ViewGroup) mGridView.getParent()).addView(v3);
            mGridView.setVisibility(View.GONE);
            mGridView = v3;
            mGridView.setVisibility(View.VISIBLE);
        }
        mGridView.setHasFixedSize(true);
        style=ImgUtil.initStyle();
        // 首页 v8 分类页：右栏要塞 6 列，用缩小版卡片（item_grid_v8）；
        // 站点自己指定了图片风格（style != null）时仍按它来，不硬盖
        gridAdapter = new GridAdapter(isFolederMode(), style, R.layout.item_grid_v8);
        this.page = 1;
        this.maxPage = 1;
        this.isLoad = false;
        this.pageLoading = false;
        this.noMorePage = false;
    }

    private void initView() {
        this.createView();
        mGridView.setAdapter(gridAdapter);
        if (isFolederMode()) {
            mGridView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));
        } else {
            // 分类页（v10）：分类搬到顶栏后左列腾空，5 列大卡正好铺满 1218 的版心，
            // 卡片尺寸和推荐页那行完全同款（215×322mm）。
            spanCount = 5;
            if (style != null) {
                spanCount = ImgUtil.spanCountByStyle(style, spanCount);
            }
            if (spanCount == 1) {
                mGridView.setLayoutManager(new V7LinearLayoutManager(mContext, spanCount, false));
            } else {
                mGridView.setLayoutManager(new V7GridLayoutManager(mContext, spanCount));
            }
        }

        gridAdapter.setOnLoadMoreListener(new BaseQuickAdapter.RequestLoadMoreListener() {
            @Override
            public void onLoadMoreRequested() {
                gridAdapter.setEnableLoadMore(true);
                // 和焦点触发的翻页共用一把锁，别同时发两个请求（getList 会串台）
                if (pageLoading || noMorePage) return;
                pageLoading = true;
                sourceViewModel.getList(sortData, page);
            }
        }, mGridView);
        mGridView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                // 放大动效统一由 GridAdapter 的 OnFocusChangeListener 处理（1.06 / 180ms）。
                // 这里原本还会弹 1.2 倍 BounceInterpolator，两套动效打架，
                // 1.2 倍的卡片会盖住隔壁格，v8 分类页直接交给 adapter。
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                maybeLoadNextPage(position);
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {

            }
        });
        mGridView.setOnInBorderKeyEventListener(new TvRecyclerView.OnInBorderKeyEventListener() {
            @Override
            public boolean onInBorderKeyEvent(int direction, View focused) {
                if (direction == View.FOCUS_UP) {
                }
                return false;
            }
        });
        gridAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                Movie.Video video = gridAdapter.getData().get(position);
                if (video != null) {
                    Bundle bundle = new Bundle();
                    bundle.putString("id", video.id);
                    bundle.putString("sourceKey", video.sourceKey);
                    bundle.putString("title", video.name);
                    if( video.tag !=null && (video.tag.equals("folder") || video.tag.equals("cover"))){
                        focusedView = view;
                        if(("12".indexOf(getUITag()) != -1)){
                            changeView(video.id,video.tag.equals("folder"));
                        }else {
                            changeView(video.id,false);
                        }
                    } else {
                        if (video.id == null || video.id.isEmpty() || video.id.startsWith("msearch:")) {
                            if(Hawk.get(HawkConfig.FAST_SEARCH_MODE, false) && enableFastSearch()){
                                jumpActivity(FastSearchActivity.class, bundle);
                            }else {
                                jumpActivity(SearchActivity.class, bundle);
                            }
                        } else {
                            jumpActivity(DetailActivity.class, bundle);
                        }
                    }

                }
            }
        });
        // takagen99 : Long Press to Fast Search
        gridAdapter.setOnItemLongClickListener(new BaseQuickAdapter.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                Movie.Video video = gridAdapter.getData().get(position);
                if (video != null) {
                    Bundle bundle = new Bundle();
                    bundle.putString("id", video.id);
                    bundle.putString("sourceKey", video.sourceKey);
                    bundle.putString("title", video.name);
                    jumpActivity(FastSearchActivity.class, bundle);
                }
                return true;
            }
        });
        gridAdapter.setLoadMoreView(new LoadMoreView());
        setLoadSir(mGridView);
    }

    /**
     * 焦点走到倒数第一行 → 主动取下一页。
     *
     * 为什么不能只靠 BaseQuickAdapter 的 setOnLoadMoreListener：
     * 它内部监听的是 RecyclerView 的 onScrolled，只有「真的滚到底」才回调。
     * 但电视是方向键移焦点，不是手指滑动 —— 一页 10 部（5 列 2 行）连一屏都
     * 撑不满，RecyclerView 压根没有滚动事件，于是第二页永远不来。
     * 用户看到的就是「分类页只有 10 部，按下键焦点卡在最后一行不动」。
     *
     * 这里改成按焦点位置判断，跟有没有滚动无关；原来的滚动监听保留，
     * 触摸屏/鼠标滚轮场景还能用。两边共用 pageLoading 锁。
     */
    private void maybeLoadNextPage(int position) {
        if (pageLoading || noMorePage) return;
        if (gridAdapter == null || sortData == null || sourceViewModel == null) return;
        int total = gridAdapter.getData().size();
        if (total <= 0) return;
        // 焦点还在前面的行就先不取，免得一进页面就把后面几页全拉下来
        if (position < total - spanCount) return;
        pageLoading = true;
        sourceViewModel.getList(sortData, page);
    }

    private void initViewModel() {
        if (sourceViewModel != null) {
            return;
        }
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        sourceViewModel.listResult.observe(this, new Observer<AbsXml>() {
            @Override
            public void onChanged(AbsXml absXml) {
//                if(mGridView != null) mGridView.requestFocus();
                // 不管成功失败都要解锁：卡住的话第二页就再也取不到了
                pageLoading = false;
                if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                    if (page == 1) {
                        showSuccess();
                        isLoad = true;
                        gridAdapter.setNewData(absXml.movie.videoList);
                        // 把第一条抛给 HomeActivity，让 hero 换成「这个分类的最新一部」
                        if (sortData != null) {
                            EventBus.getDefault().post(new SortPageEvent(
                                    sortData.id, absXml.movie.videoList.get(0)));
                        }
                    } else {
                        gridAdapter.addData(absXml.movie.videoList);
                    }
                    page++;
                    maxPage = absXml.movie.pagecount;
                    noMorePage = (maxPage != 0 && page > maxPage);
                    if (noMorePage) {
                        gridAdapter.loadMoreEnd();
                        gridAdapter.setEnableLoadMore(false);
                    } else {
                        gridAdapter.loadMoreComplete();
                        gridAdapter.setEnableLoadMore(true);
                    }
                } else {
                    if (page == 1) {
                        showEmpty();
                    }
                    noMorePage = (maxPage != 0 && page > maxPage);
                    if (noMorePage) {
                        Toast.makeText(getContext(), "没有更多了", Toast.LENGTH_SHORT).show();
                        gridAdapter.loadMoreEnd();
                    } else {
                        gridAdapter.loadMoreComplete();
                    }
                    gridAdapter.setEnableLoadMore(false);
                }
            }
        });
    }

    public boolean isLoad() {
        return isLoad || !mGrids.empty(); //如果有缓存页的话也可以认为是加载了数据的
    }

    private void initData() {
    	if (ApiConfig.get().getHomeSourceBean().getApi()==null) {
            showEmpty();
            return;
        }
        showLoading();
        isLoad = false;
        pageLoading = false;
        noMorePage = false;
        scrollTop();
        toggleFilterStatus();
        sourceViewModel.getList(sortData, page);
    }

    private void toggleFilterStatus() {
        if (sortData!=null && sortData.filters != null && !sortData.filters.isEmpty()) {
            int count = sortData.filterSelectCount();
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_FILTER_CHANGE, count));
        }
    }

    public boolean isTop() {
        return isTop;
    }

    public void scrollTop() {
        isTop = true;
        mGridView.scrollToPosition(0);
    }

    /** 把焦点交给海报墙（分类页从左列按 → 时调用） */
    public void requestGridFocus() {
        if (mGridView == null) return;
        if (!mGridView.hasFocus()) mGridView.requestFocus();
    }

    /**
     * 给 HomeActivity 用：分类页真正的那个网格。
     * 不要通过 ViewPager 的 child 去找 —— setLoadSir 会把 RecyclerView 套一层，
     * 而且 ViewPager 在页面缓存/复用时 getChildAt(currentItem) 可能拿到空容器。
     */
    public androidx.recyclerview.widget.RecyclerView gridRecycler() {
        return mGridView;
    }

    public void showFilter() {
    	if (sortData!=null && !sortData.filters.isEmpty() && gridFilterDialog == null) {
            gridFilterDialog = new GridFilterDialog(mContext);
//            gridFilterDialog.setData(sortData);
//            gridFilterDialog.setOnDismiss(new GridFilterDialog.Callback() {
//                @Override
//                public void change() {
//                    page = 1;
//                    initData();
//                }
//            });
            setFilterDialogData();
        }
        if (gridFilterDialog != null)
            gridFilterDialog.show();
    }

    public void setFilterDialogData() {
        Context context = getContext();
        LayoutInflater inflater = LayoutInflater.from(context);
        assert context != null;

        // 获取动态主题颜色
        TypedArray a = getContext().obtainStyledAttributes(R.styleable.themeColor);
        int selectedColor = a.getColor(R.styleable.themeColor_color_theme, 0); // 选择的颜色
        int defaultColor = ContextCompat.getColor(context, R.color.color_FFFFFF);
        // 释放 TypedArray 资源
        a.recycle();
        // 遍历过滤条件数据
        for (MovieSort.SortFilter filter : sortData.filters) {
            View line = inflater.inflate(R.layout.item_grid_filter, gridFilterDialog.filterRoot, false);
            TextView filterNameTv = line.findViewById(R.id.filterName);
            filterNameTv.setText(filter.name);
            TvRecyclerView gridView = line.findViewById(R.id.mFilterKv);
            gridView.setHasFixedSize(true);
            gridView.setLayoutManager(new V7LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
            GridFilterKVAdapter adapter = new GridFilterKVAdapter();
            gridView.setAdapter(adapter);
            final String key = filter.key;
            final ArrayList<String> values = new ArrayList<>(filter.values.keySet());
            final ArrayList<String> keys = new ArrayList<>(filter.values.values());
            adapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
                // 用于记录上一次选中的 view
                View previousSelectedView = null;
                @Override
                public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                    String currentSelection = sortData.filterSelect.get(key);
                    String newSelection = keys.get(position);
                    if (currentSelection == null || !currentSelection.equals(newSelection)) {
                        // 更新选中状态
                        sortData.filterSelect.put(key, newSelection);
                        updateViewStyle(view, selectedColor, true);
                        if (previousSelectedView != null) {
                            updateViewStyle(previousSelectedView, defaultColor, false);
                        }
                        previousSelectedView = view;
                    } else {
                        // 取消选中
                        sortData.filterSelect.remove(key);
                        if (previousSelectedView != null) {
                            updateViewStyle(previousSelectedView, defaultColor, false);
                        }
                        previousSelectedView = null;
                    }
                    forceRefresh();
                }
                private void updateViewStyle(View view, int color, boolean isBold) {
                    TextView valueTv = view.findViewById(R.id.filterValue);
                    valueTv.getPaint().setFakeBoldText(isBold);
                    valueTv.setTextColor(color);
                }
            });
            adapter.setNewData(values);
            gridFilterDialog.filterRoot.addView(line);
        }
    }

    public void forceRefresh() {
        page = 1;
        initData();
    }
}
