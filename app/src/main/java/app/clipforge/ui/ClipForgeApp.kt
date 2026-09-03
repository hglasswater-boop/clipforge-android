package app.clipforge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clipforge.MainViewModel
import app.clipforge.smb.SmbEntry

@Composable
fun ClipForgeApp(viewModel: MainViewModel) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        ClipForgeScreen(viewModel)
    }
}

@Composable
private fun ClipForgeScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var host by remember { mutableStateOf("") }
    var share by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var outputName by remember { mutableStateOf("merged.mkv") }
    var startSeconds by remember { mutableStateOf("0") }
    var endSeconds by remember { mutableStateOf("") }

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
                    RemoteEntryRow(entry, entry.path in state.selectedPaths, !state.busy,
                        onOpen = { viewModel.openDirectory(entry) }, onToggle = { viewModel.toggleSelection(entry) })
                }
                item {
                    EditCard(
                        selectedCount = state.selectedPaths.size,
                        outputName = outputName,
                        startSeconds = startSeconds,
                        endSeconds = endSeconds,
                        enabled = !state.busy,
                        onOutputName = { outputName = it },
                        onStart = { startSeconds = it },
                        onEnd = { endSeconds = it },
                        onConcat = { viewModel.concatSelected(outputName) },
                        onCut = { viewModel.cutSelected(outputName, startSeconds, endSeconds) }
                    )
                }
            }

            item {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.status, style = MaterialTheme.typography.bodyMedium)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(24.dp))
            }
        }
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
private fun RemoteEntryRow(entry: SmbEntry, selected: Boolean, enabled: Boolean, onOpen: () -> Unit, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { if (entry.directory) onOpen() else if (entry.isVideo) onToggle() }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (entry.directory) "📁" else "🎞️", modifier = Modifier.padding(end = 10.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name)
            if (!entry.directory) Text(formatBytes(entry.size), style = MaterialTheme.typography.bodySmall)
        }
        if (entry.isVideo) Checkbox(selected, onCheckedChange = { onToggle() }, enabled = enabled)
    }
    HorizontalDivider()
}

@Composable
private fun EditCard(
    selectedCount: Int, outputName: String, startSeconds: String, endSeconds: String, enabled: Boolean,
    onOutputName: (String) -> Unit, onStart: (String) -> Unit, onEnd: (String) -> Unit,
    onConcat: () -> Unit, onCut: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("編集", style = MaterialTheme.typography.titleMedium)
            Text("選択: ${selectedCount}本")
            OutlinedTextField(outputName, onOutputName, Modifier.fillMaxWidth(), label = { Text("出力ファイル名 (.mp4 / .mkv)") }, enabled = enabled)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(startSeconds, onStart, Modifier.weight(1f), label = { Text("開始 秒") }, enabled = enabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(endSeconds, onEnd, Modifier.weight(1f), label = { Text("終了 秒（空=末尾）") }, enabled = enabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConcat, enabled = enabled && selectedCount >= 2) { Text("無劣化で結合") }
                Button(onClick = onCut, enabled = enabled && selectedCount == 1) { Text("無劣化でカット") }
            }
            Text(
                "無劣化カットは再エンコードしないため、映像の開始位置は最寄りのキーフレーム境界になります。フレーム単位の完全一致には再エンコードが必要です。",
                style = MaterialTheme.typography.bodySmall
            )
        }
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
