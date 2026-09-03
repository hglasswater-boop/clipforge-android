package app.clipforge.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.clipforge.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private object ClipForgeSelfUpdater {
    private const val RELEASE_API =
        "https://api.github.com/repos/hglasswater-boop/clipforge-android/releases/tags/debug-latest"
    private const val PREFS = "clipforge_self_update"
    private const val LAST_AUTO_CHECK = "last_auto_check"
    private const val AUTO_CHECK_ENABLED = "auto_check_enabled"
    private const val AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L

    fun isAutoCheckEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(AUTO_CHECK_ENABLED, true)

    fun setAutoCheckEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(AUTO_CHECK_ENABLED, enabled)
            .apply()
    }

    fun lastCheck(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(LAST_AUTO_CHECK, 0L)

    fun autoCheckDue(context: Context): Boolean =
        isAutoCheckEnabled(context) &&
            System.currentTimeMillis() - lastCheck(context) >= AUTO_CHECK_INTERVAL_MS

    fun markChecked(context: Context): Long {
        val now = System.currentTimeMillis()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(LAST_AUTO_CHECK, now)
            .apply()
        return now
    }

    suspend fun check(): ClipForgeRelease? = withContext(Dispatchers.IO) {
        val connection = openConnection(RELEASE_API, "application/vnd.github+json")
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("GitHub HTTP $code")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val assetsJson = JSONObject(body).getJSONArray("assets")
            val assets = buildList {
                for (index in 0 until assetsJson.length()) {
                    val asset = assetsJson.getJSONObject(index)
                    add(asset.optString("name") to asset.optString("browser_download_url"))
                }
            }
            UpdateReleaseParser.newest(assets, BuildConfig.VERSION_CODE)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun downloadAndValidate(context: Context, release: ClipForgeRelease): File =
        withContext(Dispatchers.IO) {
            val updateDir = File(context.cacheDir, "clipforge-updates").apply { mkdirs() }
            updateDir.listFiles()?.forEach { it.delete() }
            val partial = File(updateDir, "${release.assetName}.part")
            val target = File(updateDir, release.assetName)

            val connection = openConnection(release.downloadUrl, "application/octet-stream")
            try {
                val code = connection.responseCode
                if (code !in 200..299) error("Download HTTP $code")
                connection.inputStream.use { input ->
                    FileOutputStream(partial).use { output -> input.copyTo(output) }
                }
            } finally {
                connection.disconnect()
            }

            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
            validateApk(context, target, release)
            target
        }

    fun canInstallPackages(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun launchInstaller(context: Context, apk: File) {
        SelfUpdateInstaller.install(context, apk)
    }

    @Suppress("DEPRECATION")
    private fun validateApk(context: Context, apk: File, release: ClipForgeRelease) {
        val packageInfo = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            ?: error("ダウンロードしたAPKを読み取れません")
        if (packageInfo.packageName != context.packageName) {
            error("ダウンロードしたAPKはClipForgeではありません")
        }
        val downloadedBuild = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
        if (downloadedBuild <= BuildConfig.VERSION_CODE.toLong()) {
            error("ダウンロードしたAPKは現在のビルドより新しくありません")
        }
        if (downloadedBuild != release.buildNumber.toLong()) {
            error("APKのビルド番号がGitHub Releaseと一致しません")
        }
    }

    private fun openConnection(url: String, accept: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "ClipForge/${BuildConfig.VERSION_NAME}")
        }
}

@Composable
fun ClipForgeUpdateHost() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var available by remember { mutableStateOf<ClipForgeRelease?>(null) }
    var autoCheckEnabled by remember { mutableStateOf(ClipForgeSelfUpdater.isAutoCheckEnabled(context)) }
    var lastCheck by remember { mutableStateOf(ClipForgeSelfUpdater.lastCheck(context)) }

    LaunchedEffect(Unit) {
        if (!ClipForgeSelfUpdater.autoCheckDue(context)) return@LaunchedEffect
        runCatching { ClipForgeSelfUpdater.check() }
            .onSuccess { release ->
                lastCheck = ClipForgeSelfUpdater.markChecked(context)
                available = release
            }
    }

    Box(Modifier.fillMaxSize()) {
        OutlinedButton(
            onClick = { showSettings = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) {
            Text("更新")
        }
    }

    if (showSettings && available == null) {
        AlertDialog(
            onDismissRequest = { if (!checking) showSettings = false },
            title = { Text("ClipForge 更新") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("現在 v${BuildConfig.VERSION_NAME} / build ${BuildConfig.VERSION_CODE}")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("自動で更新を確認")
                            Text(
                                "24時間ごとにGitHub Releaseを確認します",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = autoCheckEnabled,
                            onCheckedChange = { enabled ->
                                autoCheckEnabled = enabled
                                ClipForgeSelfUpdater.setAutoCheckEnabled(context, enabled)
                            }
                        )
                    }
                    Text(
                        if (lastCheck > 0L) {
                            "最終確認: ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(lastCheck))}"
                        } else {
                            "まだ更新確認していません"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    statusMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(
                        enabled = !checking,
                        onClick = {
                            scope.launch {
                                checking = true
                                statusMessage = null
                                runCatching { ClipForgeSelfUpdater.check() }
                                    .onSuccess { release ->
                                        lastCheck = ClipForgeSelfUpdater.markChecked(context)
                                        if (release == null) {
                                            statusMessage = "最新版です"
                                        } else {
                                            available = release
                                            showSettings = false
                                        }
                                    }
                                    .onFailure { error ->
                                        statusMessage = "更新確認に失敗: ${error.message ?: error.javaClass.simpleName}"
                                    }
                                checking = false
                            }
                        }
                    ) { Text(if (checking) "確認中…" else "今すぐ確認") }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettings = false }, enabled = !checking) {
                    Text("閉じる")
                }
            }
        )
    }

    available?.let { release ->
        UpdateAvailableDialog(
            release = release,
            onDismiss = { available = null }
        )
    }
}

@Composable
private fun UpdateAvailableDialog(
    release: ClipForgeRelease,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    var preparingInstall by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!downloading && !preparingInstall) onDismiss() },
        title = { Text("新しいClipForgeがあります") },
        text = {
            Column {
                Text("v${release.versionName} / build ${release.buildNumber} に更新できます。")
                if (!ClipForgeSelfUpdater.canInstallPackages(context)) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "最初の1回だけ、このアプリからのインストールを許可してください。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                errorMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !downloading && !preparingInstall,
                onClick = {
                    if (!ClipForgeSelfUpdater.canInstallPackages(context)) {
                        ClipForgeSelfUpdater.openInstallPermission(context)
                        return@TextButton
                    }
                    scope.launch {
                        downloading = true
                        preparingInstall = false
                        errorMessage = null
                        val apk = runCatching {
                            ClipForgeSelfUpdater.downloadAndValidate(context, release)
                        }.getOrElse { error ->
                            downloading = false
                            errorMessage = error.message ?: error.javaClass.simpleName
                            return@launch
                        }
                        downloading = false
                        preparingInstall = true
                        runCatching {
                            withContext(Dispatchers.IO) {
                                ClipForgeSelfUpdater.launchInstaller(context, apk)
                            }
                        }.onSuccess {
                            onDismiss()
                        }.onFailure { error ->
                            preparingInstall = false
                            errorMessage = error.message ?: error.javaClass.simpleName
                        }
                    }
                }
            ) {
                Text(
                    when {
                        downloading -> "ダウンロード中…"
                        preparingInstall -> "インストール準備中…"
                        !ClipForgeSelfUpdater.canInstallPackages(context) -> "インストール許可を開く"
                        else -> "更新する"
                    }
                )
            }
        },
        dismissButton = {
            TextButton(enabled = !downloading && !preparingInstall, onClick = onDismiss) {
                Text("あとで")
            }
        }
    )
}
