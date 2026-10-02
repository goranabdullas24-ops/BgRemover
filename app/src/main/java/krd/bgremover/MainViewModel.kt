package krd.bgremover

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    var serperKey by mutableStateOf(prefs.getString("serper", "") ?: "")
        private set
    var removeBgKey by mutableStateOf(prefs.getString("removebg", "") ?: "")
        private set
    var useRemoveBg by mutableStateOf(prefs.getBoolean("use_removebg", false))
        private set

    private var job: Job? = null

    fun saveSettings(serper: String, removeBg: String, useRb: Boolean) {
        serperKey = serper.trim()
        removeBgKey = removeBg.trim()
        useRemoveBg = useRb && removeBgKey.isNotBlank()
        prefs.edit()
            .putString("serper", serperKey)
            .putString("removebg", removeBgKey)
            .putBoolean("use_removebg", useRemoveBg)
            .apply()
    }

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
            try {
                val (src, list) = ImageSearch.search(q, serperKey)
                source = src
                results = list
                if (list.isEmpty()) message = "هیچ وێنەیەک نەدۆزرایەوە، ناوێکی تر تاقی بکەرەوە"
            } catch (e: Exception) {
                message = "هەڵە لە گەڕان: ${e.message}"
            } finally {
                busy = null
            }
        }
    }

    fun pick(r: ImageResult) = loadFromUrl(r.fullUrl, r.thumbUrl)

    private fun loadFromUrl(url: String, fallback: String?) {
        job?.cancel()
        job = viewModelScope.launch {
            busy = "داگرتنی وێنە..."
            val bmp = try {
                ImageUtils.download(url)
            } catch (e: Exception) {
                // هەندێک ماڵپەڕ داگرتن قەدەغە دەکەن → وێنە بچووکەکە بەکاردەهێنین
                if (fallback != null && fallback != url) {
                    try { ImageUtils.download(fallback) } catch (_: Exception) { null }
                } else null
            }
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

    fun removeBackground() {
        val src = original ?: return
        job = viewModelScope.launch {
            busy = "لابردنی باکگراوند..."
            try {
                cutout = if (useRemoveBg && removeBgKey.isNotBlank()) {
                    try {
                        BackgroundRemover.removeWithRemoveBg(src, removeBgKey)
                    } catch (e: Exception) {
                        message = "remove.bg سەرنەکەوت، بە AI ی ناو مۆبایل دەکرێت"
                        BackgroundRemover.removeOnDevice(src) { busy = it }
                    }
                } else {
                    BackgroundRemover.removeOnDevice(src) { busy = it }
                }
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
}
