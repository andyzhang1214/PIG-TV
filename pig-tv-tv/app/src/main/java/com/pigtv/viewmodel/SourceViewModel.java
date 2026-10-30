package com.pigtv.viewmodel;

import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.github.catvod.crawler.JsLoader;
import com.github.catvod.crawler.Spider;
import com.pigtv.api.ApiConfig;
import com.pigtv.base.App;
import com.pigtv.bean.AbsJson;
import com.pigtv.bean.AbsSortJson;
import com.pigtv.bean.AbsSortXml;
import com.pigtv.bean.AbsXml;
import com.pigtv.bean.Movie;
import com.pigtv.bean.MovieSort;
import com.pigtv.bean.SourceBean;
import com.pigtv.event.RefreshEvent;
import com.pigtv.util.DefaultConfig;
import com.pigtv.util.HawkConfig;
import com.pigtv.util.LOG;
import com.pigtv.util.MD5;
import com.pigtv.util.thunder.Thunder;
import com.pigtv.util.urlhttp.OkHttpUtil;
import com.pigtv.util.FileUtils;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.orhanobut.hawk.Hawk;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.DomDriver;

import org.apache.commons.lang3.BooleanUtils;
import org.greenrobot.eventbus.EventBus;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * @author pj567
 * @date :2020/12/18
 * @description:
 */
public class SourceViewModel extends ViewModel {
    public MutableLiveData<AbsSortXml> sortResult;
    public MutableLiveData<AbsXml> listResult;
    public MutableLiveData<AbsXml> searchResult;
    public MutableLiveData<AbsXml> quickSearchResult;
    public MutableLiveData<AbsXml> detailResult;
    public MutableLiveData<JSONObject> playResult;
    private ExecutorService searchExecutorService;
    public Gson gson;

    public void initExecutor() {
        if (searchExecutorService != null) {
            searchExecutorService.shutdownNow();
            searchExecutorService = null;
            JsLoader.stopAll();
        }
        // 全源搜索要排队跑几十上百个站，5 个线程太慢（用户会以为只有当前源有结果）。
        // 12 是折中：以前不敢更高，是因为同时下载多个 spider jar 容易把 TV 盒子拖死；
        // 现在 JarLoader 里按组件地址加了锁 —— 同一个 jar 只下一次，
        // 真正并行的下载最多 7 个（实测 128 个可搜站只用到 7 个不同组件），不会失控。
        searchExecutorService = Executors.newFixedThreadPool(12);
    }

    /**
     * 单站搜索的旁路池：某些站的 spider 会挂住不回包。
     * <p>没有超时的话，这个线程会被永久占住 —— 12 个线程占满，
     * 后面一百多个站一个都跑不了，表现就是「搜了半天只有几个站有结果」。
     * 超时后任务被放弃，但线程还在跑（网络调用不可中断），由固定上限兜住。</p>
     */
    private static final ExecutorService SEARCH_TASK_POOL = Executors.newFixedThreadPool(16);
    /** 单站搜索超时（秒） */
    private static final int SITE_SEARCH_TIMEOUT_S = 12;

    /**
     * 搜一个站，超时就放弃。
     *
     * @param quick true = 快速搜索（只取前几条，爬虫自己会限）
     * @return 搜索结果 JSON；超时/异常返回空串
     */
    private String searchWithTimeout(SourceBean sourceBean, String wd) {
        return searchWithTimeout(sourceBean, wd, false);
    }

    private String searchWithTimeout(SourceBean sourceBean, String wd, boolean quick) {
        try {
            final boolean q = quick;
            Future<String> f = SEARCH_TASK_POOL.submit(new Callable<String>() {
                @Override
                public String call() throws Exception {
                    Spider sp = ApiConfig.get().getCSP(sourceBean);
                    return sp.searchContent(wd, q);
                }
            });
            return f.get(SITE_SEARCH_TIMEOUT_S, TimeUnit.SECONDS);
        } catch (Throwable th) {
            return "";
        }
    }

    public void execute(Runnable runnable) {
        if (searchExecutorService != null) {
            searchExecutorService.execute(runnable);
        }
    }

    public List<Runnable> shutdownNow() {
        return searchExecutorService == null ? new ArrayList<>() : searchExecutorService.shutdownNow();
    }

    public void destroyExecutor() {
        if (searchExecutorService != null) {
            searchExecutorService = null;
        }
    }

    public SourceViewModel() {
        sortResult = new MutableLiveData<>();
        listResult = new MutableLiveData<>();
        searchResult = new MutableLiveData<>();
        quickSearchResult = new MutableLiveData<>();
        detailResult = new MutableLiveData<>();
        playResult = new MutableLiveData<>();
        gson=new Gson();
    }

    public static final ExecutorService spThreadPool = Executors.newSingleThreadExecutor();

    //homeContent缓存，最多存储5个sourceKey的AbsSortXml对象
    private static final Map<String, AbsSortXml> sortCache = new LinkedHashMap<String, AbsSortXml>(5, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Entry<String, AbsSortXml> eldest) {
            return size() > 5;
        }
    };

    /* ==================================================================
       请求代数（generation）
       ------------------------------------------------------------------
       sortResult 只有**一条** LiveData，谁在等结果（PhoneBridge.sortCb）
       和结果是谁请求的，结构上对不上号。切线路时会串台：

         1. 旧站点 A 的 getSort 还在飞（spider 慢，或 20 秒超时兜底）；
         2. 用户切到站点 B，PhoneBridge 清掉 sortCb、重新 config()；
         3. A 的结果这时才回来 → 落到 B 的 sortCb 上 → 前端拿到的
            「新站点配置」其实是 A 的，左上角站名不变、首页还是 A 的内容；
         4. B 真正结果回来时 sortCb 已经被消费掉 → 直接丢弃，
            于是 B 的首页永远是空的。

       做法：每次 getSort 领一个递增的代数，只有当前代数的结果才准
       postValue / 进缓存。cancelSort() 让所有在飞的请求全部作废。
       ================================================================== */
    private static volatile int sortGen = 0;

    /** 换片源 / 换接口：作废在飞的取分类请求，并丢掉旧站点的缓存 */
    public void cancelSort() {
        sortGen++;
        synchronized (sortCache) {
            sortCache.clear();
        }
    }

    private void postSort(AbsSortXml xml, int gen) {
        if (gen != sortGen) {
            LOG.i("echo--getSort-drop-stale--" + gen + "/" + sortGen);
            return;
        }
        sortResult.postValue(xml);
    }

    private void cacheSort(String key, AbsSortXml xml, int gen) {
        if (gen != sortGen) return;
        synchronized (sortCache) {
            sortCache.put(key, xml);
        }
    }

    // homeContent
    public void getSort(final String sourceKey) {
        LOG.i("echo--getSort-start");
        if (sourceKey == null) {
            sortResult.postValue(null);
            return;
        }
        // 领代数：从这一行开始，之前所有 getSort 的回包都算过期
        final int gen = ++sortGen;

        // 优先检查缓存
        AbsSortXml cached = sortCache.get(sourceKey);
        if (cached != null) {
            LOG.i("echo--getSort-cached--"+sourceKey);
            int homeRec = Hawk.get(HawkConfig.HOME_REC, 0);
            boolean shouldUseCache = (homeRec != 1) || (cached.videoList != null && !cached.videoList.isEmpty());
            if (shouldUseCache) {
                postSort(cached, gen);
                return;
            }
        }

        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        if (sourceBean == null) {
            // 换了接口之后旧 sort id 还可能被一个残留请求带进来，别让 NPE 冒到 PhoneBridge
            com.pigtv.api.SpiderDiag.set("这个站点在当前接口里不存在（可能刚换过线路）");
            postSort(null, gen);
            return;
        }
        com.pigtv.api.SpiderDiag.clear();          // 新一轮请求，上一轮的错误作废
        final int type = sourceBean.getType();
        if (type == 3) {
            Runnable waitResponse = new Runnable() {
                @Override
                public void run() {
                    ExecutorService executor = Executors.newSingleThreadExecutor();
                    Future<String> future = executor.submit(new Callable<String>() {
                        @Override
                        public String call() throws Exception {
                            Spider sp = ApiConfig.get().getCSP(sourceBean);
                            return sp.homeContent(true);
                        }
                    });
                    String sortJson = null;
                    try {
                        sortJson = future.get(20, TimeUnit.SECONDS);
                    } catch (TimeoutException e) {
                        e.printStackTrace();
                        future.cancel(true);
                        com.pigtv.api.SpiderDiag.set("取分类超时（20 秒），站点没有响应");
                    } catch (InterruptedException | ExecutionException e) {
                        e.printStackTrace();
                        // 这里以前只有 printStackTrace：站点抛的 NoClassDefFoundError 全被吞了，
                        // 用户侧只看到「接口没有返回任何分类」。
                        com.pigtv.api.SpiderDiag.fail("取分类", e instanceof ExecutionException ? e.getCause() : e);
                    } finally {
                        if (sortJson != null) {
                            if (sortJson.trim().isEmpty() || "{}".equals(sortJson.trim()))
                                com.pigtv.api.SpiderDiag.set("站点返回了空内容（可能该站在维护或已停用）");
                            // 首页有内容、影视页却「没有分类」时，先看这一行：
                            // 站点到底给没给 class（有的接口只给 list，或者字段名写成 classes）
                            LOG.i("echo--getSort-json len=" + sortJson.length()
                                    + " hasClass=" + sortJson.contains("\"class\"")
                                    + " hasClasses=" + sortJson.contains("\"classes\"")
                                    + " head=" + sortJson.substring(0, Math.min(200, sortJson.length())));
                            final AbsSortXml sortXml = sortJson(sortResult, sortJson);
                            LOG.i("echo--getSort-parsed " + (sortXml == null ? "xml=null"
                                    : ("sorts=" + (sortXml.classes == null || sortXml.classes.sortList == null
                                    ? "null" : String.valueOf(sortXml.classes.sortList.size())))
                                    + " list=" + (sortXml.videoList == null ? 0 : sortXml.videoList.size())));
                            if (sortXml == null)
                                com.pigtv.api.SpiderDiag.set("站点返回的分类解析不了（不是标准 JSON）");
                            if (sortXml != null && Hawk.get(HawkConfig.HOME_REC, 0) == 1) {
                                AbsXml absXml = json(null, sortJson, sourceBean.getKey());
                                if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                                    sortXml.videoList = absXml.movie.videoList;
                                    postSort(sortXml, gen);
                                    cacheSort(sourceKey, sortXml, gen);
                                } else {
                                    getHomeRecList(sourceBean, null, new HomeRecCallback() {
                                        @Override
                                        public void done(List<Movie.Video> videos) {
                                            sortXml.videoList = videos;
                                            postSort(sortXml, gen);
                                            cacheSort(sourceKey, sortXml, gen);
                                        }
                                    });
                                }
                            } else {
                                postSort(sortXml, gen);
                                cacheSort(sourceKey, sortXml, gen);
                            }
                        } else {
                            if (com.pigtv.api.SpiderDiag.get() == null)
                                com.pigtv.api.SpiderDiag.set("站点没有返回分类（首页接口是空的）");
                            postSort(null, gen);
                        }
                        try {
                            executor.shutdown();
                        } catch (Throwable th) {
                            th.printStackTrace();
                        }
                    }
                }
            };
            spThreadPool.execute(waitResponse);
        } else if (type == 0 || type == 1) {
            OkGo.<String>get(sourceBean.getApi())
                    .tag(sourceBean.getKey() + "_sort")
                    .execute(new AbsCallback<String>() {
                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            AbsSortXml sortXml = null;
                            if (type == 0) {
                                String xml = response.body();
                                sortXml = sortXml(sortResult, xml);
                            } else if (type == 1) {
                                String json = response.body();
                                sortXml = sortJson(sortResult, json);
                            }
                            if (sortXml != null && Hawk.get(HawkConfig.HOME_REC, 0) == 1 && sortXml.list != null && sortXml.list.videoList != null && sortXml.list.videoList.size() > 0) {
                                ArrayList<String> ids = new ArrayList<>();
                                for (Movie.Video vod : sortXml.list.videoList) {
                                    ids.add(vod.id);
                                }
                                final AbsSortXml finalSortXml = sortXml;
                                getHomeRecList(sourceBean, ids, new HomeRecCallback() {
                                    @Override
                                    public void done(List<Movie.Video> videos) {
                                        finalSortXml.videoList = videos;
                                        postSort(finalSortXml, gen);
                                        cacheSort(sourceKey, finalSortXml, gen);
                                    }
                                });
                            } else {
                                postSort(sortXml, gen);
                                cacheSort(sourceKey, sortXml, gen);
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            Throwable ex = response.getException();
                            com.pigtv.api.SpiderDiag.set("取分类失败："
                                    + (ex == null ? "网络错误" : ex.getClass().getSimpleName()
                                    + (ex.getMessage() == null ? "" : " " + ex.getMessage())));
                            postSort(null, gen);
                        }
                    });
        } else if (type == 4) {
            String extend=sourceBean.getExt();
            extend=getFixUrl(extend);
            OkGo.<String>get(sourceBean.getApi())
                    .tag(sourceBean.getKey() + "_sort")
                    .params("filter", "true")
                    .params("extend", extend)
                    .execute(new AbsCallback<String>() {
                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            String sortJson = response.body();
                            if (sortJson != null) {
                                final AbsSortXml sortXml = sortJson(sortResult, sortJson);
                                if (sortXml != null && Hawk.get(HawkConfig.HOME_REC, 0) == 1) {
                                    AbsXml absXml = json(null, sortJson, sourceBean.getKey());
                                    if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                                        sortXml.videoList = absXml.movie.videoList;
                                        postSort(sortXml, gen);
                                        cacheSort(sourceKey, sortXml, gen);
                                    } else {
                                        getHomeRecList(sourceBean, null, new HomeRecCallback() {
                                            @Override
                                            public void done(List<Movie.Video> videos) {
                                                sortXml.videoList = videos;
                                                postSort(sortXml, gen);
                                                cacheSort(sourceKey, sortXml, gen);
                                            }
                                        });
                                    }
                                } else {
                                    postSort(sortXml, gen);
                                    cacheSort(sourceKey, sortXml, gen);
                                }
                            } else {
                                postSort(null, gen);
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            postSort(null, gen);
                        }
                    });
        } else {
            postSort(null, gen);
        }
    }

    /* ==================================================================
       分类列表请求：两条通道
       ------------------------------------------------------------------
       老通道（cb == null）：结果统一 post 到 listResult，只适用于「同一时刻
       只有一个请求在飞」的场景（分类页网格、分页加载）。

       新通道（cb != null）：结果直接回给调用方。首页 v8 要同时为 4 个分类
       各拉一页，如果还共用 listResult，四行数据必然串台——A 行的结果被画到
       B 行上，B 行永远空着。回调式让「谁请求的、结果给谁」变成一对一。
       ================================================================== */
    public interface ListCallback {
        void done(AbsXml absXml);
    }

    private void deliverList(AbsXml absXml, ListCallback cb) {
        if (cb != null) {
            cb.done(absXml);
        } else {
            listResult.postValue(absXml);
        }
    }

    // categoryContent
    public void getList(MovieSort.SortData sortData, int page) {
        getList(sortData, page, null);
    }

    public void getList(MovieSort.SortData sortData, int page, ListCallback cb) {
        SourceBean homeSourceBean = ApiConfig.get().getHomeSourceBean();
        int type = homeSourceBean.getType();
        if (type == 3) {
            spThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        Spider sp = ApiConfig.get().getCSP(homeSourceBean);
                        deliverList(json(null, sp.categoryContent(sortData.id, page + "", true, sortData.filterSelect), homeSourceBean.getKey()), cb);
                    } catch (Throwable th) {
                        th.printStackTrace();
                        deliverList(null, cb);
                    }
                }
            });
        } else if (type == 0 || type == 1) {
            OkGo.<String>get(homeSourceBean.getApi())
                    .tag(homeSourceBean.getApi())
                    .params("ac", type == 0 ? "videolist" : "detail")
                    .params("t", sortData.id)
                    .params("pg", page)
                    .params(sortData.filterSelect)
                    .params("f", (sortData.filterSelect == null || sortData.filterSelect.size() <= 0) ? "" : new JSONObject(sortData.filterSelect).toString())
                    .execute(new AbsCallback<String>() {

                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            if (type == 0) {
                                String xml = response.body();
                                deliverList(xml(null, xml, homeSourceBean.getKey()), cb);
                            } else {
                                String json = response.body();
                                deliverList(json(null, json, homeSourceBean.getKey()), cb);
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            deliverList(null, cb);
                        }
                    });
        } else if (type == 4) {
            String ext = "";
            if (sortData.filterSelect != null && sortData.filterSelect.size() > 0) {
                try {
                    LOG.i(new JSONObject(sortData.filterSelect).toString());
                    ext = Base64.encodeToString(new JSONObject(sortData.filterSelect).toString().getBytes("UTF-8"), Base64.DEFAULT | Base64.NO_WRAP);
                    LOG.i(ext);
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
            }
            OkGo.<String>get(homeSourceBean.getApi())
                    .tag(homeSourceBean.getApi())
                    .params("ac", "detail")
                    .params("filter", "true")
                    .params("t", sortData.id)
                    .params("pg", page)
                    .params("ext", ext)
                    .execute(new AbsCallback<String>() {
                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            String json = response.body();
                            LOG.i(json);
                            deliverList(json(null, json, homeSourceBean.getKey()), cb);
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            deliverList(null, cb);
                        }
                    });
        } else {
            deliverList(null, cb);
        }
    }

    interface HomeRecCallback {
        void done(List<Movie.Video> videos);
    }

    //    homeVideoContent
    void getHomeRecList(SourceBean sourceBean, ArrayList<String> ids, HomeRecCallback callback) {
        int type = sourceBean.getType();
        if (type == 3) {
            Runnable waitResponse = new Runnable() {
                @Override
                public void run() {
                    ExecutorService executor = Executors.newSingleThreadExecutor();
                    Future<String> future = executor.submit(new Callable<String>() {
                        @Override
                        public String call() throws Exception {
                            Spider sp = ApiConfig.get().getCSP(sourceBean);
                            return sp.homeVideoContent();
                        }
                    });
                    String sortJson = null;
                    try {
                        sortJson = future.get(15, TimeUnit.SECONDS);
                    } catch (TimeoutException e) {
                        e.printStackTrace();
                        future.cancel(true);
                    } catch (InterruptedException | ExecutionException e) {
                        e.printStackTrace();
                    } finally {
                        if (sortJson != null) {
                            AbsXml absXml = json(null, sortJson, sourceBean.getKey());
                            if (absXml != null && absXml.movie != null && absXml.movie.videoList != null) {
                                callback.done(absXml.movie.videoList);
                            } else {
                                callback.done(null);
                            }
                        } else {
                            callback.done(null);
                        }
                        try {
                            executor.shutdown();
                        } catch (Throwable th) {
                            th.printStackTrace();
                        }
                    }
                }
            };
            spThreadPool.execute(waitResponse);
        } else if (type == 0 || type == 1) {
            OkGo.<String>get(sourceBean.getApi())
                    .tag("detail")
                    .params("ac", sourceBean.getType() == 0 ? "videolist" : "detail")
                    .params("ids", TextUtils.join(",", ids))
                    .execute(new AbsCallback<String>() {

                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            AbsXml absXml;
                            if (sourceBean.getType() == 0) {
                                String xml = response.body();
                                absXml = xml(null, xml, sourceBean.getKey());
                            } else {
                                String json = response.body();
                                absXml = json(null, json, sourceBean.getKey());
                            }
                            if (absXml != null && absXml.movie != null && absXml.movie.videoList != null) {
                                callback.done(absXml.movie.videoList);
                            } else {
                                callback.done(null);
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            callback.done(null);
                        }
                    });
        } else {
            callback.done(null);
        }
    }

    // detailContent
    public void getDetail(String sourceKey, String urlid) {

        if (urlid.startsWith("push://") && ApiConfig.get().getSource("push_agent") != null) {
            String pushUrl = urlid.substring(7);
            if (pushUrl.startsWith("b64:")) {
                try {
                    pushUrl = new String(Base64.decode(pushUrl.substring(4), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP), "UTF-8");
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
            } else {
                pushUrl = URLDecoder.decode(pushUrl);
            }
            sourceKey = "push_agent";
            urlid = pushUrl;
        }
        String id = urlid;
    
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        if (sourceBean == null) {
            detailResult.postValue(null);
            Log.e("sourceBean", "get sourceBean got null, this should not be happended, maybe apiconfig get from http failed and use cache, sourceKey is " + sourceKey);
            return;
        }
        int type = sourceBean.getType();
        if (type == 3) {
            spThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    ExecutorService executor = Executors.newSingleThreadExecutor();
                    Future<String> future = executor.submit(new Callable<String>() {
                        @Override
                        public String call() {
                            Spider sp = ApiConfig.get().getCSP(sourceBean);
                            List<String> ids = new ArrayList<>();
                            ids.add(id);
                            try {
                                return sp.detailContent(ids);
                            } catch (Exception e) {
                                LOG.i("echo--getDetail--error: " + e.getMessage());
                                return "";
                            }
                        }
                    });

                    String json = null;
                    try {
                        json = future.get(15, TimeUnit.SECONDS);
                        LOG.i("echo--getDetail--result:" + json);
                    } catch (TimeoutException e) {
                        LOG.i("echo--getDetail--timeout");
                        future.cancel(true);
                    } catch (Exception e) {
                        LOG.i("echo--getDetail--error: " + e.getMessage());
                    } finally {
                        json(detailResult, json, sourceBean.getKey());
                        executor.shutdown();
                    }
                }
            });
        } else if (type == 0 || type == 1|| type == 4) {
            String extend=sourceBean.getExt();
            extend=getFixUrl(extend);
            OkGo.<String>get(sourceBean.getApi())
                    .tag("detail")
                    .params("ac", type == 0 ? "videolist" : "detail")
                    .params("ids", id)
                    .params("extend", extend)
                    .execute(new AbsCallback<String>() {

                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            if (type == 0) {
                                String xml = response.body();
                                xml(detailResult, xml, sourceBean.getKey());
                            } else {
                                String json = response.body();
                                LOG.i(json);
                                json(detailResult, json, sourceBean.getKey());
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            json(detailResult, "", sourceBean.getKey());
                        }
                    });
        } else {
            detailResult.postValue(null);
        }
    }

    // searchContent
    public void getSearch(String sourceKey, String wd) {
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        // 换源/刷新配置后站点可能已经不在列表里，这里必须判空：
        // 原来直接 getType() 会抛 NPE，异常抛在搜索线程里，一趟搜索的进度计数就再也减不到 0（永远转圈）
        if (sourceBean == null) {
            json(searchResult, "", sourceKey);
            return;
        }
        int type = sourceBean.getType();
        if (type == 3) {
            try {
                /* 单站超时：有些站的 spider 会挂住不回包。
                   没有超时的话这个线程会被永久占住 —— 12 个线程占满，
                   后面一百多个站一个都跑不了（表现就是「搜了半天只有几个站有结果」）。 */
                String search = searchWithTimeout(sourceBean, wd);
                if (!TextUtils.isEmpty(search)) {
                    json(searchResult, search, sourceBean.getKey());
                } else {
                    json(searchResult, "", sourceBean.getKey());
                }
            } catch (Throwable th) {
                th.printStackTrace();
                json(searchResult, "", sourceBean.getKey());
            }
        } else if (type == 0 || type == 1) {
            OkGo.<String>get(sourceBean.getApi())
                    .params("wd", wd)
                    .params(type == 1 ? "ac" : null, type == 1 ? "detail" : null)
                    .tag("search")
                    .execute(new AbsCallback<String>() {
                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            if (type == 0) {
                                String xml = response.body();
                                xml(searchResult, xml, sourceBean.getKey());
                            } else {
                                String json = response.body();
                                json(searchResult, json, sourceBean.getKey());
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            // searchResult.postValue(null);
                            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, null));
                        }
                    });
        } else if (type == 4) {
            OkGo.<String>get(sourceBean.getApi())
                    .params("wd", wd)
                    .params("ac", "detail")
                    .params("quick", "false")
                    .tag("search")
                    .execute(new AbsCallback<String>() {
                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            String json = response.body();
                            LOG.i(json);
                            json(searchResult, json, sourceBean.getKey());
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            // searchResult.postValue(null);
                            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, null));
                        }
                    });
        } else {
            searchResult.postValue(null);
        }
    }

    // searchContent
    public void getQuickSearch(String sourceKey, String wd) {
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        if (sourceBean == null) {
            return;
        }
        int type = sourceBean.getType();
        if (type == 3) {
            try {
                /* 同样要超时：快速搜索是边打字边搜，一个挂住的站
                   会把搜索线程占住，后面的站全都排不上。 */
                json(quickSearchResult, searchWithTimeout(sourceBean, wd, true), sourceBean.getKey());
            } catch (Throwable th) {
                th.printStackTrace();
            }
        } else if (type == 0 || type == 1) {
            OkGo.<String>get(sourceBean.getApi())
                    .params("wd", wd)
                    .params(type == 1 ? "ac" : null, type == 1 ? "detail" : null)
                    .tag("quick_search")
                    .execute(new AbsCallback<String>() {
                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            if (type == 0) {
                                String xml = response.body();
                                xml(quickSearchResult, xml, sourceBean.getKey());
                            } else {
                                String json = response.body();
                                json(quickSearchResult, json, sourceBean.getKey());
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            // quickSearchResult.postValue(null);
                            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_RESULT, null));
                        }
                    });
        } else if (type == 4) {
            OkGo.<String>get(sourceBean.getApi())
                    .params("wd", wd)
                    .params("ac", "detail")
                    .params("quick", "true")
                    .tag("search")
                    .execute(new AbsCallback<String>() {
                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            String json = response.body();
                            LOG.i(json);
                            json(quickSearchResult, json, sourceBean.getKey());
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            // searchResult.postValue(null);
                            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, null));
                        }
                    });
        } else {
            quickSearchResult.postValue(null);
        }
    }

    // playerContent
    //开销会不会太大了 参考 FongMi 写法优化 获取播放地址代码
    public ExecutorService threadPoolGetPlay = null;

    public void getPlay(String sourceKey, String playFlag, String progressKey, String url, String subtitleKey) {
        if (threadPoolGetPlay != null) threadPoolGetPlay.shutdownNow();
        threadPoolGetPlay = Executors.newFixedThreadPool(2);
        Callable<JSONObject> callable = () -> {
            if (Thread.currentThread().isInterrupted()) return null;
            SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
            int type = sourceBean.getType();
            JSONObject result = null;
            if (type == 3) {
                Spider sp = ApiConfig.get().getCSP(sourceBean);
                String json = sp.playerContent(playFlag, url, ApiConfig.get().getVipParseFlags());
                result = new JSONObject(json);
            } else if (type == 0 || type == 1) {
                result = new JSONObject();
                String playUrl = sourceBean.getPlayerUrl().trim();
                boolean parse = DefaultConfig.isVideoFormat(url) && playUrl.isEmpty();
                result.put("parse", BooleanUtils.toInteger(!parse));
                result.put("url", url);
                result.put("playUrl", playUrl);
                //直接就有
            } else if (type == 4) {
                String extend=sourceBean.getExt();
                extend=getFixUrl(extend);
                okhttp3.Response response = OkGo.<String>get(sourceBean.getApi()).params("play", url).params("flag", playFlag).params("extend", extend).tag("play").execute();
                String json = response.body().string();
                result = new JSONObject(json);
            }
            if (result != null) {
                result.put("key", url);
                result.put("proKey", progressKey);
                result.put("subtKey", subtitleKey);
                if (!result.has("flag"))
                    result.put("flag", playFlag);
            }
            return result;
        };
        threadPoolGetPlay.execute(() -> {
            Future<JSONObject> future = threadPoolGetPlay.submit(callable);
            try {
                JSONObject jsonObject = future.get(15, TimeUnit.SECONDS);
                playResult.postValue(jsonObject);
            } catch (Throwable e) {
                e.printStackTrace();
                playResult.postValue(null);
            }
        });
    }
    private static final ConcurrentHashMap<String, String> extendCache = new ConcurrentHashMap<>();
    private String getFixUrl(final String extend) {
        if(!extend.startsWith("http"))return extend;
        final String key = MD5.string2MD5(extend);
        if (extendCache.containsKey(key)) {
            LOG.i("echo-getFixUrl Cache");
            return extendCache.get(key);
        }
        LOG.i("echo-getFixUrl load");
        Future<String> future = spThreadPool.submit(new Callable<String>() {
            @Override
            public String call() {
                String result = extend;
                if (extend.startsWith("http://127.0.0.1")) {
                    String path = extend.replaceAll("^http.+/file/", FileUtils.getRootPath() + "/");
                    path = path.replaceAll("localhost/", "/");
                    result = FileUtils.readFileToString(path, "UTF-8");
                    result = tryMinifyJson(result);
                    extendCache.putIfAbsent(key, result);
                } else if (extend.startsWith("http")) {
                    result = OkHttpUtil.string(extend, null);
                    if (!result.isEmpty()) {
                        result = tryMinifyJson(result);
                        extendCache.putIfAbsent(key, result);
                    }
                }
                return result;
            }
        });

        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            te.printStackTrace();
            future.cancel(true);
            return extend;
        } catch (Exception e) {
            e.printStackTrace();
            return extend;
        }
    }

    private String tryMinifyJson(String raw) {
        try {
            raw = raw.trim();
            JsonElement jsonElement = JsonParser.parseString(raw);
            return gson.toJson(jsonElement);
        } catch (Exception e) {
            return raw;
        }

    }

    private MovieSort.SortFilter getSortFilter(JsonObject obj) {
        String key = obj.get("key").getAsString();
        String name = obj.get("name").getAsString();
        JsonArray kv = obj.getAsJsonArray("value");
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (JsonElement ele : kv) {
            JsonObject ele_obj = ele.getAsJsonObject();
            String values_key=ele_obj.has("n")?ele_obj.get("n").getAsString():"";
            String values_value=ele_obj.has("v")?ele_obj.get("v").getAsString():"";
            values.put(values_key, values_value);
        }
        MovieSort.SortFilter filter = new MovieSort.SortFilter();
        filter.key = key;
        filter.name = name;
        filter.values = values;
        return filter;
    }

    private AbsSortXml sortJson(MutableLiveData<AbsSortXml> result, String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            // 不要用 new TypeToken<AbsSortJson>(){}.getType()：
            // 匿名子类的泛型签名会被 R8 裁掉，直接抛 IllegalStateException，
            // 分类就整个解析成 null。给具体 Class 就行。
            AbsSortJson sortJson = gson.fromJson(obj, AbsSortJson.class);
            AbsSortXml data = sortJson.toAbsSortXml();
            try {
                if (obj.has("filters")) {
                    LinkedHashMap<String, ArrayList<MovieSort.SortFilter>> sortFilters = new LinkedHashMap<>();
                    JsonObject filters = obj.getAsJsonObject("filters");
                    for (String key : filters.keySet()) {
                        ArrayList<MovieSort.SortFilter> sortFilter = new ArrayList<>();
                        JsonElement one = filters.get(key);
                        if (one.isJsonObject()) {
                            sortFilter.add(getSortFilter(one.getAsJsonObject()));
                        } else {
                            for (JsonElement ele : one.getAsJsonArray()) {
                                sortFilter.add(getSortFilter(ele.getAsJsonObject()));
                            }
                        }
                        sortFilters.put(key, sortFilter);
                    }
                    for (MovieSort.SortData sort : data.classes.sortList) {
                        if (sortFilters.containsKey(sort.id) && sortFilters.get(sort.id) != null) {
                            sort.filters = sortFilters.get(sort.id);
                        }
                    }
                }
            } catch (Throwable th) {

            }
            return data;
        } catch (Exception e) {
            // 别静默吞：解析失败时首页会「有推荐没分类」，影视页直接空态，
            // 不打印根本看不出是哪一步挂的
            LOG.e("echo--getSort-parse-fail " + e);
            e.printStackTrace();
            return null;
        }
    }

    private AbsSortXml sortXml(MutableLiveData<AbsSortXml> result, String xml) {
        try {
            XStream xstream = new XStream(new DomDriver());//创建Xstram对象
            xstream.autodetectAnnotations(true);
            xstream.processAnnotations(AbsSortXml.class);
            xstream.ignoreUnknownElements();
            xstream.allowTypes(new Class[]{AbsSortXml.class});
            AbsSortXml data = (AbsSortXml) xstream.fromXML(xml);
            for (MovieSort.SortData sort : data.classes.sortList) {
                if (sort.filters == null) {
                    sort.filters = new ArrayList<>();
                }
            }
            return data;
        } catch (Exception e) {
            return null;
        }
    }

    private void absXml(AbsXml data, String sourceKey) {
        if (data.movie != null && data.movie.videoList != null) {
            for (Movie.Video video : data.movie.videoList) {
                if (video.urlBean != null && video.urlBean.infoList != null) {
                    for (Movie.Video.UrlBean.UrlInfo urlInfo : video.urlBean.infoList) {
                        String[] str = null;
                        if (urlInfo.urls.contains("#")) {
                            str = urlInfo.urls.split("#");
                        } else {
                            str = new String[]{urlInfo.urls};
                        }
                        List<Movie.Video.UrlBean.UrlInfo.InfoBean> infoBeanList = new ArrayList<>();
//                        for (String s : str) {
//                            if (s.contains("$")) {
//                                String[] ss = s.split("\\$");
//                                if (ss.length >= 2) {
//                                    infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean(ss[0], ss[1]));
//                                }
//                                //infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean(s.substring(0, s.indexOf("$")), s.substring(s.indexOf("$") + 1)));
//                            }
//                        }
                        for (String s : str) {
                            String[] ss = s.split("\\$");
                            if (ss.length > 0) {
                                if (ss.length >= 2) {
                                    infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean(ss[0], ss[1]));
                                } else {
                                    infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean((infoBeanList.size() + 1) + "", ss[0]));
                                }
                            }
                        }
                        urlInfo.beanList = infoBeanList;
                    }
                }
                if (video.sourceKey == null)
                    video.sourceKey = sourceKey;
            }
        }
    }

    private AbsXml checkPush(AbsXml data) {
        if (data.movie != null && data.movie.videoList != null && data.movie.videoList.size() > 0) {
            Movie.Video video = data.movie.videoList.get(0);
            if (video != null && video.urlBean != null && video.urlBean.infoList != null && video.urlBean.infoList.size() > 0) {
                for (int i = 0; i < video.urlBean.infoList.size(); i++) {
                    Movie.Video.UrlBean.UrlInfo urlinfo = video.urlBean.infoList.get(i);
                    if (urlinfo != null && urlinfo.beanList != null && !urlinfo.beanList.isEmpty()) {
                        for (Movie.Video.UrlBean.UrlInfo.InfoBean infoBean : urlinfo.beanList) {
                            if (infoBean.url.startsWith("push://")) {
                                String pushUrl = infoBean.url.substring(7);
                                if (pushUrl.startsWith("b64:")) {
                                    try {
                                        pushUrl = new String(Base64.decode(pushUrl.substring(4), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP), "UTF-8");
                                    } catch (UnsupportedEncodingException e) {
                                        e.printStackTrace();
                                    }
                                } else {
                                    pushUrl = URLDecoder.decode(pushUrl);
                                }

                                final AbsXml[] resData = {null};

                                final CountDownLatch countDownLatch = new CountDownLatch(1);
                                ExecutorService threadPool = Executors.newSingleThreadExecutor();
                                String finalPushUrl = pushUrl;
                                threadPool.execute(new Runnable() {
                                    @Override
                                    public void run() {
                                        SourceBean sb = ApiConfig.get().getSource("push_agent");
                                        if (sb == null) {
                                            countDownLatch.countDown();
                                            return;
                                        }
                                        if (sb.getType() == 4) {
                                            OkGo.<String>get(sb.getApi())
                                                    .tag("detail")
                                                    .params("ac", "detail")
                                                    .params("ids", finalPushUrl)
                                                    .execute(new AbsCallback<String>() {
                                                        @Override
                                                        public String convertResponse(okhttp3.Response response) throws Throwable {
                                                            if (response.body() != null) {
                                                                return response.body().string();
                                                            } else {
                                                                return "";
                                                            }
                                                        }

                                                        @Override
                                                        public void onSuccess(Response<String> response) {
                                                            String res = response.body();
                                                            if (!TextUtils.isEmpty(res)) {
                                                                try {
                                                                    AbsJson absJson = gson.fromJson(res, AbsJson.class);
                                                                    resData[0] = absJson.toAbsXml();
                                                                    absXml(resData[0], sb.getKey());
                                                                } catch (Exception e) {
                                                                    e.printStackTrace();
                                                                }
                                                            }
                                                            countDownLatch.countDown();
                                                        }

                                                        @Override
                                                        public void onError(Response<String> response) {
                                                            super.onError(response);
                                                            countDownLatch.countDown();
                                                        }
                                                    });
                                        } else {
                                            try {
                                                Spider sp = ApiConfig.get().getCSP(sb);
                                                //   ApiConfig.get().setPlayJarKey(sb.getJar());
                                                List<String> ids = new ArrayList<>();
                                                ids.add(finalPushUrl);
                                                String res = sp.detailContent(ids);
                                                if (!TextUtils.isEmpty(res)) {
                                                    try {
                                                        AbsJson absJson = gson.fromJson(res, AbsJson.class);
                                                        resData[0] = absJson.toAbsXml();
                                                        absXml(resData[0], sb.getKey());
                                                    } catch (Exception e) {
                                                        e.printStackTrace();
                                                    }
                                                }
                                            } catch (Throwable th) {
                                                th.printStackTrace();
                                            }
                                            countDownLatch.countDown();
                                        }
                                    }
                                });
                                try {
                                    countDownLatch.await(15, TimeUnit.SECONDS);
                                    threadPool.shutdown();
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                                if (resData[0] != null) {
                                    AbsXml res = resData[0];
                                    if (res.movie != null && res.movie.videoList != null && res.movie.videoList.size() > 0) {
                                        Movie.Video resVideo = res.movie.videoList.get(0);
                                        if (resVideo != null && resVideo.urlBean != null && resVideo.urlBean.infoList != null && resVideo.urlBean.infoList.size() > 0) {
                                            if (urlinfo.beanList.size() == 1) {
                                                video.urlBean.infoList.remove(i);
                                            } else {
                                                urlinfo.beanList.remove(infoBean);
                                            }
                                            for (Movie.Video.UrlBean.UrlInfo resUrlinfo : resVideo.urlBean.infoList) {
                                                if (resUrlinfo != null && resUrlinfo.beanList != null && !resUrlinfo.beanList.isEmpty()) {
                                                    video.urlBean.infoList.add(resUrlinfo);
                                                }
                                            }
                                            video.sourceKey = "push_agent";
                                            return data;
                                        }
                                    }
                                }
                                infoBean.name = "解析失败 >>> " + infoBean.name;
                            }
                        }
                    }
                }
            }
        }
        return data;
    }

    public void checkThunder(AbsXml data, int index) {
        boolean thunderParse = false;
        if (data.movie != null && data.movie.videoList != null && data.movie.videoList.size() == 1) {
            Movie.Video video = data.movie.videoList.get(0);
            if (video != null && video.urlBean != null && video.urlBean.infoList != null) {
            	boolean hasThunder=false;
                thunderLoop:
                for (int idx=0;idx<video.urlBean.infoList.size();idx++) {
                    Movie.Video.UrlBean.UrlInfo urlInfo = video.urlBean.infoList.get(idx);
                    for (Movie.Video.UrlBean.UrlInfo.InfoBean infoBean : urlInfo.beanList) {
                        if(Thunder.isSupportUrl(infoBean.url)){
                            hasThunder=true;
                            break thunderLoop;
                        }
                    }
                }
                if (hasThunder) {
                    thunderParse = true;
                    Thunder.parse(App.getInstance(), video.urlBean, new Thunder.ThunderCallback() {
                        @Override
                        public void status(int code, String info) {
                            if (code >= 0) {
                                LOG.i(info);
                            } else {
                                video.urlBean.infoList.get(0).beanList.get(0).name = info;
                                detailResult.postValue(data);
                            }
                        }

                        @Override
                        public void list(Map<Integer, String> urlMap) {
                            for (int key : urlMap.keySet()) {
                                String playList=urlMap.get(key);
                                video.urlBean.infoList.get(key).urls = playList;
                                String[] str = playList.split("#");
                                List<Movie.Video.UrlBean.UrlInfo.InfoBean> infoBeanList = new ArrayList<>();
                                for (String s : str) {
                                    if (s.contains("$")) {
                                        String[] ss = s.split("\\$");

                                        if (ss.length > 0) {
                                            if (ss.length >= 2) {
                                                infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean(ss[0], ss[1]));
                                            } else {
                                                infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean((infoBeanList.size() + 1) + "", ss[0]));
                                            }
                                        }
                                    }
                                }
                                video.urlBean.infoList.get(key).beanList = infoBeanList;
                            }
                            detailResult.postValue(data);
                        }

                        @Override
                        public void play(String url) {

                        }
                    });
                }
            }
        }
        if (!thunderParse && index==0) {
            detailResult.postValue(data);
        }
    }

    private AbsXml xml(MutableLiveData<AbsXml> result, String xml, String sourceKey) {
        try {
            XStream xstream = new XStream(new DomDriver());//创建Xstram对象
            xstream.autodetectAnnotations(true);
            xstream.processAnnotations(AbsXml.class);
            xstream.ignoreUnknownElements();
            xstream.allowTypes(new Class[]{AbsXml.class});
            if (xml.contains("<year></year>")) {
                xml = xml.replace("<year></year>", "<year>0</year>");
            }
            if (xml.contains("<state></state>")) {
                xml = xml.replace("<state></state>", "<state>0</state>");
            }
            AbsXml data = (AbsXml) xstream.fromXML(xml);
            absXml(data, sourceKey);
            if (searchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, data));
            } else if (quickSearchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_RESULT, data));
            } else if (result != null) {
                if (result == detailResult) {
                    data = checkPush(data);
                    checkThunder(data, 0);
                } else {
                    result.postValue(data);
                }
            }
            return data;
        } catch (Exception e) {
            if (searchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, null));
            } else if (quickSearchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_RESULT, null));
            } else if (result != null) {
                result.postValue(null);
            }
            return null;
        }
    }

    private AbsXml json(MutableLiveData<AbsXml> result, String json, String sourceKey) {
        try {
            // 测试数据
            /*json = "{\n" +
                    "\t\"list\": [{\n" +
                    "\t\t\"vod_id\": \"137133\",\n" +
                    "\t\t\"vod_name\": \"磁力测试\",\n" +
                    "\t\t\"vod_pic\": \"https:/img9.doubanio.com/view/photo/s_ratio_poster/public/p2656327176.webp\",\n" +
                    "\t\t\"type_name\": \"剧情 / 爱情 / 古装\",\n" +
                    "\t\t\"vod_year\": \"2022\",\n" +
                    "\t\t\"vod_area\": \"中国大陆\",\n" +
                    "\t\t\"vod_remarks\": \"40集全\",\n" +
                    "\t\t\"vod_actor\": \"刘亦菲\",\n" +
                    "\t\t\"vod_director\": \"杨阳\",\n" +
                    "\t\t\"vod_content\": \"　　在钱塘开茶铺的赵盼儿（刘亦菲 饰）惊闻未婚夫、新科探花欧阳旭（徐海乔 饰）要另娶当朝高官之女，不甘命运的她誓要上京讨个公道。在途中她遇到了出自权门但生性正直的皇城司指挥顾千帆（陈晓 饰），并卷入江南一场大案，两人不打不相识从而结缘。赵盼儿凭借智慧解救了被骗婚而惨遭虐待的“江南第一琵琶高手”宋引章（林允 饰）与被苛刻家人逼得离家出走的豪爽厨娘孙三娘（柳岩 饰），三位姐妹从此结伴同行，终抵汴京，见识世间繁华。为了不被另攀高枝的欧阳旭从东京赶走，赵盼儿与宋引章、孙三娘一起历经艰辛，将小小茶坊一步步发展为汴京最大的酒楼，揭露了负心人的真面目，收获了各自的真挚感情和人生感悟，也为无数平凡女子推开了一扇平等救赎之门。\",\n" +
                    "\t\t\"vod_play_from\": \"磁力测试\",\n" +
                    "\t\t\"vod_play_url\": \"0$magnet:?xt=urn:btih:9e9358b946c427962533472efdd2efd9e9e38c67&dn=%e9%98%b3%e5%85%89%e7%94%b5%e5%bd%b1www.ygdy8.com.%e7%83%ad%e8%a1%80.2022.BD.1080P.%e9%9f%a9%e8%af%ad%e4%b8%ad%e8%8b%b1%e5%8f%8c%e5%ad%97.mkv&tr=udp%3a%2f%2ftracker.opentrackr.org%3a1337%2fannounce&tr=udp%3a%2f%2fexodus.desync.com%3a6969%2fannounce\"\n" +
                    "\t}]\n" +
                    "}";*/
            AbsJson absJson = gson.fromJson(json, AbsJson.class);
            AbsXml data = absJson.toAbsXml();
            absXml(data, sourceKey);
            if (searchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, data));
            } else if (quickSearchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_RESULT, data));
            } else if (result != null) {
                if (result == detailResult) {
                    data = checkPush(data);
                    checkThunder(data, 0);
                } else {
                    result.postValue(data);
                }
            }
            return data;
        } catch (Exception e) {
            if (searchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, null));
            } else if (quickSearchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_RESULT, null));
            } else if (result != null) {
                result.postValue(null);
            }
            return null;
        }
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        closeExecutor(threadPoolGetPlay);

    }

    private void closeExecutor(ExecutorService executorService) {
        if (executorService != null) {
            try {
                executorService.shutdownNow();
            } catch (Throwable ignored) {
            }
        }
    }
}
