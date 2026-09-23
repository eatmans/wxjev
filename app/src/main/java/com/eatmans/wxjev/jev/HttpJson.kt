package com.eatmans.wxjev.jev

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/** 三路接口的来源标识, 用于生成用户能看懂的错误文案（判断接口/回复接口/…）。 */
object Route {
    const val JUDGE = "判断接口"
    const val REPLY = "回复接口"
}

/**
 * 携带来源路由、HTTP 状态码（null = 传输层失败）与响应体前 120 字,
 * 设置页/面板据此展示真实原因。
 */
class ApiException(
    val route: String,
    val status: Int?,
    val snippet: String
) : RuntimeException(buildMessage(route, status, snippet)) {
    companion object {
        fun buildMessage(route: String, status: Int?, snippet: String): String =
            if (status != null) "$route HTTP $status：${snippet.take(120)}"
            else "$route 请求失败：${snippet.take(120)}"
    }
}

/**
 * 共用 POST-JSON: UTF-8、429/529 指数退避、其它 4xx 不重试、
 * 一切失败归一为 [ApiException]。密钥按调用传入, 绝不进日志。
 */
object HttpJson {

    private const val MAX_ATTEMPTS = 3

    fun post(
        url: String,
        key: String,
        body: JSONObject,
        route: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): JSONObject {
        var attempt = 0
        var last: ApiException? = null
        while (attempt < MAX_ATTEMPTS) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 40000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $key")
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                }
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                conn.outputStream.use { os: OutputStream -> os.write(bytes) }
                val code = conn.responseCode
                if (code == 429 || code == 529) {
                    last = ApiException(route, code, "服务繁忙，已重试")
                    attempt++
                    if (attempt < MAX_ATTEMPTS) Thread.sleep(500L * (1L shl attempt))
                    continue
                }
                // 先判状态码再读体: errorStream 在部分失败/OEM 栈上为 null, 读也可能抛——
                // 若先读体, 状态码会被丢掉, 401 会被当成传输失败重试。
                if (code !in 200..299) {
                    val errText = readBody(conn.errorStream)
                    throw ApiException(route, code, errText.ifBlank { "（响应体为空）" })
                }
                val text = readBody(conn.inputStream)
                if (text.isBlank()) throw ApiException(route, code, "响应体为空")
                return JSONObject(text)
            } catch (e: ApiException) {
                if (e.status != null && e.status in 400..499) throw e  // 客户端错误: 不重试
                last = e
                attempt++
                if (attempt < MAX_ATTEMPTS) Thread.sleep(500L * (1L shl attempt))
            } catch (e: Exception) {
                last = ApiException(route, null, describe(e))
                attempt++
                if (attempt < MAX_ATTEMPTS) Thread.sleep(500L * (1L shl attempt))
            } finally {
                conn?.disconnect()
            }
        }
        throw last ?: ApiException(route, null, "请求失败")
    }

    /** 响应体文本; 流为 null 或读失败一律返回 ""，绝不因此丢状态码。 */
    private fun readBody(stream: java.io.InputStream?): String {
        stream ?: return ""
        return try {
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        } catch (_: Exception) { "" }
    }

    /** OpenRouter 要求归因头; 其它主机会礼貌忽略未知头。 */
    fun headersFor(url: String): Map<String, String> =
        if (url.contains("openrouter.ai", ignoreCase = true))
            mapOf("HTTP-Referer" to "https://wechatjev.local", "X-Title" to "WeChatJev")
        else emptyMap()

    /** 传输失败的人话（不出现任何密钥材料）。 */
    private fun describe(e: Exception): String {
        val m = e.message ?: e.javaClass.simpleName
        return when {
            m.contains("timed out") || m.contains("timeout", true) -> "网络超时，请检查连接"
            m.contains("Unable to resolve host") -> "域名解析失败，地址填错或无网络"
            m.contains("Failed to connect") || m.contains("ECONNREFUSED") -> "无法连接该地址"
            m.contains("CertPath") || m.contains("SSL") -> "HTTPS 证书校验失败"
            else -> m
        }
    }
}
