package app.clipforge.ui

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.clipforge.MainUiState
import app.clipforge.MainViewModel
import app.clipforge.TrimEditorState
import app.clipforge.workflow.PickedVideo
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

private const val XFILES_PACKAGE = "app.local1st.files"
private const val XFILES_PICK_ACTION = "app.local1st.files.action.PICK_FILES"
private const val XFILES_ALLOWED_EXTENSIONS = "app.local1st.files.extra.ALLOWED_EXTENSIONS"

@Composable
fun ClipForgeApp(viewModel: MainViewModel) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uris = result.data.readResultUris()
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        viewModel.acceptPickedUris(uris.map(Uri::toString))
    }

    val xFilesAvailable = remember(context) { hasXFilesPicker(context) }

    LaunchedEffect(state.pendingOutput?.localPath) {
        val output = state.pendingOutput ?: return@LaunchedEffect
        val file = File(output.localPath)
        if (!file.isFile) {
            viewModel.outputHandoffFailed("出力ファイルが見つかりません")
            return@LaunchedEffect
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val send = Intent(Intent.ACTION_SEND)
            .setType(output.mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newUri(context.contentResolver, output.fileName, uri)

        val handedOff = if (xFilesAvailable) {
            runCatching {
                context.startActivity(Intent(send).setPackage(XFILES_PACKAGE))
            }.isSuccess
        } else {
            false
        }
        if (handedOff) {
            viewModel.outputHandedOff()
        } else {
            runCatching {
                context.startActivity(Intent.createChooser(send, "保存先へ送る"))
            }.onSuccess {
                viewModel.outputHandedOff()
            }.onFailure { error ->
                viewModel.outputHandoffFailed(error.message ?: "出力ファイルを渡せませんでした")
            }
        }
    }

    val editor = state.trimEditor
    if (editor != null) {
        TrimEditorScreen(viewModel, state, editor)
    } else {
        HomeScreen(
            viewModel = viewModel,
            state = state,
            xFilesAvailable = xFilesAvailable,
            onPickVideos = { pickerLauncher.launch(buildVideoPickerIntent(context, xFilesAvailable)) },
        )
    }
}

@Composable
private fun HomeScreen(
    viewModel: MainViewModel,
    state: MainUiState,
    xFilesAvailable: Boolean,
    onPickVideos: () -> Unit,
) {
    var outputName by remember(
        state.selectedVideos.firstOrNull()?.uri,
        state.suggestedConcatOutputName,
    ) { mutableStateOf(state.suggestedConcatOutputName) }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                Text("ClipForge", style = MaterialTheme.typography.headlineMedium)
                Text("動画探しはファイラーに任せて、ClipForgeは無劣化の結合・カットだけ担当します。")
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("動画を選択", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (xFilesAvailable) {
                                "XFilesを開いてMP4 / MKVを選べます。SMBの接続設定と資格情報はXFiles側だけに残ります。"
                            } else {
                                "XFilesの選択モードが見つからないため、Androidのファイル選択画面を開きます。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = onPickVideos, enabled = !state.busy) {
                            Text(if (xFilesAvailable) "XFilesで動画を選ぶ" else "動画を選ぶ")
                        }
                    }
                }
            }

            if (state.selectedVideos.isNotEmpty()) {
                item {
                    EditCard(
                        selectedVideos = state.selectedVideos,
                        outputName = outputName,
                        enabled = !state.busy,
                        onOutputName = { outputName = it },
                        onConcat = { viewModel.concatSelected(outputName) },
                        onOpenTrim = viewModel::openTrimEditor,
                        onMoveSelected = viewModel::moveSelected,
                        onRemoveSelected = viewModel::removeSelected,
                    )
                }
            }
            item { StatusArea(state) }
        }
    }
}

@Composable
private fun TrimEditorScreen(viewModel: MainViewModel, state: MainUiState, editor: TrimEditorState) {
    val context = LocalContext.current
    val player = remember(editor.localPath) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(editor.localPath))))
            prepare()
        }
    }
    var outputName by remember(editor.sourceUri) { mutableStateOf("") }
    var previewSelection by remember(editor.localPath) { mutableStateOf(false) }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    LaunchedEffect(player, previewSelection, editor.startMs, editor.endMs) {
        while (previewSelection) {
            if (player.currentPosition >= editor.endMs) {
                player.pause()
                previewSelection = false
                break
            }
            delay(50)
        }
    }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                Text("カット編集", style = MaterialTheme.typography.headlineMedium)
                Text(editor.sourceName, style = MaterialTheme.typography.titleMedium)
            }
            item {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = player
                            useController = true
                        }
                    },
                    update = { it.player = player },
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                )
            }
            item { TrimRangeControls(viewModel, player, editor) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            previewSelection = false
                            player.pause()
                            player.seekTo(editor.startMs)
                        },
                        enabled = !state.busy,
                    ) { Text("INへ") }
                    Button(
                        onClick = {
                            player.seekTo(editor.startMs)
                            player.play()
                            previewSelection = true
                        },
                        enabled = !state.busy,
                    ) { Text("選択範囲を再生") }
                    OutlinedButton(
                        onClick = {
                            previewSelection = false
                            player.pause()
                            player.seekTo(editor.endMs.coerceAtMost(editor.durationMs))
                        },
                        enabled = !state.busy,
                    ) { Text("OUTへ") }
                }
            }
            item {
                OutlinedTextField(
                    value = outputName,
                    onValueChange = { outputName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("出力ファイル名 (.mp4 / .mkv)") },
                    placeholder = { Text("空欄なら元ファイル名-cut") },
                    enabled = !state.busy,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::cancelTrimEditor, enabled = !state.busy) {
                        Text("キャンセル")
                    }
                    Button(onClick = { viewModel.applyTrim(outputName) }, enabled = !state.busy) {
                        Text("この範囲で無劣化カット")
                    }
                }
            }
            item {
                Text(
                    if (editor.keyframesMs.isNotEmpty()) {
                        "IN/OUTハンドルは実際に切断可能なキーフレームへスナップします。表示位置と出力結果が食い違わないようにしています。"
                    } else {
                        "キーフレーム一覧を取得できなかったため、範囲指定は時刻ベースです。無劣化カットでは切断位置が前後する場合があります。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item { StatusArea(state) }
        }
    }
}

@Composable
private fun EditCard(
    selectedVideos: List<PickedVideo>,
    outputName: String,
    enabled: Boolean,
    onOutputName: (String) -> Unit,
    onConcat: () -> Unit,
    onOpenTrim: () -> Unit,
    onMoveSelected: (String, Int) -> Unit,
    onRemoveSelected: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("編集", style = MaterialTheme.typography.titleMedium)
            Text("選択: ${selectedVideos.size}本")
            when {
                selectedVideos.size == 1 -> {
                    SelectedVideoRow(
                        index = 0,
                        video = selectedVideos.single(),
                        count = 1,
                        enabled = enabled,
                        onMove = onMoveSelected,
                        onRemove = onRemoveSelected,
                    )
                    Button(onClick = onOpenTrim, enabled = enabled) {
                        Text("プレビューしてカット範囲を選ぶ")
                    }
                    Text(
                        "秒数入力は不要です。動画を見ながら左右のハンドルで範囲を決めます。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                selectedVideos.size >= 2 -> {
                    Text("結合キュー", style = MaterialTheme.typography.titleSmall)
                    selectedVideos.forEachIndexed { index, video ->
                        key(video.uri) {
                            SelectedVideoRow(
                                index = index,
                                video = video,
                                count = selectedVideos.size,
                                enabled = enabled,
                                onMove = onMoveSelected,
                                onRemove = onRemoveSelected,
                            )
                        }
                    }
                    Text(
                        "右端の ≡ をつかんで上下にドラッグすると結合順を変更できます。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = outputName,
                        onValueChange = onOutputName,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("出力ファイル名 (.mp4 / .mkv)") },
                        supportingText = {
                            Text("先頭ファイル名 + _concat を初期値にします")
                        },
                        enabled = enabled,
                    )
                    Button(onClick = onConcat, enabled = enabled) {
                        Text("この順番で無劣化結合")
                    }
                }
            }
            Text(
                "処理後はXFilesへ戻り、保存先フォルダを選びます。原本は直接書き換えません。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SelectedVideoRow(
    index: Int,
    video: PickedVideo,
    count: Int,
    enabled: Boolean,
    onMove: (String, Int) -> Unit,
    onRemove: (String) -> Unit,
) {
    var dragOffsetY by remember(video.uri) { mutableStateOf(0f) }
    var rowHeightPx by remember(video.uri) { mutableStateOf(0f) }
    val currentIndex by rememberUpdatedState(index)
    val currentCount by rememberUpdatedState(count)
    val currentOnMove by rememberUpdatedState(onMove)
    val visibleName = remember(video.displayName) { compactDisplayName(video.displayName) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { rowHeightPx = it.height.toFloat() }
            .graphicsLayer { translationY = dragOffsetY },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "#${index + 1}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(end = 2.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = visibleName,
                    maxLines = 2,
                    overflow = TextOverflow.Clip,
                    style = MaterialTheme.typography.bodyLarge,
                )
                video.sizeBytes?.let {
                    Text(formatBytes(it), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (count > 1) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .pointerInput(video.uri, enabled, count) {
                            if (!enabled) return@pointerInput
                            detectDragGestures(
                                onDragStart = { dragOffsetY = 0f },
                                onDragEnd = { dragOffsetY = 0f },
                                onDragCancel = { dragOffsetY = 0f },
                            ) { change, dragAmount ->
                                change.consume()
                                dragOffsetY += dragAmount.y
                                val threshold = (rowHeightPx * 0.45f).coerceAtLeast(36.dp.toPx())
                                when {
                                    dragOffsetY <= -threshold && currentIndex > 0 -> {
                                        currentOnMove(video.uri, -1)
                                        dragOffsetY += threshold
                                    }
                                    dragOffsetY >= threshold && currentIndex < currentCount - 1 -> {
                                        currentOnMove(video.uri, 1)
                                        dragOffsetY -= threshold
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("≡", style = MaterialTheme.typography.titleLarge)
                }
            }
            IconButton(
                onClick = { onRemove(video.uri) },
                enabled = enabled,
            ) {
                Text("×", style = MaterialTheme.typography.titleLarge)
            }
        }
        HorizontalDivider()
    }
}

private fun compactDisplayName(name: String): String {
    val normalized = name.ifBlank { "video" }
    if (normalized.length <= 24) return normalized
    return normalized.take(10) + "…" + normalized.takeLast(13)
}

@Composable
private fun TrimRangeControls(viewModel: MainViewModel, player: ExoPlayer, editor: TrimEditorState) {
    val duration = editor.durationMs.coerceAtLeast(1L)
    val startFraction = (editor.startMs.toDouble() / duration).toFloat().coerceIn(0f, 1f)
    val endFraction = (editor.endMs.toDouble() / duration).toFloat().coerceIn(0f, 1f)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TimelineThumbnailStrip(editor.thumbnailPaths)
        RangeSlider(
            value = startFraction..endFraction,
            onValueChange = { range ->
                val requestedStart = (range.start * duration).roundToLong()
                val requestedEnd = (range.endInclusive * duration).roundToLong()
                val startMoved = abs(requestedStart - editor.startMs) >= abs(requestedEnd - editor.endMs)
                val updated = viewModel.updateTrimRange(requestedStart, requestedEnd)
                if (updated != null) {
                    player.pause()
                    player.seekTo(if (startMoved) updated.startMs else updated.endMs)
                }
            },
            valueRange = 0f..1f,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("IN  ${formatTime(editor.startMs)}")
            Text("OUT  ${formatTime(editor.endMs)}")
        }
        Text(
            "選択範囲 ${formatTime(editor.endMs - editor.startMs)} / 全体 ${formatTime(editor.durationMs)}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun TimelineThumbnailStrip(paths: List<String>) {
    if (paths.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth().height(68.dp).clip(RoundedCornerShape(8.dp)),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        paths.forEach { path ->
            val image = remember(path) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun StatusArea(state: MainUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(state.status, style = MaterialTheme.typography.bodyMedium)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(24.dp))
    }
}

private fun hasXFilesPicker(context: Context): Boolean {
    val intent = Intent(XFILES_PICK_ACTION).setPackage(XFILES_PACKAGE)
    return context.packageManager.resolveActivity(intent, 0) != null
}

private fun buildVideoPickerIntent(context: Context, xFilesAvailable: Boolean): Intent {
    if (xFilesAvailable) {
        return Intent(XFILES_PICK_ACTION)
            .setPackage(XFILES_PACKAGE)
            .putExtra(XFILES_ALLOWED_EXTENSIONS, arrayOf("mp4", "mkv"))
    }
    return Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType("video/*")
        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/mp4", "video/x-matroska", "video/*"))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
}

private fun Intent?.readResultUris(): List<Uri> {
    val result = LinkedHashSet<Uri>()
    this?.clipData?.let { clips ->
        for (index in 0 until clips.itemCount) {
            clips.getItemAt(index).uri?.let(result::add)
        }
    }
    this?.data?.let(result::add)
    return result.toList()
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(Locale.US, kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(Locale.US, mb)
    return "%.2f GB".format(Locale.US, mb / 1024.0)
}

private fun formatTime(ms: Long): String {
    val safe = ms.coerceAtLeast(0L)
    val hours = safe / 3_600_000L
    val minutes = (safe / 60_000L) % 60L
    val seconds = (safe / 1_000L) % 60L
    val millis = safe % 1_000L
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d.%03d", hours, minutes, seconds, millis)
    } else {
        String.format(Locale.US, "%02d:%02d.%03d", minutes, seconds, millis)
    }
}
