package krd.bgremover

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/** یەک وێنە لە ڕیزی کارکردنی بەکۆمەڵ. */
data class BatchItem(
    val id: Int,
    val url: String? = null,
    val fallbackUrl: String? = null,
    val referer: String? = null,
    val uri: Uri? = null,
    val status: Status = Status.WAITING,
    val thumb: Bitmap? = null,
    val file: File? = null,
    val error: String? = null
) {
    enum class Status { WAITING, WORKING, DONE, ERROR }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // ───────── پاشەکەوتی دۆخ: ئەگەر ئەندرۆید ئەپەکەی لە پشتەوە داخست، وەک خۆی دەگەڕێتەوە ─────────
    private val stateDir = File(app.filesDir, "state").apply { mkdirs() }
    private val stateJson = File(stateDir, "state.json")
    private val originalFile = File(stateDir, "original.png")
    private val cutoutFile = File(stateDir, "cutout.png")
    private var savedOriginal: Bitmap? = null
    private var savedCutout: Bitmap? = null
    private var restoring = true

    var query by mutableStateOf("")
    var results by mutableStateOf<List<ImageResult>>(emptyList())
    var source by mutableStateOf("")
    var original by mutableStateOf<Bitmap?>(null)
    var cutout by mutableStateOf<Bitmap?>(null)
    var bgColor by mutableStateOf<Int?>(null)
    var busy by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)

    // گەڕان: لاپەڕەکان
    private var lastQuery = ""
    private var page = 1
    var loadingMore by mutableStateOf(false)
    var canLoadMore by mutableStateOf(false)

    // هەڵبژاردنی چەند وێنەیەک
    var selectMode by mutableStateOf(false)
    var selected by mutableStateOf<Set<String>>(emptySet())

    // کاری بەکۆمەڵ
    val batch = mutableStateListOf<BatchItem>()
    var batchStatus by mutableStateOf<String?>(null)
    private var batchJob: Job? = null
    private var nextId = 1

    // کلیلی Serper: ئەوەی لە ⚙ نووسراوە، یان کلیلی ناو ئەپ (لە GitHub Secret) بۆ هەموو مۆبایلەکان
    var serperKey by mutableStateOf(
        prefs.getString("serper", null)?.ifBlank { null } ?: BuildConfig.DEFAULT_SERPER_KEY
    )
        private set
    var removeBgKey by mutableStateOf(prefs.getString("removebg", "") ?: "")
        private set
    /** "isnet" (بنەڕەت، وردترین) | "fast" (ML Kit، خێرا) | "removebg" */
    var engine by mutableStateOf(
        prefs.getString("engine", null)
            ?: if (prefs.getBoolean("use_removebg", false)) "removebg" else "isnet"
    )
        private set

    /** تەنها مرۆڤەکە بمێنێتەوە (شتی زیادە لادەبرێت). */
    var personOnly by mutableStateOf(prefs.getBoolean("person_only", true))
        private set

    private var job: Job? = null

    /** تەنها کەسی سەرەکی (ئەوەی فۆکسی لەسەرە)، کەسانی تر لادەبرێن. */
    var focusOnly by mutableStateOf(prefs.getBoolean("focus_only", true))
        private set

    fun changeFocusOnly(v: Boolean) {
        focusOnly = v
        prefs.edit().putBoolean("focus_only", v).apply()
    }

    /** دوای لابردن، وێنەی بچووک ×2 بە AI ڕوون دەکرێتەوە (لە شوێنی خۆی) */
    var autoEnhance by mutableStateOf(prefs.getBoolean("enhance_after_cut", false))
        private set

    fun changeAutoEnhance(v: Boolean) {
        autoEnhance = v
        prefs.edit().putBoolean("enhance_after_cut", v).apply()
    }

    fun changePersonOnly(v: Boolean) {
        personOnly = v
        prefs.edit().putBoolean("person_only", v).apply()
    }

    /** ئەنجامی کۆتایی: تەنها ڕەنگی باکگراوند (ئەگەر هەڵبژێردرابێت). */
    fun compose(fg: Bitmap): Bitmap = ImageUtils.withBackground(fg, bgColor)

    fun saveSettings(serper: String, removeBg: String, eng: String) {
        serperKey = serper.trim()
        removeBgKey = removeBg.trim()
        engine = if (eng == "removebg" && removeBgKey.isBlank()) "isnet" else eng
        prefs.edit()
            .putString("serper", serperKey)
            .putString("removebg", removeBgKey)
            .putString("engine", engine)
            .apply()
    }

    // ───────────────────────── گەڕان ─────────────────────────

    /** ئەگەر لینک بوو ڕاستەوخۆ دادەبەزێنێت، ئەگەر نا بە ناو دەگەڕێت. */
    fun submit() {
        val q = query.trim()
        if (q.isEmpty()) return
        if (q.startsWith("http://", true) || q.startsWith("https://", true)) {
            loadFromUrl(q, null)
        } else search(q)
    }

    private fun search(q: String) {
        job?.cancel()
        job = viewModelScope.launch {
            busy = "گەڕان بۆ «$q»..."
            selected = emptySet()
            try {
                val (src, list) = ImageSearch.search(q, serperKey, 1)
                lastQuery = q
                page = 1
                source = src
                results = list
                canLoadMore = list.isNotEmpty()
                if (list.isEmpty()) message = "هیچ وێنەیەک نەدۆزرایەوە، ناوێکی تر تاقی بکەرەوە"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "هەڵە لە گەڕان: ${e.message}"
            } finally {
                busy = null
            }
        }
    }

    fun loadMore() {
        if (loadingMore || lastQuery.isEmpty()) return
        viewModelScope.launch {
            loadingMore = true
            try {
                val (_, list) = ImageSearch.search(lastQuery, serperKey, page + 1)
                val known = results.map { it.fullUrl }.toHashSet()
                val fresh = list.filter { it.fullUrl !in known }
                page++
                results = results + fresh
                canLoadMore = fresh.isNotEmpty()
                if (fresh.isEmpty()) message = "وێنەی زیاتر نییە"
            } catch (e: Exception) {
                message = "هەڵە: ${e.message}"
            } finally {
                loadingMore = false
            }
        }
    }

    // ───────────────────────── هەڵبژاردن ─────────────────────────

    fun toggleSelect(r: ImageResult) {
        selected = if (r.fullUrl in selected) selected - r.fullUrl else selected + r.fullUrl
        if (selected.isEmpty()) selectMode = false
    }

    fun startSelect(r: ImageResult?) {
        selectMode = true
        if (r != null && r.fullUrl !in selected) selected = selected + r.fullUrl
    }

    fun cancelSelect() {
        selectMode = false
        selected = emptySet()
    }

    fun selectAll() {
        selectMode = true
        selected = results.map { it.fullUrl }.toSet()
    }

    // ───────────────────────── یەک وێنە ─────────────────────────

    fun pick(r: ImageResult) = loadFromUrl(r.fullUrl, r.thumbUrl, r.pageUrl)

    /** true ئەگەر وێنە ئەسڵییەکە دانەبەزی و تەنها وێنە بچووکەکە بەکارهات. */
    var lowQuality by mutableStateOf(false)

    /**
     * سەرەتا وێنە ئەسڵییەکە بە قەبارە و کوالیتی تەواو دادەبەزێنێت.
     * ئەگەر ماڵپەڕەکە ڕێگەی نەدا، جارێکی تر لەگەڵ Referer هەوڵ دەدات،
     * تەنها لە کۆتاییدا وێنە بچووکەکە (thumbnail) بەکاردەهێنێت.
     */
    private suspend fun download(url: String, fallback: String?, referer: String? = null): Bitmap? {
        lowQuality = false
        val ref = referer?.ifBlank { null } ?: Uri.parse(url).let { "${it.scheme}://${it.host}/" }
        // ١. ڕاستەوخۆ  ٢. وەک وێبگەڕی کۆمپیوتەر لەگەڵ Referer  ٣. لە ڕێگەی پرۆکسیی وێنەوە
        // هەموویان قەبارەی ئەسڵی دەهێنن؛ تەنها لە کۆتاییدا وێنە بچووکەکە
        val attempts = buildList<suspend () -> Bitmap> {
            add { ImageUtils.download(url) }
            add { ImageUtils.download(url, ref, ImageUtils.DESKTOP_UA) }
            add { ImageUtils.download(ImageUtils.proxied(url)) }
        }
        for (a in attempts) {
            try {
                return a()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        if (fallback != null && fallback != url) {
            try {
                val b = ImageUtils.download(fallback)
                lowQuality = true
                return b
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        return null
    }

    // سەرچاوەی وێنەی ئێستا (بۆ داگرتنی ئەسڵی)
    private var originalUrl: String? = null
    private var originalFallback: String? = null

    /** ئەنجامی داگرتن: null = سەرکەوتوو؛ ئەگەرنا هۆکاری هەڵە. */
    private var lastRawLow = false

    /** داگرتنی فایلێک بەبێ لابردنی باکگراوند (ئەسڵی، کوالیتی تەواو). */
    private suspend fun saveRaw(url: String, fallback: String?): String? {
        val app = getApplication<Application>()
        val ref = Uri.parse(url).let { "${it.scheme}://${it.host}/" }
        val tries = listOf<suspend () -> Pair<ByteArray, String>>(
            { ImageUtils.downloadRaw(url) },
            { ImageUtils.downloadRaw(url, ref, ImageUtils.DESKTOP_UA) },
            { ImageUtils.downloadRaw(ImageUtils.proxied(url)) },
            { ImageUtils.downloadRaw(ImageUtils.proxied(url) + "&output=jpg") },
        )
        var got: Pair<ByteArray, String>? = null
        var err: String? = null
        lastRawLow = false
        for (t in tries) {
            try { got = t(); break } catch (e: CancellationException) { throw e } catch (e: Exception) { err = e.message }
        }
        if (got == null && fallback != null && fallback != url) {
            try { got = ImageUtils.downloadRaw(fallback); lastRawLow = true } catch (e: CancellationException) { throw e } catch (e: Exception) { err = e.message }
        }
        val (bytes, mime) = got ?: return err ?: "هەڵەی تۆڕ"
        return try {
            withContext(Dispatchers.IO) { ImageUtils.saveBytesToGallery(app, bytes, mime) }
            null
        } catch (e: Exception) { "پاشەکەوت سەرنەکەوت: ${e.message}" }
    }

    /** وێنەی سەرەکی (پێش لابردن) پاشەکەوت دەکات. */
    fun saveOriginal() {
        viewModelScope.launch {
            busy = "داگرتنی وێنەی ئەسڵی..."
            val url = originalUrl
            val ok = if (url != null) saveRaw(url, originalFallback) == null else {
                val bmp = original
                if (bmp == null) false else try {
                    withContext(Dispatchers.IO) {
                        val bos = java.io.ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)   // بێ لەدەستدانی کوالیتی
                        ImageUtils.saveBytesToGallery(getApplication(), bos.toByteArray(), "image/png")
                    }
                    true
                } catch (_: Exception) { false }
            }
            busy = null
            message = if (ok) "پاشەکەوت کرا لە گاڵەری › Pictures/SG search/Original" else "داگرتن سەرنەکەوت"
        }
    }

    /** URL ی ئەو وێنانەی ئێستا دادەبەزن (بۆ پیشاندانی بازنەی چاوەڕێ لەسەر ئایکۆنەکە). */
    var downloading by mutableStateOf<Set<String>>(emptySet())
        private set

    /** ئایکۆنی داگرتن لەسەر وێنەیەک: بە قەبارە و کوالیتی ئەسڵی، بێ لابردنی باکگراوند. */
    fun downloadOne(r: ImageResult) {
        if (r.fullUrl in downloading) return
        downloading = downloading + r.fullUrl
        viewModelScope.launch {
            val err = try { saveRaw(r.fullUrl, r.thumbUrl) } finally { downloading = downloading - r.fullUrl }
            message = if (err == null) "✔ دابەزی › گاڵەری › Pictures/SG search/Original" +
                (if (lastRawLow) " (ماڵپەڕەکە تەنها وێنەی بچووکی دا)" else "")
            else "ئەم وێنەیە دانابەزێت ($err)"
        }
    }

    /** ئایکۆنی لابردنی باکگراوند لەسەر وێنەیەک. */
    fun removeOne(r: ImageResult) = pick(r)

    /** وێنە هەڵبژێردراوەکان بەبێ لابردنی باکگراوند دادەبەزێنێت. */
    fun downloadSelected() {
        val picked = results.filter { it.fullUrl in selected }
        if (picked.isEmpty()) return
        cancelSelect()
        viewModelScope.launch {
            var ok = 0
            picked.forEachIndexed { i, r ->
                busy = "داگرتنی وێنەی ${i + 1} لە ${picked.size}..."
                if (saveRaw(r.fullUrl, r.thumbUrl) == null) ok++
            }
            busy = null
            message = "$ok لە ${picked.size} وێنە دابەزی › Pictures/SG search/Original"
        }
    }

    private fun loadFromUrl(url: String, fallback: String?, referer: String? = null) {
        originalUrl = url
        originalFallback = fallback
        job?.cancel()
        job = viewModelScope.launch {
            original = null
            cutout = null
            busy = "١/٢ داگرتنی وێنە بە قەبارە و کوالیتی تەواو..."
            val bmp = download(url, fallback, referer)
            busy = null
            if (bmp == null) {
                message = "ئەم وێنەیە دانابەزێت، یەکێکی تر هەڵبژێرە"
            } else setImage(bmp)
        }
    }

    fun loadUri(uri: Uri) {
        originalUrl = null
        originalFallback = null
        job?.cancel()
        job = viewModelScope.launch {
            busy = "کردنەوەی وێنە..."
            try {
                val bmp = withContext(Dispatchers.IO) { ImageUtils.decodeUri(getApplication(), uri) }
                busy = null
                setImage(bmp)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                busy = null
                message = "نەتوانرا وێنەکە بکرێتەوە"
            }
        }
    }

    // ───────────────────────── Upscale ─────────────────────────

    /** ئەنجامی ئێستا upscale کراوە؟ */
    var upscaled by mutableStateOf(false)
        private set

    private suspend fun upscaleSafe(bmp: Bitmap, maxSide: Int, onStatus: (String) -> Unit): Bitmap? =
        try {
            Upscaler.upscale(getApplication(), bmp, maxSide, onStatus)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            message = "Upscale: بیرگەی مۆبایل بەس نییە"; null
        } catch (e: Exception) {
            message = "Upscale سەرنەکەوت: ${e.message}"; null
        }


    // ───────────────────────── لابردن «لە شوێنی خۆی» لەسەر هەر وێنەیەک ─────────────────────────

    data class TileState(
        val status: String = "",          // "" | WAITING | WORKING | DONE | ERROR
        val msg: String = "",
        val file: String = "",
        val orig: String = "",
        val w: Int = 0, val h: Int = 0,
        val enhanced: Boolean = false,
        val thumb: Bitmap? = null
    )

    val tiles = mutableStateMapOf<String, TileState>()
    private val inplaceLock = Mutex()
    private val inplaceDir get() = File(getApplication<Application>().filesDir, "inplace").apply { mkdirs() }

    fun inplace(r: ImageResult) {
        val url = r.fullUrl
        val cur = tiles[url]
        if (cur != null && cur.status in setOf("WAITING", "WORKING", "DONE")) return
        tiles[url] = TileState("WAITING")
        viewModelScope.launch {
            inplaceLock.withLock {
                if (tiles[url]?.status != "WAITING") return@withLock
                var lastT = 0L
                fun upd(m: String) {
                    val now = System.currentTimeMillis()
                    if (now - lastT < 250 && tiles[url]?.status == "WORKING") return
                    lastT = now
                    tiles[url] = TileState("WORKING", m.replace("داگرتنی مۆدێلی", "مۆدێل").take(46))
                }
                try {
                    upd("داگرتنی وێنە بە قەبارەی تەواو...")
                    val ref = Uri.parse(url).let { "${it.scheme}://${it.host}/" }
                    val tries = listOf<suspend () -> Pair<ByteArray, String>>(
                        { ImageUtils.downloadRaw(url) },
                        { ImageUtils.downloadRaw(url, ref, ImageUtils.DESKTOP_UA) },
                        { ImageUtils.downloadRaw(ImageUtils.proxied(url)) },
                        { ImageUtils.downloadRaw(r.thumbUrl) },
                    )
                    var got: Pair<ByteArray, String>? = null
                    var err: String? = null
                    for (t in tries) {
                        try { got = t(); break } catch (e: CancellationException) { throw e } catch (e: Exception) { err = e.message }
                    }
                    val (bytes, mime) = got ?: throw java.io.IOException(err ?: "دانابەزێت")
                    val src = withContext(Dispatchers.Default) { ImageUtils.decodeBytes(bytes) }
                    var out = cut(src) { upd(it) }
                    var enh = false
                    if (autoEnhance && max(out.width, out.height) < 1800) {
                        try {
                            val up = Upscaler.upscaleImage(getApplication(), out, 2, src) {
                                upd(it.replace("Upscale ×2 بە AI", "بەرزکردنەوەی کوالیتی"))
                            }
                            out = up; enh = true
                        } catch (e: CancellationException) { throw e } catch (_: Throwable) {}
                    }
                    upd("پاشەکەوتکردن...")
                    val h = Integer.toHexString(url.hashCode())
                    val f = File(inplaceDir, "$h.png")
                    val of = File(inplaceDir, "${h}_orig." + when (mime) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" })
                    val thumb = withContext(Dispatchers.IO) {
                        f.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        of.writeBytes(bytes)
                        val sc = minOf(1f, 520f / max(out.width, out.height))
                        Bitmap.createScaledBitmap(out, (out.width * sc).toInt().coerceAtLeast(1), (out.height * sc).toInt().coerceAtLeast(1), true)
                    }
                    tiles[url] = TileState("DONE", "", f.absolutePath, of.absolutePath, out.width, out.height, enh, thumb)
                } catch (e: CancellationException) {
                    tiles.remove(url); throw e
                } catch (e: OutOfMemoryError) {
                    tiles[url] = TileState("ERROR", "بیرگەی مۆبایل بەس نییە")
                } catch (e: Exception) {
                    tiles[url] = TileState("ERROR", (e.message ?: "هەڵە").take(50))
                }
            }
        }
    }

    fun inplaceAll() {
        var n = 0
        results.forEach { r ->
            val st = tiles[r.fullUrl]?.status ?: ""
            if (st == "" || st == "ERROR") { tiles.remove(r.fullUrl); inplace(r); n++ }
        }
        message = "$n وێنە خرانە ڕیز"
    }

    val doneCount: Int get() = results.count { tiles[it.fullUrl]?.status == "DONE" }

    fun saveInplace(r: ImageResult, quiet: Boolean = false) {
        val st = tiles[r.fullUrl] ?: return
        if (st.status != "DONE") return
        viewModelScope.launch {
            val ok = try {
                withContext(Dispatchers.IO) { ImageUtils.savePngFileToGallery(getApplication(), File(st.file)) }
                true
            } catch (e: Exception) { false }
            if (!quiet) message = if (ok) "✔ پاشەکەوت کرا لە گاڵەری › Pictures/SG search" else "پاشەکەوت سەرنەکەوت"
        }
    }

    fun saveAllInplace() {
        val done = results.filter { tiles[it.fullUrl]?.status == "DONE" }
        done.forEach { saveInplace(it, quiet = true) }
        message = "✔ ${done.size} وێنە پاشەکەوت کران لە گاڵەری › Pictures/SG search"
    }

    fun openInplace(r: ImageResult) {
        val st = tiles[r.fullUrl] ?: return
        if (st.status != "DONE" || busy != null) return
        viewModelScope.launch {
            try {
                val (o, c) = withContext(Dispatchers.IO) {
                    ImageUtils.decodeBytes(File(st.orig).readBytes()) to
                        ImageUtils.decodeBytes(File(st.file).readBytes())
                }
                originalUrl = r.fullUrl; originalFallback = r.thumbUrl
                lowQuality = false
                original = o
                cutout = c
                upscaled = st.enhanced
            } catch (e: Exception) {
                message = "نەتوانرا بکرێتەوە: ${e.message}"
            }
        }
    }

    fun undoInplace(r: ImageResult) {
        tiles.remove(r.fullUrl)
    }

    fun closeEditor() {
        if (busy != null) return
        original = null; cutout = null; upscaled = false
    }

    // ───── بەشی Upscale ی جیا ─────
    var upSrc by mutableStateOf<Bitmap?>(null)
        private set
    private var upColor: Bitmap? = null
    var upRes by mutableStateOf<Bitmap?>(null)
        private set
    var upInfo by mutableStateOf("")
        private set
    private var upName = "image"
    private var upScale = 2

    private fun setUpSource(bmp: Bitmap, color: Bitmap?, name: String) {
        upSrc = bmp; upColor = color; upRes = null; upName = name
        upInfo = "وێنە: ${bmp.width}×${bmp.height} پیکسڵ — دوگمەی 2× یان 4× دابگرە"
    }

    /** دوگمەی Upscale لەسەر ئەنجام یان وێنەی سەرەکی */
    fun upscaleFrom(what: String, scale: Int = 2) {
        if (busy != null) return
        when (what) {
            "cutout" -> {
                val c = cutout ?: return
                val o = original?.takeIf { it.width == c.width && it.height == c.height }
                val bg = bgColor
                if (bg != null) setUpSource(ImageUtils.withBackground(c, bg), null, "bg")
                else setUpSource(c, o, "nobg")
            }
            "original" -> setUpSource(original ?: return, null, "original")
            else -> return
        }
        runUpscale(scale)
    }

    /** دوگمەی 2× لەسەر وێنەیەکی گەڕان: بە قەبارەی تەواو دادەبەزێت و ×2 دەکرێت */
    fun upscaleTile(r: ImageResult) {
        if (busy != null) return
        job?.cancel()
        job = viewModelScope.launch {
            busy = "داگرتنی وێنەکە بۆ Upscale..."
            val ref = Uri.parse(r.fullUrl).let { "${it.scheme}://${it.host}/" }
            val tries = listOf<suspend () -> Bitmap>(
                { ImageUtils.download(r.fullUrl) },
                { ImageUtils.download(r.fullUrl, ref, ImageUtils.DESKTOP_UA) },
                { ImageUtils.download(ImageUtils.proxied(r.fullUrl)) },
                { ImageUtils.download(r.thumbUrl) },
            )
            var bmp: Bitmap? = null
            for (t in tries) {
                try { bmp = t(); break } catch (e: CancellationException) { throw e } catch (_: Exception) {}
            }
            busy = null
            if (bmp == null) { message = "ئەم وێنەیە دانابەزێت"; return@launch }
            setUpSource(bmp, null, "web")
            runUpscale(2)
        }
    }

    /** وێنەیەک لە گاڵەری بۆ Upscale */
    fun upscaleUri(uri: Uri) {
        viewModelScope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) { ImageUtils.decodeUri(getApplication(), uri) }
                setUpSource(bmp, null, "gallery")
            } catch (e: Exception) {
                message = "نەتوانرا وێنەکە بکرێتەوە"
            }
        }
    }

    fun runUpscale(scale: Int) {
        val src = upSrc ?: return
        if (busy != null) return
        val color = upColor
        job?.cancel()
        job = viewModelScope.launch {
            busy = "Upscale ×$scale..."
            val up = try {
                Upscaler.upscaleImage(getApplication(), src, scale, color) { busy = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                message = "Upscale: بیرگەی مۆبایل بەس نییە، ×2 تاقی بکەرەوە"; null
            } catch (e: Exception) {
                message = "Upscale سەرنەکەوت: ${e.message}"; null
            }
            busy = null
            if (up != null) {
                upRes = up
                upScale = scale
                upInfo = "✔ Upscale ×$scale: ${src.width}×${src.height} → ${up.width}×${up.height} پیکسڵ"
                message = "Upscale کرا: ${up.width}×${up.height}"
            }
        }
    }

    fun saveUpscaled() {
        val bmp = upRes ?: return
        viewModelScope.launch {
            message = try {
                withContext(Dispatchers.IO) { ImageUtils.saveToGallery(getApplication(), bmp) }
                "پاشەکەوت کرا لە گاڵەری › Pictures/SG search"
            } catch (e: Exception) {
                "پاشەکەوت نەکرا: ${e.message}"
            }
        }
    }

    suspend fun shareUpscaledUri(): Uri? {
        val bmp = upRes ?: return null
        return withContext(Dispatchers.IO) { ImageUtils.shareUri(getApplication(), bmp) }
    }

    fun closeUpscale() {
        upSrc = null; upColor = null; upRes = null; upInfo = ""
    }

    private fun setImage(bmp: Bitmap) {
        upscaled = false
        original = bmp
        cutout = null
        removeBackground()   // ڕاستەوخۆ باکگراوند لادەبات
    }

    /** دڵی ئەپەکە: بەپێی شێوازی هەڵبژێردراو باکگراوند لادەبات، لەگەڵ شێوازی یەدەگ. */
    /**
     * دڵی ئەپەکە. شیکردنەوە لەسەر کۆپییەکی بچووکتر دەکرێت، بەڵام ئەنجامی کۆتایی
     * بە قەبارە و کوالیتی تەواوی وێنە ئەسڵییەکەیە (ڕەنگەکان دەستکاری ناکرێن).
     */
    private suspend fun cut(src: Bitmap, onStatus: (String) -> Unit): Bitmap {
        val app = getApplication<Application>()
        if (engine == "removebg") {
            try {
                onStatus("لابردنی باکگراوند بە remove.bg...")
                return BackgroundRemover.removeWithRemoveBg(src, removeBgKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "remove.bg سەرنەکەوت — بە IS-Net کرا"
            }
        }
        val work = ImageUtils.workCopy(src)
        val small = if (engine == "fast") {
            BackgroundRemover.removeOnDevice(work, personOnly, focusOnly, onStatus)
        } else try {
            BackgroundRemover.removeIsNet(app, work, personOnly, focusOnly, onStatus)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = "IS-Net: ${e.message} — بە شێوازی خێرا کرا"
            BackgroundRemover.removeOnDevice(work, personOnly, focusOnly, onStatus)
        }
        onStatus("جێبەجێکردن لەسەر کوالیتی تەواو (${src.width}×${src.height})...")
        val full = withContext(Dispatchers.Default) { ImageUtils.applyAlpha(src, small) }
        small.recycle()
        if (work !== src) work.recycle()
        return full
    }

    fun removeBackground() {
        val src = original ?: return
        job = viewModelScope.launch {
            busy = "٢/٢ لابردنی باکگراوند (${src.width}×${src.height})..."
            try {
                cutout = cut(src) { busy = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                message = "وێنەکە زۆر گەورەیە بۆ ئەم مۆبایلە"
            } catch (e: Exception) {
                message = "هەڵە: ${e.message}"
            } finally {
                busy = null
            }
        }
    }

    fun finalBitmap(): Bitmap? = cutout?.let { compose(it) }

    fun save() {
        val bmp = finalBitmap() ?: return
        viewModelScope.launch {
            message = try {
                withContext(Dispatchers.IO) { ImageUtils.saveToGallery(getApplication(), bmp) }
                "پاشەکەوت کرا لە گاڵەری › Pictures/SG search"
            } catch (e: Exception) {
                "پاشەکەوت نەکرا: ${e.message}"
            }
        }
    }

    suspend fun shareUri(): Uri? {
        val bmp = finalBitmap() ?: return null
        return withContext(Dispatchers.IO) { ImageUtils.shareUri(getApplication(), bmp) }
    }

    // ───────────────────────── بەکۆمەڵ ─────────────────────────

    /** وێنە هەڵبژێردراوەکانی ئەنجامی گەڕان دەخاتە ڕیزەوە. */
    fun batchFromSelection() {
        val picked = results.filter { it.fullUrl in selected }
        if (picked.isEmpty()) return
        cancelSelect()
        picked.forEach { inplace(it) }
        message = "${picked.size} وێنە خرانە ڕیز — هەر یەکە لە شوێنی خۆی"
    }

    fun batchFromUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (uris.size == 1) { loadUri(uris[0]); return }
        uris.forEach { batch += BatchItem(nextId++, uri = it) }
        runBatch()
    }

    private fun update(id: Int, f: (BatchItem) -> BatchItem) {
        val i = batch.indexOfFirst { it.id == id }
        if (i >= 0) batch[i] = f(batch[i])
    }

    private fun runBatch() {
        if (batchJob?.isActive == true) return   // کارەکە بەردەوامە و وێنە نوێیەکانیش دەگرێتەوە
        batchJob = viewModelScope.launch {
            val app = getApplication<Application>()
            val dir = File(app.filesDir, "batch").apply { mkdirs() }
            while (true) {
                val item = batch.firstOrNull { it.status == BatchItem.Status.WAITING } ?: break
                val done = batch.count { it.status == BatchItem.Status.DONE || it.status == BatchItem.Status.ERROR }
                val prefix = "وێنەی ${done + 1} لە ${batch.size}"
                batchStatus = prefix
                update(item.id) { it.copy(status = BatchItem.Status.WORKING) }
                try {
                    val src = when {
                        item.uri != null -> withContext(Dispatchers.IO) { ImageUtils.decodeUri(app, item.uri) }
                        else -> download(item.url!!, item.fallbackUrl, item.referer) ?: error("دانابەزێت")
                    }
                    val res = cut(src) { batchStatus = "$prefix — $it" }
                    val f = File(dir, "cut_${item.id}.png")
                    val thumb = withContext(Dispatchers.IO) {
                        f.outputStream().use { res.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        val s = 320f / max(res.width, res.height)
                        Bitmap.createScaledBitmap(
                            res, (res.width * s).toInt().coerceAtLeast(1),
                            (res.height * s).toInt().coerceAtLeast(1), true
                        )
                    }
                    if (res != src) res.recycle()
                    src.recycle()
                    update(item.id) { it.copy(status = BatchItem.Status.DONE, thumb = thumb, file = f) }
                } catch (e: CancellationException) {
                    update(item.id) { it.copy(status = BatchItem.Status.WAITING) }
                    throw e
                } catch (e: Throwable) {
                    val msg = if (e is OutOfMemoryError) "زۆر گەورەیە" else (e.message ?: "هەڵە")
                    update(item.id) { it.copy(status = BatchItem.Status.ERROR, error = msg) }
                }
            }
            val ok = batch.count { it.status == BatchItem.Status.DONE }
            batchStatus = null
            message = "تەواو بوو: $ok لە ${batch.size} وێنە"
        }
    }

    fun stopBatch() {
        batchJob?.cancel()
        batchStatus = null
    }

    fun clearBatch() {
        stopBatch()
        batch.forEach { it.file?.delete() }
        batch.clear()
    }

    fun retryFailed() {
        for (i in batch.indices) {
            if (batch[i].status == BatchItem.Status.ERROR) {
                batch[i] = batch[i].copy(status = BatchItem.Status.WAITING, error = null)
            }
        }
        runBatch()
    }

    private fun decodeFile(f: File): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { d, _, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            d.isMutableRequired = true
        }

    /** کردنەوەی ئەنجامێکی بەکۆمەڵ لە بەشی سەرەوە بۆ گۆڕینی ڕەنگ یان پاشەکەوتکردنی تەنها. */
    fun openBatchItem(item: BatchItem) {
        val f = item.file ?: return
        viewModelScope.launch {
            cutout = withContext(Dispatchers.IO) { decodeFile(f) }
            original = null
        }
    }

    fun saveAll() {
        val files = batch.mapNotNull { it.file }
        if (files.isEmpty()) return
        viewModelScope.launch {
            busy = "پاشەکەوتکردنی ${files.size} وێنە..."
            var ok = 0
            withContext(Dispatchers.IO) {
                for (f in files) {
                    try {
                        val b = decodeFile(f)
                        val out = compose(b)
                        ImageUtils.saveToGallery(getApplication(), out)
                        if (out != b) out.recycle()
                        b.recycle()
                        ok++
                    } catch (_: Exception) {
                    }
                }
            }
            busy = null
            message = "$ok وێنە پاشەکەوت کرا لە گاڵەری › Pictures/SG search"
        }
    }

    suspend fun shareAllUris(): ArrayList<Uri> = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val dir = File(app.cacheDir, "shared").apply { mkdirs() }
        val out = ArrayList<Uri>()
        batch.mapNotNull { it.file }.forEachIndexed { i, f ->
            try {
                val b = decodeFile(f)
                val o = compose(b)
                val sf = File(dir, "result_${i + 1}.png")
                sf.outputStream().use { o.compress(Bitmap.CompressFormat.PNG, 100, it) }
                out += FileProvider.getUriForFile(app, "${app.packageName}.files", sf)
            } catch (_: Exception) {
            }
        }
        out
    }

    // ───────────────────────── پاشەکەوت / گەڕاندنەوەی دۆخ ─────────────────────────

    private var started = false

    /**
     * restore = true: ئەندرۆید ئەپەکەی لە پشتەوە داخستبوو (بۆ نموونە چوویتە سەر ئەپێکی تر) → وەک خۆی دەگەڕێتەوە.
     * restore = false: بەکارهێنەر ئەپەکەی بە تەواوی داخستبوو → لە سەرەتاوە دەست پێدەکات.
     */
    fun start(restore: Boolean) {
        if (started) return
        started = true
        if (restore) {
            viewModelScope.launch { restoreState() }
        } else {
            restoring = false
            viewModelScope.launch(Dispatchers.IO) {
                stateDir.listFiles()?.forEach { it.delete() }
                File(getApplication<Application>().filesDir, "batch").listFiles()?.forEach { it.delete() }
            }
        }
    }

    private fun resultToJson(r: ImageResult) = org.json.JSONObject()
        .put("f", r.fullUrl).put("t", r.thumbUrl).put("ti", r.title)
        .put("p", r.pageUrl).put("w", r.width).put("h", r.height)

    private fun resultFromJson(o: org.json.JSONObject) = ImageResult(
        o.optString("f"), o.optString("t"), o.optString("ti"),
        o.optString("p"), o.optInt("w"), o.optInt("h")
    )

    /** کاتێک ئەپ دەچێتە پشتەوە بانگ دەکرێت (onStop). */
    fun persist() {
        if (restoring) return
        val o = org.json.JSONObject()
            .put("query", query).put("lastQuery", lastQuery).put("page", page)
            .put("source", source).put("canLoadMore", canLoadMore)
            .put("bgColor", bgColor ?: org.json.JSONObject.NULL)
            .put("upscaled", upscaled).put("lowQuality", lowQuality)
            .put("originalUrl", originalUrl ?: "").put("originalFallback", originalFallback ?: "")
            .put("hasOriginal", original != null).put("hasCutout", cutout != null)
            .put("nextId", nextId)
        val ra = org.json.JSONArray()
        results.forEach { ra.put(resultToJson(it)) }
        o.put("results", ra)
        val ba = org.json.JSONArray()
        batch.forEach { b ->
            ba.put(
                org.json.JSONObject().put("id", b.id).put("url", b.url ?: "")
                    .put("fb", b.fallbackUrl ?: "").put("ref", b.referer ?: "")
                    .put("uri", b.uri?.toString() ?: "")
                    // ئەوەی لە کاردا بوو دووبارە دەکرێتەوە
                    .put("st", if (b.status == BatchItem.Status.WORKING) "WAITING" else b.status.name)
                    .put("file", b.file?.absolutePath ?: "").put("err", b.error ?: "")
            )
        }
        o.put("batch", ba)
        val orig = original
        val cut = cutout
        // بە هاوکاتی پاشەکەوت دەکرێت؛ تەنها ئەگەر وێنەکە گۆڕابێت
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            try {
                if (orig != null && orig !== savedOriginal) {
                    originalFile.outputStream().use { orig.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    savedOriginal = orig
                } else if (orig == null) originalFile.delete()
                if (cut != null && cut !== savedCutout) {
                    cutoutFile.outputStream().use { cut.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    savedCutout = cut
                } else if (cut == null) cutoutFile.delete()
                val tmp = File(stateDir, "state.json.tmp")
                tmp.writeText(o.toString())
                tmp.renameTo(stateJson)
            } catch (_: Throwable) {
            }
        }
    }

    private fun decodeState(f: File): Bitmap? = try {
        if (f.exists()) ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { d, _, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            d.isMutableRequired = true
        } else null
    } catch (_: Throwable) { null }

    private suspend fun restoreState() {
        try {
            if (!stateJson.exists()) return
            val o = withContext(Dispatchers.IO) { org.json.JSONObject(stateJson.readText()) }
            query = o.optString("query")
            lastQuery = o.optString("lastQuery")
            page = o.optInt("page", 1)
            source = o.optString("source")
            canLoadMore = o.optBoolean("canLoadMore")
            bgColor = if (o.isNull("bgColor")) null else o.optInt("bgColor")
            lowQuality = o.optBoolean("lowQuality")
            originalUrl = o.optString("originalUrl").ifBlank { null }
            originalFallback = o.optString("originalFallback").ifBlank { null }
            nextId = o.optInt("nextId", 1)
            val ra = o.optJSONArray("results")
            if (ra != null) results = List(ra.length()) { resultFromJson(ra.getJSONObject(it)) }

            val orig = if (o.optBoolean("hasOriginal")) withContext(Dispatchers.IO) { decodeState(originalFile) } else null
            val cut = if (o.optBoolean("hasCutout")) withContext(Dispatchers.IO) { decodeState(cutoutFile) } else null
            savedOriginal = orig; savedCutout = cut
            original = orig
            cutout = cut
            upscaled = o.optBoolean("upscaled") && cut != null

            val ba = o.optJSONArray("batch")
            if (ba != null) {
                for (i in 0 until ba.length()) {
                    val b = ba.getJSONObject(i)
                    val file = b.optString("file").ifBlank { null }?.let { File(it) }?.takeIf { it.exists() }
                    var st = runCatching { BatchItem.Status.valueOf(b.optString("st")) }.getOrDefault(BatchItem.Status.WAITING)
                    if (st == BatchItem.Status.DONE && file == null) st = BatchItem.Status.WAITING
                    val thumb = if (file != null) withContext(Dispatchers.IO) {
                        decodeState(file)?.let { full ->
                            val sc = 320f / max(full.width, full.height)
                            Bitmap.createScaledBitmap(
                                full, (full.width * sc).toInt().coerceAtLeast(1),
                                (full.height * sc).toInt().coerceAtLeast(1), true
                            ).also { if (it !== full) full.recycle() }
                        }
                    } else null
                    batch += BatchItem(
                        id = b.optInt("id"),
                        url = b.optString("url").ifBlank { null },
                        fallbackUrl = b.optString("fb").ifBlank { null },
                        referer = b.optString("ref").ifBlank { null },
                        uri = b.optString("uri").ifBlank { null }?.let { Uri.parse(it) },
                        status = st, thumb = thumb, file = file,
                        error = b.optString("err").ifBlank { null }
                    )
                }
            }
        } catch (_: Throwable) {
        } finally {
            restoring = false
        }
        // ئەو کارانەی کە لە کاتی داخستندا تەواو نەبوون بەردەوام دەبن
        if (original != null && cutout == null) removeBackground()
        if (batch.any { it.status == BatchItem.Status.WAITING }) runBatch()
    }
}
