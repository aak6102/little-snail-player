package dev.anilbeesetti.nextplayer.core.data.license

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 票据验签的端到端校验（真实签名，不是 mock）。
 *
 * 下面这张票是用**生产私钥**签的，公钥常量与服务端 `ticket.ts` 必须对得上。
 * 这个测试一旦变红，说明线上会出现「付了钱但永远不解锁」——
 * 是整套授权里最不能出错的一环，所以用固定夹具而不只测边界。
 */
@RunWith(RobolectricTestRunner::class)
class TicketVerifierTest {

    private val realTicket =
        "eyJ2IjoxLCJkaWQiOiJhMWIyYzNkNGU1ZjYwNzE4IiwicGxhbiI6InNvbG8iLCJwZXJtIjoxLCJleHAiOjAsImlhdCI6MTc5Mzg1MTA5MSwidnUiOjE3OTY0NDMwOTEsInNlcSI6MX0." +
            "MUmjJt_MP5kptbO5jbhCLNr65Bds4lrj2pbAUWUawBu2WfVWOhGvGxDy9WgtFM_aNhmNiVGi5kz3O7ZmUEwkcKtsC_3JnaxccV82ABO9-jV3qaPQrsYPpVKA91M3qslmd38VpadVNc4zhu1wWeEZMiE5qfBPnD5eMBCvQdQGZiB4vEpCzaD1MS6of7tbfSMa0E0DZrVwHEf4Sg0zKwfu-0CfiwtjVXIMcFvrO88ZSXhkI1-lcEE3-4ZGmeiF7Tw17cWWveN05cuCcMC66soBpDbazOvc2qgm5I_B8US5Oayhp4NCiB_uCFph5sL9drRlu-tpjUaQbYg2oRrfg5dyLw"

    @Test
    fun `生产私钥签出的票能验证通过`() {
        val payload = TicketVerifier.verify(realTicket)

        assertNotNull(payload)
        assertEquals(1, payload!!.v)
        assertEquals("a1b2c3d4e5f60718", payload.did)
        assertEquals("solo", payload.plan)
        assertEquals(1, payload.perm)
        assertEquals(1L, payload.seq)
    }

    @Test
    fun `改动载荷内容会导致验签失败`() {
        // 把 perm 从 1 改成 0（或反过来把年费改成永久）是最有动机的一次攻击
        val payloadPart = realTicket.substringBefore('.')
        val signature = realTicket.substringAfter('.')
        val tampered = payloadPart.replace('1', '2') + "." + signature

        assertNull(TicketVerifier.verify(tampered))
    }

    @Test
    fun `改动签名会导致验签失败`() {
        val payloadPart = realTicket.substringBefore('.')
        val signature = realTicket.substringAfter('.')
        val tampered = payloadPart + "." + signature.dropLast(4) + "AAAA"

        assertNull(TicketVerifier.verify(tampered))
    }

    @Test
    fun `畸形输入一律返回空`() {
        assertNull(TicketVerifier.verify(null))
        assertNull(TicketVerifier.verify(""))
        assertNull(TicketVerifier.verify("没有点号的字符串"))
        assertNull(TicketVerifier.verify("abc."))
        assertNull(TicketVerifier.verify(".abc"))
        assertNull(TicketVerifier.verify("!!!.???"))
    }
}
