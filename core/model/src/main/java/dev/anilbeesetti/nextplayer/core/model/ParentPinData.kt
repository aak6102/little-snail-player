package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.Serializable

/**
 * 家长 PIN 的凭据。
 *
 * 只存哈希，不存明文：`parent_pin.json` 落在应用私有目录里，普通用户读不到，
 * 但 root 过的设备能直接拷走。4 位 PIN 只有一万种组合，明文基本等于没有防护。
 *
 * 恢复码同理 —— 它是一次性展示给家长抄走的备用钥匙，服务端也不留副本。
 */
@Serializable
data class ParentPinData(
    /** PBKDF2 派生出的 PIN 哈希（Base64）。空串表示还没设置过 PIN。 */
    val hash: String = "",
    /** PIN 哈希用的随机盐（Base64）。 */
    val salt: String = "",
    /** 恢复码哈希（Base64）。 */
    val recoveryHash: String = "",
    /** 恢复码哈希用的盐（Base64）。与 PIN 的盐分开，避免撞彩虹表。 */
    val recoverySalt: String = "",
    /** 连续输错次数。输对一次清零。 */
    val failCount: Int = 0,
    /** 锁定截止时刻。连续输错达到上限后写入，到点自动解锁。 */
    val lockUntilMs: Long = 0L,
    /** 首次设置时间，仅用于客服判断「这是不是刚被孩子乱设过」。 */
    val createdAtMs: Long = 0L,
) {
    /** 是否已经设过 PIN。没设过要先走创建流程。 */
    val isSet: Boolean get() = hash.isNotBlank() && salt.isNotBlank()

    /** 此刻是否处于输错锁定中。 */
    fun isLockedAt(nowMs: Long): Boolean = lockUntilMs > nowMs
}
