package app.clipforge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.clipforge.media.AutoTrimStateStore
import app.clipforge.processing.AutoTrimAnalysisService
import app.clipforge.ui.AutoTrimEntryButton
import app.clipforge.ui.AutoTrimHost
import app.clipforge.ui.ClipForgeApp
import app.clipforge.ui.ClipForgeDirectOutputHost
import app.clipforge.update.ClipForgeUpdateHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionOnce()
        handleIncomingIntent(intent)
        setContent {
            val mainState by mainViewModel.uiState.collectAsStateWithLifecycle()
            val dark = isSystemInDarkTheme()

            LaunchedEffect(mainState.trimEditor?.sessionPath) {
                val editor = mainState.trimEditor ?: return@LaunchedEffect
                val restored = withContext(Dispatchers.IO) {
                    AutoTrimStateStore.restore(editor.sessionPath)
                }
                val autoState = AutoTrimStateStore.state.value
                if (
                    restored &&
                    autoState.sessionPath == editor.sessionPath &&
                    autoState.running
                ) {
                    // START_REDELIVER_INTENT normally revives the service after process pressure.
                    // Starting again here is an idempotent self-heal for a cold Activity restore.
                    AutoTrimAnalysisService.start(
                        context = applicationContext,
                        sourceUri = editor.sourceUri,
                        sessionPath = editor.sessionPath,
                        localInputPath = editor.localPath,
                        durationMs = editor.durationMs,
                    )
                }
            }

            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Box(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .navigationBarsPadding(),
                    ) {
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        ) {
                            ClipForgeApp(mainViewModel)
                        }
                        mainState.trimEditor?.let { editor ->
                            AutoTrimEntryButton(
                                editor = editor,
                                enabled = !mainState.busy,
                            )
                        }
                    }
                    ClipForgeDirectOutputHost(mainViewModel)
                    AutoTrimHost(mainViewModel)
                    ClipForgeUpdateHost(showEntryButton = mainState.trimEditor == null)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(incoming: Intent?) {
        val source = incoming ?: return
        val uris = sharedVideoUris(source)
        if (uris.isEmpty()) return

        mainViewModel.acceptPickedUris(uris)

        // Consume the share payload so a configuration change does not import the same files again.
        source.action = Intent.ACTION_MAIN
        source.data = null
        source.clipData = null
        source.replaceExtras(Bundle())
    }

    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("clipforge_permissions", MODE_PRIVATE)
        if (prefs.getBoolean("asked_notifications", false)) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
    }
}
