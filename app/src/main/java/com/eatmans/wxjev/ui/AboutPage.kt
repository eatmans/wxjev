package com.eatmans.wxjev.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.eatmans.wxjev.R

/** 关于: BottomSheet 内容（控制台页「关于」行唤起）。 */
object AboutPage {

    private const val GITHUB_URL = "https://github.com/eatmans/wxjev"
    private const val WECHAT_MP = "吴见午"
    private const val DEVELOPER = "EATMANS"

    @Composable
    fun Content() {
        val ctx = LocalContext.current
        val version = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull() ?: "?"
        val copy: (String) -> Unit = { text ->
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("about", text))
            Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
        }

        LazyColumn {
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 14.dp)
                ) {
                    Image(
                        painter = painterResource(R.drawable.app_logo),
                        contentDescription = null,
                        modifier = Modifier.size(72.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(text = "吴小见", fontSize = 19.sp)
                    Text(
                        text = "v$version · 聊天回复副驾",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            item { SmallTitle(text = "联系") }
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    ArrowPreference(
                        title = "公众号",
                        summary = WECHAT_MP,
                        onClick = { copy(WECHAT_MP) }
                    )
                    ArrowPreference(
                        title = "开发者",
                        summary = DEVELOPER,
                        onClick = { copy(DEVELOPER) }
                    )
                    ArrowPreference(
                        title = "GitHub 仓库",
                        summary = "eatmans/wxjev",
                        onClick = {
                            runCatching {
                                ctx.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                    )
                }
            }
            item {
                Text(
                    text = "仅个人学习研究使用 · 发送永远由你手动点",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 20.dp)
                )
            }
        }
    }
}
