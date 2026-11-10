package dev.anilbeesetti.nextplayer.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 授权判定的规则测试。
 *
 * 这些用例钉住的是「怎么算都不该多给权益」这条底线：
 * 票据绑错设备、票据过期、试用到期、时钟被往前调，都必须收敛到 `pro = false`
 * 或者「按已到期处理」。改动 [computeStatus] 时先看这里是否还绿。
 */
class LicenseStateTest {

    private val dayMs = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L
    private val deviceId = "a1b2c3d4e5f60718"

    private fun payload(
        did: String = deviceId,
        perm: Int = 0,
        exp: Long = 0L,
        vu: Long = 0L,
        v: Int = 1,
    ) = TicketPayload(
        v = v,
        did = did,
        plan = "solo",
        perm = perm,
        exp = exp,
        iat = now / 1000 - 3600,
        vu = if (vu == 0L) now / 1000 + 30 * 86_400 else vu,
        seq = 1,
    )

    @Test
    fun `没有票据也没有试用时不可用`() {
        val status = LicenseData(deviceId = deviceId).computeStatus(now, null)

        assertFalse(status.pro)
        assertFalse(status.licensed)
        assertFalse(status.trialStarted)
    }

    @Test
    fun `试用中可用并显示剩余天数`() {
        val data = LicenseData(
            deviceId = deviceId,
            trialStartedAtMs = now - dayMs,
            trialExpireAtMs = now + 6 * dayMs,
        )

        val status = data.computeStatus(now, null)

        assertTrue(status.pro)
        assertTrue(status.trialActive)
        assertEquals(6, status.trialDaysLeft)
    }

    @Test
    fun `试用到期后只剩播放器功能`() {
        val data = LicenseData(
            deviceId = deviceId,
            trialStartedAtMs = now - 8 * dayMs,
            trialExpireAtMs = now - dayMs,
        )

        val status = data.computeStatus(now, null)

        assertFalse(status.pro)
        assertTrue(status.trialStarted)
        assertFalse(status.trialActive)
    }

    @Test
    fun `永久票据永远可用`() {
        val status = LicenseData(deviceId = deviceId).computeStatus(now, payload(perm = 1))

        assertTrue(status.pro)
        assertTrue(status.permanent)
        assertEquals(0L, status.expireAtMs)
    }

    @Test
    fun `设备码不匹配的票据无效`() {
        // 换绑之后旧手机上残留的那张票就长这样：签名合法，但 did 不是自己
        val status = LicenseData(deviceId = deviceId)
            .computeStatus(now, payload(did = "ffffffffffffffff", perm = 1))

        assertFalse(status.pro)
        assertFalse(status.licensed)
    }

    @Test
    fun `年费票据到期即失效`() {
        val expired = payload(exp = now / 1000 - 60)
        val valid = payload(exp = now / 1000 + 60)

        assertFalse(LicenseData(deviceId = deviceId).computeStatus(now, expired).pro)
        assertTrue(LicenseData(deviceId = deviceId).computeStatus(now, valid).pro)
    }

    @Test
    fun `票据自身过期不影响授权本身`() {
        // vu 过期只代表「不能离线判定」，联网续签即可恢复；授权本身到 exp 才算完
        val stale = payload(exp = now / 1000 + 300 * 86_400, vu = now / 1000 - 10)

        val status = LicenseData(deviceId = deviceId).computeStatus(now, stale)

        assertTrue(status.pro)
        assertTrue(status.needsRefresh)
    }

    @Test
    fun `把系统时间往前调不会让试用复活`() {
        val data = LicenseData(
            deviceId = deviceId,
            trialStartedAtMs = now - 10 * dayMs,
            trialExpireAtMs = now - 3 * dayMs,
            lastSyncAtMs = now,
        )

        // 用户手动把时间调到试用期内，但 lastSyncAtMs 记录了真实时刻
        val status = data.computeStatus(now - 5 * dayMs, null)

        assertFalse(status.trialActive)
        assertFalse(status.pro)
    }

    @Test
    fun `未来版本票据一律不认`() {
        val status = LicenseData(deviceId = deviceId).computeStatus(now, payload(perm = 1, v = 2))

        assertFalse(status.pro)
    }
}
