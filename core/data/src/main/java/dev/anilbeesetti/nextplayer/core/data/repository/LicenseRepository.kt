package dev.anilbeesetti.nextplayer.core.data.repository

import dev.anilbeesetti.nextplayer.core.data.license.LicenseApi
import dev.anilbeesetti.nextplayer.core.model.LicenseStatus
import kotlinx.coroutines.flow.StateFlow

/**
 * 授权与试用。
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
     * 开始 7 天试用。
     *
     * 失败时本地仍然按「已开始」处理（乐观更新）：家长点开关必须立刻有反应，
     * 不能让一次超时把开关弹回去——那样第一道转化漏斗就漏了。
     *
     * @return **服务端是否已经记下这次试用**。返回 false 不代表试用没开始（本地已生效），
     *   而是「云端还没这条记录」——调用方据此给家长一句可解释的提示，
     *   下次冷启动或进入授权页时 bootstrap 会带着本地起点自动补报。
     */
    suspend fun startTrial(): Boolean

    suspend fun createOrder(planKey: String): LicenseApi.Result<LicenseApi.PurchaseOrder>

    /**
     * 查一次订单状态。付款成功时会把票据写进本地，[status] 随即变为已授权。
     */
    suspend fun pollOrder(orderNo: String): LicenseApi.Result<LicenseApi.OrderState>
}
