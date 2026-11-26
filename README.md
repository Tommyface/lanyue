# 澜阅 LanRead v0.4.16

开源安卓阅读器 · 自建骨架 + 兼容 Legado 书源生态（路线 B）

## 定位

- **本体零内置书源**：App 只做「阅读器 + 规则引擎 + 本地格式」，不预装任何内容站点（规避聚合盗版内容的版权风险，参考 2026 年 gedoor 清空、Kotatsu 关停等事件的教训）
- **兼容 Legado 书源 JSON 导入**：复用全网现有书源资产，格式与 legado（阅读）一致，用户可从社区可信合集自行导入
- **本地 TXT 完整支持**：编码嗅探（UTF-8/UTF-16/GB18030）、静态分页引擎、阅读进度持久化

## 技术栈

| 模块 | 选型 | 协议 |
|---|---|---|
| 语言/UI | Kotlin 2.0.21 + Jetpack Compose (Material 3) | Apache-2.0 |
| 存储 | Room 2.6.1（书架/书源/进度） | Apache-2.0 |
| 网络 | OkHttp 4.12 | Apache-2.0 |
| 规则解析 | jsoup 1.18.3 + JsonPath 2.9.0 | MIT / Apache-2.0 |
| 规则脚本 | Rhino 1.7.15（沙箱化，禁 Java 访问） | MPL-2.0 |
| 图片 | Coil 2.7 | Apache-2.0 |

全部依赖为 Apache-2.0/MIT/MPL 类宽松协议，无 GPL 传染。

## 视觉设计

取自「澜」（水波）与中文书卷的纸墨世界：

- **色**：墨青 `#1E3A47`（主）/ 澜水 `#3E7C8F`（辅，在线书标记）/ 宣纸 `#F6F3EA`（底）/ 竹青 `#4C7A5A`（本地书标记）/ 夜墨 `#101A1F`（暗色底）
- **字**：标题用平台衬线（宋体族，克制使用），正文用系统无衬线，页码与章节计数用等宽字（folio 传统）
- **签名元素**：书架为中文书脊形制——中文书名竖排于书脊，底端色带标注来源（竹=本地，澜=在线），无汉字标题自动回退横排
- **阅读主题**：纸 / 宣 / 竹 / 夜
- **图标**：宣纸底 + 墨青书脊 + 澜水波

## 功能（v0.4.11）

1. **书架**：本地 TXT 导入（SAF）、在线书籍收藏、长按移除、阅读进度自动保存
2. **书源管理**：粘贴 JSON / URL 导入 Legado 书源、启停、删除、按 URL 去重
3. **聚合搜索**：并发搜索全部启用书源、聚合结果展示、一键加入书架
4. **书详情**：简介/封面字段规则提取、目录列表、续读定位（滚动到已读章节）
5. **阅读器**：
   - 静态分页引擎（StaticLayout 逐段测量，真实屏宽）
   - 左 1/3 屏上一页 / 右 1/3 屏下一页 / 中部呼出设置
   - 字号 12–32sp 调节、四色主题（白/米/绿/黑）、设置持久化
   - 页级阅读进度持久化与恢复（换字号后自动裁剪到当前页数）
6. **听书（系统 TTS）**：按中文句末标点切句入队，本页读完自动续读下一页；翻页/切章自动停止
 7. **备份与同步**：
   - 本地 JSON 备份导出/导入（书架 + 书源 + 进度，覆盖式）
   - WebDAV 云备份/恢复（Basic 认证，内置极简客户端：PUT/GET/MKCOL）
   - 设置页可视化配置服务器 URL/用户名/密码，支持连通性测试
 8. **订阅源（RssSource）引擎（v0.3.0）**：
   - Legado 订阅源 JSON 导入（粘贴 / URL）
   - `sortUrl` 多分组浏览（书源/订阅源聚合站等），`{{page}}` 翻页
   - 文章正文 WebView 渲染（articleStyle 1 自定义 HTML+JS，含源内 style CSS）
   - `jsLib` 全局库：`java.toast`、`cache.getFromMemory/putMemory`、`source.getVariable()`，支持镜像测速选优（bhost 模式）
   - `legado://import/auto?src=...` 一键导入闭环：正文内点击直接判别书源/订阅源入库
   - 规则增强：负索引（`span.-1`）、无前缀标签名（`a/p/h2`）、`{{规则}}`/`{{JS}}` 自动分流
   - JS 沙箱：ClassShutter 白名单仅 jsoup/java.lang/java.util，阻断任意类访问
 9. **漫画源支持（v0.4.0）**：
   - 漫画书源（bookSourceType=2）导入与搜索，书架以墨青色带标记
   - `<js>...</js>` 两段式规则：JS 结果作为新上下文再求值剩余规则（如 `$.list`）
   - jsLib 全量执行环境：多语句脚本直接执行取完成值；`baseUrl`/`key`/`page` 变量注入
   - JS 桥 `java.put/get`（源变量持久存储）、`source.put/get`、AES/MD5/Base64/GZIP 等加密与网络类沙箱开放
   - ruleBookInfo.init 初始化规则（详情页 JS 拉取 API 数据）
   - 漫画阅读模式：正文 `<img>` 列表解析 + Coil 纵向连续滚动（条漫体验）
 10. **源变量 / 登录页（v0.4.1）**：
   - 书源卡片的设置图标打开源变量对话框
   - 渲染 loginUi 控件：按钮执行 loginUrl 定义的 JS（漫蛙线路[1]~[5]/自动选线/查看当前线路/恢复默认），text/password 输入框存变量
   - 全局变量存查看 / 新增 / 删除（jsLib 脚本经 java.get 读取）
   - java.toast 执行反馈 + 脚本返回值展示
 11. **发现页（v0.4.2）**：
   - exploreUrl 入口解析：`@js:` 动态生成（执行 JS 取返回 JSON 数组，如漫蛙按 tag API 动态生成全部/3D/CG…分类）或静态 `名称::URL` 行
   - 分组标签横滑切换，`{{page}}` 翻页
   - 复用 ruleExplore 规则链（<js> 两段式 + $.list）拉取列表
   - 点卡片直接入书架并进入书详情；多源切换下拉
   - 书架顶部新增发现入口图标
 12. **订阅源变量桥接（v0.4.3）**：
   - RssRepository 切 LanJs 全局桥：书源/订阅源共享同一变量存与 cache
   - `source.getVariable()` 修读回 bug（setVariable 写入后可读）
   - 订阅页新增源变量入口：搜索关键词（即 `keys={{source.getVariable()}}`）+ 全局变量增删改
  13. **发现页封面与书架去重（v0.4.4）**：
    - 发现页卡片渲染封面（Coil 异步图，无封面/加载失败回退书名首字底纹）
    - 书架去重：同源+同内容 URL 已在架则不再重复入库（搜索/发现两路均生效）
  14. **导入体验修复（v0.4.5）**：
    - 书源/订阅源导入文本框限高 + 框内可滚动：粘贴超长 JSON 不再把导入按钮顶出屏幕
    - 支持 Legado 标准顶层 `searchUrl` 字段（此前仅读 `ruleSearch.searchUrl`，标准书源搜索会静默失败）
  15. **搜索/发现页链路修复（v0.4.6，真机反馈）**：
    - JS 沙箱白名单扩容：放行 `java./javax./android./okhttp3./okio./sun.net.` 前缀（保留 Runtime/ProcessBuilder 等进程级拒绝）——旧白名单拦截了 HttpURLConnection 的 OkHttp 实现类，导致漫蛙等源的 jsLib 内置网络请求全部失败
    - 规则阶段 `baseUrl` 改传实际请求 URL（旧版传书源首页），漫蛙 bookList 从 baseUrl 提取 `k=`/`/api/` path 的源恢复正常
    - Rhino 改解释模式（optimizationLevel=-1），Android 兼容性优先
    - 单源搜索限时 90s、发现页入口解析限时 120s：弱网下线路探测不再永久卡 UI
    - 模板化 URL 请求失败不阻断（JS 自管请求的源依赖 bookList 脚本自行拉取）
    - 订阅源导入防呆：粘贴书源 JSON 时提示去书源页导入
   16. **发现页/订阅源列表可靠性与诊断（v0.4.7，真机反馈）**：
    - **explorePage 不再因入口页请求失败整页放弃**：实测漫蛙 API 拒绝无 Devid/X-Token 的裸 GET（返回 501）——旧版遇此直接返回空列表，而漫蛙列表本就由 bookList 的 `<js>` 脚本带 App 私有头自行拉取，body 允许为空
    - 订阅源 loadGroup 同步降级：请求失败继续走空 body 解析，不再直接断路
    - 目录/正文/详情的非 JS 分支 Http.get 容错：弱网页面不再抛异常中断
    - 空页原因透出：入口请求失败 / exploreUrl 脚本无返回等具体原因直接拼进页面提示（取代笼统的“规则不适用、站点不可达或已到末页”）
   17. **真机沙箱与相对 URL 修复（v0.4.8，真机反馈）**：
    - **JS 沙箱改黑名单制**：前缀白名单枚举不完 Android 系统实现类（真机 HttpURLConnection 实现是 com.android.okhttp.*，TLS 在 conscrypt，socket 走 libcore.io）——一漏网络就全挂。现仅拒绝 Runtime/ProcessBuilder/Process/ClassLoader/SecurityManager 与 dalvik.system.*（动态加载），其余放行（书源 JS 本就由用户自行导入，README 已声明风险自担）
    - **相对 URL 全链路补全**（Http.absUrl）：源内 sortUrl/bookUrl/入口 URL 为相对路径（如 /yuedu/...）时自动拼上源地址，不再抛 Expected URL scheme
    - discoverPage 入口请求失败降级 + 原因透出修复上一版未落地的漏洞；SearchHit.bookUrl/coverUrl 与入书架前均补全
   18. **稳定性与网络兼容（v0.4.9，真机反馈）**：
    - **全局崩溃日志**：未捕获异常堆栈自动落盘（files/crash/，保留最近 5 份），设置页「诊断 → 查看最近崩溃日志」可查看与复制——真机闪退不再盲修
    - **列表去重**：搜索/发现页结果、目录章节均按 URL 去重，消除 Compose key 冲突闪退（同页重复书籍/重复章节时必崩）
    - **HTTP/1.1 + 连接级重试**：部分 CDN/防护对 HTTP/2 或非浏览器 TLS 指纹直接 RST（真机 Connection reset），强制 1.1 并自动重试一次
    - **源 header 字段实现**：书源/订阅源自带请求头（UA/Referer 等）现随源请求发出，反爬场景不再裸连
   19. **ES6 语法与 JSON 上下文提取修复（v0.4.10，真机反馈）**：
    - **Rhino 开 ES6 语言版本**：yck 等源 jsLib 用 `const { java, cache } = this` 解构赋值，ES5 解析直接报 missing : after property id → bhost() 无法执行 → URL 回退到已死域名 → “站点不可达(Connection reset)”。实测 ES6 版本下 bhost() 完整跑通并测速选出活镜像（yck.email）
    - **JSON 字符串上下文的裸字段名提取**：ruleBookInfo.init 的 JS 返回 JSON 字符串时，name/author/coverUrl/tocUrl 等裸字段名此前全部提取失败（只支持 Map/List 上下文）→ tocUrl 回退到详情 URL（无 book_id）→ 漫蛙目录为空的直接根因。现 String 且以 { 或 [ 开头自动按 $.key 解析
   20. **列表卡片重设计 + 图片加载器（v0.4.11）**：
    - 发现页/搜索结果卡片按主流阅读器样式重设计：方形圆角封面（裁剪填充）、标题、作者行、**连载状态标签**（kind 首段：红系“连载中”/主色系“完结”）、两行简介
    - SearchHit 新增 kind 提取（ruleExplore/ruleSearch 的 kind 字段，$$ 分隔连载状态与分类）
    - **Coil 全局图片加载器**：专用 OkHttp（HTTP/1.1 + 浏览器 UA + 信任自签证书）——书源图床常为 IP 直链自签证书（如漫蛙 52.128.242.34:20608），默认主机名校验必失败；图片仅显示不执行，按 Legado 同等策略
   21. **源仓库可靠性与书架重设计（v0.4.12/v0.4.13，真机反馈）**：
    - **预览模式**：点搜索/发现结果不再自动入库，详情页「加入书架」由用户决定
    - **源仓库页**：书源页顶栏入口，浏览 yck 类订阅源的书源分组，点击条目一键导入（提取 legado://src）
    - **中缀 @ 子选择**：`.layui-card-body@.ylist` 类列表规则不再丢段，条目错位/提取空导致导入 URL 残缺的问题修复
    - **订阅源镜像降级重试（v0.4.13）**：实测 yck.email 为 HEAD 200 伪活镜像（GET 深路径回首页壳页）——分组解析 0 条时自动从 jsLib 提取候选域名逐个换 host 重试（yckceo.com 可救回）
    - **singleUrl/无分组订阅源兼容**：无 sortUrl 的源自动兑底「首页」分组（如源仓库官方纯净入口）
    - **JS 异常透出**：详情 init 空输出时区分“脚本异常：具体异常”与“返回空（线路不可达）”，拼进目录为空提示
    - **书架重设计**：单列列表（左 3:4 圆角封面 + 右书名/作者/已读进度/最新章节/两行简介）
   22. **前导 @ 规则兼容与镜像纠正（v0.4.14，真机反馈）**：
    - **前导 @ 规则剥离**：`@a@href` / `@span.-2@text`（yck 订阅源 ruleDescription/rulePubDate 的实用写法）此前提取为 null → 一键导入 URL 残缺（`/yuedu//json/id/.json`）与时间行空白；实测修复后导入 URL 拼出完整 `/yuedu/shuyuan/json/id/8165.json` 且返回有效书源 JSON
    - **镜像重试后纠正 bhost 缓存**：重试成功的镜像写回 JS cache 的 working_host，renderArticle 里 `{{bhost()}}` 生成的一键导入 URL 指向真正服务内容的镜像
    - **evalJs 捕获 Throwable**（StackOverflowError 等 java.lang.Error 此前静默返回 null）+ 空完成值单独记录——真机诊断不再盲区
   23. **整段 @js: 规则与 Legado key 语义（v0.4.15，对照 OmoFun/8100 实测）**：
    - **列表规则支持整段 `@js:`**（OmoFun bookList 为 `@js:(function(){...})()` 完整 IIFE）：返回 java List/JS 数组自动拆条目（旧版 toString 变乱码）；搜索 URL 也支持整段 @js:
    - **key 传原始关键词**（Legado 语义）：JS 脚本内 `String(key)` 拿原始值自行编码（OmoFun `encodeURIComponent(key)`，旧版预编码双重编码必失败）；模板 `{{key}}` 替换时百分号编码
    - **JS 返回值二次模板替换**：@js: 脚本返回值里保留的 `{{page}}` 等占位符继续由模板系统替换（实测 searchUrl 拼出 `/search/海盗----------1---.html`）
   24. **整段 JS 规则短路（v0.4.16，真机锤死）**：
    - **根因**：`getString()` 对规则做 `||`/`&&`/`##` 切分——JS 代码里遍地 `||`（`String(result||'')`、`j.data||j.list`），一切分脚本就碎成残段 → Rhino 报 `EvaluatorException: missing ) after argument list`（真机漫蛙/OmoFun 详情 init 均中招，即“目录为空”真因）
    - **修复**：`<js>` / `@js:` 开头的整段 JS 规则跳过切分与模板分流，直接整体执行——JVM 实测 8066 (OmoFun) jsLib+init 整段跑通返回完整 JSON，切分版复现真机报错
   25. **兼容的 Legado 书源规则子集**：
   - `@css:` 前缀选择器（含 `@attr` 后缀、`.index` 索引）
   - `$.` / `@json:` / `[` 开头的 JSONPath
   - `@js:` 脚本与 `{{...}}` 模板（Rhino 求值）
   - `class./tag./id.` 默认语法 + 数字索引 + `@attr`
   - `##regex[##replace]` 净化规则、`&&` 并列、`||` 兜底
    - 暂不支持：XPath（`//`）、`<js></js>` 标签、对象型规则、POST 搜索

> 关于上一条：`<js>...</js>` 两段式列表规则（v0.4.0 起在 bookList/chapterList/content 已支持），不支持的是任意字段两段式

## 构建

因 AGP 拒绝含非 ASCII 字符的项目路径，本项目实际构建于 `D:\lanread-build`（纯 ASCII）：

```
D:\lanread-build\
  build.ps1              # 一键构建脚本（设 JAVA_HOME/ANDROID_HOME 后调 gradle）
  build-env\             # JDK 21 + Android SDK (34) + Gradle 8.9 外置工具链
  LanRead\               # 项目源码
    local.properties     # sdk.dir=D:/lanread-build/build-env/android-sdk
    app\release.keystore # 自签名密钥（alias=lanread，密码见 build.gradle.kts）
```

构建命令：
```
powershell -File D:\lanread-build\build.ps1            # debug + release
powershell -File D:\lanread-build\build.ps1 release    # 仅 release
```
产物：`app/build/outputs/apk/{debug,release}/`，release 以 `app/release.keystore` 签名（CN=LanRead,O=LanRead,C=CN）。

## 合规声明

- 本应用不提供、不内置、不分发任何书源与内容；所有内容入口由用户自行导入第三方书源
- 书源规则内含可执行 JS，请仅导入可信来源；本项目对书源行为不背书
- 仅供个人学习研究使用，请遵守所在地法律法规

## 路线图

- [x] M0 本地阅读 MVP（TXT + 分页 + 主题 + 进度）
- [x] M1 精简（书源导入 + 规则引擎 + 聚合搜索）
- [x] M2a-c：系统 TTS 听书、本地 JSON 备份、WebDAV 云同步（v0.2.0）
- [x] M2e 订阅源（RssSource）引擎：分组浏览 + WebView 正文 + 一键导入闭环（v0.3.0）
- [x] 漫画源支持：<js> 两段式规则 + jsLib 加密类沙箱 + 漫画阅读模式（v0.4.0）
- [x] 源变量/登录页 UI：loginUi 控件渲染 + 变量增删改（v0.4.1）
- [x] 发现页：exploreUrl 动态/静态入口 + ruleExplore 列表 + 翻页（v0.4.2）
- [x] 订阅源变量桥接：LanJs 全局桥 + source.getVariable() 搜索关键词可维护（v0.4.3）
- [x] 发现页封面渲染 + 书架去重（同源+同 URL）（v0.4.4）
- [x] 导入文本框限高滚动 + 顶层 searchUrl 兼容（v0.4.5）
- [x] 沙箱白名单扩容 + baseUrl 实际 URL + 搜索限时（v0.4.6，漫蛙源全链路修复）
- [x] 发现页/订阅源列表 Http 降级 + 空页原因透出（v0.4.7，漫蛙 API 拒绝裸 GET 的 501 场景）
- [x] 沙箱改黑名单制 + 相对 URL 全链路补全（v0.4.8，真机 com.android.okhttp 拦截与 /yuedu 相对路径）
- [x] 崩溃日志落盘 + 列表去重 + HTTP/1.1 重试 + 源 header 支持（v0.4.9）
- [x] Rhino ES6 语言版本（解构赋值）+ JSON 字符串裸字段名提取（v0.4.10，漫蛙目录为空与 yck Connection reset 根因）
- [x] 卡片重设计（状态标签/简介/方圆角封面）+ Coil 图片加载器（v0.4.11）
- [ ] M2d：WorkManager 定时追更提醒
- [ ] M3：EPUB 支持、插件仓库协议

> AI生成
