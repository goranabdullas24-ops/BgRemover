package krd.bgremover

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.ImageLoader
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ── ڕووکار: تێمی ڕووناک + ڕەنگی وەنەوشەیی/پەمەیی (وەک بەرنامەی کۆمپیوتەر) ──
private val Bg = Color(0xFFF6F4FF)
private val SurfaceC = Color(0xFFFFFFFF)
private val CardC = Color(0xFFFFFFFF)
private val Card2 = Color(0xFFF2EFFD)
private val LineC = Color(0xFFE3DEF6)
private val TextC = Color(0xFF1D1B33)
private val Muted = Color(0xFF6B6890)
private val Accent = Color(0xFF7B5CF0)
private val Accent2 = Color(0xFFF0508F)
private val OkC = Color(0xFF14A86A)
private val ErrC = Color(0xFFE5484D)
private val Purple = Accent
private val Grad = Brush.linearGradient(listOf(Accent, Accent2))

private fun dims(w: Int, h: Int) = "\u200e${w}×${h}\u200e"

private val AppColors = lightColorScheme(
    primary = Accent, onPrimary = Color.White,
    primaryContainer = Card2, onPrimaryContainer = Accent,
    secondary = Accent2, onSecondary = Color.White,
    secondaryContainer = Card2, onSecondaryContainer = Accent2,
    tertiary = OkC,
    background = Bg, onBackground = TextC,
    surface = SurfaceC, onSurface = TextC,
    surfaceVariant = CardC, onSurfaceVariant = Muted,
    surfaceContainer = CardC, surfaceContainerHigh = Card2, surfaceContainerHighest = Card2,
    surfaceContainerLow = SurfaceC, surfaceContainerLowest = Bg,
    outline = LineC, outlineVariant = LineC,
    error = ErrC, inverseSurface = TextC, inverseOnSurface = Bg
)

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // savedInstanceState تەنها کاتێک هەیە کە ئەندرۆید خۆی ئەپەکەی داخستبێت (نەک بەکارهێنەر)
        vm.start(restore = savedInstanceState?.getBoolean(KEEP_STATE) == true)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = AppColors) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    App(vm)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEEP_STATE, true)
    }

    companion object { private const val KEEP_STATE = "keep_state" }

    /** کاتێک دەچیتە سەر ئەپێکی تر: دۆخ پاشەکەوت دەکرێت. */
    override fun onStop() {
        super.onStop()
        vm.persist()
    }

    /** ئەندرۆید داوای بیرگە دەکات: مۆدێلەکانی AI ئازاد دەکرێن (پاشان خۆکارانە دووبارە بار دەبنەوە). */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && vm.busy == null && vm.batchStatus == null) {
            IsNet.release(); ModNet.release(); Upscaler.release()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun App(vm: MainViewModel = viewModel()) {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }
    val loader = remember { ImageLoader.Builder(ctx).okHttpClient(Net.client).crossfade(true).build() }

    // چەند وێنەیەک پێکەوە (تا ٣٠)؛ یەک وێنە = شێوازی ئاسایی، زیاتر = بەکۆمەڵ
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(30)
    ) { uris -> vm.batchFromUris(uris) }
    val upscaleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) vm.upscaleUri(uri) }
    val camUri = remember { ImageUtils.cameraUri(ctx) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) vm.loadUri(camUri)
    }

    // کاتێک وێنەیەک هەڵدەبژێردرێت یان کاری بەکۆمەڵ دەست پێدەکات، بگەڕێوە بۆ سەرەوە
    val mainScroll = rememberScrollState()
    LaunchedEffect(vm.original, vm.cutout, vm.batch.size, vm.upSrc) {
        if (vm.original != null || vm.cutout != null || vm.batch.isNotEmpty() || vm.upSrc != null) mainScroll.animateScrollTo(0)
    }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            snack.showSnackbar(it)
            vm.message = null
        }
    }

    Scaffold(
        containerColor = Bg,
        topBar = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.horizontalGradient(listOf(Accent, Accent2)))
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Image(
                    androidx.compose.ui.res.painterResource(R.drawable.logo), contentDescription = "SG search",
                    modifier = Modifier.size(46.dp).border(2.dp, Color(0x99FFFFFF), RoundedCornerShape(13.dp))
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("SG search", color = Color.White, fontWeight = FontWeight.ExtraBold,
                        style = MaterialTheme.typography.titleLarge)
                    Text("گەڕان · لابردنی باکگراوند · Upscale بە AI", color = Color(0xE6FFFFFF),
                        style = MaterialTheme.typography.labelMedium)
                }
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Default.Settings, contentDescription = "ڕێکخستن", tint = Color.White)
                }
            }
        },
        snackbarHost = { SnackbarHost(snack) }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(mainScroll)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = vm.query,
                onValueChange = { vm.query = it },
                label = { Text("ناو یان لینکی وێنە") },
                placeholder = { Text("بۆ نموونە: گوڵی نێرگز") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (vm.query.isNotEmpty()) IconButton(onClick = { vm.query = "" }) {
                        Icon(Icons.Default.Close, "سڕینەوە")
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); vm.submit() }),
                modifier = Modifier.fillMaxWidth()
            )

            GradientButton(
                text = "گەڕان و داگرتنی وێنە",
                icon = Icons.Default.ImageSearch,
                enabled = vm.query.isNotBlank() && vm.busy == null,
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) { focus.clearFocus(); vm.submit() }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { cameraLauncher.launch(camUri) },
                    modifier = Modifier.weight(1f).height(52.dp)
                ) {
                    Icon(Icons.Default.PhotoCamera, null); Spacer(Modifier.width(8.dp)); Text("کامێرا")
                }
                OutlinedButton(
                    onClick = {
                        galleryLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier.weight(1f).height(52.dp)
                ) {
                    Icon(Icons.Default.PhotoLibrary, null); Spacer(Modifier.width(8.dp)); Text("لە گاڵەری")
                }
            }
            if (vm.upSrc == null) {
                OutlinedButton(
                    onClick = {
                        upscaleLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(8.dp))
                    Text("Upscale ی وێنەیەک لە گاڵەری", maxLines = 1)
                }
            }

            vm.busy?.let {
                Column {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
            }

            // دوگمەکانی داگرتن/لابردن لە سەرەوە
            if (vm.selectMode && vm.selected.isNotEmpty()) {
                Button(
                    onClick = vm::batchFromSelection,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Icon(Icons.Default.AutoFixHigh, null); Spacer(Modifier.width(8.dp))
                    Text("لابردنی باکگراوندی ${vm.selected.size} وێنە")
                }
                OutlinedButton(
                    onClick = vm::downloadSelected,
                    enabled = vm.busy == null,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp))
                    Text("داگرتنی ${vm.selected.size} وێنە بەبێ لابردنی باکگراوند")
                }
            }

            if (vm.batch.isNotEmpty()) {
                BatchSection(vm, scope)
            }

            vm.original?.let { bmp ->
                SectionCard("وێنەی سەرەکی — ${dims(bmp.width, bmp.height)} پیکسڵ" + if (vm.upscaled) "" else "") {
                    if (vm.lowQuality) {
                        Text(
                            "⚠ ئەم ماڵپەڕە ڕێگەی بە داگرتنی وێنە ئەسڵییەکە نەدا؛ تەنها وێنە بچووکەکەی بەردەستە. وێنەیەکی تر هەڵبژێرە بۆ کوالیتی باشتر.",
                            color = ErrC,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                    val img = remember(bmp) { bmp.asImageBitmap() }
                    Image(
                        img, null,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                        contentScale = ContentScale.Fit
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = vm::saveOriginal,
                        enabled = vm.busy == null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp))
                        Text("داگرتنی وێنەکە بەبێ لابردنی باکگراوند")
                    }
                    OutlinedButton(
                        onClick = { vm.upscaleFrom("original", 2) },
                        enabled = vm.busy == null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(6.dp))
                        Text("Upscale ×2 ی ئەم وێنەیە")
                    }
                }
            }

            UpscaleSection(vm, scope, upscaleLauncher)

            vm.cutout?.let { cut ->
                SectionCard("ئەنجام — بێ باکگراوند (${dims(cut.width, cut.height)})" + if (vm.upscaled) " ✨ Upscale" else "") {
                    val img = remember(cut) { cut.asImageBitmap() }
                    val bg = vm.bgColor
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .then(if (bg == null) Modifier.checkerboard() else Modifier.background(Color(bg)))
                    ) {
                        Image(
                            img, null,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("ڕەنگی باکگراوند:", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val colors: List<Int?> = listOf(
                            null,
                            Color.White.toArgb(),
                            Color.Black.toArgb(),
                            Color(0xFF1E6FD9).toArgb(),
                            Color(0xFFD93025).toArgb(),
                            Color(0xFF2E7D32).toArgb()
                        )
                        colors.forEach { c -> ColorChip(c, selected = vm.bgColor == c) { vm.bgColor = c } }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { vm.upscaleFrom("cutout", 2) },
                        enabled = vm.busy == null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(6.dp))
                        Text("Upscale ×2 بە AI")
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = vm::save, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp)); Text("پاشەکەوت")
                        }
                        OutlinedButton(onClick = {
                            scope.launch {
                                val uri = vm.shareUri() ?: return@launch
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "image/png"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                ctx.startActivity(Intent.createChooser(send, "ناردن"))
                            }
                        }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text("ناردن")
                        }
                    }
                    TextButton(
                        onClick = vm::removeBackground,
                        enabled = vm.busy == null,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) { Text("دووبارە لابردنەوە") }
                    TextButton(
                        onClick = vm::closeEditor,
                        enabled = vm.busy == null,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) { Text("✕  داخستن") }
                }
            }
            if (vm.results.isNotEmpty()) {
                val title = if (vm.selectMode) "${vm.selected.size} وێنە هەڵبژێردراوە"
                            else "${vm.results.size} وێنە · ✨ لابردن لە شوێنی خۆی · ⬇ داگرتن · 2× Upscale"
                SectionCard(title) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    ) {
                        if (vm.selectMode) {
                            OutlinedButton(onClick = vm::selectAll, modifier = Modifier.weight(1f)) { Text("هەمووی") }
                            OutlinedButton(onClick = vm::cancelSelect, modifier = Modifier.weight(1f)) { Text("هەڵوەشاندنەوە") }
                        } else {
                            OutlinedButton(onClick = vm::inplaceAll, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.AutoFixHigh, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                                Text("هەمووی", maxLines = 1)
                            }
                            OutlinedButton(onClick = { vm.startSelect(null) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Checklist, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                                Text("هەڵبژاردن", maxLines = 1)
                            }
                        }
                    }
                    val nDone = vm.doneCount
                    if (nDone > 0 && !vm.selectMode) {
                        GradientButton(
                            text = "پاشەکەوتی $nDone وێنەی ئامادە",
                            icon = Icons.Default.Save,
                            modifier = Modifier.fillMaxWidth().height(46.dp).padding(bottom = 4.dp)
                        ) { vm.saveAllInplace() }
                        Spacer(Modifier.height(8.dp))
                    }
                    vm.results.chunked(2).forEach { row ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(bottom = 10.dp)
                        ) {
                            row.forEach { item ->
                                ResultTile(vm, item, loader, Modifier.weight(1f))
                            }
                            repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    if (vm.canLoadMore) {
                        OutlinedButton(
                            onClick = vm::loadMore,
                            enabled = !vm.loadingMore,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (vm.loadingMore) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("وێنەی زیاتر")
                        }
                    }
                    if (vm.source == "free") {
                        Text(
                            "سەرچاوە: ویکیپیدیا، Wikimedia Commons، Openverse. بۆ ئەنجامی ڕاستەوخۆی گۆگڵ، کلیلی Serper لە ⚙ دابنێ.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showSettings) SettingsDialog(vm) { showSettings = false }
}

@Composable
private fun BatchSection(vm: MainViewModel, scope: kotlinx.coroutines.CoroutineScope) {
    val ctx = LocalContext.current
    val done = vm.batch.count { it.status == BatchItem.Status.DONE }
    val failed = vm.batch.count { it.status == BatchItem.Status.ERROR }
    SectionCard("بەکۆمەڵ: $done لە ${vm.batch.size} تەواو بوو" + if (failed > 0) " ($failed سەرنەکەوت)" else "") {
        vm.batchStatus?.let {
            LinearProgressIndicator(
                progress = { (done + failed).toFloat() / vm.batch.size.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth()
            )
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
        }
        vm.batch.chunked(3).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 6.dp)
            ) {
                row.forEach { item ->
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .then(
                                if (vm.bgColor == null) Modifier.checkerboard(8.dp)
                                else Modifier.background(Color(vm.bgColor!!))
                            )
                            .clickable(enabled = item.status == BatchItem.Status.DONE) { vm.openBatchItem(item) },
                        contentAlignment = Alignment.Center
                    ) {
                        item.thumb?.let { t ->
                            val img = remember(t) { t.asImageBitmap() }
                            Image(img, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                        }
                        when (item.status) {
                            BatchItem.Status.WORKING ->
                                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                            BatchItem.Status.WAITING ->
                                Icon(Icons.Default.HourglassEmpty, null, tint = Color.Gray)
                            BatchItem.Status.ERROR ->
                                Text("✗\n${item.error ?: ""}", color = ErrC,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(4.dp))
                            BatchItem.Status.DONE -> {}
                        }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Text("ڕەنگی باکگراوند بۆ هەمووی:", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        ColorRow(vm)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::saveAll, enabled = done > 0, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Download, null); Spacer(Modifier.width(4.dp)); Text("پاشەکەوتی هەموو")
            }
            OutlinedButton(onClick = {
                scope.launch {
                    val uris = vm.shareAllUris()
                    if (uris.isEmpty()) return@launch
                    val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "image/png"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    ctx.startActivity(Intent.createChooser(send, "ناردن"))
                }
            }, enabled = done > 0, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Share, null); Spacer(Modifier.width(4.dp)); Text("ناردنی هەموو")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (vm.batchStatus != null) {
                TextButton(onClick = vm::stopBatch) { Text("وەستاندن") }
            } else if (failed > 0) {
                TextButton(onClick = vm::retryFailed) { Text("دووبارە هەوڵدانەوە") }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = vm::clearBatch) { Text("سڕینەوەی لیست") }
        }
    }
}

/** ئایکۆنی بچووکی بازنەیی لەسەر وێنە. */
@Composable
private fun TileIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    loading: Boolean = false,
    text: String? = null,
    onClick: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color(0xF0FFFFFF))
            .border(1.dp, Color(0x337B5CF0), CircleShape)
            .clickable(enabled = enabled && !loading, onClick = onClick)
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), color = Accent, strokeWidth = 2.dp)
        } else if (text != null) {
            Text(text, color = Accent, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelMedium)
        } else {
            Icon(icon, contentDescription = label, tint = Accent, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun ColorRow(vm: MainViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val colors: List<Int?> = listOf(
            null,
            Color.White.toArgb(),
            Color.Black.toArgb(),
            Color(0xFF1E6FD9).toArgb(),
            Color(0xFFD93025).toArgb(),
            Color(0xFF2E7D32).toArgb()
        )
        colors.forEach { c -> ColorChip(c, selected = vm.bgColor == c) { vm.bgColor = c } }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardC, contentColor = TextC),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, LineC),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                title,
                fontWeight = FontWeight.Bold,
                color = TextC,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp)
            )
            content()
        }
    }
}

@Composable
private fun ColorChip(color: Int?, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .then(if (color == null) Modifier.checkerboard(6.dp) else Modifier.background(Color(color)))
            .border(
                BorderStroke(if (selected) 3.dp else 1.dp, if (selected) Accent2 else LineC),
                CircleShape
            )
            .clickable(onClick = onClick)
    )
}

private fun Modifier.checkerboard(cell: Dp = 12.dp) = drawBehind {
    val s = cell.toPx()
    drawRect(Color.White)
    val cols = (size.width / s).toInt() + 1
    val rows = (size.height / s).toInt() + 1
    for (y in 0 until rows) for (x in 0 until cols) {
        if ((x + y) % 2 == 0) drawRect(Color(0xFFECE8F7), Offset(x * s, y * s), Size(s, s))
    }
}

@Composable
private fun SettingsDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    var serper by remember { mutableStateOf(vm.serperKey) }
    var rb by remember { mutableStateOf(vm.removeBgKey) }
    var eng by remember { mutableStateOf(vm.engine) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ڕێکخستن") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = vm.personOnly, onCheckedChange = { vm.changePersonOnly(it) })
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("تەنها مرۆڤ", fontWeight = FontWeight.Medium)
                        Text("شتی زیادە لادەبات و لەشی کەسەکە پڕ دەکات", style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = vm.focusOnly, onCheckedChange = { vm.changeFocusOnly(it) })
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("تەنها کەسی سەرەکی (فۆکس)", fontWeight = FontWeight.Medium)
                        Text("ئەگەر چەند کەس هەبن، تەنها ئەوەی فۆکسی لەسەرە دەمێنێتەوە", style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = vm.autoEnhance, onCheckedChange = { vm.changeAutoEnhance(it) })
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("بەرزکردنەوەی کوالیتی دوای لابردن", fontWeight = FontWeight.Medium)
                        Text("وێنەی بچووک (کەمتر لە 1800px) ×2 بە AI ڕوون دەکرێتەوە", style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                }
                HorizontalDivider()
                Text("شێوازی لابردنی باکگراوند:", fontWeight = FontWeight.Medium)
                EngineOption("isnet", eng, "IS-Net 1024 (وردترین، بەخۆڕایی)",
                    "یەکەم جار مۆدێلێک دادەبەزێت (~٤٧ MB)، پاشان بێ ئینتەرنێت") { eng = it }
                EngineOption("fast", eng, "خێرا (ML Kit)", "خێراتر بەڵام کەمتر ورد") { eng = it }
                EngineOption("removebg", eng, "remove.bg", "پێویستی بە کلیل و ئینتەرنێتە", enabled = rb.isNotBlank()) { eng = it }
                OutlinedTextField(
                    rb, { rb = it },
                    label = { Text("remove.bg API Key (ئارەزوومەندانە)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                HorizontalDivider()
                Text(
                    "گەڕانی گۆگڵ: کلیلێکی بەخۆڕایی لە serper.dev. بەبێ کلیل لە ویکیپیدیا دەگەڕێت.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    serper, { serper = it },
                    label = { Text("Serper API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.saveSettings(serper, rb, eng); onDismiss() }) { Text("پاشەکەوت") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("داخستن") } }
    )
}

@Composable
private fun EngineOption(
    value: String, selected: String, title: String, sub: String,
    enabled: Boolean = true, onSelect: (String) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { onSelect(value) }
            .padding(vertical = 2.dp)
    ) {
        RadioButton(selected = selected == value, onClick = { onSelect(value) }, enabled = enabled)
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}


/** بەشی Upscale ی جیا: پێش / دوای، ×2 / ×4، پاشەکەوت و ناردن. */
@Composable
private fun UpscaleSection(
    vm: MainViewModel,
    scope: kotlinx.coroutines.CoroutineScope,
    picker: androidx.activity.result.ActivityResultLauncher<PickVisualMediaRequest>
) {
    val src = vm.upSrc ?: return
    val ctx = LocalContext.current
    SectionCard("Upscale — گەورەکردن بە AI") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("پێش" to src, "دوای Upscale" to vm.upRes).forEach { (label, bmp) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp)
                            .clip(RoundedCornerShape(10.dp)).checkerboard(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (bmp != null) {
                            val img = remember(bmp) { bmp.asImageBitmap() }
                            Image(img, null, modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                        } else {
                            Text("—", color = Muted)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(vm.upInfo, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.runUpscale(2) }, enabled = vm.busy == null, modifier = Modifier.weight(1f)) {
                Text("Upscale ×2")
            }
            OutlinedButton(onClick = { vm.runUpscale(4) }, enabled = vm.busy == null, modifier = Modifier.weight(1f)) {
                Text("Upscale ×4")
            }
        }
        if (vm.upRes != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::saveUpscaled, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp)); Text("پاشەکەوت")
                }
                OutlinedButton(onClick = {
                    scope.launch {
                        val uri = vm.shareUpscaledUri() ?: return@launch
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        ctx.startActivity(Intent.createChooser(send, "ناردن"))
                    }
                }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text("ناردن")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }, enabled = vm.busy == null) { Text("وێنەیەکی تر لە گاڵەری") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = vm::closeUpscale, enabled = vm.busy == null) { Text("داخستن") }
        }
    }
}


/** دوگمەی سەرەکی بە ڕەنگی تێکەڵ (وەنەوشەیی → پەمەیی). */
@Composable
private fun GradientButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) Grad else Brush.linearGradient(listOf(Card2, Card2)))
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = if (enabled) Color.White else Muted)
                Spacer(Modifier.width(8.dp))
            }
            Text(text, color = if (enabled) Color.White else Muted, fontWeight = FontWeight.Bold)
        }
    }
}

/** خانەی وێنەیەکی گەڕان: لابردنی باکگراوند و بەرزکردنەوەی کوالیتی لە شوێنی خۆیدا. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultTile(vm: MainViewModel, item: ImageResult, loader: ImageLoader, modifier: Modifier) {
    val st = vm.tiles[item.fullUrl] ?: MainViewModel.TileState()
    val sel = item.fullUrl in vm.selected
    val done = st.status == "DONE"
    val working = st.status == "WAITING" || st.status == "WORKING"
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(CardC)
            .then(
                when {
                    sel -> Modifier.border(3.dp, Grad, shape)
                    done -> Modifier.border(2.dp, OkC, shape)
                    else -> Modifier.border(1.dp, LineC, shape)
                }
            )
            .combinedClickable(
                onClick = {
                    when {
                        vm.selectMode -> vm.toggleSelect(item)
                        done -> vm.openInplace(item)
                        vm.busy == null && !working -> vm.pick(item)
                    }
                },
                onLongClick = { vm.startSelect(item) }
            )
    ) {
        if (done && st.thumb != null) {
            Box(Modifier.fillMaxSize().checkerboard(10.dp))
            val img = remember(st.thumb) { st.thumb!!.asImageBitmap() }
            Image(
                img, null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 26.dp)
            )
        } else {
            AsyncImage(
                model = item.thumbUrl,
                contentDescription = item.title,
                imageLoader = loader,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        // شریتی خوارەوە
        if (done) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(30.dp).background(Color(0xF0FFFFFF)))
        } else {
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(48.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD080812))))
            )
        }
        val label = when {
            done -> "✓ ${dims(st.w, st.h)}" + if (st.enhanced) "  ✨HD" else ""
            item.width > 0 && item.height > 0 -> dims(item.width, item.height)
            else -> ""
        }
        if (label.isNotEmpty()) {
            Text(
                label, color = if (done) OkC else Color.White, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelMedium, maxLines = 1,
                modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 10.dp, vertical = 7.dp)
            )
        }
        // دۆخی کارکردن
        if (working || st.status == "ERROR") {
            Column(
                Modifier.fillMaxSize().background(Color(0xB80A0A18)).padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                when (st.status) {
                    "WORKING" -> CircularProgressIndicator(Modifier.size(40.dp), color = Accent2, strokeWidth = 4.dp,
                        trackColor = Color(0x338B6CFF))
                    "WAITING" -> Icon(Icons.Default.HourglassEmpty, null, tint = Color.White)
                    else -> Icon(Icons.Default.ErrorOutline, null, tint = ErrC)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    when (st.status) { "WAITING" -> "لە ڕیزدایە..."; "WORKING" -> st.msg.ifBlank { "کار دەکات..." }; else -> st.msg },
                    color = if (st.status == "ERROR") ErrC else Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 3
                )
                if (st.status == "ERROR") {
                    TextButton(onClick = { vm.undoInplace(item); vm.inplace(item) }) { Text("دووبارە", color = Color.White) }
                }
            }
        }
        if (!vm.selectMode && !working) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.align(Alignment.TopEnd).padding(7.dp)
            ) {
                if (done) {
                    TileIcon(Icons.Default.Save, "پاشەکەوت") { vm.saveInplace(item) }
                    TileIcon(Icons.Default.OpenInFull, "کردنەوە") { vm.openInplace(item) }
                    TileIcon(Icons.Default.Restore, "گەڕانەوە") { vm.undoInplace(item) }
                } else {
                    TileIcon(Icons.Default.AutoFixHigh, "لابردنی باکگراوند لێرە") { vm.inplace(item) }
                    TileIcon(Icons.Default.Download, "داگرتن", loading = item.fullUrl in vm.downloading) { vm.downloadOne(item) }
                    TileIcon(Icons.Default.AutoAwesome, "Upscale ×2", text = "2×", enabled = vm.busy == null) { vm.upscaleTile(item) }
                }
            }
        }
        if (vm.selectMode) {
            Icon(
                if (sel) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                null,
                tint = if (sel) Accent2 else Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color(0x66000000), CircleShape)
            )
        }
    }
}
