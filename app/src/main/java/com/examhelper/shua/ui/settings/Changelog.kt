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
        version = "1.0.6",
        date = "2026-10-29",
        current = true,
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
