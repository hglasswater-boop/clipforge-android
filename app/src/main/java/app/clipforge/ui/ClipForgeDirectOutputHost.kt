package app.clipforge.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            viewModel.destinationPickerCancelled()
            return@rememberLauncherForActivityResult
        }
        val uri = result.data?.data
        if (uri == null) {
            viewModel.destinationPickerFailed("XFilesから保存先URIを受け取れませんでした")
            return@rememberLauncherForActivityResult
        }
        val grantedFlags = result.data?.flags
            ?.and(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            ?: (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        persistGrant(context, uri, grantedFlags)
        viewModel.startPendingDestination(uri.toString())
    }

    LaunchedEffect(request?.token) {
        val pending = request ?: return@LaunchedEffect
        val intent = Intent(XFILES_OUTPUT_ACTION)
            .setPackage(XFILES_PACKAGE)
            .putExtra(XFILES_OUTPUT_NAME, pending.outputName)
            .putExtra(XFILES_OUTPUT_MIME, pending.mimeType)
        if (intent.resolveActivity(context.packageManager) == null) {
            viewModel.destinationPickerFailed("XFilesを最新バージョンへ更新してください")
            return@LaunchedEffect
        }
        runCatching { launcher.launch(intent) }
            .onFailure { error ->
                viewModel.destinationPickerFailed(error.message ?: "XFilesを開けませんでした")
            }
    }
}

private fun persistGrant(context: Context, uri: Uri, flags: Int) {
    if (flags == 0) return
    runCatching {
        context.contentResolver.takePersistableUriPermission(uri, flags)
    }
}
