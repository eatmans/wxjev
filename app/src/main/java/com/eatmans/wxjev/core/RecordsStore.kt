package com.eatmans.wxjev.core

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/**
 * 一次分析的完整记录（对话原文 + 判断 + 候选回复）。
 * 2026-09-22 用户拍板: 打破「聊天内容不落盘」红线, 只存本机 App 私有目录。
 */
data class AnalysisRecord(
    val id: String,
    val ts: Long,
    val pkg: String,
    val title: String?,
    val messages: List<Msg>,
    val intent: String?,
    val intentConf: Double?,
    val danger: Int?,
    val needs: String?,
    val action: String?,
    val replyNow: Boolean?,
    val replies: List<RankedReply>,
    val replyError: String? = null,
    val judgeError: String? = null
)

/** 记录存储: 内存可观察列表 + filesDir/records.json 原子持久化, LRU 上限 200。 */
object RecordsStore {

    private const val TAG = "JevAssist"
    private const val MAX_RECORDS = 200
    private const val FILE_NAME = "records.json"

    private val io = Executors.newSingleThreadExecutor()

    /** UI 可观察; add/clear 在主线程调, 写盘在工作线程。 */
    val records = mutableStateOf<List<AnalysisRecord>>(emptyList())

    @Volatile private var loaded = false
    private var appContext: Context? = null

    /** App 启动时调一次; 从盘恢复到内存。 */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (loaded) return
        loaded = true
        io.execute {
            val list = runCatching { loadFromDisk() }.getOrElse {
                Log.w(TAG, "records load failed: ${it.message}")
                emptyList()
            }
            records.value = list
        }
    }

    /** 新记录插到头部并裁剪; 同步更新内存(主线程), 异步落盘。 */
    fun add(record: AnalysisRecord) {
        records.value = (listOf(record) + records.value).take(MAX_RECORDS)
        persist()
    }

    fun clear() {
        records.value = emptyList()
        persist()
    }

    // ---------------------------------------------------------------- disk

    private fun file(ctx: Context) = File(ctx.filesDir, FILE_NAME)

    private fun loadFromDisk(): List<AnalysisRecord> {
        val ctx = appContext ?: return emptyList()
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        val out = ArrayList<AnalysisRecord>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val msgs = ArrayList<Msg>()
            o.optJSONArray("messages")?.let { ma ->
                for (j in 0 until ma.length()) {
                    val m = ma.optJSONObject(j) ?: continue
                    msgs.add(Msg(m.optString("side"), m.optString("text")))
                }
            }
            val replies = ArrayList<RankedReply>()
            o.optJSONArray("replies")?.let { ra ->
                for (j in 0 until ra.length()) {
                    val r = ra.optJSONObject(j) ?: continue
                    replies.add(RankedReply(r.optString("text"), r.optDouble("prob", 0.0)))
                }
            }
            out.add(AnalysisRecord(
                id = o.optString("id"),
                ts = o.optLong("ts"),
                pkg = o.optString("pkg"),
                title = o.optString("title").ifBlank { null },
                messages = msgs,
                intent = o.optString("intent").ifBlank { null },
                intentConf = o.optDouble("conf", Double.NaN).takeUnless { it.isNaN() },
                danger = o.optInt("danger", -1).takeIf { it >= 0 },
                needs = o.optString("needs").ifBlank { null },
                action = o.optString("action").ifBlank { null },
                replyNow = o.optBoolean("replyNow", false).takeIf { o.has("replyNow") },
                replies = replies,
                replyError = o.optString("replyError").ifBlank { null },
                judgeError = o.optString("judgeError").ifBlank { null }
            ))
        }
        return out
    }

    /** 原子写: 先 .tmp 再 rename, 工作线程执行。 */
    private fun persist() {
        val snapshot = records.value
        val ctx = appContext ?: return
        io.execute {
            runCatching {
                val arr = JSONArray()
                for (r in snapshot) arr.put(r.toJson())
                val f = file(ctx)
                val tmp = File(f.parentFile, FILE_NAME + ".tmp")
                tmp.writeText(arr.toString())
                if (!tmp.renameTo(f)) {
                    f.delete()
                    tmp.renameTo(f)
                }
            }.onFailure { Log.w(TAG, "records persist failed: ${it.message}") }
        }
    }

    private fun AnalysisRecord.toJson(): JSONObject {
        val msgs = JSONArray()
        messages.forEach { m -> msgs.put(JSONObject().put("side", m.side).put("text", m.text)) }
        val reps = JSONArray()
        replies.forEach { r -> reps.put(JSONObject().put("text", r.text).put("prob", r.prob)) }
        val o = JSONObject()
            .put("id", id)
            .put("ts", ts)
            .put("pkg", pkg)
            .put("title", title ?: "")
            .put("messages", msgs)
        intent?.let { o.put("intent", it) }
        intentConf?.let { o.put("conf", it) }
        danger?.let { o.put("danger", it) }
        needs?.let { o.put("needs", it) }
        action?.let { o.put("action", it) }
        replyNow?.let { o.put("replyNow", it) }
        o.put("replies", reps)
        replyError?.let { o.put("replyError", it) }
        judgeError?.let { o.put("judgeError", it) }
        return o
    }

    fun newId(): String = UUID.randomUUID().toString().take(8)
}
