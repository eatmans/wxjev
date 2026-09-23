package com.eatmans.wxjev.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.eatmans.wxjev.core.ChatSnapshot
import com.eatmans.wxjev.core.Msg
import com.eatmans.wxjev.core.Prefs
import com.eatmans.wxjev.jev.JudgeClient
import com.eatmans.wxjev.jev.ReplyClient
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * 设置页状态与逻辑（从原 SettingsActivity 迁移）。
 * 框内当前值即时生效于测试按钮（草稿 Prefs），「保存」才写入真实配置。
 */
class SettingsState(private val activity: ComponentActivity) {

    private val prefs = Prefs(activity)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    val judgeIdx = mutableStateOf(0)
    val judgeBase = mutableStateOf("")
    val judgeModel = mutableStateOf("")
    val judgeKey = mutableStateOf("")
    val judgeResult = mutableStateOf("")

    val replyIdx = mutableStateOf(0)
    val replyBase = mutableStateOf("")
    val replyModel = mutableStateOf("")
    val replyKey = mutableStateOf("")
    val replyResult = mutableStateOf("")

    val rel = mutableStateOf("")
    val wl = mutableStateOf("")
    val autoAnalyze = mutableStateOf(false)
    val dailyCap = mutableStateOf("50")
    val opacity = mutableStateOf(32f)   // 0..40, +60 = 百分比

    init {
        judgeIdx.value = when (prefs.judgeProvider) {
            Prefs.PROVIDER_OPENROUTER -> 1
            Prefs.PROVIDER_CUSTOM -> 2
            else -> 0
        }
        judgeBase.value = prefs.judgeBaseUrl
        judgeModel.value = prefs.judgeModel
        judgeKey.value = prefs.judgeKey
        replyIdx.value = when (prefs.replyBaseUrl.trim().trimEnd('/')) {
            Prefs.DEFAULT_REPLY_BASE -> 0
            Prefs.DEEPSEEK_BASE -> 1
            Prefs.DASHSCOPE_BASE -> 2
            else -> 3
        }
        replyBase.value = prefs.replyBaseUrl
        replyModel.value = prefs.replyModel
        replyKey.value = prefs.replyKey
        rel.value = prefs.relationship
        wl.value = prefs.whitelist.joinToString("\n")
        autoAnalyze.value = prefs.autoAnalyze
        dailyCap.value = prefs.autoDailyCap.toString()
        opacity.value = (prefs.overlayOpacity - 60).toFloat()
    }

    fun onJudgeProvider(idx: Int) {
        judgeIdx.value = idx
        when (idx) {
            0 -> { judgeBase.value = Prefs.DEFAULT_JUDGE_BASE_TYPESAFE; judgeModel.value = Prefs.DEFAULT_JUDGE_MODEL_TYPESAFE }
            1 -> { judgeBase.value = Prefs.DEFAULT_JUDGE_BASE_OPENROUTER; judgeModel.value = Prefs.DEFAULT_JUDGE_MODEL_OPENROUTER }
            2 -> judgeBase.value = expandJudgeUrl(judgeBase.value)
        }
    }

    fun onReplyProvider(idx: Int) {
        replyIdx.value = idx
        when (idx) {
            0 -> { replyBase.value = Prefs.DEFAULT_REPLY_BASE; replyModel.value = Prefs.DEFAULT_REPLY_MODEL }
            1 -> { replyBase.value = Prefs.DEEPSEEK_BASE; replyModel.value = Prefs.DEEPSEEK_MODEL }
            2 -> { replyBase.value = Prefs.DASHSCOPE_BASE; replyModel.value = Prefs.DASHSCOPE_MODEL }
        }
    }

    fun testJudge() {
        val base = judgeBase.value.trim()
        val key = judgeKey.value.trim()
        val model = judgeModel.value.trim()
        if (key.isBlank()) { judgeResult.value = "请先填密钥"; return }
        val provider = resolveJudgeProvider(judgeIdx.value, base)
        if (provider == Prefs.PROVIDER_CUSTOM && base.isBlank()) {
            judgeResult.value = "自定义要填完整 URL"; return
        }
        if (provider == Prefs.PROVIDER_CUSTOM && model.isBlank()) {
            judgeResult.value = "自定义要填模型名"; return
        }
        judgeResult.value = "测试中…"
        val probe = draftPrefs(SCRATCH_JUDGE) {
            judgeProvider = provider
            judgeBaseUrl = base.ifBlank { defaultJudgeBase(provider) }
            judgeKey = key
            judgeModel = model.ifBlank { defaultJudgeModel(provider) }
        }
        worker.execute {
            val t0 = System.currentTimeMillis()
            val demo = ChatSnapshot("连通测试", listOf(Msg("other", "在吗？"), Msg("me", "在")))
            val a = JudgeClient(probe).judge(demo, prefs.relationship)
            val ms = System.currentTimeMillis() - t0
            main.post {
                judgeResult.value = if (a.error != null) "失败（${ms}ms）：${a.error}"
                else "成功 ${ms}ms · 意图=${a.trueIntent?.choice ?: "?"}（置信 ${pct(a.trueIntent?.confidence)}）"
            }
        }
    }

    fun testReply() {
        val s = this
        val probe = draftPrefs(SCRATCH_REPLY) {
            judgeKey = s.judgeKey.value.trim()
            replyBaseUrl = s.replyBase.value.trim().ifBlank { Prefs.DEFAULT_REPLY_BASE }
            replyKey = s.replyKey.value.trim()
            replyModel = s.replyModel.value.trim().ifBlank { Prefs.DEFAULT_REPLY_MODEL }
        }
        if (probe.effectiveReplyKey().isBlank()) { replyResult.value = "请先填密钥"; return }
        replyResult.value = "测试中…"
        worker.execute {
            val t0 = System.currentTimeMillis()
            var err: String? = null
            val out = try { ReplyClient(probe).ping() } catch (e: Exception) { err = e.message; "" }
            val ms = System.currentTimeMillis() - t0
            main.post {
                replyResult.value = if (err != null) "失败（${ms}ms）：$err"
                else "成功 ${ms}ms · ${out.replace("\n", " ").take(40)}"
            }
        }
    }

    fun save() {
        // 地址优先于档位: 框里留着预设主机意味着该预设的 provider。
        val judgeBaseTyped = judgeBase.value.trim()
        val judgeProv = resolveJudgeProvider(judgeIdx.value, judgeBaseTyped)
        val judgeModelTyped = judgeModel.value.trim()
        prefs.judgeProvider = judgeProv
        prefs.judgeBaseUrl = when {
            judgeBaseTyped.isNotBlank() -> judgeBaseTyped
            judgeProv == Prefs.PROVIDER_CUSTOM -> ""
            else -> defaultJudgeBase(judgeProv)
        }
        prefs.judgeKey = judgeKey.value.trim()
        prefs.judgeModel = when {
            judgeModelTyped.isNotBlank() -> judgeModelTyped
            judgeProv == Prefs.PROVIDER_CUSTOM -> ""
            else -> defaultJudgeModel(judgeProv)
        }

        prefs.replyBaseUrl = replyBase.value.trim().ifBlank { Prefs.DEFAULT_REPLY_BASE }
        prefs.replyKey = replyKey.value.trim()
        prefs.replyModel = replyModel.value.trim().ifBlank { Prefs.DEFAULT_REPLY_MODEL }

        prefs.relationship = rel.value
        prefs.whitelist = wl.value.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        prefs.autoAnalyze = autoAnalyze.value
        prefs.autoDailyCap = dailyCap.value.trim().toIntOrNull()?.coerceIn(1, 500) ?: 50
        prefs.overlayOpacity = opacity.value.roundToInt() + 60
    }

    fun shutdown() { worker.shutdownNow() }

    private fun providerOf(idx: Int) = when (idx) {
        1 -> Prefs.PROVIDER_OPENROUTER
        2 -> Prefs.PROVIDER_CUSTOM
        else -> Prefs.PROVIDER_TYPESAFE
    }

    private fun resolveJudgeProvider(idx: Int, base: String): String =
        when (base.trim().trimEnd('/')) {
            Prefs.DEFAULT_JUDGE_BASE_TYPESAFE -> Prefs.PROVIDER_TYPESAFE
            Prefs.DEFAULT_JUDGE_BASE_OPENROUTER -> Prefs.PROVIDER_OPENROUTER
            else -> providerOf(idx)
        }

    private fun expandJudgeUrl(base: String): String = when (base.trim().trimEnd('/')) {
        Prefs.DEFAULT_JUDGE_BASE_TYPESAFE -> Prefs.DEFAULT_JUDGE_BASE_TYPESAFE + "/v1/systemone"
        Prefs.DEFAULT_JUDGE_BASE_OPENROUTER -> Prefs.DEFAULT_JUDGE_BASE_OPENROUTER + "/alpha/decisions"
        else -> base.trim()
    }

    private fun defaultJudgeBase(provider: String): String =
        if (provider == Prefs.PROVIDER_TYPESAFE) Prefs.DEFAULT_JUDGE_BASE_TYPESAFE
        else Prefs.DEFAULT_JUDGE_BASE_OPENROUTER

    private fun defaultJudgeModel(provider: String): String =
        if (provider == Prefs.PROVIDER_TYPESAFE) Prefs.DEFAULT_JUDGE_MODEL_TYPESAFE
        else Prefs.DEFAULT_JUDGE_MODEL_OPENROUTER

    /** 只装着框内当前值的一次性 Prefs; 独立草稿文件, 真实配置永不被碰。 */
    private fun draftPrefs(scratchName: String, fill: Prefs.() -> Unit): Prefs {
        activity.getSharedPreferences(scratchName, Context.MODE_PRIVATE)
            .edit().clear().commit()
        return Prefs(activity, scratchName).apply(fill)
    }

    private fun pct(d: Double?): String =
        if (d == null) "?" else "${(d * 100).roundToInt()}%"

    companion object {
        private const val SCRATCH_JUDGE = "wechatjev_scratch_judge"
        private const val SCRATCH_REPLY = "wechatjev_scratch_reply"
    }
}

/** 设置页 UI。 */
@Composable
fun SettingsPageContent(state: SettingsState) {
    val scrollBehavior = MiuixScrollBehavior()
    Scaffold(
        topBar = { TopAppBar(title = "设置", scrollBehavior = scrollBehavior) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .imePadding()
                .navigationBarsPadding()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = 20.dp
            )
        ) {
            item { SmallTitle(text = "判断接口") }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    OverlayDropdownPreference(
                        title = "服务商",
                        items = listOf("TypeSafe 直连", "OpenRouter", "自定义"),
                        selectedIndex = state.judgeIdx.value,
                        onSelectedIndexChange = { state.onJudgeProvider(it); state.save() }
                    )
                    Field(state.judgeBase.value, { state.judgeBase.value = it; state.save() }, "Base URL")
                    Field(state.judgeKey.value, { state.judgeKey.value = it; state.save() }, "密钥", placeholder = true, secret = true)
                    Field(state.judgeModel.value, { state.judgeModel.value = it; state.save() }, "模型")
                    TestButton("测试", state.judgeResult.value) { state.testJudge() }
                }
                Spacer(Modifier.height(4.dp))
            }
            item { SmallTitle(text = "回复接口") }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    OverlayDropdownPreference(
                        title = "预设",
                        items = listOf("OpenRouter", "DeepSeek 官方", "通义兼容", "自定义"),
                        selectedIndex = state.replyIdx.value,
                        onSelectedIndexChange = { state.onReplyProvider(it); state.save() }
                    )
                    Field(state.replyBase.value, { state.replyBase.value = it; state.save() }, "Base URL")
                    Field(state.replyKey.value, { state.replyKey.value = it; state.save() }, "密钥（留空继承判断接口）", placeholder = true, secret = true)
                    Field(state.replyModel.value, { state.replyModel.value = it; state.save() }, "模型")
                    TestButton("测试", state.replyResult.value) { state.testReply() }
                }
                Spacer(Modifier.height(4.dp))
            }
            item { SmallTitle(text = "分析") }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    Field(state.rel.value, { state.rel.value = it; state.save() }, "关系描述", placeholder = true)
                    Field(state.wl.value, { state.wl.value = it; state.save() }, "会话白名单（每行一个，空 = 全部）")
                    SwitchPreference(
                        title = "自动分析",
                        summary = "对方新消息时自动触发，每日有上限",
                        checked = state.autoAnalyze.value,
                        onCheckedChange = { state.autoAnalyze.value = it; state.save() }
                    )
                    Field(state.dailyCap.value, { state.dailyCap.value = it; state.save() }, "每日上限", placeholder = true)
                }
                Spacer(Modifier.height(4.dp))
            }
            item { SmallTitle(text = "悬浮窗") }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(text = "不透明度 ${state.opacity.value.roundToInt() + 60}%", fontSize = 14.sp)
                    }
                    Slider(
                        value = state.opacity.value,
                        onValueChange = { state.opacity.value = it; state.save() },
                        valueRange = 0f..40f,
                        steps = 39,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp)
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}


/** 卡片内输入框: 统一水平边距与字段间距（miuix TextField 默认无边距）。
 *  secret=true 时掩码显示, 尾部「显示/隐藏」切换明文——密钥类输入防窥屏。 */
@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: Boolean = false,
    secret: Boolean = false
) {
    var show by remember { mutableStateOf(false) }
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        useLabelAsPlaceholder = placeholder,
        singleLine = secret,
        visualTransformation = if (secret && !show) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (secret) {
            {
                // 眼睛图标: 掩码态显示 Show(点击明文), 明文态显示 Hide(点击掩回)
                Icon(
                    imageVector = if (show) MiuixIcons.Hide else MiuixIcons.Show,
                    contentDescription = if (show) "隐藏密钥" else "显示密钥",
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { show = !show }
                )
            }
        } else null,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
    )
}

@Composable
private fun TestButton(label: String, result: String, onClick: () -> Unit) {
    Column {
        TextButton(
            text = label,
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            colors = ButtonDefaults.textButtonColorsPrimary()
        )
        if (result.isNotEmpty()) {
            Text(
                text = result, fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
    }
}
