package dev.anilbeesetti.nextplayer.core.datastore.datasource

import androidx.datastore.core.DataStore
import dev.anilbeesetti.nextplayer.core.common.Logger
import dev.anilbeesetti.nextplayer.core.common.di.DiQualifiers
import dev.anilbeesetti.nextplayer.core.model.LicenseData
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Named

@Factory(binds = [])
class LicenseDataSource(
    @Named(DiQualifiers.LICENSE) private val licenseDataStore: DataStore<LicenseData>,
) : PreferencesDataSource<LicenseData> {

    companion object {
        private const val TAG = "LicenseDataSource"
    }

    override val preferences = licenseDataStore.data

    override suspend fun update(transform: suspend (LicenseData) -> LicenseData) {
        try {
            licenseDataStore.updateData(transform)
        } catch (exception: Exception) {
            Logger.logError(TAG, "Failed to update license: $exception")
        }
    }
}
