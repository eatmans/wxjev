package com.eatmans.wxjev.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import top.yukonga.miuix.kmp.nav.transition.NavTransitions

/** 三个顶级页: 控制台 / 记录 / 设置。关于走控制台页的 BottomSheet。 */
sealed interface Route : NavKey {
    data object Console : Route
    data object Records : Route
    data object Settings : Route
}

/**
 * 应用骨架: 底部 NavigationBar 切换三个顶级页, 页面渲染走 miuix-nav 的
 * NavDisplay(单元素栈 + replace 切换, 带原生转场)。
 *
 * @param jumpTo 外部要求跳到的页(悬浮窗「打开设置」), 消费后置回 null。
 */
@Composable
fun AppScaffold(
    jumpTo: Route?,
    onJumpConsumed: () -> Unit,
    console: @Composable () -> Unit,
    records: @Composable () -> Unit,
    settings: @Composable () -> Unit
) {
    val backStack: NavBackStack = remember { navBackStackOf(Route.Console) }

    LaunchedEffect(jumpTo) {
        if (jumpTo != null) {
            switchTo(backStack, jumpTo)
            onJumpConsumed()
        }
    }

    val tabs = listOf(
        Triple(Route.Console, "控制台", MiuixIcons.GridView),
        Triple(Route.Records, "记录", MiuixIcons.Messages),
        Triple(Route.Settings, "设置", MiuixIcons.Settings)
    )
    val current = backStack.lastOrNull() ?: Route.Console

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { (route, label, icon) ->
                    NavigationBarItem(
                        selected = current == route,
                        onClick = { switchTo(backStack, route) },
                        icon = icon,
                        label = label
                    )
                }
            }
        }
    ) { outer ->
        NavDisplay(
            backStack = backStack,
            transition = NavTransitions.MiuixDefault,
            modifier = Modifier.padding(bottom = outer.calculateBottomPadding())
        ) {
            entry<Route.Console> { console() }
            entry<Route.Records> { records() }
            entry<Route.Settings> { settings() }
        }
    }
}

/** 单元素栈替换 = 标签切换; 同页重点击不动作。 */
private fun switchTo(backStack: NavBackStack, route: Route) {
    if (backStack.lastOrNull() == route) return
    backStack.clear()
    backStack.add(route)
}
