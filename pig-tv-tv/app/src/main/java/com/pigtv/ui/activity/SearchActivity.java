package com.pigtv.ui.activity;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.BounceInterpolator; //添加放大效果
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.DiffUtil;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.catvod.crawler.JsLoader;
import com.pigtv.R;
import com.pigtv.api.ApiConfig;
import com.pigtv.base.BaseActivity;
import com.pigtv.bean.AbsXml;
import com.pigtv.bean.Movie;
import com.pigtv.bean.SourceBean;
import com.pigtv.cache.SearchHistory;
import com.pigtv.data.SearchPresenter;
import com.pigtv.event.InputMsgEvent;
import com.pigtv.event.RefreshEvent;
import com.pigtv.event.ServerEvent;
import com.pigtv.server.ControlManager;
import com.pigtv.ui.adapter.PinyinAdapter;
import com.pigtv.ui.adapter.SearchAdapter;
import com.pigtv.ui.adapter.SelectDialogAdapter;
import com.pigtv.ui.dialog.RemoteDialog;
import com.pigtv.ui.dialog.SearchCheckboxDialog;
import com.pigtv.ui.dialog.SelectDialog;
import com.pigtv.ui.tv.QRCodeGen;
import com.pigtv.ui.tv.widget.CustomEditText;
import com.pigtv.ui.tv.widget.SearchKeyboard;
import com.pigtv.util.FastClickCheckUtil;
import com.pigtv.util.HawkConfig;
import com.pigtv.util.SearchHelper;
import com.pigtv.util.SettingsUtil;
import com.pigtv.util.V8TopBar;
import com.pigtv.viewmodel.SourceViewModel;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;
import com.yang.flowlayoutlibrary.FlowLayout;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import me.jessyan.autosize.utils.AutoSizeUtils;

/**
 * @author pj567
 * @date :2020/12/23
 * @description:
 */
public class SearchActivity extends BaseActivity {
    private LinearLayout llLayout;
    private TvRecyclerView mGridView;
    private TvRecyclerView mGridViewWord;
    private SourceViewModel sourceViewModel;
    private CustomEditText etSearch;
    private TextView tvSearch;
    private TextView tvClear;
    private SearchKeyboard keyboard;
    private TextView tvAddress;
    private ImageView ivQRCode;
    private SearchAdapter searchAdapter;
    private PinyinAdapter wordAdapter;
    private String searchTitle = "";
    private ImageView tvSearchCheckbox;
    private TextView filterBtn;

    private RelativeLayout searchTips;
    private FlowLayout tv_history;
    private LinearLayout llWord;

    private ImageView clearHistory;
    private SearchPresenter searchPresenter;

    private String sKey;
    public String keyword;

    // 搜索进度：全源搜索时 179 个站排队跑，用户看不到进度会以为"只搜了当前源"
    private int searchTotal = 0;
    private int searchDone = 0;
    private String filterBtnBase = "全局搜索";
    // 兜底收尾：某些站的 spider 会挂住不回包，没有超时就会一直转圈。
    // 35 秒指的是「一声不吭这么久」—— 只要还有站慢慢回结果，这个计时就会重置。
    private static final long SEARCH_TIMEOUT_MS = 35000L;
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private Runnable timeoutRunnable;

    private TextView tHotSearchText;
    private static ArrayList<String> hots = new ArrayList<>();
    private HashMap<String, String> mCheckSources = null;
    private SearchCheckboxDialog mSearchCheckboxDialog = null;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_search;
    }

    private static Boolean hasKeyBoard;

    @Override
    protected void init() {
        disableKeyboard(SearchActivity.this);
        initView();
        initViewModel();
        initData();
        // 顶上多了导航栏，系统默认会把焦点丢给第一个可聚焦的导航项；
        // 搜索页的入口一直是最上面那个「全局搜索」按钮，把焦点还给它
        filterBtn.post(new Runnable() {
            @Override
            public void run() {
                filterBtn.requestFocus();
            }
        });
    }

    /*
     * 禁止软键盘
     * @param activity Activity
     */
    public static void disableKeyboard(Activity activity) {
        hasKeyBoard = false;
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
    }

    /*
     * 启用软键盘
     * @param activity Activity
     */
    public static void enableKeyboard(Activity activity) {
        hasKeyBoard = true;
        activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
    }

    public void openSystemKeyBoard() {
        InputMethodManager imm = (InputMethodManager) this.getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.showSoftInput(this.getCurrentFocus(), InputMethodManager.SHOW_FORCED);
    }

    public void hideSystemKeyBoard() {
        InputMethodManager imm = (InputMethodManager) this.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm.isActive()) {
            imm.hideSoftInputFromWindow(etSearch.getApplicationWindowToken(), InputMethodManager.RESULT_UNCHANGED_SHOWN);
        }
    }

    private boolean isKeyboardHidden() {
        final View rootView = getWindow().getDecorView().findViewById(android.R.id.content);
        Rect r = new Rect();
        rootView.getWindowVisibleDisplayFrame(r);
        return rootView.getBottom() == r.bottom;
    }

    private List<Runnable> pauseRunnable = null;

    @Override
    protected void onResume() {
        super.onResume();
        if (pauseRunnable != null && pauseRunnable.size() > 0) {
            allRunCount.set(pauseRunnable.size());
            if (sourceViewModel != null) {
                sourceViewModel.initExecutor();
                for (Runnable runnable : pauseRunnable) {
                    sourceViewModel.execute(runnable);
                }
            }
            pauseRunnable.clear();
            pauseRunnable = null;
        }
    }

    private void initView() {
        EventBus.getDefault().register(this);
        V8TopBar.attach(this, V8TopBar.NAV_SEARCH);
        llLayout = findViewById(R.id.llLayout);
        llWord = findViewById(R.id.llWord);
        etSearch = findViewById(R.id.etSearch);
        tvSearch = findViewById(R.id.tvSearch);
        tvSearchCheckbox = findViewById(R.id.tvSearchCheckbox);
        filterBtn = findViewById(R.id.filterBtn);
        tHotSearchText = findViewById(R.id.mHotSearch_text);
        searchTips = findViewById(R.id.search_tips);
        tv_history = findViewById(R.id.tv_history);
        clearHistory = findViewById(R.id.clear_history);
        tvClear = findViewById(R.id.tvClear);
        tvAddress = findViewById(R.id.tvAddress);
        ivQRCode = findViewById(R.id.ivQRCode);
        mGridView = findViewById(R.id.mGridView);
        keyboard = findViewById(R.id.keyBoardRoot);
        mGridViewWord = findViewById(R.id.mGridViewWord);
        mGridViewWord.setHasFixedSize(true);
        mGridViewWord.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));
        wordAdapter = new PinyinAdapter();
        mGridViewWord.setAdapter(wordAdapter);


        wordAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                keyword = wordAdapter.getItem(position);
                String[] split = keyword.split("\uFEFF");
                keyword = split[split.length - 1];
                etSearch.setText(keyword);
                if (Hawk.get(HawkConfig.FAST_SEARCH_MODE, false)) {
                    Bundle bundle = new Bundle();
                    bundle.putString("title", keyword);
                    refreshSearchHistory(keyword);
                    jumpActivity(FastSearchActivity.class, bundle);
                } else {
                    search(keyword);
                }
            }
        });
		//添加放大效果
        mGridView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                itemView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(300).setInterpolator(new BounceInterpolator()).start();
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                itemView.animate().scaleX(1.15f).scaleY(1.15f).setDuration(300).setInterpolator(new BounceInterpolator()).start();
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {

            }
        });
        mGridView.setHasFixedSize(true);
        // lite
        if (Hawk.get(HawkConfig.SEARCH_VIEW, 0) == 0)
            mGridView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));
            // with preview
        else
            mGridView.setLayoutManager(new V7GridLayoutManager(this.mContext, 4));
        searchAdapter = new SearchAdapter();
        mGridView.setAdapter(searchAdapter);
        searchAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                Movie.Video video = searchAdapter.getData().get(position);
                if (video != null) {
                    try {
                        if (sourceViewModel != null) {
                            pauseRunnable = sourceViewModel.shutdownNow();
                            JsLoader.stopAll();
                            sourceViewModel.destroyExecutor();
                        }
                    } catch (Throwable th) {
                        th.printStackTrace();
                    }
                    Bundle bundle = new Bundle();
                    bundle.putString("id", video.id);
                    bundle.putString("sourceKey", video.sourceKey);
                    jumpActivity(DetailActivity.class, bundle);
                }
            }
        });
        tvSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                if (!TextUtils.isEmpty(keyword)) {
                    if (Hawk.get(HawkConfig.FAST_SEARCH_MODE, false)) {
                        Bundle bundle = new Bundle();
                        bundle.putString("title", keyword);
                        refreshSearchHistory(keyword);
                        jumpActivity(FastSearchActivity.class, bundle);
                    } else {
                        search(keyword);
                    }
                } else {
                    Toast.makeText(mContext, getString(R.string.search_input), Toast.LENGTH_SHORT).show();
                }
            }
        });
        tvClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                etSearch.setText("");
                wordAdapter.setNewData(hots);
                mGridViewWord.smoothScrollToPosition(0);
                tHotSearchText.setText("热门搜索");
                cancel();
            }
        });

        this.etSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            public void afterTextChanged(Editable s) {
                keyword = s.toString().trim();
                if (TextUtils.isEmpty(keyword)) {
                    cancel();
                    tv_history.setVisibility(View.VISIBLE);
                    searchTips.setVisibility(View.VISIBLE);
                    llWord.setVisibility(View.VISIBLE);
                    mGridView.setVisibility(View.GONE);
                }
            }
        });
        etSearch.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_ENTER) {
                    hideSystemKeyBoard();
                }
                return false;
            }
        });
        etSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
//                Toast.makeText(mContext,"点击",Toast.LENGTH_SHORT).show();
                if (!hasKeyBoard) enableKeyboard(SearchActivity.this);
                openSystemKeyBoard();//再次尝试拉起键盘
                SearchActivity.this.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            }
        });
        etSearch.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View view, int keyCode, KeyEvent keyEvent) {
                if (isKeyboardHidden()) {
                    if (keyEvent.getAction() == KeyEvent.ACTION_DOWN) {
                        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                                    .showSoftInput(etSearch, 0);
                            return false;
                        }
                    } else if (keyEvent.getAction() == KeyEvent.ACTION_UP) {
                        int len = etSearch.getText().length();
                        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                            // Avoid show ime keyboard bug
                            return true;
                        } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                            etSearch.focusSearch(View.FOCUS_DOWN).requestFocus();
                            return true;
                        } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && (len == 0 || etSearch.getSelectionStart() == len)) {
                            etSearch.focusSearch(View.FOCUS_RIGHT).requestFocus();
                            return true;
                        }
                    }
                }
                return false;
            }
        });
        clearHistory.setOnClickListener(v -> {
            searchPresenter.clearSearchHistory();
            initSearchHistory();
        });

        keyboard.setOnSearchKeyListener(new SearchKeyboard.OnSearchKeyListener() {
            @Override
            public void onSearchKey(int pos, String key) {
                if (pos > 1) {
                    String text = etSearch.getText().toString().trim();
                    text += key;
                    etSearch.setText(text);
                    if (text.length() > 0) {
                        loadRec(text);
                    }
                } else if (pos == 1) {
                    String text = etSearch.getText().toString().trim();
                    if (text.length() > 0) {
                        text = text.substring(0, text.length() - 1);
                        etSearch.setText(text);
                    }
                    if (text.length() > 0) {
                        loadRec(text);
                    }
                    if (text.length() == 0) {
                        wordAdapter.setNewData(hots);
                        mGridViewWord.smoothScrollToPosition(0);
                        tHotSearchText.setText("热门搜索");
                    }
                } else if (pos == 0) {
                    RemoteDialog remoteDialog = new RemoteDialog(mContext);
                    remoteDialog.show();
                }
            }
        });
        setLoadSir(llLayout);

        // 搜索范围：和手机端保持一致 —— 永远搜「所有能搜的站」。
        // 这个键以前是「搜索数据源」单选框存下来的：只要选过一次某个站，
        // 之后每次搜索都只发那一个站，用户看到的就是「只有当前源的结果」。
        SettingsUtil.hkPut(HawkConfig.SEARCH_FILTER_KEY, "");
        sKey = "";

        filterBtn.setSelected(true);//能动起来的关键代码
        filterBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openSourcePicker();
            }
        });
        tvSearchCheckbox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openSourcePicker();
            }
        });
    }

    /**
     * 站点多选：决定这一轮搜索打哪些站。
     * 默认全选（SearchHelper 在没有记录时会把所有可搜站都放进去），
     * 用户可以只留几个常用的 —— 但那是「减法」，不是默认值。
     */
    private void openSourcePicker() {
        if (mCheckSources == null) initCheckedSourcesForSearch();
        if (mSearchCheckboxDialog == null) {
            List<SourceBean> searchable = new ArrayList<>();
            for (SourceBean b : ApiConfig.get().getSourceBeanList()) {
                if (b != null && b.isSearchable()) searchable.add(b);
            }
            if (searchable.isEmpty()) {
                Toast.makeText(mContext, R.string.search_site, Toast.LENGTH_SHORT).show();
                return;
            }
            mSearchCheckboxDialog = new SearchCheckboxDialog(SearchActivity.this, searchable, mCheckSources);
        }
        mSearchCheckboxDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                // 勾完回来，按钮上的「全部源 · 179 站」得跟着变
                updateSourceLabel();
            }
        });
        mSearchCheckboxDialog.show();
    }

    /** 搜索按钮的字：搜全部就写站数，只勾了一部分写「已选 M/N」 */
    private void updateSourceLabel() {
        int total = 0, checked = 0;
        for (SourceBean b : ApiConfig.get().getSourceBeanList()) {
            if (b == null || !b.isSearchable()) continue;
            total++;
            if (mCheckSources == null || mCheckSources.isEmpty()
                    || mCheckSources.containsKey(b.getKey())) checked++;
        }
        filterBtnBase = (checked >= total)
                ? getString(R.string.search_all_src, total)
                : getString(R.string.search_part_src, checked, total);
        filterBtn.setText(filterBtnBase);
    }

    private void refreshSearchHistory(String keyword2) {
        if (!this.searchPresenter.keywordsExist(keyword2)) {
            this.searchPresenter.addKeyWordsTodb(keyword2);
            initSearchHistory();
        }
    }

    private void initSearchHistory() {
        ArrayList<SearchHistory> searchHistory = this.searchPresenter.getSearchHistory();
        List<String> historyList = new ArrayList<>();
        for (SearchHistory history : searchHistory) {
            historyList.add(history.searchKeyWords);
        }
        Collections.reverse(historyList);
        tv_history.setViews(historyList, new FlowLayout.OnItemClickListener() {
            public void onItemClick(String content) {
                etSearch.setText(content);
                if (Hawk.get(HawkConfig.FAST_SEARCH_MODE, false)) {
                    Bundle bundle = new Bundle();
                    bundle.putString("title", content);
                    refreshSearchHistory(content);
                    jumpActivity(FastSearchActivity.class, bundle);
                } else {
                    search(content);
                    //etSearch.setSelection(etSearch.getText().length());
                }
            }
        });
    }

    private void initViewModel() {
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        searchPresenter = new SearchPresenter();
    }

    /**
     * 拼音联想
     */
    private void loadRec(String key) {
        OkGo.get("https://tv.aiseet.atianqi.com/i-tvbin/qtv_video/search/get_search_smart_box")
                .params("format", "json")
                .params("page_num", 0)
                .params("page_size", 50) //随便改
                .params("key", key)
                .execute(new AbsCallback() {
                    @Override
                    public void onSuccess(Response response) {
                        try {
                            ArrayList hots = new ArrayList<>();
                            String result = (String) response.body();
                            Gson gson = new Gson();
                            JsonElement json = gson.fromJson(result, JsonElement.class);
                            JsonArray groupDataArr = json.getAsJsonObject()
                                    .get("data").getAsJsonObject()
                                    .get("search_data").getAsJsonObject()
                                    .get("vecGroupData").getAsJsonArray()
                                    .get(0).getAsJsonObject()
                                    .get("group_data").getAsJsonArray();
                            for (JsonElement groupDataElement : groupDataArr) {
                                JsonObject groupData = groupDataElement.getAsJsonObject();
                                String keywordTxt = groupData.getAsJsonObject("dtReportInfo")
                                        .getAsJsonObject("reportData")
                                        .get("keyword_txt").getAsString();
                                hots.add(keywordTxt.trim());
                            }
                            tHotSearchText.setText("猜你想搜");
                            wordAdapter.setNewData(hots);
                            mGridViewWord.smoothScrollToPosition(0);
                        } catch (Throwable th) {
                            th.printStackTrace();
                        }
                    }

                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        return response.body().string();
                    }
                });
    }

    private void initData() {
        showSuccess();
        mGridView.setVisibility(View.GONE);
        refreshQRCode();
        initCheckedSourcesForSearch();
        // 按钮上写清楚这一轮打多少站（全部 179 站 / 已选 12/179），
        // 否则用户永远不知道「全局搜索」到底搜了几个源
        updateSourceLabel();
        initSearchHistory();
        Intent intent = getIntent();
        if (intent != null && intent.hasExtra("title")) {
            String title = intent.getStringExtra("title");
            showLoading();
            if (Hawk.get(HawkConfig.FAST_SEARCH_MODE, false)) {
                Bundle bundle = new Bundle();
                bundle.putString("title", title);
                refreshSearchHistory(title);
                jumpActivity(FastSearchActivity.class, bundle);
            } else {
                search(title);
            }
        }
        // 加载热词
        if (hots.size() != 0) {
            wordAdapter.setNewData(hots);
            return;
        }
        OkGo.<String>get("https://node.video.qq.com/x/api/hot_search")
                .params("channdlId", "0")
                .params("_", System.currentTimeMillis())
                .execute(new AbsCallback<String>() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        try {
                            JsonObject mapResult = JsonParser.parseString(response.body())
                                    .getAsJsonObject()
                                    .get("data").getAsJsonObject()
                                    .get("mapResult").getAsJsonObject();
                            List<String> emoji;
                            if(Build.VERSION.SDK_INT <= Build.VERSION_CODES.N)
                                emoji = Arrays.asList(" ❶ "," ❷ "," ❸ "," ❹ "," ❺ "," ❻ "," ❼ "," ❽ "," ❾ "," ❿ "," ⑪ "," ⑫ "," ⑬ "," ⑭ "," ⑮ "," ⑯ "," ⑰ "," ⑱ "," ⑲ "," ⑳ ");
                             else
                                emoji = Arrays.asList("\uD83E\uDD47","\uD83E\uDD48","\uD83E\uDD49"," ❹ "," ❺ "," ❻ "," ❼ "," ❽ "," ❾ "," ❿ "," ⑪ "," ⑫ "," ⑬ "," ⑭ "," ⑮ "," ⑯ "," ⑰ "," ⑱ "," ⑲ "," ⑳ ");
                            JsonArray itemList = mapResult.get("0").getAsJsonObject()
                                    .get("listInfo").getAsJsonArray();
                            for (int i = 0; i < 10; i++) {
                                JsonObject obj = itemList.get(i).getAsJsonObject();
                                String hotKey = obj.get("title").getAsString().trim().replaceAll("<|>|《|》|-", "").split(" ")[0];
                                hots.add(emoji.get(i) + "\uFEFF" + hotKey);
                            }
                            wordAdapter.setNewData(hots);
                        } catch (Throwable th) {
                            th.printStackTrace();
                        }
                    }

                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        return response.body().string();
                    }
                });
    }

    private void refreshQRCode() {
        String address = ControlManager.get().getAddress(false);
        tvAddress.setText(String.format("远程搜索使用手机/电脑扫描下面二维码或者直接浏览器访问地址\n%s", address));
        ivQRCode.setImageBitmap(QRCodeGen.generateBitmap(address, 300, 300));
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void server(ServerEvent event) {
        if (event.type == ServerEvent.SERVER_SEARCH) {
            String title = (String) event.obj;
            showLoading();
            if (Hawk.get(HawkConfig.FAST_SEARCH_MODE, false)) {
                Bundle bundle = new Bundle();
                bundle.putString("title", title);
                refreshSearchHistory(title);
                jumpActivity(FastSearchActivity.class, bundle);
            } else {
                search(title);
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_SEARCH_RESULT) {
            try {
                searchData(event.obj == null ? null : (AbsXml) event.obj);
            } catch (Exception e) {
                searchData(null);
            }
        }
    }

    private void initCheckedSourcesForSearch() {
        mCheckSources = SearchHelper.getSourcesForSearch();
    }

    private void search(String title) {
        cancel();
        cancelSearchTimeout();
        showLoading();
        this.searchTitle = title;
        mGridView.setVisibility(View.GONE);
        searchAdapter.setNewData(new ArrayList<>());
        refreshSearchHistory(title);
        searchResult();
    }

    private AtomicInteger allRunCount = new AtomicInteger(0);

    private void searchResult() {
        try {
            sourceViewModel.initExecutor();
        } catch (Throwable th) {
            th.printStackTrace();
        } finally {
            searchAdapter.setNewData(new ArrayList<>());
            allRunCount.set(0);
        }

        // 和手机端一个逻辑：默认打「所有能搜的站」，站点勾选只做减法。
        // 默认源排最前面 —— 它的结果最先回来，用户不用等半分钟才看到第一屏。
        // 以前这里会看 sKey（上次单选的那个站），选过一次就只搜那一个，
        // 于是「全站搜索」变得名不副实。
        List<SourceBean> searchRequestList = new ArrayList<>();
        SourceBean home = ApiConfig.get().getHomeSourceBean();
        if (home != null && home.isSearchable()) searchRequestList.add(home);
        for (SourceBean b : ApiConfig.get().getSourceBeanList()) {
            if (b == null || b == home || !b.isSearchable()) continue;
            searchRequestList.add(b);
        }

        ArrayList<String> siteKey = new ArrayList<>();
        for (SourceBean bean : searchRequestList) {
            if (!bean.isSearchable()) {
                continue;
            }
            if (mCheckSources != null && !mCheckSources.isEmpty()
                    && !mCheckSources.containsKey(bean.getKey())) {
                continue;
            }
            siteKey.add(bean.getKey());
            allRunCount.incrementAndGet();
        }
        if (siteKey.size() <= 0) {
            Toast.makeText(mContext, getString(R.string.search_site), Toast.LENGTH_SHORT).show();
            //showEmpty();
            return;
        }

        // 全源搜索时站点很多（默认源 179 个），5 线程排队跑，界面没有进度的话
        // 用户只看得到「当前源」先返回的那一批，就会以为"只搜了当前源"。
        // 把 总数 / 已完成 打到筛选按钮上，一眼能看出是在搜全部源。
        searchTotal = siteKey.size();
        searchDone = 0;
        filterBtn.setText("搜索中 0/" + searchTotal);
        startSearchTimeout();

        for (String key : siteKey) {
            sourceViewModel.execute(new Runnable() {
                @Override
                public void run() {
                    sourceViewModel.getSearch(key, searchTitle);
                }
            });
        }
    }

    /** 兜底收尾：某些站的 spider 会挂住不回包，没有超时就会永远转圈 */
    private void startSearchTimeout() {
        cancelSearchTimeout();
        timeoutRunnable = new Runnable() {
            @Override
            public void run() {
                timeoutRunnable = null;
                if (allRunCount.get() <= 0) return;
                allRunCount.set(0);
                cancel();
                filterBtn.setText(filterBtnBase);
                // 已经出的结果留着，没出的不再等：宁可少几个源，也不能一直转圈
                if (searchAdapter.getData().size() > 0) {
                    showSuccess();
                } else {
                    showEmpty();
                }
            }
        };
        timeoutHandler.postDelayed(timeoutRunnable, SEARCH_TIMEOUT_MS);
    }

    /**
     * 又出了新结果 → 把兜底计时重置一次。
     * <p>全站搜索有上百个站，慢站一个接一个地回，凭什么 35 秒就判死刑？
     * 只有「一直一声不吭」才说明搜索真卡死了 —— 有结果就说明还活着，继续等。
     * 这样 35 秒不再是「搜不完就砍掉」，而是「彻底没动静才收手」。</p>
     */
    private void resetSearchTimeout() {
        if (timeoutRunnable == null) return;   // 已经超时收尾了，别再启动
        startSearchTimeout();
    }

    private void cancelSearchTimeout() {
        if (timeoutRunnable != null) {
            timeoutHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }

    private void searchData(AbsXml absXml) {
        if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
            List<Movie.Video> data = new ArrayList<>();
            for (Movie.Video video : absXml.movie.videoList) {
                data.add(video);
            }
            if (searchAdapter.getData().size() > 0) {
                searchAdapter.addData(data);
            } else {
                showSuccess();
                searchAdapter.setNewData(data);
                tv_history.setVisibility(View.GONE);
                searchTips.setVisibility(View.GONE);
                llWord.setVisibility(View.GONE);
                mGridView.setVisibility(View.VISIBLE);
            }
        }

        int count = allRunCount.decrementAndGet();
        searchDone++;
        if (count > 0 && searchTotal > 0) {
            filterBtn.setText("搜索中 " + Math.min(searchDone, searchTotal) + "/" + searchTotal);
            // 还有结果在回来 → 兜底计时重置，别把还在慢慢回应的站砍掉
            resetSearchTimeout();
        }
        if (count <= 0) {
            filterBtn.setText(filterBtnBase);
            cancelSearchTimeout();
            if (searchAdapter.getData().size() <= 0) {
                showEmpty();
            }
            cancel();
        }
    }

    private void cancel() {
        OkGo.getInstance().cancelTag("search");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancel();
        cancelSearchTimeout();
        try {
            if (sourceViewModel != null) {
                sourceViewModel.shutdownNow();
                sourceViewModel.destroyExecutor();
                JsLoader.stopAll();
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        EventBus.getDefault().unregister(this);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onInputMsgEvent(InputMsgEvent inputMsgEvent) {
        etSearch.setFocusableInTouchMode(true);
        etSearch.requestFocus();
        etSearch.setText(inputMsgEvent.getText());
        search(inputMsgEvent.getText());
    }
}
