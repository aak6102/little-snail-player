package dev.anilbeesetti.nextplayer.core.data.content

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 内容包（词库）接口。
 *
 * 与 [dev.anilbeesetti.nextplayer.core.data.update.UpdateApi] 同一套约定：
 * 只用 HttpURLConnection + org.json，不引 OkHttp；失败一律降级，不阻塞任何本地功能。
 *
 * 与在线升级的两点不同：
 *  1. **需要凭据**（device + token）—— 词库只发给已授权或试用中的设备；
 *  2. **不支持 Range**：包只有 300KB 量级，断点续传的复杂度远大于重下一次，
 *     所以失败就整包重下，落盘前一次性校验 SHA-256。
 */
class ContentApi(private val baseUrl: String = DEFAULT_BASE_URL) {

    companion object {
        const val DEFAULT_BASE_URL = "https://snail.709048.xyz"
        private const val TIMEOUT_MS = 15_000
        private const val DOWNLOAD_TIMEOUT_MS = 30_000

        /** 下载最多尝试几次（含首次）。整包 300KB 量级，重下的代价可以忽略。 */
        private const val DOWNLOAD_ATTEMPTS = 2

        /** 两次下载之间的间隔：给运营商出口一次从拥塞里缓过来的时间。 */
        private const val RETRY_DELAY_MS = 1_500L

        /** 词库包再长也不会超过这个量级；超了就说明下到的不是词库（HTML 错误页、被劫持等） */
        private const val MAX_PACK_BYTES = 8L * 1024 * 1024
    }

    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        data class Err(val code: String, val message: String) : Result<Nothing>
    }

    data class PackInfo(
        val version: Int,
        val sizeBytes: Long,
        val sha256: String,
        val notes: String,
        val downloadUrl: String,
    )

    /**
     * 查询当前下发版本。
     *
     * @param localVersion 本地已安装的版本，服务端据此直接告诉我们「不用下」
     * @param appVersionCode 当前 App 的 versionCode，服务端据此挡住「新包老客户端」的组合
     * @return null 表示服务端没有可下发的包（未发布 / 指针为 0），不是错误
     */
    suspend fun manifest(deviceId: String, token: String, localVersion: Int, appVersionCode: Int): Result<PackInfo?> =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val query = "device=${URLEncoder.encode(deviceId, "UTF-8")}" +
                    "&local=$localVersion" +
                    "&vc=$appVersionCode" +
                    if (token.isNotBlank()) "&token=${URLEncoder.encode(token, "UTF-8")}" else ""
                connection = (URL("$baseUrl/api/v1/content/wordpack?$query").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    useCaches = false
                    setRequestProperty("Accept", "application/json")
                }

                val code = connection.responseCode
                val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()

                if (code == 403) return@withContext Result.Err("FORBIDDEN", "当前没有可更新的词库")
                if (code !in 200..299) return@withContext Result.Err("HTTP_$code", "服务暂时不可用")

                val root = JSONObject(text)
                if (!root.optBoolean("ok", false)) {
                    val err = root.optJSONObject("error")
                    return@withContext Result.Err(
                        err?.optString("code").orEmpty().ifBlank { "SERVER" },
                        err?.optString("message").orEmpty().ifBlank { "服务暂时不可用" },
                    )
                }

                val data = root.optJSONObject("data") ?: return@withContext Result.Ok(null)
                val version = data.optInt("version", 0)
                if (version <= 0) return@withContext Result.Ok(null)
                if (data.optBoolean("up_to_date", false)) return@withContext Result.Ok(null)

                val url = data.optString("url")
                // 只认自家域名：DNS 被劫持时不能把词库下到别处去
                if (!url.startsWith(baseUrl)) return@withContext Result.Err("BAD_URL", "下载地址不合法")

                Result.Ok(
                    PackInfo(
                        version = version,
                        sizeBytes = data.optLong("size"),
                        sha256 = data.optString("sha256"),
                        notes = data.optString("notes"),
                        downloadUrl = url,
                    ),
                )
            } catch (e: IOException) {
                Result.Err("NETWORK_ERROR", "网络不可用")
            } catch (e: Exception) {
                Result.Err("CLIENT_ERROR", e.message ?: "检查词库失败")
            } finally {
                connection?.disconnect()
            }
        }

    /**
     * 下载并校验。成功后 [target] 已是完整且摘要匹配的文件。
     *
     * 先写 `.part` 再改名：中途断电或进程被杀，下次不会把半个文件当成好包读进去。
     *
     * 失败会整包重下一次：包只有 300KB 量级，重下的成本远小于实现断点续传，
     * 而「国内 → Cloudflare」偶发二三十秒不响应是常态——一次超时就放弃，
     * 会让家长点「更新词库」时好时坏，看起来像功能坏了。
     */
    suspend fun download(info: PackInfo, target: File): Result<Unit> = withContext(Dispatchers.IO) {
        var last: Result<Unit> = Result.Err("NETWORK_ERROR", "下载失败")
        repeat(DOWNLOAD_ATTEMPTS) { attempt ->
            last = downloadOnce(info, target)
            if (last is Result.Ok) return@withContext last
            if (attempt < DOWNLOAD_ATTEMPTS - 1) delay(RETRY_DELAY_MS)
        }
        last
    }

    /**
     * 单次下载。
     *
     * 清理放在 finally 而不是各条错误分支里：分支有四五条（HTTP 非 2xx、体积超限、
     * 长度不符、摘要不符、超时、协程取消），漏一条就会在 `filesDir` 里留下一份
     * 三百多 KB 的半截 `.part`，既占空间又让人误判「下载还在进行」。
     */
    private suspend fun downloadOnce(info: PackInfo, target: File): Result<Unit> = withContext(Dispatchers.IO) {
        val temp = File(target.parentFile, "${target.name}.part")
        var connection: HttpURLConnection? = null
        var installed = false
        try {
            temp.parentFile?.mkdirs()
            if (temp.exists()) temp.delete()

            connection = (URL(info.downloadUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = DOWNLOAD_TIMEOUT_MS
                useCaches = false
                // 不让中间层压缩：Content-Length 与落盘字节必须一一对应
                setRequestProperty("Accept-Encoding", "identity")
            }
            connection.connect()
            if (connection.responseCode !in 200..299) {
                return@withContext Result.Err("HTTP_${connection.responseCode}", "下载失败")
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            connection.inputStream.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        written += read
                        if (written > MAX_PACK_BYTES) {
                            return@withContext Result.Err("TOO_LARGE", "内容包异常")
                        }
                    }
                }
            }

            if (info.sizeBytes > 0 && written != info.sizeBytes) {
                return@withContext Result.Err("SIZE_MISMATCH", "下载不完整，请重试")
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (!actual.equals(info.sha256, ignoreCase = true)) {
                return@withContext Result.Err("BAD_SHA", "校验失败，请重试")
            }

            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                return@withContext Result.Err("IO_ERROR", "写入失败，请检查存储空间")
            }
            installed = true
            Result.Ok(Unit)
        } catch (e: Exception) {
            Result.Err("NETWORK_ERROR", e.message ?: "下载失败")
        } finally {
            connection?.disconnect()
            if (!installed) runCatching { temp.delete() }
        }
    }
}
