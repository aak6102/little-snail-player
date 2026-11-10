package dev.anilbeesetti.nextplayer.core.data.license

import android.util.Base64
import dev.anilbeesetti.nextplayer.core.model.TicketPayload
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlinx.serialization.json.Json

/**
 * 离线票据验签。
 *
 * 这是整个授权体系里**唯一真正防篡改**的环节：到期时间、是否永久都从签名载荷里解出来，
 * 用户改 `license.json` 的任何字段都不会让验签通过。
 *
 * 签名算法与 Workers 侧 `ticket.ts` 必须完全一致：
 *   SHA256withRSA（RSASSA-PKCS1-v1_5）
 *   签名对象 = 票据第一段字符串本身的 ASCII 字节，而不是解码后的 JSON 原文。
 * 后者很容易写成「重新序列化 JSON 再验」，那样一旦字段顺序变化就全盘失败。
 */
object TicketVerifier {

    /**
     * 票据验签公钥（SPKI / DER / base64）。
     *
     * 只能换不能改：换公钥意味着所有已发出的票据作废，用户必须联网重新取票。
     * 私钥在 Worker Secrets 里（`TICKET_PRIVATE_KEY`），**永远不进 App**。
     */
    private const val PUBLIC_KEY_DER_B64 =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAmUfZU7e2MAUpDnMmUpOX16vJ/fI+EBWsJJvzYvrLUPUBB3zD1jMl08DFXmfn0XMRAy8X4O7slyGKeN+BYfDq63qWcesJGjarx+MgYgxeDmkXxtXaErnTiFM2i6c7/cVHTGV9mZd42L3pUtoJAx456F6Pxqa3zMPyXguPb4ioPurMYp6n1lQX5P7GY0JcbV1OWUge6Bn3RXqpI2eOYCLy5JZ1Fb5mHgCqHMWJP/ctAorEDhs+TseW1BrW848SeWfYmkEPXNgmvpluuC9F4wsFO7iXrWoLw1YzH/TLGOBX6Pp38iHgBZGZVcDa/COR1cZ0dmNKj7GNvh5wWMbgFSc0fwIDAQAB"

    private const val TICKET_VERSION = 1

    private val jsonFormat = Json { ignoreUnknownKeys = true }

    private val publicKey: PublicKey? by lazy {
        runCatching {
            val der = Base64.decode(PUBLIC_KEY_DER_B64, Base64.NO_WRAP)
            KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
        }.getOrNull()
    }

    /**
     * 验签并解出载荷。任何异常都返回 null——**验不了就当没有授权**，
     * 这条规则不能为了「体验好一点」放宽，否则一条畸形票据就能白嫖。
     */
    fun verify(ticket: String?): TicketPayload? {
        if (ticket.isNullOrBlank()) return null
        val key = publicKey ?: return null

        val separator = ticket.indexOf('.')
        if (separator <= 0 || separator == ticket.lastIndex) return null

        val body = ticket.substring(0, separator)
        val signature = decodeUrlSafe(ticket.substring(separator + 1)) ?: return null

        val signedOk = runCatching {
            Signature.getInstance("SHA256withRSA").run {
                initVerify(key)
                update(body.toByteArray(Charsets.US_ASCII))
                verify(signature)
            }
        }.getOrDefault(false)
        if (!signedOk) return null

        val json = decodeUrlSafe(body)?.toString(Charsets.UTF_8) ?: return null
        val payload = runCatching {
            jsonFormat.decodeFromString(TicketPayload.serializer(), json)
        }.getOrNull() ?: return null

        // 版本号必须显式校验：将来字段语义变了（比如 exp 单位改毫秒），
        // 老客户端认不出 v=2 的票，宁可当没有授权也不能按老规则误判成永久。
        return payload.takeIf { it.v == TICKET_VERSION }
    }

    private fun decodeUrlSafe(value: String): ByteArray? = runCatching {
        Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }.getOrNull()
}
