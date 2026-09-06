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
import app.clipforge.media.AutoTrimAnalyzer
import app.clipforge.media.AutoTrimCandidate
import app.clipforge.media.AutoTrimEvidence
import app.clipforge.media.AutoTrimRangeSettings
import app.clipforge.media.AutoTrimSide
import app.clipforge.media.AutoTrimStateStore
import app.clipforge.media.AutoTrimUiState
import app.clipforge.media.isAutoTrimCandidateApplied
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
                AutoTrimStateStore.open(editor.sessionPath)
            }
        },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            when {
                currentSession && autoState.running ->
                    "前後を自動解析中 ${autoState.progress?.overallPercent ?: 0}%"
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
            processingBusy = state.busy,
            onClose = { AutoTrimStateStore.dismiss(editor.sessionPath) },
        )
    }
}

@Composable
private fun AutoTrimSheet(
    viewModel: MainViewModel,
    editor: TrimEditorState,
    autoState: AutoTrimUiState,
    processingBusy: Boolean,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val analyzer = remember(context) { AutoTrimAnalyzer(context.applicationContext) }
    val rangeSettings = remember(context) { AutoTrimRangeSettings(context.applicationContext) }
    var selectedEdgeWindowMs by remember(editor.sessionPath) {
        mutableStateOf(rangeSettings.loadEdgeWindowMs())
    }
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

    fun selectRange(value: Long) {
        if (autoState.running) return
        selectedEdgeWindowMs = value
        rangeSettings.saveEdgeWindowMs(value)
    }

    fun startAnalysis() {
        player.pause()
        previewStopMs = null
        notice = null
        rangeSettings.saveEdgeWindowMs(selectedEdgeWindowMs)
        AutoTrimAnalysisService.start(
            context = context.applicationContext,
            sourceUri = editor.sourceUri,
            sessionPath = editor.sessionPath,
            localInputPath = editor.localPath,
            durationMs = editor.durationMs,
            edgeWindowMs = selectedEdgeWindowMs,
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

    fun isAccepted(candidate: AutoTrimCandidate): Boolean = isAutoTrimCandidateApplied(
        candidate = candidate,
        durationMs = editor.durationMs,
        cutRanges = editor.cutRanges,
        cutMode = editor.cutMode,
    )

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

        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("解析範囲", style = MaterialTheme.typography.titleMedium)
                Text(
                    "先頭・末尾それぞれを何分調べるか選びます。通常は5分がおすすめです。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RangeChoiceButton(
                        minutes = 1,
                        selected = selectedEdgeWindowMs == 1 * AutoTrimRangeSettings.ONE_MINUTE_MS,
                        enabled = !autoState.running,
                        onClick = { selectRange(1 * AutoTrimRangeSettings.ONE_MINUTE_MS) },
                        modifier = Modifier.weight(1f),
                    )
                    RangeChoiceButton(
                        minutes = 3,
                        selected = selectedEdgeWindowMs == 3 * AutoTrimRangeSettings.ONE_MINUTE_MS,
                        enabled = !autoState.running,
                        onClick = { selectRange(3 * AutoTrimRangeSettings.ONE_MINUTE_MS) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RangeChoiceButton(
                        minutes = 5,
                        selected = selectedEdgeWindowMs == 5 * AutoTrimRangeSettings.ONE_MINUTE_MS,
                        enabled = !autoState.running,
                        onClick = { selectRange(5 * AutoTrimRangeSettings.ONE_MINUTE_MS) },
                        modifier = Modifier.weight(1f),
                    )
                    RangeChoiceButton(
                        minutes = 10,
                        selected = selectedEdgeWindowMs == 10 * AutoTrimRangeSettings.ONE_MINUTE_MS,
                        enabled = !autoState.running,
                        onClick = { selectRange(10 * AutoTrimRangeSettings.ONE_MINUTE_MS) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    "選択中: 前後それぞれ ${selectedEdgeWindowMs / AutoTrimRangeSettings.ONE_MINUTE_MS}分",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

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
            val progress = autoState.progress
            val overallPercent = progress?.overallPercent?.coerceIn(0, 99) ?: 0
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(progress?.phase?.label ?: "解析を準備中")
                        Text("$overallPercent%", style = MaterialTheme.typography.titleMedium)
                    }
                    LinearProgressIndicator(
                        progress = { overallPercent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    progress?.let {
                        Text(
                            "この工程 ${it.phasePercent}% ・ 経過 ${formatDurationShort(it.elapsedMs)} ・ " +
                                "残り ${it.remainingMs?.let { remaining -> "約${formatDurationShort(remaining)}" } ?: "計算中"}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        "進捗は実際に解析できた動画時間とfingerprint取得数から計算しています。バックグラウンドでも継続します。",
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
                acceptEnabled = !processingBusy,
                isAccepted = ::isAccepted,
                onPreview = ::preview,
                onAccept = ::accept,
            )
            CandidateSection(
                title = "末尾",
                emptyText = "末尾側に明確な境界候補は見つかりませんでした。",
                candidates = result.endCandidates,
                acceptEnabled = !processingBusy,
                isAccepted = ::isAccepted,
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
private fun RangeChoiceButton(
    minutes: Int,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
        ) {
            Text("${minutes}分")
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
        ) {
            Text("${minutes}分")
        }
    }
}

@Composable
private fun CandidateSection(
    title: String,
    emptyText: String,
    candidates: List<AutoTrimCandidate>,
    acceptEnabled: Boolean,
    isAccepted: (AutoTrimCandidate) -> Boolean,
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
                    val accepted = isAccepted(candidate)
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
                                enabled = acceptEnabled && !accepted,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(if (accepted) "採用済み ✓" else "採用")
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

private fun formatDurationShort(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1_000L).coerceAtLeast(0L)
    if (totalSeconds < 60L) return "${totalSeconds}秒"
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (seconds == 0L) "${minutes}分" else String.format(Locale.JAPAN, "%d分%02d秒", minutes, seconds)
}

private const val PREVIEW_RADIUS_MS = 5_000L
