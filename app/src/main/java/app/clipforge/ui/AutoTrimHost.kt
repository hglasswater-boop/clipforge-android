package app.clipforge.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.clipforge.MainViewModel
import app.clipforge.TrimEditorState
import app.clipforge.media.AutoTrimAnalysis
import app.clipforge.media.AutoTrimAnalyzer
import app.clipforge.media.AutoTrimCandidate
import app.clipforge.media.AutoTrimEvidence
import app.clipforge.media.AutoTrimSide
import app.clipforge.media.AutoTrimStateStore
import app.clipforge.media.AutoTrimUiState
import app.clipforge.processing.AutoTrimAnalysisService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

@Composable
fun AutoTrimEntryButton(
    editor: TrimEditorState,
    enabled: Boolean,
) {
    val context = LocalContext.current
    val autoState by AutoTrimStateStore.state.collectAsStateWithLifecycle()
    val currentSession = autoState.sessionPath == editor.sessionPath
    val hasCurrentState = currentSession && (
        autoState.running || autoState.analysis != null || autoState.error != null
    )

    Button(
        onClick = {
            if (hasCurrentState) {
                AutoTrimStateStore.show(editor.sessionPath)
            } else {
                AutoTrimAnalysisService.start(
                    context = context.applicationContext,
                    sourceUri = editor.sourceUri,
                    sessionPath = editor.sessionPath,
                    localInputPath = editor.localPath,
                    durationMs = editor.durationMs,
                )
            }
        },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            when {
                currentSession && autoState.running -> "前後を自動解析中…"
                currentSession && autoState.analysis != null -> "自動解析結果を開く"
                currentSession && autoState.error != null -> "自動解析を確認"
                else -> "前後を自動検出"
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoTrimHost(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val editor = state.trimEditor ?: return
    val autoState by AutoTrimStateStore.state.collectAsStateWithLifecycle()
    if (autoState.sessionPath != editor.sessionPath || !autoState.visible) return

    ModalBottomSheet(
        onDismissRequest = { AutoTrimStateStore.dismiss(editor.sessionPath) },
    ) {
        AutoTrimSheet(
            viewModel = viewModel,
            editor = editor,
            autoState = autoState,
            onClose = { AutoTrimStateStore.dismiss(editor.sessionPath) },
        )
    }
}

@Composable
private fun AutoTrimSheet(
    viewModel: MainViewModel,
    editor: TrimEditorState,
    autoState: AutoTrimUiState,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val analyzer = remember(context) { AutoTrimAnalyzer(context.applicationContext) }
    var notice by remember(editor.sessionPath) { mutableStateOf<String?>(null) }
    var previewStopMs by remember(editor.sessionPath) { mutableStateOf<Long?>(null) }

    val mediaUri = editor.localPath?.let { Uri.fromFile(File(it)) } ?: Uri.parse(editor.sourceUri)
    val player = remember(editor.sessionPath) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(mediaUri))
            prepare()
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    LaunchedEffect(player, previewStopMs) {
        while (previewStopMs != null) {
            val stop = previewStopMs ?: break
            if (player.currentPosition >= stop) {
                player.pause()
                previewStopMs = null
                break
            }
            delay(50)
        }
    }

    fun startAnalysis() {
        player.pause()
        previewStopMs = null
        notice = null
        AutoTrimAnalysisService.start(
            context = context.applicationContext,
            sourceUri = editor.sourceUri,
            sessionPath = editor.sessionPath,
            localInputPath = editor.localPath,
            durationMs = editor.durationMs,
        )
    }

    fun preview(candidate: AutoTrimCandidate) {
        val start = (candidate.boundaryMs - PREVIEW_RADIUS_MS).coerceAtLeast(0L)
        val end = (candidate.boundaryMs + PREVIEW_RADIUS_MS).coerceAtMost(editor.durationMs)
        player.pause()
        player.seekTo(start)
        previewStopMs = end
        player.play()
    }

    fun accept(candidate: AutoTrimCandidate) {
        val rangeStart = if (candidate.side == AutoTrimSide.START) 0L else candidate.boundaryMs
        val rangeEnd = if (candidate.side == AutoTrimSide.START) candidate.boundaryMs else editor.durationMs
        player.pause()
        previewStopMs = null
        viewModel.updateTrimRange(rangeStart, rangeEnd)
        viewModel.addCurrentCutRange()
        notice = when (candidate.side) {
            AutoTrimSide.START -> "先頭の候補を削除リストへ追加しました"
            AutoTrimSide.END -> "末尾の候補を削除リストへ追加しました"
        }
        autoState.analysis?.let { currentAnalysis ->
            scope.launch(Dispatchers.IO) {
                analyzer.rememberConfirmed(
                    analysis = currentAnalysis,
                    candidate = candidate,
                    durationMs = editor.durationMs,
                )
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("前後の不要動画を自動検出", style = MaterialTheme.typography.headlineSmall)
        Text(
            "先頭と末尾だけを解析します。解析はバックグラウンドでも継続し、戻ったときに同じ結果画面へ復帰します。",
            style = MaterialTheme.typography.bodyMedium,
        )

        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxWidth().height(190.dp),
        )
        Text("候補の「±5秒確認」で境界の前後だけ再生します。", style = MaterialTheme.typography.bodySmall)

        if (autoState.running) {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(modifier = Modifier.height(28.dp))
                        Text("前後だけ解析中…")
                    }
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        "アプリをバックグラウンドにしても解析は継続します。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = { AutoTrimAnalysisService.cancel(context.applicationContext) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("解析をキャンセル")
                    }
                }
            }
        }

        autoState.analysis?.let { result ->
            CandidateSection(
                title = "先頭",
                emptyText = "先頭側に明確な境界候補は見つかりませんでした。",
                candidates = result.startCandidates,
                onPreview = ::preview,
                onAccept = ::accept,
            )
            CandidateSection(
                title = "末尾",
                emptyText = "末尾側に明確な境界候補は見つかりませんでした。",
                candidates = result.endCandidates,
                onPreview = ::preview,
                onAccept = ::accept,
            )
            Text(
                "解析範囲: 先頭 ${formatAutoTime(result.scannedStart.endMs)} / 末尾 ${formatAutoTime(result.scannedEnd.durationMs)}。採用した前後動画は端末内だけに指紋保存され、次回から既知クリップとして照合します。",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        autoState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = ::startAnalysis,
                enabled = !autoState.running,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (autoState.analysis == null) "解析する" else "再解析")
            }
            TextButton(
                onClick = onClose,
                modifier = Modifier.weight(1f),
            ) {
                Text("閉じる")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CandidateSection(
    title: String,
    emptyText: String,
    candidates: List<AutoTrimCandidate>,
    onPreview: (AutoTrimCandidate) -> Unit,
    onAccept: (AutoTrimCandidate) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (candidates.isEmpty()) {
                Text(emptyText, style = MaterialTheme.typography.bodySmall)
            } else {
                candidates.forEachIndexed { index, candidate ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("候補 ${index + 1}: ${formatAutoTime(candidate.boundaryMs)}")
                            Text("${(candidate.confidence * 100).toInt()}%")
                        }
                        Text(
                            evidenceText(candidate),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = { onPreview(candidate) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("±5秒確認")
                            }
                            Button(
                                onClick = { onAccept(candidate) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("採用")
                            }
                        }
                    }
                    if (index < candidates.lastIndex) Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

private fun evidenceText(candidate: AutoTrimCandidate): String {
    val labels = candidate.evidence.map { evidence ->
        when (evidence) {
            AutoTrimEvidence.KNOWN_CLIP -> "既知クリップ一致"
            AutoTrimEvidence.SCENE_CHANGE -> "映像切替"
            AutoTrimEvidence.BLACK_FRAME -> "黒画面"
            AutoTrimEvidence.AUDIO_CHANGE -> "音声変化"
            AutoTrimEvidence.SILENCE_BOUNDARY -> "無音境界"
            AutoTrimEvidence.SCENE_DENSITY -> "前後のシーン密度差"
        }
    }
    val known = candidate.knownClipSimilarity?.let { " / 指紋一致 ${(it * 100).toInt()}%" }.orEmpty()
    return "根拠: ${labels.joinToString("・")}$known"
}

private fun formatAutoTime(ms: Long): String {
    val safe = ms.coerceAtLeast(0L)
    val hours = safe / 3_600_000L
    val minutes = (safe / 60_000L) % 60L
    val seconds = (safe / 1_000L) % 60L
    val millis = safe % 1_000L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d.%03d", hours, minutes, seconds, millis)
    } else {
        String.format(Locale.US, "%02d:%02d.%03d", minutes, seconds, millis)
    }
}

private const val PREVIEW_RADIUS_MS = 5_000L
