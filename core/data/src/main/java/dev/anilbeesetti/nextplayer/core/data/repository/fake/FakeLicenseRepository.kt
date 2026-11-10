package dev.anilbeesetti.nextplayer.core.data.repository

import dev.anilbeesetti.nextplayer.core.data.license.LicenseApi
import dev.anilbeesetti.nextplayer.core.model.LicenseStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 测试用授权仓库。
 *
 * 默认给「已解锁」：绝大多数 UI 测试关心的不是付费墙本身，
 * 如果默认未解锁，每个测试都得先补一步开授权，噪音比价值大。
 * 需要验证付费墙的用例显式传 `pro = false`。
 */
class FakeLicenseRepository(
    pro: Boolean = true,
    initialDeviceId: String = "0123456789abcdef",
) : LicenseRepository {

    private val _status = MutableStateFlow(
        LicenseStatus(
            deviceId = initialDeviceId,
            licensed = pro,
            permanent = pro,
            pro = pro,
        ),
    )

    override val status: StateFlow<LicenseStatus> = _status.asStateFlow()

    override val deviceId: String = initialDeviceId

    override val contactEmail: String = "test@example.com"

    override fun statusNow(): LicenseStatus = _status.value

    override suspend fun refresh(): Boolean = true

    override suspend fun startTrial(): Boolean {
        if (_status.value.licensed) return true
        _status.value = _status.value.copy(
            trialStarted = true,
            trialActive = true,
            trialDaysLeft = 7,
            pro = true,
        )
        return true
    }

    override suspend fun createOrder(planKey: String): LicenseApi.Result<LicenseApi.PurchaseOrder> =
        LicenseApi.Result.Ok(
            LicenseApi.PurchaseOrder(
                orderNo = "SN20260101000000TEST",
                amountCents = 2900,
                method = "qr",
                qrContent = "https://qr.alipay.com/test",
                expireIn = 900,
            ),
        )

    override suspend fun pollOrder(orderNo: String): LicenseApi.Result<LicenseApi.OrderState> =
        LicenseApi.Result.Ok(
            LicenseApi.OrderState(
                orderNo = orderNo,
                status = "pending",
            ),
        )
}
