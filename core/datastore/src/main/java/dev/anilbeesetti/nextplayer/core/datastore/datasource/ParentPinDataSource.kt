package dev.anilbeesetti.nextplayer.core.datastore.datasource

import androidx.datastore.core.DataStore
import dev.anilbeesetti.nextplayer.core.common.Logger
import dev.anilbeesetti.nextplayer.core.common.di.DiQualifiers
import dev.anilbeesetti.nextplayer.core.model.ParentPinData
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Named

@Factory(binds = [])
class ParentPinDataSource(
    @Named(DiQualifiers.PARENT_PIN) private val parentPinDataStore: DataStore<ParentPinData>,
) : PreferencesDataSource<ParentPinData> {

    companion object {
        private const val TAG = "ParentPinDataSource"
    }

    override val preferences = parentPinDataStore.data

    override suspend fun update(transform: suspend (ParentPinData) -> ParentPinData) {
        try {
            parentPinDataStore.updateData(transform)
        } catch (exception: Exception) {
            Logger.logError(TAG, "Failed to update parent pin: $exception")
        }
    }
}
