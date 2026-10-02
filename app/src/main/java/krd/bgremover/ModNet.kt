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
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * MODNet: مۆدێلی تایبەت بە جیاکردنەوەی مرۆڤ (portrait matting).
 * تەنها مرۆڤ دەناسێتەوە، بۆیە لۆگۆ، بانەر، تابلۆ و شتی پشتەوە بە تەواوی لادەبات.
 * تاقیکراوەتەوە لەسەر وێنەی وتاربێژ لەگەڵ لۆگۆی پشتەوە: لۆگۆکە بە تەواوی لابرا.
 */
object ModNet {
    private const val MODEL_URL =
        "https://github.com/goranabdullas24-ops/BgRemover/raw/main/model/modnet.onnx"
    private const val MODEL_FILE = "modnet.onnx"
    private const val MIN_BYTES = 25_000_000L
    private const val REF = 512

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
                if (!resp.isSuccessful) throw IOException("داگرتنی مۆدێلی مرۆڤ سەرنەکەوت (HTTP ${resp.code})")
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

    /** ماسکی مرۆڤ (0..1) بە قەبارەی وێنەکە. */
    suspend fun mask(ctx: Context, src: Bitmap, onStatus: (String) -> Unit): FloatArray = lock.withLock {
        val file = ensureModel(ctx) { onStatus("داگرتنی مۆدێلی مرۆڤ (تەنها یەک جار): $it%") }
        onStatus("جیاکردنەوەی کەسەکە...")
        withContext(Dispatchers.Default) {
            val sess = session(file)
            val w = src.width; val h = src.height
            var rw: Int; var rh: Int
            if (max(w, h) < REF || min(w, h) > REF) {
                if (w >= h) { rh = REF; rw = (w.toFloat() / h * REF).toInt() }
                else { rw = REF; rh = (h.toFloat() / w * REF).toInt() }
            } else { rw = w; rh = h }
            rw = max(32, rw - rw % 32); rh = max(32, rh - rh % 32)
            val inp = Bitmap.createScaledBitmap(src, rw, rh, true)
            val n = rw * rh
            val px = IntArray(n)
            inp.getPixels(px, 0, rw, 0, 0, rw, rh)
            if (inp !== src) inp.recycle()
            val fb = FloatBuffer.allocate(3 * n)
            for (i in 0 until n) {
                val c = px[i]
                fb.put(i, ((c shr 16) and 255) / 127.5f - 1f)
                fb.put(n + i, ((c shr 8) and 255) / 127.5f - 1f)
                fb.put(2 * n + i, (c and 255) / 127.5f - 1f)
            }
            fb.rewind()
            val out = FloatArray(n)
            OnnxTensor.createTensor(env, fb, longArrayOf(1, 3, rh.toLong(), rw.toLong())).use { t ->
                sess.run(mapOf(sess.inputNames.first() to t)).use { r ->
                    (r.get(0) as OnnxTensor).floatBuffer.get(out)
                }
            }
            val gray = IntArray(n) {
                val v = (out[it].coerceIn(0f, 1f) * 255f).roundToInt()
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            val small = Bitmap.createBitmap(gray, rw, rh, Bitmap.Config.ARGB_8888)
            val big = Bitmap.createScaledBitmap(small, w, h, true)
            val bp = IntArray(w * h)
            big.getPixels(bp, 0, w, 0, 0, w, h)
            if (big !== small) big.recycle()
            small.recycle()
            FloatArray(bp.size) { ((bp[it] shr 8) and 255) / 255f }
        }
    }
}
