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
import android.graphics.Bitmap
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.sp
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

// ── ڕووکار: شێوازی گۆگڵ (سپی، شین) + ڕەنگی وەنەوشەیی/پەمەیی (وەک بەرنامەی کۆمپیوتەر) ──
private val Bg = Color(0xFFF7F8FC)
private val SurfaceC = Color(0xFFFFFFFF)
private val CardC = Color(0xFFFFFFFF)
private val Card2 = Color(0xFFF1F3F9)
private val LineC = Color(0xFFE3E6EF)
private val TextC = Color(0xFF1B1E2B)
private val Muted = Color(0xFF6A7086)
private val Accent = Color(0xFF5B5BF0)
private val Accent2 = Color(0xFFD94F9C)
private val OkC = Color(0xFF12A150)
private val ErrC = Color(0xFFE5484D)
private val Purple = Accent
private val Grad = Brush.linearGradient(listOf(Color(0xFF6A5BF2), Color(0xFF5B5BF0)))

private fun dims(w: Int, h: Int) = "${w}x${h}"

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
    val snack = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }
    val loader = remember { ImageLoader.Builder(ctx).okHttpClient(Net.client).crossfade(true).build() }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(30)
    ) { uris -> vm.addMine(uris) }
    val camUri = remember { ImageUtils.cameraUri(ctx) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) vm.addCamera(camUri)
    }
    val pickGallery = { galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val pickCamera = { cameraLauncher.launch(camUri) }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            snack.showSnackbar(it)
            vm.message = null
        }
    }

    val hasItems = vm.results.isNotEmpty() || vm.mine.isNotEmpty()
    var showResults by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(hasItems) { if (hasItems) showResults = true }
    val onResults = showResults && hasItems

    androidx.activity.compose.BackHandler(enabled = onResults && vm.viewing == null) {
        if (vm.selectMode) vm.cancelSelect() else showResults = false
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        Scaffold(
            containerColor = Bg,
            topBar = {
                if (onResults) ResultsTopBar(vm, onHome = { showResults = false }, onSettings = { showSettings = true },
                    onGallery = pickGallery, onSearch = { focus.clearFocus(); vm.submit() })
                else Box(Modifier.statusBarsPadding())
            },
            bottomBar = { if (onResults && vm.selectMode) SelectionBar(vm) },
            snackbarHost = { SnackbarHost(snack) }
        ) { pad ->
            if (!onResults) {
                HomeScreen(vm, Modifier.padding(pad), onSearch = { focus.clearFocus(); vm.submit() },
                    onGallery = pickGallery, onCamera = pickCamera, onSettings = { showSettings = true })
            } else {
                ResultsScreen(vm, loader, Modifier.padding(pad))
            }
        }
        vm.viewing?.let { r -> ViewerScreen(vm, r) }
    }

    if (showSettings) SettingsDialog(vm) { showSettings = false }
}

/** پەڕەی سەرەکی: لۆگۆ، گەڕانی گەورە، کارتی گاڵەری و کامێرا. */
@Composable
private fun HomeScreen(
    vm: MainViewModel, modifier: Modifier, onSearch: () -> Unit,
    onGallery: () -> Unit, onCamera: () -> Unit, onSettings: () -> Unit
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "ڕێکخستن", tint = Muted) }
        }
        Spacer(Modifier.height(36.dp))
        Image(painterResource(R.drawable.logo), "SG search", Modifier.size(96.dp))
        Spacer(Modifier.height(14.dp))
        Text("SG search", fontWeight = FontWeight.ExtraBold, color = TextC, fontSize = 34.sp)
        Text("وێنە بدۆزەرەوە، باکگراوندەکەی لاببە، و بە کوالیتی تەواو دایبگرە",
            color = Muted, style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(28.dp))
        SearchField(vm, onSearch, big = true)
        Spacer(Modifier.height(14.dp))
        GradientButton(
            text = "گەڕان", icon = Icons.Default.Search,
            enabled = vm.query.isNotBlank() && vm.busy == null,
            modifier = Modifier.fillMaxWidth().height(54.dp)
        ) { onSearch() }
        vm.busy?.let {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent, trackColor = Card2)
            Text(it, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(26.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            ActionCard(Icons.Default.PhotoLibrary, "وێنەی خۆم", "لە گاڵەری", Modifier.weight(1f), onGallery)
            ActionCard(Icons.Default.PhotoCamera, "کامێرا", "وێنە بگرە", Modifier.weight(1f), onCamera)
        }
        Spacer(Modifier.height(18.dp))
        Text("وێنەکان لە شوێنی خۆیان باکگراوندیان لادەبرێت — کلیک لە هەر وێنەیەک بکە بۆ بەراورد و پاشەکەوت",
            color = Muted, style = MaterialTheme.typography.bodySmall,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text("وەشانی ${BuildConfig.VERSION_NAME}", color = Muted, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String,
    modifier: Modifier, onClick: () -> Unit
) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White)
            .border(1.dp, LineC, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFFECECFE)),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = Accent) }
        Spacer(Modifier.height(10.dp))
        Text(title, fontWeight = FontWeight.Bold, color = TextC)
        Text(sub, color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SearchField(vm: MainViewModel, onSearch: () -> Unit, big: Boolean) {
    OutlinedTextField(
        value = vm.query,
        onValueChange = { vm.query = it },
        placeholder = { Text(if (big) "ناوی کەس، شوێن یان لینکی وێنە..." else "گەڕان...", color = Muted, maxLines = 1) },
        leadingIcon = { Icon(Icons.Default.Search, null, tint = Muted) },
        trailingIcon = {
            if (vm.query.isNotEmpty()) IconButton(onClick = { vm.query = "" }) { Icon(Icons.Default.Close, "سڕینەوە", tint = Muted) }
        },
        singleLine = true,
        shape = RoundedCornerShape(30.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Accent, unfocusedBorderColor = LineC,
            focusedContainerColor = Color.White, unfocusedContainerColor = Color.White
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = Modifier.fillMaxWidth().then(if (big) Modifier.height(60.dp) else Modifier)
    )
}

@Composable
private fun ResultsTopBar(
    vm: MainViewModel, onHome: () -> Unit, onSettings: () -> Unit, onGallery: () -> Unit, onSearch: () -> Unit
) {
    Column(Modifier.background(Color.White).statusBarsPadding()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
        ) {
            Image(
                painterResource(R.drawable.logo), "سەرەتا",
                Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onHome)
            )
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) { SearchField(vm, onSearch, big = false) }
            IconButton(onClick = onGallery) { Icon(Icons.Default.AddPhotoAlternate, "وێنەی خۆم", tint = Muted) }
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "ڕێکخستن", tint = Muted) }
        }
        vm.busy?.let { LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Accent, trackColor = Card2) }
        HorizontalDivider(color = LineC)
    }
}

@Composable
private fun SelectionBar(vm: MainViewModel) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().background(Color.White).navigationBarsPadding()
            .border(1.dp, LineC).padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text("${vm.selected.size}", fontWeight = FontWeight.Bold, color = Accent)
        TextButton(onClick = vm::selectAll) { Text("هەمووی") }
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = vm::downloadSelected, enabled = vm.selected.isNotEmpty()) {
            Icon(Icons.Default.Download, null, Modifier.size(18.dp))
        }
        GradientButton("لابردن", Icons.Default.AutoFixHigh, enabled = vm.selected.isNotEmpty(),
            modifier = Modifier.height(44.dp).padding(horizontal = 2.dp).widthIn(min = 120.dp)) { vm.batchFromSelection() }
        IconButton(onClick = vm::cancelSelect) { Icon(Icons.Default.Close, "داخستن", tint = Muted) }
    }
}

@Composable
private fun ResultsScreen(vm: MainViewModel, loader: ImageLoader, modifier: Modifier) {
    val items = vm.mine + vm.results
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (vm.selectMode) "${vm.selected.size} هەڵبژێردراوە" else "${items.size} وێنە",
                fontWeight = FontWeight.Bold, color = TextC, modifier = Modifier.weight(1f)
            )
            if (!vm.selectMode) {
                AssistChip(onClick = vm::inplaceAll, label = { Text("لابردنی هەموو") },
                    leadingIcon = { Icon(Icons.Default.AutoFixHigh, null, Modifier.size(16.dp), tint = Accent) })
                AssistChip(onClick = { vm.startSelect(null) }, label = { Text("هەڵبژاردن") },
                    leadingIcon = { Icon(Icons.Default.Checklist, null, Modifier.size(16.dp), tint = Accent) })
            }
        }
        val nDone = vm.doneCount
        if (nDone > 0 && !vm.selectMode) {
            GradientButton("پاشەکەوتی $nDone وێنەی ئامادە", Icons.Default.Save,
                modifier = Modifier.fillMaxWidth().height(46.dp)) { vm.saveAllInplace() }
        }
        // دوو ستوون، هەر وێنەیەک بە ڕێژەی خۆی (بێ بڕین)
        val cols = remember(items) {
            val a = ArrayList<ImageResult>(); val b = ArrayList<ImageResult>()
            var ha = 0f; var hb = 0f
            items.forEach { r ->
                val h = 1f / tileRatio(r)
                if (ha <= hb) { a += r; ha += h } else { b += r; hb += h }
            }
            a to b
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(cols.first, cols.second).forEach { col ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    col.forEach { item -> ResultTile(vm, item, loader, Modifier.fillMaxWidth()) }
                }
            }
        }
        if (vm.canLoadMore) {
            OutlinedButton(onClick = vm::loadMore, enabled = !vm.loadingMore, modifier = Modifier.fillMaxWidth()) {
                if (vm.loadingMore) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("وێنەی زیاتر")
            }
        }
        if (vm.source == "free" && vm.results.isNotEmpty()) {
            Text("سەرچاوە: ویکیپیدیا، Commons، Openverse — بۆ ئەنجامی گۆگڵ کلیلی Serper لە ⚙ دابنێ.",
                color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** پەنجەرەی بینین: بەراوردی پێش/دوای بە سلایدەر، ڕەنگی باکگراوند، Upscale، پاشەکەوت و ناردن. */
@Composable
private fun ViewerScreen(vm: MainViewModel, r: ImageResult) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st = vm.tiles[r.fullUrl] ?: MainViewModel.TileState()
    LaunchedEffect(r.fullUrl, st.status) { vm.loadViewer() }
    androidx.activity.compose.BackHandler { vm.closeViewer() }
    val working = st.status == "WAITING" || st.status == "WORKING"
    val cur = vm.viewUp ?: vm.viewCut
    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            IconButton(onClick = vm::closeViewer) { Icon(Icons.Default.Close, "داخستن", tint = TextC) }
            Column(Modifier.weight(1f)) {
                Text(if (cur != null) "بێ باکگراوند" else "لابردنی باکگراوند", fontWeight = FontWeight.Bold, color = TextC)
                Text(
                    when {
                        cur != null -> dims(cur.width, cur.height) + (if (vm.viewUp != null) "  ·  Upscale ×${vm.viewUpScale}" else "")
                        working -> st.msg.ifBlank { "کار دەکات..." }
                        st.status == "ERROR" -> "✗ " + st.msg
                        else -> ""
                    },
                    color = if (st.status == "ERROR") ErrC else Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1
                )
            }
            IconButton(onClick = vm::viewerRedo, enabled = !working && vm.viewBusy == null) {
                Icon(Icons.Default.Refresh, "دووبارە لابردنەوە", tint = Muted)
            }
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(18.dp)).background(Color.White).border(1.dp, LineC, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center
        ) {
            CompareSlider(vm.viewOrig, cur, vm.bgColor, r, Modifier.fillMaxSize().padding(8.dp))
            if (working || vm.viewBusy != null) {
                Column(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xEEFFFFFF)).padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = Accent, trackColor = Color(0xFFE3E3FD))
                    Spacer(Modifier.height(10.dp))
                    Text(vm.viewBusy ?: st.msg.ifBlank { "لابردنی باکگراوند..." }, color = TextC,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ColorRow(vm)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GradientButton("پاشەکەوت", Icons.Default.Save, enabled = cur != null,
                    modifier = Modifier.weight(1f).height(50.dp)) { vm.viewerSave() }
                OutlinedButton(onClick = {
                    scope.launch {
                        val uri = vm.viewerShareUri() ?: return@launch
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        ctx.startActivity(Intent.createChooser(send, "ناردن"))
                    }
                }, enabled = cur != null, modifier = Modifier.height(50.dp)) { Icon(Icons.Default.Share, "ناردن") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.viewerUpscale(2) }, enabled = vm.viewCut != null && vm.viewBusy == null,
                    modifier = Modifier.weight(1f)) { Text("Upscale ×2", maxLines = 1, softWrap = false) }
                OutlinedButton(onClick = { vm.viewerUpscale(4) }, enabled = vm.viewCut != null && vm.viewBusy == null) { Text("×4", maxLines = 1) }
                OutlinedButton(onClick = vm::viewerSaveOriginal) {
                    Icon(Icons.Default.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("ئەسڵی", maxLines = 1)
                }
            }
        }
    }
}

/** سلایدەری بەراورد: لای چەپ وێنەی ئەسڵی، لای ڕاست ئەنجام. ڕاکێشان بۆ گۆڕینی شوێنی هێڵ. */
@Composable
private fun CompareSlider(orig: Bitmap?, cut: Bitmap?, bg: Int?, r: ImageResult, modifier: Modifier) {
    var split by remember { mutableStateOf(0.5f) }
    val origImg = remember(orig) { orig?.asImageBitmap() }
    val cutImg = remember(cut) { cut?.asImageBitmap() }
    val ratio = (cut ?: orig)?.let { it.width.toFloat() / it.height } ?: tileRatio(r)
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier.aspectRatio(ratio.coerceIn(0.3f, 3f))
                .pointerInput(cutImg) {
                    detectHorizontalDragGestures { change, _ ->
                        split = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                }
                .pointerInput(cutImg) {
                    detectTapGestures { pos -> split = (pos.x / size.width).coerceIn(0f, 1f) }
                }
        ) {
            if (origImg == null && cutImg == null) {
                AsyncImage(model = r.thumbUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            // ئەنجام (لە هەموو شوێنێک) و وێنەی ئەسڵی تەنها لە لای چەپی هێڵ
            if (cutImg != null) {
                Box(Modifier.fillMaxSize().then(if (bg == null) Modifier.checkerboard(12.dp) else Modifier.background(Color(bg))))
                Image(cutImg, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            if (origImg != null) {
                Image(
                    origImg, null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().then(
                        if (cutImg != null) Modifier.drawWithContent {
                            clipRect(right = size.width * split) { this@drawWithContent.drawContent() }
                        } else Modifier
                    )
                )
            }
            if (cutImg != null && origImg != null) {
                Box(Modifier.fillMaxSize().drawWithContent {
                    val x = size.width * split
                    drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 6f)
                    drawCircle(Accent, radius = 34f, center = Offset(x, size.height / 2))
                    drawCircle(Color.White, radius = 12f, center = Offset(x, size.height / 2))
                })
                Text("پێش", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(androidx.compose.ui.AbsoluteAlignment.TopLeft).padding(8.dp)
                        .background(Color(0x88000000), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
                Text("دوای", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(androidx.compose.ui.AbsoluteAlignment.TopRight).padding(8.dp)
                        .background(Color(0x88000000), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
            }
        }
    }
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
                        Text("بەرزکردنەوەی کوالیتی لەگەڵ لابردن", fontWeight = FontWeight.Medium)
                        Text("بە شێوەی بنەڕەت کوژاوەیە — تەنها کاتێک خۆت دەتەوێت", style = MaterialTheme.typography.bodySmall, color = Muted)
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
            .clip(RoundedCornerShape(24.dp))
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
private fun tileRatio(r: ImageResult): Float =
    if (r.width > 0 && r.height > 0) (r.width.toFloat() / r.height).coerceIn(0.55f, 1.9f) else 1f

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultTile(vm: MainViewModel, item: ImageResult, loader: ImageLoader, modifier: Modifier) {
    val st = vm.tiles[item.fullUrl] ?: MainViewModel.TileState()
    val sel = item.fullUrl in vm.selected
    val done = st.status == "DONE"
    val working = st.status == "WAITING" || st.status == "WORKING"
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .aspectRatio(if (done && st.h > 0) (st.w.toFloat() / st.h).coerceIn(0.55f, 1.9f) else tileRatio(item))
            .clip(shape)
            .background(Card2)
            .then(if (sel) Modifier.border(3.dp, Accent, shape) else Modifier)
            .combinedClickable(
                onClick = { if (vm.selectMode) vm.toggleSelect(item) else vm.openViewer(item) },
                onLongClick = { vm.startSelect(item) }
            )
    ) {
        if (done && st.thumb != null) {
            Box(Modifier.fillMaxSize().checkerboard(10.dp))
            val img = remember(st.thumb) { st.thumb!!.asImageBitmap() }
            Image(img, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            AsyncImage(
                model = item.thumbUrl, contentDescription = item.title, imageLoader = loader,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
            )
        }
        if (done) {
            Text(
                "✓" + if (st.enhanced) " HD" else "", color = Color.White, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                    .background(OkC, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 2.dp)
            )
        } else if (vm.isLocal(item.fullUrl)) {
            Text(
                "هی خۆم", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                    .background(Accent, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
        if (working || st.status == "ERROR") {
            Column(
                Modifier.fillMaxSize().background(Color(0xD9FFFFFF)).padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
            ) {
                when (st.status) {
                    "WORKING" -> CircularProgressIndicator(Modifier.size(34.dp), color = Accent, strokeWidth = 4.dp,
                        trackColor = Color(0xFFE3E3FD))
                    "WAITING" -> Icon(Icons.Default.HourglassEmpty, null, tint = Muted)
                    else -> Icon(Icons.Default.ErrorOutline, null, tint = ErrC)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    when (st.status) { "WAITING" -> "لە ڕیزدایە..."; "WORKING" -> st.msg.ifBlank { "کار دەکات..." }; else -> st.msg },
                    color = if (st.status == "ERROR") ErrC else TextC, style = MaterialTheme.typography.labelSmall,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 3
                )
            }
        }
        if (!vm.selectMode && !working) {
            // تەنها یەک دوگمەی بچووک: ✨ لابردن لێرە (یان 💾 پاشەکەوت ئەگەر ئامادەیە)
            Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                if (done) TileIcon(Icons.Default.Save, "پاشەکەوت") { vm.saveInplace(item) }
                else TileIcon(Icons.Default.AutoFixHigh, "لابردنی باکگراوند لێرە") {
                    if (st.status == "ERROR") vm.undoInplace(item); vm.inplace(item)
                }
            }
        }
        if (vm.selectMode) {
            Icon(
                if (sel) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null,
                tint = if (sel) Accent else Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color(0x55000000), CircleShape)
            )
        }
    }
}
