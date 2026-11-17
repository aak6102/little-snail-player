package dev.anilbeesetti.nextplayer.core.datastore.datasource

import androidx.datastore.core.DataStore
import dev.anilbeesetti.nextplayer.core.common.Logger
import dev.anilbeesetti.nextplayer.core.common.di.DiQualifiers
import dev.anilbeesetti.nextplayer.core.model.DailyQuotaProgress
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Named

@Factory(binds = [])
class DailyQuotaDataSource(
    @Named(DiQualifiers.DAILY_QUOTA) private val dailyQuotaDataStore: DataStore<DailyQuotaProgress>,
) : PreferencesDataSource<DailyQuotaProgress> {

    companion object {
        private const val TAG = "DailyQuotaDataSource"
    }

    override val preferences = dailyQuotaDataStore.data

    override suspend fun update(transform: suspend (DailyQuotaProgress) -> DailyQuotaProgress) {
        try {
            dailyQuotaDataStore.updateData(transform)
        } catch (ioException: Exception) {
            Logger.logError(TAG, "Failed to update daily quota progress: $ioException")
        }
    }
}
