package dev.anilbeesetti.nextplayer.core.data.repository

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import dev.anilbeesetti.nextplayer.core.data.update.UpdateApi
import dev.anilbeesetti.nextplayer.core.model.UpdateInfo
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

/**
 * 在线升级的本地实现。
 *
 * 下载刻意不用 DownloadManager，而是自己发 HttpURLConnection：
 *  - 服务端（Cloudflare Worker 代理 R2）已验证支持 Range/206，能断点续传；
 *  - 可以边下边算 SHA-256，装之前就能发现被劫持或截断的包；
 *  - 不依赖系统下载管理器，精简 ROM 和 Android TV 上行为一致。
 *
 * 下载目的固定为 cacheDir，FileProvider 的 cache-path 已覆盖，无需改 Manifest。
 */
@Single
class LocalUpdateRepository(
    context: Context,
) : UpdateRepository {

    private val appContext = context.applicationContext
    private val api = UpdateApi()

    override suspend fun checkForUpdate(currentVersionCode: Int, abi: String, device: String): UpdateCheckResult =
        withContext(Dispatchers.IO) {
            when (val result = api.check(currentVersionCode, abi, device)) {
                is UpdateApi.Result.Ok -> result.value?.let { UpdateCheckResult.Available(it) }
                    ?: UpdateCheckResult.UpToDate
                is UpdateApi.Result.Err -> UpdateCheckResult.Error(result.message)
            }
        }

    override suspend fun download(info: UpdateInfo, onProgress: (Int) -> Unit): DownloadResult =
        withContext(Dispatchers.IO) {
            val file = File(appContext.cacheDir, "snail-update-${info.versionCode}-${info.abi}.apk")
            val total = info.sizeBytes.coerceAtLeast(1L)
            var existing = if (file.exists()) file.length() else 0L

            // 上次可能已经下完但没来得及安装：先验一次，别再白下 19MB
            if (existing > total) {
                file.delete()
                existing = 0
            }
            if (existing == total) {
                if (sha256Of(file).equals(info.sha256, ignoreCase = true)) {
                    onProgress(100)
                    return@withContext DownloadResult.Success(file)
                }
                file.delete()
                existing = 0
            }

            val digest = MessageDigest.getInstance("SHA-256")
            if (existing > 0) feedDigest(file, digest)

            var connection: HttpURLConnection? = null
            try {
                connection = (URL(info.downloadUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    useCaches = false
                    // 不让中间层做压缩，Content-Length 与 APK 字节必须一一对应
                    setRequestProperty("Accept-Encoding", "identity")
                    if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
                }
                connection.connect()

                val append: Boolean
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        // 服务端没吃 Range：必须从头下，否则会把两份内容拼在一起
                        if (existing > 0) {
                            file.delete()
                            existing = 0
                            digest.reset()
                        }
                        append = false
                    }
                    HttpURLConnection.HTTP_PARTIAL -> append = true
                    else -> return@withContext DownloadResult.Failure("下载失败（HTTP ${connection.responseCode}）")
                }

                var done = existing
                connection.inputStream.use { input ->
                    FileOutputStream(file, append).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            done += read
                            onProgress(((done * 100) / total).toInt().coerceIn(0, 100))
                        }
                    }
                }

                val actual = digest.digest().toHex()
                if (!actual.equals(info.sha256, ignoreCase = true)) {
                    file.delete()
                    return@withContext DownloadResult.Failure("安装包校验失败，已删除，请重试")
                }
                DownloadResult.Success(file)
            } catch (e: Exception) {
                DownloadResult.Failure(e.message ?: "下载失败，请稍后重试")
            } finally {
                connection?.disconnect()
            }
        }

    override fun install(file: File): Boolean = try {
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    override fun needsInstallPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !appContext.packageManager.canRequestPackageInstalls()

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        feedDigest(file, digest)
        return digest.digest().toHex()
    }

    private fun feedDigest(file: File, digest: MessageDigest) {
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) digest.update(buffer, 0, read)
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
