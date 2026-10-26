
package com.github.catvod.crawler;

import android.content.Context;
import android.util.Log;


import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.MD5;
import com.lzy.okgo.OkGo;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dalvik.system.DexClassLoader;
import okhttp3.Response;

public class JarLoader {
    private final ConcurrentHashMap<String, DexClassLoader> classLoaders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Method> proxyMethods = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Spider> spiders = new ConcurrentHashMap<>();
    private volatile String recentJarKey = "";

    /**
     * 不要在主线程调用我
     *
     * @param cache
     */
    public boolean load(String cache) {
        recentJarKey = "main";
        return loadClassLoader(cache, recentJarKey);
    }

    public void clear() {
        spiders.clear();
        proxyMethods.clear();
        classLoaders.clear();
    }

    /**
     * 丢掉某个 key 的 classLoader。
     * <p>检测到坏包（下载残缺 / md5 与配置对不上）时必须调它：
     * {@link #loadClassLoader} 开头会因为 {@code containsKey} 直接 return true，
     * 不清掉的话，后面对的新包永远加载不进来，页面一直停在上一个坏包上。
     */
    public void removeLoader(String key) {
        if (key == null) return;
        classLoaders.remove(key);
        spiders.remove(key);
        proxyMethods.remove(key);
    }

    private boolean loadClassLoader(String jar, String key) {
        if (classLoaders.containsKey(key)){
            Log.i("JarLoader", "echo-loadClassLoader jar缓存: " + key);
            return true;
        }
        boolean success = false;
        try {
            File cacheDir = new File(App.getInstance().getCacheDir().getAbsolutePath() + "/catvod_csp");
            if (!cacheDir.exists())
                cacheDir.mkdirs();
            final DexClassLoader classLoader = new DexClassLoader(jar, cacheDir.getAbsolutePath(), null, App.getInstance().getClassLoader());
            int count = 0;
            do {
                try {
                    final Class<?> classInit = classLoader.loadClass("com.github.catvod.spider.Init");
                    if (classInit != null) {
                        final Method initMethod = classInit.getMethod("init", Context.class);
                        // 在子线程中调用 init 方法，避免网络请求在主线程中执行
                        Thread initThread = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    initMethod.invoke(null, App.getInstance());
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }
                        });
                        initThread.start();
                        initThread.join();
                        Log.i("JarLoader", "echo-自定义爬虫代码加载成功!");
                        success = true;
                        try {
                            Class<?> proxy = classLoader.loadClass("com.github.catvod.spider.Proxy");
                            Method proxyMethod = proxy.getMethod("proxy", Map.class);
                            proxyMethods.put(key, proxyMethod);
                        } catch (Throwable th) {
                            // 可以记录错误日志
                            th.printStackTrace();
                        }
                        break;
                    }
                    Thread.sleep(200);
                } catch (Throwable th) {
                    th.printStackTrace();
                }
                count++;
            } while (count < 2);

            if (success) {
                classLoaders.put(key, classLoader);
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return success;
    }

    private DexClassLoader loadJarInternal(String jar, String md5, String key) {
        if (classLoaders.containsKey(key)){
            Log.i("JarLoader", "echo-loadJarInternal jar缓存: " + key);
            return classLoaders.get(key);
        }
        File cache = new File(App.getInstance().getFilesDir().getAbsolutePath() + "/csp/" + key + ".jar");
        if (!md5.isEmpty()) {
            if (cache.exists() && MD5.getFileMd5(cache).equalsIgnoreCase(md5)) {
                if(loadClassLoader(cache.getAbsolutePath(), key)){
                    return classLoaders.get(key);
                }else {
                    return null;
                }
            }
        }else {
            if (cache.exists() && !FileUtils.isWeekAgo(cache)) {
                if(loadClassLoader(cache.getAbsolutePath(), key)){
                    return classLoaders.get(key);
                }
            }
        }
        // 站点自带 jar（config 里每个 site 的 "jar" 字段）走的是这条分支。
        // 以前这里用 OkGo 直下：10 秒读超时、没有续传，慢站/断流就是
        // 「这个站点一直是空的」，而且失败没有任何提示。统一交给 JarFetcher。
        try {
            File part = new File(cache.getAbsolutePath() + ".part");
            com.github.tvbox.osc.api.JarFetcher.Result r = com.github.tvbox.osc.api.JarFetcher.fetch(jar, part, md5);
            if (r.file != null && r.file.exists() && r.file.length() > 0) {
                if (cache.exists()) cache.delete();
                if (!r.file.renameTo(cache)) {
                    // 跨挂载点 rename 会失败，退化成复制
                    java.io.FileInputStream in = new java.io.FileInputStream(r.file);
                    FileOutputStream out = new FileOutputStream(cache);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    out.flush();
                    out.close();
                    in.close();
                    r.file.delete();
                }
                if (loadClassLoader(cache.getAbsolutePath(), key)) {
                    com.github.tvbox.osc.api.SpiderDiag.clear();
                    return classLoaders.get(key);
                }
                com.github.tvbox.osc.api.SpiderDiag.set("站点组件已下载但加载失败（可能不是 jar）");
            } else {
                com.github.tvbox.osc.api.SpiderDiag.set("下载站点组件失败：" + r.err
                        + (r.diag == null || r.diag.isEmpty() ? "" : "（" + r.diag + "）"));
            }
        } catch (Throwable e) {
            com.github.tvbox.osc.api.SpiderDiag.fail("下载站点组件", e);
            e.printStackTrace();
        }
        return null;
    }

    public Spider getSpider(String key, String cls, String ext, String jar) {
        if (spiders.containsKey(key)) {
            Log.i("JarLoader", "echo-getSpider spider缓存: " + key);
            return spiders.get(key);
        }
        String clsKey = cls.replace("csp_", "");
        String jarUrl = "";
        String jarMd5 = "";
        String jarKey;
        if (jar.isEmpty()) {
            jarKey = "main";
        } else {
            String[] urls = jar.split(";md5;");
            jarUrl = urls[0];
            jarKey = MD5.string2MD5(jarUrl);
            jarMd5 = urls.length > 1 ? urls[1].trim() : "";
        }
        recentJarKey = jarKey;
        assert jarKey != null;
        DexClassLoader classLoader = jarKey.equals("main")? classLoaders.get("main"):loadJarInternal(jarUrl, jarMd5, jarKey);
        if (classLoader == null) {
            com.github.tvbox.osc.api.SpiderDiag.set("播放组件没有加载成功，站点 " + clsKey + " 用不了");
            return new SpiderNull();
        }
        try {
            Log.i("JarLoader", "echo-getSpider 加载spider: " + key);
            Spider sp = (Spider) classLoader.loadClass("com.github.catvod.spider." + clsKey).newInstance();
            sp.init(App.getInstance(), ext);
            if (!jar.isEmpty()) {
                sp.homeContent(false); // 增加此行 应该可以解决部分写的有问题源的历史记录问题 但会增加这个源的首次加载时间 不需要可以已删掉
            }
            spiders.put(key, sp);
            return sp;
        } catch (Throwable th) {
            // 这里最常抓到的是 NoClassDefFoundError: com.github.catvod.net.OkHttp ——
            // 也就是「R8 把宿主给 jar 用的 API 改名了」，报给前端才能看出来。
            com.github.tvbox.osc.api.SpiderDiag.fail("加载站点 " + clsKey, th);
            // 另一类：主 jar 里没有这个类。多半是站点换了新组件、本地缓存还是旧包
            // （接口不声明 md5 时，光看文件分辨不出来），旧包能 load 就一直接着用，
            // 表现就是「接口连上了但没内容」。把旧组件丢掉，下次加载会重下。
            if (jar.isEmpty()
                    && (th instanceof ClassNotFoundException || th instanceof NoClassDefFoundError)) {
                if (com.github.tvbox.osc.api.ApiConfig.get().invalidateMainJar()) {
                    com.github.tvbox.osc.api.SpiderDiag.set("站点 " + clsKey
                            + " 在本地组件里不存在（当前组件是旧版），已清掉旧组件，"
                            + "点「重新加载」会自动下载新组件");
                }
            }
            th.printStackTrace();
        }
        return new SpiderNull();
    }

    public JSONObject jsonExt(String key, LinkedHashMap<String, String> jxs, String url) {
        try {
            DexClassLoader classLoader = classLoaders.get("main");
            String clsKey = "Json" + key;
            String hotClass = "com.github.catvod.parser." + clsKey;
            assert classLoader != null;
            Class<?> jsonParserCls = classLoader.loadClass(hotClass);
            Method mth = jsonParserCls.getMethod("parse", LinkedHashMap.class, String.class);
            return (JSONObject) mth.invoke(null, jxs, url);
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return null;
    }

    public JSONObject jsonExtMix(String flag, String key, String name, LinkedHashMap<String, HashMap<String, String>> jxs, String url) {
        try {
            DexClassLoader classLoader = classLoaders.get("main");
            String clsKey = "Mix" + key;
            String hotClass = "com.github.catvod.parser." + clsKey;
            assert classLoader != null;
            Class<?> jsonParserCls = classLoader.loadClass(hotClass);
            Method mth = jsonParserCls.getMethod("parse", LinkedHashMap.class, String.class, String.class, String.class);
            return (JSONObject) mth.invoke(null, jxs, name, flag, url);
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return null;
    }

    public Object[] proxyInvoke(Map<String,String> params) {
        try {
            Method proxyFun = proxyMethods.get(recentJarKey);
            if (proxyFun != null) {
                return (Object[]) proxyFun.invoke(null, params);
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return null;
    }
}
