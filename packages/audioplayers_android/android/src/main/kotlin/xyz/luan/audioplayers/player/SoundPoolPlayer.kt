package xyz.luan.audioplayers.player

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import xyz.luan.audioplayers.AudioContextAndroid
import xyz.luan.audioplayers.AudioplayersPlugin
import xyz.luan.audioplayers.LogLevel
import xyz.luan.audioplayers.source.Source
import xyz.luan.audioplayers.source.UrlSource
import java.util.Collections.synchronizedMap

// TODO(luan): make this configurable
private const val MAX_STREAMS = 100

class SoundPoolPlayer(
    val wrappedPlayer: WrappedPlayer,
    private val soundPoolWrapper: SoundPoolWrapper,
) : Player {

    /** The id of the sound of source which will be played */
    var soundId: Int? = null

    /** The id of the stream / player */
    var streamId: Int? = null

    val urlSource: UrlSource?
        get() = wrappedPlayer.source as? UrlSource

    override fun stop() {
        streamId?.let {
            soundPoolWrapper.soundPool.stop(it)
            streamId = null
        }
    }

    override fun release() {
        stop()
        val soundId = this.soundId ?: return
        val urlSource = this.urlSource ?: return

        synchronized(soundPoolWrapper.urlToPlayers) {
            val playersForSoundId = soundPoolWrapper.urlToPlayers[urlSource] ?: return
            if (playersForSoundId.singleOrNull() === this) {
                soundPoolWrapper.urlToPlayers.remove(urlSource)
                soundPoolWrapper.soundPool.unload(soundId)
                soundPoolWrapper.soundIdToPlayer.remove(soundId)
                this.soundId = null
                wrappedPlayer.onLog("unloaded soundId $soundId", LogLevel.INFO)
            } else {
                // This is not the last player using the soundId, just remove it from the list.
                playersForSoundId.remove(this)
            }

        }
    }

    override fun pause() {
        streamId?.let { soundPoolWrapper.soundPool.pause(it) }
    }

    override fun updateContext(context: AudioContextAndroid) {
        // no-op
    }

    override fun setSource(source: Source) {
        source.setForSoundPool(this)
    }

    fun setUrlSource(urlSource: UrlSource) {
        if (soundId != null) {
            release()
        }
        synchronized(soundPoolWrapper.urlToPlayers) {
            val urlPlayers = soundPoolWrapper.urlToPlayers.getOrPut(urlSource) { mutableListOf() }
            val originalPlayer = urlPlayers.firstOrNull()

            if (originalPlayer != null) {
                // Sound has already been loaded - reuse the soundId.
                val prepared = originalPlayer.wrappedPlayer.prepared
                wrappedPlayer.prepared = prepared
                soundId = originalPlayer.soundId
                wrappedPlayer.onLog(
                    "Reusing soundId $soundId for $urlSource is prepared=$prepared $this",
                    LogLevel.INFO
                )
            } else {
                // First one for this URL - load it.
                val start = System.currentTimeMillis()

                wrappedPlayer.prepared = false
                wrappedPlayer.onLog("Fetching actual URL for $urlSource", LogLevel.INFO)
                val actualUrl = urlSource.getAudioPathForSoundPool()
                wrappedPlayer.onLog("Now loading $actualUrl", LogLevel.INFO)
                val intSoundId = soundPoolWrapper.soundPool.load(actualUrl, 1)
                soundPoolWrapper.soundIdToPlayer[intSoundId] = this
                soundId = intSoundId

                wrappedPlayer.onLog(
                    "time to call load() for $urlSource: ${System.currentTimeMillis() - start} player=$this",
                    LogLevel.INFO
                )
            }
            urlPlayers.add(this)
        }
    }

    override fun setVolume(volume: Float) {
        streamId?.let { soundPoolWrapper.soundPool.setVolume(it, volume, volume) }
    }

    override fun setRate(rate: Float) {
        streamId?.let { soundPoolWrapper.soundPool.setRate(it, rate) }
    }

    override fun setLooping(looping: Boolean) {
        streamId?.let { soundPoolWrapper.soundPool.setLoop(it, looping.loopModeInteger()) }
    }

    // Cannot get duration for Sound Pool
    override fun getDuration() = null

    // Cannot get current position for Sound Pool
    override fun getCurrentPosition() = null

    override fun isActuallyPlaying() = false

    override fun seekTo(position: Int) {
        if (position == 0) {
            streamId?.let {
                stop()
                if (wrappedPlayer.playing) {
                    soundPoolWrapper.soundPool.resume(it)
                }
            }
        } else {
            unsupportedOperation("seek")
        }
    }

    override fun start() {
        val streamId = streamId
        val soundId = soundId

        if (streamId != null) {
            soundPoolWrapper.soundPool.resume(streamId)
        } else if (soundId != null) {
            this.streamId = soundPoolWrapper.soundPool.play(
                soundId,
                wrappedPlayer.volume,
                wrappedPlayer.volume,
                0,
                wrappedPlayer.isLooping.loopModeInteger(),
                wrappedPlayer.rate,
            )
        }
    }

    override fun prepare() {
        // sound pool automatically prepares when source URL is set
    }

    override fun reset() {
        // TODO(luan): what do I do here?
    }

    override fun isLiveStream() = false

    /** Integer representation of the loop mode used by Android */
    private fun Boolean.loopModeInteger(): Int = if (this) -1 else 0

    private fun unsupportedOperation(message: String): Nothing {
        throw UnsupportedOperationException("LOW_LATENCY mode does not support: $message")
    }
}

class SoundPoolWrapper(
    private val ref: AudioplayersPlugin,
) {
    val soundPool = createSoundPool()

    /** For the onLoadComplete listener, track which sound id is associated with which player. An entry only exists until
     * it has been loaded.
     */
    val soundIdToPlayer: MutableMap<Int, SoundPoolPlayer> = synchronizedMap(mutableMapOf<Int, SoundPoolPlayer>())

    /** This is to keep track of the players which share the same sound id, referenced by url. When a player release()s, it
     * is removed from the associated player list. The last player to be removed actually unloads() the sound id and then
     * the url is removed from this map.
     */
    val urlToPlayers: MutableMap<UrlSource, MutableList<SoundPoolPlayer>> =
        synchronizedMap(mutableMapOf<UrlSource, MutableList<SoundPoolPlayer>>())

    private fun createSoundPool(): SoundPool {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // TODO(luan) this should consider updateAttributes configs. we would need one pool per config
            val attrs = AudioAttributes.Builder().setLegacyStreamType(AudioManager.USE_DEFAULT_STREAM_TYPE)
                .setUsage(AudioAttributes.USAGE_GAME)
                .build()
            // make a new SoundPool, allowing up to 100 streams
            SoundPool.Builder()
                .setAudioAttributes(attrs)
                .setMaxStreams(MAX_STREAMS)
                .build()
        } else {
            // make a new SoundPool, allowing up to 100 streams
            @Suppress("DEPRECATION")
            SoundPool(MAX_STREAMS, AudioManager.STREAM_MUSIC, 0)
        }
    }

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, _ ->
            ref.handleGlobalLog("Loaded $sampleId", LogLevel.INFO)
            val loadingPlayer = soundIdToPlayer[sampleId]
            val urlSource = loadingPlayer?.urlSource
            if (urlSource != null) {
                soundIdToPlayer.remove(loadingPlayer.soundId)
                // Now mark all players using this sound as not loading and start them if necessary
                synchronized(urlToPlayers) {
                    val urlPlayers = urlToPlayers[urlSource] ?: listOf()
                    for (player in urlPlayers) {
                        player.wrappedPlayer.onLog("Marking $player as loaded", LogLevel.INFO)
                        player.wrappedPlayer.prepared = true
                        if (player.wrappedPlayer.playing) {
                            player.wrappedPlayer.onLog("Delayed start of $player", LogLevel.INFO)
                            player.start()
                        }
                    }
                }
            }
        }
    }
    
    fun dispose() {
        soundPool.release()
        soundIdToPlayer.clear()
        urlToPlayers.clear()
    }
}
