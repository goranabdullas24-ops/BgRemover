package krd.bgremover

import android.graphics.Bitmap
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentationResult
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object BackgroundRemover {

    private val segmenter by lazy {
        SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder()
                .enableForegroundConfidenceMask()
                .build()
        )
    }

    /** لەسەر مۆبایل: ML Kit + وردکردنەوەی لێوار. بێ ئینتەرنێت کار دەکات. */
    suspend fun removeOnDevice(
        src: Bitmap, personOnly: Boolean = false, focusOnly: Boolean = false, onStatus: (String) -> Unit = {}
    ): Bitmap {
        val input = InputImage.fromBitmap(src, 0)
        var attempt = 0
        var result: SubjectSegmentationResult? = null
        while (result == null) {
            try {
                result = segmenter.process(input).await()
            } catch (e: MlKitException) {
                // یەکەم جار مۆدێلەکە دادەبەزێت؛ چاوەڕێ دەکەین
                if (e.errorCode == MlKitException.UNAVAILABLE && attempt < 20) {
                    attempt++
                    onStatus("مۆدێلی AI دادەبەزێت... ($attempt)")
                    delay(3000)
                } else throw e
            }
        }
        val buf = result!!.foregroundConfidenceMask
            ?: throw IllegalStateException("هیچ کەس یان شتێک لە وێنەکەدا نەدۆزرایەوە")

        val person = if (personOnly) PersonCut.personMask(src) else null
        val focus = if (focusOnly) PersonCut.focusMask(src) else null
        return withContext(Dispatchers.Default) {
            val w = src.width
            val h = src.height
            buf.rewind()
            if (buf.remaining() != w * h) {
                throw IllegalStateException("قەبارەی ماسک هاوتا نییە")
            }
            val mask = FloatArray(w * h)
            buf.get(mask)
            onStatus("وردکردنەوەی لێوارەکان...")
            PersonCut.clean(mask, person, w, h, focus)
            MaskRefiner.refine(src, mask)
        }
    }

    /** IS-Net 1024px — وردترین، بەخۆڕایی، لەسەر مۆبایل (دوای یەک جار داگرتنی مۆدێل). */
    suspend fun removeIsNet(
        ctx: android.content.Context, src: Bitmap, personOnly: Boolean, focusOnly: Boolean,
        onStatus: (String) -> Unit
    ): Bitmap {
        val mask = IsNet.mask(ctx, src, onStatus)
        val person = if (personOnly) { onStatus("دۆزینەوەی کەسەکە..."); PersonCut.personMask(src) } else null
        val focus = if (focusOnly) { onStatus("دۆزینەوەی کەسی سەرەکی (فۆکس)..."); PersonCut.focusMask(src) } else null
        onStatus("پاککردنەوەی دەوروبەر و لێوارەکان...")
        return withContext(Dispatchers.Default) {
            PersonCut.clean(mask, person, src.width, src.height, focus)
            MaskRefiner.refine(src, mask, light = true)
        }
    }

    /** remove.bg — وردترین ئەنجام (بە تایبەت بۆ قژ). پێویستی بە کلیل و ئینتەرنێتە. */
    suspend fun removeWithRemoveBg(src: Bitmap, apiKey: String): Bitmap = withContext(Dispatchers.IO) {
        val bos = ByteArrayOutputStream()
        src.compress(Bitmap.CompressFormat.JPEG, 100, bos)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("size", "auto")
            .addFormDataPart("format", "png")
            .addFormDataPart(
                "image_file", "image.jpg",
                bos.toByteArray().toRequestBody("image/jpeg".toMediaType())
            )
            .build()
        val req = Request.Builder()
            .url("https://api.remove.bg/v1.0/removebg")
            .header("X-Api-Key", apiKey.trim())
            .post(body)
            .build()
        ImageUtils.decodeBytes(Net.bytes(req))
    }
}

/**
 * وردکردنەوەی ماسک بە سێ هەنگاو:
 *  ١. لابردنی پەڵەی بچووکی جیاواز (دوورگەکان) کە بە هەڵە وەک بابەت ناسراون
 *  ٢. Guided Filter: لێوارەکان لەسەر بنەمای وێنە ڕاستەقینەکە ڕێک دەخات (قژ، پەنجە، لێواری جل)
 *  ٣. لابردنی هالۆ (رەنگی باکگراوندی کۆن لەسەر لێوارەکان)
 */
object MaskRefiner {

    /**
     * light = true بۆ IS-Net: ماسکەکە خۆی وردە، تەنها پەڵە بچووکەکان و هالۆی ڕەنگ لادەبرێن
     * و Guided Filter ی لاواز بەکاردێت بۆ ئەوەی تاڵەکانی قژ نەسڕێنەوە.
     */
    fun refine(src: Bitmap, raw: FloatArray, light: Boolean = false): Bitmap {
        val w = src.width
        val h = src.height
        val n = w * h
        val px = IntArray(n)
        src.getPixels(px, 0, w, 0, 0, w, h)

        val r = FloatArray(n)
        val g = FloatArray(n)
        val b = FloatArray(n)
        val gray = FloatArray(n)
        for (i in 0 until n) {
            val c = px[i]
            r[i] = ((c shr 16) and 255) / 255f
            g[i] = ((c shr 8) and 255) / 255f
            b[i] = (c and 255) / 255f
            gray[i] = 0.299f * r[i] + 0.587f * g[i] + 0.114f * b[i]
        }

        // ١. دوورگە بچووکەکان
        removeSmallIslands(raw, w, h)

        // ٢. Guided filter
        val radius: Int
        var alpha: FloatArray
        if (light) {
            radius = max(2, (min(w, h) / 160f).roundToInt())
            alpha = guidedFilter(gray, raw, w, h, max(1, radius / 2), 1e-4f)
            // تێکەڵکردن: ماسکی IS-Net سەرەکییە، فلتەر تەنها لێوار ڕێک دەخات
            for (i in alpha.indices) alpha[i] = 0.6f * raw[i] + 0.4f * alpha[i]
        } else {
            radius = max(3, (min(w, h) / 90f).roundToInt())
            alpha = guidedFilter(gray, raw, w, h, radius, 1e-3f)
            // تێپەڕینی دووەم بە نیوەی تیشک بۆ وردەکاری زیاتر
            alpha = guidedFilter(gray, alpha, w, h, max(1, radius / 2), 1e-4f)
        }

        // تەنها لە نزیک لێوارەکاندا ڕێگە بە ئەلفا بدە (نەک لە ناو باکگراوندی دوور)
        val band = box(raw, w, h, radius * 3)
        for (i in 0 until n) {
            var a = alpha[i]
            when {
                raw[i] > 0.985f && a > 0.5f -> a = 1f          // ناوەوەی بابەت: تەواو ڕوون نییە
                band[i] < 0.01f -> a = 0f                       // دوور لە بابەت: تەواو لابراو
            }
            // levels: کەمێک تیژکردنەوە بۆ ئەوەی لێوار لێڵ نەبێت
            alpha[i] = if (light) ((a - 0.03f) / 0.94f).coerceIn(0f, 1f)
                       else ((a - 0.12f) / 0.80f).coerceIn(0f, 1f)
        }

        // ٣. لابردنی هالۆ: ڕەنگی پێشەوە = (C - (1-a)·B) / a
        val inv = FloatArray(n) { 1f - alpha[it] }
        val bigR = radius * 4
        val denom = box(inv, w, h, bigR)
        val bgR = box(FloatArray(n) { r[it] * inv[it] }, w, h, bigR)
        val bgG = box(FloatArray(n) { g[it] * inv[it] }, w, h, bigR)
        val bgB = box(FloatArray(n) { b[it] * inv[it] }, w, h, bigR)

        val out = IntArray(n)
        for (i in 0 until n) {
            val a = alpha[i]
            if (a <= 0.003f) { out[i] = 0; continue }
            var rr = r[i]; var gg = g[i]; var bb = b[i]
            if (a < 0.98f && denom[i] > 1e-3f) {
                val br = bgR[i] / denom[i]
                val bg = bgG[i] / denom[i]
                val bb2 = bgB[i] / denom[i]
                val aa = max(a, 0.2f)
                rr = ((rr - (1f - aa) * br) / aa).coerceIn(0f, 1f)
                gg = ((gg - (1f - aa) * bg) / aa).coerceIn(0f, 1f)
                bb = ((bb - (1f - aa) * bb2) / aa).coerceIn(0f, 1f)
            }
            out[i] = ((a * 255f).roundToInt() shl 24) or
                ((rr * 255f).roundToInt() shl 16) or
                ((gg * 255f).roundToInt() shl 8) or
                (bb * 255f).roundToInt()
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setHasAlpha(true)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    /** پەڵە جیاکان کە زۆر لە گەورەترین بەش بچووکترن لادەبات. */
    private fun removeSmallIslands(mask: FloatArray, w: Int, h: Int) {
        val n = w * h
        val label = IntArray(n)            // 0 = نەبینراو
        val sizes = ArrayList<Int>()
        sizes.add(0)
        val stack = IntArray(n)
        for (start in 0 until n) {
            if (label[start] != 0 || mask[start] < 0.5f) continue
            val id = sizes.size
            var sp = 0
            stack[sp++] = start
            label[start] = id
            var count = 0
            while (sp > 0) {
                val p = stack[--sp]
                count++
                val x = p % w
                val y = p / w
                if (x > 0) { val q = p - 1; if (label[q] == 0 && mask[q] >= 0.5f) { label[q] = id; stack[sp++] = q } }
                if (x < w - 1) { val q = p + 1; if (label[q] == 0 && mask[q] >= 0.5f) { label[q] = id; stack[sp++] = q } }
                if (y > 0) { val q = p - w; if (label[q] == 0 && mask[q] >= 0.5f) { label[q] = id; stack[sp++] = q } }
                if (y < h - 1) { val q = p + w; if (label[q] == 0 && mask[q] >= 0.5f) { label[q] = id; stack[sp++] = q } }
            }
            sizes.add(count)
        }
        if (sizes.size <= 2) return
        val biggest = sizes.maxOrNull() ?: return
        val minKeep = max(64, (biggest * 0.03f).toInt())
        for (i in 0 until n) {
            val l = label[i]
            if (l != 0 && sizes[l] < minKeep) mask[i] = 0f
        }
    }

    /** Guided Filter (He et al.) بە وێنەی خۆڵەمێشی وەک ڕێنما. */
    private fun guidedFilter(
        guide: FloatArray, p: FloatArray, w: Int, h: Int, r: Int, eps: Float
    ): FloatArray {
        val n = w * h
        val meanI = box(guide, w, h, r)
        val meanP = box(p, w, h, r)
        val tmp = FloatArray(n)
        for (i in 0 until n) tmp[i] = guide[i] * p[i]
        val corrIP = box(tmp, w, h, r)
        for (i in 0 until n) tmp[i] = guide[i] * guide[i]
        val corrII = box(tmp, w, h, r)

        val a = FloatArray(n)
        val bArr = FloatArray(n)
        for (i in 0 until n) {
            val varI = corrII[i] - meanI[i] * meanI[i]
            val covIP = corrIP[i] - meanI[i] * meanP[i]
            a[i] = covIP / (varI + eps)
            bArr[i] = meanP[i] - a[i] * meanI[i]
        }
        val meanA = box(a, w, h, r)
        val meanB = box(bArr, w, h, r)
        val q = FloatArray(n)
        for (i in 0 until n) q[i] = (meanA[i] * guide[i] + meanB[i]).coerceIn(0f, 1f)
        return q
    }

    /** Box blur خێرا (prefix sums)، لێوارەکان بە ژمارەی ڕاستەقینە دابەش دەکرێن. */
    private fun box(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(w * h)
        val pre = DoubleArray(max(w, h) + 1)
        for (y in 0 until h) {
            val o = y * w
            pre[0] = 0.0
            for (x in 0 until w) pre[x + 1] = pre[x] + src[o + x]
            for (x in 0 until w) {
                val lo = max(0, x - r)
                val hi = min(w, x + r + 1)
                tmp[o + x] = ((pre[hi] - pre[lo]) / (hi - lo)).toFloat()
            }
        }
        val out = FloatArray(w * h)
        for (x in 0 until w) {
            pre[0] = 0.0
            for (y in 0 until h) pre[y + 1] = pre[y] + tmp[y * w + x]
            for (y in 0 until h) {
                val lo = max(0, y - r)
                val hi = min(h, y + r + 1)
                out[y * w + x] = ((pre[hi] - pre[lo]) / (hi - lo)).toFloat()
            }
        }
        return out
    }
}
