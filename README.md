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
