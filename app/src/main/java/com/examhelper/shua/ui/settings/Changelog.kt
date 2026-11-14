package com.examhelper.shua.ui.settings

/**
 * 版本更新日志（App 内「关于」展示）。
 *
 * 维护约定：每次发版在最前面插入一条，[version] 与 app/build.gradle.kts 的 versionName 保持一致。
 * 文案面向用户，写"能感知到什么"，不写技术实现。
 */
data class ReleaseNote(
    val version: String,
    val date: String,
    /** true = 当前运行版本，界面高亮 */
    val current: Boolean = false,
    val items: List<NoteItem>
)

data class NoteItem(val kind: Kind, val text: String) {
    enum class Kind { NEW, IMPROVE, FIX }
}

val RELEASE_NOTES: List<ReleaseNote> = listOf(
    ReleaseNote(
        version = "1.2.0",
        date = "2026-11-14",
        current = true,
        items = listOf(
            NoteItem(NoteItem.Kind.NEW, "新增题库组、题库合并与撤销合并，支持自定义名称、备注和装饰色"),
            NoteItem(NoteItem.Kind.NEW, "新增错题 PDF 导出，支持题库/章节、题后或文末解析、深度/极简/仅答案及无答案模式"),
            NoteItem(NoteItem.Kind.NEW, "学习数据新增 .shua 标准导出格式，整合题库、题库组、合并关系和学习记录"),
            NoteItem(NoteItem.Kind.NEW, "导出数据中 manifest.json 新增应用版本、versionCode、数据库版本、导出时间和题库 SHA256"),
            NoteItem(NoteItem.Kind.NEW, "新增 AI 用量统一统计，以及基于 GitHub Release 的检查更新功能"),
            NoteItem(NoteItem.Kind.NEW, "新增首页题库编辑模式和拖拽排序，题库管理更简便"),
            NoteItem(NoteItem.Kind.IMPROVE, "优化首页编辑模式、拖拽排序、批量操作栏、问 AI 抽屉、Tab 切换和主要按钮的弹簧动效"),
            NoteItem(NoteItem.Kind.IMPROVE, "优化极简解析长度限制，取消固定字符截断，保证输出句子完整"),
            NoteItem(NoteItem.Kind.IMPROVE, "优化 docx、Markdown、纯文本和长文档导入，增加安全拆分、重叠识别及超时降载"),
            NoteItem(NoteItem.Kind.IMPROVE, "优化扫描件章节识别：保留页眉章号，过滤概念小标题，自动补齐、归并和统一章节名称"),
            NoteItem(NoteItem.Kind.IMPROVE, "优化章末答案回收，支持多个答案区、题号乱码和整章低覆盖检查；真实扫描 PDF 答案回归覆盖显著增高"),
            NoteItem(NoteItem.Kind.IMPROVE, ".shua 导出保留题库组指针、合并状态、深度解析和极简解析状态，避免同名题库错误归属"),
            NoteItem(NoteItem.Kind.IMPROVE, "震动强度、字体大小、问 AI 开关和编辑模式反馈改为更及时、更跟手的交互"),
            NoteItem(NoteItem.Kind.FIX, "修复维护任务切后台后进度卡住、取消异常被吞掉、无法真正停止的问题"),
            NoteItem(NoteItem.Kind.FIX, "修复问 AI 关闭时按钮颜色和震动反馈延迟恢复的问题"),
            NoteItem(NoteItem.Kind.FIX, "修复编辑模式菜单与拖拽横条上下抖动、遮罩区分不明显的问题"),
            NoteItem(NoteItem.Kind.FIX, "修复题库合并后错题、收藏、斩题及作答记录归属错误的问题"),
            NoteItem(NoteItem.Kind.FIX, "修复旧版纯学习记录 JSON 被误当题库导入，以及导出缺少版本和完整性校验的问题"),
        )
    ),
    ReleaseNote(
        version = "1.1.0",
        date = "2026-11-11",
        current = false,
        items = listOf(
            NoteItem(NoteItem.Kind.FIX, "修复首页上下滑动题库列表时会误进入题库：现在划动只滚动列表，轻点才进入"),
            NoteItem(NoteItem.Kind.NEW, "判断题成了独立题型：原文没写「A 正确 / B 错误」也会自动补齐两项，答案写成「对/错/√/×/T/F」都能识别，不会再出现「没有可点的选项、答不了」"),
            NoteItem(NoteItem.Kind.NEW, "新增单题详情页：测试报告和错题本里点任意题目都能进，完整题干、共用材料、选项、参考答案与解析一次看全，解析默认展开"),
            NoteItem(NoteItem.Kind.NEW, "正确答案标绿修好了：多选题会标出全部正确项，判断题也能正确标出「正确/错误」"),
            NoteItem(NoteItem.Kind.NEW, "错题本新增「重点」分组：题目右上角菜单可标记重点，重点题能单独拿出来复刷"),
            NoteItem(NoteItem.Kind.NEW, "错题本每道题新增「⋯」菜单：收藏、斩题、标记重点、从错题本删除"),
            NoteItem(NoteItem.Kind.NEW, "设置页新增「深度解题 / 补全解析 · 思考强度」四档（Off / Low / High / Max），默认 Low；调高更会推演，但更慢更贵"),
            NoteItem(NoteItem.Kind.NEW, "设置页「账户余额」下方新增「用量情况」：可查近 7 天 / 30 天的实际消费与本地估算，并拆出缓存命中、未命中、输出 token"),
            NoteItem(NoteItem.Kind.IMPROVE, "导入解析快得多：PDF 整本只解析一次、渲染器跨页复用，并支持逐页落盘——跑一半被杀掉不用从头再来"),
            NoteItem(NoteItem.Kind.IMPROVE, "导入解析更省钱：解析默认不开启思考模式（同样内容以前会把输出预算烧光、几乎解析不出题），提示词也精简了约三成"),
            NoteItem(NoteItem.Kind.IMPROVE, "缓存命中统计不再误导：新增区分「还没有数据」和「真的 0%」；断点续传也不会再把已累计的用量冲掉"),
            NoteItem(NoteItem.Kind.IMPROVE, "震动强度和时长彻底分开：强度只管轻重、时长只管长短，默认都是 2；拖动滑块时每一档都有实时预览"),
            NoteItem(NoteItem.Kind.IMPROVE, "错题本的题库 / 章节筛选改成下拉，占位从三排压到一行；「忽略整本」独立成一行，不再和筛选挤在一起"),
            NoteItem(NoteItem.Kind.IMPROVE, "底部导航切换更顺，滑动胶囊不再掉帧"),
            NoteItem(NoteItem.Kind.FIX, "修复章节名被弄坏：不会再出现只剩「第X章」、只剩章名、或同时冒出「第X章」和「第X章 名字」两条的情况。已导入的题库升级后会自动修好，不用重导"),
            NoteItem(NoteItem.Kind.FIX, "修复 AI 导入答案识别不全：章末答案表现在按题号精确对应，同一本扫描件的答案覆盖率从约三成提升到八成以上（离线解析仍维持九成九）"),
            NoteItem(NoteItem.Kind.FIX, "修复题库卡片右上角「更多」按钮会误进入题库、菜单点了没反应"),
            NoteItem(NoteItem.Kind.FIX, "修复导入任务点「取消」后界面卡在「解析中」、不自动返回"),
            NoteItem(NoteItem.Kind.FIX, "修复切后台 / 锁屏后导入任务中断：现在锁屏也会继续跑完"),
            NoteItem(NoteItem.Kind.FIX, "修复同一份 PDF 偶发导入出成片乱码"),
            NoteItem(NoteItem.Kind.FIX, "修复刷题时以前答过的题一进来就直接摊着解析、不等你作答"),
            NoteItem(NoteItem.Kind.FIX, "修复「上一题 / 下一题」按钮动画消失、连点没反应"),
            NoteItem(NoteItem.Kind.FIX, "修复偶发的启动崩溃"),
        )
    ),
    ReleaseNote(
        version = "1.0.12",
        date = "2026-11-04",
        current = false,
        items = listOf(
            NoteItem(NoteItem.Kind.FIX, "彻底修复章末集中答案识别不出：扫描件每章末的「三、参考答案」现在会被单独识别成答案表，不再被当成题目"),
            NoteItem(NoteItem.Kind.FIX, "答案表支持三种排版：每行一对「1.A」、题号与答案被拆到两行、以及一行挤了多道「1.E 2.B 3.D」"),
            NoteItem(NoteItem.Kind.FIX, "答案按章节分开对应。同一章里名词解释与选择题各自从 1 编号，不会再互相错位"),
            NoteItem(NoteItem.Kind.FIX, "名词解释、简答题的答案按内容匹配，不再被当成选择题套上一个字母"),
            NoteItem(NoteItem.Kind.FIX, "修复「第十章」这类章号识别不出，以及页眉重复导致章节名变成「第十章 第十章 多基因病」"),
            NoteItem(NoteItem.Kind.FIX, "B1 型题（共用备选答案）的 A~E 选项不再被上一题吞掉，整组题都能拿到选项与答案"),
            NoteItem(NoteItem.Kind.IMPROVE, "「一、学习目标」下的「1.掌握… 2.熟悉…」不再被当成题目，章末答案的题号不会因此错位"),
            NoteItem(NoteItem.Kind.IMPROVE, "扫描件 OCR 提高识别分辨率并放宽检测阈值，A4 小字选项的漏检明显减少（答案覆盖率 95% → 99.7%）"),
        )
    ),
    ReleaseNote(
        version = "1.0.11",
        date = "2026-11-03",
        current = false,
        items = listOf(
            NoteItem(NoteItem.Kind.NEW, "首页标题栏新增「导入」按钮，空题库时页面正中也有一个大按钮，不用再找右下角"),
            NoteItem(NoteItem.Kind.NEW, "首页标题栏新增「问 AI」，与刷题页同一套面板，可以直接问概念、考点和复习方法"),
            NoteItem(NoteItem.Kind.NEW, "长按题库可弹出菜单：置顶、编辑题库名称、归档、删除。归档的题库收在列表最下方，点「显示已归档题库」展开"),
            NoteItem(NoteItem.Kind.NEW, "AI 解析题库时实时显示模型的生成过程，解析出一批题目就在下方显示一批"),
            NoteItem(NoteItem.Kind.IMPROVE, "默认 OCR 引擎改为 RapidOCR（PP-OCRv5 + 版面分析），中文扫描件与照片的识别准确率明显更高"),
            NoteItem(NoteItem.Kind.FIX, "修复章节识别：「（一）名词解释」「（三）简答题」这类题型标签不再被当成章节名，「第X章」也不会再丢失；OCR 漏掉的章号会按顺序补齐，前后编号连续"),
            NoteItem(NoteItem.Kind.FIX, "修复章末集中答案大范围识别不出：答案区常见的「1.A」「2. D」每行一对格式现在能正确解析，并按章节分开对应，不会把上一章的答案填到下一章"),
            NoteItem(NoteItem.Kind.FIX, "修复解析成本显示「未知模型不计价」：实际使用的模型没有传到界面，导致一直无法计价"),
        )
    ),
    ReleaseNote(
        version = "1.0.10",
        date = "2026-11-02",
        current = false,
        items = listOf(
            NoteItem(NoteItem.Kind.FIX, "修复文本题库乱码：GBK / GB18030 / UTF-16 编码的 .txt、.md 文件现在能正确识别，不再出现成片的「�」"),
            NoteItem(NoteItem.Kind.FIX, "修复带 BOM 的文件首行失效（章节标题识别不出、第一题丢失）"),
            NoteItem(NoteItem.Kind.FIX, "修复生僻字、数学符号、emoji 在 OCR 与长文本切分时被从中间截断成乱码"),
            NoteItem(NoteItem.Kind.FIX, "修复单栏扫描件被误判成双栏，导致题号与选项被拆到文件两端"),
            NoteItem(NoteItem.Kind.FIX, "PDF 文字层抽出来就是乱码时，自动改用 OCR 识别该页"),
            NoteItem(NoteItem.Kind.IMPROVE, "页眉页脚过滤真正生效，导入后需要手工删的杂行明显减少"),
        )
    ),
    ReleaseNote(
        version = "1.0.9",
        date = "2026-11-02",
        current = false,
        items = listOf(
            NoteItem(NoteItem.Kind.FIX, "⚠️ 此版本存在文本题库乱码的恶性 bug，不建议下载，请使用 1.0.10 或更新版本"),
            NoteItem(NoteItem.Kind.NEW, "扫描件与拍照识别改为「先看版面、再认字」：先用版面模型把整页切成标题、正文段落、表格、公式等区域，再逐块识别"),
            NoteItem(NoteItem.Kind.FIX, "修复双栏排版试卷识别后文字整段串行的问题，现在能找准中缝，左栏读完再读右栏"),
            NoteItem(NoteItem.Kind.IMPROVE, "页眉、页脚、页码不再混进题干"),
            NoteItem(NoteItem.Kind.IMPROVE, "版面模型缺失或机型不支持时自动退回原来的识别方式"),
        )
    ),
    ReleaseNote(
        version = "1.0.8",
        date = "2026-11-02",
        current = false,
        items = listOf(
            NoteItem(NoteItem.Kind.NEW, "本轮做完全新完成页：文案改为「这轮题目已经完成啦」，主按钮变成醒目的「返回」；答错的话多一个「刷本轮错题（N）」入口，一键只刷本轮做错的那几道"),
            NoteItem(NoteItem.Kind.NEW, "深度解题与「问 AI」改为逐字流式输出：答案和推理过程边生成边显示，不再让用户对着转圈干等"),
            NoteItem(NoteItem.Kind.NEW, "切题做成左右连续卡片：拖动时当前卡片跟手平移，两侧还能看到相邻题卡的边缘，滑一小段就能翻页"),
            NoteItem(NoteItem.Kind.NEW, "AI 任务花钱看得见了：「导入解析」和「AI 生成」都在开工前后各查一次 DeepSeek 官方余额，直接显示本轮实际扣了多少钱、账户还剩多少钱（与官方账单一致，不再是拿 token 单价估出来的数）"),
            NoteItem(NoteItem.Kind.FIX, "修复左右滑动切题时「动画播两遍 + 中间闪一下」：现在滑出动画走完的那一帧，新题就已经在原位，不再从对侧弹回来补一遍动画"),
            NoteItem(NoteItem.Kind.FIX, "修复滑动切题手感：手指拖多远卡片就走多远（最远正好滑到下一题，不会翻过头），轻轻滑一小段再短暂按住松手也能跳题，不必甩得飞快"),
            NoteItem(NoteItem.Kind.IMPROVE, "切题时卡片投影大幅减淡：由原来一离手就糊上 20dp 的硬边黑影，改为随位移从 0 平滑升起的一层浅浅暖色投影，滑完几乎看不出来"),
            NoteItem(NoteItem.Kind.FIX, "修复切题动画中与落地后「题干、选项行距和字号突然变一下」的跳动：相邻题卡不再用简化的预览排版，而是与正式题卡完全同构，滑入落地时一个像素都不动"),
            NoteItem(NoteItem.Kind.NEW, "导入页的「复制格式化提示词给 AI」改为让 AI 直接产出可导入的 JSON：拿到 .json 文件用「导入 .json」一步入库，不必再写成 Markdown 导回来重解析一遍"),
            NoteItem(NoteItem.Kind.IMPROVE, "速刷模式默认等待时间从 3 秒改为 2 秒（设置页仍可 1~10 秒自行调）"),
            NoteItem(NoteItem.Kind.IMPROVE, "震动反馈更短促有力：点选项、切题、判分都是一下干净利落的触感，三档强度现在真正有区别（不支持幅度控制的设备也做了区分）"),
            NoteItem(NoteItem.Kind.IMPROVE, "所有题目解析下方加一行小字提示「内容为 AI 生成，可能会出错」"),
            NoteItem(NoteItem.Kind.IMPROVE, "成本统计接入 DeepSeek 官方余额接口：设置页可直接查到账户余额，不再只靠 token 反推"),
            NoteItem(NoteItem.Kind.FIX, "修复范围刷题刷完后「第 30 / 30 题」这类做完的进度还挂在选择页上、点进去却是空页的问题：一轮做完即清"),
            NoteItem(NoteItem.Kind.FIX, "修复答案与题库原件冲突时的提示文案自相矛盾（出现「AI 答案 B 与题库答案 B 不一致」），现在明确以题库参考答案为准"),
            NoteItem(NoteItem.Kind.FIX, "修复 AI 解析整段失败重试后仍拿不到结果的问题，深度解题给出的答案会回写为最终答案，旧答案留作对照"),
        )
    ),
    ReleaseNote(
        version = "1.0.7",
        date = "2026-10-30",
        current = false,
        items = listOf(
            NoteItem(NoteItem.Kind.NEW, "支持直接导入 Markdown 题库（.md / .markdown）：识别 # 章节标题、1. 题号、- A. 选项、【答案】【解析】、公式与表格，无需先转成 PDF 或 docx"),
            NoteItem(NoteItem.Kind.NEW, "导入页新增「复制格式化提示词给 AI」：一键复制整理规范，粘给任意 AI 让它把杂乱题目整理成标准 Markdown，再存成 .md 导回即可"),
            NoteItem(NoteItem.Kind.IMPROVE, "RapidOCR 升级到 PP-OCRv5 模型：中文、符号（β、t₁/₂）与密集小字的识别明显更准，同时删掉旧模型与多余词表，安装包反而更小"),
            NoteItem(NoteItem.Kind.FIX, "修复扫描件里同一行并排的两个选项顺序颠倒的问题（如「A. 支气管哮喘  B. 高血压」被识别成 B 在前），该问题会导致选项与答案字母错位"),
            NoteItem(NoteItem.Kind.FIX, "修复长文本切块解析时把一道题劈成两半、造成缺题或选项不全的问题：现在按题目边界切分并保留重叠，跨段落的答案会自动回填到对应题目"),
            NoteItem(NoteItem.Kind.FIX, "修复成本预估不准：改为按 DeepSeek 官方价目分模型（flash / v4-pro）计价，并正确区分高峰与空闲时段（周末与法定节假日全天按空闲价）"),
            NoteItem(NoteItem.Kind.FIX, "修复 AI 回答与解析里单行 *斜体* / _斜体_ 标记不生效、直接露出星号下划线的问题，同时保证公式中的下标不被误当斜体"),
            NoteItem(NoteItem.Kind.FIX, "修复深度解题与「问 AI」输出里 ${'$'}${'$'}…${'$'}${'$'} 行间公式被当成普通文字整段吐出的问题（现在分式、根号、积分求和都能正常排版）"),
            NoteItem(NoteItem.Kind.FIX, "修复积分号与求和号的上下限被后面的变量指数覆盖（∫₀¹x² 曾显示成 ∫₀²x），以及 \\bigcup / \\bigcap 原样吐出符号名的问题"),
            NoteItem(NoteItem.Kind.FIX, "修复深度解题「有时能生成、有时报错」：对模型空返回、网关偶发错误与超时自动重试，上下文长度上限放宽，正文被截断时直接重试而不会写入半截解析"),
            NoteItem(NoteItem.Kind.IMPROVE, "深度解题完成后不再自动弹出「问 AI」面板——解析刚出来，先读为要；想追问点解析下方的「追问 AI」即可"),
            NoteItem(NoteItem.Kind.FIX, "修复「问 AI」还没有任何消息时，键盘弹起会把输入框压在一大片空白下、发送按钮不见的问题"),
            NoteItem(NoteItem.Kind.NEW, "切题增加左右平移动画：新题从手势方向滑入、旧题反向滑出，配合左右滑动切题的操作直觉"),
            NoteItem(NoteItem.Kind.IMPROVE, "统计页热力图长按后，日期与题数气泡改为悬浮在手指上方，不再固定在图下方"),
        )
    ),
    ReleaseNote(
        version = "1.0.6",
        date = "2026-10-29",
        current = false,
        items = listOf(
            NoteItem(NoteItem.Kind.NEW, "公式渲染全面重写：行内公式（如 x²、aₙ₊₁、Σᵢ₌₁ⁿ i）与正文同处一行自然换行；独占一行的公式按真实排版绘制——分式真正上下分层并带分数线、根号带横线罩住被开方数、求和与积分符号上下限归位、矩阵与分段函数成组排版"),
            NoteItem(NoteItem.Kind.NEW, "解析区支持完整 Markdown：标题、粗体、斜体、代码、引用、有序/无序列表、表格、分隔线都与公式混排，AI 回答不再是一坨纯文本"),
            NoteItem(NoteItem.Kind.NEW, "深度解题与「问 AI」的回答同样走 Markdown + 公式渲染，多步推导可以分条列出"),
            NoteItem(NoteItem.Kind.IMPROVE, "参考答案、选项、共用题干、错题本详情、测试报告、编辑列表统一接入公式渲染，不再出现裸露的 $\\frac{a}{b}$ 源码"),
            NoteItem(NoteItem.Kind.IMPROVE, "列表预览改为按行数省略，不再按字符硬截断（避免把公式命令从中间切断写成乱码）"),
            NoteItem(NoteItem.Kind.FIX, "选项里混入行间公式时整张卡片被撑成两行、内容居中错位的问题")
        )
    ),
    ReleaseNote(
        version = "1.0.5",
        date = "2026-10-29",
        current = false,
        items = listOf(
            NoteItem(NoteItem.Kind.NEW, "成本显示：生成答案的 Token 用量改为「总 token / 缓存命中 / 参考成本（元）」，按 DeepSeek 官方峰谷分时定价计算"),
            NoteItem(NoteItem.Kind.NEW, "错题本章节重刷：错题按章节归拢，可单选或多选章节做「全部重刷 / 随机抽检」，做题页与题库刷题完全一致"),
            NoteItem(NoteItem.Kind.NEW, "热力图增强：格子加大、默认近 8 周，可切换 8 / 16 / 32 周，长按任意格子查看当天日期与所刷题数"),
            NoteItem(NoteItem.Kind.NEW, "名词解释独立题型：解析时把「解释××」这类纯概念题与简答题分开，筛选、编辑、统计各自独立"),
            NoteItem(NoteItem.Kind.NEW, "生成范围新增「错误范围」：已有答案但缺解析的题单独成组，可批量补齐"),
            NoteItem(NoteItem.Kind.NEW, "「仅生成答案和极简解析」：解析压到 30 字以内，费用约为标准模式的一半"),
            NoteItem(NoteItem.Kind.NEW, "生成范围可按章节排序筛选，大题库只重跑某几章"),
            NoteItem(NoteItem.Kind.IMPROVE, "生成缺失统计改为「缺解析」口径，不再与缺答案混在一起"),
            NoteItem(NoteItem.Kind.IMPROVE, "导入 OCR 过程在应用内也显示进度条与已解析题数，不必只盯通知栏"),
            NoteItem(NoteItem.Kind.IMPROVE, "底栏按住横滑：改为滑到哪一格就切到哪一页，胶囊非线性弹簧吸附，跨多格不再卡顿"),
            NoteItem(NoteItem.Kind.FIX, "深度解题拿不到解析内容的问题"),
            NoteItem(NoteItem.Kind.FIX, "缓存命中率恒显示 0% 的统计问题"),
            NoteItem(NoteItem.Kind.FIX, "问 AI 输入框被输入法弹起的空白遮挡、发送按钮丢失"),
            NoteItem(NoteItem.Kind.FIX, "全库搜索搜不到结果"),
            NoteItem(NoteItem.Kind.FIX, "AI 生成完成后解析覆盖率统计不全")
        )
    ),
    ReleaseNote(
        version = "1.0.4",
        date = "2026-10-29",
        current = false,
        items = listOf(
            NoteItem(NoteItem.Kind.NEW, "问 AI 追问：深度解题完成后自动弹出对话输入框，可以接着问「为什么不选 C」「这题考的什么」这类追问，模型带着整道题和之前的对话回答"),
            NoteItem(NoteItem.Kind.NEW, "做题页常驻「问 AI」按钮：顶栏与解析区各有一个入口，不必先深度解题也能直接提问"),
            NoteItem(NoteItem.Kind.IMPROVE, "追问默认走主部模型，与深度解题同一套配置；没有配置模型时会明确提示去哪添加"),
            NoteItem(NoteItem.Kind.FIX, "追问途中切题不会再让回答串到新题目上"),
            NoteItem(NoteItem.Kind.FIX, "深度解题后题目解析不刷新的问题")
        )
    ),
    ReleaseNote(
        version = "1.0.3",
        date = "2026-10-29",
        current = false,
        items = listOf(
            NoteItem(NoteItem.Kind.NEW, "AI 深度解题：答完后一键让模型重做本题，给出完整推理链并覆盖保存，题干旁标注「已深度解析」"),
            NoteItem(NoteItem.Kind.NEW, "左右滑动切题：手指在题面上左右划即可上一题/下一题，切到做过的题自动还原当时的作答状态"),
            NoteItem(NoteItem.Kind.NEW, "回到上次进度：退出后再进来，做题范围页会置顶显示「上次进度」卡片，带时间戳与所在章节，多条记录可自选；忽略后不再打扰"),
            NoteItem(NoteItem.Kind.NEW, "公式渲染：题干与解析里的 LaTeX（分数、上下标、根号、求和积分等）改为真正的数学排版，不再显示反斜杠源码"),
            NoteItem(NoteItem.Kind.NEW, "生成进度自愈：若后台任务被系统回收，页面会识别并给出「修正进度」按钮，可按章节范围重新生成，不再永远卡在同一格"),
            NoteItem(NoteItem.Kind.NEW, "震动反馈独立开关：与提示音拆开，可各自开关"),
            NoteItem(NoteItem.Kind.IMPROVE, "顶栏统一为圆角矩形实体纸卡（去掉毛玻璃与副标题），字号加大、高度略增，设置页与其他页面完全对齐"),
            NoteItem(NoteItem.Kind.IMPROVE, "底部 Dock 重写：修掉切换页签时的动画掉帧；浅色模式补上高光描边；长按可拖动横滑切换，每跨一格短促震动"),
            NoteItem(NoteItem.Kind.IMPROVE, "所有页面标题改用更粗的衬线字重，像书页扉页"),
            NoteItem(NoteItem.Kind.IMPROVE, "做题页正确选项改为柔和的暗绿（原橄榄色偏灰），答错用赤陶红，一眼可辨"),
            NoteItem(NoteItem.Kind.IMPROVE, "参考答案优先：题库缺答案或选项不清时以 AI 参考答案为准，并标注「与参考答案一致」；两答案不一致时双栏对照"),
            NoteItem(NoteItem.Kind.IMPROVE, "解题提示词宽限到 80 字，解析更详尽，同时继续要求 LaTeX 公式"),
            NoteItem(NoteItem.Kind.FIX, "修复部分页面底部内容被系统手势条遮挡"),
            NoteItem(NoteItem.Kind.FIX, "修复设置页开关偶发点击无响应")
        )
    ),
    ReleaseNote(
        version = "1.0.2",
        date = "2026-10-28",
        items = listOf(
            NoteItem(NoteItem.Kind.NEW, "设置页重构为分组列表：学习、外观、数据、模型、提示词、关于，一屏可扫完"),
            NoteItem(NoteItem.Kind.NEW, "本地学习数据管理：学习记录导出为 JSON、清空收藏夹、重置刷题进度，均二次确认"),
            NoteItem(NoteItem.Kind.NEW, "做题音效开关（答对/答错不同音色，可静音）"),
            NoteItem(NoteItem.Kind.NEW, "暗黑护眼模式：橄榄柔光夜读配色，独立于系统深色设置，可手动切换"),
            NoteItem(NoteItem.Kind.NEW, "「关于」补齐作者 @Natsume、反馈邮箱、项目地址（可点击跳转）与开源声明"),
            NoteItem(NoteItem.Kind.IMPROVE, "安卓状态栏/水滴屏安全边距全页面统一，内容不再顶到摄像头上"),
            NoteItem(NoteItem.Kind.IMPROVE, "做题界面紧凑化：题干、选项、控制条尽量一屏装下"),
            NoteItem(NoteItem.Kind.IMPROVE, "小屏（<380dp）按钮组弹性折行，杜绝横向滚动与文字竖排挤压"),
            NoteItem(NoteItem.Kind.FIX, "修复窄屏下部分按钮被屏幕右缘裁切")
        )
    ),
    ReleaseNote(
        version = "1.0.1",
        date = "2026-10-27",
        items = listOf(
            NoteItem(NoteItem.Kind.NEW, "全新视觉系统：Anthropic 暖纸美学 + 苹果液态玻璃，羊皮纸底色、赤陶红主色、象牙白浮层"),
            NoteItem(NoteItem.Kind.NEW, "底部 iOS 悬浮 Dock 导航（毛玻璃胶囊 + 手势条避让），一级导航全部下移"),
            NoteItem(NoteItem.Kind.IMPROVE, "全站卡片、弹窗、导航栏改用毛玻璃 + 高光描边 + 内发光倒角"),
            NoteItem(NoteItem.Kind.IMPROVE, "衬线标题 + 无衬线正文 + 等宽数据的排版体系落地"),
            NoteItem(NoteItem.Kind.IMPROVE, "题库详情、导入、生成、编辑、搜索、测试报告等页面逐一视觉对齐"),
            NoteItem(NoteItem.Kind.FIX, "修复生成页在进度中断后无法重新开始")
        )
    ),
    ReleaseNote(
        version = "1.0.0",
        date = "2026-10-24",
        items = listOf(
            NoteItem(NoteItem.Kind.NEW, "多源导入：docx / 文字 PDF / 扫描 PDF / 拍照 / 粘贴文本，双 OCR 引擎（ML Kit 中文 + RapidOCR PP-OCRv3），双栏试卷阅读顺序自动重排"),
            NoteItem(NoteItem.Kind.NEW, "AI 结构化解析：题目、选项、答案、章节、页码、A1A2A3B 医学题型分组，后台运行断点续传"),
            NoteItem(NoteItem.Kind.NEW, "四种刷题模式 + 测试模式（组卷、答题卡、交卷报告）"),
            NoteItem(NoteItem.Kind.NEW, "三省六部多模型交叉验证：置信度 = 0.6×主部自评 + 0.4×一致率，分歧可展开"),
            NoteItem(NoteItem.Kind.NEW, "费用透明：批量生成 + 前缀缓存友好提示词，token 用量与命中率实时显示"),
            NoteItem(NoteItem.Kind.NEW, "学习工具：错题本、斩题墙、收藏、16 周热力图、每题用时、全库搜索"),
            NoteItem(NoteItem.Kind.NEW, "题库导出为通用 JSON（可选含/不含 AI 内容），完整往返导入"),
            NoteItem(NoteItem.Kind.IMPROVE, "全部原生库按 16KB 页对齐，兼容新设备"),
            NoteItem(NoteItem.Kind.IMPROVE, "ABI 分包，按设备架构安装对应 APK，体积更小")
        )
    )
)
