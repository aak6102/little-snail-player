package dev.anilbeesetti.nextplayer.core.datastore.datasource

import androidx.datastore.core.DataStore
import dev.anilbeesetti.nextplayer.core.common.Logger
import dev.anilbeesetti.nextplayer.core.common.di.DiQualifiers
import dev.anilbeesetti.nextplayer.core.model.StudyProgress
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Named

@Factory(binds = [])
class StudyProgressDataSource(
    @Named(DiQualifiers.STUDY_PROGRESS) private val studyProgressDataStore: DataStore<StudyProgress>,
) : PreferencesDataSource<StudyProgress> {

    companion object {
        private const val TAG = "StudyProgressDataSource"
    }

    override val preferences = studyProgressDataStore.data

    override suspend fun update(transform: suspend (StudyProgress) -> StudyProgress) {
        try {
            studyProgressDataStore.updateData(transform)
        } catch (ioException: Exception) {
            Logger.logError(TAG, "Failed to update study progress: $ioException")
        }
    }
}
