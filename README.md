# 猪猪影视 · PIG-TV

基于开源 TVBox 改造的影视聚合客户端。一套代码两个壳：**电视版**（Leanback 遥控器操作）和
**手机版**（竖屏 + WebView 前端），共用同一套片源配置格式。

> 纯客户端，不含任何片源。接口地址需要自己配置（支持 TVBox 的 config JSON / XML 接口）。

---

## 目录结构

| 路径 | 说明 |
|---|---|
| `Box/` | **电视版**（Android TV / 遥控器）。`assembleArm64GenericNormalRelease` |
| `Box-phone/` | **手机版**。UI 主体是 `app/src/main/assets/phone/` 下的 HTML/CSS/JS，通过 `PhoneBridge` 暴露原生能力 |
| `TV-fongmi/` | 另一个 TV 分支（Fongmi 内核）。**缺私有依赖，本仓库编不出来**，仅作参考 |
| `ui-design/` | 前端设计稿 + 自动化验证脚本（Playwright），不是 App 的一部分 |
| `dist/` | 打包输出的 APK（**不入库**） |

手机版前端的三个文件：

- `assets/phone/index.html` —— 全部页面与逻辑（~2200 行）
- `assets/phone/app.css` —— 样式，颜色走 `--*` CSS 变量（深浅色 / 三态主题）
- `android/.../phone/PhoneBridge.java` —— `@JavascriptInterface` 桥，前端所有原生能力都从这里走

---

## 构建

环境要求（实测可用的组合）：

```bash
export JAVA_HOME=~/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home   # 必须 JDK 17
export ANDROID_HOME=~/Library/Android/sdk
unset GRADLE_USER_HOME
```

> AGP 7.4.2 不认 JDK 21。`gradle.properties` 里已把 `org.gradle.jvmargs` 调到 `-Xmx6144m`，
> R8 默认堆会 OOM。

**电视版**

```bash
cd Box
./gradlew :app:assembleArm64GenericNormalRelease
# 输出 app/build/outputs/apk/arm64GenericNormal/release/
```

**手机版**

```bash
cd Box-phone
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

---

## 前端自测

```bash
cd ui-design
node _verify_app.js      # 156 条断言 + 截图到 shots-app/
node _diag_api.js        # 接口配置页 18 项
```

`_verify_app.js` 用 Playwright 直接加载 `assets/phone/index.html`，mock 掉 `AndroidPhone` 桥。
**改 UI 后必须跑**，截图会输出到 `shots-app/`。

---

## 仓库里没有的东西

为了避免仓库膨胀到几个 G，以下内容**未入库**，需要时自己准备：

| 未入库 | 原因 |
|---|---|
| `*.jks` / 签名口令 | 安全。自己生成一个 keystore 即可打包 |
| `dist/*.apk` | 构建产物，见 Release 页 |
| `Box*/xwalk/*.zip` | 4 个 Crosswalk 预编译包共 208MB，只有 `python` 玩法模块用到（而那个模块本来就编不出来） |
| `build/` `.gradle/` `local.properties` | 构建缓存与机器相关配置 |
| `ui-design/shots*/` | 验证脚本生成的截图，跑一遍就有 |

---

## 声明

仅供学习交流，不提供、不托管任何影视内容。所有内容来自用户自行配置的第三方接口，
与本项目无关。请于下载后 24 小时内删除。
