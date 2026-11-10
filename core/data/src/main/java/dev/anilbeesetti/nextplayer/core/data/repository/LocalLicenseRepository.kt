package dev.anilbeesetti.nextplayer.core.data.repository

import android.content.Context
import android.provider.Settings
import dev.anilbeesetti.nextplayer.core.common.Logger
import dev.anilbeesetti.nextplayer.core.common.di.DiQualifiers
import dev.anilbeesetti.nextplayer.core.data.license.LicenseApi
import dev.anilbeesetti.nextplayer.core.data.license.TicketVerifier
import dev.anilbeesetti.nextplayer.core.datastore.datasource.LicenseDataSource
import dev.anilbeesetti.nextplayer.core.model.LicenseData
import dev.anilbeesetti.nextplayer.core.model.LicenseStatus
import dev.anilbeesetti.nextplayer.core.model.TicketPayload
import dev.anilbeesetti.nextplayer.core.model.computeStatus
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

/**
 * 授权的本地实现。
 *
 * 三条不可动摇的规则：
 *  1. **判定全在本地**：联网只负责「把票据换新」，绝不参与「现在能不能用」的判断；
 *  2. **失败一律静默**：任何网络异常都不弹提示、不回滚开关，最多让授权页显示「待联网确认」；
 *  3. **票据即真相**：`license.json` 里的到期时间只是缓存，真正生效的是验签后的载荷。
 */
@Single
class LocalLicenseRepository(
    private val licenseDataSource: LicenseDataSource,
    private val context: Context,
    @Named(DiQualifiers.APPLICATION_SCOPE) private val scope: CoroutineScope,
) : LicenseRepository {

    companion object {
        private const val TAG = "LocalLicenseRepository"

        /**
         * 设备码的固定盐。
         *
         * 用**常量**而不是存进 `license.json` 的随机值：随机盐一旦随文件被删（卸载重装、
         * 用户清数据），设备码就会变，用户明明买过却变成「未授权」。
         * 常量盐 + ANDROID_ID 才能做到「卸载重装不变、恢复出厂才变」。
         */
        private const val DEVICE_SALT = "snail-player-license-v1"

        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** 与服务端 `TRIAL_DAYS` 对齐的本地兜底值，仅在乐观更新时使用。 */
        private const val FALLBACK_TRIAL_DAYS = 7L

        /** 服务端没下发邮箱时的兜底，保证「发邮件」按钮永远可点。 */
        private const val FALLBACK_CONTACT_EMAIL = "ak6102@163.com"

        /** 试用上报失败后多久补报一次（见 [scheduleTrialReplay]）。 */
        private const val TRIAL_REPLAY_DELAY_MS = 15_000L
    }

    private val api = LicenseApi()

    @Volatile
    private var latest: LicenseData = LicenseData()

    @Volatile
    private var cachedDeviceId: String = ""

    /** 试用补报是否已经排过队，避免连点开关排出多次请求。 */
    private val trialReplayScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    private val _contactEmail = MutableStateFlow(FALLBACK_CONTACT_EMAIL)

    override val contactEmail: String
        get() = _contactEmail.value

    override val deviceId: String
        get() = cachedDeviceId

    /**
     * 会话凭证。与 [latest] 同源，不额外读取 DataStore —— 内容包下载是低频操作，
     * 读一次磁盘的代价可以忽略，但保持与票据同一份快照能避免「票是新设备、token 是旧设备」。
     */
    override val token: String
        get() = latest.token

    /** 缓一次验签结果：QuizGate 每秒都会问一次，不能每秒做一次 RSA。 */
    @Volatile
    private var verifiedTicket: String? = null

    @Volatile
    private var verifiedPayload: TicketPayload? = null

    override val status: StateFlow<LicenseStatus> = licenseDataSource.preferences
        .map { data ->
            latest = data
            if (data.deviceId.isNotBlank()) cachedDeviceId = data.deviceId
            data.toStatus()
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = LicenseStatus(),
        )

    init {
        scope.launch {
            runCatching {
                ensureDeviceId()
                // 冷启动同步：只在后台跑，绝不参与「界面要不要等」的决策
                refresh()
            }.onFailure { Logger.logError(TAG, "bootstrap failed: $it") }
        }
    }

    override fun statusNow(): LicenseStatus {
        val data = latest
        val now = System.currentTimeMillis()
        return data.copy(deviceId = data.deviceId.ifBlank { cachedDeviceId }).toStatus(now)
    }

    override suspend fun refresh(): Boolean {
        val id = ensureDeviceId()
        return when (val result = api.bootstrap(id, appVersion(), latest.trialStartedAtMs)) {
            is LicenseApi.Result.Ok -> {
                applySync(result.value)
                true
            }

            is LicenseApi.Result.Err -> {
                // 断网、超时、服务端抖动都走这里。用本地票据继续，不打断任何功能。
                Logger.logError(TAG, "bootstrap failed: ${result.code} ${result.message}")
                false
            }
        }
    }

    override suspend fun startTrial(): Boolean {
        val id = ensureDeviceId()
        val now = System.currentTimeMillis()

        // 先本地生效：家长点开关必须立刻看到反馈。
        // 服务端用的是 COALESCE 写入，重复上报不会覆盖真正的开始时间。
        licenseDataSource.update { data ->
            if (data.trialStartedAtMs > 0L) {
                data
            } else {
                data.copy(
                    trialStartedAtMs = now,
                    trialExpireAtMs = now + FALLBACK_TRIAL_DAYS * DAY_MS,
                )
            }
        }

        // 用 NonCancellable 包住这次请求。
        // 家长点完「开始试用」通常会立刻退出设置页，viewModelScope 随之取消；
        // 普通挂起调用会被直接掐断，服务端就永远收不到这条记录 —— 后台看到的就是
        // 「有设备、没有试用」，也正是这个 bug。
        val result = withContext(NonCancellable) { api.startTrial(id, appVersion()) }
        return when (result) {
            is LicenseApi.Result.Ok -> {
                applySync(result.value)
                true
            }

            is LicenseApi.Result.Err -> {
                Logger.logError(TAG, "startTrial failed: ${result.code} ${result.message}")
                // 本地已经生效，但云端没有这条记录。过一会儿补一次：
                // bootstrap 会带上本地起点自动补写，所以即使这次也失败，
                // 下次冷启动 / 进授权页仍能补上——这里只是把「等下次冷启动」缩短到十几秒。
                // 不立刻重试：同一条链路刚超时，紧接着再来一次大概率还是超时。
                scheduleTrialReplay()
                false
            }
        }
    }

    /**
     * 安排一次试用补报。
     *
     * 用一个独立的 flag 去重：家长可能连点几次开关，没必要排出五六次请求。
     */
    private fun scheduleTrialReplay() {
        if (!trialReplayScheduled.compareAndSet(false, true)) return
        scope.launch {
            delay(TRIAL_REPLAY_DELAY_MS)
            trialReplayScheduled.set(false)
            runCatching {
                if (latest.trialStartedAtMs > 0L) refresh()
            }.onFailure { Logger.logError(TAG, "trial replay failed: $it") }
        }
    }

    override suspend fun createOrder(planKey: String): LicenseApi.Result<LicenseApi.PurchaseOrder> =
        api.createOrder(ensureDeviceId(), planKey)

    override suspend fun pollOrder(orderNo: String): LicenseApi.Result<LicenseApi.OrderState> {
        val result = api.getOrder(orderNo)
        if (result is LicenseApi.Result.Ok && result.value.isPaid) {
            // 付款成功：把新票据落盘，[status] 随之翻转，不需要调用方再做任何事
            applyPaid(result.value.ticket, result.value.token, result.value.serverTimeMs)
        }
        return result
    }

    /* ---------------- 内部 ---------------- */

    private fun LicenseData.toStatus(nowMs: Long = System.currentTimeMillis()): LicenseStatus =
        computeStatus(nowMs, payloadOf(ticket))

    private fun payloadOf(ticket: String): TicketPayload? {
        if (ticket == verifiedTicket) return verifiedPayload
        val payload = TicketVerifier.verify(ticket)
        verifiedTicket = ticket
        verifiedPayload = payload
        return payload
    }

    private suspend fun ensureDeviceId(): String {
        val existing = licenseDataSource.preferences.first().deviceId
        if (existing.isNotBlank()) {
            cachedDeviceId = existing
            return existing
        }
        val generated = generateDeviceId()
        licenseDataSource.update { if (it.deviceId.isBlank()) it.copy(deviceId = generated) else it }
        cachedDeviceId = generated
        return generated
    }

    /**
     * `SHA256(盐 + ANDROID_ID)` 取前 16 位。
     *
     * Android 8 起 ANDROID_ID 的应用可见性改为「按签名 + 用户隔离」，
     * 同签名的包卸载重装取值不变，所以这个码是稳定的；恢复出厂才会变，
     * 那正是需要走人工换绑的场景。
     */
    private fun generateDeviceId(): String {
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()

        // 极少数定制 ROM 会返回 null/空串，此时退回随机值：
        // 授权会随重装丢失，但总比所有设备共用同一个码（互相顶号）要好。
        val seed = androidId.ifBlank { UUID.randomUUID().toString() }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$DEVICE_SALT:$seed".toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { byte -> "%02x".format(byte) }
    }

    private suspend fun applySync(response: LicenseApi.SyncResponse) {
        val now = System.currentTimeMillis()
        licenseDataSource.update { data ->
            // 服务端明确停用（换绑走了 / 人工封禁）：本地票据与 token 立即作废。
            // 不清的话，旧设备会拿着最长 30 天有效期的票据继续当 pro，
            // 「后台踢下线」就退化成「等它自己过期」。
            val revoked = response.disabled
            data.copy(
                ticket = if (revoked) "" else response.ticket?.takeIf { it.isNotBlank() } ?: data.ticket,
                token = if (revoked) "" else response.token.ifBlank { data.token },
                // 服务端没给（未开始试用）时保留本地值，别把乐观写入的试用时间抹掉
                trialStartedAtMs = response.trialStartedAtMs.takeIf { it > 0L } ?: data.trialStartedAtMs,
                trialExpireAtMs = response.trialExpireAtMs.takeIf { it > 0L } ?: data.trialExpireAtMs,
                lastSyncAtMs = now,
                clockSkewMs = if (response.serverTimeMs > 0L) response.serverTimeMs - now else data.clockSkewMs,
            )
        }
        if (response.contactEmail.isNotBlank()) _contactEmail.value = response.contactEmail
    }

    private suspend fun applyPaid(ticket: String?, token: String, serverTimeMs: Long) {
        val now = System.currentTimeMillis()
        licenseDataSource.update { data ->
            data.copy(
                ticket = ticket?.takeIf { it.isNotBlank() } ?: data.ticket,
                token = token.ifBlank { data.token },
                lastSyncAtMs = now,
                clockSkewMs = if (serverTimeMs > 0L) serverTimeMs - now else data.clockSkewMs,
            )
        }
    }

    private fun appVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")
}
