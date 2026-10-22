package com.github.tvbox.osc.viewmodel;

import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.github.catvod.crawler.JsLoader;
import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.AbsJson;
import com.github.tvbox.osc.bean.AbsSortJson;
import com.github.tvbox.osc.bean.AbsSortXml;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.thunder.Thunder;
import com.github.tvbox.osc.util.urlhttp.OkHttpUtil;
import com.github.tvbox.osc.util.FileUtils;
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
        searchExecutorService = Executors.newFixedThreadPool(5);
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
            com.github.tvbox.osc.api.SpiderDiag.set("这个站点在当前接口里不存在（可能刚换过线路）");
            postSort(null, gen);
            return;
        }
        com.github.tvbox.osc.api.SpiderDiag.clear();          // 新一轮请求，上一轮的错误作废
        final int type = sourceBean.getType();
        if (type == 3) {
            Runnable waitResponse = new Runnable() {
                @Override
                public void run() {
                    ExecutorService executor = Executors.newSingleThreadExecutor();
                    // spider 没加载起来（jar 没下完 / 下载失败 / 类名不对）时，
                    // JarLoader 给的是 SpiderNull，它的 homeContent() 直接返回 ""。
                    // 记下来，好在下面给出「组件没加载好」而不是「站点返回的不是 JSON」。
                    final boolean[] noSpider = {false};
                    Future<String> future = executor.submit(new Callable<String>() {
                        @Override
                        public String call() throws Exception {
                            Spider sp = ApiConfig.get().getCSP(sourceBean);
                            if (sp == null || sp instanceof com.github.catvod.crawler.SpiderNull) noSpider[0] = true;
                            return sp == null ? "" : sp.homeContent(true);
                        }
                    });
                    String sortJson = null;
                    try {
                        sortJson = future.get(20, TimeUnit.SECONDS);
                    } catch (TimeoutException e) {
                        e.printStackTrace();
                        future.cancel(true);
                        com.github.tvbox.osc.api.SpiderDiag.set("取分类超时（20 秒），站点没有响应");
                    } catch (InterruptedException | ExecutionException e) {
                        e.printStackTrace();
                        // 这里以前只有 printStackTrace：站点抛的 NoClassDefFoundError 全被吞了，
                        // 用户侧只看到「接口没有返回任何分类」。
                        com.github.tvbox.osc.api.SpiderDiag.fail("取分类", e instanceof ExecutionException ? e.getCause() : e);
                    } finally {
                        // 组件没加载好时 homeContent 只会给空串，下面那两个分支会先把原因
                        // 写成「站点返回了空内容」、再被「不是标准 JSON」覆盖掉 —— 用户看到的是
                        // 「站点坏了」，实际是「组件没下下来」。所以这里先按真实情况定性，
                        // 后面两步只在还没有原因时才补兜底文案。
                        if (noSpider[0] && com.github.tvbox.osc.api.SpiderDiag.get() == null)
                            com.github.tvbox.osc.api.SpiderDiag.set("播放组件（spider.jar）还没加载好，这个站点取不到内容");
                        if (sortJson != null) {
                            if ((sortJson.trim().isEmpty() || "{}".equals(sortJson.trim()))
                                    && com.github.tvbox.osc.api.SpiderDiag.get() == null)
                                com.github.tvbox.osc.api.SpiderDiag.set("站点返回了空内容（可能该站在维护或已停用）");
                            final AbsSortXml sortXml = sortJson(sortResult, sortJson);
                            if (sortXml == null && com.github.tvbox.osc.api.SpiderDiag.get() == null)
                                com.github.tvbox.osc.api.SpiderDiag.set("站点返回的分类解析不了（不是标准 JSON）");
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
                            if (com.github.tvbox.osc.api.SpiderDiag.get() == null)
                                com.github.tvbox.osc.api.SpiderDiag.set("站点没有返回分类（首页接口是空的）");
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
                            com.github.tvbox.osc.api.SpiderDiag.set("取分类失败："
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

    // categoryContent
    public void getList(MovieSort.SortData sortData, int page) {
        SourceBean homeSourceBean = ApiConfig.get().getHomeSourceBean();
        int type = homeSourceBean.getType();
        if (type == 3) {
            spThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        Spider sp = ApiConfig.get().getCSP(homeSourceBean);
                        json(listResult, sp.categoryContent(sortData.id, page + "", true, sortData.filterSelect), homeSourceBean.getKey());
                    } catch (Throwable th) {
                        th.printStackTrace();
                        listResult.postValue(null);
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
                                xml(listResult, xml, homeSourceBean.getKey());
                            } else {
                                String json = response.body();
                                json(listResult, json, homeSourceBean.getKey());
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            listResult.postValue(null);
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
                            json(listResult, json, homeSourceBean.getKey());
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            listResult.postValue(null);
                        }
                    });
        } else {
            listResult.postValue(null);
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
        int type = sourceBean.getType();
        if (type == 3) {
            try {
                Spider sp = ApiConfig.get().getCSP(sourceBean);
                String search = sp.searchContent(wd, false);
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
        int type = sourceBean.getType();
        if (type == 3) {
            try {
                Spider sp = ApiConfig.get().getCSP(sourceBean);
                json(quickSearchResult, sp.searchContent(wd, true), sourceBean.getKey());
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
    /** 详情 / 播放请求都要先把 extend 里的 http 地址抓成本地 json，不能直接透传 */
    public String getFixUrl(final String extend) {
        if (extend == null) return "";
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

    /** JsonObject 里取字符串，缺字段 / null 都返回空串，绝不抛异常 */
    private static String strOf(JsonObject obj, String name) {
        if (obj == null || !obj.has(name)) return "";
        JsonElement el = obj.get(name);
        if (el == null || el.isJsonNull()) return "";
        try {
            return el.getAsString();
        } catch (Throwable th) {
            return "";
        }
    }

    /**
     * 单个筛选组。真实片源里这个结构的写法五花八门，原来只要有一处不合
     * 预期（缺 name、value 不是对象数组）就抛异常，而外层是
     * {@code catch (Throwable) {}} —— 于是**所有分类的筛选一起消失**，
     * 前端看起来就是「筛选条不见了」，而分类本身还正常。
     * <p>
     * 这里把见过的几种形态都认下来，并且一项解析失败只丢那一项：
     * <pre>
     *   {"key":"area","name":"地区","value":[{"n":"全部","v":""}]}   标准
     *   {"key":"area","name":"地区","values":[{...}]}                字段名是 values
     *   {"key":"area","name":"地区","value":[["全部",""]]}           二维数组
     *   {"key":"area","name":"地区","value":{"全部":"","内地":"内地"}} 对象（名字→值）
     *   {"name":"地区","value":["全部","内地"]}                     纯字符串数组 / 没有 key
     * </pre>
     */
    private MovieSort.SortFilter getSortFilter(JsonObject obj) {
        if (obj == null) return null;
        String key = strOf(obj, "key");
        String name = strOf(obj, "name");
        if (TextUtils.isEmpty(name)) name = key;
        if (TextUtils.isEmpty(key)) key = name;
        if (TextUtils.isEmpty(key) && TextUtils.isEmpty(name)) return null;

        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        JsonElement ve = obj.get("value");
        if (ve == null || ve.isJsonNull()) ve = obj.get("values");
        if (ve != null && !ve.isJsonNull()) {
            if (ve.isJsonArray()) {
                for (JsonElement ele : ve.getAsJsonArray()) {
                    try {
                        if (ele == null || ele.isJsonNull()) continue;
                        String n, v;
                        if (ele.isJsonObject()) {
                            JsonObject eo = ele.getAsJsonObject();
                            n = strOf(eo, "n");
                            if (TextUtils.isEmpty(n)) n = strOf(eo, "name");
                            v = strOf(eo, "v");
                            if (TextUtils.isEmpty(v)) v = strOf(eo, "value");
                        } else if (ele.isJsonArray()) {
                            // ["显示名","提交值"]
                            JsonArray kv = ele.getAsJsonArray();
                            n = kv.size() > 0 && !kv.get(0).isJsonNull() ? kv.get(0).getAsString() : "";
                            v = kv.size() > 1 && !kv.get(1).isJsonNull() ? kv.get(1).getAsString() : "";
                        } else {
                            // 纯字符串数组：显示名就是要提交的值
                            n = ele.getAsString();
                            v = n;
                        }
                        // 爬虫只给了值没给名字（或反过来）时互相兜一下，
                        // 否则会出现一个点不到的空按钮
                        if (TextUtils.isEmpty(n)) n = v;
                        if (TextUtils.isEmpty(v)) v = n;
                        if (TextUtils.isEmpty(n) && TextUtils.isEmpty(v)) continue;
                        values.put(n, v);
                    } catch (Throwable ignored) {
                        // 单个取值坏掉不影响这一组的其它取值
                    }
                }
            } else if (ve.isJsonObject()) {
                JsonObject vo = ve.getAsJsonObject();
                for (Map.Entry<String, JsonElement> e : vo.entrySet()) {
                    try {
                        String n = e.getKey();
                        String v = e.getValue() == null || e.getValue().isJsonNull() ? "" : e.getValue().getAsString();
                        if (TextUtils.isEmpty(v)) v = n;
                        values.put(n, v);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        if (values.isEmpty()) return null;

        MovieSort.SortFilter filter = new MovieSort.SortFilter();
        filter.key = key;
        filter.name = name;
        filter.values = values;
        return filter;
    }

    /** filters 的某一项 → 一组筛选（可能本身就是单个对象，也可能是数组） */
    private ArrayList<MovieSort.SortFilter> parseFilterList(JsonElement one) {
        ArrayList<MovieSort.SortFilter> out = new ArrayList<>();
        if (one == null || one.isJsonNull()) return out;
        try {
            if (one.isJsonObject()) {
                MovieSort.SortFilter f = getSortFilter(one.getAsJsonObject());
                if (f != null) out.add(f);
            } else if (one.isJsonArray()) {
                for (JsonElement ele : one.getAsJsonArray()) {
                    try {
                        if (ele != null && ele.isJsonObject()) {
                            MovieSort.SortFilter f = getSortFilter(ele.getAsJsonObject());
                            if (f != null) out.add(f);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 把 homeContent 顶层的 filters 挂到各个分类上。
     * <p>
     * 匹配顺序：分类 id → 分类名 → 位置。前两个是标准做法；第三个是兜底 ——
     * 有些爬虫的 filters key 是「第几组」或者干脆是中转用的乱码，跟 type_id
     * 一个都对不上，这时候如果直接放弃，用户看到的就是「分类正常、筛选没有」。
     * 只有在**一个都没匹配上**时才按顺序兜底，避免顺序错位挂到别的分类上。
     */
    private void applySortFilters(JsonObject obj, AbsSortXml data) {
        if (obj == null || data == null || data.classes == null || data.classes.sortList == null) return;
        JsonElement fe = obj.get("filters");
        if (fe == null || fe.isJsonNull()) return;

        LinkedHashMap<String, ArrayList<MovieSort.SortFilter>> parsed = new LinkedHashMap<>();
        ArrayList<String> order = new ArrayList<>();
        try {
            if (fe.isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : fe.getAsJsonObject().entrySet()) {
                    ArrayList<MovieSort.SortFilter> one = parseFilterList(e.getValue());
                    if (one.isEmpty()) continue;
                    parsed.put(e.getKey(), one);
                    order.add(e.getKey());
                }
            } else if (fe.isJsonArray()) {
                int i = 0;
                for (JsonElement ele : fe.getAsJsonArray()) {
                    ArrayList<MovieSort.SortFilter> one = parseFilterList(ele);
                    String k = String.valueOf(i++);
                    if (one.isEmpty()) continue;
                    parsed.put(k, one);
                    order.add(k);
                }
            }
        } catch (Throwable th) {
            LOG.i("echo--filters-parse-fail--" + th.getMessage());
        }
        if (parsed.isEmpty()) return;

        int matched = 0;
        for (MovieSort.SortData sort : data.classes.sortList) {
            if (sort == null) continue;
            ArrayList<MovieSort.SortFilter> hit = null;
            if (!TextUtils.isEmpty(sort.id) && parsed.containsKey(sort.id)) hit = parsed.get(sort.id);
            else if (!TextUtils.isEmpty(sort.name) && parsed.containsKey(sort.name)) hit = parsed.get(sort.name);
            if (hit != null) {
                sort.filters = hit;
                matched++;
            }
        }

        // 一个都对不上：按位置兜底（filters 的组数 >= 分类数时才这么做）
        if (matched == 0 && order.size() >= data.classes.sortList.size()) {
            for (int i = 0; i < data.classes.sortList.size(); i++) {
                MovieSort.SortData sort = data.classes.sortList.get(i);
                if (sort == null) continue;
                ArrayList<MovieSort.SortFilter> one = parsed.get(order.get(i));
                if (one != null) sort.filters = one;
            }
            LOG.i("echo--filters-by-index--" + data.classes.sortList.size());
        }
        LOG.i("echo--filters-matched--" + matched + "/" + data.classes.sortList.size() + " keys=" + parsed.size());
    }

    private static String firstNonEmpty(String... vals) {
        if (vals == null) return "";
        for (String v : vals) {
            if (v != null && !v.trim().isEmpty()) return v;
        }
        return "";
    }

    /**
     * Gson 失败（或没解出分类）时的兜底解析。
     * <p>
     * 典型场景：爬虫的 homeContent 里 {@code "class"} 是字符串数组
     * {@code ["电影","电视剧"]}，而 {@link AbsSortJson} 里声明的是对象数组，
     * Gson 会直接抛 → 整个 sortJson 返回 null → 用户看到「接口没有返回任何分类」。
     */
    private AbsSortXml manualSort(JsonObject obj) {
        if (obj == null) return null;
        JsonElement ce = obj.get("class");
        if (ce == null || !ce.isJsonArray()) return null;
        MovieSort movieSort = new MovieSort();
        movieSort.sortList = new ArrayList<>();
        int idx = 0;
        for (JsonElement el : ce.getAsJsonArray()) {
            try {
                if (el == null || el.isJsonNull()) continue;
                MovieSort.SortData sd = new MovieSort.SortData();
                if (el.isJsonObject()) {
                    JsonObject co = el.getAsJsonObject();
                    sd.id = firstNonEmpty(strOf(co, "type_id"), strOf(co, "id"), strOf(co, "key"));
                    sd.name = firstNonEmpty(strOf(co, "type_name"), strOf(co, "name"), sd.id);
                    sd.flag = firstNonEmpty(strOf(co, "type_flag"), strOf(co, "flag"));
                } else {
                    // 字符串数组：名字就是 id（这类爬虫的 categoryContent 也按名字取）
                    sd.name = el.getAsString();
                    sd.id = sd.name;
                }
                sd.id = firstNonEmpty(sd.id, String.valueOf(idx));
                sd.name = firstNonEmpty(sd.name, sd.id);
                movieSort.sortList.add(sd);
                idx++;
            } catch (Throwable ignored) {
            }
        }
        if (movieSort.sortList.isEmpty()) return null;
        AbsSortXml data = new AbsSortXml();
        data.classes = movieSort;
        return data;
    }

    private AbsSortXml sortJson(MutableLiveData<AbsSortXml> result, String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            AbsSortXml data = null;
            try {
                AbsSortJson sortJson = gson.fromJson(obj, new TypeToken<AbsSortJson>() {
                }.getType());
                if (sortJson != null) data = sortJson.toAbsSortXml();
            } catch (Throwable ignored) {
                // 结构不合 Gson 的预期，下面手动兜底
            }
            if (data == null || data.classes == null || data.classes.sortList == null
                    || data.classes.sortList.isEmpty()) {
                AbsSortXml manual = manualSort(obj);
                if (manual != null) data = manual;
            }
            if (data == null) return null;
            applySortFilters(obj, data);
            return data;
        } catch (Exception e) {
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
                                                                    AbsJson absJson = gson.fromJson(res, new TypeToken<AbsJson>() {
                                                                    }.getType());
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
                                                        AbsJson absJson = gson.fromJson(res, new TypeToken<AbsJson>() {
                                                        }.getType());
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
            AbsJson absJson = gson.fromJson(json, new TypeToken<AbsJson>() {
            }.getType());
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
