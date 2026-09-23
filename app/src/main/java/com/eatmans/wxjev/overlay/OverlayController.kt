package com.eatmans.wxjev.overlay

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.eatmans.wxjev.core.Analysis
import com.eatmans.wxjev.core.Prefs
import com.eatmans.wxjev.core.RankedReply
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 悬浮窗: 可拖动小球 + 展开分析面板（HyperOS 卡片风）。
 * 深色自适应、危险徽章→意图→标签芯片→候选卡的信息分级。
 * 面板动作只有复制 / 填入——绝不发送。
 *
 * 窗口类型: 首选 TYPE_ACCESSIBILITY_OVERLAY（免悬浮窗权限）;
 * 添加失败时退回 TYPE_APPLICATION_OVERLAY（需用户授权）。
 */
class OverlayController(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = Prefs(ctx)
    private var root: FrameLayout? = null
    private var bubble: View? = null
    private var dangerDot: View? = null
    private var panel: LinearLayout? = null
    private var contentBox: LinearLayout? = null
    private var headerTitle: TextView? = null
    private var expanded = false
    private var lp: WindowManager.LayoutParams? = null

    var onManualAnalyze: (() -> Unit)? = null

    /** 本帧采集方式备注（如 OCR 帧的「未分边」说明, M5 用）。 */
    private var noteText: String? = null

    /** 头部显示的会话标题。 */
    private var convTitle: String? = null

    fun isShowing(): Boolean = root != null

    private var lastJudgment: Analysis? = null
    private var lastFill: ((String) -> Unit)? = null
    private var replyError: String? = null

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).roundToInt()

    private val screenW get() = ctx.resources.displayMetrics.widthPixels
    private val screenH get() = ctx.resources.displayMetrics.heightPixels

    // ------------------------------------------------------------- 配色

    private val dark: Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private val cSurface get() = if (dark) 0xFF1B1A1F.toInt() else Color.WHITE
    private val cOnSurface get() = if (dark) 0xFFF2F3F5.toInt() else 0xFF111827.toInt()
    private val cSecondary get() = if (dark) 0xFF9CA3AF.toInt() else 0xFF6B7280.toInt()
    private val cCard get() = if (dark) 0xFF26242C.toInt() else 0xFFF6F7FB.toInt()
    private val cCardTop get() = if (dark) 0xFF1D2A47.toInt() else 0xFFEAF1FF.toInt()
    private val cDivider get() = if (dark) 0x22FFFFFF else 0x14000000
    private val cStroke get() = if (dark) 0x2EFFFFFF else 0x1A000000

    private val cPrimary = 0xFF3A7AFE.toInt()
    private val cPrimarySoft = if (dark) 0x333A7AFE.toInt() else 0x1F3A7AFE.toInt()
    private val cDanger = 0xFFDC2626.toInt()
    private val cWarn = 0xFFD97706.toInt()
    private val cOk = 0xFF16A34A.toInt()

    /** 面板底色: 用户不透明度 ×(亮色白/暗色深)。 */
    private fun panelBg(): Int {
        val a = (prefs.overlayOpacity / 100f * 255).roundToInt().coerceIn(150, 255)
        return (a shl 24) or (if (dark) 0x001B1A1F else 0x00FFFFFF)
    }

    // ------------------------------------------------------------- 基础件

    private fun shape(radius: Int, color: Int, strokeColor: Int = 0, strokeDp: Int = 1) =
        GradientDrawable().apply {
            cornerRadius = dp(radius).toFloat()
            setColor(color)
            if (strokeColor != 0) setStroke(dp(strokeDp), strokeColor)
        }

    /** Material 水波纹包一层, 按钮才有按压反馈。 */
    private fun ripple(bg: Drawable): Drawable =
        RippleDrawable(ColorStateList.valueOf(0x283A7AFE), bg, null)

    private fun pill(
        label: String,
        filled: Boolean,
        onClick: () -> Unit
    ) = TextView(ctx).apply {
        text = label; textSize = 13f; gravity = Gravity.CENTER
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        val bgShape = if (filled) shape(999, cPrimary)
        else shape(999, Color.TRANSPARENT, strokeColor = (if (dark) 0x59FFFFFF else 0x33000000))
        background = ripple(bgShape)
        setTextColor(if (filled) Color.WHITE else cOnSurface)
        setPadding(dp(20), dp(8), dp(20), dp(8))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun text(t: String, color: Int, size: Float, bold: Boolean = false) =
        TextView(ctx).apply {
            text = t; setTextColor(color); textSize = size
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            includeFontPadding = false
        }

    /** 小标签芯片: 12% 主色底 + 主色字。 */
    private fun chip(t: String, color: Int, soft: Boolean = true) = TextView(ctx).apply {
        text = t; textSize = 11.5f; gravity = Gravity.CENTER
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(if (soft) color else Color.WHITE)
        background = if (soft) shape(999, (color and 0x00FFFFFF) or 0x26000000)
        else shape(999, color)
        setPadding(dp(10), dp(4), dp(10), dp(4))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(6) }
    }

    private fun divider() = View(ctx).apply {
        setBackgroundColor(cDivider)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(12); bottomMargin = dp(8)
        }
    }

    // ------------------------------------------------------------- window

    private fun ensureRoot() {
        if (root != null) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (prefs.bubbleX in 0..(screenW - dp(52))) prefs.bubbleX else dp(8)
            y = if (prefs.bubbleY >= 0) prefs.bubbleY else dp(150)
        }
        val r = FrameLayout(ctx)
        r.addView(buildPanel())
        r.addView(buildBubble(params))
        try {
            wm.addView(r, params)
        } catch (e: Exception) {
            // 个别 ROM 拒绝无障碍特权窗口 → 退回应用悬浮窗（需用户已授权）。
            if (Settings.canDrawOverlays(ctx)) {
                params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                try {
                    wm.addView(r, params)
                } catch (e2: Exception) {
                    android.util.Log.e(TAG, "overlay addView failed: ${e2.message}")
                    return
                }
            } else {
                android.util.Log.w(TAG, "overlay: a11y type rejected and no overlay permission")
                return
            }
        }
        lp = params
        root = r
    }

    private fun buildBubble(params: WindowManager.LayoutParams): View {
        val wrap = FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(dp(52), dp(52))
        }
        // 球体 = App Logo(预裁圆形), 带细白描边在任何聊天背景上都清晰
        val b = ImageView(ctx).apply {
            setImageResource(com.eatmans.wxjev.R.drawable.bubble_logo)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dp(2), Color.WHITE)
            }
            layoutParams = FrameLayout.LayoutParams(dp(52), dp(52))
        }
        val dot = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT)
                setStroke(dp(2), Color.WHITE)
            }
            layoutParams = FrameLayout.LayoutParams(dp(12), dp(12)).apply {
                gravity = Gravity.TOP or Gravity.END
            }
        }
        wrap.addView(b)
        wrap.addView(dot)
        attachBubbleTouch(wrap, params)
        bubble = b; dangerDot = dot
        return wrap
    }

    private fun buildPanel(): LinearLayout {
        val p = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = shape(24, panelBg(), strokeColor = cStroke)
            elevation = dp(10).toFloat()
            setPadding(dp(16), dp(12), dp(16), dp(14))
            layoutParams = FrameLayout.LayoutParams(dp(322), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(56)
            }
        }

        // Header: 圆点 + 标题 + 会话名 + 设置/关闭
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(View(ctx).apply {
            background = shape(999, cPrimary)
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply { rightMargin = dp(8) }
        })
        header.addView(text("Jev 分析", cOnSurface, 15f, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        headerTitle = text("", cSecondary, 12f).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(10); rightMargin = dp(6)
            }
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            ellipsize = android.text.TextUtils.TruncateAt.END
            isSingleLine = true
        }
        header.addView(headerTitle!!)
        header.addView(iconBtn("⚙") { openSettings() })
        header.addView(iconBtn("✕") { toggle() })
        p.addView(header)

        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            // 限高: 面板留在上半区, 不盖聊天输入框/键盘; 超高内部滚动。
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (screenH * 0.40f).roundToInt()).apply { topMargin = dp(8) }
        }
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        p.addView(scroll)
        contentBox = content
        panel = p
        return p
    }

    private fun iconBtn(glyph: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = glyph; setTextColor(cSecondary); textSize = 15f
        gravity = Gravity.CENTER
        background = ripple(shape(999, Color.TRANSPARENT))
        layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { leftMargin = dp(2) }
        setOnClickListener { onClick() }
    }

    // ------------------------------------------------------------- 手势

    private fun attachBubbleTouch(v: View, params: WindowManager.LayoutParams) {
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        var moved = false; var longFired = false
        val longPress = Runnable {
            if (!moved) { longFired = true; showBubbleMenu() }
        }
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y; touchX = e.rawX; touchY = e.rawY
                    moved = false; longFired = false
                    v.postDelayed(longPress, 500); true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt(); val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    // 两侧留边: 极边是 MIUI 手势区。自由摆放, 不强制贴边。
                    params.x = (startX + dx).coerceIn(dp(8), screenW - dp(60))
                    params.y = (startY + dy).coerceIn(dp(24), screenH - dp(120))
                    root?.let { runCatching { wm.updateViewLayout(it, params) } }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    if (longFired) { true }
                    else if (moved) {
                        prefs.bubbleX = params.x; prefs.bubbleY = params.y; true
                    } else { toggle(); true }
                }
                MotionEvent.ACTION_CANCEL -> { v.removeCallbacks(longPress); true }
                else -> false
            }
        }
    }

    private fun showBubbleMenu() {
        val menu = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(18, panelBg(), strokeColor = cStroke)
            elevation = dp(10).toFloat()
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = FrameLayout.LayoutParams(dp(200), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(56)
            }
        }
        menu.addView(menuItem("打开设置") { openSettings(); root?.removeView(menu) })
        menu.addView(menuItem("隐藏（本次）") { hide() })
        menu.addView(menuItem("取消") { root?.removeView(menu) })
        root?.addView(menu)
    }

    private fun menuItem(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; setTextColor(cOnSurface); textSize = 14f
        background = ripple(shape(12, Color.TRANSPARENT))
        setPadding(dp(14), dp(12), dp(14), dp(12)); setOnClickListener { onClick() }
    }

    private fun openSettings() {
        runCatching {
            ctx.startActivity(Intent().setClassName(ctx, "com.eatmans.wxjev.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("tab", "settings"))
        }
        if (expanded) toggle()
    }

    private var collapsedX = dp(6)
    private var collapsedY = dp(150)

    private fun toggle() {
        expanded = !expanded
        val params = lp ?: return
        if (expanded) {
            collapsedX = params.x; collapsedY = params.y
            params.x = dp(6)
            val maxTop = (screenH * 0.14f).roundToInt()
            if (params.y > maxTop) params.y = maxTop
            panel?.visibility = View.VISIBLE
        } else {
            panel?.visibility = View.GONE
            params.x = collapsedX; params.y = collapsedY
        }
        root?.let { runCatching { wm.updateViewLayout(it, params) } }
    }

    // ------------------------------------------------------------- public API

    fun showIdle(title: String?) {
        ensureRoot(); bubble?.alpha = 0.66f
        convTitle = title?.takeIf { it.isNotBlank() }
        headerTitle?.text = convTitle ?: ""
        if (lastJudgment == null || contentBox?.childCount == 0) {
            setContent(listOf(
                primaryButton("分析当前对话") { onManualAnalyze?.invoke() },
                text("点悬浮球展开 · 长按有菜单", cSecondary, 11.5f).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(8), 0, dp(2))
                })
            )
        }
    }

    /**
     * 丢掉属于上一个会话的判断/候选/备注。给不同聊天窗显示任何东西之前调用。
     */
    fun resetForNewConversation() {
        lastJudgment = null
        lastFill = null
        noteText = null
        replyError = null
        contentBox?.removeAllViews()
    }

    private fun primaryButton(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 15f; gravity = Gravity.CENTER
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Color.WHITE)
        background = ripple(shape(999, cPrimary))
        setPadding(dp(12), dp(13), dp(12), dp(13))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    fun showLoading() {
        ensureRoot(); bubble?.alpha = 1f
        replyError = null
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        row.addView(ProgressBar(ctx, null, android.R.attr.progressBarStyleSmall).apply {
            indeterminateTintList = ColorStateList.valueOf(cPrimary)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(10) }
        })
        row.addView(text("分析中…", cSecondary, 13.5f))
        setContent(listOf(row))
        if (!expanded) toggle()
    }

    fun setNote(note: String?) { noteText = note }

    fun showError(msg: String) {
        ensureRoot(); bubble?.alpha = 1f
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        row.addView(TextView(ctx).apply {
            text = "!"; setTextColor(Color.WHITE); textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = shape(999, cDanger)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(10) }
        })
        row.addView(text(msg, cOnSurface, 13.5f))
        setContent(listOf(row))
        if (!expanded) toggle()
    }

    fun showJudgment(a: Analysis) {
        lastJudgment = a
        render(a, generating = true)
    }

    fun showReplies(ranked: List<RankedReply>, error: String? = null, onFill: (String) -> Unit) {
        lastFill = onFill
        replyError = error
        val a = lastJudgment?.copy(rankedReplies = ranked) ?: return
        lastJudgment = a
        render(a, generating = false)
    }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    fun hide() {
        val r = root ?: return
        runCatching { wm.removeView(r) }
        root = null; bubble = null; panel = null; contentBox = null
        headerTitle = null; dangerDot = null; expanded = false
    }

    // ------------------------------------------------------------- 渲染

    private fun setContent(views: List<View>) {
        val c = contentBox ?: return
        c.removeAllViews(); views.forEach { c.addView(it) }
    }

    private fun render(a: Analysis, generating: Boolean) {
        ensureRoot(); bubble?.alpha = 1f
        panel?.background = shape(24, panelBg(), strokeColor = cStroke)
        val views = ArrayList<View>()

        noteText?.let { if (it.isNotBlank()) views.add(hintLine(it)) }

        // 危险徽章
        a.dangerLevel?.let {
            val lvl = it.score.roundToInt()
            views.add(dangerRow(lvl, it.maxLevel))
            tintBubbleDanger(it.score)
        }
        // 意图标题
        a.trueIntent?.let {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, 0)
            }
            row.addView(text("对方真实意图：${INTENT[it.choice] ?: it.choice}", cOnSurface, 16f, bold = true).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(text("${(it.confidence * 100).roundToInt()}%", cPrimary, 12.5f, bold = true))
            views.add(row)
        }
        // 次级信息 → 芯片行
        val chips = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0)
        }
        a.sheNeeds?.let { chips.addView(chip("要${NEEDS[it.choice] ?: it.choice}", cPrimary)) }
        a.bestAction?.let { chips.addView(chip(ACTION[it.choice] ?: it.choice, cSecondary)) }
        a.shouldReplyNow?.let {
            chips.addView(
                if (it >= 0.5) chip("可给实质", cOk) else chip("先别给实质", cWarn))
        }
        if (chips.childCount > 0) views.add(chips)
        a.tensionResolved?.let { if (it >= 0.7) views.add(text("✓ 紧张已缓解", cOk, 12.5f, bold = true).apply { setPadding(0, dp(8), 0, 0) }) }

        views.add(divider())
        views.add(text("候选回复 · Jev 排序", cSecondary, 11.5f, bold = true))
        if (generating) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(2))
            }
            row.addView(ProgressBar(ctx, null, android.R.attr.progressBarStyleSmall).apply {
                indeterminateTintList = ColorStateList.valueOf(cPrimary)
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(8) }
            })
            row.addView(text("生成中…", cSecondary, 12.5f))
            views.add(row)
        } else {
            val fill = lastFill ?: {}
            a.rankedReplies.forEachIndexed { i, r ->
                views.add(replyCard(i + 1, r.text, (r.prob * 100).roundToInt(), fill))
            }
            if (a.rankedReplies.isEmpty()) {
                val msg = replyError?.let { "回复接口出错：$it" } ?: "（未生成候选回复）"
                views.add(hintLine(msg).apply { setPadding(0, dp(8), 0, 0) })
            }
        }
        views.add(text("重新分析", cPrimary, 12.5f, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(12), dp(10), dp(4))
            setOnClickListener { onManualAnalyze?.invoke() }
        })

        setContent(views)
        if (!expanded) toggle()
    }

    private fun hintLine(t: String) = text(t, cSecondary, 12f).apply { setPadding(0, dp(2), 0, dp(2)) }

    /** 危险行: 实底色徽章 + 评级词, 一眼定调。 */
    private fun dangerRow(lvl: Int, max: Int): View {
        val color = dangerColor(lvl)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        row.addView(TextView(ctx).apply {
            text = "危险 $lvl/$max"
            setTextColor(Color.WHITE); textSize = 12.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = shape(999, color)
        })
        row.addView(TextView(ctx).apply {
            text = dangerWord(lvl); setTextColor(color); textSize = 13.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(10) }
        })
        return row
    }

    /** 候选卡: 第 1 名主色描边+淡蓝底, 其余中性底; 排名徽章 + 概率右对齐。 */
    private fun replyCard(rank: Int, msg: String, pct: Int, onFill: (String) -> Unit): View {
        val top = rank == 1
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = if (top) shape(16, cCardTop, strokeColor = (cPrimary and 0x66FFFFFF))
            else shape(16, cCard)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(TextView(ctx).apply {
            text = "#$rank"; setTextColor(cPrimary); textSize = 11.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            background = shape(999, cPrimarySoft)
            setPadding(dp(8), dp(2), dp(8), dp(3))
        })
        head.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        head.addView(text("$pct%", cPrimary, 12f, bold = true))
        c.addView(head)
        c.addView(text(msg, cOnSurface, 14f).apply {
            setPadding(0, dp(7), 0, dp(9))
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        val btns = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
        }
        btns.addView(pill("复制", false) { copy(msg) })
        // 填入后收起面板, 让输入框和键盘露出来供检查/发送。
        btns.addView(pill("填入", true) { onFill(msg); if (expanded) toggle() })
        c.addView(btns)
        return c
    }

    private fun tintBubbleDanger(score: Double) {
        val color = dangerColor(score.roundToInt())
        dangerDot?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(color); setStroke(dp(2), Color.WHITE)
        }
    }

    private fun copy(t: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("jev_reply", t))
        toast("已复制")
    }

    private fun dangerColor(lvl: Int): Int = when {
        lvl >= 6 -> cDanger
        lvl >= 3 -> cWarn
        else -> cOk
    }

    private fun dangerWord(lvl: Int): String = when {
        lvl >= 8 -> "很危险"
        lvl >= 6 -> "偏危险"
        lvl >= 3 -> "留神"
        else -> "安全"
    }

    companion object {
        private const val TAG = "JevAssist"

        private val INTENT = mapOf(
            "confirm_you_care" to "确认你在不在乎", "vent_anger" to "在发泄情绪",
            "request_action" to "要你办事", "seek_explanation" to "要个解释",
            "casual_chat" to "随便聊聊", "close_topic" to "事情过去了")
        private val NEEDS = mapOf(
            "apology" to "道歉", "action" to "具体行动", "explanation" to "解释",
            "care" to "你的在乎", "nothing" to "（不用做什么）")
        private val ACTION = mapOf(
            "check_history" to "翻聊天记录", "apologize" to "先道歉", "give_commitment" to "给承诺",
            "explain" to "解释清楚", "acknowledge" to "接住情绪", "say_less" to "少说两句",
            "make_plan" to "定个安排")
    }
}
