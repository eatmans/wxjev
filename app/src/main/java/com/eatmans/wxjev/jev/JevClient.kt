package com.eatmans.wxjev.jev

import com.eatmans.wxjev.core.Analysis
import com.eatmans.wxjev.core.ChatSnapshot
import com.eatmans.wxjev.core.Prefs
import com.eatmans.wxjev.core.RankedReply

/**
 * 三个拆分客户端之上的薄门面, 调用方保持单一入口。
 * 以 [Prefs] 构造——各路地址/密钥/模型即时读取, 设置里换供应商下一次调用即生效。
 */
class JevClient(prefs: Prefs) {

    private val judgeClient = JudgeClient(prefs)
    private val replyClient = ReplyClient(prefs)

    /** 7 道判断题。错误在 [Analysis.error] 里。 */
    fun judge(snapshot: ChatSnapshot, relationship: String): Analysis =
        judgeClient.judge(snapshot, relationship)

    /** 回复路起草 3 条, 判断路排序。 */
    fun draftAndRank(snapshot: ChatSnapshot, relationship: String): List<RankedReply> {
        val candidates = replyClient.draft(snapshot, relationship)
        return judgeClient.rank(snapshot, relationship, candidates)
    }

    /** 判断 + 候选, 顺序执行。设置页连通测试用。 */
    fun analyze(snapshot: ChatSnapshot, relationship: String): Analysis {
        val a = judge(snapshot, relationship)
        if (a.error != null) return a
        val ranked = try { draftAndRank(snapshot, relationship) } catch (e: Exception) { emptyList() }
        return a.copy(rankedReplies = ranked)
    }
}
