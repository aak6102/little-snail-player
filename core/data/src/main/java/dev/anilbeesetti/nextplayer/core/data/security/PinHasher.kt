package dev.anilbeesetti.nextplayer.core.data.security

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 家长 PIN / 恢复码的哈希工具。
 *
 * 用 PBKDF2WithHmacSHA256 还是 SHA1？
 * **SHA1** —— 不是因为它更好，而是因为 minSdk 24：`PBKDF2WithHmacSHA256`
 * 在 Android 8.0（API 26）才进 Conscrypt，在 7.0/7.1 上会直接抛
 * `NoSuchAlgorithmException`。为了让老机型也能设 PIN，统一用 SHA1 版；
 * 真正的强度来自 10 万次迭代，而不是摘要算法本身。
 *
 * 这个哈希防的是「拿到 parent_pin.json 之后离线爆破」：
 * 4 位 PIN 只有一万种组合，不做迭代哈希的话一秒就能穷举完。
 */
object PinHasher {

    private const val ALGORITHM = "PBKDF2WithHmacSHA1"
    private const val ITERATIONS = 100_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16

    private val secureRandom = SecureRandom()

    fun newSalt(): String {
        val bytes = ByteArray(SALT_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun hash(secret: String, saltB64: String): String {
        val salt = runCatching { Base64.decode(saltB64, Base64.NO_WRAP) }.getOrNull()
            ?: return ""
        if (salt.isEmpty()) return ""
        return runCatching {
            val spec = PBEKeySpec(secret.toCharArray(), salt, ITERATIONS, KEY_BITS)
            val key = SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
            Base64.encodeToString(key, Base64.NO_WRAP)
        }.getOrDefault("")
    }

    /**
     * 定长比较。
     *
     * 用普通的 `==` 比较哈希，理论上可以被时序攻击逐位猜出来 —— 在这个场景下
     * 攻击者得先能读进程内存，属于理论上成立、实际上用不上的威胁。
     * 但这段代码只有五行，没有理由留一个「知道的人会皱眉」的写法。
     */
    fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (index in a.indices) diff = diff or (a[index].code xor b[index].code)
        return diff == 0
    }

    /**
     * 8 位数字恢复码。
     *
     * 之所以不是字母数字混合：家长要把它抄在纸上或者存进备忘录，
     * 纯数字不用切换输入法，抄错的概率低一个数量级。
     * 8 位 = 一亿种组合，配合 5 次错误锁定，在线爆破没有意义。
     */
    fun newRecoveryCode(): String = buildString {
        repeat(8) { append(secureRandom.nextInt(10)) }
    }
}
