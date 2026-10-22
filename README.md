# 猪猪影视 · PIG-TV

基于开源项目 **TVBox** 二次开发的影视聚合客户端。一套内核两个壳：**电视版**（Leanback
遥控器操作）和**手机版**（竖屏 + WebView 前端），共用同一套片源配置格式。

> 纯客户端，不含任何片源。接口地址需要自己配置（支持 TVBox 的 config JSON / XML 接口）。

---

## 与 TVBox 的关系

**本项目根据 TVBox 改编，内核沿用、UI 全部重做。**

| | 来源 |
|---|---|
| 片源解析、播放器（IJK / Exo）、jar 插件、局域网推送服务 | 沿用 [TVBox](https://github.com/CatVodTVOfficial/TVBoxOSC)（CatVodTVOfficial/TVBoxOSC） |
| **电视版界面** | 重做：Leanback 首页 / 分类 / 搜索 / 详情 / 我的，暖色设计 token 与新的焦点动效 |
| **手机版** | 全新：TVBox 原本只有电视一套 UI，本项目的手机版是独立 Activity + WebView 前端（`assets/phone/` 下的 HTML/CSS/JS），原生能力通过 `PhoneBridge` 以 `@JavascriptInterface` 暴露 |
| 品牌 | 猪猪影视 / PIG-TV，图标、启动图、应用名全部替换 |

包名已经改为本项目自己的：

| | 包名 |
|---|---|
| 电视版 | `com.pigtv.tv`（海信定制 flavor：`com.pigtv.tv.hisense`） |
| 手机版 | `com.pigtv.phone`（海信定制 flavor：`com.pigtv.phone.hisense`） |
| 代码根包 | `com.pigtv` |

⚠️ **这是一款全新的应用**，和旧版 TVBox 签名的包不共存：装上新版后，旧版配好的片源、
观看历史、设置都不会继承过来，需要重新配置。旧版可以单独留着不用卸载。

---

## 目录结构

| 路径 | 说明 |
|---|---|
| `pig-tv-tv/` | **电视版**（Android TV / 遥控器）。`assembleArm64GenericNormalRelease` |
| `pig-tv-phone/` | **手机版**。UI 主体是 `app/src/main/assets/phone/` 下的 HTML/CSS/JS，通过 `PhoneBridge` 暴露原生能力 |
| `pig-tv-fongmi/` | 另一个 TV 分支（[Fongmi](https://github.com/FongMi/TV) 内核）。**缺私有依赖，本仓库编不出来**，仅作参考 |
| `dist/` | 打包输出的 APK（**不入库**） |

手机版前端的三个文件：

- `assets/phone/index.html` —— 全部页面与逻辑（~2200 行）
- `assets/phone/app.css` —— 样式，颜色走 `--*` CSS 变量（深浅色 / 三态主题）
- `android/.../phone/PhoneBridge.java` —— `@JavascriptInterface` 桥，前端所有原生能力都从这里走

构建产物名由 `settings.gradle` 的 `rootProject.name = "PIG-TV"` 决定，输出形如
`PIG-TV_release-arm64-generic-java.apk`。

---

## 构建

环境要求（实测可用的组合）：

```bash
export JAVA_HOME=~/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home   # 必须 JDK 17
export ANDROID_HOME=~/Library/Android/sdk
unset GRADLE_USER_HOME
```

> AGP 7.4.2 不认 JDK 21。`gradle.properties` 里已把 `org.gradle.jvmargs` 调到 `-Xmx6144m`，
> 用默认堆跑 R8 会 OOM。改完这个文件记得 `./gradlew --stop` 再编。

**电视版**

```bash
cd pig-tv-tv
./gradlew :app:assembleArm64GenericNormalRelease
# 输出 app/build/outputs/apk/arm64GenericNormal/release/
```

**手机版**

```bash
cd pig-tv-phone
./gradlew :app:assembleUniversalGenericNormalRelease
# 输出 app/build/outputs/apk/universalGenericNormal/release/
```

**签名**（release 没配 signingConfigs，需手动两步）

```bash
BT=$ANDROID_HOME/build-tools/33.0.1
$BT/zipalign -p -f 4 in.apk /tmp/aligned.apk
$BT/apksigner sign --ks <你的.jks> --ks-key-alias <alias> \
  --min-sdk-version 21 --v1-signing-enabled true \
  --v2-signing-enabled true --v3-signing-enabled true --out out.apk /tmp/aligned.apk
```

> 本仓库**不含签名文件**（`*.jks` 已 ignore）。自己打包请生成一个，否则只能装 debug 包。

---

## 几个反直觉的点（改代码前先看）

1. **`HomeActivity.getRes()` 这类静态入口在手机版一律是 null**，`loadJar` 也只被 `HomeActivity` 调过。
   手机版自己补了 `ensureJarBlocking()`。
2. **`HomeActivity.init()` 里三件隐形基础设施手机版必须自己起**：`res = getResources()`、
   `ControlManager.startServer()`、`App.startWebserver()`。少了 `startServer()`（`127.0.0.1:9978`）
   会出现「有集数但一播就报错」。
3. **WebView 里 `env(safe-area-inset-*)` 恒为 0**。手机版用原生量出高度 ÷ density 注入
   `--sat/--sab`，且只对 CSS 变量有效 —— WebView 的 padding 管不到 `position:fixed` 的浮层。
4. **浮层层级阶梯**：`#scroll`1 < `#tabs`40 < `#sov`70 < `#detail`**76** < `#cfg`/`#toast`90
   < `#flt`94 < `#dAlt`95 < `#ask`96。从某个浮层里再开另一个浮层，被盖住就是「点了没反应」。
5. **数据绝不拼进 `onclick="fn('X')"`**。HTML 属性是先解码实体再交给 JS 编译的，
   把 `'` 转成 `&#39;` 并不能阻止注入。带参数的点击一律 `data-act` + `data-val` + 事件委托。
6. **`SourceViewModel.listResult` 只有一条 LiveData**，结构上无法把结果和请求对应起来
   （`postValue` 还会合并连发）。所以 `PhoneBridge` 里 list 请求是**串行队列**：
   `listQ`（用户）优先、`hotQ`（首页推荐池）垫底，20s 看门狗防堵死。
7. **主题必须是 `Theme.AppCompat.DayNight.NoActionBar`**。祖先链里出现 `Light.*` 会把 night 位
   摁成 NO，`values-night` 静默失效 —— 编译和前端验证全绿，只有真机看得出。
8. **spider jar 不能用全局 OkGo 下**。`OkGoHelper.init()` 把 readTimeout 定成 10 秒、还允许 h2，
   小 JSON 没问题，1MB 的 jar 会在慢站上「下到一半就断」——用户看到的就是首页空着、
   提示「jar 下载失败」。现在走 `com.pigtv.api.JarFetcher`：独立客户端（30s 读超时、强制
   HTTP/1.1、Range 续传、识别拦截页、http→https 兜底），下载细节通过 `ApiConfig.getJarDiag()`
   一路传到前端空态里显示，出问题直接看那行小字。
9. **`com.github.catvod.**` 必须整包 keep**（见 `proguard-rules.pro` 里那段注释）。
   这几个类是宿主借给 jar 里爬虫用的 API，运行时按**类名**直接调用。只 keep `crawler.*` 时
   R8 会把 `net.OkHttp` 改名成 `androidx.base.xx`，jar 第一句 `OkHttp.get()` 就
   `NoClassDefFoundError`，用户侧看到的却是「接口没有返回任何分类」。上游 Box 也有这个坑。
   排查入口：`com.pigtv.api.SpiderDiag`（把三层 catch 吞掉的异常捞回来，显示在首页空态）。
10. **站点自带 `jar` 字段走 `JarLoader.loadJarInternal`**，是另一条独立下载路径。别再改回
   OkGo，已经统一到 `JarFetcher`，否则「配置里的主 jar 能下、站点自己的 jar 永远下不来」。

---

## 前端自测（脚本不在本仓库）

手机版界面是纯前端，改 `assets/phone/` 下的文件后建议跑一遍 Playwright 断言。
脚本（`_verify_app.js` 等）体积大、含大量截图，**未入库**，在本地设计稿目录里：

```bash
cd <本地设计稿目录>
node _verify_app.js      # 全量断言 + 截图
node _diag_api.js        # 接口配置页
```

`_verify_app.js` 直接加载 `assets/phone/index.html`，mock 掉 `AndroidPhone` 桥。

---

## 仓库里没有的东西

为了避免仓库膨胀到几个 G，以下内容**未入库**，需要时自己准备：

| 未入库 | 原因 |
|---|---|
| `*.jks` / 签名口令 | 安全。自己生成一个 keystore 即可打包 |
| `dist/*.apk` | 构建产物，见 Release 页 |
| `ui-design/` | 设计稿 HTML 原型 + 截图外壳工程 + Playwright 脚本，148MB |
| `pig-tv-*/xwalk/*.zip` | 4 个 Crosswalk 预编译包共 208MB，只有 `python` 玩法模块用到（而那个模块本来就编不出来） |
| `build/` `.gradle/` `local.properties` | 构建缓存与机器相关配置 |
| 根目录 `*.md` | 只保留 `README.md`，说明/排查类文档本地留档 |

---

## 声明

本项目仅供学习交流，不提供、不托管任何影视内容。所有内容来自用户自行配置的第三方接口，
与本项目无关。请于下载后 24 小时内删除。

界面与交互部分由本项目重做，内核版权归 [TVBox](https://github.com/CatVodTVOfficial/TVBoxOSC)
原作者所有，遵循其原始许可证（见 `pig-tv-tv/LICENSE`）。
