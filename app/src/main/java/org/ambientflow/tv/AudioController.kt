package org.ambientflow.tv

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.StreamKey
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native, best-effort background audio; never resumes after losing audio priority.
 * All state and status callbacks are confined to the main looper; public calls may use any thread.
 * Blank sources (and the legacy "youtube" alias) use [YOUTUBE_URL]. Volume is clamped to 0..1;
 * muting releases playback and focus. Call [release] when the owning screen/service is destroyed.
 */
@UnstableApi
class AudioController(context: Context, status: (String) -> Unit) {
    private val onStatus = status
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audioManager = this.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val worker = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "AmbientFlow-audio").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private var released = false
    private var wanted = false
    private var source = YOUTUBE_URL
    private var level = 0f
    private var muted = false
    private var generation = 0L
    private var failures = 0
    private var player: ExoPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var extraction: Future<*>? = null
    private var network: ExtractionScope? = null
    private var retry: Runnable? = null
    private var deadline: Runnable? = null
    private var stable: Runnable? = null
    private var alternatives: List<Resolved> = emptyList()

    fun start(source: String = YOUTUBE_URL, volume: Float, muted: Boolean) = onMain {
        if (!released) {
            clearAttempt()
            this.source = source.trim().ifEmpty { YOUTUBE_URL }
            this.level = normalized(volume)
            this.muted = muted
            wanted = true
            failures = 0
            if (audible()) begin() else status("Audio muted")
        }
    }

    fun setVolume(volume: Float, muted: Boolean) = onMain {
        if (!released) {
            val wasAudible = audible()
            level = normalized(volume)
            this.muted = muted
            if (!audible()) {
                clearAttempt()
                status("Audio muted")
            } else if (!wasAudible && wanted) {
                failures = 0
                begin()
            } else {
                player?.volume = level
            }
        }
    }

    fun stop() = onMain {
        if (!released) {
            wanted = false
            clearAttempt()
            status("Audio stopped")
        }
    }

    fun release() = onMain {
        if (!released) {
            released = true
            wanted = false
            clearAttempt()
            worker.shutdownNow()
        }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == main.looper) action() else main.post { action() }
    }

    private fun normalized(value: Float) = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
    private fun audible() = !muted && level > 0f
    private fun status(message: String) { if (!released) onStatus(message) }
    private fun current(token: Long) = !released && wanted && audible() && token == generation

    private fun clearAttempt() {
        generation++
        retry?.let(main::removeCallbacks)
        deadline?.let(main::removeCallbacks)
        stable?.let(main::removeCallbacks)
        retry = null
        deadline = null
        stable = null
        alternatives = emptyList()
        network?.cancel()
        network = null
        extraction?.cancel(true)
        extraction = null
        player?.release()
        player = null
        focus?.let { audioManager.abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun yieldToOtherAudio() {
        wanted = false
        clearAttempt()
        status("Audio idle: another app has audio priority; start again to resume")
    }

    private fun begin() {
        if (released || !wanted || !audible()) return
        if (audioManager.isMusicActive) {
            yieldToOtherAudio()
            return
        }
        val token = generation
        status("Audio connecting")
        // Bounds extraction (which may make many individually bounded requests) and preparation.
        deadline = Runnable { if (current(token)) failed(token, "connection timed out") }
            .also { main.postDelayed(it, 60_000) }
        val requestedSource = source
        val uri = Uri.parse(requestedSource)
        val youtube = requestedSource.equals("youtube", ignoreCase = true) || isYoutubePage(uri)
        if (youtube) {
            val scope = ExtractionScope()
            network = scope
            extraction = worker.submit {
                try {
                    ExtractorDownloader.initialize()
                    ExtractorDownloader.scope.set(scope)
                    scope.check()
                    val info = StreamInfo.getInfo(ServiceList.YouTube,
                        if (requestedSource.equals("youtube", true)) YOUTUBE_URL else requestedSource)
                    scope.check()
                    val candidates = resolve(info)
                    if (candidates.isEmpty()) {
                        throw IOException("No HTTPS audio stream or HLS/DASH manifest")
                    }
                    main.post {
                        if (current(token)) {
                            alternatives = candidates.drop(1)
                            prepare(token, candidates.first())
                        }
                    }
                } catch (e: Exception) {
                    if (!scope.cancelled.get()) main.post {
                        if (current(token)) failed(token, extractionError(e))
                    }
                } finally {
                    ExtractorDownloader.scope.remove()
                    scope.cancel()
                }
            }
        } else {
            if (uri.scheme != "https" && uri.scheme != "content") {
                wanted = false
                clearAttempt()
                status("Audio source must be youtube, HTTPS, or a content URI")
                return
            }
            val mime = when {
                uri.lastPathSegment?.endsWith(".mpd", true) == true -> MimeTypes.APPLICATION_MPD
                uri.lastPathSegment?.endsWith(".m3u8", true) == true -> MimeTypes.APPLICATION_M3U8
                else -> null
            }
            prepare(token, Resolved(source, mime))
        }
    }

    private fun prepare(token: Long, resolved: Resolved) {
        if (!current(token)) return
        try {
            val data = DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory()
                .setUserAgent(USER_AGENT)
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(15_000)
                .setAllowCrossProtocolRedirects(false))
            val item = MediaItem.Builder().setUri(resolved.url).setMimeType(resolved.mime).build()
            val mediaSource: MediaSource = if (resolved.mime == MimeTypes.APPLICATION_MPD) {
                DashMediaSource.Factory(data)
                    .setManifestParser { uri: Uri, input: InputStream ->
                        audioOnly(DashManifestParser().parse(uri, input))
                    }
                    .createMediaSource(item)
            } else {
                DefaultMediaSourceFactory(data).createMediaSource(item)
            }
            // Construct only an audio renderer, rather than creating a video decoder and hiding it.
            val renderers = RenderersFactory { handler, _, listener, _, _ ->
                arrayOf(MediaCodecAudioRenderer(context, MediaCodecSelector.DEFAULT, handler, listener))
            }
            val instance = ExoPlayer.Builder(context, renderers).setLooper(main.looper).build()
            player = instance
            instance.trackSelectionParameters = instance.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            instance.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
            instance.volume = level
            instance.addListener(object : Player.Listener {
                private var started = false

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (!current(token) || player !== instance) return
                    if (playbackState == Player.STATE_READY && !started) {
                        if (!instance.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO)) {
                            failed(token, "source has no playable audio track")
                            return
                        }
                        // No delayed focus grant, no automatic resume, and no ducking another app.
                        if (audioManager.isMusicActive) {
                            yieldToOtherAudio()
                            return
                        }
                        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                            .setAudioAttributes(android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                            .setAcceptsDelayedFocusGain(false)
                            .setWillPauseWhenDucked(true)
                            .setOnAudioFocusChangeListener({ change ->
                                if (current(token) && change != AudioManager.AUDIOFOCUS_GAIN) {
                                    yieldToOtherAudio()
                                }
                            }, main).build()
                        focus = request
                        val granted = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                        if (!granted || audioManager.isMusicActive) {
                            yieldToOtherAudio()
                            return
                        }
                        started = true
                        deadline?.let(main::removeCallbacks)
                        deadline = null
                        instance.play()
                    } else if (playbackState == Player.STATE_ENDED) {
                        wanted = false
                        clearAttempt()
                        status("Audio ended")
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (!current(token) || player !== instance) return
                    stable?.let(main::removeCallbacks)
                    stable = null
                    deadline?.let(main::removeCallbacks)
                    deadline = null
                    if (isPlaying) {
                        status("Audio playing")
                        stable = Runnable { if (current(token)) failures = 0 }
                            .also { main.postDelayed(it, 60_000) }
                    } else if (started && instance.playbackState != Player.STATE_ENDED) {
                        status("Audio buffering")
                        deadline = Runnable { if (current(token)) failed(token, "playback stalled") }
                            .also { main.postDelayed(it, 60_000) }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    failed(token, error.errorCodeName)
                }
            })
            instance.setMediaSource(mediaSource)
            instance.prepare()
        } catch (e: Exception) {
            failed(token, e.javaClass.simpleName)
        }
    }

    private fun failed(token: Long, reason: String) {
        if (!current(token)) return
        // Try the remaining extracted formats/manifests before re-extracting signed URLs.
        val remaining = alternatives
        clearAttempt()
        if (remaining.isNotEmpty()) {
            alternatives = remaining.drop(1)
            val next = generation
            status("Audio trying alternate stream: $reason")
            deadline = Runnable { if (current(next)) failed(next, "connection timed out") }
                .also { main.postDelayed(it, 60_000) }
            prepare(next, remaining.first())
            return
        }
        failures++
        if (failures > 5) {
            wanted = false
            status("Audio unavailable: $reason; start again to retry")
            return
        }
        val delay = 2_000L shl (failures - 1)
        val next = generation
        status("Audio retry in ${delay / 1000}s: $reason")
        retry = Runnable { if (current(next)) { retry = null; begin() } }
            .also { main.postDelayed(it, delay) }
    }

    private data class Resolved(val url: String, val mime: String?)

    companion object {
        private const val YOUTUBE_URL = "https://www.youtube.com/watch?v=rFZHOHl-L8A"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36"

        private fun isYoutubePage(uri: Uri): Boolean {
            if (uri.scheme != "https") return false
            val host = uri.host.orEmpty().lowercase(java.util.Locale.ROOT)
            val youtube = host == "youtube.com" || host.endsWith(".youtube.com") ||
                host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com")
            return host == "youtu.be" || (youtube && (uri.path == "/watch" ||
                listOf("/live/", "/shorts/", "/embed/").any { uri.path.orEmpty().startsWith(it) }))
        }

        private fun resolve(info: StreamInfo): List<Resolved> {
            val streams = info.audioStreams.orEmpty()
                .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                .filter { Uri.parse(it.content).scheme == "https" }
                .sortedByDescending { it.averageBitrate }
                .take(3)
                .map { Resolved(it.content, it.format?.mimeType) }
                .toMutableList()
            // Live/OTF AudioStream.content may be a segment endpoint, not a manifest URL.
            // Prefer DASH: audioOnly() prunes video representations before segment loading.
            // HLS can be muxed, even with video tracks and video rendering disabled.
            info.dashMpdUrl?.takeIf { Uri.parse(it).scheme == "https" }?.let {
                streams += Resolved(it, MimeTypes.APPLICATION_MPD)
            }
            info.hlsUrl?.takeIf { Uri.parse(it).scheme == "https" }?.let {
                streams += Resolved(it, MimeTypes.APPLICATION_M3U8)
            }
            return streams.distinctBy { it.url }
        }

        private fun extractionError(error: Exception): String = when (error) {
            is ReCaptchaException -> "YouTube requires CAPTCHA/sign-in"
            else -> "YouTube extraction failed (${error.javaClass.simpleName})"
        }

        private fun audioOnly(manifest: DashManifest): DashManifest {
            val keys = mutableListOf<StreamKey>()
            for (periodIndex in 0 until manifest.periodCount) {
                val sets = manifest.getPeriod(periodIndex).adaptationSets
                var found = false
                sets.forEachIndexed { setIndex, set ->
                    if (set.type == C.TRACK_TYPE_AUDIO) {
                        set.representations.forEachIndexed { representationIndex, _ ->
                            keys += StreamKey(periodIndex, setIndex, representationIndex)
                            found = true
                        }
                    }
                }
                if (!found) throw IOException("DASH period has no audio representations")
            }
            if (keys.isEmpty()) throw IOException("DASH manifest has no audio")
            return manifest.copy(keys)
        }

        private class ExtractionScope {
            val cancelled = AtomicBoolean(false)
            val calls: MutableSet<Call> = ConcurrentHashMap.newKeySet()
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .callTimeout(25, TimeUnit.SECONDS)
                .followSslRedirects(false)
                .build()
            fun check() {
                if (cancelled.get() || Thread.currentThread().isInterrupted) throw IOException("Cancelled")
            }
            fun cancel() {
                cancelled.set(true)
                calls.forEach { it.cancel() }
                client.dispatcher.cancelAll()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdownNow()
            }
        }

        /** NewPipe uses a process-global downloader; ThreadLocal routes cancellation to its owner. */
        private object ExtractorDownloader : Downloader() {
            val scope = ThreadLocal<ExtractionScope>()
            private var initialized = false

            @Synchronized fun initialize() {
                if (!initialized) {
                    NewPipe.init(this)
                    initialized = true
                }
            }

            override fun execute(request: Request): Response {
                val owner = scope.get() ?: throw IOException("Extraction outside audio worker")
                owner.check()
                val builder = okhttp3.Request.Builder().url(request.url())
                    .header("User-Agent", USER_AGENT)
                request.headers().forEach { (name, values) ->
                    builder.removeHeader(name)
                    values.forEach { builder.addHeader(name, it) }
                }
                val bytes = request.dataToSend()
                val body = if (bytes != null) bytes.toRequestBody(null) else when (request.httpMethod()) {
                    "POST", "PUT", "PATCH" -> ByteArray(0).toRequestBody(null)
                    else -> null
                }
                val call = owner.client.newCall(builder.method(request.httpMethod(), body).build())
                owner.calls.add(call)
                try {
                    owner.check()
                    call.execute().use { response ->
                        if (response.code == 429) throw ReCaptchaException("Rate limited", request.url())
                        return Response(response.code, response.message, response.headers.toMultimap(),
                            response.body?.string().orEmpty(), response.request.url.toString())
                    }
                } finally {
                    owner.calls.remove(call)
                }
            }
        }
    }
}
