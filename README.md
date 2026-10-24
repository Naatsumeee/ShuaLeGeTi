# 刷了个题 ShuaGeLeTi

[![Release](https://img.shields.io/github/v/release/Naatsumeee/ShuaLeGeTi)](https://github.com/Naatsumeee/ShuaLeGeTi/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-brightgreen.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84.svg)](https://github.com/Naatsumeee/ShuaLeGeTi/releases)

一款**离线优先**的 Android 刷题应用：导入自己的题库（docx / PDF / 扫描件 / 照片），OCR 识别并 AI 结构化，配合四种刷题模式、测试模式与多模型交叉验证，把任何习题册变成你的私人题库。

> 医学生的复习利器，也适用于任何有题库要刷的人。AI 功能全部由你自己的 API Key 驱动，题目数据完全留在本地。

## ✨ 功能特性

### 题库导入
- **多源导入**：docx / 文字型 PDF / 扫描型 PDF / 拍照 / 粘贴文本 / 标准题库 JSON
- **双 OCR 引擎**（均完全离线）：ML Kit 中文（轻量快速）+ RapidOCR PP-OCRv3（高精度），导入时可任选
- **双栏重排**：自动识别左右双栏扫描试卷的阅读顺序
- **AI 结构化解析**：题目、选项、答案、章节、页码、题型全部分类；支持医学 **A1/A2/A3/B 型题**分组（共用题干、共用选项自动聚合）；章末集中答案表按题号回填；OCR 错字按"只修不改"原则纠正
- **后台流水线**：WorkManager 前台服务运行，切应用 / 锁屏不中断；分块并行、断点续传，中断后只补失败段不重复计费

### 刷题模式
| 模式 | 说明 |
|---|---|
| 标准做题 | 作答即判分（字母+选项内容双通道），展示答案解析，手动下一题 |
| 速刷模式 | 作答后展示答案，3 秒自动下一题（1–10 秒可调，可暂停） |
| 背题模式 | 默认显示答案与解析 |
| 随机巩固 | 乱序抽题不重复，进度自动记忆，可手动重置 |
| 测试模式 | 选范围组卷整卷作答，答题卡跳题计时，交卷出报告（得分/用时/分章节正确率/错题清单） |

### 学习工具
- **多维筛选**：章节 / 题型 / A1A2A3B / 未做 · 错题 · 收藏 · 未斩 · 已斩（切换筛选自动重置对应进度）
- **斩题**：剪刀一挥，已掌握的题默认不再出现；斩题墙可随时恢复
- **错题本**：按题库分组筛选，未掌握 / 已掌握 / 收藏三态管理
- **统计**：今日与累计正确率、分库统计、每题用时、16 周 GitHub 风格刷题热力图
- **全库搜索**：跨题库搜题干 / 选项 / 解析
- **章节划分编辑器**：按原文页码或题号范围批量分章，逐题编辑题干/选项/答案/解析/题型

### AI 能力（自带 API Key）
- 兼容所有 **OpenAI 兼容接口**：DeepSeek / GLM / Kimi / Qwen / OpenAI / Ollama 等，多模型管理 + 连通性测试
- **一键生成答案与解析**：批量 12 题/请求 × 3 批并发，每题标注置信度（小字展示）
- **三省六部模式**：多模型交叉验证——主部高权重（置信度 = 0.6×主部自评 + 0.4×各部一致率），分歧模型答案可展开查看；AI 与题库原答案冲突时**双答案对照展示**
- **费用透明与优化**：稳定系统提示词提升前缀缓存命中（DeepSeek 命中价仅为未命中 1/50）、max_tokens 上限、紧凑题目序列化；生成页实时显示 token 用量与缓存命中率；已生成题目自动跳过不重复计费
- **系统提示词可自定义**：设置页直接在默认提示词基础上修改

### 数据与隐私
- 题库 / 进度 / 错题 / 收藏全部存本地 Room 数据库；OCR 全程离线；API Key 仅存本机
- 标准题库 JSON 导出（可勾选是否包含 AI 生成内容、附章节分类汇总、缩进美化），导出→导入完整往返不丢字段
- 数据库平滑迁移（v1→v4），升级不丢数据

## 📥 下载安装

前往 [Releases](https://github.com/Naatsumeee/ShuaLeGeTi/releases) 按设备架构选择 APK：

| 文件 | 架构 | 适用 |
|---|---|---|
| `app-arm64-v8a-release.apk` | arm64-v8a | 绝大多数主流手机 |
| `app-armeabi-v7a-release.apk` | armeabi-v7a | 较老的 32 位设备 |
| `app-x86_64-release.apk` | x86_64 | 模拟器 / Chromebook |

- 系统：Android 8.0（API 26）及以上；原生库全部按 **16KB 页对齐**构建，兼容新设备
- AI 功能需在「设置」配置任意 OpenAI 兼容模型；不配置也可正常离线刷题
- 当前 Release 使用调试签名，正式签名将在后续版本提供

## 🚀 快速上手

1. 安装后进入「设置 → 添加模型」，填入 Base URL / API Key / 模型名（如 DeepSeek 官方：`https://api.deepseek.com/v1` + `deepseek-flash`），点「测试」确认连通
2. 「题库 → 导入题库」，选择扫描 PDF / docx / 拍照，按需选择 OCR 引擎，点「AI 智能解析」——任务进入后台，可切出应用等通知
3. 回到预览页确认解析结果（可核对章节 / A1A2A3B 标签 / 页码），点「确认导入」
4. 题库详情页可「AI 生成答案」补全缺失答案与解析；随后选择任意模式开始刷题
5. 建议用「编辑题目 / 章节划分」按页码把题库分成章节，配合筛选按章复习

## 🧰 技术栈

Kotlin · Jetpack Compose (Material 3) · Room · DataStore · WorkManager · OkHttp · [ML Kit Text Recognition](https://developers.google.com/ml-kit/vision/text-recognition/v2) · [RapidOCR / PP-OCRv3 (ONNX Runtime)](https://github.com/RapidAI/RapidOcrAndroidOnnx) · [PDFBox-Android](https://github.com/tomroush/pdfbox-android) · PdfRenderer

## 📁 项目结构

```
app/src/main/java/com/examhelper/shua/
├── data/
│   ├── db/          # Room：实体、DAO、迁移（v1→v4）
│   ├── ocr/         # 双 OCR 引擎 + 双栏阅读顺序重排
│   ├── ingest/      # docx/PDF/图片统一提取（页码标记贯穿）
│   ├── parse/       # AI 结构化解析 / 离线正则 / 选项规范化
│   ├── generate/    # 批量答案生成、三省六部聚合、后台 Worker
│   ├── importjob/   # 导入流水线 Worker（断点续传）
│   ├── llm/         # OpenAI 兼容客户端、集中式系统提示词
│   ├── repo/        # BankRepository / StudyRepository（判分逻辑）
│   └── settings/    # DataStore 设置
├── ui/              # Compose 页面（bank/study/exam/editor/...）
└── theme/           # 纸感阅读风主题（editorial-reader 设计语言）
rapidocr-src/OcrLibrary/  # 内置的 RapidOCR 原生模块（NDK r27, 16KB 对齐）
AGENTS.md           # 项目完整记忆（供任何模型/Agent 接手开发）
```

## 🔨 开发构建

```bash
git clone https://github.com/Naatsumeee/ShuaLeGeTi.git
cd ShuaLeGeTi
./gradlew assembleDebug          # 需 JDK 17 与 Android SDK（compileSdk 35）
./gradlew testDebugUnitTest      # 单元测试
```

架构决策、环境细节、已知问题与路线图见 [AGENTS.md](AGENTS.md)。

## 🗺️ 路线图

- [ ] Release 正式签名与应用商店分发
- [ ] ML Kit 16KB 对齐版本跟进
- [ ] 题目配图支持（docx 内嵌图片 / 公式）
- [ ] RapidOCR 升级 PP-OCRv5 模型
- [ ] 超大题库分页加载优化

## 🙏 致谢与参考

- [RapidAI/RapidOcrAndroidOnnx](https://github.com/RapidAI/RapidOcrAndroidOnnx)（Apache-2.0）— RapidOCR 引擎
- [onnxruntime](https://github.com/microsoft/onnxruntime)（MIT）· [opencv-mobile](https://github.com/nolange/opencv-mobile)（Apache-2.0）· [PDFBox-Android](https://github.com/tomroush/pdfbox-android)（Apache-2.0）
- [qyn1126/LearnHelper](https://github.com/qyn1126/LearnHelper) · [ERRRC/kaogong-shuati](https://github.com/ERRRC/kaogong-shuati) · [nanguaguag/yibubang](https://github.com/nanguaguag/yibubang)（功能设计参考）
- [alchaincyf/huashu-design](https://github.com/alchaincyf/huashu-design)（editorial-reader 视觉风格参考）

## 📄 许可证

本项目基于 [MIT License](LICENSE) 开源。

内置第三方组件遵循各自许可证：RapidOcrAndroidOnnx / opencv-mobile / PDFBox（Apache-2.0）、onnxruntime（MIT）、ML Kit（Google 服务条款）。内置的 PP-OCRv3 模型与字典来自 PaddleOCR 生态（Apache-2.0）。
