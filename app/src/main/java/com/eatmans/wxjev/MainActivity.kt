package com.eatmans.wxjev

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.mutableStateOf
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import com.eatmans.wxjev.capture.KeepAliveService
import com.eatmans.wxjev.core.Prefs
import com.eatmans.wxjev.core.RecordsStore
import com.eatmans.wxjev.ui.AboutPage
import com.eatmans.wxjev.ui.AppScaffold
import com.eatmans.wxjev.ui.ConsolePage
import com.eatmans.wxjev.ui.Route
import com.eatmans.wxjev.ui.SettingsPageContent
import com.eatmans.wxjev.ui.SettingsState

/**
 * 单 Activity 宿主: 底部导航三页（控制台 / 设置 / 关于, miuix-nav 渲染）。
 * 悬浮窗「打开设置」通过 extra "tab"="settings" 直达设置页。
 */
class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private lateinit var settingsState: SettingsState

    private val readyText = mutableStateOf("")
    private val enabled = mutableStateOf(true)
    private val a11yOn = mutableStateOf(false)
    private val keySet = mutableStateOf(false)
    private val jumpTo = mutableStateOf<Route?>(null)

    private val a11yComponent =
        "com.eatmans.wxjev/com.google.android.accessibility.selecttospeak.SelectToSpeakService"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        RecordsStore.init(this)
        settingsState = SettingsState(this)
        consumeTabExtra(intent)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        setContent {
            MiuixTheme(
                colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            ) {
                AppScaffold(
                    jumpTo = jumpTo.value,
                    onJumpConsumed = { jumpTo.value = null },
                    console = {
                        ConsolePage.Content(
                            readyText = readyText.value,
                            a11yOn = a11yOn.value,
                            keySet = keySet.value,
                            enabled = enabled.value,
                            onToggleEnabled = {
                                prefs.enabled = it
                                enabled.value = it
                            },
                            onOpenA11y = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                            onOpenOverlayPerm = {
                                startActivity(
                                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:$packageName"))
                                )
                            },
                            onOpenBattery = {
                                runCatching {
                                    startActivity(
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.parse("package:$packageName"))
                                    )
                                }
                            }
                        )
                    },
                    records = { com.eatmans.wxjev.ui.RecordsPage.Content() },
                    settings = { SettingsPageContent(settingsState) }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeTabExtra(intent)
    }

    private fun consumeTabExtra(intent: Intent?) {
        if (intent?.getStringExtra("tab") == "settings") jumpTo.value = Route.Settings
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        if (a11yOn.value) KeepAliveService.start(this)
    }

    private fun refreshStatus() {
        a11yOn.value = isA11yEnabled()
        keySet.value = prefs.hasKey()
        enabled.value = prefs.enabled
        readyText.value = when {
            a11yOn.value && keySet.value -> "已就绪，进微信聊天页试试"
            !a11yOn.value -> "无障碍未开启"
            else -> "密钥未设置"
        }
    }

    private fun isA11yEnabled(): Boolean {
        val s = Settings.Secure.getString(contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains(a11yComponent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) settingsState.shutdown()
    }
}
