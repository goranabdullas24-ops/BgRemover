package krd.bgremover

import android.graphics.Bitmap
import android.graphics.Canvas
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * «تەنها مرۆڤ» + سترۆک.
 * تاقیکراوەتەوە لەسەر چەند وێنەیەک: شتی زیادە لادەبات، لەشی کەسەکە تەواو پڕ دەکات
 * (نە نیمچە-ڕوون)، و سترۆکێکی ڕێک و بێ دڕکاوی دروست دەکات.
 */
object PersonCut {

    private val selfie by lazy {
        Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .build()
        )
    }

    /** ماسکی ئەگەری مرۆڤ (0..1) بە قەبارەی وێنەکە؛ null ئەگەر کار نەکرد. */
    suspend fun personMask(src: Bitmap): FloatArray? = try {
        val s = 512f / max(src.width, src.height)
        val small = if (s < 1f) Bitmap.createScaledBitmap(
            src, (src.width * s).roundToInt().coerceAtLeast(1),
            (src.height * s).roundToInt().coerceAtLeast(1), true
        ) else src
        val m = selfie.process(InputImage.fromBitmap(small, 0)).await()
        val mw = m.width
        val mh = m.height
        val buf = m.buffer
        buf.rewind()
        val f = FloatArray(mw * mh) { buf.float }
        if (small != src) small.recycle()
        resize(f, mw, mh, src.width, src.height)
    } catch (e: Exception) {
        null
    }

    /** گۆڕینی قەبارەی ماسک (bilinear). */
    private fun resize(f: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
        if (sw == dw && sh == dh) return f
        val out = FloatArray(dw * dh)
        val fx = (sw - 1).toFloat() / max(1, dw - 1)
        val fy = (sh - 1).toFloat() / max(1, dh - 1)
        for (y in 0 until dh) {
            val yy = y * fy
            val y0 = yy.toInt().coerceIn(0, sh - 1)
            val y1 = min(y0 + 1, sh - 1)
            val ty = yy - y0
            for (x in 0 until dw) {
                val xx = x * fx
                val x0 = xx.toInt().coerceIn(0, sw - 1)
                val x1 = min(x0 + 1, sw - 1)
                val tx = xx - x0
                val a = f[y0 * sw + x0] * (1 - tx) + f[y0 * sw + x1] * tx
                val b = f[y1 * sw + x0] * (1 - tx) + f[y1 * sw + x1] * tx
                out[y * dw + x] = a * (1 - ty) + b * ty
            }
        }
        return out
    }

    /**
     * پاککردنەوەی ماسک:
     *  ١. (ئەگەر مرۆڤ هەبێت) هەر شتێک دوور لە کەسەکە بێت لادەبرێت، ناوەڕاستی لەش پڕ دەکرێت
     *  ٢. تەنها بەشە سەرەکییەکان دەمێننەوە (پارچە جیاکان لادەبرێن)
     *  ٣. ناوەوەی بابەت تەواو پڕ دەکرێت (نە نیمچە-ڕوون)
     */
    fun clean(alpha: FloatArray, person: FloatArray?, w: Int, h: Int) {
        val n = w * h
        val r = max(4, (min(w, h) * 0.03f).roundToInt())

        if (person != null) {
            var cnt = 0
            for (v in person) if (v > 0.5f) cnt++
            if (cnt > n / 100) {
                val per = FloatArray(n) { if (person[it] > 0.5f) 1f else 0f }
                // دەروازە: ناوچەی کەسەکە + کەمێک فراوانتر (بۆ قژ و جل)
                val dil = MaskOps.dilate(per, w, h, r)
                val gate = MaskOps.box(dil, w, h, r)
                // ناوکی دڵنیا: لەشی کەسەکە دەبێت تەواو پڕ بێت
                val core = MaskOps.erode(FloatArray(n) { if (person[it] > 0.6f) 1f else 0f }, w, h, r)
                for (i in 0 until n) {
                    alpha[i] = max(alpha[i] * min(1f, gate[i] * 1.5f), core[i])
                }
            }
        }

        keepMain(alpha, w, h)

        // پڕکردنەوەی ناوەوە: پیکسڵی قووڵ لەناو بابەتدا = تەواو ڕوون نییە
        val deepPx = max(3f, min(w, h) * 0.015f)
        val dist = MaskOps.edt(BooleanArray(n) { alpha[it] <= 0.08f }, w, h)
        for (i in 0 until n) if (dist[i] > deepPx && alpha[i] > 0.15f) alpha[i] = 1f
    }

    /** پارچەی جیا کە لە ٨٪ ی گەورەترین بەش بچووکترە لادەبرێت، لەگەڵ لێوارە نەرمەکەی. */
    private fun keepMain(alpha: FloatArray, w: Int, h: Int) {
        val n = w * h
        val label = IntArray(n)
        val sizes = ArrayList<Int>().apply { add(0) }
        val stack = IntArray(n)
        for (start in 0 until n) {
            if (label[start] != 0 || alpha[start] < 0.5f) continue
            val id = sizes.size
            var sp = 0
            stack[sp++] = start; label[start] = id
            var count = 0
            while (sp > 0) {
                val p = stack[--sp]; count++
                val x = p % w; val y = p / w
                if (x > 0) { val q = p - 1; if (label[q] == 0 && alpha[q] >= 0.5f) { label[q] = id; stack[sp++] = q } }
                if (x < w - 1) { val q = p + 1; if (label[q] == 0 && alpha[q] >= 0.5f) { label[q] = id; stack[sp++] = q } }
                if (y > 0) { val q = p - w; if (label[q] == 0 && alpha[q] >= 0.5f) { label[q] = id; stack[sp++] = q } }
                if (y < h - 1) { val q = p + w; if (label[q] == 0 && alpha[q] >= 0.5f) { label[q] = id; stack[sp++] = q } }
            }
            sizes.add(count)
        }
        if (sizes.size <= 1) return
        val big = sizes.max()
        val keep = FloatArray(n) { val l = label[it]; if (l != 0 && sizes[l] >= big * 0.08f) 1f else 0f }
        val region = MaskOps.dilate(keep, w, h, 3)
        for (i in 0 until n) alpha[i] *= region[i]
    }

    /**
     * سترۆک (هێڵی دەوروبەر) بە ڕەنگ و پانی دیاریکراو.
     * شێوەکە پێشتر نەرم دەکرێت بۆ ئەوەی هێڵەکە ڕێک بێت (بەبێ دڕکاوی قژ).
     */
    fun withStroke(fg: Bitmap, widthPx: Int, color: Int): Bitmap {
        if (widthPx <= 0) return fg
        val w = fg.width; val h = fg.height; val n = w * h
        val px = IntArray(n)
        fg.getPixels(px, 0, w, 0, 0, w, h)
        val a = FloatArray(n) { ((px[it] ushr 24) and 255) / 255f }
        // نەرمکردن ≈ Gaussian (سێ جار box)
        val rr = max(1, (widthPx * 0.35f).roundToInt())
        var sm = MaskOps.box(a, w, h, rr)
        sm = MaskOps.box(sm, w, h, rr)
        sm = MaskOps.box(sm, w, h, rr)
        val inside = BooleanArray(n) { sm[it] > 0.5f || a[it] > 0.5f }
        val d = MaskOps.edt(inside, w, h)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val sp = IntArray(n)
        val cr = color and 0x00FFFFFF
        val ca = (color ushr 24) and 255
        for (i in 0 until n) {
            val s = (widthPx - d[i] + 0.5f).coerceIn(0f, 1f)
            if (s > 0f) sp[i] = ((s * ca).roundToInt() shl 24) or cr
        }
        out.setPixels(sp, 0, w, 0, 0, w, h)
        Canvas(out).drawBitmap(fg, 0f, 0f, null)
        return out
    }
}

/** کردارە بنەڕەتییەکانی ماسک. */
object MaskOps {

    /** Box blur خێرا (prefix sums). */
    fun box(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(w * h)
        val pre = DoubleArray(max(w, h) + 1)
        for (y in 0 until h) {
            val o = y * w
            pre[0] = 0.0
            for (x in 0 until w) pre[x + 1] = pre[x] + src[o + x]
            for (x in 0 until w) {
                val lo = max(0, x - r); val hi = min(w, x + r + 1)
                tmp[o + x] = ((pre[hi] - pre[lo]) / (hi - lo)).toFloat()
            }
        }
        val out = FloatArray(w * h)
        for (x in 0 until w) {
            pre[0] = 0.0
            for (y in 0 until h) pre[y + 1] = pre[y] + tmp[y * w + x]
            for (y in 0 until h) {
                val lo = max(0, y - r); val hi = min(h, y + r + 1)
                out[y * w + x] = ((pre[hi] - pre[lo]) / (hi - lo)).toFloat()
            }
        }
        return out
    }

    /** فراوانکردن (چوارگۆشەیی) بۆ ماسکی 0/1. */
    fun dilate(m: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val b = box(m, w, h, r)
        return FloatArray(b.size) { if (b[it] > 1e-4f) 1f else 0f }
    }

    /** تەسککردنەوە بۆ ماسکی 0/1. */
    fun erode(m: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val b = box(m, w, h, r)
        return FloatArray(b.size) { if (b[it] > 0.9999f) 1f else 0f }
    }

    /** ماوەی ئیقلیدی بۆ نزیکترین پیکسڵی true (Felzenszwalb & Huttenlocher). */
    fun edt(feature: BooleanArray, w: Int, h: Int): FloatArray {
        val inf = 1e20f
        val g = FloatArray(w * h) { if (feature[it]) 0f else inf }
        val n = max(w, h)
        val f = FloatArray(n); val d = FloatArray(n)
        val v = IntArray(n); val z = FloatArray(n + 1)
        // ستوونەکان
        for (x in 0 until w) {
            for (y in 0 until h) f[y] = g[y * w + x]
            dt1(f, h, d, v, z)
            for (y in 0 until h) g[y * w + x] = d[y]
        }
        // ڕیزەکان
        for (y in 0 until h) {
            val o = y * w
            for (x in 0 until w) f[x] = g[o + x]
            dt1(f, w, d, v, z)
            for (x in 0 until w) g[o + x] = sqrt(d[x])
        }
        return g
    }

    private fun dt1(f: FloatArray, n: Int, d: FloatArray, v: IntArray, z: FloatArray) {
        var k = 0
        v[0] = 0
        z[0] = -Float.MAX_VALUE
        z[1] = Float.MAX_VALUE
        for (q in 1 until n) {
            var s: Float
            while (true) {
                val p = v[k]
                s = ((f[q] + q.toFloat() * q) - (f[p] + p.toFloat() * p)) / (2f * (q - p))
                if (s <= z[k] && k > 0) k-- else break
            }
            if (s <= z[k]) {
                // k == 0 و s <= z[0] ناکرێت چونکە z[0] = -∞
                v[0] = q; z[0] = -Float.MAX_VALUE; z[1] = Float.MAX_VALUE
            } else {
                k++
                v[k] = q
                z[k] = s
                z[k + 1] = Float.MAX_VALUE
            }
        }
        k = 0
        for (q in 0 until n) {
            while (z[k + 1] < q) k++
            val p = v[k]
            d[q] = (q - p).toFloat() * (q - p) + f[p]
        }
    }
}
