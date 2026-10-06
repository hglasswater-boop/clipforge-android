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
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clipforge.MainViewModel

private const val XFILES_PACKAGE = "app.local1st.files"
private const val XFILES_OUTPUT_ACTION = "app.local1st.files.action.PICK_OUTPUT"
private const val XFILES_OUTPUT_NAME = "app.local1st.files.extra.OUTPUT_NAME"
private const val XFILES_OUTPUT_MIME = "app.local1st.files.extra.OUTPUT_MIME"

@Composable
fun ClipForgeDirectOutputHost(viewModel: MainViewModel) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val request = state.pendingDestination

    val outputLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            viewModel.destinationPickerCancelled()
            return@rememberLauncherForActivityResult
        }
        val uri = result.data?.data
        if (uri == null) {
            viewModel.destinationPickerFailed("保存先URIを受け取れませんでした")
            return@rememberLauncherForActivityResult
        }
        val grantedFlags = result.data?.flags
            ?.and(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            ?: (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        persistGrant(context, uri, grantedFlags)
        viewModel.startPendingDestination(uri.toString())
    }

    val xFilesIntent = request?.let { pending ->
        Intent(XFILES_OUTPUT_ACTION)
            .setPackage(XFILES_PACKAGE)
            .putExtra(XFILES_OUTPUT_NAME, pending.outputName)
            .putExtra(XFILES_OUTPUT_MIME, pending.mimeType)
    }
    val destinations = availableOutputDestinations(
        xFilesAvailable = xFilesIntent?.resolveActivity(context.packageManager) != null,
    )

    request?.let { pending ->
        AlertDialog(
            onDismissRequest = viewModel::destinationPickerCancelled,
            title = { Text("保存先を選択") },
            text = {
                Text(
                    if (OutputDestinationTarget.XFILES_SMB in destinations) {
                        "端末ローカル、またはXFilesでSMBの保存先を選択できます。"
                    } else {
                        "端末ローカルの保存先を選択できます。"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType(pending.mimeType)
                            .putExtra(Intent.EXTRA_TITLE, pending.outputName)
                            .addFlags(
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                            )
                        runCatching { outputLauncher.launch(intent) }
                            .onFailure { error ->
                                viewModel.destinationPickerFailed(error.message ?: "端末の保存先を開けませんでした")
                            }
                    },
                ) {
                    Text("端末に保存")
                }
            },
            dismissButton = {
                if (OutputDestinationTarget.XFILES_SMB in destinations) {
                    TextButton(
                        onClick = {
                            val intent = checkNotNull(xFilesIntent)
                            runCatching { outputLauncher.launch(intent) }
                                .onFailure { error ->
                                    viewModel.destinationPickerFailed(error.message ?: "XFilesを開けませんでした")
                                }
                        },
                    ) {
                        Text("XFilesでSMBに保存")
                    }
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
