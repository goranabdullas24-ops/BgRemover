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

data class ImageResult(val fullUrl: String, val thumbUrl: String, val title: String)

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
            val ua = if (isWikimedia(req.url.host)) WIKI_UA else UA
            chain.proceed(req.newBuilder().header("User-Agent", ua).build())
        }
        .build()

    fun bytes(request: Request): ByteArray =
        client.newCall(request).execute().use { resp ->
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
}

object ImageSearch {

    /**
     * ئەگەر کلیلی Serper هەبێت → ئەنجامی ڕاستەقینەی Google Images.
     * ئەگەر نا → Wikimedia Commons (بەخۆڕایی، بێ کلیل).
     * دەگەڕێتەوە: (ناوی سەرچاوە، لیستی وێنەکان)
     */
    suspend fun search(query: String, serperKey: String): Pair<String, List<ImageResult>> =
        withContext(Dispatchers.IO) {
            if (serperKey.isNotBlank()) "Google" to googleViaSerper(query, serperKey.trim())
            else "Wikipedia" to freeSearch(query)
        }

    private fun googleViaSerper(q: String, key: String): List<ImageResult> {
        val payload = JSONObject().put("q", q).put("num", 30).toString()
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
            out += ImageResult(full, thumb, o.optString("title"))
        }
        return out
    }

    /** بێ کلیل: ویکیپیدیای کوردی + ئینگلیزی (وێنەی سەرەکیی بابەت) + Wikimedia Commons. */
    private fun freeSearch(q: String): List<ImageResult> {
        val out = LinkedHashMap<String, ImageResult>()
        var lastError: Exception? = null
        var anyOk = false
        val sources: List<() -> List<ImageResult>> = listOf(
            { wikipedia("ckb", q) },
            { wikipedia("en", q) },
            { wikimedia(q) }
        )
        for (src in sources) {
            try {
                src().forEach { out.putIfAbsent(it.fullUrl, it) }
                anyOk = true
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (!anyOk && lastError != null) throw lastError!!
        return out.values.toList()
    }

    private fun wikipedia(lang: String, q: String): List<ImageResult> {
        val url = HttpUrl.Builder()
            .scheme("https").host("$lang.wikipedia.org").addPathSegments("w/api.php")
            .addQueryParameter("action", "query")
            .addQueryParameter("format", "json")
            .addQueryParameter("generator", "search")
            .addQueryParameter("gsrsearch", q)
            .addQueryParameter("gsrlimit", "10")
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
            val full = p.optJSONObject("original")?.optString("source").orEmpty()
            if (full.isBlank() || full.endsWith(".svg", true)) continue
            val thumb = p.optJSONObject("thumbnail")?.optString("source").orEmpty().ifBlank { full }
            items += p.optInt("index", 999) to ImageResult(full, thumb, p.optString("title"))
        }
        return items.sortedBy { it.first }.map { it.second }
    }

    private fun wikimedia(q: String): List<ImageResult> {
        val url = HttpUrl.Builder()
            .scheme("https").host("commons.wikimedia.org").addPathSegments("w/api.php")
            .addQueryParameter("action", "query")
            .addQueryParameter("format", "json")
            .addQueryParameter("generator", "search")
            .addQueryParameter("gsrsearch", "$q filetype:bitmap")
            .addQueryParameter("gsrnamespace", "6")
            .addQueryParameter("gsrlimit", "30")
            .addQueryParameter("prop", "imageinfo")
            .addQueryParameter("iiprop", "url|mime")
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
            items += p.optInt("index", 999) to ImageResult(full, thumb, p.optString("title"))
        }
        return items.sortedBy { it.first }.map { it.second }
    }
}

object ImageUtils {
    /** گەورەترین لا. گەورەتر لەمە بچووک دەکرێتەوە بۆ ئەوەی مۆبایل پڕ نەبێت. */
    const val MAX_SIDE = 1600

    private fun decode(source: ImageDecoder.Source): Bitmap {
        val bmp = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
            val w = info.size.width
            val h = info.size.height
            val m = max(w, h)
            if (m > MAX_SIDE) {
                val s = MAX_SIDE.toFloat() / m
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

    suspend fun download(url: String): Bitmap = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("Accept", "image/*,*/*;q=0.8").build()
        decodeBytes(Net.bytes(req))
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
