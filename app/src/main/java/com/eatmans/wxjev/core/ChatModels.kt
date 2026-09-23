package com.eatmans.wxjev.core

import android.graphics.Rect

/** 一条聊天气泡。side 为 "me"(右侧,我) 或 "other"(左侧,对方)。 */
data class Msg(val side: String, val text: String)

/**
 * 树能定位但读不到正文的气泡（正文自绘型 App, OCR 兜底用, M5）。
 * [rect] 为屏幕坐标; [side] 是树在气泡周围能推断出的说话人。
 */
data class BubbleRect(val rect: Rect, val side: String)

/**
 * 当前打开会话的一帧快照（适配器契约的产出, 见 capture/ChatAppAdapter）。
 *
 * 契约（纪律级, 混用会引发误判）:
 * - adapter.extract 返回 null = 不在该 App 的聊天窗（服务静默）;
 * - 返回本类但 [messages] 为空 = 在聊天窗但树里没正文 —— 唯一触发 OCR 兜底的情况,
 *   此时 [bubbleRects] 可能非空。
 *
 * [note] 是关于本帧采集方式的备注, 面板原样展示（如 OCR 帧无法区分说话人）。
 */
data class ChatSnapshot(
    val title: String?,
    val messages: List<Msg>,
    val bubbleRects: List<BubbleRect> = emptyList(),
    val note: String? = null
) {
    val latestFrom: String? get() = messages.lastOrNull()?.side

    /** 最近几条消息的稳定签名, 用于识别真实变化（去重/防抖）。 */
    fun signature(): String =
        messages.takeLast(6).joinToString("|") { "${it.side}:${it.text}" }
}

/** 一帧快照的 Jev 判断结果 + 排序后的候选回复。 */
data class Analysis(
    val trueIntent: Choice?,
    val dangerLevel: Score?,
    val sheNeeds: Choice?,
    val shouldReplyNow: Double?,
    val bestAction: Choice?,
    val tensionResolved: Double?,
    val literalQuestion: Double?,
    val rankedReplies: List<RankedReply>,
    val latencyMs: Long,
    val error: String? = null
)

data class Choice(val choice: String, val confidence: Double, val probabilities: Map<String, Double>)
data class Score(val score: Double, val confidence: Double, val maxLevel: Int)
data class RankedReply(val text: String, val prob: Double)
