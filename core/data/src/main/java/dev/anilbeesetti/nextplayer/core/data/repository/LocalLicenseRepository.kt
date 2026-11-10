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
import dev.anilbeesetti.nextplayer.core.model.TRIAL_PLAN_KEY
import dev.anilbeesetti.nextplayer.core.model.computeStatus
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
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

        /** 服务端没下发邮箱时的兜底，保证「发邮件」按钮永远可点。 */
        private const val FALLBACK_CONTACT_EMAIL = "support@709048.xyz"
    }

    private val api = LicenseApi()

    @Volatile
    private var latest: LicenseData = LicenseData()

    @Volatile
    private var cachedDeviceId: String = ""

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

    /**
     * 领 0 元体验包。
     *
     * 服务端把体验包做成了一条普通套餐，所以这里就是「下单」：
     * 价格 0 → 服务端跳过支付宝、当场开单发货、返回票据。
     *
     * 为什么不再做本地乐观更新：以前是先记一个本地试用起点再说，
     * 断网时看着像成功了，实际上云端什么都没记上，事后对不上账也没法补。
     * 现在体验包和付费共用一条发货链路，**拿到票据才算领到**，
     * 领不到就老实告诉家长失败，让他重试或换网络。
     */
    override suspend fun claimTrialPack(): ClaimResult {
        val id = ensureDeviceId()

        // 和付款下单一样用 NonCancellable：家长点完常常立刻退出设置页，
        // viewModelScope 一取消，请求就被掐断，服务端可能已经发货、本地却没落盘。
        val result = withContext(NonCancellable) {
            api.createOrder(id, TRIAL_PLAN_KEY)
        }

        return when (result) {
            is LicenseApi.Result.Ok -> {
                val order = result.value
                if (!order.paid) {
                    // 0 元套餐不该出现 pending。真出现了说明后台把这一档的价格改了，
                    // 客户端不能自作主张放行，交回调用方提示失败。
                    Logger.logError(TAG, "trial pack returned unpaid order ${order.orderNo}")
                    return ClaimResult.Failed("体验包暂时不可用，请稍后再试")
                }
                applyPaid(order.ticket, order.token, order.serverTimeMs)
                // 记下「领过了」，之后入口不再出现；同时把天数缓存下来给文案用
                licenseDataSource.update { it.copy(trialPackClaimed = true) }
                ClaimResult.Ok
            }

            is LicenseApi.Result.Err -> when (result.code) {
                "TRIAL_USED", "ALREADY_LICENSED" -> {
                    // 领过了：这不是错误，是「换条路」——本地也记下来，别再摆这个入口
                    licenseDataSource.update { it.copy(trialPackClaimed = true) }
                    ClaimResult.AlreadyUsed
                }

                else -> {
                    Logger.logError(TAG, "claimTrialPack failed: ${result.code} ${result.message}")
                    ClaimResult.Failed(result.message.ifBlank { "网络不太好，请稍后重试" })
                }
            }
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
                // 服务端说「体验包已经不能领了」就记下来，入口不再出现；
                // 没下发（旧服务端 / 套餐下架）时保持原值，不要凭一次同步就把入口判死。
                trialPackClaimed = response.trialPlanAvailable?.let { !it } ?: data.trialPackClaimed,
                trialPackDays = response.trialPlanDays.takeIf { it > 0 } ?: data.trialPackDays,
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
