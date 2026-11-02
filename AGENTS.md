# AGENTS.md — 刷了个题 (ShuaGeLeTi) 项目记忆

> 本文件是项目的完整上下文记忆，供任何模型/Agent 无缝接手开发。最后更新：v1.0.9（2026-11-02）。

## 项目一句话

Android 离线刷题应用「刷了个题」（包名 `com.examhelper.shua`）：导入 docx/PDF/扫描件/照片题库 → OCR → LLM 结构化解析 → 四种刷题模式 + 测试模式，支持多模型交叉验证（"三省六部"）、斩题、错题本、统计热力图。

- 仓库：https://github.com/Naatsumeee/ShuaLeGeTi （公开；曾用名 1023-exam-helper）
- 主分支 `main`，直接推送（单人项目，无 PR 流程）
- 用户 Windows 机器，Git Bash 环境，中文交流

## 开发环境（本机已装好）

| 项 | 路径/版本 |
|---|---|
| JDK | `C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot`（构建前 `export JAVA_HOME` 指向它） |
| Gradle | 8.9 wrapper（镜像：腾讯 `mirrors.cloud.tencent.com/gradle`，已写入 wrapper properties） |
| Android SDK | `C:\Users\xutia\AppData\Local\Android\Sdk`（platform 35 / build-tools 34 / NDK 27.0.12077973 / CMake 3.22.1） |
| 构建 | `./gradlew assembleDebug`（依赖走阿里云镜像 + google/mavenCentral，见 settings.gradle.kts） |
| 模拟器 | AVD `shua_test`（Pixel6/API35），启动脚本 `启动模拟器.bat`（幂等：已在跑则直接装根目录 APK；按设备 ABI 自动选包） |
| 手机 | adb 设备 `e87a3fe4` |
| 凭据 | GitHub 账号 Naatsumeee（Windows 凭据管理器存 PAT，`git credential fill` 可取；gh CLI 已装，`GH_TOKEN=<token> gh ...` 可用） |

## 技术栈与关键决策

- Kotlin + Jetpack Compose（BOM 2024.12.01）+ Material3；MVVM（ViewModel 用 @JvmOverloads/SavedStateHandle 构造，无 DI 框架，`ShuaApp.instance.container` 手工容器）
- Room v4（`data/db/`：Entities/Daos/AppDatabase，含 1→2→3→4 迁移，**禁用破坏性重建需谨慎**：fallbackToDestructiveMigration 仍在，新增字段必须写正式迁移）
- 双 OCR 引擎（`data/ocr/`）：ML Kit 中文 bundled（默认）+ RapidOCR（PP-OCRv5 ONNX）；OCR 后做**双栏阅读顺序重排**（`MlKitOcrEngine.rebuildReadingOrder`，扫描试卷是左右双栏）；RapidOCR 额外跑**版面分析**（见「版面分析」节）
- RapidOCR 为**源码模块** `rapidocr-src/OcrLibrary`（Apache-2.0，已入库 ~93MB）：NDK r27 + `ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON` + onnxruntime **1.20**（16KB 页对齐，Maven 的 AAR 抽取）+ opencv-mobile 3.4.15（`src/sdk/`）。**16KB 页对齐是硬要求**（用户手机是 16KB 设备），换依赖时必须验证 PT_LOAD p_align=16384
- 后台任务 = WorkManager 前台服务（dataSync 类型）：`ImportPipelineWorker`（导入/解析，断点续传按"已落盘分块"）与 `GenerateWorker`（AI 生成，断点续传按"已答即跳过"）；进度写 Room 表（import_jobs/generate_jobs），UI 观察 Flow
- LLM：`data/llm/LlmClient.kt`（OpenAI 兼容 chat/completions，jsonMode 失败自动降级重试，max_tokens 支持）；**系统提示词集中**在 `data/llm/Prompts.kt`（用户可在设置页覆盖，稳定前缀为命中 DeepSeek 前缀缓存——命中价是未命中 1/50）
- 答案生成：`data/generate/AnswerGenerator.kt` 批量 12 题/请求 × 3 批并发；三省六部=主部高权重 0.6 + 一致率 0.4，分歧存 dissent；**与题库原答案冲突时**原答案存 `Question.originalAnswer` 做题页双展示
- 解析：AI（`LlmQuestionParser`，分块交 `ChunkPlanner` 按题目边界切分 + 重叠窗口，页码标记 `[[PG:n]]` 贯穿到 `Question.sourcePage`）+ 离线正则兜底（`TextQuestionParser`）；`OptionNormalizer` 合并重复选项字母并重映射答案（OCR 重复 B 类问题的修复）
- 判分：`StudyRepository.judgeAnswer`（伴生对象，字母优先+选项内容兜底+文本归一化）
- UI 主题：**Anthropic 暖纸 + 苹果液态玻璃 v2**（`ui/theme/Theme.kt` 色板与排版 + `ui/theme/Glass.kt` 玻璃组件与安全区扩展）。双套色板 `PaperPalette`（日间）/`OlivePalette`（夜间）：画布暖中性 Parchment、表层 Ivory、主色 Terracotta `#D97757`、错误 Sage `#B53333`、标注 Amber `#D97706`、边框 CreamLine `#F0EEE6`（夜间 `#30302E`）。字体：标题/大数字衬线（`serifTitle`，Georgia/Noto Serif CJK，字重 500），正文无衬线行高 1.6–1.75，数据等宽（`monoNumber`）。玻璃拟态统一走 `GlassSurface`/`PaperCard`/`CompactTopBar`/`FloatingDock`（backdrop blur 16dp + 半透明白内衬 + 高光边 + 内发光倒角）。
  - **安全区硬规范**：每页根容器必须 `.safeTopPadding().safeBottomPadding()`（`Glass.kt` 里的 Modifier 扩展）。Android 15 edge-to-edge 下自绘 Surface 不会自动避让状态栏/手势条，漏写就会被安卓状态栏或导航条盖住。
  - 一级导航是**底部悬浮玻璃胶囊 Dock**（4 tab），距底 `max(14dp, 手势条安全区)`；tabs 页面的 `contentPadding` 由 `AppRoot` 传入 `top = 状态栏高度`。
  - 暗黑模式由用户在设置页决定（`SettingsStore.darkMode`：FOLLOW/LIGHT/DARK），**不再跟随系统**；`MainActivity` 读设置后传给 `ShuaTheme(darkTheme = ...)`。
  - **切题是跟手左右连续卡片**（v1.0.8，`StudyScreen.kt` 的 `PeekSlot`/`PeekPage` + 拖拽偏移量）：拖动时当前卡跟手平移、左右邻卡露出边缘，松手按阈值/速度吸附到上一题或下一题。三条硬约定：
    1. **邻卡排版必须与真卡逐像素同构** —— `PeekPage` 直接复用 `QuestionBody(q, peekState(...), vm, compact, interactive = false)`，内边距/字号/行距/选项行高全部共用；**绝不能再手写"降级预览"**（v1.0.8 之前就是这么写的：选项 9dp 内边距、题干最多 6 行、纯文本两行，滑入落地时整页文字间距"由小到大跳一下"）。
    2. **邻卡 interactive = false**：`OptionRow(enabled = interactive && !state.answered)`、输入框 `readOnly`、`AnswerCard` 只在 `state.revealed && interactive` 时画；否则会误改当前题状态、或在背诵模式下提前泄题。
    3. **当前卡的投影是随位移自 0 升到 2.5dp 的暖色淡影**（`graphicsLayer { shadowElevation = 2.5f * prog * density; ambientShadowColor = shadowTint; spotShadowColor = shadowTint }`），不是旧的 `shadow(20.dp)` 硬黑边——用户明确反馈 20dp 一离手就"糊上一圈黑边"太突兀。
    4. 动画只播一次：`onDragStopped` 里 `animateTo(±w, tween(150))` 滑出 → 同一帧把 `snapCur` 换成目标题 + `frozen = true` + `drag = 0`，**不要再让 LaunchedEffect(pageKey) 从 ∓w 弹回 0**（那是"动画播两遍+中间闪一下"的根因）。
- **本轮完成页**（`FinishedView`）：「返回」是主按钮、「刷本轮错题（N）」进独立子会话（`brushRoundWrong`，用 `roundFilterKey()` 的独立进度指纹，刷完点「完成」只清子进度不 reload）。
- 做题/测试页刻意压成一屏：`CompactTopBar` 48dp + 题干/选项紧凑卡 + 底部固定控制条，避免反复滚动。

## 问 AI（`ui/study/AskAiPanel.kt` + `StudyViewModel`）

- 做题页**常驻入口两处**：`CompactTopBar` 右侧 `AskAiButton`（小屏自动收成纯图标）、解析区 `DeepSolveRow` 里的「追问 AI」。无参考答案的分支**不能提前 return**，否则连深度解题入口都没了。
- 上下文 = `Prompts.ASK_DEFAULT` 系统提示 + 题目全文（题干/选项/参考答案/解析/当前作答）+ 已有对话轮次；`llmClient.chatWithMessages(jsonMode=false, maxTokens=1500)`。
- 默认走 `providerDao.getPrimary()`，没有主部时退化为第一个启用中的模型；两者都没有 → "尚未配置 AI 模型，请到设置页添加"。
- 对话状态只在内存（`StudyUiState.chat`），**切题即清空**（`reload` 重建 state）；请求途中切题用 `askedForId` 守卫，回答不会落到新题上。
- 深度解题成功后**不再自动弹面板**（v1.0.7 起）：解析刚生成要先读，追问入口常驻在解析下方 `DeepSolveRow`。`applyDeepSolve` 返回的 `updated` 必须回写 `questions` 与 `session.question`，否则页面仍显示旧解析；回填前用 `stillHere = session?.question?.id == q.id` 守卫，避免请求途中切题被旧结果覆盖。
- **流式逐字输出**（v1.0.8）：`LlmClient.chatStream(...)` 走 SSE（`data:`/`data: [DONE]`，解析在 `data/llm/SseParser.kt`），深度解题与问 AI 都边生成边刷新到 UI（`StudyUiState.deepStreaming` / `chat` 的最后一轮），流断了会自动重试一次；非流式 `chatWithMessages` 仍保留给批量生成等场景。
- 深度解题失败会**带退避重试 3 次**（0 / 900 / 2200 ms），只对配置/鉴权/参数类错误立即放弃（`isRetryable`）；`max_tokens = 4096`（`DEEP_SOLVE_MAX_TOKENS`），`ChatResult.truncated`（finishReason=="length"）且正文为空时判失败重试，**绝不把半截解析写库**。

## 公式与 Markdown 渲染（`ui/theme/Latex.kt` + `ui/theme/Markdown.kt`，v1.0.6）

- 两套渲染器**都是自研、零第三方依赖**。调研过 `ru.noties:jlatexmath-android`（GPL+CE、只出 AndroidView）与 `io.github.huarangmeng:latex-renderer`（MIT 但要求 compileSdk 36），都不合用。
- `Latex` 双路径：**行内**（`$x^2$`）走 `annotate()` 压平成一条 `AnnotatedString`（BaselineShift 上下标、分式压 `a/b`、根式 `√(x)`），与中文同处一行可自然换行；**行间**（独占一行的 `$$..$$`）走 `LatexView` 真布局节点（`Column(width=IntrinsicSize.Max)` + `HorizontalDivider` 做分数线、根号横线罩住被开方数、大运算符上下限归位、`cases/matrix/aligned` 成组）。
- 对外入口 `Latex.LatexText(text, style, color, maxLines, allowDisplay)`。**`allowDisplay = false` 是选项/预览/单行场景的必填项**：否则选项里出现 `$$` 会被当成行间公式，把整张卡片撑成两行并居中错位。
- `Markdown.MarkdownText(text, style, color, tight)` 支持标题/粗斜体/行内代码/引用/有序无序列表/表格/分隔线，内部把公式段落转交 `Latex`。所有展示 AI 输出的地方（解析、深度解题、问 AI 回答、错题本详情、测试报告）都走它。
- **列表预览不要按字符 `take(n)` 截断**——会把 LaTeX 命令从中间切断渲染成乱码；改成完整传入 + `maxLines` 由排版层省略。

## 版面分析（`data/ocr/LayoutAnalyzer.kt` + `rapidocr-src/.../LayoutNet.cpp`，v1.0.9）

RapidOCR 识别一页分两步：**先版面分析，再识别文字**。模型是 PP-DocLayout-S（4.7MB ONNX，`app/src/main/assets/pp_doclayout_s.onnx`），ONNX Runtime C++ 侧跑（`LayoutNet`，letterbox 保比例到 480×480，raw 输出恒 100 行，先按 score 过滤再 NMS）。ML Kit 引擎没有版面模型，永远拿 `PageLayout.NONE`。

- **栏缝绝不能由版面模型算**（决定性实测结论）：PP-DocLayout-S 是**段落粒度**检测器，双栏页常被并成横跨全宽的大框，缝里全是权重 → `columnCuts` 由版面模型返回恒为空。栏缝改用**OCR 行块垂直投影**（`LayoutAnalyzer.withGutters(layout, boxes, pageWidth)` → `detectGutters`），粒度细、原图坐标才找得到真正中缝。所以 `RapidOcrEngine.recognize` 的顺序是 `analyze()` → `engine.detect()` → `withGutters()` → `rebuildReadingOrder()`。
- `detectGutters` 细节：按每块覆盖的 x 区间累加**高度加权**的投影直方图；**横跨超过 0.72 页宽的块不参与**（`SPANNING_SKIP_RATIO`，取 0.5 会把整栏正文当通栏丢掉，实测踩过）；只在页宽 0.26~0.74 找；走廊宽度需 ≥ 页宽 2%（`MIN_GUTTER_RATIO`）；还要求左右两侧块数覆盖 ≥ 某比例才认。
- `columnOf(box)` / `columnAt(x)` **按左边界判栏**（`columnAt(box.left)`）。早期用右边界，跨栏标题的右边界落在第二栏会被误判成右栏内容。
- 版面模型仍然有用：页眉页脚（`header/footer/header_image/footer_image/number`）、表格、公式的定位靠它。**页眉页脚只在页面上下 12% 内才算数**——模型常把正文里的「一、名词解释」误判成 header，不加这条会删题干。
- `PageLayout.NONE` 是可选降级：assets 缺模型或 native 加载失败时 `layoutEngineAvailable = false`，自动退回旧的双栏启发式（块在页宽一半哪一侧），不影响出题。
- **坐标全用自研 `Box`**（`data/ocr/Box.kt`，left/top/right/bottom Int + centerX/centerY/width/height + `Box.of()`），不用 `android.graphics.Rect`：单测是纯 JVM（无 Robolectric），`LayoutReadingOrderTest` 14 个用例全绿。
- 实测（模拟器 API35）：合成双栏页 1240×1753（通栏标题 + 左 3 题 + 右 3 题，中缝 560..660）日志 `analyzed=true cols=2 cuts=[549]`，重排后左栏三题连排、右栏三题连排、标题在最前 —— 顺序正确。

## 本地文件存储规范（**硬要求**）

- 唯一入口 `data/storage/AppStorage.kt`，全工程不要自己 `new File(context.cacheDir, "...")` 拼路径。
- **绝不写 `/sdcard`、`/storage/emulated/0`、`Environment.getExternalStorageDirectory()`**；不申请 `WRITE_EXTERNAL_STORAGE`（只保留读图片权限用于选图）。
- 目录布局（全部在应用私有 `cacheDir` 下，一用途一子目录，根目录不堆文件）：
  `cache/import/`（OCR 原文 `raw_<jobId>.txt` + 分块 `parsed_<jobId>.jsonl`，断点续跑依据，保留 7 天）、
  `cache/temp/`（PDF 落地等一次性文件，用完即删，保留 2 小时）、
  `cache/temp/camera/`（相机输出，**唯一**对 FileProvider 暴露的目录）、
  `cache/sfx/`（合成音效 wav）。
- 用户要拿走的成品（题库导出、学习记录导出）一律走 **SAF**：`ActivityResultContracts.CreateDocument` + `contentResolver.openOutputStream(uri)`，落点由用户决定（`LocalDataRepository` / `BankViewModels` / `SettingsScreen`）。
- `res/xml/file_paths.xml` 只放行 `temp/camera/`，**禁止**写 `<cache-path path="."/>`（等于把整个缓存授权给任意应用）。
- `AppStorage.cleanupStale()` 在 `Application.onCreate` 里跑：按保留期清理上述三个目录，并顺手删掉旧版本遗留在 `cacheDir` 根目录的 `import_raw_*`/`ingest_*`/`camera_*`/`sfx_*` 前缀文件。

## 页面地图（ui/）

`AppRoot.kt` 全部路由。banks/wrongbook/stats/settings 四 tab；bank/{id} 详情（覆盖率+模式入口+导出对话框）；study_setup（多维筛选：章节/题型/A1A2A3B/作答状态）；study/{bankId}/{mode}?filter=（四种模式+答题卡+斩题+冲突展示）；exam（测试模式+答题卡）→exam_report/{runId}；generate/{bankId}（AI 生成+token 用量展示）；editor/{bankId}（按页码/题号批量分章+单题编辑）；import_file（OCR 引擎选择+后台进度+日志+预览）；import_text（粘贴）；search。

## 已知问题/注意

- ML Kit `text-recognition-chinese:16.0.1` 上游尚无 16KB 对齐版本（用户设备警告里可能残留它，等 Google 更新后升版本号即可）
- 中转 API 可能不透传缓存字段 → token 命中显示 0；已做 OpenAI `prompt_tokens_details.cached_tokens` 回退解析
- DeepSeek 模型名现为 deepseek-flash / deepseek-v4-pro（旧 deepseek-chat 已下线）
- APK 体积：debug 全 ABI ~190MB；v1.0.0 起启用 **ABI splits**（arm64-v8a / armeabi-v7a / x86_64），release 用 debug 签名（未配置正式签名）
- **APK 一律不入库**（v1.0.4 起）：`/app-*.apk` 已被 `.gitignore` 忽略，分发只走 GitHub Releases；但 `启动模拟器.bat` 仍读根目录 APK，所以本地构建后要手动 `cp app/build/outputs/apk/debug/app-<abi>-debug.apk .`
- 样例题库在 `QuestionBankExample/`（gitignore，版权资料）；`app-debug*.apk` 也 gitignore
- **仓库体积**：v1.0.9 时代仓库已有 167MB（GitHub 上），其中约 290MB 历史 blob 是早期误跟踪的 APK（x86_64/arm64/v7a 三包）与 `app/libs/OcrLibrary-1.3.0-release.aar`。这些已从工作区移除并写入 `.gitignore`，但**仍在历史里**——若要真正瘦身需 `git filter-repo` 重写历史 + 强推（会改变所有 commit hash）。**用户已决定「先不动历史，保持现状」，不要再提重写**。当前入库大文件只有内置模型与预编译 onnxruntime.so（合计 74.9MB，构建必需，不可删）

### 仓库卫生（v1.0.9 整理后）

- `.gitignore` 已在 v1.0.9 后重写成分组版本（版权资料 / 构建产物 / 本地凭据 / Gradle 缓存 / 测试环境 / 编辑器系统），带「单文件 < 50MB，仓库 < 200MB」红线注释。跟踪文件 135 个、76MB，与规则零冲突。
- **克隆后无法直接构建，必须先补两份被 `.gitignore` 挡住的第三方原生依赖**（`rapidocr-src/OcrLibrary/.gitignore:5` 与 `:7`，历史上从未入库）：
  - `rapidocr-src/OcrLibrary/src/sdk/` ← opencv-mobile 3.4.15（43MB，含 `native/jni/OpenCVConfig.cmake` 与 `native/staticlibs/{arm64-v8a,armeabi-v7a,x86_64}/*.a`）。`cpp/CMakeLists.txt:14` 用 `set(OpenCV_DIR "${CMAKE_SOURCE_DIR}/../../sdk/native/jni")` + `find_package(OpenCV REQUIRED)`，缺失即 `FATAL_ERROR "opencv Not Found!"`。
  - `rapidocr-src/OcrLibrary/src/main/onnxruntime-shared/` ← onnxruntime 1.20 头文件（`ORT_API_VERSION 20`）+ 各 ABI 的 `OnnxRuntimeConfig.cmake`（把 `jniLibs/<abi>/libonnxruntime.so` 包成 IMPORTED target），1.6MB。
  - 这两块**不能删**（构建必需），也**没入库**（体积考虑）。README「原生依赖准备」节已写明来源。
- 远程遗留分支 `UIStyleUpdate`（`686c539`）已完全合入 main，可安全删除。


## 常用命令

```bash
export JAVA_HOME="/c/Program Files/Microsoft/jdk-17.0.20.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"
./gradlew assembleDebug testDebugUnitTest          # 构建+单测
ADB="/c/Users/xutia/AppData/Local/Android/Sdk/platform-tools/adb.exe"
"$ADB" -s e87a3fe4 install -r app-debug-arm64-v8a.apk   # 装机（按 ABI 选包）
TOKEN=$(printf "protocol=https\nhost=github.com\n\n" | git credential fill | grep '^password=' | cut -d= -f2-)
GH_TOKEN=$TOKEN gh release list                    # GitHub API 操作
```

### 推送卡住的坑（**必看**）

`credential.helper=helper-selector`（指向 GCM 2.9.1）在后台/无交互 shell 里会**永久挂起**：`git push` 停在 `Pushing to https://github.com/...` 不动，`git credential fill` 也一起卡死。`git ls-remote` 却很快——因为本仓库公开、读不需要认证，极易误判成网络问题。

已验证的绕过办法（**首选**，token 不落盘、不弹窗）：

```bash
TOKEN=$(printf "protocol=https\nhost=github.com\n\n" | git credential fill 2>/dev/null | grep '^password=' | cut -d= -f2-)
GIT_TERMINAL_PROMPT=0 git -c credential.helper= \
  -c http.extraheader="AUTHORIZATION: basic $(printf "x-access-token:$TOKEN" | base64 -w0)" \
  push origin main
```

`git credential fill` 能取到 token（40 位，user=Naatsumeee），**但只取不推**——把密码塞进 `http.extraheader` 走 Basic 认证即可，绕开 helper 与 askpass 两套交互。

旧办法（也可用，但要多写临时文件）：

```bash
printf "protocol=https\nhost=github.com\n\n" | GCM_INTERACTIVE=never git credential-manager get > cred.txt
grep '^password=' cred.txt | cut -d= -f2- > tok && chmod 600 tok
printf '#!/bin/sh\ncat "%s"\n' "$PWD/tok" > askpass.sh && chmod +x askpass.sh
GIT_ASKPASS="$PWD/askpass.sh" GIT_TERMINAL_PROMPT=0 GCM_INTERACTIVE=never \
  git -c credential.helper= push origin main
rm -f tok askpass.sh cred.txt                      # 用完立刻删
```

关键是 `git -c credential.helper=` 把会挂起的 helper 清空，改由 `GIT_ASKPASS` 供密码。

## 历史里程碑

M0 环境/仓库 → M1 骨架+四模式 → M2 OCR 导入流水线 → M3 AI 生成+置信度 → M4 模式补全 → M5 三省六部 → M6 错题本/统计/收藏/导出 → 后台解析+提速 → A1/A2/A3/B 型题+筛选+斩题+测试模式+热力图+搜索 → 纸感主题 → 导出增强（AI 内容开关+章节汇总+完整往返）→ 生成后台化+断点续传+费用透明 → 字体/配色/错题本重设计 → RapidOCR 上线 → 16KB 页对齐修复（NDK 重编）→ **v1.0.0** → 视觉层 v2 重做（暖纸+液态玻璃+底部悬浮 Dock+设置页音效/暗黑/数据管理）→ v1.0.1~v1.0.3（顶栏统一、底栏重写、Serif 加粗、暗绿正确色、震动与音效拆分、左右滑切题、AI 深度解题、LaTeX 自研渲染、回到上次进度、更新日志）→ v1.0.4（问 AI 追问面板+常驻入口）→ v1.0.5（8 bug 修复 + 成本显示/错题本章节重刷/热力图增强/名词解释独立题型）→ **v1.0.6**（LaTeX 渲染器全量重写 + Markdown 渲染器，公式与 Markdown 全站混排）→ **v1.0.7**（Markdown 题库导入+复制格式化提示词、RapidOCR 升 PP-OCRv5、分块解析防断裂+跨段答案回填、成本按官方峰谷分时、深度解题重试与 $$ 公式修复、问 AI 键盘遮挡修复、切题平移动画、热力图气泡跟手）。
→ **v1.0.8**（深度解题/问 AI 改 SSE 流式逐字输出、切题重做为跟手左右连续卡片、本轮完成页重做+刷本轮错题、导入提示词改为产出可直接导入的 JSON、DeepSeek 官方余额接口、速刷默认 2s、震动短促三档真区分、做完的进度即清、AI 免责小字）。
→ **v1.0.9**（RapidOCR 先跑版面分析 PP-DocLayout-S 再识别：双栏中缝由 OCR 行块投影算出，阅读顺序修复；页眉页脚/页码自动跳过）。

## 待办池（按价值排序）

1. release 正式签名 + Play 上架准备（当前 debug 签名）
2. ML Kit 16KB 对齐版本跟进
3. 解析预览单题编辑增强（题图支持）
4. 云备份（暂缓）
5. 性能：Room 分页加载超大题库
