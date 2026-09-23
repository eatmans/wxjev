package com.eatmans.wxjev.core

import android.content.Context

/**
 * App 私有配置存储。判断/回复两路接口（三路里的视觉路随 M5 OCR 再加）,
 * 关系描述、会话白名单、触发与外观开关。
 *
 * 密钥纪律: 只存 App 私有 SharedPreferences, 不进日志（只允许打长度）、不进 git。
 */
class Prefs(context: Context, prefsName: String = PREFS_MAIN) {

    private val sp = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- judge

    /** "typesafe" | "openrouter" | "custom"。默认 TypeSafe 直连（计划书 6.2）。 */
    var judgeProvider: String
        get() = sp.getString(K_JUDGE_PROVIDER, PROVIDER_TYPESAFE) ?: PROVIDER_TYPESAFE
        set(v) = sp.edit().putString(K_JUDGE_PROVIDER, v.trim()).apply()

    /** 主机根地址; 路径按 provider 拼接（见 [judgeEndpoint]）。 */
    var judgeBaseUrl: String
        get() = sp.getString(K_JUDGE_BASE, DEFAULT_JUDGE_BASE_TYPESAFE) ?: DEFAULT_JUDGE_BASE_TYPESAFE
        set(v) = sp.edit().putString(K_JUDGE_BASE, v.trim()).apply()

    var judgeKey: String
        get() = sp.getString(K_JUDGE_KEY, "") ?: ""
        set(v) = sp.edit().putString(K_JUDGE_KEY, v.trim()).apply()

    var judgeModel: String
        get() = sp.getString(K_JUDGE_MODEL, DEFAULT_JUDGE_MODEL_TYPESAFE) ?: DEFAULT_JUDGE_MODEL_TYPESAFE
        set(v) = sp.edit().putString(K_JUDGE_MODEL, v.trim()).apply()

    // ---------------------------------------------------------------- reply

    /** OpenAI 兼容地址, 填到 /v1 为止。 */
    var replyBaseUrl: String
        get() = sp.getString(K_REPLY_BASE, DEFAULT_REPLY_BASE) ?: DEFAULT_REPLY_BASE
        set(v) = sp.edit().putString(K_REPLY_BASE, v.trim()).apply()

    /** 留空 = 继承 [judgeKey]。 */
    var replyKey: String
        get() = sp.getString(K_REPLY_KEY, "") ?: ""
        set(v) = sp.edit().putString(K_REPLY_KEY, v.trim()).apply()

    /** 起草 3 条候选回复的生成模型。 */
    var replyModel: String
        get() = sp.getString(K_REPLY_MODEL, DEFAULT_REPLY_MODEL) ?: DEFAULT_REPLY_MODEL
        set(v) = sp.edit().putString(K_REPLY_MODEL, v.trim()).apply()

    // -------------------------------------------------------------- trigger

    /** 总开关: 显示悬浮窗 + 允许分析。 */
    var enabled: Boolean
        get() = sp.getBoolean(K_ENABLED, true)
        set(v) = sp.edit().putBoolean(K_ENABLED, v).apply()

    /** 对方新消息时自动分析; 默认关（手动点悬浮球, 计划书 B 类红线: 自动需用户开启）。 */
    var autoAnalyze: Boolean
        get() = sp.getBoolean(K_AUTO, false)
        set(v) = sp.edit().putBoolean(K_AUTO, v).apply()

    /** 自动分析每日上限（计划书沿用 iOS 实测值 50）。 */
    var autoDailyCap: Int
        get() = sp.getInt(K_AUTO_CAP, 50)
        set(v) = sp.edit().putInt(K_AUTO_CAP, v.coerceIn(1, 500)).apply()

    // -------------------------------------------------------------- session

    /** 自由文本: 对方是谁。进入 Jev 的 state。 */
    var relationship: String
        get() = sp.getString(K_REL, DEFAULT_REL) ?: DEFAULT_REL
        set(v) = sp.edit().putString(K_REL, v).apply()

    /**
     * 会话白名单: 允许助手作用的会话标题关键词。空集合 = 所有会话。
     */
    var whitelist: Set<String>
        get() = sp.getStringSet(K_WHITELIST, emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet(K_WHITELIST, v).apply()

    // -------------------------------------------------------------- display

    /** 面板不透明度 60..100（%）, 越低越透。 */
    var overlayOpacity: Int
        get() = sp.getInt(K_OPACITY, 92).coerceIn(60, 100)
        set(v) = sp.edit().putInt(K_OPACITY, v.coerceIn(60, 100)).apply()

    /** 悬浮球记住的位置（px）; -1 = 默认。 */
    var bubbleY: Int
        get() = sp.getInt(K_BUBBLE_Y, -1)
        set(v) = sp.edit().putInt(K_BUBBLE_Y, v).apply()

    var bubbleX: Int
        get() = sp.getInt(K_BUBBLE_X, -1)
        set(v) = sp.edit().putInt(K_BUBBLE_X, v).apply()

    // -------------------------------------------------------------- helpers

    /** 回复路密钥, 空则继承判断路。 */
    fun effectiveReplyKey(): String = replyKey.ifBlank { judgeKey }

    /** Jev decisions 调用的完整 POST 地址, 按 provider 拼路径。 */
    fun judgeEndpoint(): String {
        val base = judgeBaseUrl.trim().trimEnd('/')
        return when (judgeProvider) {
            PROVIDER_TYPESAFE -> "$base/v1/systemone"
            PROVIDER_CUSTOM -> judgeBaseUrl.trim()   // 用户填完整 URL
            else -> "$base/alpha/decisions"
        }
    }

    /** OpenAI 兼容 chat/completions 完整地址。 */
    fun replyEndpoint(): String = "${replyBaseUrl.trim().trimEnd('/')}/chat/completions"

    fun isAllowed(title: String?): Boolean {
        val wl = whitelist
        if (wl.isEmpty()) return true
        if (title == null) return false
        return wl.any { title.contains(it) }
    }

    /** 就绪门槛: 判断路必须配了密钥。 */
    fun hasKey(): Boolean = judgeKey.isNotBlank()

    companion object {
        /** 唯一的真实配置文件; 其它实例（设置页测试按钮的草稿）一律用别的名字。 */
        const val PREFS_MAIN = "wechatjev_prefs"

        private const val K_JUDGE_PROVIDER = "judge_provider"
        private const val K_JUDGE_BASE = "judge_base_url"
        private const val K_JUDGE_KEY = "judge_key"
        private const val K_JUDGE_MODEL = "judge_model"
        private const val K_REPLY_BASE = "reply_base_url"
        private const val K_REPLY_KEY = "reply_key"
        private const val K_REPLY_MODEL = "reply_model"
        private const val K_ENABLED = "enabled"
        private const val K_AUTO = "auto_analyze"
        private const val K_AUTO_CAP = "auto_daily_cap"
        private const val K_REL = "relationship"
        private const val K_WHITELIST = "whitelist"
        private const val K_OPACITY = "overlay_opacity"
        private const val K_BUBBLE_Y = "bubble_y"
        private const val K_BUBBLE_X = "bubble_x"

        const val PROVIDER_TYPESAFE = "typesafe"
        const val PROVIDER_OPENROUTER = "openrouter"
        const val PROVIDER_CUSTOM = "custom"

        // 判断路预设。默认 TypeSafe 直连（计划书 6.2）。
        const val DEFAULT_JUDGE_BASE_TYPESAFE = "https://api.typesafe.ai"
        const val DEFAULT_JUDGE_MODEL_TYPESAFE = "jev-latest"
        const val DEFAULT_JUDGE_BASE_OPENROUTER = "https://openrouter.ai/api"
        const val DEFAULT_JUDGE_MODEL_OPENROUTER = "typesafe/jev-1.13"

        // 回复路预设（OpenAI 兼容）。
        const val DEFAULT_REPLY_BASE = "https://openrouter.ai/api/v1"
        const val DEFAULT_REPLY_MODEL = "deepseek/deepseek-chat-v3.1"
        const val DEEPSEEK_BASE = "https://api.deepseek.com/v1"
        const val DEEPSEEK_MODEL = "deepseek-chat"
        const val DASHSCOPE_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1"
        const val DASHSCOPE_MODEL = "qwen-plus"

        const val DEFAULT_REL = "对方是我的伴侣；from=me 的是我发的，from=other 的是对方发的"
    }
}
