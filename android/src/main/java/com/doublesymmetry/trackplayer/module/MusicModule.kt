package com.doublesymmetry.trackplayer.module

import android.content.*
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.support.v4.media.RatingCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.doublesymmetry.kotlinaudio.models.Capability
import com.doublesymmetry.kotlinaudio.models.RepeatMode
import com.doublesymmetry.trackplayer.extensions.NumberExt.Companion.toMilliseconds
import com.doublesymmetry.trackplayer.model.State
import com.doublesymmetry.trackplayer.model.Track
import com.doublesymmetry.trackplayer.module.MusicEvents.Companion.EVENT_INTENT
import com.doublesymmetry.trackplayer.service.MusicService
import com.doublesymmetry.trackplayer.utils.AppForegroundTracker
import com.doublesymmetry.trackplayer.utils.RejectionException
import com.facebook.react.bridge.*
import com.google.android.exoplayer2.DefaultLoadControl.*
import com.facebook.react.module.annotations.ReactModule
import com.google.android.exoplayer2.Player
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.*
import javax.annotation.Nonnull

import com.doublesymmetry.trackplayer.NativeRNTPSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers


/**
 * @author Milen Pivchev @mpivchev
 */
@ReactModule(name = MusicModule.NAME)
class MusicModule(reactContext: ReactApplicationContext) : NativeRNTPSpec(reactContext),
    ServiceConnection {
    private var playerOptions: Bundle? = null
    private var isServiceBound = false
    private var playerSetUpPromise: Promise? = null
    private val scope = MainScope()
    private lateinit var musicService: MusicService
    private val context = reactContext

    @Nonnull
    override fun getName(): String {
        return NAME
    }

    companion object {
        const val NAME = "TrackPlayerModule"
    }
    
    init {
        Timber.plant(Timber.DebugTree())
        AppForegroundTracker.start()
    }

    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        run {
            // If a binder already exists, don't get a new one
            if (!::musicService.isInitialized) {
                val binder: MusicService.MusicBinder = service as MusicService.MusicBinder
                musicService = binder.service
                musicService.setupPlayer(playerOptions)
                playerSetUpPromise?.resolve(null)
            }

            isServiceBound = true
        }
    }

    /**
     * Called when a connection to the Service has been lost.
     */
    override fun onServiceDisconnected(name: ComponentName) {
        run {
            isServiceBound = false
        }
    }

    /**
     * Checks wither service is bound, or rejects. Returns whether promise was rejected.
     */
    private fun verifyServiceBoundOrReject(promise: Promise): Boolean {
        if (!isServiceBound) {
            promise.reject(
                "player_not_initialized",
                "The player is not initialized. Call setupPlayer first."
            )
            return true
        }

        return false
    }

    /**
     * Checks wither service is bound. Returns true or false.
     */
    private fun verifyServiceBound(promise: Promise): Boolean {
        if (!isServiceBound) {
            return false
        }

        return true
    }

    private fun bundleToTrack(bundle: Bundle): Track {
        return Track(context, bundle, musicService.ratingType)
    }

    private fun rejectWithException(callback: Promise, exception: Exception) {
        when (exception) {
            is RejectionException -> {
                callback.reject(exception.code, exception)
            }
            else -> {
                callback.reject("runtime_exception", exception)
            }
        }
    }

    private fun readableArrayToTrackList(data: ReadableArray?): MutableList<Track> {
        val bundleList = Arguments.toList(data)
        if (bundleList !is ArrayList) {
            throw RejectionException("invalid_parameter", "Was not given an array of tracks")
        }
        return bundleList.map {
            if (it is Bundle) {
                bundleToTrack(it)
            } else {
                throw RejectionException(
                    "invalid_track_object",
                    "Track was not a dictionary type"
                )
            }
        }.toMutableList()
    }

    /* ****************************** API ****************************** */
    override fun getTypedExportedConstants(): Map<String, Any> {
        return HashMap<String, Any>().apply {
            // Capabilities
            this["CAPABILITY_PLAY"] = Capability.PLAY.ordinal
            this["CAPABILITY_PLAY_FROM_ID"] = Capability.PLAY_FROM_ID.ordinal
            this["CAPABILITY_PLAY_FROM_SEARCH"] = Capability.PLAY_FROM_SEARCH.ordinal
            this["CAPABILITY_PAUSE"] = Capability.PAUSE.ordinal
            this["CAPABILITY_STOP"] = Capability.STOP.ordinal
            this["CAPABILITY_SEEK_TO"] = Capability.SEEK_TO.ordinal
            this["CAPABILITY_SKIP"] = OnErrorAction.SKIP.ordinal
            this["CAPABILITY_SKIP_TO_NEXT"] = Capability.SKIP_TO_NEXT.ordinal
            this["CAPABILITY_SKIP_TO_PREVIOUS"] = Capability.SKIP_TO_PREVIOUS.ordinal
            this["CAPABILITY_SET_RATING"] = Capability.SET_RATING.ordinal
            this["CAPABILITY_JUMP_FORWARD"] = Capability.JUMP_FORWARD.ordinal
            this["CAPABILITY_JUMP_BACKWARD"] = Capability.JUMP_BACKWARD.ordinal
            this["CAPABILITY_BOOKMARK"] = Capability.BOOKMARK.ordinal
            this["CAPABILITY_DISLIKE"] = Capability.DISLIKE.ordinal
            this["CAPABILITY_LIKE"] = Capability.LIKE.ordinal

            // States
            this["STATE_NONE"] = State.None.state
            this["STATE_READY"] = State.Ready.state
            this["STATE_PLAYING"] = State.Playing.state
            this["STATE_PAUSED"] = State.Paused.state
            this["STATE_STOPPED"] = State.Stopped.state
            this["STATE_BUFFERING"] = State.Buffering.state
            this["STATE_LOADING"] = State.Loading.state

            // Rating Types
            this["RATING_HEART"] = RatingCompat.RATING_HEART
            this["RATING_THUMBS_UP_DOWN"] = RatingCompat.RATING_THUMB_UP_DOWN
            this["RATING_3_STARS"] = RatingCompat.RATING_3_STARS
            this["RATING_4_STARS"] = RatingCompat.RATING_4_STARS
            this["RATING_5_STARS"] = RatingCompat.RATING_5_STARS
            this["RATING_PERCENTAGE"] = RatingCompat.RATING_PERCENTAGE

            // Repeat Modes
            this["REPEAT_OFF"] = Player.REPEAT_MODE_OFF
            this["REPEAT_TRACK"] = Player.REPEAT_MODE_ONE
            this["REPEAT_QUEUE"] = Player.REPEAT_MODE_ALL

            // Pitch Algorithm: No-op on android
            this["PITCH_ALGORITHM_LINEAR"] = 1
            this["PITCH_ALGORITHM_MUSIC"] = 2
            this["PITCH_ALGORITHM_VOICE"] = 3
        }
    }

    override fun setupPlayer(data: ReadableMap?, promise: Promise) {
        if (isServiceBound) {
            promise.reject(
                "player_already_initialized",
                "The player has already been initialized via setupPlayer."
            )
            return
        }

        // prevent crash Fatal Exception: android.app.RemoteServiceException$ForegroundServiceDidNotStartInTimeException
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && AppForegroundTracker.backgrounded) {
            promise.reject(
                "android_cannot_setup_player_in_background",
                "On Android the app must be in the foreground when setting up the player."
            )
            return
        }

        // Validate buffer keys.
        val bundledData = Arguments.toBundle(data)
        val minBuffer =
            bundledData?.getDouble(MusicService.MIN_BUFFER_KEY)?.toMilliseconds()?.toInt()
                ?: DEFAULT_MIN_BUFFER_MS
        val maxBuffer =
            bundledData?.getDouble(MusicService.MAX_BUFFER_KEY)?.toMilliseconds()?.toInt()
                ?: DEFAULT_MAX_BUFFER_MS
        val playBuffer =
            bundledData?.getDouble(MusicService.PLAY_BUFFER_KEY)?.toMilliseconds()?.toInt()
                ?: DEFAULT_BUFFER_FOR_PLAYBACK_MS
        val backBuffer =
            bundledData?.getDouble(MusicService.BACK_BUFFER_KEY)?.toMilliseconds()?.toInt()
                ?: DEFAULT_BACK_BUFFER_DURATION_MS

        if (playBuffer < 0) {
            promise.reject(
                "play_buffer_error",
                "The value for playBuffer should be greater than or equal to zero."
            )
            return
        }

        if (backBuffer < 0) {
            promise.reject(
                "back_buffer_error",
                "The value for backBuffer should be greater than or equal to zero."
            )
            return
        }

        if (minBuffer < playBuffer) {
            promise.reject(
                "min_buffer_error",
                "The value for minBuffer should be greater than or equal to playBuffer."
            )
            return
        }

        if (maxBuffer < minBuffer) {
            promise.reject(
                "min_buffer_error",
                "The value for maxBuffer should be greater than or equal to minBuffer."
            )
            return
        }

        playerSetUpPromise = promise
        playerOptions = bundledData


        LocalBroadcastManager.getInstance(context).registerReceiver(
            MusicEvents(context),
            IntentFilter(EVENT_INTENT)
        )

        Intent(context, MusicService::class.java).also { intent ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            context.bindService(intent, this, Context.BIND_AUTO_CREATE)
        }
    }

    @Deprecated("Backwards compatible function from the old android implementation. Should be removed in the next major release.")
    override fun isServiceRunning(callback: Promise) {
        callback.resolve(isServiceBound)
    }

    override fun updateOptions(data: ReadableMap?, callback: Promise) = run {
        if (verifyServiceBoundOrReject(callback)) return

        val options = Arguments.toBundle(data)

        options?.let {
            musicService.updateOptions(it)
        }

        callback.resolve(null)
    }

    override fun add(data: ReadableArray, insertBeforeIndex: Double?, callback: Promise) = run {
        if (verifyServiceBoundOrReject(callback)) return

        try {
            val tracks = readableArrayToTrackList(data);
            if ((insertBeforeIndex?.toInt() ?: 0) < -1 || (insertBeforeIndex?.toInt() ?: 0) > musicService.tracks.size) {
                callback.reject("index_out_of_bounds", "The track index is out of bounds")
                return
            }
            val index = if ((insertBeforeIndex?.toInt() ?: 0) == -1) musicService.tracks.size else insertBeforeIndex
            musicService.add(
                tracks,
                index?.toInt() ?: 0
            )
            callback.resolve(index)
        } catch (exception: Exception) {
            rejectWithException(callback, exception)
        }
    }

    override fun load(data: ReadableMap?, callback: Promise) = run {
        if (verifyServiceBoundOrReject(callback)) return
        if (data == null) {
            callback.resolve(null)
            return
        }
        val bundle = Arguments.toBundle(data);
        if (bundle is Bundle) {
            musicService.load(bundleToTrack(bundle))
            callback.resolve(null)
        } else {
            callback.reject("invalid_track_object", "Track was not a dictionary type")
        }
    }

    override fun move(fromIndex: Double, toIndex: Double, callback: Promise) = run {
        if (verifyServiceBoundOrReject(callback)) return
        musicService.move(fromIndex.toInt(), toIndex.toInt())
        callback.resolve(null)
    }

    override fun remove(data: ReadableArray?, callback: Promise) = run {
        if (verifyServiceBoundOrReject(callback)) return
        val inputIndexes = Arguments.toList(data)
        if (inputIndexes != null) {
            val size = musicService.tracks.size
            val indexes: ArrayList<Int> = ArrayList();
            for (inputIndex in inputIndexes) {
                val index = if (inputIndex is Int) inputIndex else inputIndex.toString().toInt()
                if (index < 0 || index >= size) {
                    callback.reject(
                        "index_out_of_bounds",
                        "One or more indexes was out of bounds"
                    )
                    return
                }
                indexes.add(index)
            }
            musicService.remove(indexes)
        }
        callback.resolve(null)
    }

    override fun updateMetadataForTrack(index: Double, map: ReadableMap?, callback: Promise) =
        run {
            if (verifyServiceBoundOrReject(callback)) return

            if (index < 0 || index >= musicService.tracks.size) {
                callback.reject("index_out_of_bounds", "The index is out of bounds")
            } else {
                val context: ReactContext = context
                val track = musicService.tracks[index.toInt()]
                track.setMetadata(context, Arguments.toBundle(map), musicService.ratingType)
                musicService.updateMetadataForTrack(index.toInt(), track)

                callback.resolve(null)
            }
        }

    override fun updateNowPlayingMetadata(map: ReadableMap?, callback: Promise) = run {
        if (verifyServiceBoundOrReject(callback)) return

        if (musicService.tracks.isEmpty())
            callback.reject("no_current_item", "There is no current item in the player")

        val context: ReactContext = context
        Arguments.toBundle(map)?.let {
            val track = bundleToTrack(it)
            musicService.updateNowPlayingMetadata(track)
        }

        callback.resolve(null)
    }

    override fun clearNowPlayingMetadata(callback: Promise) = run {
        if (verifyServiceBoundOrReject(callback)) return

        if (musicService.tracks.isEmpty())
            callback.reject("no_current_item", "There is no current item in the player")

        musicService.clearNotificationMetadata()
        callback.resolve(null)
    }

    override fun removeUpcomingTracks(callback: Promise) = run {
        if (verifyServiceBoundOrReject(callback)) return

        musicService.removeUpcomingTracks()
        callback.resolve(null)
    }

    override fun skip(index: Double, initialTime: Double?, callback: Promise) = run {
        if (verifyServiceBoundOrReject(callback)) return

        musicService.skip(index.toInt())

        if ((initialTime?.toInt() ?: 0) >= 0) {
            musicService.seekTo((initialTime?.toInt() ?: 0).toFloat())
        }

        callback.resolve(null)
    }

    override fun skipToNext(initialTime: Double?, callback: Promise) = run {
        if (verifyServiceBoundOrReject(callback)) return

        musicService.skipToNext()

        if ((initialTime?.toInt() ?: 0) >= 0) {
            musicService.seekTo((initialTime?.toInt() ?: 0).toFloat())
        }

        callback.resolve(null)
    }

    override fun skipToPrevious(initialTime: Double?, callback: Promise) = run {
        if (verifyServiceBoundOrReject(callback)) return

        musicService.skipToPrevious()

        if ((initialTime?.toInt() ?: 0) >= 0) {
            musicService.seekTo((initialTime?.toInt() ?: 0).toFloat())
        }

        callback.resolve(null)
    }

    override fun reset(callback: Promise): Unit = run {
        if (verifyServiceBoundOrReject(callback)) return

        CoroutineScope(Dispatchers.Main).launch {
            musicService.stop()
            delay(300) // Allow playback to stop
            musicService.clear()

            callback.resolve(null)
        }
    }

    override fun play(callback: Promise) = run {
        if (verifyServiceBoundOrReject(callback)) return

        musicService.play()
        callback.resolve(null)
    }

    override fun pause(callback: Promise) = run {
        if (verifyServiceBoundOrReject(callback)) return

        musicService.pause()
        callback.resolve(null)
    }

    override fun stop(callback: Promise) = run {
        if (verifyServiceBoundOrReject(callback)) return

        musicService.stop()
        callback.resolve(null)
    }

    override fun seekTo(seconds: Double, callback: Promise) = run {
        if (verifyServiceBoundOrReject(callback)) return

        musicService.seekTo(seconds.toFloat())
        callback.resolve(null)
    }

    override fun seekBy(offset: Double, callback: Promise) = run {
        if (verifyServiceBoundOrReject(callback)) return

        musicService.seekBy(offset.toFloat())
        callback.resolve(null)
    }

    override fun retry(callback: Promise) = run {
        if (verifyServiceBoundOrReject(callback)) return

        musicService.retry()
        callback.resolve(null)
    }

    override fun setVolume(volume: Double, callback: Promise) = run {
        if (verifyServiceBoundOrReject(callback)) return

        musicService.setVolume(volume.toFloat())
        callback.resolve(null)
    }

    override fun getVolume(callback: Promise) = run {
        if (verifyServiceBoundOrReject(callback)) return

        callback.resolve(musicService.getVolume())
    }

    override fun setRate(rate: Double, callback: Promise) = run {
        if (verifyServiceBoundOrReject(callback)) return

        callback.resolve(musicService.setRate(rate.toFloat()))
    }

    override fun getRate(callback: Promise) = run {
        if (verifyServiceBoundOrReject(callback)) return

        callback.resolve(musicService.getRate())
    }

    override fun setRepeatMode(mode: Double, callback: Promise) = run {
        if (verifyServiceBoundOrReject(callback)) return

        musicService.setRepeatMode(RepeatMode.fromOrdinal(mode.toInt()))
        callback.resolve(null)
    }

    override fun getRepeatMode(callback: Promise) = run {
        if (verifyServiceBoundOrReject(callback)) return

        callback.resolve(musicService.getRepeatMode().ordinal)
    }

    override fun setPlayWhenReady(playWhenReady: Boolean, callback: Promise) = run {
        if (verifyServiceBoundOrReject(callback)) return

        musicService.playWhenReady = playWhenReady
        callback.resolve(null)
    }

    override fun getPlayWhenReady(callback: Promise) = run {
        if (verifyServiceBoundOrReject(callback)) return

        callback.resolve(musicService.playWhenReady)
    }

    override fun getTrack(index: Double, callback: Promise) = run {
        if (verifyServiceBoundOrReject(callback)) return

        if (index >= 0 && index < musicService.tracks.size) {
            callback.resolve(Arguments.fromBundle(musicService.tracks[index.toInt()].originalItem))
        } else {
            callback.resolve(null)
        }
    }

    override fun getQueue(callback: Promise) = run {
        if (verifyServiceBoundOrReject(callback)) return

        callback.resolve(Arguments.fromList(musicService.tracks.map { it.originalItem }))
    }

    override fun setQueue(data: ReadableArray?, callback: Promise) = run {
        if (verifyServiceBoundOrReject(callback)) return

        try {
            musicService.clear()
            musicService.add(readableArrayToTrackList(data))
            callback.resolve(null)
        } catch (exception: Exception) {
            rejectWithException(callback, exception)
        }
    }

    override fun getActiveTrackIndex(callback: Promise) = run {
        if (verifyServiceBoundOrReject(callback)) return
        callback.resolve(
            if (musicService.tracks.isEmpty()) null else musicService.getCurrentTrackIndex()
        )
    }

    override fun getActiveTrack(callback: Promise) = run {
        if (verifyServiceBoundOrReject(callback)) return
        callback.resolve(
            if (musicService.tracks.isEmpty()) null
            else Arguments.fromBundle(
                musicService.tracks[musicService.getCurrentTrackIndex()].originalItem
            )
        )
    }

    override fun getDuration(callback: Promise) = run {
        if (verifyServiceBoundOrReject(callback)) return

        callback.resolve(musicService.getDurationInSeconds())
    }

    override fun getBufferedPosition(callback: Promise) = run {
        if (verifyServiceBoundOrReject(callback)) return

        callback.resolve(musicService.getBufferedPositionInSeconds())
    }

    override fun getPosition(callback: Promise) = run {
        if (verifyServiceBoundOrReject(callback)) return

        callback.resolve(musicService.getPositionInSeconds())
    }

    override fun getProgress(callback: Promise) = run {
        if (verifyServiceBoundOrReject(callback)) return
        var bundle = Bundle()
        bundle.putDouble("duration", musicService.getDurationInSeconds());
        bundle.putDouble("position", musicService.getPositionInSeconds());
        bundle.putDouble("buffered", musicService.getBufferedPositionInSeconds());
        callback.resolve(Arguments.fromBundle(bundle))
    }

    override fun getPlaybackState(callback: Promise) = run {
        if (verifyServiceBoundOrReject(callback)) return
        callback.resolve(Arguments.fromBundle(musicService.getPlayerStateBundle(musicService.state)))
    }
}
