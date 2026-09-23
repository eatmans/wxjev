package com.eatmans.wxjev.capture

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.eatmans.wxjev.core.Analysis
import com.eatmans.wxjev.core.AnalysisRecord
import com.eatmans.wxjev.core.ChatSnapshot
import com.eatmans.wxjev.core.Prefs
import com.eatmans.wxjev.core.RankedReply
import com.eatmans.wxjev.core.RecordsStore
import com.eatmans.wxjev.jev.JevClient
import com.eatmans.wxjev.overlay.OverlayController
import kotlin.math.roundToInt
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * 正式采集服务（以伪装类名注册——见其伪装子类, 这是读到微信节点树的关键）。
 * 读前台被适配的聊天 App, 检测对方新消息, 工作线程跑 Jev 分析, 驱动悬浮窗。
 *
 * 各 App 的节点规则在 [ChatAppAdapter] 实现里; 这里全部与 App 无关。
 *
 * 红线（计划书第八节 A 类）: 绝不发送消息。唯一写动作是用户点「填入」时的
 * ACTION_SET_TEXT（失败退剪贴板 + ACTION_PASTE）回填聊天输入框;
 * 发送永远由用户手动点。绝不 ACTION_CLICK 发送按钮, 绝不碰转账/红包/收款。
 */
open class ChatCaptureService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2)

    /** 已适配的聊天 App, 按包名索引。飞书随 M5 OCR 一并接入。 */
    private val adapters = listOf(WeChatAdapter(), QQAdapter(), XAdapter()).associateBy { it.pkg }

    /** 提交到工作线程; 服务销毁后的陈旧回调被拒绝时静默忽略, 绝不崩进程。 */
    private fun submit(task: () -> Unit) {
        try { worker.execute(task) } catch (_: RejectedExecutionException) { }
    }
    private lateinit var prefs: Prefs
    private var overlay: OverlayController? = null

    private var lastSignature = ""
    private var activePkg: String? = null
    private var analyzing = false

    /** 自动分析每日计数（计划书: 默认上限 50 次/天, 沿用 iOS 实测值）。 */
    private var autoCountDay = ""
    private var autoCount = 0
    private fun autoQuotaLeft(): Boolean {
        val today = android.text.format.DateFormat.format("yyyyMMdd", java.util.Date()).toString()
        if (today != autoCountDay) { autoCountDay = today; autoCount = 0 }
        return autoCount < prefs.autoDailyCap
    }

    /**
     * 每个包最近一次的已知良好（非瞬态）标题。见 [isTransientTitle]: X 的私信页
     * 刚打开时会短暂显示「连接中…」, 它绝不能覆盖真实会话标题。切 App 不清空——
     * 该包的下一个真实标题自然替换。
     */
    private val lastGoodTitle: MutableMap<String, String> = HashMap()
    private val debounce = Runnable { runAnalysis() }
    private var pendingSnapshot: ChatSnapshot? = null
    @Volatile private var currentSnapshot: ChatSnapshot? = null
    private var foregroundPkg: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        RecordsStore.init(this)
        overlay = OverlayController(this)
        overlay?.onManualAnalyze = {
            currentSnapshot?.let { pendingSnapshot = it; runAnalysis() }
        }
        // 把进程抬到前台重要级, 防 MIUI 冻结（失败容忍: 部分场景系统限制后台起前台服务）。
        KeepAliveService.start(this)
        // HyperOS 可能杀掉再拉起我们。重连时对已打开的聊天主动补一次采集,
        // 让悬浮球自己回来, 而不是等用户滚动。
        if (prefs.enabled) main.postDelayed({ runCatching { maybeCapture() } }, 900)
        Log.i(TAG, "capture service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!this::prefs.isInitialized) return   // onServiceConnected 之前的残余事件
        if (!prefs.enabled) { main.post { overlay?.hide() }; return }

        val type = event.eventType
        // 「是否离开了聊天 App」按真实活动窗口判, 不按事件的包名判。事件包名可能是
        // 输入法（如讯飞）或状态栏而聊天 App 仍在前台——按它判会让悬浮球闪烁。
        // rootInActiveWindow 在键盘弹出时仍停留在聊天 App 上, 稳定。
        //
        // 没有适配器的 App 不是撤掉悬浮球的理由: 无适配 App 的气泡只是停靠, 不采集
        // 不分析; 真正要撤的是它只会碍事的地方——本 App 自己的界面、桌面、系统 UI。
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val fg = rootInActiveWindow?.packageName?.toString()
            if (fg != null && fg !in adapters) {
                foregroundPkg = fg
                val drop = fg == packageName ||
                    fg.contains("launcher", ignoreCase = true) ||
                    fg == "com.miui.home" ||
                    fg == "com.android.systemui"
                main.post { if (drop) overlay?.hide() else overlay?.showIdle(null) }
                return
            }
        }

        when (type) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> maybeCapture()
        }
    }

    private fun maybeCapture() {
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        val adapter = adapters[pkg] ?: return
        // 只在聊天窗内动作（适配器在别处返回 null）。
        val rawSnapshot = adapter.extract(root, resources) ?: return
        // 标题先稳态化, 再给下面任何读它的逻辑用。
        val snapshot = stabilizeTitle(pkg, rawSnapshot)
        if (!prefs.isAllowed(snapshot.title)) { main.post { overlay?.hide() }; return }
        // 在聊天窗但树里没正文（伪装失效的微信/自绘正文的 App）→ OCR 兜底位,
        // M5 接; 当前版本只静默。
        if (snapshot.messages.isEmpty()) return

        // 切到另一个已适配 App 时重置去重签名, 两个 App 恰好相同的最近几条
        // 消息不会互相吞掉。
        if (pkg != activePkg) { activePkg = pkg; lastSignature = "" }

        currentSnapshot = snapshot
        val sig = snapshot.signature()
        val showing = overlay?.isShowing() == true
        // 内容相同且球已显示 → 无事可做。
        if (sig == lastSignature && showing) return
        // 内容相同但球没了（被 MIUI 杀了, 或离开又回来）→ 只把球放回去, 不重新
        // 分析（省 token/时间）。
        if (sig == lastSignature && !showing) { main.post { overlay?.showIdle(snapshot.title) }; return }
        // 走到这里的是真正不同的会话（换了 App, 或本会话有新内容）——之前会话
        // 残留的判断/候选绝不能串进来。
        main.post { overlay?.resetForNewConversation() }
        lastSignature = sig
        Log.d(TAG, "snapshot[$pkg] title=${snapshot.title} n=${snapshot.messages.size} " +
            snapshot.messages.takeLast(6).joinToString(" | ") { "${it.side}:${it.text.length}" }) // 只记边与长度, 不记内容

        // 触发条件: 最新一条来自对方 且 自动分析开启 且 当日配额未用完。
        // 否则亮出待机球（点一下手动分析, 默认形态）。
        if (snapshot.latestFrom != "other" || !prefs.autoAnalyze || !autoQuotaLeft()) {
            main.post { overlay?.showIdle(snapshot.title) }; return
        }

        pendingSnapshot = snapshot
        main.removeCallbacks(debounce)
        main.postDelayed(debounce, 800) // 防内容变化事件连发
    }

    /** App 只显示一小会的占位标题（如 X 私信页刚打开时的「连接中…」）。
     *  空白/null 也算——调用方统一走同一个兜底。 */
    private fun isTransientTitle(t: String?): Boolean {
        val trimmed = t?.trim()?.removeSuffix("…")?.removeSuffix("...")?.trim()
        if (trimmed.isNullOrEmpty()) return true
        val lower = trimmed.lowercase()
        return TRANSIENT_TITLE_WORDS.any { lower.contains(it.lowercase()) }
    }

    /** 瞬态标题替换为该包最近一次良好标题（若有）; 否则把当前标题记作新的良好标题。 */
    private fun stabilizeTitle(pkg: String, snapshot: ChatSnapshot): ChatSnapshot {
        if (isTransientTitle(snapshot.title)) {
            val good = lastGoodTitle[pkg] ?: return snapshot
            return snapshot.copy(title = good)
        }
        snapshot.title?.let { lastGoodTitle[pkg] = it }
        return snapshot
    }

    private fun runAnalysis() {
        val snapshot = pendingSnapshot ?: return
        if (analyzing) return
        if (!prefs.hasKey()) { main.post { overlay?.showError("未设置判断接口密钥，去设置里填") }; return }
        analyzing = true
        autoCount++
        main.post { overlay?.showLoading(); overlay?.setNote(snapshot.note) }
        val client = JevClient(prefs)
        val rel = prefs.relationship
        // 记录装配: 判断/回复并行完成, 两样都齐时由后到者落一次记录。
        val recordId = RecordsStore.newId()
        val judgeRef = java.util.concurrent.atomic.AtomicReference<Analysis?>(null)
        val replyRef = java.util.concurrent.atomic.AtomicReference<Pair<List<RankedReply>, String?>?>(null)
        val recorded = java.util.concurrent.atomic.AtomicBoolean(false)
        fun tryRecord() {
            val j = judgeRef.get() ?: return
            val r = replyRef.get() ?: return
            if (!recorded.compareAndSet(false, true)) return
            RecordsStore.add(AnalysisRecord(
                id = recordId,
                ts = System.currentTimeMillis(),
                pkg = activePkg ?: "",
                title = snapshot.title,
                messages = snapshot.messages,
                intent = j.trueIntent?.choice,
                intentConf = j.trueIntent?.confidence,
                danger = j.dangerLevel?.score?.roundToInt(),
                needs = j.sheNeeds?.choice,
                action = j.bestAction?.choice,
                replyNow = j.shouldReplyNow?.let { it >= 0.5 },
                replies = r.first,
                replyError = r.second,
                judgeError = j.error
            ))
        }
        // 判断快（~1s）——先立即上屏; 候选慢（生成 + 排序）——好了再补。
        submit {
            submit {
                val judgment = client.judge(snapshot, rel)
                judgeRef.set(judgment)
                tryRecord()
                main.post {
                    if (judgment.error != null) { analyzing = false; overlay?.showError(judgment.error) }
                    else overlay?.showJudgment(judgment)
                }
            }
            submit {
                var replyError: String? = null
                val ranked = try { client.draftAndRank(snapshot, rel) } catch (e: Exception) {
                    replyError = e.message ?: e.javaClass.simpleName
                    emptyList()
                }
                replyRef.set(ranked to replyError)
                tryRecord()
                main.post {
                    analyzing = false
                    overlay?.showReplies(ranked, replyError) { text -> fillInput(text) }
                }
            }
        }
    }

    // ---------------------------------------------------------------- 回填

    /** 把选中的候选回复填进聊天输入框（绝不发送）。 */
    private fun fillInput(text: String) {
        submit {
            // 快路径: 输入框已有焦点且无输入法组合会话时 SET_TEXT 直接生效。
            var ok = trySetText(text)
            if (!ok) {
                // 否则先点输入框（弹出键盘）再试 SET_TEXT; 若输入法组合区仍吞掉
                // （微信会）, 从剪贴板 PASTE。PASTE 前先清空输入框, 免得一次
                // 静默半成功的 SET_TEXT 被翻倍。绝不点发送。
                val edit = rootInActiveWindow?.let { findEditable(it) }
                if (edit != null) {
                    edit.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Thread.sleep(300)
                    ok = trySetText(text)
                    if (!ok) {
                        copyToClipboard(text)
                        val focused = rootInActiveWindow?.let { findEditable(it) } ?: edit
                        setTextRaw(focused, "")
                        val pasted = focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                        Thread.sleep(150)
                        val after = readInput()
                        ok = (after != null && after.contains(text)) || (pasted && after == null)
                        Log.i(TAG, "fill: paste=$pasted readback=${after?.length ?: -1}")
                    }
                }
            }
            main.post {
                if (ok) overlay?.toast("已填入，确认后自己发送")
                else { copyToClipboard(text); overlay?.toast("已复制，长按输入框粘贴") }
            }
        }
    }

    /** 对聊天输入框 SET_TEXT 并验证确实生效。 */
    private fun trySetText(text: String): Boolean {
        val edit = rootInActiveWindow?.let { findEditable(it) } ?: return false
        if (!setTextRaw(edit, text)) return false
        // SET_TEXT 可能报成功但未真正填进未聚焦的框; 回读验证。
        // 回读走 refresh()——动作刚做完时节点缓存可能还持旧（空）文本,
        // 曾让自绘 App 被误判失败而触发二次 PASTE 叠加。
        Thread.sleep(150)
        val after = readInput()
        Log.i(TAG, "fill: setText readback=${after?.length ?: -1} want=${text.length}")
        return after == text
    }

    private fun setTextRaw(edit: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** 输入框当前文本, 绕过节点缓存新鲜读取。 */
    private fun readInput(): String? {
        val edit = rootInActiveWindow?.let { findEditable(it) } ?: return null
        runCatching { edit.refresh() }
        return edit.text?.toString()
    }

    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 5000) {
            guard++
            val node = stack.removeLast()
            if (node.isEditable) return node
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
        return null
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("jev_reply", text))
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        tearDownProduction()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        tearDownProduction()
        super.onDestroy()
    }

    private fun tearDownProduction() {
        // 悬浮窗拆掉并切断回调, 陈旧的按钮点击绝不能回调进已销毁的实例。
        overlay?.onManualAnalyze = null
        overlay?.hide()
        overlay = null
        worker.shutdownNow()
    }

    companion object {
        private const val TAG = "JevAssist"

        /** App 在聊天页连接/加载中显示的瞬态占位标题——按子串、大小写不敏感匹配,
         *  匹配前先剥掉尾部省略号。 */
        private val TRANSIENT_TITLE_WORDS = listOf(
            "连接中", "正在连接", "未连接", "Connecting",
            "加载中", "Loading", "同步中", "Syncing"
        )
    }
}
