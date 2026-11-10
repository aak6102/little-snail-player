package dev.anilbeesetti.nextplayer.core.data.repository

import dev.anilbeesetti.nextplayer.core.data.license.LicenseApi
import dev.anilbeesetti.nextplayer.core.model.LicenseStatus
import kotlinx.coroutines.flow.StateFlow

/**
 * 领取 0 元体验包的结果。
 *
 * 三种情况必须分开，因为家长看到的东西完全不同：
 * 领到了（开关直接亮）、已经领过（该引导去买，不是骂他网络）、失败了（可以重试）。
 */
sealed interface ClaimResult {
    data object Ok : ClaimResult

    /** 这台设备已经领过（或已经买过），服务端拒绝再发一次。 */
    data object AlreadyUsed : ClaimResult

    /** 网络或服务端异常，本地没有拿到任何票据。 */
    data class Failed(val message: String) : ClaimResult
}

/**
 * 授权与体验包。
 *
 * 消费端只需要记住一件事：**[status] 是本地算出来的，不依赖网络**。
 * 冷启动不等待任何请求，界面 0ms 就能决定要不要锁学习模式。
 */
interface LicenseRepository {

    /** 当前授权结论。联网成功后才更新，离线时保持上一次的结果。 */
    val status: StateFlow<LicenseStatus>

    /**
     * 用「此刻」重新算一遍结论。
     *
     * [status] 是流，只在数据变化时重算；试用在进程存活期间到期不会触发任何数据变化，
     * 所以 gate（尤其是每秒都被调用的答题锁）必须用这个函数，而不是读 `status.value`。
     */
    fun statusNow(): LicenseStatus

    /** 设备码（16 位小写 hex），用于换绑与售后。 */
    val deviceId: String

    /**
     * 会话凭证，续签与内容包下载用它证明「我就是这台设备」。
     *
     * 未授权（只有试用）时为空串——试用设备没有 token，服务端按设备码 + 试用状态放行。
     */
    val token: String

    /** 售后邮箱，由服务端下发；未同步到时为空。 */
    val contactEmail: String

    /**
     * 后台静默同步一次。**调用方不要 await 它来决定显示什么。**
     *
     * @return 是否成功（失败不影响任何本地判定）
     */
    suspend fun refresh(): Boolean

    /**
     * 领取 0 元体验包（服务端一条价格 0、若干天的普通套餐）。
     *
     * 走的是**下单**链路而不是原来的「本地先开启、稍后补报」：
     * 体验包发一张和付费完全一样的票据，没有票据就没有权限，
     * 也就不存在「App 说在体验、后台查不到」这种对不上账的状态。
     * 一台设备终身只能领一次，判定在服务端（见 `trialPackUsed`）。
     */
    suspend fun claimTrialPack(): ClaimResult

    suspend fun createOrder(planKey: String): LicenseApi.Result<LicenseApi.PurchaseOrder>

    /**
     * 查一次订单状态。付款成功时会把票据写进本地，[status] 随即变为已授权。
     */
    suspend fun pollOrder(orderNo: String): LicenseApi.Result<LicenseApi.OrderState>
}
