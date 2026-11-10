package dev.anilbeesetti.nextplayer.core.model

/**
 * 服务端下发的可用更新。
 *
 * 版本比对一律用 [versionCode]（整数），不用 versionName 字符串；
 * [sha256] 必须在下载完成后校验，防止 APK 被劫持或传输被截断。
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val abi: String,
    val sizeBytes: Long,
    val sha256: String,
    val notes: String,
    val minVersionCode: Int,
    val force: Boolean,
    val downloadUrl: String,
)
