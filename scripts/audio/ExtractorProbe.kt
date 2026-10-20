package org.ambientflow.tv

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException

/** JVM network smoke test using the production downloader, without constructing Android objects. */
fun main(args: Array<String>) {
    val url = args.firstOrNull() ?: "https://www.youtube.com/watch?v=rFZHOHl-L8A"
    // Reflection keeps the implementation private rather than adding a production test-only API.
    val prefix = AudioController::class.java.name + "\$Companion\$"
    val downloaderClass = Class.forName(prefix + "ExtractorDownloader")
    val downloader = downloaderClass.getDeclaredField("INSTANCE").apply { isAccessible = true }
        .get(null) as Downloader
    val scopeClass = Class.forName(prefix + "ExtractionScope")
    val scope = scopeClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
    @Suppress("UNCHECKED_CAST")
    val local = downloaderClass.getDeclaredMethod("getScope").apply { isAccessible = true }
        .invoke(downloader) as ThreadLocal<Any>
    val cancel = scopeClass.getDeclaredMethod("cancel").apply { isAccessible = true }
    local.set(scope)
    NewPipe.init(downloader)
    try {
        val info = StreamInfo.getInfo(ServiceList.YouTube, url)
        val audio = info.audioStreams.orEmpty().filter {
            it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                it.content.startsWith("https://")
        }
        println("EXTRACTION_OK title=${info.name} type=${info.streamType}")
        println("progressiveAudio=${audio.size} hls=${!info.hlsUrl.isNullOrBlank()} dash=${!info.dashMpdUrl.isNullOrBlank()}")
        val streamUrl = audio.maxByOrNull { it.averageBitrate }?.content
            ?: info.dashMpdUrl?.takeIf { it.startsWith("https://") }
            ?: info.hlsUrl?.takeIf { it.startsWith("https://") }
            ?: error("No supported audio URL or manifest")
        // Do not print signed URLs. A bounded range also works for servers that reject HEAD.
        val response = downloader.execute(Request.newBuilder().get(streamUrl)
            .setHeader("Range", "bytes=0-1023").build())
        check(response.responseCode() in 200..299) { "Stream HTTP ${response.responseCode()}" }
        check(response.responseBody().isNotEmpty()) { "Empty stream response" }
        println("STREAM_HTTP_OK code=${response.responseCode()} nonempty=true")
        info.hlsUrl?.takeIf { it.startsWith("https://") }?.let { hls ->
            val master = downloader.get(hls)
            check(master.responseCode() in 200..299) { "HLS HTTP ${master.responseCode()}" }
            check(master.responseBody().startsWith("#EXTM3U")) { "Not an HLS manifest" }
            val lines = master.responseBody().lineSequence().map { it.trim() }.toList()
            val renditions = lines.count { it.startsWith("#EXT-X-MEDIA:") && it.contains("TYPE=AUDIO") }
            println("HLS_MANIFEST_OK code=${master.responseCode()} audioRenditions=$renditions")
            val audioOnly = lines.indices.filter { i ->
                val line = lines[i]
                line.startsWith("#EXT-X-STREAM-INF:") && !line.contains("RESOLUTION=") &&
                    (line.contains("mp4a") || line.contains("opus")) &&
                    listOf("avc1", "av01", "hev1", "hvc1", "vp09").none { line.contains(it) }
            }
            println("HLS_AUDIO_ONLY_VARIANTS count=${audioOnly.size}")
            audioOnly.firstOrNull()?.let { index ->
                val child = lines.drop(index + 1).first { it.isNotEmpty() && !it.startsWith("#") }
                val childUrl = java.net.URI(hls).resolve(child).toString()
                val playlist = downloader.get(childUrl)
                check(playlist.responseCode() in 200..299) { "Audio playlist HTTP ${playlist.responseCode()}" }
                check(playlist.responseBody().contains("#EXTINF:")) { "No audio segments in playlist" }
                println("HLS_AUDIO_PLAYLIST_OK code=${playlist.responseCode()} segments=true")
            }
        }
        info.dashMpdUrl?.takeIf { it.startsWith("https://") }?.let { dash ->
            val manifest = downloader.get(dash)
            check(manifest.responseCode() in 200..299) { "DASH HTTP ${manifest.responseCode()}" }
            check(manifest.responseBody().contains("<MPD")) { "Not a DASH manifest" }
            println("DASH_MANIFEST_OK code=${manifest.responseCode()} audio=${manifest.responseBody().contains("audio/")}")
        }
    } finally {
        cancel.invoke(scope)
        try {
            downloader.execute(Request.newBuilder().get(url).build())
            error("Cancelled extraction accepted a new request")
        } catch (_: IOException) {
            println("CANCELLATION_OK new requests rejected after session cancellation")
        }
        local.remove()
    }
}
