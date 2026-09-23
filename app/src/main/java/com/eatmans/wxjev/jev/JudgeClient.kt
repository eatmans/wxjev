package com.eatmans.wxjev.jev

import android.util.Log
import com.eatmans.wxjev.core.Analysis
import com.eatmans.wxjev.core.ChatSnapshot
import com.eatmans.wxjev.core.Choice
import com.eatmans.wxjev.core.Prefs
import com.eatmans.wxjev.core.RankedReply
import com.eatmans.wxjev.core.Score
import org.json.JSONObject

/**
 * 判断路（Jev）专用: 7 道判断题一次调用 + 候选排序题。
 * 读 Prefs 的 judgeProvider / judgeBaseUrl / judgeKey / judgeModel;
 * 本类没有任何生成式调用。
 */
class JudgeClient(private val prefs: Prefs) {

    /** 7 道判断题（快, ~1s）。错误放在 [Analysis.error] 返回, 不抛出。 */
    fun judge(snapshot: ChatSnapshot, relationship: String): Analysis {
        val start = System.currentTimeMillis()
        return try {
            val answers = postDecisions(snapshot, relationship, JevQuestions.judge())
            Analysis(
                trueIntent = parseChoice(answers.optJSONObject("true_intent")),
                dangerLevel = parseScore(answers.optJSONObject("danger_level")),
                sheNeeds = parseChoice(answers.optJSONObject("she_needs")),
                shouldReplyNow = answers.optJSONObject("should_reply_now")?.optDouble("noul"),
                bestAction = parseChoice(answers.optJSONObject("best_action")),
                tensionResolved = answers.optJSONObject("tension_resolved")?.optDouble("noul"),
                literalQuestion = answers.optJSONObject("literal_question")?.optDouble("noul"),
                rankedReplies = emptyList(),
                latencyMs = System.currentTimeMillis() - start
            )
        } catch (e: Exception) {
            Log.w(TAG, "judge failed: ${e.message}")
            Analysis(null, null, null, null, null, null, null, emptyList(),
                System.currentTimeMillis() - start, error = e.message ?: "判断接口请求失败")
        }
    }

    /** 问 Jev 哪条候选最好; 失败抛出。 */
    fun rank(
        snapshot: ChatSnapshot,
        relationship: String,
        candidates: List<String>
    ): List<RankedReply> {
        val questions = JSONObject().put("best_reply",
            JevQuestions.rankQuestion(candidates).getJSONObject("best_reply"))
        val answers = postDecisions(snapshot, relationship, questions)
        return parseRanked(answers.optJSONObject("best_reply"), candidates)
    }

    private fun postDecisions(
        snapshot: ChatSnapshot,
        relationship: String,
        questions: JSONObject
    ): JSONObject {
        val url = prefs.judgeEndpoint()
        val body = JSONObject()
            .put("model", prefs.judgeModel)
            .put("state", JevQuestions.buildState(snapshot, relationship))
            .put("questions", questions)
        val resp = HttpJson.post(url, prefs.judgeKey, body, Route.JUDGE, HttpJson.headersFor(url))
        return resp.optJSONObject("answers") ?: JSONObject()
    }

    private fun parseChoice(o: JSONObject?): Choice? {
        o ?: return null
        val probs = HashMap<String, Double>()
        o.optJSONObject("probabilities")?.let { p ->
            p.keys().forEach { k -> probs[k] = p.optDouble(k) }
        }
        return Choice(o.optString("choice"), o.optDouble("confidence", 0.0), probs)
    }

    private fun parseScore(o: JSONObject?): Score? {
        o ?: return null
        val legend = o.optJSONObject("legend")
        val maxLevel = legend?.keys()?.asSequence()?.mapNotNull { it.toIntOrNull() }?.maxOrNull() ?: 9
        return Score(o.optDouble("score", 0.0), o.optDouble("confidence", 0.0), maxLevel)
    }

    private fun parseRanked(o: JSONObject?, candidates: List<String>): List<RankedReply> {
        val keys = listOf("reply_a", "reply_b", "reply_c")
        val probs = o?.optJSONObject("probabilities")
        val list = candidates.mapIndexed { i, text ->
            RankedReply(text, probs?.optDouble(keys.getOrElse(i) { "" }, 0.0) ?: 0.0)
        }
        return list.sortedByDescending { it.prob }
    }

    companion object { private const val TAG = "JevAssist" }
}
