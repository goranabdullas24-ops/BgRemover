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

    private var job: Job? = null

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

    fun pick(r: ImageResult) = loadFromUrl(r.fullUrl, r.thumbUrl)

    private suspend fun download(url: String, fallback: String?): Bitmap? =
        try {
            ImageUtils.download(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // هەندێک ماڵپەڕ داگرتن قەدەغە دەکەن → وێنە بچووکەکە بەکاردەهێنین
            if (fallback != null && fallback != url) {
                try { ImageUtils.download(fallback) } catch (_: Exception) { null }
            } else null
        }

    private fun loadFromUrl(url: String, fallback: String?) {
        job?.cancel()
        job = viewModelScope.launch {
            busy = "داگرتنی وێنە..."
            val bmp = download(url, fallback)
            busy = null
            if (bmp == null) {
                message = "ئەم وێنەیە دانابەزێت، یەکێکی تر هەڵبژێرە"
            } else setImage(bmp)
        }
    }

    fun loadUri(uri: Uri) {
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

    private fun setImage(bmp: Bitmap) {
        original = bmp
        cutout = null
        removeBackground()   // ڕاستەوخۆ باکگراوند لادەبات
    }

    /** دڵی ئەپەکە: بەپێی شێوازی هەڵبژێردراو باکگراوند لادەبات، لەگەڵ شێوازی یەدەگ. */
    private suspend fun cut(src: Bitmap, onStatus: (String) -> Unit): Bitmap {
        val app = getApplication<Application>()
        return when (engine) {
            "removebg" -> try {
                onStatus("لابردنی باکگراوند بە remove.bg...")
                BackgroundRemover.removeWithRemoveBg(src, removeBgKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "remove.bg سەرنەکەوت — بە IS-Net کرا"
                BackgroundRemover.removeIsNet(app, src, onStatus)
            }
            "fast" -> BackgroundRemover.removeOnDevice(src, onStatus)
            else -> try {
                BackgroundRemover.removeIsNet(app, src, onStatus)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "IS-Net: ${e.message} — بە شێوازی خێرا کرا"
                BackgroundRemover.removeOnDevice(src, onStatus)
            }
        }
    }

    fun removeBackground() {
        val src = original ?: return
        job = viewModelScope.launch {
            busy = "لابردنی باکگراوند..."
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

    fun finalBitmap(): Bitmap? = cutout?.let { ImageUtils.withBackground(it, bgColor) }

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
        picked.forEach { batch += BatchItem(nextId++, url = it.fullUrl, fallbackUrl = it.thumbUrl) }
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
                        else -> download(item.url!!, item.fallbackUrl) ?: error("دانابەزێت")
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
                        val out = ImageUtils.withBackground(b, bgColor)
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
                val o = ImageUtils.withBackground(b, bgColor)
                val sf = File(dir, "result_${i + 1}.png")
                sf.outputStream().use { o.compress(Bitmap.CompressFormat.PNG, 100, it) }
                out += FileProvider.getUriForFile(app, "${app.packageName}.files", sf)
            } catch (_: Exception) {
            }
        }
        out
    }
}
