package dev.anilbeesetti.nextplayer.core.data.repository

import dev.anilbeesetti.nextplayer.core.model.UpdateInfo
import java.io.File

sealed interface UpdateCheckResult {
    data class Available(val info: UpdateInfo) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

sealed interface DownloadResult {
    data class Success(val file: File) : DownloadResult
    data class Failure(val message: String) : DownloadResult
}

interface UpdateRepository {
    suspend fun checkForUpdate(currentVersionCode: Int, abi: String, device: String): UpdateCheckResult

    /** 下载到 cacheDir，支持 Range 断点续传；完成后必须校验 SHA-256 */
    suspend fun download(info: UpdateInfo, onProgress: (Int) -> Unit): DownloadResult

    /** 调起系统安装界面。返回 false 表示这台设备没有能处理 APK 的 Activity */
    fun install(file: File): Boolean

    /** Android 8+ 需要用户先授权「安装未知来源应用」 */
    fun needsInstallPermission(): Boolean
}
