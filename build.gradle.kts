// WeChatJevAndroid — M0+P1 骨架
// 计划书: docs/开发计划书.md (V2.0 无障碍路线)
// 2026-09-22 技术选型变更(用户拍板): UI 层引入 Compose + miuix(HyperOS 风格),
// Kotlin 2.0.21 → 2.4.20 (miuix 0.9.4 的元数据要求)。悬浮窗仍为传统 View(服务窗口不宜跑 Compose)。
plugins {
    id("com.android.application") version "9.4.1" apply false
    // AGP 9 内置 Kotlin 支持(org.jetbrains.kotlin.android 不再需要), compose 编译器插件仍独立
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
