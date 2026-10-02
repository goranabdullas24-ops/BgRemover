package krd.bgremover

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.FloatBuffer
import kotlin.math.max

/**
 * IS-Net (DIS) 1024px — مۆدێلی کوالیتی‌بەرزی لابردنی باکگراوند، بەخۆڕایی و لەسەر مۆبایل.
 * مۆدێلەکە یەک جار دادەبەزێت (~٤٧ MB) و پاشان بێ ئینتەرنێت کار دەکات.
 */
object IsNet {
    private const val MODEL_URL =
        "https://github.com/goranabdullas24-ops/BgRemover/raw/main/model/isnet_w8.onnx"
    private const val MODEL_FILE = "isnet_w8.onnx"
    private const val MIN_BYTES = 40_000_000L
    private const val S = 1024

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var session: OrtSession? = null
    private val lock = Mutex()

    /** کاتێک ئەپ دەچێتە پشتەوە بیرگەی مۆدێل ئازاد دەکات (ئەگەر لە کاردا نەبێت). */
    fun release() {
        if (lock.tryLock()) {
            try { session?.close(); session = null } catch (_: Exception) {} finally { lock.unlock() }
        }
    }

    fun isDownloaded(ctx: Context): Boolean =
        File(ctx.filesDir, MODEL_FILE).let { it.exists() && it.length() > MIN_BYTES }

    private suspend fun ensureModel(ctx: Context, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val f = File(ctx.filesDir, MODEL_FILE)
            if (f.exists() && f.length() > MIN_BYTES) return@withContext f
            val tmp = File(ctx.filesDir, "$MODEL_FILE.part")
            Net.client.newCall(Request.Builder().url(MODEL_URL).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("داگرتنی مۆدێل سەرنەکەوت (HTTP ${resp.code})")
                val body = resp.body ?: throw IOException("داگرتنی مۆدێل سەرنەکەوت")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(128 * 1024)
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
            if (tmp.length() < MIN_BYTES) {
                tmp.delete()
                throw IOException("فایلی مۆدێل تەواو دانەبەزی، دووبارە هەوڵ بدەرەوە")
            }
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

    /** ماسکی ئەلفا (0..1) بە قەبارەی وێنە سەرەکییەکە. */
    suspend fun mask(ctx: Context, src: Bitmap, onStatus: (String) -> Unit): FloatArray = lock.withLock {
        val file = ensureModel(ctx) { onStatus("داگرتنی مۆدێلی IS-Net (تەنها یەک جار): $it%") }
        onStatus("لابردنی باکگراوند بە IS-Net...")
        withContext(Dispatchers.Default) {
            val sess = session(file)
            val n = S * S
            // پاراستنی ڕێژەی درێژی/پانی: وێنەکە لەناو چوارگۆشەیەکدا دادەنرێت (نەک ڕاکێشان)
            // تاقیکراوەتەوە: ئەنجامی وردتر دەدات بە تایبەت بۆ دەست، قژ و لێوار
            val side = max(src.width, src.height)
            val ox = (side - src.width) / 2
            val oy = (side - src.height) / 2
            val padded = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(padded).apply {
                drawColor(meanColor(src))
                drawBitmap(src, ox.toFloat(), oy.toFloat(), null)
            }
            val inp = Bitmap.createScaledBitmap(padded, S, S, true)
            padded.recycle()
            val px = IntArray(n)
            inp.getPixels(px, 0, S, 0, 0, S, S)

            // وەک rembg: دابەشکردن بەسەر گەورەترین بەها، پاشان − 0.5
            var mx = 1
            for (c in px) {
                mx = max(mx, max((c shr 16) and 255, max((c shr 8) and 255, c and 255)))
            }
            val fb = FloatBuffer.allocate(3 * n)
            val d = mx.toFloat()
            for (i in 0 until n) fb.put(i, (((px[i] shr 16) and 255) / d) - 0.5f)
            for (i in 0 until n) fb.put(n + i, (((px[i] shr 8) and 255) / d) - 0.5f)
            for (i in 0 until n) fb.put(2 * n + i, ((px[i] and 255) / d) - 0.5f)
            fb.rewind()

            val out = FloatArray(n)
            OnnxTensor.createTensor(env, fb, longArrayOf(1, 3, S.toLong(), S.toLong())).use { t ->
                sess.run(mapOf(sess.inputNames.first() to t)).use { res ->
                    val o = res.get(0) as OnnxTensor
                    o.floatBuffer.get(out)
                }
            }
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (v in out) { if (v < lo) lo = v; if (v > hi) hi = v }
            val range = (hi - lo).coerceAtLeast(1e-6f)

            // گەورەکردنەوەی ماسک بۆ قەبارەی ڕاستەقینە (bilinear)
            val gray = IntArray(n)
            for (i in 0 until n) {
                val v = (((out[i] - lo) / range) * 255f).toInt().coerceIn(0, 255)
                gray[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            val small = Bitmap.createBitmap(gray, S, S, Bitmap.Config.ARGB_8888)
            val big = Bitmap.createScaledBitmap(small, side, side, true)
            val bp = IntArray(src.width * src.height)
            big.getPixels(bp, 0, src.width, ox, oy, src.width, src.height)
            big.recycle(); small.recycle(); inp.recycle()
            FloatArray(bp.size) { ((bp[it] shr 8) and 255) / 255f }
        }
    }

    /** ڕەنگی ناوەندی وێنە بۆ پڕکردنەوەی دەوروبەر. */
    private fun meanColor(b: Bitmap): Int {
        val t = Bitmap.createScaledBitmap(b, 16, 16, true)
        val p = IntArray(256)
        t.getPixels(p, 0, 16, 0, 0, 16, 16)
        var r = 0L; var g = 0L; var bl = 0L
        for (c in p) { r += (c shr 16) and 255; g += (c shr 8) and 255; bl += c and 255 }
        if (t != b) t.recycle()
        return (0xFF shl 24) or ((r / 256).toInt() shl 16) or ((g / 256).toInt() shl 8) or (bl / 256).toInt()
    }
}
