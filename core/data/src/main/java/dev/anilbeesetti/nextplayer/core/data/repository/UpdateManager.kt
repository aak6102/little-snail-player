package dev.anilbeesetti.nextplayer.core.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dev.anilbeesetti.nextplayer.core.common.di.DiQualifiers
import dev.anilbeesetti.nextplayer.core.data.repository.DownloadResult
import dev.anilbeesetti.nextplayer.core.data.repository.UpdateCheckResult
import dev.anilbeesetti.nextplayer.core.model.UpdateInfo
import dev.anilbeesetti.nextplayer.core.model.UpdateUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

/**
 * 在线升级的全局状态机（单例）。
 *
 * 把「检查 / 下载 / 安装」收敛到一处，关于页与 App 启动提醒共用同一份状态，避免两套 UI 各查各的、
 * 各弹各的。关于页只负责「触发检查」，真正的弹窗统一在 App 层（[dev.anilbeesetti.nextplayer.MainActivity]）渲染。
 *
 * 设计红线（与后端、与现有清单接口一致）：
 *  - 检查永远在后台跑，UI 线程绝不等待；Cloudflare 慢（[UpdateApi] 已有 10s 连接/读超时）也只影响这一条协程；
 *  - 启动时的自动检查用 [CHECK_TIMEOUT_MS] 兜底，超时/出错一律 fail-open（不弹错、不卡程序、不留「检查中」转圈）；
 *  - 启动检查只给一个**非模态**的轻量 banner 提醒，绝不弹模态框抢首屏；只有用户主动点「立即更新」才弹正式对话框。
 */
@Single
class UpdateManager(
    context: Context,
    private val repository: UpdateRepository,
    @Named(DiQualifiers.APPLICATION_SCOPE) private val scope: CoroutineScope,
) {

    private val appContext = context.applicationContext

    private val _uiState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    /** 是否应展示正式对话框（由用户点「立即更新」或关于页「检查更新」触发）。 */
    private val _dialogVisible = MutableStateFlow(false)
    val dialogVisible: StateFlow<Boolean> = _dialogVisible.asStateFlow()

    /** 是否应展示启动提醒 banner（发现新版本、且本次启动还没忽略）。 */
    private val _bannerVisible = MutableStateFlow(false)
    val bannerVisible: StateFlow<Boolean> = _bannerVisible.asStateFlow()

    /** 本次启动是否已忽略 banner（稍后 / 关闭对话框后都算忽略，避免反复弹）。 */
    private val _bannerDismissed = MutableStateFlow(false)

    /** 整个进程只自动检查一次，避免每次 MainActivity 重建都打一次服务端。 */
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    /** App 启动由 [dev.anilbeesetti.nextplayer.NextPlayerApplication] 调用一次：延时避开冷启动，后台、慢网络超时即放弃。 */
    fun checkOnStartup() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            // 冷启动后稍等再查：别和首屏渲染、播放器初始化抢资源；Cloudflare 慢也不影响启动
            delay(STARTUP_DELAY_MS)
            runCheck(showDialog = false)
        }
    }

    /** 关于页「检查更新」：显式弹对话框，用户能看到「检查中 / 已是最新 / 出错」。 */
    fun checkManually() {
        _dialogVisible.value = true
        scope.launch { runCheck(showDialog = true) }
    }

    private suspend fun runCheck(showDialog: Boolean) {
        if (_uiState.value is UpdateUiState.Checking) return
        if (showDialog) setState(UpdateUiState.Checking)
        val result = withTimeoutOrNull(CHECK_TIMEOUT_MS) {
            repository.checkForUpdate(
                currentVersionCode = versionCode().toInt(),
                abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
                device = deviceId(),
            )
        }
        if (result == null) {
            // 超时：后台放弃，fail-open；只有手动检查才把「检查超时」显给用户
            if (showDialog) setState(UpdateUiState.Error("检查超时，请稍后重试"))
            return
        }
        setState(
            when (result) {
                is UpdateCheckResult.Available -> UpdateUiState.Available(result.info)
                is UpdateCheckResult.UpToDate -> UpdateUiState.UpToDate
                is UpdateCheckResult.Error -> if (showDialog) {
                    UpdateUiState.Error(result.message)
                } else {
                    UpdateUiState.Idle
                }
            },
        )
    }

    /** 「立即更新」：开始下载安装（已内置「安装未知来源」前置判断）。可由 banner 或对话框触发。 */
    fun confirmUpdate() {
        val info = (_uiState.value as? UpdateUiState.Available)?.info ?: return
        startDownload(info)
    }

    private fun startDownload(info: UpdateInfo) {
        if (repository.needsInstallPermission()) {
            setState(UpdateUiState.NeedInstallPermission)
            return
        }
        scope.launch {
            setState(UpdateUiState.Downloading(0))
            when (
                val result = repository.download(info) { percent ->
                    setState(UpdateUiState.Downloading(percent))
                }
            ) {
                is DownloadResult.Success -> {
                    val installed = repository.install(result.file)
                    setState(
                        if (installed) {
                            UpdateUiState.Installing
                        } else {
                            UpdateUiState.Error("无法打开安装界面，请到下载目录手动安装")
                        },
                    )
                }
                is DownloadResult.Failure -> setState(UpdateUiState.Error(result.message))
            }
        }
    }

    /** 去系统设置授权「安装未知来源应用」。 */
    fun openInstallSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${appContext.packageName}"))
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(intent) }
    }

    /** 用户点 banner「稍后」：本次启动不再弹 banner。 */
    fun dismissBanner() {
        _bannerDismissed.value = true
        recomputeBanner()
    }

    /** 用户点 banner「立即更新」：打开正式对话框（由 App 层监听 [dialogVisible] 渲染）。 */
    fun openDialog() {
        _bannerDismissed.value = true
        recomputeBanner()
        _dialogVisible.value = true
    }

    /** 关闭正式对话框；一并忽略 banner，避免关闭后立刻又弹提醒。 */
    fun closeDialog() {
        _dialogVisible.value = false
        _bannerDismissed.value = true
        recomputeBanner()
    }

    /** uiState / bannerDismissed 变化后重算 banner 可见性，集中维护避免两处状态打架。 */
    private fun recomputeBanner() {
        _bannerVisible.value = _uiState.value is UpdateUiState.Available && !_bannerDismissed.value
    }

    private fun setState(state: UpdateUiState) {
        _uiState.value = state
        recomputeBanner()
    }

    private fun deviceId(): String = runCatching {
        Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
    }.getOrDefault("")

    @Suppress("DEPRECATION")
    private fun versionCode(): Long {
        val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
    }

    private companion object {
        const val TAG = "UpdateManager"
        private const val STARTUP_DELAY_MS = 2_000L
        private const val CHECK_TIMEOUT_MS = 15_000L
    }
}
