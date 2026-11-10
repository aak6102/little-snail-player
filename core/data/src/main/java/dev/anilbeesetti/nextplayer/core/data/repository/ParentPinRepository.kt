package dev.anilbeesetti.nextplayer.core.data.repository

import kotlinx.coroutines.flow.StateFlow

/** PIN 门禁的当前状态。 */
data class ParentPinState(
    /** 是否已经设置过 PIN。false 表示要走创建流程。 */
    val isSet: Boolean = false,
    /** 因连续输错而被锁定的截止时刻。0 表示没锁。 */
    val lockedUntilMs: Long = 0L,
    /** 已连续输错次数。 */
    val failCount: Int = 0,
) {
    fun isLockedAt(nowMs: Long): Boolean = lockedUntilMs > nowMs
}

/** 验证结果。 */
sealed interface PinVerifyResult {
    /** 通过。 */
    data object Ok : PinVerifyResult

    /** 错了，还剩 [attemptsLeft] 次机会，用完就锁。 */
    data class Wrong(val attemptsLeft: Int) : PinVerifyResult

    /** 正在锁定中，[untilMs] 之后才能再试。 */
    data class Locked(val untilMs: Long) : PinVerifyResult

    /** 还没设置过 PIN，调用方应该走创建流程。 */
    data object NotSet : PinVerifyResult
}

/**
 * 家长 PIN。
 *
 * 替换掉原来「关于页连点 7 次进入 + 关闭延迟 24 小时生效」那一套：
 * 连点拦住的是不知道入口的人，一旦知道就等于没锁；延迟生效连家长自己也一起挡了。
 */
interface ParentPinRepository {

    val state: StateFlow<ParentPinState>

    val stateNow: ParentPinState

    /**
     * 等第一次真正从磁盘读出来的值。
     *
     * [state] 的初始值是 `ParentPinState()`（isSet = false），而 DataStore 的读取是异步的。
     * 门禁页如果一进来就用 [stateNow] 判断，会在一瞬间把「已经设过 PIN」误判成「没设过」，
     * 直接把用户放进创建流程 —— 他输两遍新 PIN，旧 PIN 就被静默覆盖了。
     */
    suspend fun awaitState(): ParentPinState

    /**
     * 首次设置 PIN，返回**明文恢复码**。
     *
     * 恢复码只在这一刻存在：库里存的是它的哈希，之后谁都没法再取出来
     * （包括我们自己）。所以调用方必须把它展示给用户并让他确认已抄下。
     */
    suspend fun setup(pin: String): String

    suspend fun verify(pin: String): PinVerifyResult

    /** 用恢复码验证身份。通过后可以调用 [resetPin] 重设。 */
    suspend fun verifyRecovery(code: String): Boolean

    /**
     * 重设 PIN（需先通过 [verifyRecovery]），返回**新的**恢复码。
     *
     * 换 PIN 必须连带换恢复码：旧恢复码如果还有效，等于把旧钥匙留在门上。
     */
    suspend fun resetPin(newPin: String): String
}
