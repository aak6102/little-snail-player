package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.Serializable

/**
 * 授权状态。
 *
 * 分成「存盘的 [LicenseData]」与「算出来的 [LicenseStatus]」两层，是这套设计的核心：
 * 存盘的东西全部可以被用户改（GPL 开源、手机在手，防不住），所以
 * **凡是对不上的字段一律以签名票据为准**——票据改一个字节验签就失败，改本地 JSON 没有任何收益。
 *
 * 于是 decisions 全部由 [computeStatus] 这个纯函数做，UI 与播放服务拿到的是结论而不是原始数据。
 */

/**
 * 服务端签发的票据载荷。
 *
 * 字段名与服务端 `ticket.ts` 一一对应，**不要改名**：签名对象是服务端原始 JSON 的
 * base64url，App 只是解出来看内容，改字段名不影响验签，但会让两边对不上而难以排查。
 */
@Serializable
data class TicketPayload(
    /** schema 版本，当前只认 1 */
    val v: Int = 0,
    /** 设备码：换绑后旧票的 did 与新设备不符，自动失效 */
    val did: String = "",
    val plan: String = "",
    /** 1 = 永久 */
    val perm: Int = 0,
    /** 授权到期（unix 秒）。perm=1 时为 0 */
    val exp: Long = 0L,
    /** 签发时刻（unix 秒） */
    val iat: Long = 0L,
    /** 票据自身有效期（unix 秒）。过期只代表「不能离线判定」，不代表授权失效 */
    val vu: Long = 0L,
    /** 签发序号，排查用 */
    val seq: Long = 0L,
)

/**
 * 落盘的授权数据（`license.json`）。
 *
 * 刻意只存「票据原文 + 试用的两个时间点」：
 * 到期时间、是否永久这些结论**不存**，每次由 [computeStatus] 从票据里解出来。
 * 这样手改 JSON 改不出更多权益——没有私钥就签不出新票。
 */
@Serializable
data class LicenseData(
    /** 设备码，16 位小写 hex。首次启动时生成并固定下来 */
    val deviceId: String = "",
    /** 会话凭证，续签接口用它证明「我就是这台设备」，换绑后被服务端轮换 */
    val token: String = "",
    /** 服务端签发的票据原文 */
    val ticket: String = "",
    /** 试用开始时刻（毫秒）。0 表示还没开始试用 */
    val trialStartedAtMs: Long = 0L,
    /** 试用到期时刻（毫秒） */
    val trialExpireAtMs: Long = 0L,
    /** 最近一次成功联网的时刻（毫秒），用于判断「多久没校准过时间」 */
    val lastSyncAtMs: Long = 0L,
    /** 服务端时间与本地时间的差值（毫秒）。正数表示本地慢了 */
    val clockSkewMs: Long = 0L,
)

/**
 * 当前授权结论。所有 gate 只看这个对象。
 *
 * [pro] 是唯一该被消费的布尔值：它是「能开学习模式」的充要条件，
 * 内部已经合并了「已购买」与「试用中」两种情况。
 */
data class LicenseStatus(
    val deviceId: String = "",
    /** 是否已购买（票据验签通过且未到期） */
    val licensed: Boolean = false,
    val permanent: Boolean = false,
    /** 授权到期时刻（毫秒）。[permanent] 为 true 时固定 0 */
    val expireAtMs: Long = 0L,
    /** 试用是否仍在有效期内 */
    val trialActive: Boolean = false,
    /** 试用是否开始过。未开始 = 家长还没点过开关 */
    val trialStarted: Boolean = false,
    /** 试用剩余天数，向上取整；未开始或已结束为 0 */
    val trialDaysLeft: Int = 0,
    /** 票据已过自身有效期：还能用，但要尽快联网续签 */
    val needsRefresh: Boolean = false,
    /** 是否已解锁学习模式 */
    val pro: Boolean = false,
) {
    /** 授权页副标题用的稳定文案，避免各处再拼一次。 */
    val headline: String
        get() = when {
            permanent -> "永久版"
            licensed -> "已解锁（至 ${formatDate(expireAtMs)}）"
            trialActive -> "试用中，剩余 $trialDaysLeft 天"
            trialStarted -> "试用已结束"
            else -> "未解锁"
        }
}

/** 本地日期格式化：minSdk 24 用不了 java.time，手写一份，避免为此引 desugar。 */
private fun formatDate(epochMs: Long): String {
    if (epochMs <= 0L) return "—"
    val calendar = java.util.Calendar.getInstance().apply { timeInMillis = epochMs }
    val month = calendar.get(java.util.Calendar.MONTH) + 1
    val day = calendar.get(java.util.Calendar.DAY_OF_MONTH)
    return "${calendar.get(java.util.Calendar.YEAR)}-$month-$day"
}

private const val DAY_MS = 24L * 60 * 60 * 1000
private const val TICKET_SCHEMA_VERSION = 1

/**
 * 由存盘数据 + 已验签票据算出当前结论。
 *
 * [payload] 为 null 表示本地没有票据、或验签失败——这两种情况等价处理：
 * 按未购买对待，仅保留试用。**绝不能因为「验签异常」就放行**。
 *
 * 时钟回拨的处理不加惩罚：取 `max(本地时间, 最近同步时间)` 作为判定基准，
 * 把手机时间往前调不会让试用复活，往后调也不会立刻失效（最多影响下一次联网）。
 * 不做「检测到回拨就锁死」，那会误伤大量自动校准时区误区的正经用户。
 */
fun LicenseData.computeStatus(nowMs: Long, payload: TicketPayload?): LicenseStatus {
    val effectiveNow = maxOf(nowMs, lastSyncAtMs)
    val trialStarted = trialStartedAtMs > 0L
    val trialActive = trialStarted && trialExpireAtMs > effectiveNow
    val trialDaysLeft = if (trialActive) {
        ((trialExpireAtMs - effectiveNow + DAY_MS - 1) / DAY_MS).toInt().coerceAtLeast(1)
    } else {
        0
    }

    // 票据绑定设备：换绑之后旧手机手上那张票的 did 与新设备码不一致，必须判为无效
    val bound = payload != null &&
        payload.v == TICKET_SCHEMA_VERSION &&
        deviceId.isNotBlank() &&
        payload.did.equals(deviceId, ignoreCase = true)

    val permanent = bound && payload.perm == 1
    val expireAtMs = if (bound && payload.exp > 0L) payload.exp * 1000L else 0L
    val licensed = bound && (permanent || expireAtMs > effectiveNow)
    val needsRefresh = bound && payload.vu * 1000L <= effectiveNow

    return LicenseStatus(
        deviceId = deviceId,
        licensed = licensed,
        permanent = licensed && permanent,
        expireAtMs = if (licensed) expireAtMs else 0L,
        trialActive = trialActive,
        trialStarted = trialStarted,
        trialDaysLeft = trialDaysLeft,
        needsRefresh = needsRefresh,
        pro = licensed || trialActive,
    )
}
