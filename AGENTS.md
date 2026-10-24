# AGENTS.md — 刷了个题 (ShuaGeLeTi) 项目记忆

> 本文件是项目的完整上下文记忆，供任何模型/Agent 无缝接手开发。最后更新：v1.0.0（2026-10-25）。

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
- 双 OCR 引擎（`data/ocr/`）：ML Kit 中文 bundled（默认）+ RapidOCR（PP-OCRv3 ONNX）；OCR 后做**双栏阅读顺序重排**（`MlKitOcrEngine.rebuildReadingOrder`，扫描试卷是左右双栏）
- RapidOCR 为**源码模块** `rapidocr-src/OcrLibrary`（Apache-2.0，已入库 ~93MB）：NDK r27 + `ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON` + onnxruntime **1.20**（16KB 页对齐，Maven 的 AAR 抽取）+ opencv-mobile 3.4.15（`src/sdk/`）。**16KB 页对齐是硬要求**（用户手机是 16KB 设备），换依赖时必须验证 PT_LOAD p_align=16384
- 后台任务 = WorkManager 前台服务（dataSync 类型）：`ImportPipelineWorker`（导入/解析，断点续传按"已落盘分块"）与 `GenerateWorker`（AI 生成，断点续传按"已答即跳过"）；进度写 Room 表（import_jobs/generate_jobs），UI 观察 Flow
- LLM：`data/llm/LlmClient.kt`（OpenAI 兼容 chat/completions，jsonMode 失败自动降级重试，max_tokens 支持）；**系统提示词集中**在 `data/llm/Prompts.kt`（用户可在设置页覆盖，稳定前缀为命中 DeepSeek 前缀缓存——命中价是未命中 1/50）
- 答案生成：`data/generate/AnswerGenerator.kt` 批量 12 题/请求 × 3 批并发；三省六部=主部高权重 0.6 + 一致率 0.4，分歧存 dissent；**与题库原答案冲突时**原答案存 `Question.originalAnswer` 做题页双展示
- 解析：AI（`LlmQuestionParser`，6000 字分块，页码标记 `[[PG:n]]` 贯穿到 `Question.sourcePage`）+ 离线正则兜底（`TextQuestionParser`）；`OptionNormalizer` 合并重复选项字母并重映射答案（OCR 重复 B 类问题的修复）
- 判分：`StudyRepository.judgeAnswer`（伴生对象，字母优先+选项内容兜底+文本归一化）
- UI 主题：纸感阅读风（`ui/theme/Theme.kt`，editorial-reader 色板：米纸 #F2EEE3/赭红 #B5532A/墨色；**surfaceContainer 系列已覆盖为暖色**，M3 默认是薰衣草紫）；衬线仅用于大数字/标注/参考答案，标题按钮默认无衬线；紧凑 48dp 标题栏 `CompactTopBar`、56dp 底部导航（避让手势条）

## 页面地图（ui/）

`AppRoot.kt` 全部路由。banks/wrongbook/stats/settings 四 tab；bank/{id} 详情（覆盖率+模式入口+导出对话框）；study_setup（多维筛选：章节/题型/A1A2A3B/作答状态）；study/{bankId}/{mode}?filter=（四种模式+答题卡+斩题+冲突展示）；exam（测试模式+答题卡）→exam_report/{runId}；generate/{bankId}（AI 生成+token 用量展示）；editor/{bankId}（按页码/题号批量分章+单题编辑）；import_file（OCR 引擎选择+后台进度+日志+预览）；import_text（粘贴）；search。

## 已知问题/注意

- ML Kit `text-recognition-chinese:16.0.1` 上游尚无 16KB 对齐版本（用户设备警告里可能残留它，等 Google 更新后升版本号即可）
- 中转 API 可能不透传缓存字段 → token 命中显示 0；已做 OpenAI `prompt_tokens_details.cached_tokens` 回退解析
- DeepSeek 模型名现为 deepseek-flash / deepseek-v4-pro（旧 deepseek-chat 已下线）
- APK 体积：debug 全 ABI ~190MB；v1.0.0 起启用 **ABI splits**（arm64-v8a / armeabi-v7a / x86_64），release 用 debug 签名（未配置正式签名）
- 样例题库在 `QuestionBankExample/`（gitignore，版权资料）；`app-debug*.apk` 也 gitignore

## 常用命令

```bash
export JAVA_HOME="/c/Program Files/Microsoft/jdk-17.0.20.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"
./gradlew assembleDebug testDebugUnitTest          # 构建+单测
ADB="/c/Users/xutia/AppData/Local/Android/Sdk/platform-tools/adb.exe"
"$ADB" -s e87a3fe4 install -r app-debug-arm64-v8a.apk   # 装机（按 ABI 选包）
TOKEN=$(printf "protocol=https\nhost=github.com\n\n" | git credential fill | grep '^password=' | cut -d= -f2-)
GH_TOKEN=$TOKEN gh release list                    # GitHub API 操作
```

## 历史里程碑

M0 环境/仓库 → M1 骨架+四模式 → M2 OCR 导入流水线 → M3 AI 生成+置信度 → M4 模式补全 → M5 三省六部 → M6 错题本/统计/收藏/导出 → 后台解析+提速 → A1/A2/A3/B 型题+筛选+斩题+测试模式+热力图+搜索 → 纸感主题 → 导出增强（AI 内容开关+章节汇总+完整往返）→ 生成后台化+断点续传+费用透明 → 字体/配色/错题本重设计 → RapidOCR 上线 → 16KB 页对齐修复（NDK 重编）→ **v1.0.0**。

## 待办池（按价值排序）

1. release 正式签名 + Play 上架准备（当前 debug 签名）
2. ML Kit 16KB 对齐版本跟进
3. 解析预览单题编辑增强（题图支持）
4. 云备份（暂缓）；RapidOCR 可升级 PP-OCRv5 模型
5. 性能：Room 分页加载超大题库
