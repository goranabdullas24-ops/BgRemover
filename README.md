# BgRemover — لابردنی باکگراوند (وەشانی ٤.٤)

## ناوەڕۆک
- `BgRemover-v4.4.apk` — ئەپی ئامادە بۆ دامەزراندن لەسەر ئەندرۆید
- `app/` — هەموو کۆدی سەرچاوە (Kotlin + Jetpack Compose)
- `model/` — مۆدێلەکانی AI:
  - `isnet_w8.onnx` — IS-Net 1024 (لابردنی باکگراوند)
  - `modnet.onnx` — MODNet (جیاکردنەوەی تەنها مرۆڤ)
  - `esrgan_x4_natural.onnx` — Real-ESRGAN (Upscale ×4 ی سروشتی)
- `.github/workflows/build-apk.yml` — دروستکردنی خۆکاری APK لە GitHub

## فایلەکانی کۆد (app/src/main/java/krd/bgremover/)
| فایل | کار |
|---|---|
| MainActivity.kt | ڕووکار (کوردی، ڕاست بۆ چەپ) |
| MainViewModel.kt | لۆجیک، کاری بەکۆمەڵ، پاشەکەوتی دۆخ |
| Network.kt | گەڕانی وێنە، داگرتن بە قەبارەی ئەسڵی |
| IsNet.kt | مۆدێلی IS-Net |
| ModNet.kt | مۆدێلی MODNet بۆ «تەنها مرۆڤ» |
| PersonCut.kt | کەسی فۆکس، پاککردنەوەی ماسک |
| BackgroundRemover.kt | وردکردنەوەی لێوار، remove.bg |
| Upscaler.kt | Upscale بە AI |

## دروستکردنی APK
- **Android Studio:** فۆڵدەرەکە بکەرەوە (File › Open) و ▶ Run.
- **GitHub:** هەر push ێک APK ی نوێ لە بەشی Releases دروست دەکات.
  ڕیپۆ: https://github.com/goranabdullas24-ops/BgRemover

## کلیلەکان (ئارەزوومەندانە)
- Serper (گەڕانی گۆگڵ): لە ⚙ ی ئەپ، یان GitHub Secret بە ناوی `SERPER_KEY`.
- remove.bg: لە ⚙ ی ئەپ.
