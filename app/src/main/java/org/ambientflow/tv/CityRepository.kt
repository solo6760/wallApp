package org.ambientflow.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Looper
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
import java.io.InputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Source/creator details are preserved; admitted photographs require no copyright credit overlay. */
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
 * Curated Commons CC0/public-domain photographs only. All IO/decode methods are worker-only.
 * Disk locks never span HTTP. Cached photos return without waiting for network refresh;
 * a single bounded background job fills missing city slots, without decoding/pinning frames.
 * The parent owns returned bitmaps and the maximum of two live slideshow frames.
 */
class CityRepository(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val directory = File(app.filesDir, CACHE_DIRECTORY)
    private val index = AtomicFile(File(directory, "index.json"))
    private val closed = AtomicBoolean(false)
    private val registration = active.computeIfAbsent(directory.absolutePath) { AtomicInteger() }
    private val fetchGate = transfers.computeIfAbsent(directory.absolutePath) { AtomicBoolean() }
    private val httpLock = Any()
    private var inFlight: Call? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS).retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).build()
    private val warmer = Executors.newSingleThreadExecutor { task -> Thread(task, "city-photo-cache").apply { isDaemon = true } }
    private val queued = AtomicBoolean(false)
    private val retryAfter = AtomicLong(0)
    // All mutable catalogue/cache selection state below is guarded by cacheLock.
    private var catalogue: List<Seed>? = null
    private var cityCursor = 0L
    private val rejectedUntil = mutableMapOf<String, Long>()
    private val issued = mutableMapOf<String, CityPhoto>()

    init { registration.incrementAndGet() }

    fun next(cities: Set<String>, currentId: String?): CityPhoto? = worker {
        val selected = CITIES.filter { it in cities }
        if (selected.isEmpty()) return@worker null
        val selection = cacheLock.withLock {
            checkOpen()
            val entries = load()
            val desired = selected[(cityCursor++ and Long.MAX_VALUE).rem(selected.size).toInt()]
            val matching = entries.filter { it.photo.city in selected }
            val cached = matching.firstOrNull { it.photo.city == desired && it.photo.id != currentId }
                ?: matching.firstOrNull { it.photo.id != currentId } ?: matching.firstOrNull()
            if (cached != null) {
                entries.remove(cached)
                entries.add(cached)
                save(entries)
            }
            Selection(cached?.photo?.let { issue(it) }, desired, matching.isNotEmpty())
        }
        if (selection.hasCache) {
            // Offline playback never waits for an HTTP timeout or an in-progress download.
            scheduleWarm(selected, selection.desiredCity, selection.photo?.id ?: currentId)
            selection.photo
        } else {
            // Cold start is already on the caller's worker: one metadata batch, <=2 images.
            warm(selected, selection.desiredCity, currentId)
            cacheLock.withLock {
                val entries = load()
                val photo = entries.firstOrNull { it.photo.city == selection.desiredCity }
                    ?: entries.firstOrNull { it.photo.city in selected }
                photo?.photo?.let { issue(it) }
            }
        }
    }

    private data class Selection(val photo: CityPhoto?, val desiredCity: String, val hasCache: Boolean)
    private data class Seed(val id: String, val city: String)
    private data class Entry(val photo: CityPhoto, val info: JSONObject, val checkedAt: Long)

    private fun scheduleWarm(cities: List<String>, desired: String, pinned: String?) {
        if (closed.get() || SystemClock.elapsedRealtime() < retryAfter.get() || !queued.compareAndSet(false, true)) return
        try {
            warmer.execute {
                try { worker { warm(cities, desired, pinned); true } }
                finally { queued.set(false) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { queued.set(false) }
    }

    private fun warm(cities: List<String>, desired: String, pinned: String?) {
        if (closed.get() || SystemClock.elapsedRealtime() < retryAfter.get() || !fetchGate.compareAndSet(false, true)) return
        try {
            val batch = cacheLock.withLock {
                checkOpen()
                val entries = load()
                val seeds = seeds()
                val quota = minOf(2, (MAX_PHOTOS / cities.size).coerceAtLeast(1))
                val orderedCities = listOf(desired) + cities.filter { it != desired }
                val pending = orderedCities.associateWith { city ->
                    if (entries.count { it.photo.city == city } >= minOf(quota, seeds.count { it.city == city })) emptyList()
                    else seeds.filter { seed -> seed.city == city && entries.none { it.photo.id == seed.id } &&
                        SystemClock.elapsedRealtime() >= (rejectedUntil[seed.id] ?: 0L) }
                }
                // First try one candidate per city, then alternate photos; never a broad search.
                (orderedCities.mapNotNull { pending[it]?.getOrNull(0) } +
                    orderedCities.mapNotNull { pending[it]?.getOrNull(1) }).take(4)
            }
            if (batch.isEmpty()) return
            val url = API.toHttpUrl().newBuilder()
                .addQueryParameter("action", "query").addQueryParameter("format", "json")
                .addQueryParameter("pageids", batch.joinToString("|") { it.id })
                .addQueryParameter("prop", "imageinfo").addQueryParameter("iiprop", "url|size|mime|extmetadata")
                .addQueryParameter("iiurlwidth", WIDTH.toString())
                .addQueryParameter("iiextmetadatafilter", METADATA_FIELDS)
                .addQueryParameter("iiextmetadatalanguage", "en").addQueryParameter("maxlag", "5").build().toString()
            val root = JSONObject(String(requestBytes(url), Charsets.UTF_8))
            if (root.has("error")) throw IOException("Commons API error: ${root.optJSONObject("error")?.optString("code")}")
            val pages = root.optJSONObject("query")?.optJSONObject("pages")
            var attempts = 0
            for (seed in batch) {
                checkOpen()
                val page = pages?.optJSONObject(seed.id)
                val info = page?.optJSONArray("imageinfo")?.optJSONObject(0)
                val entry = if (page?.optLong("pageid") == seed.id.toLong() && page.optInt("ns") == 6 &&
                    page.optString("imagerepository") == "local" && page.optString("title").startsWith("File:") && info != null
                ) entry(seed, plain(page.getString("title").removePrefix("File:")), info, System.currentTimeMillis()) else null
                if (entry == null) { reject(seed.id); continue }
                if (attempts++ >= 2) break
                try {
                    cacheLock.withLock {
                        val entries = load()
                        if (entries.any { it.photo.id == seed.id }) return
                        reserve(entries, pinned)
                    }
                    download(entry)
                    cacheLock.withLock {
                        checkOpen()
                        val entries = load()
                        if (!File(directory, "download.part").renameTo(entry.photo.file)) throw IOException("Cannot commit photo")
                        entries.add(entry)
                        if (!save(entries)) { entry.photo.file.delete(); throw IOException("Cannot commit provenance") }
                    }
                    return
                } catch (e: RateLimited) { throw e }
                catch (_: IOException) { reject(seed.id) }
            }
        } catch (e: RateLimited) {
            retryAfter.set(SystemClock.elapsedRealtime() + e.delayMs)
        } catch (_: IOException) {
            retryAfter.set(SystemClock.elapsedRealtime() + RETRY_MS)
        } catch (_: RuntimeException) {
            retryAfter.set(SystemClock.elapsedRealtime() + RETRY_MS)
        } finally {
            cacheLock.withLock { File(directory, "download.part").delete() }
            fetchGate.set(false)
        }
    }

    private fun reject(id: String) = cacheLock.withLock {
        rejectedUntil[id] = SystemClock.elapsedRealtime() + REJECT_MS
    }

    private fun download(entry: Entry) {
        val temp = File(directory, "download.part")
        withResponse(entry.info.getString("thumburl")) { response ->
            val body = response.body ?: throw IOException("Empty Commons image")
            if (body.contentType()?.let { "${it.type}/${it.subtype}" } != "image/jpeg" || body.contentLength() > MAX_FILE_BYTES)
                throw IOException("Invalid JPEG response")
            FileOutputStream(temp).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        checkOpen()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_FILE_BYTES) throw IOException("JPEG exceeds 16 MiB")
                        output.write(buffer, 0, count)
                    }
                }
                output.fd.sync()
            }
        }
        val bounds = bounds(temp) ?: throw IOException("Invalid/undersized delivered JPEG")
        if (bounds.first > entry.info.getInt("width") || bounds.second > entry.info.getInt("height"))
            throw IOException("Delivered dimensions exceed original")
        checkOpen()
    }

    /** Exact 3840x2160 RGB565 output. Only small region strips, never a full original, are decoded. */
    @Suppress("DEPRECATION")
    fun decode(photo: CityPhoto): Bitmap? = worker {
        cacheLock.withLock {
            if (issued[photo.id] != photo) return@withLock null
            val dimensions = bounds(photo.file) ?: return@withLock null
            val width = minOf(dimensions.first.toLong(), dimensions.second.toLong() * WIDTH / HEIGHT).toInt()
            val height = minOf(dimensions.second.toLong(), dimensions.first.toLong() * HEIGHT / WIDTH).toInt()
            if (width < WIDTH || height < HEIGHT) return@withLock null
            val left = (dimensions.first - width) / 2
            val top = (dimensions.second - height) / 2
            var sample = 1
            while (width / (sample * 2) >= WIDTH && height / (sample * 2) >= HEIGHT) sample *= 2
            val decoder = BitmapRegionDecoder.newInstance(photo.file.absolutePath, false) ?: return@withLock null
            var output: Bitmap? = null
            try {
                val frame = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.RGB_565)
                output = frame
                val canvas = Canvas(frame)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
                val scaleY = HEIGHT.toFloat() / height
                var y = 0
                while (y < height) {
                    checkOpen()
                    val end = minOf(height, y + 256 * sample)
                    // A one-sampled-pixel halo avoids filtering seams at strip boundaries.
                    val startWithHalo = maxOf(0, y - sample)
                    val endWithHalo = minOf(height, end + sample)
                    val tile = decoder.decodeRegion(Rect(left, top + startWithHalo, left + width, top + endWithHalo),
                        BitmapFactory.Options().apply {
                            inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565; inDither = true
                        }) ?: throw IOException("Cannot decode JPEG region")
                    try {
                        canvas.save()
                        canvas.clipRect(0f, y * scaleY, WIDTH.toFloat(), end * scaleY)
                        canvas.drawBitmap(tile, null, RectF(0f, startWithHalo * scaleY, WIDTH.toFloat(), endWithHalo * scaleY), paint)
                        canvas.restore()
                    } finally { tile.recycle() }
                    y = end
                }
                checkOpen()
                output = null
                frame
            } finally { output?.recycle(); decoder.recycle() }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        registration.decrementAndGet()
        synchronized(httpLock) { inFlight?.cancel() }
        warmer.shutdownNow()
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        // Never acquire cacheLock, join a worker, or touch disk on the caller/main thread.
    }

    private fun <T> worker(block: () -> T?): T? {
        if (Looper.myLooper() == Looper.getMainLooper() || closed.get()) return null
        return try { block() }
        catch (_: IOException) { null }
        catch (_: RuntimeException) { null }
        catch (_: OutOfMemoryError) { null }
    }

    private fun checkOpen() {
        if (closed.get() || Thread.currentThread().isInterrupted) throw IOException("Repository closed")
    }

    private fun issue(photo: CityPhoto): CityPhoto? {
        checkOpen()
        issued[photo.id] = photo
        return photo
    }

    private fun seeds(): List<Seed> {
        catalogue?.let { return it }
        val root = JSONObject(app.assets.open("cityscapes.json").use { String(it.readCapped(MAX_METADATA_BYTES), Charsets.UTF_8) })
        if (root.getInt("version") != 2 || root.getString("mode") != "automatic-curated-commons") throw IOException("Unknown catalogue")
        val photos = root.getJSONArray("photos")
        if (photos.length() !in 7..28) throw IOException("Invalid catalogue size")
        val result = mutableListOf<Seed>()
        for (i in 0 until photos.length()) {
            val item = photos.getJSONObject(i)
            val seed = Seed(item.getString("id"), item.getString("city"))
            if (!seed.id.matches(Regex("[1-9][0-9]{0,19}")) || seed.city !in CITIES || result.any { it.id == seed.id })
                throw IOException("Invalid catalogue record")
            result.add(seed)
        }
        catalogue = result
        return result
    }

    private fun entry(seed: Seed, title: String, info: JSONObject, checkedAt: Long): Entry? {
        if (title.isBlank() || title.length > 500 || !qualifies(info.optInt("width"), info.optInt("height")) ||
            !qualifies(info.optInt("thumbwidth"), info.optInt("thumbheight")) ||
            info.optInt("thumbwidth") > info.optInt("width") || info.optInt("thumbheight") > info.optInt("height") ||
            info.optString("mime") != "image/jpeg" || info.optLong("size") !in 1..MAX_FILE_BYTES ||
            !safeUrl(info.optString("thumburl"), false) || !safeUrl(info.optString("descriptionurl"), true)
        ) return null
        val metadata = info.optJSONObject("extmetadata") ?: return null
        fun field(name: String) = plain(metadata.optJSONObject(name)?.optString("value").orEmpty())
        if (!metadata.has("Restrictions")) return null
        val license = field("LicenseShortName")
        val licenseUrl = canonicalLicense(license, field("License"), field("LicenseUrl"), field("AttributionRequired"),
            field("Restrictions"), field("NonFree"), field("Copyrighted")) ?: return null
        val artist = field("Artist")
        if (artist.isBlank()) return null
        val creator = listOf(artist, field("Attribution"), field("Credit")).filter { it.isNotBlank() }.distinct().joinToString("\n")
        if (creator.length > 8000) return null
        return Entry(CityPhoto(seed.id, title, creator, license, licenseUrl, info.getString("descriptionurl"), seed.city,
            File(directory, "${seed.id}.jpg")), info, checkedAt)
    }

    private fun load(): MutableList<Entry> {
        val entries = mutableListOf<Entry>()
        val seeds = seeds().associateBy { it.id }
        try {
            val root = JSONObject(index.openRead().use { String(it.readCapped(MAX_METADATA_BYTES), Charsets.UTF_8) })
            if (root.getInt("version") != 2) throw IOException("Unknown cache version")
            val array = root.getJSONArray("photos")
            for (i in 0 until minOf(array.length(), MAX_PHOTOS)) {
                val item = array.getJSONObject(i)
                val seed = seeds[item.getString("id")] ?: continue
                if (item.getString("city") != seed.city || entries.any { it.photo.id == seed.id }) continue
                val candidate = entry(seed, item.getString("title"), item.getJSONObject("info"), item.getLong("checkedAt")) ?: continue
                val dimensions = bounds(candidate.photo.file) ?: continue
                if (dimensions.first <= candidate.info.getInt("width") && dimensions.second <= candidate.info.getInt("height")) entries.add(candidate)
            }
        } catch (_: IOException) { entries.clear() }
        catch (_: org.json.JSONException) { entries.clear() }
        while (entries.sumOf { it.photo.file.length() } > IMAGE_BUDGET) entries.removeAt(0)
        val keep = entries.map { it.photo.file.name }.toSet() + setOf("index.json", "index.json.bak", "index.json.new") +
            if (fetchGate.get()) setOf("download.part") else emptySet()
        directory.listFiles()?.filter { it.name !in keep }?.forEach {
            if (!it.isFile || (!it.delete() && it.exists())) throw IOException("Cannot remove orphan cache file")
        }
        issued.keys.retainAll(entries.map { it.photo.id }.toSet())
        return entries
    }

    private fun reserve(entries: MutableList<Entry>, pinned: String?) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create cache")
        while (entries.size >= MAX_PHOTOS || entries.sumOf { it.photo.file.length() } > IMAGE_BUDGET - MAX_FILE_BYTES) {
            val victim = entries.firstOrNull { it.photo.id != pinned } ?: throw IOException("Cache pinned")
            entries.remove(victim)
            if (!save(entries)) throw IOException("Cannot update cache index")
            issued.remove(victim.photo.id)
            if (!victim.photo.file.delete() && victim.photo.file.exists()) throw IOException("Cannot evict photo")
        }
    }

    private fun save(entries: List<Entry>): Boolean {
        val array = JSONArray()
        entries.forEach { item -> array.put(JSONObject().put("id", item.photo.id).put("city", item.photo.city)
            .put("title", item.photo.title).put("info", item.info).put("checkedAt", item.checkedAt)) }
        val bytes = JSONObject().put("version", 2).put("photos", array).toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_METADATA_BYTES) return false
        var stream: FileOutputStream? = null
        return try {
            stream = index.startWrite(); stream.write(bytes); index.finishWrite(stream); true
        } catch (_: IOException) { index.failWrite(stream); false }
    }

    private class RateLimited(val delayMs: Long) : IOException("Commons rate limited")

    private fun <T> withResponse(url: String, block: (okhttp3.Response) -> T): T {
        checkOpen()
        val call = synchronized(httpLock) {
            checkOpen()
            client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).also { inFlight = it }
        }
        try {
            return call.execute().use { response ->
                if (response.code == 429 || response.code == 503) {
                    val delay = response.header("Retry-After")?.toLongOrNull()?.coerceIn(60, 86400)?.times(1000) ?: REJECT_MS
                    throw RateLimited(delay)
                }
                if (!response.isSuccessful) throw IOException("Commons HTTP ${response.code}")
                block(response)
            }
        } finally { synchronized(httpLock) { if (inFlight === call) inFlight = null } }
    }

    private fun requestBytes(url: String) = withResponse(url) { response ->
        val body = response.body ?: throw IOException("Empty metadata")
        if (body.contentLength() > MAX_METADATA_BYTES) throw IOException("Metadata too large")
        body.byteStream().use { it.readCapped(MAX_METADATA_BYTES) }
    }

    private fun InputStream.readCapped(limit: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            checkOpen()
            val count = read(buffer)
            if (count < 0) break
            if (output.size().toLong() + count > limit) throw IOException("Metadata too large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    companion object {
        private const val CACHE_DIRECTORY = "commons-unrestricted-city-4k-v2"
        private const val WIDTH = 3840
        private const val HEIGHT = 2160
        private const val MAX_PHOTOS = 12
        private const val MAX_FILE_BYTES = 16L * 1024 * 1024
        private const val MAX_METADATA_BYTES = 512L * 1024
        private const val IMAGE_BUDGET = 64L * 1024 * 1024 - 2 * MAX_METADATA_BYTES
        private const val RETRY_MS = 60_000L
        private const val REJECT_MS = 15 * 60_000L
        private const val API = "https://commons.wikimedia.org/w/api.php"
        private const val METADATA_FIELDS = "Artist|Credit|Attribution|LicenseShortName|LicenseUrl|License|AttributionRequired|Restrictions|NonFree|Copyrighted|UsageTerms|ImageDescription|Categories"
        private const val USER_AGENT = "AmbientFlowTV/1.0 (Android cityscape viewer; documented Commons Action API)"
        private val CITIES = listOf("Tokyo", "New York", "Hong Kong", "Singapore", "London", "Chicago", "Seoul")
        private val cacheLock = ReentrantLock()
        private val active = ConcurrentHashMap<String, AtomicInteger>()
        private val transfers = ConcurrentHashMap<String, AtomicBoolean>()

        /** Both a landscape original and its delivered rendition must satisfy this policy. */
        @JvmStatic
        fun qualifies(width: Int, height: Int) = width in WIDTH..7680 && height in HEIGHT..5120 && width > height

        /** Pure eligibility policy; no BY/SA/FAL/GFDL, unknown, multi-license or restricted fallback. */
        @JvmStatic
        fun canonicalLicense(name: String, code: String, url: String, attribution: String,
            restrictions: String, nonFree: String = "", copyrighted: String = ""): String? {
            if (!attribution.equals("false", true) || restrictions.isNotEmpty() ||
                !(nonFree.isEmpty() || nonFree.equals("false", true))) return null
            val kind = when {
                name == "CC0" && code == "cc0" -> "zero"
                name == "Public domain" && code == "pd" && copyrighted.equals("false", true) -> "mark"
                else -> return null
            }
            val canonical = "https://creativecommons.org/publicdomain/$kind/1.0/"
            // PD-self commonly has no LicenseUrl: retain its explicit pd/false status,
            // with a PDM explanatory link, not an invented source-license assertion.
            if (kind == "mark" && url.isEmpty()) return canonical
            return try {
                val uri = URI(url)
                if (uri.scheme !in setOf("https", "http") || uri.rawAuthority != "creativecommons.org" ||
                    uri.query != null || uri.fragment != null ||
                    !uri.path.matches(Regex("/publicdomain/$kind/1\\.0(?:/deed(?:\\.[a-zA-Z_-]+)?)?/?"))) null
                else canonical
            } catch (_: Exception) { null }
        }

        private fun safeUrl(value: String, source: Boolean): Boolean = try {
            val uri = URI(value)
            uri.scheme == "https" && uri.port == -1 && uri.userInfo == null && uri.fragment == null &&
                if (source) uri.rawAuthority == "commons.wikimedia.org" && uri.path.startsWith("/wiki/File:")
                else uri.rawAuthority in setOf("upload.wikimedia.org", "thumb.wikimedia.org") && uri.path.startsWith("/wikipedia/commons/")
        } catch (_: Exception) { false }

        private fun plain(value: String) = Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString().replace('\u00a0', ' ').trim()

        private fun bounds(file: File): Pair<Int, Int>? {
            if (!file.isFile || file.length() !in 1..MAX_FILE_BYTES) return null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            return if (options.outMimeType == "image/jpeg" && qualifies(options.outWidth, options.outHeight))
                options.outWidth to options.outHeight else null
        }

        /** Worker-only, no-op while active/transferring/busy; only owned namespaces are deleted. */
        @JvmStatic
        fun clear(context: Context) {
            if (Looper.myLooper() == Looper.getMainLooper() || !cacheLock.tryLock()) return
            try {
                val root = context.applicationContext.filesDir
                val key = File(root, CACHE_DIRECTORY).absolutePath
                if ((active[key]?.get() ?: 0) != 0 || transfers[key]?.get() == true) return
                for (name in listOf(CACHE_DIRECTORY, "commons-city-photos", "unsplash-local-city-4k-v1")) {
                    val target = File(root, name)
                    if (target.canonicalFile.parentFile == root.canonicalFile && target.canonicalFile.name == name) deleteOwned(target)
                }
            } catch (_: IOException) { }
            catch (_: SecurityException) { }
            finally { cacheLock.unlock() }
        }

        private fun deleteOwned(file: File) {
            if (file.canonicalFile != file.absoluteFile) { file.delete(); return }
            if (file.isDirectory) file.listFiles()?.forEach { deleteOwned(it) }
            file.delete()
        }
    }
}
