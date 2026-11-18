package com.pigtv.util;

import android.content.res.AssetManager;

import com.pigtv.base.App;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class EpgUtil {

    private static JsonObject epgDoc = null;
    private static HashMap<String, JsonObject> epgHashMap = new HashMap<>();

    /**
     * 归一化台名 → 台标地址。
     *
     * <p>源里给的台名和 epg_data.json 里的别名几乎从不逐字相同（「CCTV-4 中文国际」
     * 和「CCTV-4中文国际」差一个空格），所以查表不能用原始串，得先归一。
     */
    private static final HashMap<String, String> logoMap = new HashMap<>();
    /** 归一化别名按长度倒序，供「名字里包含某个别名」这种模糊匹配用 */
    private static List<String> aliasByLen = new ArrayList<>();

    /** 台名里的画质标记：[1080P] [4K] [2160P] */
    private static final Pattern TAG = Pattern.compile("\\[(?:\\d{3,4}[pP]|[248][kK])\\]");
    /** 台名末尾的画质词 */
    private static final Pattern TAIL = Pattern.compile("(?:高清|超清|蓝光|标清)$");
    /** 认台时无意义的空白和连接符 */
    private static final Pattern NOISE = Pattern.compile("[\\s\\-_·|]+");

    public static void init() {
        if (epgDoc != null)
            return;

        //credit by 龍
        try {
            AssetManager assetManager = App.getInstance().getAssets(); //获得assets资源管理器（assets中的文件无法直接访问，可以使用AssetManager访问）
            InputStreamReader inputStreamReader = new InputStreamReader(assetManager.open("epg_data.json"),"UTF-8"); //使用IO流读取json文件内容
            BufferedReader br = new BufferedReader(inputStreamReader);//使用字符高效流
            String line;
            StringBuilder builder = new StringBuilder();
            while ((line = br.readLine()) != null) {
                builder.append(line);
            }
            br.close();
            inputStreamReader.close();
            if (!builder.toString().isEmpty()) {
                epgDoc = new Gson().fromJson(builder.toString(), (Type) JsonObject.class);// 从builder中读取了json中的数据。
                for (JsonElement opt : epgDoc.get("epgs").getAsJsonArray()) {
                    JsonObject obj = (JsonObject) opt;
                    String name = obj.get("name").getAsString().trim();
                    String[] names = name.split(",");
                    for (String string : names) {
                        epgHashMap.put(string, obj);
                    }
                    indexLogo(obj);
                }
                aliasByLen = new ArrayList<>(logoMap.keySet());
                // 长别名优先：短别名（「浙江」这种）容易把「浙江少儿」错认成浙江卫视
                Collections.sort(aliasByLen, new Comparator<String>() {
                    @Override
                    public int compare(String a, String b) {
                        return b.length() - a.length();
                    }
                });
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** 把一条 EPG 记录的所有别名登记进 logoMap */
    private static void indexLogo(JsonObject obj) {
        try {
            String logo = obj.get("logo").getAsString();
            if (logo == null || logo.trim().isEmpty()) return;
            String[] names = obj.get("name").getAsString().trim().split(",");
            for (String n : names) {
                String k = norm(n);
                if (!k.isEmpty() && !logoMap.containsKey(k)) logoMap.put(k, logo.trim());
            }
        } catch (Exception ignored) {
        }
    }

    public static String[] getEpgInfo(String channelName) {
        try {
            if (epgHashMap.containsKey(channelName)) {
                JsonObject obj = epgHashMap.get(channelName);
                return new String[]{
                        obj.get("logo").getAsString(),
                        obj.get("epgid").getAsString()
                };
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }

    /**
     * 台标地址查表，查不到返回 null。
     *
     * <p>两级：先按归一化台名精确匹配；不中再找「名字里包含某个别名」，
     * 取最长的那个别名（「CCTV-4 中文国际（欧）」能落到 CCTV4 的台标上，
     * 但「浙江少儿」不会被「浙江」这种两字别名抢走 —— 短别名不参与模糊匹配）。
     */
    public static String getLogo(String channelName) {
        if (epgDoc == null) init();
        String k = norm(channelName);
        if (k.isEmpty()) return null;
        String exact = logoMap.get(k);
        if (exact != null) return exact;
        for (String alias : aliasByLen) {
            if (alias.length() >= 4 && k.contains(alias)) return logoMap.get(alias);
        }
        return null;
    }

    /**
     * 台名归一：剥画质标记和空白/连接符，转小写。
     *
     * <p>和 {@code FavoriteStore.normName} 是同一套规则 —— 两处都在解决「同一个台不同写法」，
     * 规则分叉的话会出现「收藏认得出、台标认不出」这种很难解释的现象。
     */
    public static String norm(String s) {
        if (s == null) return "";
        String out = TAG.matcher(s.trim()).replaceAll("").trim();
        out = TAIL.matcher(out).replaceAll("");
        return NOISE.matcher(out).replaceAll("").toLowerCase(Locale.ROOT);
    }
}
