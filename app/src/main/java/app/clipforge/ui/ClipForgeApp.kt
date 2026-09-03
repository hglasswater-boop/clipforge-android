package app.clipforge.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.clipforge.MainUiState
import app.clipforge.MainViewModel
import app.clipforge.TrimEditorState
import app.clipforge.smb.SmbEntry
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

@Composable
fun ClipForgeApp(viewModel: MainViewModel) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        val editor = state.trimEditor
        if (editor != null) {
            TrimEditorScreen(viewModel, state, editor)
        } else {
            BrowserScreen(viewModel, state)
        }
    }
}

@Composable
private fun BrowserScreen(viewModel: MainViewModel, state: MainUiState) {
    var host by remember { mutableStateOf("") }
    var share by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var outputName by remember { mutableStateOf("") }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                Text("ClipForge", style = MaterialTheme.typography.headlineMedium)
                Text("MP4 / MKV を再エンコードせずに結合・カット。SMB上の原本は直接書き換えません。")
            }

            if (!state.connected) {
                item {
                    ConnectionCard(host, share, domain, username, password,
                        onHost = { host = it }, onShare = { share = it }, onDomain = { domain = it },
                        onUsername = { username = it }, onPassword = { password = it },
                        enabled = !state.busy,
                        onConnect = { viewModel.connect(host, share, domain, username, password) }
                    )
                }
            } else {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::goUp, enabled = !state.busy && state.currentPath.isNotBlank()) { Text("↑ 上へ") }
                        Text("/${state.currentPath}")
                    }
                }
                items(state.entries, key = { it.path }) { entry ->
                    val selectionOrder = state.selectedPaths.indexOf(entry.path).takeIf { it >= 0 }?.plus(1)
                    RemoteEntryRow(
                        entry = entry,
                        selectionOrder = selectionOrder,
                        enabled = !state.busy,
                        onOpen = { viewModel.openDirectory(entry) },
                        onToggle = { viewModel.toggleSelection(entry) }
                    )
                }
                item {
                    EditCard(
                        selectedPaths = state.selectedPaths,
                        outputName = outputName,
                        enabled = !state.busy,
                        onOutputName = { outputName = it },
                        onConcat = { viewModel.concatSelected(outputName) },
                        onOpenTrim = viewModel::openTrimEditor,
                        onMoveSelected = viewModel::moveSelected,
                        onRemoveSelected = viewModel::removeSelected
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
    var outputName by remember(editor.remotePath) { mutableStateOf("") }
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
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                Text("カット編集", style = MaterialTheme.typography.headlineMedium)
                Text(editor.remotePath.substringAfterLast('/'), style = MaterialTheme.typography.titleMedium)
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
                    modifier = Modifier.fillMaxWidth().height(240.dp)
                )
            }
            item {
                TrimRangeControls(viewModel, player, editor)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            previewSelection = false
                            player.pause()
                            player.seekTo(editor.startMs)
                        },
                        enabled = !state.busy
                    ) { Text("INへ") }
                    Button(
                        onClick = {
                            player.seekTo(editor.startMs)
                            player.play()
                            previewSelection = true
                        },
                        enabled = !state.busy
                    ) { Text("選択範囲を再生") }
                    OutlinedButton(
                        onClick = {
                            previewSelection = false
                            player.pause()
                            player.seekTo(editor.endMs.coerceAtMost(editor.durationMs))
                        },
                        enabled = !state.busy
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
                    enabled = !state.busy
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::cancelTrimEditor, enabled = !state.busy) { Text("キャンセル") }
                    Button(onClick = { viewModel.applyTrim(outputName) }, enabled = !state.busy) { Text("この範囲で無劣化カット") }
                }
            }
            item {
                Text(
                    if (editor.keyframesMs.isNotEmpty()) {
                        "IN/OUTハンドルは実際に切断可能なキーフレームへスナップします。表示位置と出力結果が食い違わないようにしています。"
                    } else {
                        "キーフレーム一覧を取得できなかったため、範囲指定は時刻ベースです。無劣化カットでは切断位置が前後する場合があります。"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            item { StatusArea(state) }
        }
    }
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
            valueRange = 0f..1f
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("IN  ${formatTime(editor.startMs)}")
            Text("OUT  ${formatTime(editor.endMs)}")
        }
        Text(
            "選択範囲 ${formatTime(editor.endMs - editor.startMs)} / 全体 ${formatTime(editor.durationMs)}",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun TimelineThumbnailStrip(paths: List<String>) {
    if (paths.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth().height(68.dp).clip(RoundedCornerShape(8.dp)),
        horizontalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        paths.forEach { path ->
            val image = remember(path) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.weight(1f).fillMaxHeight()
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

@Composable
private fun ConnectionCard(
    host: String, share: String, domain: String, username: String, password: String,
    onHost: (String) -> Unit, onShare: (String) -> Unit, onDomain: (String) -> Unit,
    onUsername: (String) -> Unit, onPassword: (String) -> Unit,
    enabled: Boolean, onConnect: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("SMB接続", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(host, onHost, Modifier.fillMaxWidth(), label = { Text("ホスト / IP") }, enabled = enabled)
            OutlinedTextField(share, onShare, Modifier.fillMaxWidth(), label = { Text("共有名") }, enabled = enabled)
            OutlinedTextField(domain, onDomain, Modifier.fillMaxWidth(), label = { Text("ドメイン（任意）") }, enabled = enabled)
            OutlinedTextField(username, onUsername, Modifier.fillMaxWidth(), label = { Text("ユーザー名") }, enabled = enabled)
            OutlinedTextField(password, onPassword, Modifier.fillMaxWidth(), label = { Text("パスワード") }, enabled = enabled,
                visualTransformation = PasswordVisualTransformation())
            Button(onClick = onConnect, enabled = enabled && host.isNotBlank() && share.isNotBlank()) { Text("接続") }
            Text("SMB1は使わず、SMB2.02〜SMB3.11で接続します。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RemoteEntryRow(
    entry: SmbEntry,
    selectionOrder: Int?,
    enabled: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit
) {
    val selected = selectionOrder != null
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { if (entry.directory) onOpen() else if (entry.isVideo) onToggle() }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (entry.directory) "📁" else "🎞️", modifier = Modifier.padding(end = 10.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name)
            if (!entry.directory) Text(formatBytes(entry.size), style = MaterialTheme.typography.bodySmall)
        }
        if (entry.isVideo) {
            selectionOrder?.let { Text("#$it", style = MaterialTheme.typography.labelLarge) }
            Checkbox(selected, onCheckedChange = { onToggle() }, enabled = enabled)
        }
    }
    HorizontalDivider()
}

@Composable
private fun EditCard(
    selectedPaths: List<String>,
    outputName: String,
    enabled: Boolean,
    onOutputName: (String) -> Unit,
    onConcat: () -> Unit,
    onOpenTrim: () -> Unit,
    onMoveSelected: (String, Int) -> Unit,
    onRemoveSelected: (String) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("編集", style = MaterialTheme.typography.titleMedium)
            Text("選択: ${selectedPaths.size}本")
            when {
                selectedPaths.size == 1 -> {
                    Button(onClick = onOpenTrim, enabled = enabled) { Text("プレビューしてカット範囲を選ぶ") }
                    Text("秒数入力は不要です。動画を見ながら左右のハンドルで範囲を決めます。", style = MaterialTheme.typography.bodySmall)
                }
                selectedPaths.size >= 2 -> {
                    Text("結合キュー", style = MaterialTheme.typography.titleSmall)
                    selectedPaths.forEachIndexed { index, path ->
                        ConcatQueueRow(
                            index = index,
                            path = path,
                            count = selectedPaths.size,
                            enabled = enabled,
                            onMove = onMoveSelected,
                            onRemove = onRemoveSelected
                        )
                    }
                    OutlinedTextField(
                        outputName,
                        onOutputName,
                        Modifier.fillMaxWidth(),
                        label = { Text("出力ファイル名 (.mp4 / .mkv)") },
                        placeholder = { Text("空欄なら merged.mkv") },
                        enabled = enabled
                    )
                    Button(onClick = onConcat, enabled = enabled) { Text("この順番で無劣化結合") }
                }
                else -> Text("動画を1本選ぶとカット、2本以上選ぶと結合できます。", style = MaterialTheme.typography.bodySmall)
            }
            Text("既存ファイルは上書きしません。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ConcatQueueRow(
    index: Int,
    path: String,
    count: Int,
    enabled: Boolean,
    onMove: (String, Int) -> Unit,
    onRemove: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text("#${index + 1}", style = MaterialTheme.typography.labelLarge)
        Text(
            path.substringAfterLast('/'),
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            maxLines = 1
        )
        TextButton(onClick = { onMove(path, -1) }, enabled = enabled && index > 0) { Text("↑") }
        TextButton(onClick = { onMove(path, 1) }, enabled = enabled && index < count - 1) { Text("↓") }
        TextButton(onClick = { onRemove(path) }, enabled = enabled) { Text("×") }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
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
