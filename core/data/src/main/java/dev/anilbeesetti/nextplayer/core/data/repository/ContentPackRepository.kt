package dev.anilbeesetti.nextplayer.core.data.repository

import android.content.Context
import dev.anilbeesetti.nextplayer.core.common.Logger
import dev.anilbeesetti.nextplayer.core.common.di.DiQualifiers
import dev.anilbeesetti.nextplayer.core.data.content.ContentApi
import dev.anilbeesetti.nextplayer.core.model.ContentPackState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

/**
 * 词库内容包（云端下发）的本地管理。
 *
 * 分工刻意拆开：
 *  - 本类只管「下载、校验、落盘、报告状态」；
 *  - 读词表仍然是 [QuizBankRepository] 的事，它通过 [installedFile] 问一句
 *    「这个文件名本地有没有更新的版本」。
 *
 * 三条底线：
 *  1. **失败一律保留旧包**。下载写 `.part`，校验通过才改名；中途断网 / 断进程
 *     都不会把好词库换成一个半截文件。
 *  2. **免费版不发请求**。没解锁的用户连 manifest 都不必问——内置 220 词就是他要的全部。
 *  3. **绝不阻塞首屏**。同步在 applicationScope 里跑，界面拿到的第一帧永远是本地包。
 */
@Single
class ContentPackRepository(
    private val licenseRepository: LicenseRepository,
    context: Context,
    @Named(DiQualifiers.APPLICATION_SCOPE) private val scope: CoroutineScope,
) {

    companion object {
        private const val TAG = "ContentPackRepository"

        /** 落盘目录，位于 filesDir 下（不是 cacheDir：清缓存不该把词库也清掉） */
        const val DIR = "wordpack"

        /** 云端包落盘的文件名，与 assets 内同名，加载层才能无缝替换 */
        const val PACK_FILE = "five_levels.json"

        /** 下载中的临时文件。任何非成功路径都不该把它留在磁盘上（见 [ContentApi.download]）。 */
        private const val PART_FILE = "$PACK_FILE.part"

        private const val META_FILE = "meta.json"
    }

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, DIR)
    private val packFile = File(dir, PACK_FILE)
    private val partFile = File(dir, PART_FILE)
    private val metaFile = File(dir, META_FILE)
    private val api = ContentApi()

    private val _state = MutableStateFlow(readState())
    val state: StateFlow<ContentPackState> = _state.asStateFlow()

    init {
        // 上次下载被系统杀进程打断时会留下半截 .part。它在任何路径上都没有用处：
        // 下次下载一开始就会删掉重建，留着只是白占三百多 KB，还会让排查时误以为下载在进行。
        runCatching { if (partFile.exists()) partFile.delete() }
    }

    /** 防止 [startAutoSync] 被多次调用时挂上多个收集器 */
    private val syncStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 启动后台自动同步。
     *
     * **刻意不放在 init 里**：这个类会被 [QuizBankRepository] 注入，而题库在
     * Robolectric 测试里也会被构造 —— 构造函数一联网，几十个与词库无关的用例
     * 都要等一次超时。改由 Application 显式调用，测试不调就没有任何网络行为。
     *
     * 只在「达成 pro」的那一刻触发一次。冷启动时 status 先给默认值 false，
     * DataStore 读到真值后才是最终结果，所以用 distinctUntilChanged 避免
     * 每次读盘都去打一次接口。
     */
    fun startAutoSync() {
        if (!syncStarted.compareAndSet(false, true)) return
        scope.launch {
            runCatching {
                licenseRepository.status
                    .map { it.pro }
                    .distinctUntilChanged()
                    .collect { pro -> if (pro) sync() }
            }.onFailure { Logger.logError(TAG, "auto sync failed: $it") }
        }
    }

    /** 已安装版本；0 = 只有内置包 */
    fun installedVersion(): Int = _state.value.installedVersion

    /**
     * 本地是否有可用的云端包。
     *
     * @param fileName assets 里的文件名，目前只有 [PACK_FILE] 会被云端覆盖
     * @return 命中返回文件；否则 null，调用方回落到 assets
     */
    fun installedFile(fileName: String): File? {
        if (fileName != PACK_FILE) return null
        if (_state.value.installedVersion <= 0) return null
        return packFile.takeIf { it.isFile && it.length() > 0 }
    }

    /**
     * 检查并安装。
     *
     * @param reportErrors 用户手动点的更新要能看到失败原因；后台静默同步传 false，
     *                     静默同步失败不该在设置页留下一条永久的红字。
     * @return 是否成功更新到新版本
     */
    suspend fun sync(reportErrors: Boolean = false): Boolean {
        val status = licenseRepository.statusNow()
        if (!status.pro) {
            _state.value = _state.value.copy(
                checked = true,
                availableVersion = 0,
                busy = false,
                message = "",
                error = if (reportErrors) "完整词库随学习模式一起解锁" else "",
            )
            return false
        }

        val device = licenseRepository.deviceId
        if (device.isBlank()) return false

        _state.value = _state.value.copy(busy = true, error = "")
        val manifest = api.manifest(device, licenseRepository.token, installedVersion(), appVersionCode())
        if (manifest is ContentApi.Result.Err) {
            _state.value = _state.value.copy(
                checked = true,
                busy = false,
                error = if (reportErrors) manifest.message else "",
                message = "",
            )
            return false
        }

        val info = (manifest as ContentApi.Result.Ok).value
        if (info == null) {
            _state.value = _state.value.copy(
                checked = true,
                busy = false,
                availableVersion = 0,
                error = "",
                message = if (reportErrors) "词库已是最新" else "",
            )
            return false
        }

        _state.value = _state.value.copy(availableVersion = info.version)

        // 家长点完更新通常立刻退出设置页，viewModelScope 会随之取消；
        // 用 NonCancellable 包住，别让 300KB 的包因为切了个页面就白下
        val result = withContext(NonCancellable + Dispatchers.IO) { api.download(info, packFile) }
        return when (result) {
            is ContentApi.Result.Err -> {
                Logger.logError(TAG, "download failed: ${result.code} ${result.message}")
                // 兜底再删一次：正常路径已经由 ContentApi 的 finally 清掉了，
                // 这里防止那段逻辑将来被改坏——残留的半截文件没有任何用处。
                runCatching { if (partFile.exists()) partFile.delete() }
                _state.value = _state.value.copy(
                    busy = false,
                    error = if (reportErrors) result.message else "",
                )
                false
            }

            is ContentApi.Result.Ok -> {
                writeMeta(info.version, info.notes)
                _state.value = _state.value.copy(
                    installedVersion = info.version,
                    availableVersion = info.version,
                    checked = true,
                    busy = false,
                    error = "",
                    message = "词库已更新到 v${info.version}",
                )
                true
            }
        }
    }

    /* ---------------- 元数据 ---------------- */

    /**
     * 丢弃已安装的包，回落到内置包。
     *
     * 只在「包解析失败」时由加载层调用 —— 留着坏文件的话，每台设备都会永久
     * 退回内置包，而用户以为更新成功了。删掉后下次冷启动会重新拉一份。
     */
    fun discardInstalled() {
        runCatching {
            packFile.delete()
            partFile.delete()
            metaFile.delete()
        }
        _state.value = _state.value.copy(installedVersion = 0, availableVersion = 0)
    }

    private fun readState(): ContentPackState {
        val version = runCatching {
            if (!metaFile.isFile) return@runCatching 0
            JSONObject(metaFile.readText()).optInt("version", 0)
        }.getOrDefault(0)

        // 元数据说有版本但文件不见了（用户用清理工具删过 data 目录）：当作没装，回落到 assets
        val usable = version > 0 && packFile.isFile && packFile.length() > 0
        return ContentPackState(installedVersion = if (usable) version else 0)
    }

    private fun writeMeta(version: Int, notes: String) {
        runCatching {
            dir.mkdirs()
            metaFile.writeText(
                JSONObject()
                    .put("version", version)
                    .put("file", PACK_FILE)
                    .put("notes", notes)
                    .put("installed_at", System.currentTimeMillis())
                    .toString(),
            )
        }.onFailure { Logger.logError(TAG, "write meta failed: $it") }
    }

    /** 当前 App 的 versionCode，服务端用它挡「新包下发给了老客户端」 */
    private fun appVersionCode(): Int = runCatching {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }.getOrDefault(0)
}
