package krd.bgremover

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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

    var serperKey by mutableStateOf(prefs.getString("serper", "") ?: "")
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
        val attempts = buildList<suspend () -> Bitmap> {
            add { ImageUtils.download(url) }
            // هەندێک ماڵپەڕ تەنها لەگەڵ Referer وێنە دەدەن
            add {
                val ref = referer?.ifBlank { null }
                    ?: Uri.parse(url).let { "${it.scheme}://${it.host}/" }
                ImageUtils.download(url, ref)
            }
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

    /** داگرتنی فایلێک بەبێ لابردنی باکگراوند (ئەسڵی، کوالیتی تەواو). */
    private suspend fun saveRaw(url: String, fallback: String?): Boolean {
        val app = getApplication<Application>()
        val (bytes, mime) = try {
            ImageUtils.downloadRaw(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (fallback != null && fallback != url) {
                try { ImageUtils.downloadRaw(fallback) } catch (_: Exception) { return false }
            } else return false
        }
        return try {
            withContext(Dispatchers.IO) { ImageUtils.saveBytesToGallery(app, bytes, mime) }
            true
        } catch (_: Exception) { false }
    }

    /** وێنەی سەرەکی (پێش لابردن) پاشەکەوت دەکات. */
    fun saveOriginal() {
        viewModelScope.launch {
            busy = "داگرتنی وێنەی ئەسڵی..."
            val url = originalUrl
            val ok = if (url != null && !upscaled) saveRaw(url, originalFallback) else {
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
            message = if (ok) "پاشەکەوت کرا لە گاڵەری › Pictures/BgRemover/Original" else "داگرتن سەرنەکەوت"
        }
    }

    /** وێنە هەڵبژێردراوەکان بەبێ لابردنی باکگراوند دادەبەزێنێت. */
    fun downloadSelected() {
        val picked = results.filter { it.fullUrl in selected }
        if (picked.isEmpty()) return
        cancelSelect()
        viewModelScope.launch {
            var ok = 0
            picked.forEachIndexed { i, r ->
                busy = "داگرتنی وێنەی ${i + 1} لە ${picked.size}..."
                if (saveRaw(r.fullUrl, r.thumbUrl)) ok++
            }
            busy = null
            message = "$ok لە ${picked.size} وێنە دابەزی › Pictures/BgRemover/Original"
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
            } else setImage(prepare(bmp))
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
                setImage(prepare(bmp))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                busy = null
                message = "نەتوانرا وێنەکە بکرێتەوە"
            }
        }
    }

    // ───────────────────────── Upscale ─────────────────────────

    /** وێنە بچووکەکان خۆکارانە بە AI گەورە و ڕوون دەکرێنەوە پێش لابردنی باکگراوند. */
    var autoUpscale by mutableStateOf(prefs.getBoolean("auto_upscale", true))
        private set
    /** وێنەی ئێستا upscale کراوە؟ */
    var upscaled by mutableStateOf(false)
        private set

    fun changeAutoUpscale(v: Boolean) {
        autoUpscale = v
        prefs.edit().putBoolean("auto_upscale", v).apply()
    }

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

    /** ئەگەر Upscale ی خۆکار چالاک بێت و وێنەکە بچووک بێت، پێشتر upscale دەکرێت. */
    private suspend fun prepare(bmp: Bitmap): Bitmap {
        upscaled = false
        if (!autoUpscale || max(bmp.width, bmp.height) >= Upscaler.AUTO_BELOW) return bmp
        original = bmp
        val up = upscaleSafe(bmp, 2400) { busy = "وێنەکە بچووکە (${bmp.width}×${bmp.height}) — $it" }
        busy = null
        return if (up != null) { upscaled = true; up } else bmp
    }

    /** دوگمەی Upscale: وێنەی ئێستا ×٤ گەورە و ڕوون دەکاتەوە، پاشان باکگراوند لادەبات. */
    fun upscaleNow() {
        val src = original ?: return
        job?.cancel()
        job = viewModelScope.launch {
            val up = upscaleSafe(src, 4096) { busy = it }
            busy = null
            if (up != null) {
                upscaled = true
                setImage(up)
                message = "Upscale کرا: ${src.width}×${src.height} → ${up.width}×${up.height}"
            }
        }
    }

    private fun setImage(bmp: Bitmap) {
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
                "پاشەکەوت کرا لە گاڵەری › Pictures/BgRemover"
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
        picked.forEach { batch += BatchItem(nextId++, url = it.fullUrl, fallbackUrl = it.thumbUrl, referer = it.pageUrl) }
        cancelSelect()
        runBatch()
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
            val dir = File(app.cacheDir, "batch").apply { mkdirs() }
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
                    }.let { b ->
                        if (autoUpscale && max(b.width, b.height) < Upscaler.AUTO_BELOW)
                            upscaleSafe(b, 2400) { batchStatus = "$prefix — $it" } ?: b
                        else b
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
            message = "$ok وێنە پاشەکەوت کرا لە گاڵەری › Pictures/BgRemover"
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
}
