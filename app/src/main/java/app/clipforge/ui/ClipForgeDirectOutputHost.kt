package app.clipforge.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clipforge.MainViewModel

private const val XFILES_PACKAGE = "app.local1st.files"
private const val XFILES_OUTPUT_ACTION = "app.local1st.files.action.PICK_OUTPUT"
private const val XFILES_OUTPUT_NAME = "app.local1st.files.extra.OUTPUT_NAME"
private const val XFILES_OUTPUT_MIME = "app.local1st.files.extra.OUTPUT_MIME"

private enum class OutputPicker {
    LOCAL,
    XFILES,
}

@Composable
fun ClipForgeDirectOutputHost(viewModel: MainViewModel) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val request = state.pendingDestination
    var activePicker by remember { mutableStateOf<OutputPicker?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val picker = activePicker
        activePicker = null
        if (result.resultCode != Activity.RESULT_OK) {
            viewModel.destinationPickerCancelled()
            return@rememberLauncherForActivityResult
        }
        val uri = result.data?.data
        if (uri == null) {
            val source = if (picker == OutputPicker.XFILES) "XFiles" else "保存先選択画面"
            viewModel.destinationPickerFailed("$source から保存先URIを受け取れませんでした")
            return@rememberLauncherForActivityResult
        }
        val grantedFlags = result.data?.flags
            ?.and(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            ?: (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        persistGrant(context, uri, grantedFlags)
        viewModel.startPendingDestination(uri.toString())
    }

    if (request != null && activePicker == null) {
        AlertDialog(
            onDismissRequest = viewModel::destinationPickerCancelled,
            title = { Text("保存先") },
            text = { Text("端末内に保存するか、XFiles経由でSMBへ保存するか選択してください。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        activePicker = OutputPicker.LOCAL
                        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType(request.mimeType)
                            .putExtra(Intent.EXTRA_TITLE, request.outputName)
                            .addFlags(
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                            )
                        runCatching { launcher.launch(intent) }
                            .onFailure { error ->
                                activePicker = null
                                viewModel.destinationPickerFailed(error.message ?: "端末の保存先を開けませんでした")
                            }
                    },
                ) {
                    Text("端末に保存")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        activePicker = OutputPicker.XFILES
                        val intent = Intent(XFILES_OUTPUT_ACTION)
                            .setPackage(XFILES_PACKAGE)
                            .putExtra(XFILES_OUTPUT_NAME, request.outputName)
                            .putExtra(XFILES_OUTPUT_MIME, request.mimeType)
                        if (intent.resolveActivity(context.packageManager) == null) {
                            activePicker = null
                            viewModel.destinationPickerFailed("XFilesを最新バージョンへ更新してください")
                            return@TextButton
                        }
                        runCatching { launcher.launch(intent) }
                            .onFailure { error ->
                                activePicker = null
                                viewModel.destinationPickerFailed(error.message ?: "XFilesを開けませんでした")
                            }
                    },
                ) {
                    Text("SMBに保存")
                }
            },
        )
    }
}

private fun persistGrant(context: Context, uri: Uri, flags: Int) {
    if (flags == 0) return
    runCatching {
        context.contentResolver.takePersistableUriPermission(uri, flags)
    }
}
