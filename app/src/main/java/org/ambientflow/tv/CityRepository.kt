package org.ambientflow.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.text.Html
import android.util.AtomicFile
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/** Attribution belongs with the displayed photograph, including while it is crossfading. */
data class CityPhoto(
    val id: String,
    val title: String,
    val creator: String,
    val license: String,
    val licenseUrl: String,
    val sourceUrl: String,
    val city: String,
    val file: File,
)

/**
 * Blocking, worker-thread-only repository. No executor, UI work, or speculative image downloads.
 * Call close() before clear(). Files are private and persistent, not subject to cacheDir eviction.
 * Multiple instances serialize cache access; close() can still cancel a blocked HTTP call.
 */
class CityRepository(context: Context) : AutoCloseable {
    private val directory = File(context.applicationContext.filesDir, CACHE_DIRECTORY)
    private val index = AtomicFile(File(directory, "index.json"))
    private val closed = AtomicBoolean(false)
    private val httpLock = Any()
    private var inFlight: Call? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        // Do not follow a server redirect to an unverified host or downgrade to HTTP.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val pending = mutableMapOf<String, ArrayDeque<Candidate>>()
    private val offsets = mutableMapOf<String, Int>()
    private val rejected = LinkedHashSet<String>()
    private var cityCursor = 0
    private var retryAfter = 0L

    init {
        synchronized(cacheLock) {
            active[directory.absolutePath] = (active[directory.absolutePath] ?: 0) + 1
        }
    }

    /** Empty city selections return null. Network/IO failures fall back to a matching cached photo. */
    fun next(cities: Set<String>, currentId: String?): CityPhoto? = synchronized(cacheLock) {
        if (closed.get()) return@synchronized null
        val selected = cities.map { it.trim() }.filter { it.isNotEmpty() && it.length <= 120 }
            .distinct().sorted()
        pending.keys.retainAll(selected.toSet())
        offsets.keys.retainAll(selected.toSet())
        if (selected.isEmpty()) return@synchronized null
        try {
            if (!directory.isDirectory && !directory.mkdirs()) return@synchronized null
            val entries = load()
            val city = selected[(cityCursor.toLong() and 0x7fffffffL).rem(selected.size).toInt()]
            cityCursor++
            val matching = entries.filter { it.city in selected }
            val cityEntries = matching.filter { it.city == city }
            val quota = (MAX_PHOTOS / selected.size).coerceAtLeast(2)
            val cached = cityEntries.firstOrNull { it.id != currentId }
                ?: matching.firstOrNull { it.id != currentId }
                ?: matching.firstOrNull()
            if ((cityEntries.size >= quota && cached?.city == city && cached.id != currentId) ||
                SystemClock.elapsedRealtime() < retryAfter
            ) return@synchronized useCached(entries, cached)

            try {
                val queue = pending.getOrPut(city) { ArrayDeque() }
                if (queue.isEmpty()) queue.addAll(search(city))
                var candidate: Candidate? = null
                while (queue.isNotEmpty()) {
                    val item = queue.removeFirst()
                    if (item.photo.id != currentId && item.photo.id !in rejected &&
                        entries.none { it.id == item.photo.id }
                    ) {
                        candidate = item
                        break
                    }
                }
                // One metadata request and at most one image download per invocation.
                if (candidate != null && !closed.get()) {
                    reserve(entries, currentId)
                    val photo = candidate.photo
                    try {
                        download(candidate)
                    } catch (e: IOException) {
                        rejected.add(photo.id)
                        if (rejected.size > 128) rejected.remove(rejected.first())
                        throw e
                    }
                    if (closed.get()) {
                        photo.file.delete()
                        return@synchronized null
                    }
                    entries.add(photo)
                    if (!save(entries)) {
                        entries.remove(photo)
                        photo.file.delete()
                    } else {
                        return@synchronized photo
                    }
                }
            } catch (_: IOException) {
                retryAfter = SystemClock.elapsedRealtime() + RETRY_DELAY_MS
            } catch (_: org.json.JSONException) {
                retryAfter = SystemClock.elapsedRealtime() + RETRY_DELAY_MS
            }
            val remaining = entries.filter { it.city in selected && it.file.isFile }
            if (closed.get()) null else useCached(entries,
                remaining.firstOrNull { it.city == city && it.id != currentId }
                    ?: remaining.firstOrNull { it.id != currentId } ?: remaining.firstOrNull())
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    /** Bounds both intermediate sampling and the returned bitmap; never decodes an original URL. */
    fun decode(photo: CityPhoto): Bitmap? = synchronized(cacheLock) {
        if (closed.get() || photo.file.parentFile != directory || !photo.file.isFile ||
            photo.file.length() !in 1..MAX_DOWNLOAD_BYTES
        ) return@synchronized null
        try {
            val bounds = imageBounds(photo.file) ?: return@synchronized null
            var sample = 1
            while ((bounds.first.toLong() / sample) * (bounds.second.toLong() / sample) > MAX_PIXELS) {
                sample *= 2
            }
            val bitmap = BitmapFactory.decodeFile(photo.file.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: return@synchronized null
            if (closed.get()) {
                bitmap.recycle()
                return@synchronized null
            }
            // Handle codec sampling round-off as well as unusually wide panoramas.
            val scale = minOf(1.0, 1920.0 / bitmap.width, 1080.0 / bitmap.height,
                sqrt(MAX_PIXELS.toDouble() / (bitmap.width.toLong() * bitmap.height)))
            if (scale >= 1.0) bitmap else {
                try {
                    Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
                        (bitmap.height * scale).toInt().coerceAtLeast(1), true)
                } finally {
                    bitmap.recycle()
                }
            }
        } catch (_: RuntimeException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    override fun close() {
        synchronized(httpLock) {
            if (!closed.compareAndSet(false, true)) return
            inFlight?.cancel()
            client.dispatcher.cancelAll()
        }
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        synchronized(cacheLock) {
            val count = (active[directory.absolutePath] ?: 1) - 1
            if (count == 0) active.remove(directory.absolutePath) else active[directory.absolutePath] = count
            pending.clear()
        }
    }

    private data class Candidate(val photo: CityPhoto, val url: String)

    private fun search(city: String): List<Candidate> {
        // imageinfo/extmetadata is the documented Commons API, not scraped HTML or a random image API.
        // Quote the city as a literal search phrase; do not let user input inject search operators.
        val phrase = city.replace(Regex("[\\p{Cc}\\\"\\\\]"), " ").trim()
        if (phrase.isEmpty()) return emptyList()
        val offset = offsets[city] ?: 0
        val url = "https://commons.wikimedia.org/w/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("format", "json")
            .addQueryParameter("generator", "search")
            .addQueryParameter("gsrsearch", "skyline \"$phrase\" filetype:bitmap")
            .addQueryParameter("gsrnamespace", "6")
            .addQueryParameter("gsrlimit", "4")
            .addQueryParameter("gsroffset", offset.toString())
            .addQueryParameter("prop", "imageinfo")
            .addQueryParameter("iiprop", "url|size|mime|extmetadata")
            .addQueryParameter("iiurlwidth", "1920")
            .addQueryParameter("iiextmetadatalanguage", "en")
            .addQueryParameter("maxlag", "5")
            .build().toString()
        val root = JSONObject(String(requestBytes(url, MAX_METADATA_BYTES), Charsets.UTF_8))
        if (root.has("error")) throw IOException("Commons API error")
        offsets[city] = root.optJSONObject("continue")?.optInt("gsroffset", 0) ?: 0
        val pages = root.optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
        val result = mutableListOf<Pair<Int, Candidate>>()
        for (key in pages.keys()) {
            val page = pages.optJSONObject(key) ?: continue
            val id = page.optLong("pageid", -1).takeIf { it > 0 }?.toString() ?: continue
            val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
            if (info.optString("mime") !in IMAGE_MIMES) continue
            val width = info.optInt("thumbwidth", 0)
            val height = info.optInt("thumbheight", 0)
            if (width < 640 || height < 360 || width < height || width > 1920 ||
                width.toLong() * height > MAX_THUMB_PIXELS
            ) continue
            val metadata = info.optJSONObject("extmetadata") ?: continue
            fun field(name: String) = plain(metadata.optJSONObject(name)?.optString("value").orEmpty())
            val license = field("LicenseShortName")
            val licenseUrl = normalizedUrl(field("LicenseUrl"))
            if (!acceptedLicense(license, licenseUrl) || field("Restrictions").isNotBlank() ||
                field("NonFree").equals("true", true)
            ) continue
            val artist = field("Artist")
            if (artist.isBlank()) continue
            // Preserve the complete creator and any explicit attribution/credit, not just a link label.
            val creator = listOf(artist, field("Attribution"), field("Credit"))
                .filter { it.isNotBlank() }.distinct().joinToString("\n")
            val source = normalizedUrl(info.optString("descriptionurl"))
            val thumb = normalizedUrl(info.optString("thumburl"))
            if (!isSource(source) || !isImageUrl(thumb)) continue
            val title = plain(page.optString("title").removePrefix("File:"))
            if (title.isBlank()) continue
            val photo = CityPhoto(id, title, creator, license, licenseUrl, source, city, imageFile(id))
            result.add(page.optInt("index", Int.MAX_VALUE) to Candidate(photo, thumb))
        }
        return result.sortedBy { it.first }.map { it.second }
    }

    private fun download(candidate: Candidate) {
        val temp = File(directory, "download.part")
        try {
            withResponse(candidate.url) { response ->
                val body = response.body ?: throw IOException("Empty thumbnail")
                val mime = body.contentType()?.let { "${it.type}/${it.subtype}" }
                if (mime !in IMAGE_MIMES || body.contentLength() > MAX_DOWNLOAD_BYTES) {
                    throw IOException("Invalid thumbnail type or size")
                }
                FileOutputStream(temp).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(32 * 1024)
                        var total = 0L
                        while (true) {
                            if (closed.get()) throw IOException("Repository closed")
                            val count = input.read(buffer)
                            if (count == -1) break
                            total += count
                            if (total > MAX_DOWNLOAD_BYTES) throw IOException("Thumbnail exceeds 16 MiB")
                            output.write(buffer, 0, count)
                        }
                    }
                    output.fd.sync()
                }
            }
            val bounds = imageBounds(temp) ?: throw IOException("Invalid landscape bitmap")
            if (bounds.first > 1920 || bounds.first.toLong() * bounds.second > MAX_THUMB_PIXELS) {
                throw IOException("Unexpected thumbnail dimensions")
            }
            if (closed.get() || !temp.renameTo(candidate.photo.file)) throw IOException("Thumbnail not committed")
        } finally {
            temp.delete()
        }
    }

    private fun <T> withResponse(url: String, block: (okhttp3.Response) -> T): T {
        val call = synchronized(httpLock) {
            if (closed.get()) throw IOException("Repository closed")
            client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build())
                .also { inFlight = it }
        }
        try {
            return call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("Commons HTTP ${response.code}")
                block(response)
            }
        } finally {
            synchronized(httpLock) { if (inFlight === call) inFlight = null }
        }
    }

    private fun requestBytes(url: String, limit: Long): ByteArray = withResponse(url) { response ->
        val body = response.body ?: throw IOException("Empty metadata")
        if (body.contentLength() > limit) throw IOException("Metadata too large")
        body.byteStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                if (closed.get()) throw IOException("Repository closed")
                val count = input.read(buffer)
                if (count == -1) break
                if (output.size().toLong() + count > limit) throw IOException("Metadata too large")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun imageBounds(file: File): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return if (options.outWidth >= 640 && options.outHeight >= 360 &&
            options.outWidth >= options.outHeight && options.outWidth <= 32768 &&
            options.outHeight <= 32768 && options.outMimeType in IMAGE_MIMES
        ) options.outWidth to options.outHeight else null
    }

    private fun imageFile(id: String) = File(directory, "$id.img")

    private fun load(): MutableList<CityPhoto> {
        val entries = mutableListOf<CityPhoto>()
        try {
            val bytes = index.openRead().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size().toLong() + count > MAX_METADATA_BYTES) throw IOException("Index too large")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val root = JSONObject(String(bytes, Charsets.UTF_8))
            if (root.optInt("version") != 1) throw IOException("Unknown cache version")
            val array = root.getJSONArray("photos")
            for (i in 0 until minOf(array.length(), MAX_PHOTOS)) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id")
                if (!id.matches(Regex("[1-9][0-9]{0,19}")) || entries.any { it.id == id }) continue
                val file = imageFile(id)
                val photo = CityPhoto(id, item.optString("title"), item.optString("creator"),
                    item.optString("license"), item.optString("licenseUrl"), item.optString("sourceUrl"),
                    item.optString("city"), file)
                if (file.isFile && file.length() in 1..MAX_DOWNLOAD_BYTES && photo.city.isNotBlank() &&
                    photo.title.isNotBlank() && photo.creator.isNotBlank() && isSource(photo.sourceUrl) &&
                    acceptedLicense(photo.license, photo.licenseUrl)
                ) entries.add(photo)
            }
        } catch (_: IOException) {
            // Missing/interrupted/corrupt indices never turn unverified orphan files into photos.
        } catch (_: org.json.JSONException) {
            entries.clear()
        }
        while (entries.sumOf { it.file.length() } > IMAGE_BUDGET) entries.removeAt(0)
        val keep = entries.map { it.file.name }.toSet() + setOf("index.json", "index.json.bak", "index.json.new")
        directory.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
        return entries
    }

    private fun reserve(entries: MutableList<CityPhoto>, currentId: String?) {
        // Reserve the entire capped download before writing .part: even peak disk use stays bounded.
        while (entries.size >= MAX_PHOTOS || entries.sumOf { it.file.length() } > IMAGE_BUDGET - MAX_DOWNLOAD_BYTES) {
            val victim = entries.firstOrNull { it.id != currentId } ?: throw IOException("Cache is pinned")
            entries.remove(victim)
            if (!save(entries)) throw IOException("Cannot update cache index")
            if (!victim.file.delete() && victim.file.exists()) throw IOException("Cannot evict cached photo")
        }
    }

    private fun useCached(entries: MutableList<CityPhoto>, photo: CityPhoto?): CityPhoto? {
        if (photo == null || !photo.file.isFile || closed.get()) return null
        entries.remove(photo)
        entries.add(photo)
        save(entries)
        return photo
    }

    private fun save(entries: List<CityPhoto>): Boolean {
        val array = JSONArray()
        entries.forEach { photo ->
            array.put(JSONObject().put("id", photo.id).put("title", photo.title).put("creator", photo.creator)
                .put("license", photo.license).put("licenseUrl", photo.licenseUrl)
                .put("sourceUrl", photo.sourceUrl).put("city", photo.city))
        }
        val bytes = JSONObject().put("version", 1).put("photos", array).toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_METADATA_BYTES) return false
        var stream: FileOutputStream? = null
        return try {
            stream = index.startWrite()
            stream.write(bytes)
            index.finishWrite(stream)
            true
        } catch (_: IOException) {
            index.failWrite(stream)
            false
        }
    }

    companion object {
        private const val CACHE_DIRECTORY = "commons-city-photos"
        private const val MAX_PHOTOS = 12
        private const val MAX_DOWNLOAD_BYTES = 16L * 1024 * 1024
        private const val MAX_METADATA_BYTES = 512L * 1024
        // Leave space for both atomic index generations, in addition to the image download reserve.
        private const val IMAGE_BUDGET = 64L * 1024 * 1024 - 2 * MAX_METADATA_BYTES
        private const val MAX_PIXELS = 1920L * 1080
        private const val MAX_THUMB_PIXELS = 1920L * 1920
        private const val RETRY_DELAY_MS = 60_000L
        private const val USER_AGENT = "AmbientFlowTV/1.0 (Android; city slideshow; Wikimedia Commons imageinfo)"
        private val IMAGE_MIMES = setOf("image/jpeg", "image/png", "image/webp")
        private val cacheLock = Any()
        private val active = mutableMapOf<String, Int>()

        /** No-op while any repository for this private directory is running. Never deletes parent data. */
        @JvmStatic
        fun clear(context: Context) {
            val directory = File(context.applicationContext.filesDir, CACHE_DIRECTORY)
            synchronized(cacheLock) {
                if ((active[directory.absolutePath] ?: 0) != 0) return
                try {
                    directory.deleteRecursively()
                } catch (_: SecurityException) {
                    // A failed clear is safe; a later repository will validate any remaining files.
                }
            }
        }

        private fun plain(html: String): String = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
            .toString().replace('\u00a0', ' ').trim()

        private fun normalizedUrl(value: String): String = when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http://") -> "https:" + value.removePrefix("http:")
            else -> value
        }

        private fun isSource(value: String): Boolean {
            val uri = Uri.parse(value)
            return uri.scheme == "https" && uri.host == "commons.wikimedia.org" &&
                uri.userInfo == null && uri.port == -1 && uri.path.orEmpty().startsWith("/wiki/File:")
        }

        private fun isImageUrl(value: String): Boolean {
            val uri = Uri.parse(value)
            return uri.scheme == "https" && uri.host in setOf("upload.wikimedia.org", "thumb.wikimedia.org") &&
                uri.userInfo == null && uri.port == -1 && uri.path.orEmpty().startsWith("/wikipedia/commons/")
        }

        private fun acceptedLicense(name: String, url: String): Boolean {
            val uri = Uri.parse(url)
            if (uri.scheme != "https" || uri.host != "creativecommons.org" ||
                uri.userInfo != null || uri.port != -1 || uri.query != null || uri.fragment != null
            ) return false
            val path = uri.path.orEmpty().trimEnd('/').lowercase(Locale.ROOT)
            if (name.equals("CC0", true)) return path == "/publicdomain/zero/1.0"
            if (name.equals("Public domain", true)) return path == "/publicdomain/mark/1.0"
            val match = Regex("CC BY(-SA)? (1\\.0|2\\.0|2\\.5|3\\.0|4\\.0)", RegexOption.IGNORE_CASE)
                .matchEntire(name) ?: return false
            val kind = if (match.groupValues[1].isEmpty()) "by" else "by-sa"
            return path == "/licenses/$kind/${match.groupValues[2]}"
        }
    }
}
