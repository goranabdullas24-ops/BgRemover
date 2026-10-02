package krd.bgremover

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
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

private val Purple = Color(0xFF5A5A96)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Purple, secondary = Purple)) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    App()
                }
            }
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
    val camUri = remember { ImageUtils.cameraUri(ctx) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) vm.loadUri(camUri)
    }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            snack.showSnackbar(it)
            vm.message = null
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("داگرتن و لابردنی باکگراوند") },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "ڕێکخستن")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snack) }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
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

            Button(
                onClick = { focus.clearFocus(); vm.submit() },
                enabled = vm.query.isNotBlank() && vm.busy == null,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Icon(Icons.Default.ImageSearch, null)
                Spacer(Modifier.width(8.dp))
                Text("گەڕان و داگرتنی وێنە")
            }

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

            vm.busy?.let {
                Column {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
            }

            if (vm.results.isNotEmpty()) {
                val title = if (vm.selectMode) "${vm.selected.size} وێنە هەڵبژێردراوە"
                            else "${vm.results.size} وێنە دۆزرایەوە — دەستێک بۆ یەکێک، ڕاگرتن بۆ چەندان"
                SectionCard(title) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    ) {
                        if (vm.selectMode) {
                            OutlinedButton(onClick = vm::selectAll, modifier = Modifier.weight(1f)) { Text("هەمووی") }
                            OutlinedButton(onClick = vm::cancelSelect, modifier = Modifier.weight(1f)) { Text("هەڵوەشاندنەوە") }
                        } else {
                            OutlinedButton(onClick = { vm.startSelect(null) }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Checklist, null); Spacer(Modifier.width(6.dp))
                                Text("هەڵبژاردنی چەند وێنەیەک")
                            }
                        }
                    }
                    vm.results.chunked(3).forEach { row ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(bottom = 6.dp)
                        ) {
                            row.forEach { item ->
                                val sel = item.fullUrl in vm.selected
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFFEDEDF4))
                                        .border(
                                            if (sel) 3.dp else 0.dp,
                                            if (sel) Purple else Color.Transparent,
                                            RoundedCornerShape(10.dp)
                                        )
                                        .combinedClickable(
                                            onClick = {
                                                if (vm.selectMode) vm.toggleSelect(item)
                                                else if (vm.busy == null) vm.pick(item)
                                            },
                                            onLongClick = { vm.startSelect(item) }
                                        )
                                ) {
                                    AsyncImage(
                                        model = item.thumbUrl,
                                        contentDescription = item.title,
                                        imageLoader = loader,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    if (vm.selectMode) {
                                        Icon(
                                            if (sel) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                            null,
                                            tint = if (sel) Purple else Color.White,
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(4.dp)
                                                .background(Color(0x66000000), CircleShape)
                                        )
                                    }
                                }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
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
                            color = Purple,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
                if (vm.selectMode && vm.selected.isNotEmpty()) {
                    Button(
                        onClick = vm::batchFromSelection,
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        Icon(Icons.Default.AutoFixHigh, null); Spacer(Modifier.width(8.dp))
                        Text("لابردنی باکگراوندی ${vm.selected.size} وێنە")
                    }
                }
            }

            if (vm.batch.isNotEmpty()) {
                BatchSection(vm, scope)
            }

            vm.original?.let { bmp ->
                SectionCard("وێنەی سەرەکی") {
                    val img = remember(bmp) { bmp.asImageBitmap() }
                    Image(
                        img, null,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            }

            vm.cutout?.let { cut ->
                SectionCard("ئەنجام — بێ باکگراوند") {
                    val shown = vm.display ?: cut
                    val img = remember(shown) { shown.asImageBitmap() }
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
                    Spacer(Modifier.height(10.dp))
                    StrokeControls(vm)
                    Spacer(Modifier.height(12.dp))
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
                                Text("✗\n${item.error ?: ""}", color = Color(0xFFD93025),
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
        Spacer(Modifier.height(8.dp))
        StrokeControls(vm)
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

@Composable
private fun StrokeControls(vm: MainViewModel) {
    Text(
        if (vm.strokeLevel == 0) "سترۆک (هێڵی دەوروبەر): نییە" else "سترۆک (هێڵی دەوروبەر): ${vm.strokeLevel}",
        style = MaterialTheme.typography.labelLarge
    )
    Slider(
        value = vm.strokeLevel.toFloat(),
        onValueChange = { vm.strokeLevel = it.roundToInt() },
        onValueChangeFinished = { vm.updateStroke() },
        valueRange = 0f..12f,
        steps = 11
    )
    if (vm.strokeLevel > 0) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                Color.White, Color.Black, Color(0xFFFFC107), Color(0xFFD93025), Color(0xFF1E6FD9)
            ).forEach { c ->
                val argb = c.toArgb()
                ColorChip(argb, selected = vm.strokeColor == argb) { vm.updateStroke(color = argb) }
            }
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
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF4F3F8)),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                title,
                fontWeight = FontWeight.Medium,
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
                BorderStroke(if (selected) 3.dp else 1.dp, if (selected) Purple else Color.Gray),
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
        if ((x + y) % 2 == 0) drawRect(Color(0xFFDADADA), Offset(x * s, y * s), Size(s, s))
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
                    Switch(checked = vm.personOnly, onCheckedChange = { vm.setPersonOnly(it) })
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("تەنها مرۆڤ", fontWeight = FontWeight.Medium)
                        Text("شتی زیادە لادەبات و لەشی کەسەکە پڕ دەکات", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
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
            Text(sub, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
    }
}
