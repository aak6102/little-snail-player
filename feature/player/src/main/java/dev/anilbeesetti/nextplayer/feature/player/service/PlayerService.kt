package dev.anilbeesetti.nextplayer.feature.player.service

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.DISCONTINUITY_REASON_AUTO_TRANSITION
import androidx.media3.common.Player.DISCONTINUITY_REASON_REMOVE
import androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.CommandButton
import androidx.media3.session.CommandButton.ICON_UNDEFINED
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import coil3.ImageLoader
import coil3.request.ImageRequest
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.anilbeesetti.nextplayer.core.common.Logger
import dev.anilbeesetti.nextplayer.core.common.extensions.deleteFiles
import dev.anilbeesetti.nextplayer.core.common.extensions.getFilenameFromUri
import dev.anilbeesetti.nextplayer.core.common.extensions.getLocalSubtitles
import dev.anilbeesetti.nextplayer.core.common.extensions.getPath
import dev.anilbeesetti.nextplayer.core.common.extensions.subtitleCacheDir
import dev.anilbeesetti.nextplayer.core.data.repository.LicenseRepository
import dev.anilbeesetti.nextplayer.core.data.repository.MediaRepository
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.data.repository.StudyRepository
import dev.anilbeesetti.nextplayer.core.media.network.NetworkUri
import dev.anilbeesetti.nextplayer.core.media.network.datasource.NextDataSourceFactory
import dev.anilbeesetti.nextplayer.core.model.LoopMode
import dev.anilbeesetti.nextplayer.core.model.PlayerPreferences
import dev.anilbeesetti.nextplayer.core.model.Resume
import dev.anilbeesetti.nextplayer.core.model.effectiveSeekIntervalMillis
import dev.anilbeesetti.nextplayer.core.ui.R as coreUiR
import dev.anilbeesetti.nextplayer.feature.player.PlayerActivity
import dev.anilbeesetti.nextplayer.feature.player.R
import dev.anilbeesetti.nextplayer.feature.player.extensions.addAdditionalSubtitleConfiguration
import dev.anilbeesetti.nextplayer.feature.player.extensions.audioDecoderMode
import dev.anilbeesetti.nextplayer.feature.player.extensions.audioTrackIndex
import dev.anilbeesetti.nextplayer.feature.player.extensions.copy
import dev.anilbeesetti.nextplayer.feature.player.extensions.externalAudio
import dev.anilbeesetti.nextplayer.feature.player.extensions.externalAudioIndex
import dev.anilbeesetti.nextplayer.feature.player.extensions.getManuallySelectedTrackIndex
import dev.anilbeesetti.nextplayer.feature.player.extensions.positionMs
import dev.anilbeesetti.nextplayer.feature.player.extensions.setExtras
import dev.anilbeesetti.nextplayer.feature.player.extensions.setIsScrubbingModeEnabled
import dev.anilbeesetti.nextplayer.feature.player.extensions.subtitleDelayMilliseconds
import dev.anilbeesetti.nextplayer.feature.player.extensions.subtitleSpeed
import dev.anilbeesetti.nextplayer.feature.player.extensions.subtitleTrackIndex
import dev.anilbeesetti.nextplayer.feature.player.extensions.switchTrack
import dev.anilbeesetti.nextplayer.feature.player.extensions.uriToSubtitleConfiguration
import dev.anilbeesetti.nextplayer.feature.player.extensions.videoDecoderMode
import dev.anilbeesetti.nextplayer.feature.player.extensions.videoZoom
import dev.anilbeesetti.nextplayer.feature.player.extensions.withExternalAudio
import dev.anilbeesetti.nextplayer.feature.player.model.DecoderTrackType
import dev.anilbeesetti.nextplayer.feature.player.study.QuizGate
import dev.anilbeesetti.nextplayer.feature.player.study.RestGuard
import dev.anilbeesetti.nextplayer.feature.player.utils.isExplicitPlaylistItem
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderManager
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import io.github.anilbeesetti.nextlib.media3ext.renderer.subtitleDelayMilliseconds
import io.github.anilbeesetti.nextlib.media3ext.renderer.subtitleSpeed
import io.github.anilbeesetti.nextlib.mediainfo.MediaInfoBuilder
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

@OptIn(UnstableApi::class)
class PlayerService : MediaSessionService() {

    private val serviceScope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var mediaSession: MediaSession? = null
    private var artworkLoadJob: Job? = null

    private val preferencesRepository: PreferencesRepository by inject()

    private val dataSourceFactory: NextDataSourceFactory by inject()

    private val mediaRepository: MediaRepository by inject()

    private val imageLoader: ImageLoader by inject()

    private val quizGate: QuizGate by inject()

    private val restGuard: RestGuard by inject()

    private val studyRepository: StudyRepository by inject()

    private val licenseRepository: LicenseRepository by inject()

    private var quizTickerJob: Job? = null

    private val playerPreferences: PlayerPreferences
        get() = preferencesRepository.playerPreferences.value

    private val customCommands = CustomCommands.asSessionCommands()

    private var isMediaItemReady = false
    private var pendingAudioSelection: Pair<String, Uri>? = null

    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var currentVolumeGain: Int = 0

    private lateinit var decoderManager: DecoderManager
    private lateinit var trackSelector: DefaultTrackSelector
    private val decoderRecoveryManager = DecoderRecoveryManager()

    private val decoderAnalyticsListener = object : AnalyticsListener {
        override fun onEvents(player: Player, events: AnalyticsListener.Events) {
            publishDecoderState()
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            Logger.logInfo(
                DECODER_LOG_TAG,
                "Video decoder initialized with requested=${decoderManager.videoMode} " +
                    "as ${decoderManager.activeVideoMode}: $decoderName",
            )
            decoderRecoveryManager.onDecoderInitialized(DecoderTrackType.VIDEO)
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            Logger.logInfo(
                DECODER_LOG_TAG,
                "Audio decoder initialized with requested=${decoderManager.audioMode} " +
                    "as ${decoderManager.activeAudioMode}: $decoderName",
            )
            decoderRecoveryManager.onDecoderInitialized(DecoderTrackType.AUDIO)
        }

        override fun onTracksChanged(
            eventTime: AnalyticsListener.EventTime,
            tracks: Tracks,
        ) {
            val videoTracks = tracks.groups
                .filter { it.type == C.TRACK_TYPE_VIDEO }
                .joinToString { group ->
                    val mimeType = group.mediaTrackGroup.getFormat(0).sampleMimeType
                    "$mimeType(supported=${group.isSupported(true)}, selected=${group.isSelected})"
                }
            Logger.logInfo(
                DECODER_LOG_TAG,
                "Video tracks: ${videoTracks.ifEmpty { "none" }}, " +
                    "unmapped=${trackSelector.unmappedTrackCount(C.TRACK_TYPE_VIDEO)}",
            )
        }

        override fun onRenderedFirstFrame(
            eventTime: AnalyticsListener.EventTime,
            output: Any,
            renderTimeMs: Long,
        ) {
            Logger.logInfo(
                DECODER_LOG_TAG,
                "Rendered first frame with video=${decoderManager.activeVideoMode}",
            )
        }

        override fun onPlayerError(
            eventTime: AnalyticsListener.EventTime,
            error: PlaybackException,
        ) {
            Logger.logError(
                DECODER_LOG_TAG,
                "Player error with requestedVideo=${decoderManager.videoMode}, " +
                    "activeVideo=${decoderManager.activeVideoMode}, " +
                    "requestedAudio=${decoderManager.audioMode}, " +
                    "activeAudio=${decoderManager.activeAudioMode}: ${error.message}",
            )
        }
    }

    private val playbackStateListener = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            val player = mediaSession?.player?.exo() ?: return
            // Autoplay controls the implicit folder queue; explicitly chosen playlists play in full.
            player.pauseAtEndOfMediaItems = !playerPreferences.autoplay &&
                player.currentMediaItem?.isExplicitPlaylistItem != true
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            super.onMediaItemTransition(mediaItem, reason)
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) return
            restoreDecoderChoices(mediaItem)
            isMediaItemReady = false
            loadArtworkForCurrentMediaItem()
            restorePendingQuizLockOnce()
            mediaItem?.mediaMetadata?.let { metadata ->
                mediaSession?.player?.run {
                    playerSpecificSubtitleDelayMilliseconds = metadata.subtitleDelayMilliseconds ?: 0L
                    playerSpecificSubtitleSpeed = metadata.subtitleSpeed ?: 1f
                }

                metadata.positionMs?.takeIf { playerPreferences.resume == Resume.YES }?.let { pos ->
                    // 自动续播定位：直接对底层播放器跳转，绕开快进冷却（不刷新冷却时间戳）
                    (mediaSession?.player as? ThrottledSeekPlayer)?.seekToWithoutThrottle(pos)
                }
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            super.onPositionDiscontinuity(oldPosition, newPosition, reason)
            val oldMediaItem = oldPosition.mediaItem ?: return

            when (reason) {
                DISCONTINUITY_REASON_SEEK,
                DISCONTINUITY_REASON_AUTO_TRANSITION,
                -> {
                    if (newPosition.mediaItem == null || oldMediaItem == newPosition.mediaItem) return

                    val updatedPosition = oldPosition.positionMs.takeIf { reason == DISCONTINUITY_REASON_SEEK } ?: C.TIME_UNSET
                    mediaSession?.player?.replaceMediaItem(
                        oldPosition.mediaItemIndex,
                        oldMediaItem.copy(positionMs = updatedPosition),
                    )
                    serviceScope.launch {
                        mediaRepository.updateMediumPosition(
                            uri = oldMediaItem.mediaId,
                            position = updatedPosition,
                        )
                    }
                }

                DISCONTINUITY_REASON_REMOVE -> {
                    serviceScope.launch {
                        val durationMs = oldMediaItem.mediaMetadata.durationMs
                        val isAtEnd = durationMs != null && oldPosition.positionMs >= durationMs - 1000
                        mediaRepository.updateMediumPosition(
                            uri = oldMediaItem.mediaId,
                            position = if (isAtEnd) C.TIME_UNSET else oldPosition.positionMs,
                        )
                    }
                }

                else -> return
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            super.onTracksChanged(tracks)
            serviceScope.launch {
                mediaSession?.player?.currentTracks?.let(::handleUnsupportedTracks)
            }
            if (!isMediaItemReady && tracks.groups.isNotEmpty()) {
                isMediaItemReady = true

                val player = mediaSession?.player ?: return
                val pending = pendingAudioSelection
                pendingAudioSelection = null
                if (pending != null && pending.first == player.currentMediaItem?.mediaId) {
                    val audioIndex = player.currentMediaItem?.mediaMetadata?.externalAudio.orEmpty().indexOf(pending.second)
                    val trackIndex = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                        .indexOfFirst { it.mediaTrackGroup.externalAudioIndex == audioIndex }
                    if (audioIndex >= 0 && trackIndex >= 0) player.switchTrack(C.TRACK_TYPE_AUDIO, trackIndex)
                }
                if (!playerPreferences.rememberSelections) return
                mediaSession?.player?.mediaMetadata?.audioTrackIndex?.takeIf { pending == null }?.let {
                    mediaSession?.player?.switchTrack(C.TRACK_TYPE_AUDIO, it)
                }
                mediaSession?.player?.mediaMetadata?.subtitleTrackIndex?.let {
                    mediaSession?.player?.switchTrack(C.TRACK_TYPE_TEXT, it)
                }
            }
        }

        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
            super.onTrackSelectionParametersChanged(parameters)
            val player = mediaSession?.player ?: return
            val currentMediaItem = player.currentMediaItem ?: return

            val audioTrackIndex = player.getManuallySelectedTrackIndex(C.TRACK_TYPE_AUDIO)
            val subtitleTrackIndex = player.getManuallySelectedTrackIndex(C.TRACK_TYPE_TEXT)

            if (audioTrackIndex != null) {
                serviceScope.launch {
                    mediaRepository.updateMediumAudioTrack(
                        uri = currentMediaItem.mediaId,
                        audioTrackIndex = audioTrackIndex,
                    )
                }
            }

            if (subtitleTrackIndex != null) {
                serviceScope.launch {
                    mediaRepository.updateMediumSubtitleTrack(
                        uri = currentMediaItem.mediaId,
                        subtitleTrackIndex = subtitleTrackIndex,
                    )
                }
            }

            player.replaceMediaItem(
                player.currentMediaItemIndex,
                currentMediaItem.copy(
                    audioTrackIndex = audioTrackIndex,
                    subtitleTrackIndex = subtitleTrackIndex,
                ),
            )
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            super.onPlaybackStateChanged(playbackState)

            val player = mediaSession?.player
            val shouldResetTrackSelectionParameters = playbackState == Player.STATE_ENDED ||
                (
                    playbackState == Player.STATE_IDLE &&
                        player?.mediaItemCount == 0
                    )
            if (shouldResetTrackSelectionParameters) {
                mediaSession?.player?.trackSelectionParameters = TrackSelectionParameters.DEFAULT
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!error.isDecoderFailure) {
                decoderRecoveryManager.onNonDecoderError()
                return
            }

            val trackType = error.decoderTrackType() ?: run {
                decoderRecoveryManager.onNonDecoderError()
                return
            }
            handleDecoderFailure(trackType)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            super.onPlayWhenReadyChanged(playWhenReady, reason)

            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                if (mediaSession?.player?.repeatMode != Player.REPEAT_MODE_OFF) {
                    mediaSession?.player?.seekTo(0)
                    mediaSession?.player?.play()
                    return
                }
                mediaSession?.run {
                    player.clearMediaItems()
                    player.stop()
                }
                stopSelf()
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            // Consecutive items can reuse the renderer without another first-frame callback.
            if (player.playbackState != Player.STATE_READY ||
                !events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_PLAYBACK_STATE_CHANGED)
            ) {
                return
            }
            val currentMediaItem = player.currentMediaItem ?: return
            val duration = player.duration.validDurationOrNull()
            // Update the media metadata duration so that it will be used later in position discontinuity handling
            player.replaceMediaItem(
                player.currentMediaItemIndex,
                currentMediaItem.copy(durationMs = duration ?: 0),
            )

            serviceScope.launch {
                mediaRepository.updateMediumLastPlayedTime(
                    uri = currentMediaItem.mediaId,
                    lastPlayedTime = System.currentTimeMillis(),
                    duration = duration,
                )
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            super.onIsPlayingChanged(isPlaying)
            mediaSession?.run {
                serviceScope.launch {
                    mediaRepository.updateMediumPosition(
                        uri = player.currentMediaItem?.mediaId ?: return@launch,
                        position = player.currentPosition,
                    )
                }
            }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            super.onRepeatModeChanged(repeatMode)
            serviceScope.launch {
                preferencesRepository.updatePlayerPreferences {
                    it.copy(
                        loopMode = when (repeatMode) {
                            Player.REPEAT_MODE_OFF -> LoopMode.OFF
                            Player.REPEAT_MODE_ONE -> LoopMode.ONE
                            Player.REPEAT_MODE_ALL -> LoopMode.ALL
                            else -> LoopMode.OFF
                        },
                    )
                }
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            super.onAudioSessionIdChanged(audioSessionId)
            if (!playerPreferences.enableVolumeBoost) return
            if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
            try {
                loudnessEnhancer?.release()
                loudnessEnhancer = LoudnessEnhancer(audioSessionId)
                if (currentVolumeGain > 0) {
                    setEnhancerTargetGain(currentVolumeGain)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                loudnessEnhancer = null
            }
        }
    }

    private fun setEnhancerTargetGain(gain: Int) {
        val enhancer = loudnessEnhancer ?: return

        try {
            enhancer.setTargetGain(gain)
            enhancer.enabled = gain > 0
            currentVolumeGain = enhancer.targetGain.toInt()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val mediaSessionCallback = object : MediaSession.Callback {
        override fun onConnectAsync(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.ConnectionResult> = Futures.immediateFuture(
            MediaSession.ConnectionResult.accept(
                MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                    .buildUpon()
                    .addSessionCommands(customCommands)
                    .build(),
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS,
            ),
        )

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = serviceScope.future(Dispatchers.Default) {
            val updatedMediaItems = updatedMediaItemsWithMetadata(mediaItems)
            return@future MediaSession.MediaItemsWithStartPosition(updatedMediaItems, startIndex, startPositionMs)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = serviceScope.future(Dispatchers.Default) {
            val updatedMediaItems = updatedMediaItemsWithMetadata(mediaItems)
            return@future updatedMediaItems.toMutableList()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> = serviceScope.future {
            val command = CustomCommands.fromSessionCommand(customCommand)
                ?: return@future SessionResult(SessionError.ERROR_BAD_VALUE)

            when (command) {
                CustomCommands.ADD_SUBTITLE_TRACK -> {
                    val subtitleUri = args.getString(CustomCommands.SUBTITLE_TRACK_URI_KEY)?.toUri()
                        ?: return@future SessionResult(SessionError.ERROR_BAD_VALUE)

                    val newSubConfiguration = uriToSubtitleConfiguration(
                        uri = subtitleUri,
                        subtitleEncoding = playerPreferences.subtitleTextEncoding,
                    )
                    mediaSession?.player?.let { player ->
                        val currentMediaItem = player.currentMediaItem ?: return@let
                        val textTracks = player.currentTracks.groups.filter {
                            it.type == C.TRACK_TYPE_TEXT && it.isSupported
                        }

                        mediaRepository.updateMediumPosition(
                            uri = currentMediaItem.mediaId,
                            position = player.currentPosition,
                        )
                        mediaRepository.updateMediumSubtitleTrack(
                            uri = currentMediaItem.mediaId,
                            subtitleTrackIndex = textTracks.size,
                        )
                        mediaRepository.addExternalSubtitleToMedium(
                            uri = currentMediaItem.mediaId,
                            subtitleUri = subtitleUri,
                        )
                        player.addAdditionalSubtitleConfiguration(newSubConfiguration)
                    }
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.ADD_AUDIO_TRACK -> {
                    val uri = args.getString(CustomCommands.AUDIO_TRACK_URI_KEY)?.toUri()
                        ?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT || it.scheme == ContentResolver.SCHEME_FILE }
                        ?: return@future SessionResult(SessionError.ERROR_BAD_VALUE)
                    val player = session.player
                    val mediaItem = player.currentMediaItem
                        ?: return@future SessionResult(SessionError.ERROR_INVALID_STATE)
                    val hasAudio = withContext(Dispatchers.IO) {
                        runCatching {
                            contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                                MediaInfoBuilder().from(descriptor).build()?.let { info ->
                                    try {
                                        info.audioStreams.isNotEmpty()
                                    } finally {
                                        info.release()
                                    }
                                }
                            } == true
                        }.getOrDefault(false)
                    }
                    if (!hasAudio) return@future SessionResult(SessionError.ERROR_BAD_VALUE)
                    if (player.currentMediaItem?.mediaId != mediaItem.mediaId) {
                        return@future SessionResult(SessionError.ERROR_INVALID_STATE)
                    }
                    mediaRepository.addExternalAudioToMedium(mediaItem.mediaId, uri)
                    val currentItem = player.currentMediaItem?.takeIf { it.mediaId == mediaItem.mediaId }
                        ?: return@future SessionResult(SessionError.ERROR_INVALID_STATE)
                    val audio = (currentItem.mediaMetadata.externalAudio + uri).distinct()
                    pendingAudioSelection = currentItem.mediaId to uri
                    val updatedItem = currentItem.withExternalAudio(audio).copy(positionMs = player.currentPosition)
                    val index = player.currentMediaItemIndex
                    player.addMediaItem(index + 1, updatedItem)
                    player.seekTo(index + 1, player.currentPosition)
                    player.removeMediaItem(index)
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.SET_SKIP_SILENCE_ENABLED -> {
                    val enabled = args.getBoolean(CustomCommands.SKIP_SILENCE_ENABLED_KEY)
                    mediaSession?.player?.playerSpecificSkipSilenceEnabled = enabled
                    mediaSession?.sessionExtras = Bundle(mediaSession?.sessionExtras ?: Bundle.EMPTY).apply {
                        putBoolean(CustomCommands.SKIP_SILENCE_ENABLED_KEY, enabled)
                    }
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.GET_SKIP_SILENCE_ENABLED -> {
                    val enabled = mediaSession?.player?.playerSpecificSkipSilenceEnabled ?: false
                    return@future SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle().apply {
                            putBoolean(CustomCommands.SKIP_SILENCE_ENABLED_KEY, enabled)
                        },
                    )
                }

                CustomCommands.SET_IS_SCRUBBING_MODE_ENABLED -> {
                    val enabled = args.getBoolean(CustomCommands.IS_SCRUBBING_MODE_ENABLED_KEY)
                    mediaSession?.player?.setIsScrubbingModeEnabled(enabled)
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.IS_LOUDNESS_GAIN_SUPPORTED -> {
                    val isSupported = loudnessEnhancer != null
                    return@future SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle().apply {
                            putBoolean(CustomCommands.IS_LOUDNESS_GAIN_SUPPORTED_KEY, isSupported)
                        },
                    )
                }

                CustomCommands.SET_LOUDNESS_GAIN -> {
                    val gain = args.getInt(CustomCommands.LOUDNESS_GAIN_KEY, 0)
                    setEnhancerTargetGain(gain)
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.GET_LOUDNESS_GAIN -> {
                    return@future SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle().apply {
                            putInt(CustomCommands.LOUDNESS_GAIN_KEY, currentVolumeGain)
                        },
                    )
                }

                CustomCommands.SET_VIDEO_DECODER_MODE -> {
                    val mode = args.decoderMode(CustomCommands.VIDEO_DECODER_MODE_KEY)
                        ?: return@future SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE)
                    val player = mediaSession?.player?.exo()
                        ?: return@future SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE)
                    decoderRecoveryManager.onUserSelection(DecoderTrackType.VIDEO, mode)
                    selectDecoder(DecoderTrackType.VIDEO, mode)
                    serviceScope.launch { handleUnsupportedTrack(player.currentTracks, DecoderTrackType.VIDEO) }
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.SET_AUDIO_DECODER_MODE -> {
                    val mode = args.decoderMode(CustomCommands.AUDIO_DECODER_MODE_KEY)
                        ?: return@future SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE)
                    val player = mediaSession?.player?.exo()
                        ?: return@future SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE)
                    decoderRecoveryManager.onUserSelection(DecoderTrackType.AUDIO, mode)
                    selectDecoder(DecoderTrackType.AUDIO, mode)
                    serviceScope.launch { handleUnsupportedTrack(player.currentTracks, DecoderTrackType.AUDIO) }
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.TRY_DECODER_FALLBACK -> {
                    val retry = decoderRecoveryManager.confirmFallback()
                        ?: return@future SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE)
                    if (!retryDecoderWith(retry)) {
                        decoderRecoveryManager.onNonDecoderError()
                        return@future SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE)
                    }
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.GET_SUBTITLE_DELAY -> {
                    val subtitleDelay = mediaSession?.player?.playerSpecificSubtitleDelayMilliseconds ?: 0
                    return@future SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle().apply {
                            putLong(CustomCommands.SUBTITLE_DELAY_KEY, subtitleDelay)
                        },
                    )
                }

                CustomCommands.SET_SUBTITLE_DELAY -> {
                    val subtitleDelay = args.getLong(CustomCommands.SUBTITLE_DELAY_KEY)
                    mediaSession?.player?.playerSpecificSubtitleDelayMilliseconds = subtitleDelay
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.GET_SUBTITLE_SPEED -> {
                    val subtitleSpeed = mediaSession?.player?.playerSpecificSubtitleSpeed ?: 0f
                    return@future SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle().apply {
                            putFloat(CustomCommands.SUBTITLE_SPEED_KEY, subtitleSpeed)
                        },
                    )
                }

                CustomCommands.SET_SUBTITLE_SPEED -> {
                    val subtitleSpeed = args.getFloat(CustomCommands.SUBTITLE_SPEED_KEY)
                    mediaSession?.player?.playerSpecificSubtitleSpeed = subtitleSpeed
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.STOP_PLAYER_SESSION -> {
                    savePositionAndStopSession()
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /** 主线程 Handler，用于把 Toast 切回 UI 线程显示 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 冷却期内跳转被拦截时，弹 Toast 提示用户还需等待多少秒。
     * handleSeek 可能运行在播放器内部线程，因此用 mainHandler 切回主线程。
     */
    private fun showSeekThrottledToast(remainingMillis: Long) {
        val seconds = seekMillisToSeconds(remainingMillis)
        val message = getString(coreUiR.string.seek_throttled_hint, seconds)
        mainHandler.post {
            Toast.makeText(this@PlayerService, message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 学习答题锁：每秒统计一次「真的在播」的时长。
     *
     * 计时必须放在 Service 而不是 Compose 层——本应用支持后台播放与通知栏控制，
     * 写在 UI 层的话，切后台、锁屏、按 Home 键都能绕过拦截。
     * 用 isPlaying 判定而不是 positionMs 差值，顺带免疫「反复拖进度条刷时长」。
     */
    private fun startQuizTicker() {
        quizTickerJob?.cancel()
        quizTickerJob = serviceScope.launch {
            while (isActive) {
                delay(QUIZ_TICK_INTERVAL_MS)
                val player = mediaSession?.player ?: continue
                val progressing = player.isPlaying && player.playbackState == Player.STATE_READY
                quizGate.onTick(if (progressing) QUIZ_TICK_INTERVAL_MS else 0L)
                restGuard.onTick(progressing)
            }
        }
    }

    /**
     * 上次被强杀/闪退打断在答题页时，重新进入播放要恢复锁定。
     * 方法内部会自行去重，这里每次拿到媒体项都调一次即可。
     */
    private fun restorePendingQuizLockOnce() {
        quizGate.restorePendingLock()
    }

    override fun onCreate() {
        super.onCreate()
        decoderManager = DecoderManager()
        val renderersFactory = NextRenderersFactory(applicationContext).setDecoderManager(decoderManager)

        trackSelector = DefaultTrackSelector(applicationContext).apply {
            setParameters(
                buildUponParameters()
                    .setTunnelingEnabled(playerPreferences.enableTunneledPlayback)
                    .setPreferredAudioLanguage(playerPreferences.preferredAudioLanguage)
                    .setPreferredTextLanguage(playerPreferences.preferredSubtitleLanguage),
            )
        }

        val player = ExoPlayer.Builder(applicationContext)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(
                ExternalAudioMediaSourceFactory(
                    DefaultMediaSourceFactory(applicationContext).setDataSourceFactory(dataSourceFactory),
                ),
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                playerPreferences.requireAudioFocus,
            )
            .setHandleAudioBecomingNoisy(playerPreferences.pauseOnHeadsetDisconnect)
            .build()
            .also {
                it.setPlaybackSpeed(playerPreferences.defaultPlaybackSpeed)
                it.addListener(playbackStateListener)
                it.pauseAtEndOfMediaItems = !playerPreferences.autoplay
                it.repeatMode = when (playerPreferences.loopMode) {
                    LoopMode.OFF -> Player.REPEAT_MODE_OFF
                    LoopMode.ONE -> Player.REPEAT_MODE_ONE
                    LoopMode.ALL -> Player.REPEAT_MODE_ALL
                }
            }

        // 快进节流：用 ThrottledSeekPlayer 包裹真实 ExoPlayer 交给 MediaSession/UI。
        // 冷却时长与开关由家长在家长设置页配置，每次跳转现读，改完设置立刻生效。
        // 收费分层（方案 C）：已授权（Pro / 试用期）按家长设置；未授权强制常开、固定 2 分钟。
        // 解码器与分析监听仍使用原始 player。
        val throttledPlayer = ThrottledSeekPlayer(player) {
            effectiveSeekIntervalMillis(
                pro = licenseRepository.statusNow().pro,
                preferences = studyRepository.preferences,
            )
        }
        throttledPlayer.onSeekThrottled = { remainingMillis ->
            showSeekThrottledToast(remainingMillis)
        }

        decoderManager.attach(player)
        player.addAnalyticsListener(decoderAnalyticsListener)

        try {
            mediaSession = MediaSession.Builder(this, throttledPlayer).apply {
                setSessionActivity(
                    PendingIntent.getActivity(
                        this@PlayerService,
                        0,
                        Intent(this@PlayerService, PlayerActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                setCallback(mediaSessionCallback)
                setCustomLayout(
                    listOf(
                        CommandButton.Builder(ICON_UNDEFINED)
                            .setCustomIconResId(coreUiR.drawable.ic_close)
                            .setDisplayName(getString(coreUiR.string.stop_player_session))
                            .setSessionCommand(CustomCommands.STOP_PLAYER_SESSION.sessionCommand)
                            .setEnabled(true)
                            .build(),
                    ),
                )
            }.build()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 学习答题锁：绑定播放器并开始按「有效播放时长」计时
        quizGate.attach(throttledPlayer)
        startQuizTicker()
        // 防沉迷：恢复窗口历史（杀进程重进仍记得「已经看了多久」），随后由 ticker 持续计时
        restGuard.restore()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player ?: return
        if (!player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) {
            serviceScope.launch { savePositionAndStopSession() }
        }
    }

    private suspend fun savePositionAndStopSession() {
        mediaSession?.run {
            player.currentMediaItem?.let { mediaItem ->
                mediaRepository.updateMediumPosition(
                    uri = mediaItem.mediaId,
                    position = player.currentPosition,
                )
            }
            player.clearMediaItems()
            player.stop()
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        quizTickerJob?.cancel()
        quizGate.detach()
        artworkLoadJob?.cancel()
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        mediaSession?.run {
            player.clearMediaItems()
            player.stop()
            player.removeListener(playbackStateListener)
            decoderManager.detach()
            player.release()
            release()
            mediaSession = null
        }
        subtitleCacheDir.deleteFiles()
        // Drop the network connection before the scope dies, so a share isn't held open for the
        // lifetime of the process after playback ends.
        runBlocking { dataSourceFactory.release() }
        serviceScope.cancel()
    }

    private fun Long.validDurationOrNull(): Long? = takeIf { it != C.TIME_UNSET && it >= 0 }

    private suspend fun updatedMediaItemsWithMetadata(
        mediaItems: List<MediaItem>,
    ): List<MediaItem> = supervisorScope {
        mediaItems.map { mediaItem ->
            async {
                val uri = mediaItem.mediaId.toUri()
                val isNetwork = mediaItem.isNetworkMediaItem()

                val video = if (isNetwork) null else mediaRepository.getVideoByUri(uri = mediaItem.mediaId)
                val videoState = mediaRepository.getVideoState(uri = mediaItem.mediaId)

                val externalSubs = videoState?.externalSubs ?: emptyList()
                val savedAudio = videoState?.externalAudio.orEmpty()
                val externalAudio = withContext(Dispatchers.IO) {
                    savedAudio.filter { uri ->
                        runCatching { contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } == true }
                            .getOrDefault(false)
                    }
                }
                val localSubs = if (!isNetwork) {
                    (videoState?.path ?: getPath(uri))?.let {
                        File(it).getLocalSubtitles(
                            context = this@PlayerService,
                            excludeSubsList = externalSubs,
                        )
                    } ?: emptyList()
                } else {
                    emptyList()
                }

                val existingSubConfigurations = mediaItem.localConfiguration?.subtitleConfigurations ?: emptyList()
                val subConfigurations = (localSubs + externalSubs).map { subtitleUri ->
                    uriToSubtitleConfiguration(
                        uri = subtitleUri,
                        subtitleEncoding = playerPreferences.subtitleTextEncoding,
                    )
                }

                // Local items get a placeholder now and their real artwork in the background;
                // network items have no thumbnail to extract, so keep any supplied artwork.
                val artworkUri = if (isNetwork) {
                    mediaItem.mediaMetadata.artworkUri ?: getDefaultArtworkUri()
                } else {
                    getDefaultArtworkUri()
                }

                val title = mediaItem.mediaMetadata.title ?: video?.nameWithExtension ?: getFilenameFromUri(uri)
                val positionMs = mediaItem.mediaMetadata.positionMs ?: videoState?.position
                val videoScale = mediaItem.mediaMetadata.videoZoom ?: videoState?.videoScale
                val audioTrackIndex = mediaItem.mediaMetadata.audioTrackIndex ?: videoState?.audioTrackIndex
                val subtitleTrackIndex = mediaItem.mediaMetadata.subtitleTrackIndex ?: videoState?.subtitleTrackIndex
                val subtitleDelay = mediaItem.mediaMetadata.subtitleDelayMilliseconds ?: videoState?.subtitleDelayMilliseconds
                val subtitleSpeed = mediaItem.mediaMetadata.subtitleSpeed ?: videoState?.subtitleSpeed

                mediaItem.buildUpon().apply {
                    setSubtitleConfigurations(existingSubConfigurations + subConfigurations)
                    setMediaMetadata(
                        MediaMetadata.Builder().apply {
                            setTitle(title)
                            setArtworkUri(artworkUri)
                            setExtras(
                                positionMs = positionMs,
                                videoScale = videoScale,
                                audioTrackIndex = audioTrackIndex.takeIf { externalAudio == savedAudio },
                                subtitleTrackIndex = subtitleTrackIndex,
                                subtitleDelayMilliseconds = subtitleDelay,
                                subtitleSpeed = subtitleSpeed,
                                videoDecoderMode = mediaItem.mediaMetadata.videoDecoderMode,
                                audioDecoderMode = mediaItem.mediaMetadata.audioDecoderMode,
                            )
                        }.build(),
                    )
                }.build().withExternalAudio(externalAudio)
            }
        }.awaitAll()
    }

    private fun publishDecoderState() {
        val session = mediaSession ?: return
        val recoveryState = decoderRecoveryManager.state
        session.sessionExtras = Bundle(session.sessionExtras).apply {
            putString(CustomCommands.VIDEO_DECODER_MODE_KEY, decoderManager.activeVideoMode?.name)
            putString(CustomCommands.AUDIO_DECODER_MODE_KEY, decoderManager.activeAudioMode?.name)
            putString(CustomCommands.DECODER_RECOVERY_STATUS_KEY, recoveryState.status.name)
            putString(CustomCommands.DECODER_RECOVERY_TRACK_TYPE_KEY, recoveryState.trackType?.name)
            putString(CustomCommands.UNSUPPORTED_DECODER_MODE_KEY, recoveryState.unsupportedMode?.name)
        }
    }

    private fun retryDecoderWith(retry: DecoderRetry): Boolean {
        val player = mediaSession?.player?.exo() ?: return false
        val shouldPrepare = player.playerError != null
        selectDecoder(retry.trackType, retry.mode)
        if (shouldPrepare && player.mediaItemCount > 0) {
            player.prepare()
        }
        return true
    }

    private fun handleUnsupportedTracks(tracks: Tracks) {
        handleUnsupportedTrack(tracks, DecoderTrackType.VIDEO)
        handleUnsupportedTrack(tracks, DecoderTrackType.AUDIO)
    }

    private fun handleUnsupportedTrack(tracks: Tracks, trackType: DecoderTrackType) {
        val mediaTrackType = trackType.mediaTrackType
        val groups = tracks.groups.filter { it.type == mediaTrackType }
        val hasTrack = groups.isNotEmpty() || trackSelector.unmappedTrackCount(mediaTrackType) > 0
        if (!hasTrack || groups.any { it.isSupported(true) }) return

        handleDecoderFailure(trackType)
    }

    private fun handleDecoderFailure(trackType: DecoderTrackType) {
        val mode = when (trackType) {
            DecoderTrackType.VIDEO -> decoderManager.videoMode
            DecoderTrackType.AUDIO -> decoderManager.audioMode
        }
        val retry = decoderRecoveryManager.onDecoderFailure(trackType, mode)
        publishDecoderState()
        if (retry == null) return
        serviceScope.launch {
            if (!retryDecoderWith(retry)) decoderRecoveryManager.onNonDecoderError()
        }
    }

    private fun selectDecoder(trackType: DecoderTrackType, mode: DecoderMode) {
        when (trackType) {
            DecoderTrackType.VIDEO -> decoderManager.selectVideoDecoder(mode)
            DecoderTrackType.AUDIO -> decoderManager.selectAudioDecoder(mode)
        }
        val player = mediaSession?.player
        val mediaItem = player?.currentMediaItem
        if (mediaItem != null) {
            val updatedItem = when (trackType) {
                DecoderTrackType.VIDEO -> mediaItem.copy(videoDecoderMode = mode)
                DecoderTrackType.AUDIO -> mediaItem.copy(audioDecoderMode = mode)
            }
            player.replaceMediaItem(player.currentMediaItemIndex, updatedItem)
        }
        publishDecoderState()
    }

    private fun restoreDecoderChoices(mediaItem: MediaItem?) {
        val videoMode = mediaItem?.mediaMetadata?.videoDecoderMode ?: DecoderMode.AUTO
        val audioMode = mediaItem?.mediaMetadata?.audioDecoderMode ?: DecoderMode.AUTO
        decoderRecoveryManager.onUserSelection(DecoderTrackType.VIDEO, videoMode)
        decoderRecoveryManager.onUserSelection(DecoderTrackType.AUDIO, audioMode)
        selectDecoder(DecoderTrackType.VIDEO, videoMode)
        selectDecoder(DecoderTrackType.AUDIO, audioMode)
    }

    private fun DefaultTrackSelector.unmappedTrackCount(trackType: Int): Int {
        val trackGroups = currentMappedTrackInfo?.unmappedTrackGroups ?: return 0
        return (0 until trackGroups.length).count { index ->
            trackGroups[index].type == trackType
        }
    }

    private fun PlaybackException.decoderTrackType(): DecoderTrackType? {
        val playbackError = this as? ExoPlaybackException ?: return null
        val formatTrackType = MimeTypes.getTrackType(playbackError.rendererFormat?.sampleMimeType)
        val mediaTrackType = formatTrackType.takeIf {
            it == C.TRACK_TYPE_VIDEO || it == C.TRACK_TYPE_AUDIO
        } ?: trackSelector.currentMappedTrackInfo
            ?.takeIf { playbackError.rendererIndex in 0 until it.rendererCount }
            ?.getRendererType(playbackError.rendererIndex)

        return when (mediaTrackType) {
            C.TRACK_TYPE_VIDEO -> DecoderTrackType.VIDEO
            C.TRACK_TYPE_AUDIO -> DecoderTrackType.AUDIO
            else -> null
        }
    }

    private fun getDefaultArtworkUri(): Uri = Uri.Builder().apply {
        val defaultArtwork = R.drawable.artwork_default
        scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        authority(resources.getResourcePackageName(defaultArtwork))
        appendPath(resources.getResourceTypeName(defaultArtwork))
        appendPath(resources.getResourceEntryName(defaultArtwork))
    }.build()

    private fun loadArtworkForCurrentMediaItem() {
        artworkLoadJob?.cancel()
        artworkLoadJob = serviceScope.launch(Dispatchers.Main) {
            val player = mediaSession?.player ?: return@launch
            val currentMediaItem = player.currentMediaItem ?: return@launch
            if (currentMediaItem.mediaMetadata.artworkData != null) return@launch

            val artworkUri = loadArtworkForMediaItem(currentMediaItem)
                ?: getDefaultArtworkUri()

            val updatedPlayer = mediaSession?.player ?: return@launch
            val updatedMediaItem = updatedPlayer.currentMediaItem ?: return@launch
            if (updatedMediaItem.mediaId != currentMediaItem.mediaId) return@launch

            updatedPlayer.replaceMediaItem(
                updatedPlayer.currentMediaItemIndex,
                updatedMediaItem.withArtwork(artworkUri),
            )
        }
    }
    private suspend fun loadArtworkForMediaItem(mediaItem: MediaItem): Uri? = withContext(Dispatchers.IO) {
        val defaultArtwork = getDefaultArtworkUri()
        val uri = mediaItem.mediaMetadata.artworkUri
            ?.takeUnless { it == defaultArtwork }
            ?: if (mediaItem.isNetworkMediaItem()) {
                return@withContext null
            } else {
                mediaItem.mediaId.toUri()
            }
        return@withContext try {
            val request = ImageRequest.Builder(this@PlayerService)
                .data(uri)
                .size(512, 512)
                .build()
            imageLoader.execute(request)
            val diskCache = imageLoader.diskCache ?: return@withContext null
            return@withContext diskCache.openSnapshot(uri.toString())?.use { snapshot ->
                snapshot.data.toFile().toUri()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

    private fun MediaItem.isNetworkMediaItem(): Boolean {
        val uri = localConfiguration?.uri ?: return false
        return uri.scheme?.lowercase() in REMOTE_SCHEMES || NetworkUri.isNetworkUri(uri)
    }

    private fun MediaItem.withArtwork(uri: Uri): MediaItem = buildUpon()
        .setMediaMetadata(
            mediaMetadata.buildUpon()
                .setArtworkUri(uri)
                .build(),
        )
        .build()
}

internal val PlaybackException.isDecoderFailure: Boolean
    get() = when (errorCode) {
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
        -> true

        else -> false
    }

private val DecoderTrackType.mediaTrackType: Int
    get() = when (this) {
        DecoderTrackType.VIDEO -> C.TRACK_TYPE_VIDEO
        DecoderTrackType.AUDIO -> C.TRACK_TYPE_AUDIO
    }

private const val DECODER_LOG_TAG = "Decoder"

/** 学习答题锁的计时粒度：1 秒足够精确，也几乎不耗电。 */
private const val QUIZ_TICK_INTERVAL_MS = 1_000L

@get:UnstableApi
@set:UnstableApi
private var Player.playerSpecificSkipSilenceEnabled: Boolean
    @OptIn(UnstableApi::class)
    get() = ((this as? ExoPlayer) ?: (this as? ThrottledSeekPlayer)?.realPlayer)?.skipSilenceEnabled ?: false
    set(value) {
        val exo = (this as? ExoPlayer) ?: (this as? ThrottledSeekPlayer)?.realPlayer ?: return
        exo.skipSilenceEnabled = value
    }

@get:UnstableApi
@set:UnstableApi
private var Player.playerSpecificSubtitleDelayMilliseconds: Long
    @OptIn(UnstableApi::class)
    get() = ((this as? ExoPlayer) ?: (this as? ThrottledSeekPlayer)?.realPlayer)?.subtitleDelayMilliseconds ?: 0L
    set(value) {
        val exo = (this as? ExoPlayer) ?: (this as? ThrottledSeekPlayer)?.realPlayer ?: return
        exo.subtitleDelayMilliseconds = value
    }

@get:UnstableApi
@set:UnstableApi
private var Player.playerSpecificSubtitleSpeed: Float
    @OptIn(UnstableApi::class)
    get() = ((this as? ExoPlayer) ?: (this as? ThrottledSeekPlayer)?.realPlayer)?.subtitleSpeed ?: 0f
    set(value) {
        val exo = (this as? ExoPlayer) ?: (this as? ThrottledSeekPlayer)?.realPlayer ?: return
        exo.subtitleSpeed = value
    }

/** Schemes Media3 streams over the network itself, as opposed to reading from local storage. */
private val REMOTE_SCHEMES = setOf("http", "https", "rtsp")

/** 从 MediaSession 的 player 取底层 ExoPlayer，兼容 ThrottledSeekPlayer 包装层 */
private fun Player.exo(): ExoPlayer? =
    (this as? ExoPlayer) ?: (this as? ThrottledSeekPlayer)?.realPlayer
