package dev.anilbeesetti.nextplayer.core.datastore.datasource

import androidx.datastore.core.DataStore
import dev.anilbeesetti.nextplayer.core.common.Logger
import dev.anilbeesetti.nextplayer.core.common.di.DiQualifiers
import dev.anilbeesetti.nextplayer.core.model.RestProgress
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Named

@Factory(binds = [])
class RestProgressDataSource(
    @Named(DiQualifiers.REST_PROGRESS) private val restProgressDataStore: DataStore<RestProgress>,
) : PreferencesDataSource<RestProgress> {

    companion object {
        private const val TAG = "RestProgressDataSource"
    }

    override val preferences = restProgressDataStore.data

    override suspend fun update(transform: suspend (RestProgress) -> RestProgress) {
        try {
            restProgressDataStore.updateData(transform)
        } catch (ioException: Exception) {
            Logger.logError(TAG, "Failed to update rest progress: $ioException")
        }
    }
}
