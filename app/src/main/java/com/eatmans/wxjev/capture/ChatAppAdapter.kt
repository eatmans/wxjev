package com.eatmans.wxjev.capture

import android.content.res.Resources
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.eatmans.wxjev.core.ChatSnapshot
import com.eatmans.wxjev.core.Msg

/**
 * 一个聊天 App 一条采集规则。适配器把该 App 打开的聊天窗变成中立的
 * [ChatSnapshot]; 下游（Jev 判断、悬浮窗、回填）全部与 App 无关。
 *
 * [extract] 的三值契约（纪律级, 服务依赖它）:
 * - null            → 不在该 App 的聊天窗（列表页/朋友圈/设置页…）, 服务静默;
 * - 消息列表为空    → 在聊天窗, 但树里没有正文 → 触发 OCR 兜底（M5）;
 *                     各适配器自行说明什么证明「在聊天窗」;
 * - 消息列表非空    → 正常采集。
 *
 * 判「在不在聊天窗」一律看树内特征节点, 不看 Activity 名
 * （微信/QQ/X 全程单 Activity, 计划书 2.1-6）。
 *
 * 伪装无障碍服务（注册为 SelectToSpeakService）用于读对普通服务混淆节点树的
 * App（微信 8.0.52+）。
 */
interface ChatAppAdapter {
    val pkg: String
    fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot?
}

/** 共用的小工具。 */
private fun looksLikeTimestamp(t: String): Boolean =
    Regex("""\d{1,2}[:：]\d{2}""").containsMatchIn(t) ||
        Regex("""\d+月\d+日""").containsMatchIn(t) ||
        t == "昨天" || t == "今天"

/**
 * 顶部标题栏里的会话标题: 第一条气泡上方、大致居中的最靠上短文本。
 * 约束保证不会抓到聊天内的时间戳。QQ 的标题 id 缺失时用它兜底, X 也用它。
 * 微信有自己的 [findWeChatTitle]（群标题需要额外过滤）。
 */
internal fun findTitleInActionBar(
    root: AccessibilityNodeInfo,
    firstBubbleTop: Int,
    width: Int,
    res: Resources,
    minCenterRatio: Double = 0.25,
    maxCenterRatio: Double = 0.75
): String? {
    val actionBarMax = minOf(firstBubbleTop, (res.displayMetrics.heightPixels * 0.14).toInt())
    val minCenterX = (width * minCenterRatio).toInt()
    val maxCenterX = (width * maxCenterRatio).toInt()
    val stack = ArrayDeque<AccessibilityNodeInfo>()
    stack.addLast(root)
    var best: String? = null
    var bestTop = Int.MAX_VALUE
    var guard = 0
    while (stack.isNotEmpty() && guard < 5000) {
        guard++
        val node = stack.removeLast()
        val text = node.text?.toString()
        if (!text.isNullOrBlank() && text.length <= 24 && !looksLikeTimestamp(text)) {
            val b = Rect(); node.getBoundsInScreen(b)
            if (b.bottom in 1 until actionBarMax && b.centerX() in minCenterX..maxCenterX) {
                if (b.top < bestTop) { bestTop = b.top; best = text }
            }
        }
        for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
    }
    return best
}

/** 中文句读——真实消息/公告行有句读, 标题不会有。 */
private val WECHAT_TITLE_EXCLUDE_PUNCT = Regex("""[，。？！、]""")

/** 微信群标题尾部的 "(N)" 人数后缀, 半/全角。 */
private val WECHAT_GROUP_COUNT_SUFFIX = Regex("""[（(]\d+[）)]""")

/**
 * 微信会话标题: 群公告或游离消息可能恰好落在「最靠上、短、居中」的位置被
 * 误当标题（实测抓到过聊天行「我有企微，但是用不习惯」）。候选必须:
 * 不像句子（无中文句读）且在第一条气泡上方; 剩下的里面带 "(N)" 人数后缀的
 * 群标题优先。都不满足 → null（调用方用 lastGoodTitle 沿用上一个稳定标题,
 * 不瞎猜）。
 */
internal fun findWeChatTitle(
    root: AccessibilityNodeInfo,
    firstBubbleTop: Int,
    width: Int,
    res: Resources
): String? {
    val actionBarMax = minOf(firstBubbleTop, (res.displayMetrics.heightPixels * 0.14).toInt())
    val minCenterX = (width * 0.25).toInt()
    val maxCenterX = (width * 0.75).toInt()
    val stack = ArrayDeque<AccessibilityNodeInfo>()
    stack.addLast(root)
    var bestPlain: String? = null
    var bestPlainTop = Int.MAX_VALUE
    var bestCounted: String? = null
    var bestCountedTop = Int.MAX_VALUE
    var guard = 0
    while (stack.isNotEmpty() && guard < 5000) {
        guard++
        val node = stack.removeLast()
        val text = node.text?.toString()
        if (!text.isNullOrBlank() && text.length <= 24 && !looksLikeTimestamp(text) &&
            !WECHAT_TITLE_EXCLUDE_PUNCT.containsMatchIn(text)
        ) {
            val b = Rect(); node.getBoundsInScreen(b)
            if (b.bottom in 1 until actionBarMax && b.bottom < firstBubbleTop &&
                b.centerX() in minCenterX..maxCenterX
            ) {
                if (WECHAT_GROUP_COUNT_SUFFIX.containsMatchIn(text)) {
                    if (b.top < bestCountedTop) { bestCountedTop = b.top; bestCounted = text }
                } else if (b.top < bestPlainTop) { bestPlainTop = b.top; bestPlain = text }
            }
        }
        for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
    }
    return bestCounted ?: bestPlain
}

/**
 * 微信（com.tencent.mm）。消息气泡带稳定 id; 说话人按气泡水平位置判
 * （右=我, 左=对方）。
 *
 * 「在聊天窗」= 树里存在 id/bkl 气泡容器（哪怕正文被混淆剥空）——只有它算数,
 * 会话列表页的可编辑搜索框因此不能再冒充聊天窗（v1.3 修复: 曾在列表页误触发
 * OCR 兜底）。微信 8.0.52+ 对普通服务隐藏节点文本, 所以这里的空读（bkl 无文本）
 * 正是 OCR 兜底要接的情况——伪装生效时则直接读到正文。
 *
 * 注意: bkl 是混淆名, 随版本可能漂移（计划书 7.2 版本漂移预案: 配置化 id +
 * 特征探测兜底, 微信升级只改这里的 BUBBLE_ID）。
 */
class WeChatAdapter : ChatAppAdapter {
    override val pkg = "com.tencent.mm"

    override fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot? {
        val width = res.displayMetrics.widthPixels
        val bubbles = ArrayList<Triple<Int, Int, String>>() // top, centerX, text
        var firstBubbleTop = Int.MAX_VALUE
        var isChat = false

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 5000) {
            guard++
            val node = stack.removeLast()
            val id = node.viewIdResourceName
            val text = node.text?.toString()
            if (id == BUBBLE_ID) {
                isChat = true
                if (!text.isNullOrBlank()) {
                    val b = Rect(); node.getBoundsInScreen(b)
                    bubbles.add(Triple(b.top, b.centerX(), text))
                    if (b.top < firstBubbleTop) firstBubbleTop = b.top
                }
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        val title = findWeChatTitle(root, firstBubbleTop, width, res)
        // 在聊天窗但读不到正文 → 空快照（OCR 兜底信号, M5 接）。
        if (bubbles.isEmpty()) return if (isChat) ChatSnapshot(title, emptyList()) else null
        bubbles.sortBy { it.first }
        val msgs = bubbles.map { (_, cx, text) ->
            Msg(if (cx > width / 2) "me" else "other", text)
        }
        return ChatSnapshot(title, msgs)
    }

    companion object {
        private const val BUBBLE_ID = "com.tencent.mm:id/bkl"
    }
}

/**
 * 手机 QQ（com.tencent.mobileqq）。节点不混淆（QQ 9.3.50 / 小米 14 实测）:
 * 消息正文是带 id/mjn 的普通 TextView, 只采该 id 自然排除时间戳、群昵称
 * （id/mjq）与全宽系统提示条。
 *
 * 全 App 单 SplashActivity（fragment 架构）,「在不在聊天窗」只能看树——
 * 这里用聊天输入框 id/input: 无输入框 → null; 有输入框无正文 → 空快照。
 *
 * 说话人: QQ 把头像钉在自己一侧的外沿（对方 left≈宽×13%, 我在右侧对称）。
 * 长消息中心点会过半屏, 所以比较气泡哪条边贴着头像列, 不用中心点。
 */
class QQAdapter : ChatAppAdapter {
    override val pkg = "com.tencent.mobileqq"

    override fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot? {
        val width = res.displayMetrics.widthPixels
        val bubbles = ArrayList<Bubble>() // top, left, right, text
        var firstBubbleTop = Int.MAX_VALUE
        var title: String? = null
        var hasInput = false

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 5000) {
            guard++
            val node = stack.removeLast()
            val id = node.viewIdResourceName
            val text = node.text?.toString()
            if (id == BUBBLE_ID && !text.isNullOrBlank()) {
                val b = Rect(); node.getBoundsInScreen(b)
                bubbles.add(Bubble(b.top, b.left, b.right, text))
                if (b.top < firstBubbleTop) firstBubbleTop = b.top
            }
            if (!hasInput && id == INPUT_ID) hasInput = true
            if (id == TITLE_ID && title == null) text?.let { if (it.isNotBlank()) title = it }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        if (bubbles.isEmpty() && !hasInput) return null

        if (title == null) title = findTitleInActionBar(root, firstBubbleTop, width, res)
        if (bubbles.isEmpty()) return ChatSnapshot(title, emptyList())

        val avatarEdge = (width * 0.13).toInt()
        bubbles.sortBy { it.top }
        val msgs = bubbles.map { b ->
            val dl = kotlin.math.abs(b.left - avatarEdge)
            val dr = kotlin.math.abs((width - avatarEdge) - b.right)
            Msg(if (dr < dl) "me" else "other", b.text)
        }
        return ChatSnapshot(title, msgs)
    }

    private data class Bubble(val top: Int, val left: Int, val right: Int, val text: String)

    companion object {
        private const val BUBBLE_ID = "com.tencent.mobileqq:id/mjn"
        private const val TITLE_ID = "com.tencent.mobileqq:id/371"
        private const val INPUT_ID = "com.tencent.mobileqq:id/input"
    }
}

/** X 行尾粘的时间戳 "8:11 上午" / "10:29 下午" / "8:11 AM"。 */
private val X_TAIL_TIME = Regex("""\d{1,2}[:：]\d{2}\s*(上午|下午|AM|PM|am|pm)?$""")

/** X 用 "。" 做字段分隔, 消息结尾可能拖一串。 */
private val X_TRAILING_DOTS = Regex("""。+$""")

/**
 * 拆一条 X 私信行的 contentDescription → (发件人, 正文)。
 * "你：你这个说的就是那个虚拟人物，是吗？。8:11 上午。Read。" → ("你", "你这个说的就是那个虚拟人物，是吗？")
 * 发件人取第一个分隔符（中文界面全角 "：", 其它语言粗略 ": "）之前;
 * 其后是正文 + 尾部 chrome（回执/时间/粘连句号）, 按序剥掉。
 * 用户真实输入的标点（"是吗？"）保留。无分隔符或剥完为空 → null。
 */
private fun parseXDesc(desc: String): Pair<String, String>? {
    val full = desc.indexOf('：')
    val half = desc.indexOf(": ")
    val cut: Int
    val skip: Int
    when {
        full >= 0 && (half < 0 || full <= half) -> { cut = full; skip = 1 }
        half >= 0 -> { cut = half; skip = 2 }
        else -> return null
    }
    val sender = desc.substring(0, cut).trim()
    var body = desc.substring(cut + skip).trim()
    for (tail in arrayOf("Read。", "Read", "已读。", "已读")) {
        if (body.endsWith(tail)) { body = body.removeSuffix(tail).trim(); break }
    }
    body = X_TRAILING_DOTS.replace(body, "").trim()
    X_TAIL_TIME.find(body)?.let { body = body.substring(0, it.range.first).trim() }
    body = X_TRAILING_DOTS.replace(body, "").trim()
    if (sender.isEmpty() || body.isEmpty()) return null
    return sender to body
}

/**
 * X / Twitter（com.twitter.android）私信。X 12.25.2 / 小米 14 / 中文界面实测。
 *
 * 私信页是 Compose UI: 每条消息是一个无 resource-id 的全宽 android.view.View,
 * text 为空, 全部信息在 contentDescription（"All-In：重新写了一个😂。10:29 下午。"）。
 * 日期分隔条是无 "：" 的 TextView, 按「类名 + 全宽 + 含分隔符」过滤排除;
 * 附件行内部嵌套引用帖子的 TextView, 只取行 View 自身的 desc, 不采子节点。
 *
 * 所有页面同一个 MainActivity, 判窗只能看树: 会话页有消息 EditText,
 * 私信列表页没有; 列表行长得像但 desc 是 "All-In, @all_in_2026, …", 用 ", @"
 * 再排除一次。
 *
 * 说话人按发件人标签（"你"/"You"）判——每行都全宽, 几何说明不了问题。
 */
class XAdapter : ChatAppAdapter {
    override val pkg = "com.twitter.android"

    override fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot? {
        val width = res.displayMetrics.widthPixels
        val rows = ArrayList<Row>()
        var firstRowTop = Int.MAX_VALUE
        var hasInput = false
        // 全宽 View 且 desc 含分隔符（"：" / ": "）—— 消息行的形状, 不论能否完整解析。
        var hasMessageRowShape = false
        // 空会话页仍有的占位文本。
        var hasDmLabel = false
        // 私信列表页独有信号: 「新私信」入口, 或「聊天」标题下没有任何行。
        var hasNewDmMarker = false
        var sawListHeading = false

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 6000) {
            guard++
            val node = stack.removeLast()
            val cls = node.className?.toString()
            if (!hasInput && (node.isEditable || cls == "android.widget.EditText")) hasInput = true

            val desc = node.contentDescription?.toString()
            if (desc == "新私信" || desc == "New message") hasNewDmMarker = true
            if (cls == "android.view.View" && !desc.isNullOrBlank() && !desc.contains(", @")) {
                val b = Rect(); node.getBoundsInScreen(b)
                if (b.left == 0 && b.right == width) {
                    if (desc.contains('：') || desc.contains(": ")) hasMessageRowShape = true
                    val parsed = parseXDesc(desc)
                    if (parsed != null) {
                        rows.add(Row(b.top, parsed.first, parsed.second))
                        if (b.top < firstRowTop) firstRowTop = b.top
                    }
                }
            }

            if (cls == "android.widget.TextView") {
                val text = node.text?.toString()?.trim()
                if (text == "私信" || text == "Message" || text == "发送私信") hasDmLabel = true
                if (text == "聊天" || text == "Messages") sawListHeading = true
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        // 「这是私信列表不是会话」的显式信号——先于下面的通用规则判,
        // 免得列表页搜索框的 EditText 被当成 hasInput。
        if (hasNewDmMarker || (sawListHeading && rows.isEmpty())) return null

        // 聊天窗需要同时有输入框和会话页独有的东西（消息行形状/空会话占位）。
        if (!hasInput || !(hasMessageRowShape || hasDmLabel)) return null

        // X 的会话标题偏左（x≈300..443 of 1200）, 放宽居中带。
        val title = findTitleInActionBar(root, firstRowTop, width, res, 0.15, 0.85)
        if (rows.isEmpty()) return ChatSnapshot(title, emptyList())
        rows.sortBy { it.top }
        val msgs = rows.map { Msg(if (it.sender == "你" || it.sender == "You") "me" else "other", it.text) }
        return ChatSnapshot(title, msgs)
    }

    private data class Row(val top: Int, val sender: String, val text: String)
}
