# 刷了个题 (ShuaGeLeTi)

一款 Android 刷题应用：导入自己的题库（docx / 文字版 PDF / 扫描版 PDF / 照片），OCR 识别并结构化为标准题库，配合四种刷题模式与大模型自动生成答案解析。

## 核心特性

- **多源导入**：docx / 文字型 PDF / 扫描型 PDF / 拍照 → 双引擎 OCR（默认 ML Kit 中文，可切 RapidOCR 高精度）→ LLM 结构化解析 → 预览确认入库
- **四种刷题模式**：
  - 标准模式：作答后判对错、展示答案解析，手动下一题
  - 速刷模式：作答后展示答案解析，3 秒自动下一题
  - 背题模式：默认显示答案与解析
  - 随机巩固：乱序抽题不重复，进度记忆，可手动重置
- **AI 答案生成**：配置大模型 API（OpenAI 兼容协议），一键为无答案题目生成答案 + 解析 + 置信度（小字标注）
- **三省六部模式**：多模型交叉验证，选定主部高权重参考，展示各部分歧与一致率
- **错题本 / 收藏 / 刷题统计**

## 技术栈

Kotlin · Jetpack Compose · Room · DataStore · WorkManager · OkHttp(SSE) · ML Kit / RapidOCR(PP-OCRv4/v5 ONNX) · PDFBox-Android · PdfRenderer

## 开发里程碑

- [x] M0 环境与仓库
- [x] M1 项目骨架 + 数据层 + 四种做题模式
- [x] M2 文档/OCR 导入流水线（docx / 文字PDF / 扫描PDF / 图片 / 拍照，ML Kit 离线中文 OCR + 双栏重排 + LLM 结构化解析）
- [x] M3 大模型一键生成答案/解析/置信度
- [x] M4 速刷/背题/随机巩固（进度记忆与重置）
- [x] M5 三省六部多模型交叉验证（主部高权重 + 一致率 + 分歧展示）
- [x] M6 错题本/统计/收藏/题库导出导入

## 待完善

- RapidOCR（PP-OCR）高精度 OCR 引擎接入（设置中已有开关位）
- .doc 旧格式支持（提示转换 docx）
- docx 内嵌图片/公式题
- AI 生成移至 WorkManager 后台任务
- 解析结果单题编辑

## License

TBD
