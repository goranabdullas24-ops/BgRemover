package krd.bgremover

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.FloatBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Upscale بە AI: Real-ESRGAN (general-wdn x4v3، کەمترین نەرمکردنەوە بۆ سروشتیترین ئەنجام).
 * ئەنجامی AI بە ٧٠٪ لەگەڵ ٣٠٪ گەورەکردنی ئاسایی تێکەڵ دەکرێت بۆ ئەوەی وەک تابلۆ دیار نەبێت
 * (تاقیکراوەتەوە: ڕوونتر لە گەورەکردنی ئاسایی و سروشتیتر لە AI ی تەنها).
 * وێنە ٤ جار گەورە دەکات و وردەکارییەکان ڕوونتر دەکاتەوە. لەسەر مۆبایل، بێ ئینتەرنێت
 * (دوای یەک جار داگرتنی مۆدێل). وێنەکە پارچە پارچە (tile) کار دەکرێت بۆ ئەوەی بیرگە پڕ نەبێت.
 */
object Upscaler {
    private const val MODEL_URL =
        "https://github.com/goranabdullas24-ops/BgRemover/raw/main/model/esrgan_x4_natural.onnx"
    private const val MODEL_FILE = "esrgan_x4_natural.onnx"
    private const val MIN_BYTES = 4_000_000L
    private const val SCALE = 4
    private const val TILE = 160
    private const val PAD = 12

    private const val AI_WEIGHT = 0.7f

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var session: OrtSession? = null
    private val lock = Mutex()

    /** کاتێک ئەپ دەچێتە پشتەوە بیرگەی مۆدێل ئازاد دەکات (ئەگەر لە کاردا نەبێت). */
    fun release() {
        if (lock.tryLock()) {
            try { session?.close(); session = null } catch (_: Exception) {} finally { lock.unlock() }
        }
    }

    private suspend fun ensureModel(ctx: Context, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val f = File(ctx.filesDir, MODEL_FILE)
            if (f.exists() && f.length() > MIN_BYTES) return@withContext f
            val tmp = File(ctx.filesDir, "$MODEL_FILE.part")
            Net.client.newCall(Request.Builder().url(MODEL_URL).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("داگرتنی مۆدێلی Upscale سەرنەکەوت (HTTP ${resp.code})")
                val body = resp.body ?: throw IOException("داگرتنی مۆدێل سەرنەکەوت")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        var last = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) {
                                val p = (read * 100 / total).toInt()
                                if (p != last) { last = p; onProgress(p) }
                            }
                        }
                    }
                }
            }
            if (tmp.length() < MIN_BYTES) { tmp.delete(); throw IOException("فایلی مۆدێل تەواو دانەبەزی") }
            if (!tmp.renameTo(f)) throw IOException("نەتوانرا مۆدێل پاشەکەوت بکرێت")
            f
        }

    private fun session(file: File): OrtSession =
        session ?: env.createSession(
            file.absolutePath,
            OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(4)
            }
        ).also { session = it }

    /**
     * maxSide: گەورەترین قەبارەی ئەنجام. ئەگەر ×٤ زیاتر بێت، بە کوالیتی بەرز بچووک دەکرێتەوە.
     */
    suspend fun upscale(
        ctx: Context, src: Bitmap, maxSide: Int, onStatus: (String) -> Unit
    ): Bitmap = lock.withLock {
        val file = ensureModel(ctx) { onStatus("داگرتنی مۆدێلی Upscale (تەنها یەک جار): $it%") }

        // ئەگەر ×٤ زۆر گەورە دەبێت، پێشتر کەمێک بچووکی دەکەینەوە بۆ خێرایی و بیرگە
        val maxIn = max(1, maxSide / SCALE)
        val pixBudget = (ImageUtils.MAX_PIXELS / (SCALE * SCALE)).toInt()
        var input = src
        val m = max(src.width, src.height)
        val px = src.width.toLong() * src.height
        if (m > maxIn * 2 || px > pixBudget) {
            val s = min(maxIn * 2f / m, sqrt(pixBudget.toFloat() / px))
            input = Bitmap.createScaledBitmap(
                src, (src.width * s).roundToInt().coerceAtLeast(1),
                (src.height * s).roundToInt().coerceAtLeast(1), true
            )
        }

        val w = input.width
        val h = input.height
        val ow = w * SCALE
        val oh = h * SCALE
        val sess = session(file)
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val tilesX = (w + TILE - 1) / TILE
        val tilesY = (h + TILE - 1) / TILE
        val total = tilesX * tilesY
        var done = 0

        withContext(Dispatchers.Default) {
            for (ty in 0 until h step TILE) for (tx in 0 until w step TILE) {
                coroutineContext.ensureActive()
                onStatus("Upscale ×4 بە AI: ${done * 100 / total}%")
                val x0 = max(0, tx - PAD); val y0 = max(0, ty - PAD)
                val x1 = min(w, tx + TILE + PAD); val y1 = min(h, ty + TILE + PAD)
                val tw = x1 - x0; val th = y1 - y0
                val n = tw * th
                val pix = IntArray(n)
                input.getPixels(pix, 0, tw, x0, y0, tw, th)
                val fb = FloatBuffer.allocate(3 * n)
                for (i in 0 until n) {
                    val c = pix[i]
                    fb.put(i, ((c shr 16) and 255) / 255f)
                    fb.put(n + i, ((c shr 8) and 255) / 255f)
                    fb.put(2 * n + i, (c and 255) / 255f)
                }
                fb.rewind()
                val otw = tw * SCALE; val oth = th * SCALE; val on = otw * oth
                val res = FloatArray(3 * on)
                OnnxTensor.createTensor(env, fb, longArrayOf(1, 3, th.toLong(), tw.toLong())).use { t ->
                    sess.run(mapOf(sess.inputNames.first() to t)).use { r ->
                        (r.get(0) as OnnxTensor).floatBuffer.get(res)
                    }
                }
                // تەنها ناوەڕاستی پارچەکە (بێ PAD) دەنووسرێت بۆ ئەوەی جێگای پێکگەیشتن دیار نەبێت
                val cx0 = (tx - x0) * SCALE; val cy0 = (ty - y0) * SCALE
                val cw = (min(tx + TILE, w) - tx) * SCALE
                val ch = (min(ty + TILE, h) - ty) * SCALE
                val row = IntArray(cw * ch)
                for (yy in 0 until ch) for (xx in 0 until cw) {
                    val i = (cy0 + yy) * otw + (cx0 + xx)
                    val r = (res[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val g = (res[on + i] * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val b = (res[2 * on + i] * 255f + 0.5f).toInt().coerceIn(0, 255)
                    row[yy * cw + xx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                out.setPixels(row, 0, cw, tx * SCALE, ty * SCALE, cw, ch)
                done++
            }
        }
        // تێکەڵکردن: ٧٠٪ AI + ٣٠٪ گەورەکردنی ئاسایی (سروشتیتر)
        withContext(Dispatchers.Default) {
            onStatus("Upscale ×4 بە AI: سروشتیکردن...")
            val base = Bitmap.createScaledBitmap(input, ow, oh, true)
            val band = 64
            val a = IntArray(ow * band); val b = IntArray(ow * band)
            var y = 0
            while (y < oh) {
                val rows = min(band, oh - y)
                out.getPixels(a, 0, ow, 0, y, ow, rows)
                base.getPixels(b, 0, ow, 0, y, ow, rows)
                for (i in 0 until ow * rows) {
                    val p = a[i]; val q = b[i]
                    val r = (((p shr 16) and 255) * AI_WEIGHT + ((q shr 16) and 255) * (1 - AI_WEIGHT)).roundToInt()
                    val g = (((p shr 8) and 255) * AI_WEIGHT + ((q shr 8) and 255) * (1 - AI_WEIGHT)).roundToInt()
                    val bl = ((p and 255) * AI_WEIGHT + (q and 255) * (1 - AI_WEIGHT)).roundToInt()
                    a[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
                }
                out.setPixels(a, 0, ow, 0, y, ow, rows)
                y += rows
            }
            base.recycle()
        }
        if (input !== src) input.recycle()

        // سنووری قەبارە: بچووککردنەوەی بە نەرمی (هەنگاو بە هەنگاو بۆ کوالیتی باش)
        var result = out
        while (max(result.width, result.height) > maxSide) {
            val s = max(0.5f, maxSide.toFloat() / max(result.width, result.height))
            val next = Bitmap.createScaledBitmap(
                result, (result.width * s).roundToInt().coerceAtLeast(1),
                (result.height * s).roundToInt().coerceAtLeast(1), true
            )
            if (result !== out) result.recycle()
            result = next
        }
        if (result !== out) out.recycle()
        result
    }
}
