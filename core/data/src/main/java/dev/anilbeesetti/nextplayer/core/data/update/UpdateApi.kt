package dev.anilbeesetti.nextplayer.core.data.update

import dev.anilbeesetti.nextplayer.core.model.UpdateInfo
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 在线升级的版本清单接口。
 *
 * 沿用 LicenseApi 的约定：只用 HttpURLConnection + org.json，刻意不引入 OkHttp。
 * 检查更新是用户主动点的，失败要能看到提示；但服务端本身是 fail-open 的
 * （异常时返回 has_update=false），所以这里网络失败只当「暂时查不到」处理。
 */
class UpdateApi(private val baseUrl: String = DEFAULT_BASE_URL) {

    companion object {
        const val DEFAULT_BASE_URL = "https://snail.709048.xyz"
        private const val TIMEOUT_MS = 10_000
    }

    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        data class Err(val code: String, val message: String) : Result<Nothing>
    }

    suspend fun check(versionCode: Int, abi: String, device: String): Result<UpdateInfo?> =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val query = "vc=$versionCode" +
                    "&abi=${URLEncoder.encode(abi, "UTF-8")}" +
                    "&device=${URLEncoder.encode(device, "UTF-8")}"
                connection = (URL("$baseUrl/api/v1/app/update?$query").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    useCaches = false
                    setRequestProperty("Accept", "application/json")
                }

                val code = connection.responseCode
                val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) return@withContext Result.Err("HTTP_$code", "服务暂时不可用")

                val root = JSONObject(text)
                if (!root.optBoolean("ok", false)) return@withContext Result.Err("SERVER", "服务暂时不可用")
                val data = root.optJSONObject("data") ?: return@withContext Result.Ok(null)
                if (!data.optBoolean("has_update", false)) return@withContext Result.Ok(null)

                val url = data.optString("download_url")
                // 只认自家域名：万一 DNS/服务端被劫持，不能让 App 去下载来路不明的 APK
                if (!url.startsWith(baseUrl)) return@withContext Result.Err("BAD_URL", "更新地址不合法")

                Result.Ok(
                    UpdateInfo(
                        versionCode = data.optInt("vc"),
                        versionName = data.optString("vn"),
                        abi = data.optString("abi"),
                        sizeBytes = data.optLong("size"),
                        sha256 = data.optString("sha256"),
                        notes = data.optString("notes"),
                        minVersionCode = data.optInt("min_vc"),
                        force = data.optBoolean("force"),
                        downloadUrl = url,
                    ),
                )
            } catch (e: IOException) {
                Result.Err("NETWORK_ERROR", "网络不可用，请稍后再试")
            } catch (e: Exception) {
                Result.Err("CLIENT_ERROR", e.message ?: "检查更新失败")
            } finally {
                connection?.disconnect()
            }
        }
}
