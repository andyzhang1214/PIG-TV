# 项目长期记忆 · PIG-TV / 猪猪影视

## 构建与签名
- JDK：`/Users/andy/Library/Java/JavaVirtualMachines/ms-17.0.19/Contents/Home`（AGP 7.4.2 不认 21）
- `GRADLE_USER_HOME=/Users/andy/Workbuddy/wbWorkSpaces/tvbox/.gradle-home`（沙箱禁止删工作区外临时文件）
- keystore：`tv-release.jks`，alias `tvbox`，**口令 tvbox123456**，CN=TVBox（手机版/TV 版同一个，
  覆盖安装必须用它）
- 出包三步分开跑（串成一条命令会触发沙箱报错）：zipalign → apksigner（v1+v2+v3）→ verify
- 手机版变体：`assembleUniversalGenericNormalRelease`（推给用户首选）/`assembleArm64...`
- 产物一律放 `dist/`，文件名用中文：`猪猪影视-手机版-universal.apk` 等

## 代码约定
- 手机 UI 全在 `pig-tv-phone/app/src/main/assets/phone/{index.html,app.css}`，
  改完要重新 assemble（assets 打进 APK），联调用 `ui-design/_verify_app.js`（181 条断言），
  切源/切线路用 `_verify_switch.js`（18 条）和 `_verify_srcname.js`（10 条）
- 前端与原生只经 `PhoneBridge`（`AndroidPhone.xxx(args..., cbId)` → `__cb`），
  新桥方法要同时加 `@JavascriptInterface` 和前端 `ncall`
- 手机版/TV 版的 `ApiConfig`、`JarFetcher`、`SourceViewModel.getSort` 保持同一份实现（两边同步改）
- 首页空态必须区分三种：没配接口 / 接口连上但没内容（带原因+详情+重试）/ 真没内容
- 站名只能经前端 `applySourceName(name)` 改（空值不覆盖，同时刷 `#srcName` 和 `#siteHint`）；
  任何「切换片源/接口」的入口都要**先用回包落一次**，不能只靠 `loadAll` 的 config 回调

## 异步结果的串台问题（切线路/切接口必看）
- `SourceViewModel.sortResult` 只有一条 LiveData、`PhoneBridge.sortCb` 只有一个槽：
  「谁在等」和「结果是谁的」对不上号。旧请求的结果会落到新请求的回调上，
  新请求的真结果反而被丢 → 站名不变、首页空
- 解法：`getSort` 领递增代数 `sortGen`，只有当前代数的结果才准 `postSort`/`cacheSort`；
  换源时 `vm.cancelSort()`。改任何新的 LiveData 请求都要照这个套路来
- `list` 早就用 FIFO 队列（`listQ`/`hotQ`）解决同一类问题，可以对照看
- `config()` 有 35 秒看门狗，保证前端永远不会永远停在空态


## 已知的源站问题
- 肥猫系列接口（`http://肥猫.live`）在本机网络会被返回「上海反诈中心」警告页，
  DNS 与 DoH 结果一致 → 是源站按 IP/地区拦，不是本地 DNS 污染。
  **本机永远无法复现用户的下载问题，只能靠用户实测**
- spider jar 常放在海外小机房，慢且会断；不要用全局 OkGo 下（10s 超时 + h2），用 `JarFetcher`

## JarFetcher 的铁律（改它之前先读这段）
- 源站常见「一次连接只给六七十 KB 就 RST」。这**不是下不动**，是「放弃得太早」：
  续传一定要做成**分段 Range**（`bytes=a-b`，段长参考上次实际拿到的字节数），
  请求段一短连接就能在被打断前正常结束，于是每轮必有净进度
- 重试次数按「有没有进展」算（连续 10 次零进展才停）+ 总时长 210s 兜底，
  不要写成固定次数（曾经 `MAX_ATTEMPT=4` 直接导致「组件只下到 256/1024KB」）
- 失败时**残包必须保留**（`.part`），删了等于每轮从 0 开始；只有长度够了还 load 不起来才删。
  跨天残包要丢（站点可能换包，拼出来必然是坏的）
- 进度必须实时推给 UI（`Progress` → `PhoneBridge.bindJarProgress` → `window.__evt('jar')`），
  否则几十秒的白等会被当成卡死

## 混淆（踩过两次坑，别动这两条）
- `-keep class com.github.catvod.** { *; }`：宿主借给 jar 里爬虫的 API，按类名调用；
  只 keep crawler.* 会被 R8 改成 androidx.base.**，爬虫一调就 NoClassDefFoundError，
  而异常会被三层 catch 吞掉 → 用户只看到「接口没有返回任何分类」
- jar 的两条下载路径都要走 `JarFetcher`：`ApiConfig.loadJar`（配置主 jar）
  和 `JarLoader.loadJarInternal`（站点自带 "jar" 字段）
- 排查入口 `com.pigtv.api.SpiderDiag`（最后一次爬虫错误，前端空态显示）
