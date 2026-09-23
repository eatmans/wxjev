package com.eatmans.wxjev.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.eatmans.wxjev.core.AnalysisRecord
import com.eatmans.wxjev.core.RecordsStore

/** 记录列表页: 每次分析一条, 点开 BottomSheet 看对话原文 + 判断 + 候选详情。 */
object RecordsPage {

    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    @Composable
    fun Content() {
        var detail by remember { mutableStateOf<AnalysisRecord?>(null) }
        var showDetail by remember { mutableStateOf(false) }
        val ctx = LocalContext.current
        val scrollBehavior = MiuixScrollBehavior()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = "记录",
                    subtitle = "每次分析一条，只存本机",
                    scrollBehavior = scrollBehavior,
                    actions = {
                        if (RecordsStore.records.value.isNotEmpty()) {
                            Text(
                                text = "清空",
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(end = 20.dp)
                                    .clickable { 
                                        RecordsStore.clear()
                                        Toast.makeText(ctx, "已清空", Toast.LENGTH_SHORT).show()
                                    }
                            )
                        }
                    }
                )
            }
        ) { padding ->
            val list = RecordsStore.records.value
            if (list.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = padding.calculateTopPadding() + 80.dp),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "暂无记录",
                        fontSize = 15.sp,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "进微信聊天页，点智囊球「分析当前对话」",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(
                        top = padding.calculateTopPadding() + 6.dp,
                        bottom = 20.dp
                    )
                ) {
                    items(list, key = { it.id }) { r ->
                        RecordCard(r) { detail = r; showDetail = true }
                    }
                }
            }
        }

        OverlayBottomSheet(
            title = "记录详情",
            show = showDetail,
            allowDismiss = true,
            enableNestedScroll = true,
            onDismissRequest = { showDetail = false },
            onDismissFinished = { detail = null }
        ) {
            detail?.let { DetailContent(it) }
        }
    }

    @Composable
    private fun RecordCard(r: AnalysisRecord, onClick: () -> Unit) {
        Card(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 5.dp)
                .clickable(onClick = onClick)
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row {
                    Text(
                        text = r.title ?: "未命名会话",
                        fontSize = 15.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = timeFmt.format(Date(r.ts)),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
                val bits = buildList {
                    r.danger?.let { add("危险 ${it}") }
                    r.intent?.let { add(INTENTLabel(it)) }
                    if (r.judgeError != null) add("判断失败")
                    else if (r.replyError != null) add("回复失败")
                    else if (r.replies.isEmpty()) add("无候选")
                    else add("${r.replies.size} 条候选")
                }
                Text(
                    text = bits.joinToString(" · "),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp),
                    maxLines = 1
                )
            }
        }
    }

    /** 详情: 对话原文 → 判断 → 候选 → 错误。 */
    @Composable
    private fun DetailContent(r: AnalysisRecord) {
        LazyColumn(modifier = Modifier.padding(bottom = 24.dp)) {
            item { SmallTitle(text = "对话原文（${r.messages.size} 条）") }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        if (r.messages.isEmpty()) {
                            Text(text = "（无采集内容）", fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        r.messages.forEach { m ->
                            Text(
                                text = (if (m.side == "me") "我：" else "对方：") + m.text,
                                fontSize = 13.5.sp,
                                color = if (m.side == "me") MiuixTheme.colorScheme.onSurfaceVariantSummary
                                else MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(vertical = 3.dp)
                            )
                        }
                    }
                }
            }
            item { SmallTitle(text = "判断") }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        if (r.judgeError != null) {
                            Text(text = "判断失败：${r.judgeError}", fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.primary)
                        }
                        r.danger?.let { KeyValue("危险等级", "$it/10") }
                        r.intent?.let { KeyValue("真实意图", INTENTLabel(it) + pct(r.intentConf)) }
                        r.needs?.let { KeyValue("对方需要", NEEDSLabel(it)) }
                        r.action?.let { KeyValue("建议动作", ACTIONLabel(it)) }
                        r.replyNow?.let { KeyValue("可给实质", if (it) "是" else "否") }
                    }
                }
            }
            item { SmallTitle(text = "候选回复（Jev 排序）") }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        if (r.replyError != null) {
                            Text(text = "回复失败：${r.replyError}", fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.primary)
                        } else if (r.replies.isEmpty()) {
                            Text(text = "（无候选）", fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        r.replies.forEachIndexed { i, rep ->
                            Text(
                                text = "#${i + 1} · ${(rep.prob * 100).toInt()}%",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = if (i == 0) 0.dp else 8.dp)
                            )
                            Text(text = rep.text, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun KeyValue(label: String, value: String) {
        Row(modifier = Modifier.padding(vertical = 3.dp)) {
            Text(
                text = label, fontSize = 13.5.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.width(84.dp)
            )
            Text(text = value, fontSize = 13.5.sp)
        }
    }

    private fun pct(d: Double?): String =
        if (d == null) "" else "（置信 ${(d * 100).toInt()}%）"

    private val INTENT_MAP = mapOf(
        "confirm_you_care" to "确认你在不在乎", "vent_anger" to "在发泄情绪",
        "request_action" to "要你办事", "seek_explanation" to "要个解释",
        "casual_chat" to "随便聊聊", "close_topic" to "事情过去了")
    private val NEEDS_MAP = mapOf(
        "apology" to "道歉", "action" to "具体行动", "explanation" to "解释",
        "care" to "你的在乎", "nothing" to "（不用做什么）")
    private val ACTION_MAP = mapOf(
        "check_history" to "翻聊天记录", "apologize" to "先道歉", "give_commitment" to "给承诺",
        "explain" to "解释清楚", "acknowledge" to "接住情绪", "say_less" to "少说两句",
        "make_plan" to "定个安排")

    private fun INTENTLabel(k: String) = INTENT_MAP[k] ?: k
    private fun NEEDSLabel(k: String) = NEEDS_MAP[k] ?: k
    private fun ACTIONLabel(k: String) = ACTION_MAP[k] ?: k
}
