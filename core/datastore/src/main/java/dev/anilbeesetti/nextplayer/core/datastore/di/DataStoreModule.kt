package dev.anilbeesetti.nextplayer.core.datastore.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import dev.anilbeesetti.nextplayer.core.common.di.DiQualifiers
import dev.anilbeesetti.nextplayer.core.common.di.DispatchersModule
import dev.anilbeesetti.nextplayer.core.datastore.serializer.ApplicationPreferencesSerializer
import dev.anilbeesetti.nextplayer.core.datastore.serializer.LicenseSerializer
import dev.anilbeesetti.nextplayer.core.datastore.serializer.ParentPinSerializer
import dev.anilbeesetti.nextplayer.core.datastore.serializer.PlayerPreferencesSerializer
import dev.anilbeesetti.nextplayer.core.datastore.serializer.SearchHistorySerializer
import dev.anilbeesetti.nextplayer.core.datastore.serializer.StudyProgressSerializer
import dev.anilbeesetti.nextplayer.core.datastore.serializer.RestProgressSerializer
import dev.anilbeesetti.nextplayer.core.model.ApplicationPreferences
import dev.anilbeesetti.nextplayer.core.model.LicenseData
import dev.anilbeesetti.nextplayer.core.model.ParentPinData
import dev.anilbeesetti.nextplayer.core.model.PlayerPreferences
import dev.anilbeesetti.nextplayer.core.model.SearchHistory
import dev.anilbeesetti.nextplayer.core.model.StudyProgress
import dev.anilbeesetti.nextplayer.core.model.RestProgress
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

private const val APP_PREFERENCES_DATASTORE_FILE = "app_preferences.json"
private const val PLAYER_PREFERENCES_DATASTORE_FILE = "player_preferences.json"
private const val SEARCH_HISTORY_DATASTORE_FILE = "search_history.json"
private const val STUDY_PROGRESS_DATASTORE_FILE = "study.json"
private const val REST_PROGRESS_DATASTORE_FILE = "rest.json"
private const val LICENSE_DATASTORE_FILE = "license.json"
private const val PARENT_PIN_DATASTORE_FILE = "parent_pin.json"

@Module(includes = [DispatchersModule::class])
@ComponentScan("dev.anilbeesetti.nextplayer.core.datastore")
class DataStoreModule {

    @Single
    @Named(DiQualifiers.APP_PREFERENCES)
    fun provideAppPreferencesDataStore(
        context: Context,
        @Named(DiQualifiers.IO_DISPATCHER) ioDispatcher: CoroutineDispatcher,
        @Named(DiQualifiers.APPLICATION_SCOPE) scope: CoroutineScope,
    ): DataStore<ApplicationPreferences> = DataStoreFactory.create(
        serializer = ApplicationPreferencesSerializer,
        scope = CoroutineScope(scope.coroutineContext + ioDispatcher),
        produceFile = { context.dataStoreFile(APP_PREFERENCES_DATASTORE_FILE) },
    )

    @Single
    @Named(DiQualifiers.PLAYER_PREFERENCES)
    fun providePlayerPreferencesDataStore(
        applicationContext: Context,
        @Named(DiQualifiers.IO_DISPATCHER) ioDispatcher: CoroutineDispatcher,
        @Named(DiQualifiers.APPLICATION_SCOPE) scope: CoroutineScope,
    ): DataStore<PlayerPreferences> = DataStoreFactory.create(
        serializer = PlayerPreferencesSerializer,
        scope = CoroutineScope(scope.coroutineContext + ioDispatcher),
        produceFile = { applicationContext.dataStoreFile(PLAYER_PREFERENCES_DATASTORE_FILE) },
    )

    @Single
    @Named(DiQualifiers.SEARCH_HISTORY)
    fun provideSearchHistoryDataStore(
        applicationContext: Context,
        @Named(DiQualifiers.IO_DISPATCHER) ioDispatcher: CoroutineDispatcher,
        @Named(DiQualifiers.APPLICATION_SCOPE) scope: CoroutineScope,
    ): DataStore<SearchHistory> = DataStoreFactory.create(
        serializer = SearchHistorySerializer,
        scope = CoroutineScope(scope.coroutineContext + ioDispatcher),
        produceFile = { applicationContext.dataStoreFile(SEARCH_HISTORY_DATASTORE_FILE) },
    )

    @Single
    @Named(DiQualifiers.STUDY_PROGRESS)
    fun provideStudyProgressDataStore(
        applicationContext: Context,
        @Named(DiQualifiers.IO_DISPATCHER) ioDispatcher: CoroutineDispatcher,
        @Named(DiQualifiers.APPLICATION_SCOPE) scope: CoroutineScope,
    ): DataStore<StudyProgress> = DataStoreFactory.create(
        serializer = StudyProgressSerializer,
        scope = CoroutineScope(scope.coroutineContext + ioDispatcher),
        produceFile = { applicationContext.dataStoreFile(STUDY_PROGRESS_DATASTORE_FILE) },
    )

    @Single
    @Named(DiQualifiers.REST_PROGRESS)
    fun provideRestProgressDataStore(
        applicationContext: Context,
        @Named(DiQualifiers.IO_DISPATCHER) ioDispatcher: CoroutineDispatcher,
        @Named(DiQualifiers.APPLICATION_SCOPE) scope: CoroutineScope,
    ): DataStore<RestProgress> = DataStoreFactory.create(
        serializer = RestProgressSerializer,
        scope = CoroutineScope(scope.coroutineContext + ioDispatcher),
        produceFile = { applicationContext.dataStoreFile(REST_PROGRESS_DATASTORE_FILE) },
    )

    @Single
    @Named(DiQualifiers.LICENSE)
    fun provideLicenseDataStore(
        applicationContext: Context,
        @Named(DiQualifiers.IO_DISPATCHER) ioDispatcher: CoroutineDispatcher,
        @Named(DiQualifiers.APPLICATION_SCOPE) scope: CoroutineScope,
    ): DataStore<LicenseData> = DataStoreFactory.create(
        serializer = LicenseSerializer,
        scope = CoroutineScope(scope.coroutineContext + ioDispatcher),
        produceFile = { applicationContext.dataStoreFile(LICENSE_DATASTORE_FILE) },
    )

    @Single
    @Named(DiQualifiers.PARENT_PIN)
    fun provideParentPinDataStore(
        applicationContext: Context,
        @Named(DiQualifiers.IO_DISPATCHER) ioDispatcher: CoroutineDispatcher,
        @Named(DiQualifiers.APPLICATION_SCOPE) scope: CoroutineScope,
    ): DataStore<ParentPinData> = DataStoreFactory.create(
        serializer = ParentPinSerializer,
        scope = CoroutineScope(scope.coroutineContext + ioDispatcher),
        produceFile = { applicationContext.dataStoreFile(PARENT_PIN_DATASTORE_FILE) },
    )
}
