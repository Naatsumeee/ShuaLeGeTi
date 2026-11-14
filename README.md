# 刷了个题 ShuaGeLeTi

> ## 📖 第一次用？从这里开始
>
> 先看 **[快速上手.md](快速上手.md)** —— 从申请 DeepSeek API Key、导入题库，到刷题、错题重刷、换机迁移，一份文档走完全流程，含截图级的界面路径说明与常见问题。
>
> 只想下载安装：直接去 [Releases](https://github.com/Naatsumeee/ShuaLeGeTi/releases) 拿 `Shualegeti-arm64-v8a-1.2.0.apk`。

[![Release](https://img.shields.io/github/v/release/Naatsumeee/ShuaLeGeTi)](https://github.com/Naatsumeee/ShuaLeGeTi/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-brightgreen.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84.svg)](https://github.com/Naatsumeee/ShuaLeGeTi/releases)

一款**离线优先**的 Android 刷题应用：导入自己的题库（docx / PDF / Markdown / 扫描件 / 照片），OCR 识别并 AI 结构化，配合四种刷题模式、测试模式与多模型交叉验证，把任何习题册变成你的私人题库。
**注意：软件主要功能依托大模型的API，请了解如何配置API Key后进行使用**

> 医学生的复习利器，也适用于任何有题库要刷的人。AI 功能全部由你自己的 API Key 驱动，题目数据完全留在本地。

## ✨ 功能特性

### 题库导入
- **多源导入**：docx / 文字型 PDF / 扫描型 PDF / 拍照 / 粘贴文本 / **Markdown 题库** / 标准题库 JSON
- **让 AI 帮你整理题库**：复制内置格式化提示词给任意 AI，让它直接产出可一键导入的 `.json`
- **双 OCR 引擎**（均完全离线）：ML Kit 中文（轻量快速）+ RapidOCR **PP-OCRv5**（高精度，符号与密集小字更准），导入时可任选
- **先看版面再认字**：PP-DocLayout-S 按标题 / 正文 / 表格 / 公式分区，页眉页脚与页码自动跳过
- **双栏重排**：由文字行块的垂直投影算出真实中缝，左右双栏扫描试卷按栏读完，不会整段串行
- **AI 结构化解析**：题目、选项、答案、章节、页码、题型全部分类；支持医学 **A1/A2/A3/B 型题**分组（共用题干、共用选项自动聚合）；章末集中答案表按题号回填；OCR 错字按"只修不改"原则纠正
- **判断题自动成型**：原文没给「正确 / 错误」两个选项也会自动补齐，答案写成「对 / 错 / √ / × / T / F」都能识别，不会因为缺选项就答不了
- **答案表精确回填**：题号按「章 + 题型分组」各自编号对应，扫描件的章末集中答案不再整章错位或丢失
- **轻量、便宜、可控**：同一提示词前缀命中缓存（DeepSeek 命中价仅为未命中 1/50）；解析默认不启用思考模式（结构化抽取用不上，白白烧掉输出预算）；分块 6000 字为实测最优（再放大反而会截断丢题）
- **后台流水线**：WorkManager 前台服务运行，切应用 / 锁屏不中断；分块并行、断点续传，中断后只补失败段不重复计费；**OCR 阶段逐页落盘**，一本扫描件跑到一半被杀掉也不用从头再来

### 刷题模式
| 模式 | 说明 |
|---|---|
| 标准做题 | 作答即判分（字母+选项内容双通道），展示答案解析，手动下一题 |
| 速刷模式 | 作答后展示答案，2 秒自动下一题（1–10 秒可调，可暂停） |
| 背题模式 | 默认显示答案与解析 |
| 随机巩固 | 乱序抽题不重复，进度自动记忆，可手动重置 |
| 测试模式 | 选范围组卷整卷作答，答题卡跳题计时，交卷出报告（得分/用时/分章节正确率/错题清单） |

### 学习工具
- **多维筛选**：章节 / 题型 / A1A2A3B / 未做 · 错题 · 收藏 · 未斩 · 已斩（切换筛选自动重置对应进度）
- **连续卡片切题**：左右滑动时当前题卡跟手平移、两侧露出相邻题卡边缘，滑一小段即可翻页；手指滑动中按住片刻再松手也能翻页。上一题 / 下一题按钮走同一套动画
- **单题详情页**：测试报告或错题本里点任意题目，都能进完整页面看题干、共用材料、选项（对错标色）、参考答案与解析，解析默认展开
- **斩题**：剪刀一挥，已掌握的题默认不再出现；斩题墙可随时恢复
- **错题本**：按题库分组，**全部 / 错题 / 已掌握 / 重点** 分组管理；每题右上角菜单可收藏、斩题、标记重点、从错题本删除；标记为重点的题能单独拿出来复刷
- **统计**：今日与累计正确率、分库统计、每题用时、16 周 GitHub 风格刷题热力图
- **全库搜索**：跨题库搜题干 / 选项 / 解析
- **章节划分编辑器**：按原文页码或题号范围批量分章，逐题编辑题干/选项/答案/解析/题型

### AI 能力（自带 API Key）
- 兼容所有 **OpenAI 兼容接口**：DeepSeek / GLM / Kimi / Qwen / OpenAI / Ollama 等，多模型管理 + 连通性测试
- **一键生成答案与解析**：批量 12 题/请求 × 3 批并发，每题标注置信度
- **逐字流式输出**：深度解题与「问 AI」边生成边显示，推理过程与正文都可见，不用对着转圈干等
- **三省六部模式**：多模型交叉验证——主部高权重（置信度 = 0.6×主部自评 + 0.4×各部一致率），分歧模型答案可展开查看；AI 与题库原答案冲突时**双答案对照展示**
- **费用透明与优化**：稳定系统提示词提升前缀缓存命中（DeepSeek 命中价仅为未命中 1/50）、max_tokens 上限、紧凑题目序列化、提示词精简；生成页实时显示 token 用量与缓存命中率；已生成题目自动跳过不重复计费
- **思考强度可调**：深度解题 / 补全解析可选 `Off / Low / High / Max`（默认 Low，越高越会推演但也越慢越贵）；**导入解析固定关闭思考**——结构化抽取用不上推理，开着只会把输出预算烧光
- **用量情况**：设置页可查近 7 天 / 30 天的消费，两个独立算法并列对照——「实际消费」按余额下降量累计（能对上官方账单），「本地估算」按记录到的 token 与峰谷单价算，并拆出缓存命中 / 未命中 / 输出
- **账户余额（仅 DeepSeek 官方端点）**：设置页可随时查询剩余余额
- **系统提示词可自定义**：设置页直接在默认提示词基础上修改

> 说明：早期版本曾提供「实扣」——用任务前后两次余额之差当作本轮花费。该功能已移除，
> 因为 DeepSeek 计费有几分钟延迟，任务刚结束时两次读数几乎总相等，差值恒为 0。
> 想看实际消费请用「用量情况」的多天累计。

### 数据与隐私
- 题库 / 进度 / 错题 / 收藏全部存本地 Room 数据库；OCR 全程离线；API Key 仅存本机
- 学习数据可导出为 `.shua` 标准 ZIP：整合题库、题库组、合并关系与学习记录，`manifest.json` 带应用/数据库版本、时间戳和题库 SHA256
- 标准题库 JSON 导出（可勾选是否包含 AI 生成内容、附章节分类汇总、缩进美化），导出→导入完整往返不丢字段
- 数据库平滑迁移（**v1 → v15**），升级不丢数据；章节名的历史脏数据在升级时**就地清洗**，不用重导题库

## 📥 下载安装

前往 [Releases](https://github.com/Naatsumeee/ShuaLeGeTi/releases) 下载最新 APK：

| 文件 | 架构 | 适用 |
|---|---|---|
| `Shualegeti-arm64-v8a-<版本>.apk` | arm64-v8a | 绝大多数主流手机（**当前只发这一种**） |

- 命名规则：`Shualegeti-<架构>-<版本号>.apk`，例如 `Shualegeti-arm64-v8a-1.2.0.apk`
- 系统：Android 8.0（API 26）及以上；原生库全部按 **16KB 页对齐**构建，兼容新设备
- AI 功能需在「设置」配置任意 OpenAI 兼容模型；不配置也可正常离线刷题
- 从 **v1.1.0 起使用正式签名**。此前所有版本都是调试签名，两者证书不同，
  **升级到 v1.1.0 必须先卸载旧版**（会清空应用数据，请先在旧版导出题库/学习记录）；
  v1.1.0 之后签名固定，后续可正常覆盖升级
- 需要在模拟器或旧 32 位设备上运行时，自行构建并指定 ABI：
  `./gradlew assembleRelease -PabiOverride=arm64-v8a,x86_64`

## 🚀 快速上手

> 图文版逐步说明（含配模型、导入、刷题、常见问题）见 **[快速上手.md](快速上手.md)**。

1. 安装后进入「设置 → 添加模型」，填入 Base URL / API Key / 模型名（如 DeepSeek 官方：`https://api.deepseek.com` + `deepseek-flash`），点「测试」确认连通
2. 「题库 → 导入题库」，选择扫描 PDF / docx / 拍照，按需选择 OCR 引擎，点「AI 智能解析」——任务进入后台，可切出应用等通知
3. 回到预览页确认解析结果（可核对章节 / A1A2A3B 标签 / 页码），点「确认导入」
4. 题库详情页可「AI 生成答案」补全缺失答案与解析；随后选择任意模式开始刷题
5. 建议用「编辑题目 / 章节划分」按页码把题库分成章节，配合筛选按章复习

## 🧰 技术栈

Kotlin · Jetpack Compose (Material 3) · Room · DataStore · WorkManager · OkHttp · [ML Kit Text Recognition](https://developers.google.com/ml-kit/vision/text-recognition/v2) · [RapidOCR / PP-OCRv5 (ONNX Runtime)](https://github.com/RapidAI/RapidOcrAndroidOnnx) · [PDFBox-Android](https://github.com/tomroush/pdfbox-android) · PdfRenderer

## 📁 项目结构

```
app/src/main/java/com/examhelper/shua/
├── data/
│   ├── db/          # Room：实体、DAO、迁移（v1→v12）
│   ├── ocr/         # 双 OCR 引擎 + 版面分析 + 双栏阅读顺序重排
│   ├── ingest/      # docx/PDF/图片/Markdown 统一提取（页码标记贯穿、逐页落盘）
│   ├── parse/       # AI 结构化解析（分块防断裂）/ 答案表绑定 / 离线正则 / 选项规范化
│   ├── generate/    # 批量答案生成、三省六部聚合、峰谷计价、后台 Worker
│   ├── importjob/   # 导入流水线 Worker（断点续传）
│   ├── llm/         # OpenAI 兼容客户端（含 SSE 流式）、集中式系统提示词、用量统计
│   ├── repo/        # BankRepository / StudyRepository（判分逻辑）
│   ├── storage/     # 本地文件存储唯一入口（缓存分区与清理）
│   └── settings/    # DataStore 设置
├── ui/              # Compose 页面
│   ├── bank/ study/ exam/ editor/ generate/ import_/ search/
│   ├── question/    # 单题详情页（测试报告与错题本共用）
│   ├── stats/ wrongbook/ settings/ sound/
│   └── theme/       # 暖纸主题、液态玻璃组件、自研 LaTeX 与 Markdown 渲染
rapidocr-src/OcrLibrary/  # 内置的 RapidOCR 原生模块（NDK r27, 16KB 对齐）
app/src/main/assets/      # 内置 ONNX 模型（PP-OCRv5 / PP-DocLayout-S）
AGENTS.md           # 项目完整记忆（供任何模型/Agent 接手开发）
```

## 🔨 开发构建

```bash
git clone https://github.com/Naatsumeee/ShuaLeGeTi.git
cd ShuaLeGeTi
./gradlew assembleDebug          # 需 JDK 17 与 Android SDK（compileSdk 35）
./gradlew testDebugUnitTest      # 单元测试（205 项）
./gradlew assembleRelease        # 出正式包 → Shualegeti-arm64-v8a-<版本>.apk
```

### 正式签名

发布用的 keystore **不入库**。要在本地出正式签名包，在仓库根目录放 `keystore.properties`：

```properties
storeFile=release.jks
storePassword=<你的密码>
keyAlias=<你的别名>
keyPassword=<你的密码>
```

没有这个文件时，`assembleRelease` 会**优雅回退到 debug 签名**（包能出，但不能覆盖已装的正版），
所以克隆下来直接构建不会失败。

### 原生依赖准备（克隆后必做）

仓库不入库体积庞大的第三方原生依赖，构建 `rapidocr-src/OcrLibrary` 前需手动放置两份：

| 目录 | 来源 | 说明 |
|---|---|---|
| `rapidocr-src/OcrLibrary/src/sdk/` | [opencv-mobile 3.4.15](https://github.com/nolange/opencv-mobile/releases) | 解压后保证 `src/sdk/native/jni/OpenCVConfig.cmake` 存在，约 43MB |
| `rapidocr-src/OcrLibrary/src/main/onnxruntime-shared/` | [onnxruntime-android 1.20.0](https://github.com/microsoft/onnxruntime/releases) | 头文件 + `OnnxRuntimeConfig.cmake` 包装层，约 1.6MB |

两处缺任一项，CMake 会直接 `FATAL_ERROR: opencv Not Found!`，构建无法完成。

架构决策、环境细节、已知问题与路线图见 [AGENTS.md](AGENTS.md)。

## 🗺️ 路线图

- [x] Release 正式签名（v1.1.0 起）
- [ ] 应用商店分发
- [ ] ML Kit 16KB 对齐版本跟进
- [ ] 拍照搜题功能（AI API 驱动）
- [ ] 题目配图支持（docx 内嵌图片 / 公式）
- [ ] 超大题库分页加载优化

## 🙏 致谢与参考

- [RapidAI/RapidOcrAndroidOnnx](https://github.com/RapidAI/RapidOcrAndroidOnnx)（Apache-2.0）— RapidOCR 引擎
- [onnxruntime](https://github.com/microsoft/onnxruntime)（MIT）· [opencv-mobile](https://github.com/nolange/opencv-mobile)（Apache-2.0）· [PDFBox-Android](https://github.com/tomroush/pdfbox-android)（Apache-2.0）
- [qyn1126/LearnHelper](https://github.com/qyn1126/LearnHelper) · [ERRRC/kaogong-shuati](https://github.com/ERRRC/kaogong-shuati) · [nanguaguag/yibubang](https://github.com/nanguaguag/yibubang)（功能设计参考）
- [alchaincyf/huashu-design](https://github.com/alchaincyf/huashu-design)（editorial-reader 视觉风格参考）

## 📄 许可证

本项目基于 [MIT License](LICENSE) 开源。

内置第三方组件遵循各自许可证：RapidOcrAndroidOnnx / opencv-mobile / PDFBox（Apache-2.0）、onnxruntime（MIT）、ML Kit（Google 服务条款）。内置的 PP-OCRv5 模型与字典来自 PaddleOCR 生态（Apache-2.0）。
