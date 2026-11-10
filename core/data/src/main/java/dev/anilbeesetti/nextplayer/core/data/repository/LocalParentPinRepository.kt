package dev.anilbeesetti.nextplayer.core.data.repository

import dev.anilbeesetti.nextplayer.core.common.di.DiQualifiers
import dev.anilbeesetti.nextplayer.core.data.security.PinHasher
import dev.anilbeesetti.nextplayer.core.datastore.datasource.ParentPinDataSource
import dev.anilbeesetti.nextplayer.core.model.ParentPinData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

/**
 * 家长 PIN 的本地实现。
 *
 * 全部离线：没有服务端校验，也就不存在「客服帮你重置」这条路。
 * 所以恢复码是唯一的兜底——设置时必须让家长抄下来。
 */
@Single
class LocalParentPinRepository(
    private val dataSource: ParentPinDataSource,
    @Named(DiQualifiers.APPLICATION_SCOPE) scope: CoroutineScope,
) : ParentPinRepository {

    companion object {
        /** 连续错几次就锁。5 次是「手滑够用、穷举不够」的分界点。 */
        private const val MAX_ATTEMPTS = 5

        /** 锁多久。5 分钟足够劝退孩子，又不至于把家长自己关在门外太久。 */
        private const val LOCK_DURATION_MS = 5L * 60 * 1000
    }

    @Volatile
    private var latest: ParentPinData = ParentPinData()

    override val state: StateFlow<ParentPinState> = dataSource.preferences
        .map { data ->
            latest = data
            data.toState()
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = ParentPinState(),
        )

    override val stateNow: ParentPinState
        get() = latest.toState()

    override suspend fun awaitState(): ParentPinState {
        val data = dataSource.preferences.first()
        latest = data
        return data.toState()
    }

    private fun ParentPinData.toState() = ParentPinState(
        isSet = isSet,
        lockedUntilMs = lockUntilMs,
        failCount = failCount,
    )

    /**
     * PBKDF2 十万次迭代在低端机上要 100~200ms。
     * 放主线程会掉帧，而且这里本来就允许异步——用户输完 4 位数字，等一下「正在设置」是自然的。
     */
    override suspend fun setup(pin: String): String = withContext(Dispatchers.Default) {
        val salt = PinHasher.newSalt()
        val recoveryCode = PinHasher.newRecoveryCode()
        val recoverySalt = PinHasher.newSalt()

        dataSource.update {
            ParentPinData(
                hash = PinHasher.hash(pin, salt),
                salt = salt,
                recoveryHash = PinHasher.hash(recoveryCode, recoverySalt),
                recoverySalt = recoverySalt,
                failCount = 0,
                lockUntilMs = 0L,
                createdAtMs = System.currentTimeMillis(),
            )
        }
        recoveryCode
    }

    override suspend fun verify(pin: String): PinVerifyResult = withContext(Dispatchers.Default) {
        val data = dataSource.preferences.first()
        latest = data

        if (!data.isSet) return@withContext PinVerifyResult.NotSet

        val now = System.currentTimeMillis()
        if (data.isLockedAt(now)) return@withContext PinVerifyResult.Locked(data.lockUntilMs)

        val candidate = PinHasher.hash(pin, data.salt)
        if (candidate.isNotBlank() && PinHasher.constantTimeEquals(candidate, data.hash)) {
            // 输对就清零，避免「今天错 4 次、明天错 1 次」也能触发锁定
            dataSource.update { it.copy(failCount = 0, lockUntilMs = 0L) }
            return@withContext PinVerifyResult.Ok
        }

        registerFailure(now)
    }

    override suspend fun verifyRecovery(code: String): Boolean = withContext(Dispatchers.Default) {
        val data = dataSource.preferences.first()
        latest = data

        if (!data.isSet || data.recoveryHash.isBlank() || data.recoverySalt.isBlank()) return@withContext false

        val now = System.currentTimeMillis()
        if (data.isLockedAt(now)) return@withContext false

        val candidate = PinHasher.hash(code, data.recoverySalt)
        if (candidate.isNotBlank() && PinHasher.constantTimeEquals(candidate, data.recoveryHash)) {
            dataSource.update { it.copy(failCount = 0, lockUntilMs = 0L) }
            return@withContext true
        }

        registerFailure(now)
        false
    }

    override suspend fun resetPin(newPin: String): String = withContext(Dispatchers.Default) {
        val salt = PinHasher.newSalt()
        val recoveryCode = PinHasher.newRecoveryCode()
        val recoverySalt = PinHasher.newSalt()

        dataSource.update {
            it.copy(
                hash = PinHasher.hash(newPin, salt),
                salt = salt,
                recoveryHash = PinHasher.hash(recoveryCode, recoverySalt),
                recoverySalt = recoverySalt,
                failCount = 0,
                lockUntilMs = 0L,
            )
        }
        recoveryCode
    }

    private suspend fun registerFailure(nowMs: Long): PinVerifyResult {
        val data = dataSource.preferences.first()
        val fails = data.failCount + 1
        val lockUntil = if (fails >= MAX_ATTEMPTS) nowMs + LOCK_DURATION_MS else 0L
        dataSource.update { it.copy(failCount = fails, lockUntilMs = lockUntil) }

        return if (lockUntil > 0L) {
            PinVerifyResult.Locked(lockUntil)
        } else {
            PinVerifyResult.Wrong(attemptsLeft = (MAX_ATTEMPTS - fails).coerceAtLeast(0))
        }
    }
}
