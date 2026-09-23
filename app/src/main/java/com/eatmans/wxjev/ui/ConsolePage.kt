package com.eatmans.wxjev.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.input.nestedscroll.nestedScroll

/** 控制台: LSPosed 风——状态大卡 + 信息卡 + 权限/助手/关于。 */
object ConsolePage {

    @Composable
    fun Content(
        readyText: String,
        a11yOn: Boolean,
        keySet: Boolean,
        enabled: Boolean,
        onToggleEnabled: (Boolean) -> Unit,
        onOpenA11y: () -> Unit,
        onOpenOverlayPerm: () -> Unit,
        onOpenBattery: () -> Unit
    ) {
        var showAbout by remember { mutableStateOf(false) }
        val ctx = LocalContext.current
        val dark = isSystemInDarkTheme()
        val version = remember {
            runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }
                .getOrNull() ?: "?"
        }
        val wechatInstalled = remember {
            runCatching { ctx.packageManager.getPackageInfo("com.tencent.mm", 0) }.isSuccess
        }
        val ready = a11yOn && keySet
        val scrollBehavior = MiuixScrollBehavior()

        Scaffold(
            topBar = { TopAppBar(title = "WxJev", scrollBehavior = scrollBehavior) }
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
                // 状态大卡: 开关内嵌——关=灰「已关闭」, 开+就绪=绿, 开+未就绪=橙
                item { HeroCard(readyText, ready, version, dark, enabled, onToggleEnabled) }
                // 信息卡
                item { SmallTitle(text = "状态") }
                item {
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        InfoRow("无障碍服务", if (a11yOn) "已开启" else "未开启")
                        InfoRow("判断密钥", if (keySet) "已设置" else "未设置")
                        InfoRow("微信", if (wechatInstalled) "已安装" else "未检测到")
                    }
                    Spacer(Modifier.height(4.dp))
                }
                // 权限
                item { SmallTitle(text = "权限") }
                item {
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        ArrowPreference(title = "无障碍", summary = "开启「吴小见WxJev聊天助手」", onClick = onOpenA11y)
                        ArrowPreference(title = "悬浮窗", summary = "个别 ROM 需要", onClick = onOpenOverlayPerm)
                        ArrowPreference(title = "自启动 + 省电无限制", summary = "小米必做", onClick = onOpenBattery)
                    }
                    Spacer(Modifier.height(4.dp))
                }
                // 关于 → BottomSheet
                item {
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        ArrowPreference(title = "关于", onClick = { showAbout = true })
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        OverlayBottomSheet(
            title = "关于",
            show = showAbout,
            allowDismiss = true,
            enableNestedScroll = true,
            onDismissRequest = { showAbout = false },
            onDismissFinished = { }
        ) {
            AboutPage.Content()
        }
    }

    /** 状态大卡: 开关内嵌。关=灰「已关闭」; 开+就绪=绿; 开+未就绪=橙。 */
    @Composable
    private fun HeroCard(
        subtitle: String,
        ready: Boolean,
        version: String,
        dark: Boolean,
        enabled: Boolean,
        onToggleEnabled: (Boolean) -> Unit
    ) {
        val bg = when {
            !enabled -> if (!dark) Color(0xFFEEF0F3) else Color(0xFF2A2C31)
            ready && !dark -> Color(0xFFE3F4E3)
            ready -> Color(0xFF1E3327)
            !dark -> Color(0xFFFDEEE2)
            else -> Color(0xFF3A2E22)
        }
        val accent = when {
            !enabled -> if (!dark) Color(0xFF6B7280) else Color(0xFF9CA3AF)
            ready -> Color(0xFF16A34A)
            else -> Color(0xFFD97706)
        }
        Card(modifier = Modifier.padding(horizontal = 12.dp)) {
            Row(
                modifier = Modifier
                    .background(bg)
                    .padding(horizontal = 20.dp, vertical = 18.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = when {
                            !enabled -> "已关闭"
                            ready -> "已就绪"
                            else -> "未就绪"
                        },
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = accent
                    )
                    Text(
                        text = if (enabled) subtitle else "打开右上开关以启用助手",
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Text(
                        text = "v$version · 吴小见",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Switch(checked = enabled, onCheckedChange = onToggleEnabled)
            }
        }
        Spacer(Modifier.height(4.dp))
    }

    @Composable
    private fun InfoRow(label: String, value: String) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(text = label, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(
                text = value,
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}
