package dev.anilbeesetti.nextplayer.core.data.repository

import dev.anilbeesetti.nextplayer.core.common.di.DiQualifiers
import dev.anilbeesetti.nextplayer.core.datastore.datasource.DailyQuotaDataSource
import dev.anilbeesetti.nextplayer.core.model.DailyQuotaPreferences
import dev.anilbeesetti.nextplayer.core.model.DailyQuotaProgress
import dev.anilbeesetti.nextplayer.core.model.dayKey
import dev.anilbeesetti.nextplayer.core.model.grantEligibleRules
import dev.anilbeesetti.nextplayer.core.model.rollToDay
import dev.anilbeesetti.nextplayer.core.model.withGrants
import dev.anilbeesetti.nextplayer.core.model.withWatched
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

@Single
class LocalDailyQuotaRepository(
    private val dailyQuotaDataSource: DailyQuotaDataSource,
    @Named(DiQualifiers.APPLICATION_SCOPE) scope: CoroutineScope,
) : DailyQuotaRepository {

    override val progress: StateFlow<DailyQuotaProgress> =
        dailyQuotaDataSource.preferences.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = DailyQuotaProgress(),
        )

    override suspend fun updatePreferences(transform: (DailyQuotaPreferences) -> DailyQuotaPreferences) {
        dailyQuotaDataSource.update { current ->
            current.copy(preferences = transform(current.preferences))
        }
    }

    override suspend fun saveWatched(watchedMs: Long, nowMs: Long) {
        val today = dayKey(nowMs)
        dailyQuotaDataSource.update { current ->
            // 先换天再写：跨零点的那次写入要落到新的一天，不能把昨天的累计带过来。
            val rolled = current.rollToDay(today)
            if (rolled.watchedMs == watchedMs) rolled else rolled.copy(watchedMs = watchedMs)
        }
    }

    override suspend fun settle(
        usageMsByPackage: Map<String, Long>,
        nowMs: Long,
    ): Map<String, Int> {
        var grantedNow: Map<String, Int> = emptyMap()
        val today = dayKey(nowMs)
        dailyQuotaDataSource.update { current ->
            // 结算与换天放在同一次 update 里，避免「换天清了 granted」与「读取旧 granted」交错
            // 导致同一份奖励发两次。
            val rolled = current.rollToDay(today)
            // 是否该发，只认磁盘上的这份配置。调用方（QuotaGuard）在冷启动瞬间拿到的
            // 内存快照还是默认值，用它判断会把整次结算静默跳过。
            if (!rolled.preferences.enabled || rolled.preferences.activeRules.isEmpty()) {
                if (rolled.lastSettledAtMs == nowMs) rolled else rolled.copy(lastSettledAtMs = nowMs)
            } else {
                val fresh = grantEligibleRules(
                    preferences = rolled.preferences,
                    usageMsByPackage = usageMsByPackage,
                    alreadyGranted = rolled.granted,
                )
                grantedNow = fresh
                rolled.withGrants(fresh, nowMs).copy(lastSettledAtMs = nowMs)
            }
        }
        return grantedNow
    }

    override suspend fun markNotice(noticeBucket: Int, lowBalanceNotified: Set<Int>) {
        dailyQuotaDataSource.update { current ->
            if (current.noticeBucket == noticeBucket &&
                current.lowBalanceNotified == lowBalanceNotified
            ) {
                current
            } else {
                current.copy(
                    noticeBucket = noticeBucket,
                    lowBalanceNotified = lowBalanceNotified,
                )
            }
        }
    }
}
