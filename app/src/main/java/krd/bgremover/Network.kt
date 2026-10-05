package krd.bgremover

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

data class ImageResult(
    val fullUrl: String,
    val thumbUrl: String,
    val title: String,
    /** پەڕەی سەرچاوە؛ بۆ ئەو ماڵپەڕانەی بەبێ Referer وێنە نادەن */
    val pageUrl: String = "",
    val width: Int = 0,
    val height: Int = 0
)

/** HTTP client هاوبەش. User-Agent زیاد دەکات چونکە هەندێک ماڵپەڕ بەبێ ئەوە وێنە نادەن. */
object Net {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36"

    /** Wikimedia داوای ناسنامەی ڕاستەقینەی ئەپ دەکات؛ User-Agent ی وەک وێبگەڕ بلۆک دەکات (HTTP 403). */
    const val WIKI_UA =
        "BgRemover/2.1 (https://github.com/goranabdullas24-ops/BgRemover; Android image app) okhttp/4.12"

    private fun isWikimedia(host: String) =
        host.endsWith("wikimedia.org") || host.endsWith("wikipedia.org") || host.endsWith("wikidata.org")

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor { chain ->
            val req = chain.request()
            if (req.header("User-Agent") != null) return@addInterceptor chain.proceed(req)
            val ua = if (isWikimedia(req.url.host)) WIKI_UA else UA
            chain.proceed(req.newBuilder().header("User-Agent", ua).build())
        }
        .build()

    fun bytes(request: Request): ByteArray {
        var attempt = 0
        while (true) {
            var wait = 0L
            val body = client.newCall(request).execute().use { resp ->
                // 429/503: سێرڤەر دەڵێت «خێرا مەبە» → چاوەڕێ و دووبارە (Wikimedia کاتی داگرتنی چەند وێنەیەک)
                if ((resp.code == 429 || resp.code == 503) && attempt < 3) {
                    wait = (resp.header("Retry-After")?.toLongOrNull()?.times(1000) ?: (1500L * (attempt + 1))).coerceIn(500, 8000)
                    return@use null
                }
                val body = resp.body?.bytes() ?: ByteArray(0)
                if (!resp.isSuccessful) {
                    val hint = when (resp.code) {
                        401, 403 -> "ڕێگە نەدرا"
                        404 -> "نەدۆزرایەوە"
                        429 -> "داواکاری زۆرە، کەمێک چاوەڕێ بکە"
                        in 500..599 -> "سێرڤەر کێشەی هەیە"
                        else -> "هەڵەی تۆڕ"
                    }
                    throw IOException("$hint (HTTP ${resp.code})")
                }
                body
            }
            if (body != null) return body
            attempt++
            Thread.sleep(wait)
        }
    }
}

object ImageSearch {

    /**
     * ئەگەر کلیلی Serper هەبێت → ئەنجامی ڕاستەقینەی Google Images.
     * ئەگەر نا → چەند سەرچاوەیەکی بەخۆڕایی پێکەوە (ویکیپیدیا، Wikimedia Commons، Openverse).
     * page لە ١ ەوە دەست پێدەکات؛ بۆ «زیاتر» page زیاد دەکرێت.
     */
    suspend fun search(query: String, serperKey: String, page: Int = 1): Pair<String, List<ImageResult>> =
        withContext(Dispatchers.IO) {
            // ئەگەر Serper کار نەکات (کلیل هەڵە/تەواوبوو) یان هیچی نەدا → سەرچاوە بەخۆڕاییەکان
            val google = if (serperKey.isNotBlank())
                runCatching { googleViaSerper(query, serperKey.trim(), page) }.getOrNull().orEmpty()
            else emptyList()
            if (google.isNotEmpty()) "Google" to google else "free" to freeSearch(query, page)
        }

    private fun googleViaSerper(q: String, key: String, page: Int): List<ImageResult> {
        val payload = JSONObject().put("q", q).put("num", 100).put("page", page).toString()
        val req = Request.Builder()
            .url("https://google.serper.dev/images")
            .header("X-API-KEY", key)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        val json = JSONObject(String(Net.bytes(req)))
        val arr = json.optJSONArray("images") ?: return emptyList()
        val out = ArrayList<ImageResult>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val full = o.optString("imageUrl")
            if (full.isBlank()) continue
            val thumb = o.optString("thumbnailUrl").ifBlank { full }
            out += ImageResult(
                full, thumb, o.optString("title"), o.optString("link"),
                o.optInt("imageWidth"), o.optInt("imageHeight")
            )
        }
        return out
    }

    /** سەرچاوە بەخۆڕاییەکان بە هاوکاتی؛ ئەگەر یەکێکیان کار نەکات ئەوانی تر بەردەوام دەبن. */
    private suspend fun freeSearch(q: String, page: Int): List<ImageResult> = coroutineScope {
        val jobs = buildList<suspend () -> List<ImageResult>> {
            if (page <= 3) {
                // وێنەی سەرەکیی بابەتەکانی ویکیپیدیا: زۆر پەیوەندیدار بە ناوی کەس و شوێن
                add { wikipedia("ckb", q, page) }
                add { wikipedia("en", q, page) }
                add { wikipedia("ar", q, page) }
            }
            add { wikimedia(q, page) }
            add { openverse(q, page) }
        }.map { f -> async { runCatching { f() } } }
        val results = jobs.awaitAll()
        if (results.all { it.isFailure }) throw results.first().exceptionOrNull()!!
        val out = LinkedHashMap<String, ImageResult>()
        // تێکەڵکردن بە نۆرە بۆ ئەوەی هەموو سەرچاوەکان لە سەرەتادا دەربکەون
        val lists = results.mapNotNull { it.getOrNull() }
        val maxLen = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until maxLen) for (l in lists) if (i < l.size) out.putIfAbsent(l[i].fullUrl.substringBefore('?').lowercase(), l[i])
        out.values.toList()
    }

    private fun wikipedia(lang: String, q: String, page: Int): List<ImageResult> {
        val url = HttpUrl.Builder()
            .scheme("https").host("$lang.wikipedia.org").addPathSegments("w/api.php")
            .addQueryParameter("action", "query")
            .addQueryParameter("format", "json")
            .addQueryParameter("generator", "search")
            .addQueryParameter("gsrsearch", q)
            .addQueryParameter("gsrlimit", "20")
            .addQueryParameter("gsroffset", ((page - 1) * 20).toString())
            .addQueryParameter("prop", "pageimages")
            .addQueryParameter("piprop", "original|thumbnail")
            .addQueryParameter("pithumbsize", "400")
            .build()
        val json = JSONObject(String(Net.bytes(Request.Builder().url(url).build())))
        val pages = json.optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
        val items = ArrayList<Pair<Int, ImageResult>>()
        val keys = pages.keys()
        while (keys.hasNext()) {
            val p = pages.getJSONObject(keys.next())
            val orig = p.optJSONObject("original")
            val full = orig?.optString("source").orEmpty()
            if (full.isBlank() || full.endsWith(".svg", true)) continue
            val thumb = p.optJSONObject("thumbnail")?.optString("source").orEmpty().ifBlank { full }
            items += p.optInt("index", 999) to ImageResult(
                full, thumb, p.optString("title"), "", orig?.optInt("width") ?: 0, orig?.optInt("height") ?: 0
            )
        }
        return items.sortedBy { it.first }.map { it.second }
    }

    private fun wikimedia(q: String, page: Int): List<ImageResult> {
        val url = HttpUrl.Builder()
            .scheme("https").host("commons.wikimedia.org").addPathSegments("w/api.php")
            .addQueryParameter("action", "query")
            .addQueryParameter("format", "json")
            .addQueryParameter("generator", "search")
            .addQueryParameter("gsrsearch", "$q filetype:bitmap")
            .addQueryParameter("gsrnamespace", "6")
            .addQueryParameter("gsrlimit", "50")
            .addQueryParameter("gsroffset", ((page - 1) * 50).toString())
            .addQueryParameter("prop", "imageinfo")
            .addQueryParameter("iiprop", "url|mime|size")
            .addQueryParameter("iiurlwidth", "400")
            .build()
        val json = JSONObject(String(Net.bytes(Request.Builder().url(url).build())))
        val pages = json.optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
        val items = ArrayList<Pair<Int, ImageResult>>()
        val keys = pages.keys()
        while (keys.hasNext()) {
            val p = pages.getJSONObject(keys.next())
            val info = p.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
            val mime = info.optString("mime")
            if (mime !in setOf("image/jpeg", "image/png", "image/webp")) continue
            val full = info.optString("url")
            val thumb = info.optString("thumburl").ifBlank { full }
            items += p.optInt("index", 999) to ImageResult(
                full, thumb, p.optString("title"), "", info.optInt("width"), info.optInt("height")
            )
        }
        return items.sortedBy { it.first }.map { it.second }
    }

    /** Openverse: کتێبخانەی کراوەی +٨٠٠ ملیۆن وێنە (Flickr، میوزەخانەکان...). بێ کلیل. */
    private fun openverse(q: String, page: Int): List<ImageResult> {
        val url = HttpUrl.Builder()
            .scheme("https").host("api.openverse.org").addPathSegments("v1/images/")
            .addQueryParameter("q", q)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("page_size", "20")
            .addQueryParameter("mature", "false")
            .build()
        val json = JSONObject(String(Net.bytes(Request.Builder().url(url).build())))
        val arr = json.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<ImageResult>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val full = o.optString("url")
            if (full.isBlank() || full.endsWith(".svg", true)) continue
            out += ImageResult(
                full, o.optString("thumbnail").ifBlank { full }, o.optString("title"),
                o.optString("foreign_landing_url"), o.optInt("width"), o.optInt("height")
            )
        }
        return out
    }
}

object ImageUtils {
    /** گەورەترین لا. گەورەتر لەمە بچووک دەکرێتەوە بۆ ئەوەی مۆبایل پڕ نەبێت. */
    const val MAX_PIXELS = 24_000_000L

    /** قەبارەی کارکردن بۆ دۆزینەوەی باکگراوند؛ ئەنجامی کۆتایی بە قەبارەی تەواوی ئەسڵی دەبێت. */
    const val WORK_SIDE = 1600

    /** کۆپییەکی بچووکتر بۆ شیکردنەوە (ئەسڵەکە دەستکاری ناکرێت). */
    fun workCopy(src: Bitmap): Bitmap {
        val m = max(src.width, src.height)
        if (m <= WORK_SIDE) return src
        val s = WORK_SIDE.toFloat() / m
        return Bitmap.createScaledBitmap(
            src, (src.width * s).roundToInt().coerceAtLeast(1),
            (src.height * s).roundToInt().coerceAtLeast(1), true
        )
    }

    /**
     * ماسکی ئەنجامە بچووکەکە (تەنها ئەلفا) دەخاتە سەر وێنە ئەسڵییەکە بە قەبارەی تەواو.
     * ڕەنگ و وردەکارییەکانی وێنەکە هیچ دەستکاری ناکرێن.
     */
    fun applyAlpha(src: Bitmap, cut: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val a = cut.extractAlpha()
        val aFull = if (a.width == w && a.height == h) a else Bitmap.createScaledBitmap(a, w, h, true)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setHasAlpha(true)
        val band = 64
        val sp = IntArray(w * band)
        val ap = IntArray(w * band)
        var y = 0
        while (y < h) {
            val rows = minOf(band, h - y)
            src.getPixels(sp, 0, w, 0, y, w, rows)
            aFull.getPixels(ap, 0, w, 0, y, w, rows)
            for (i in 0 until w * rows) {
                sp[i] = (ap[i] and 0xFF000000.toInt()) or (sp[i] and 0x00FFFFFF)
            }
            out.setPixels(sp, 0, w, 0, y, w, rows)
            y += rows
        }
        if (aFull !== a) aFull.recycle()
        a.recycle()
        return out
    }

    private fun decode(source: ImageDecoder.Source): Bitmap {
        val bmp = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
            val w = info.size.width
            val h = info.size.height
            // قەبارەی ئەسڵی وەک خۆی؛ تەنها وێنەی زۆر زۆر گەورە (> ٢٤ مێگاپیکسڵ) بچووک دەکرێتەوە
            // بۆ ئەوەی مۆبایل پڕ نەبێت
            val px = w.toLong() * h
            if (px > MAX_PIXELS) {
                val s = kotlin.math.sqrt(MAX_PIXELS.toDouble() / px).toFloat()
                decoder.setTargetSize(
                    (w * s).roundToInt().coerceAtLeast(1),
                    (h * s).roundToInt().coerceAtLeast(1)
                )
            }
        }
        return if (bmp.config == Bitmap.Config.ARGB_8888) bmp
        else bmp.copy(Bitmap.Config.ARGB_8888, true)
    }

    fun decodeBytes(bytes: ByteArray): Bitmap =
        try {
            decode(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
        } catch (e: Exception) {
            throw IOException("ئەم فایلە وێنە نییە یان پشتگیری ناکرێت")
        }

    fun decodeUri(ctx: Context, uri: Uri): Bitmap =
        decode(ImageDecoder.createSource(ctx.contentResolver, uri))

    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Safari/537.36"

    /** پرۆکسی گشتیی وێنە (wsrv.nl): وێنەکە بە قەبارەی ئەسڵی لە سێرڤەرەکەوە دەهێنێت. */
    fun proxied(url: String): String =
        "https://wsrv.nl/?url=" + java.net.URLEncoder.encode(url, "UTF-8") + "&q=100"

    suspend fun download(url: String, referer: String? = null, ua: String? = null): Bitmap = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url).header("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
        if (!referer.isNullOrBlank()) b.header("Referer", referer)
        if (!ua.isNullOrBlank()) b.header("User-Agent", ua)
        decodeBytes(Net.bytes(b.build()))
    }

    /** وێنەی بێ باکگراوند لەسەر ڕەنگێک دادەنێت. null = ڕوون (transparent). */
    fun withBackground(fg: Bitmap, color: Int?): Bitmap {
        if (color == null) return fg
        val out = Bitmap.createBitmap(fg.width, fg.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(color)
        c.drawBitmap(fg, 0f, 0f, null)
        return out
    }

    /** داگرتنی فایلی ئەسڵی وەک خۆی (بێ گۆڕین و بێ بچووککردنەوە). */
    suspend fun downloadRaw(url: String, referer: String? = null, ua: String? = null): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
        val rb = Request.Builder().url(url).header("Accept", "image/jpeg,image/png,image/webp,image/*;q=0.8,*/*;q=0.5")
        if (!referer.isNullOrBlank()) rb.header("Referer", referer)
        if (!ua.isNullOrBlank()) rb.header("User-Agent", ua)
        val req = rb.build()
        val bytes = Net.bytes(req)
        val mime = sniffMime(bytes)
        if (mime != null) return@withContext bytes to mime
        // AVIF/HEIC/BMP...: دەیکاتەوە و وەک PNG (بێ لەدەستدانی کوالیتی) پاشەکەوتی دەکات
        val bmp = decodeBytes(bytes)
        val bos = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        bos.toByteArray() to "image/png"
    }

    private fun sniffMime(b: ByteArray): String? = when {
        b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "image/jpeg"
        b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() -> "image/png"
        b.size > 12 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(b, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        b.size > 6 && String(b, 0, 3, Charsets.US_ASCII) == "GIF" -> "image/gif"
        else -> null
    }

    /** پاشەکەوتکردنی فایلی ئەسڵی لە گاڵەری › Pictures/BgRemover/Original */
    fun saveBytesToGallery(ctx: Context, bytes: ByteArray, mime: String): Uri {
        val ext = when (mime) {
            "image/png" -> "png"; "image/webp" -> "webp"; "image/gif" -> "gif"; else -> "jpg"
        }
        val r = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "img_${System.currentTimeMillis()}.$ext")
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/BgRemover/Original")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("نەتوانرا فایل دروست بکرێت")
        r.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IOException("نەتوانرا فایل بنووسرێت")
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        r.update(uri, values, null, null)
        return uri
    }

    /** فایلی PNG ی ئامادە (وەک خۆی، بێ دووبارە کۆمپرێسکردن) → گاڵەری › Pictures/BgRemover */
    fun savePngFileToGallery(ctx: Context, file: File): Uri {
        val r = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "bg_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/BgRemover")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("نەتوانرا فایل دروست بکرێت")
        r.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            ?: throw IOException("نەتوانرا فایل بنووسرێت")
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        r.update(uri, values, null, null)
        return uri
    }

    fun saveToGallery(ctx: Context, bmp: Bitmap): Uri {
        val r = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "bg_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/BgRemover")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("نەتوانرا فایل دروست بکرێت")
        r.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            ?: throw IOException("نەتوانرا فایل بنووسرێت")
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        r.update(uri, values, null, null)
        return uri
    }

    fun shareUri(ctx: Context, bmp: Bitmap): Uri {
        val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
        val f = File(dir, "result.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    }

    fun cameraUri(ctx: Context): Uri {
        val f = File(ctx.cacheDir, "camera.jpg")
        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    }
}
