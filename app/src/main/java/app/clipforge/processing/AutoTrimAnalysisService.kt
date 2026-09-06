package app.clipforge.processing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import app.clipforge.MainActivity
import app.clipforge.media.AutoTrimAnalyzer
import app.clipforge.media.AutoTrimPhase
import app.clipforge.media.AutoTrimStateStore
import com.arthenica.ffmpegkit.FFmpegKit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

class AutoTrimAnalysisService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var analysisJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var cancellationRequested = false
    private var lastNotificationPercent = -1

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = intent ?: return START_NOT_STICKY
        if (request.action == ACTION_CANCEL) {
            cancellationRequested = true
            FFmpegKit.cancel()
            analysisJob?.cancel(CancellationException("Auto trim cancelled"))
            return START_NOT_STICKY
        }
        if (request.action != ACTION_ANALYZE || analysisJob?.isActive == true) return START_NOT_STICKY

        val sourceUri = requireNotNull(request.getStringExtra(EXTRA_SOURCE_URI))
        val sessionPath = requireNotNull(request.getStringExtra(EXTRA_SESSION_PATH))
        val localInputPath = request.getStringExtra(EXTRA_LOCAL_INPUT)
        val durationMs = request.getLongExtra(EXTRA_DURATION_MS, -1L)
        require(durationMs > 0L) { "動画の長さが不正です" }

        cancellationRequested = false
        lastNotificationPercent = -1
        AutoTrimStateStore.begin(sessionPath)
        startAnalysisForeground(
            buildNotification(
                title = "前後を自動解析中",
                message = "解析を準備しています",
                ongoing = true,
                progressPercent = 0,
            ),
        )
        acquireWakeLock()

        analysisJob = serviceScope.launch {
            try {
                val analysis = AutoTrimAnalyzer(applicationContext).analyze(
                    sourceUri = sourceUri,
                    localInputPath = localInputPath,
                    durationMs = durationMs,
                    onProgress = { phase, percent ->
                        AutoTrimStateStore.progress(sessionPath, phase, percent)
                        updateProgressNotification(phase, percent)
                    },
                )
                AutoTrimStateStore.ready(sessionPath, analysis)
                finishNotification("自動解析が完了しました")
            } catch (error: Throwable) {
                if (cancellationRequested || error is CancellationException) {
                    AutoTrimStateStore.cancelled(sessionPath)
                    finishNotification("自動解析をキャンセルしました")
                } else {
                    val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                    AutoTrimStateStore.failure(sessionPath, detail)
                    finishNotification("自動解析に失敗しました: $detail")
                }
            } finally {
                releaseWakeLock()
                analysisJob = null
                cancellationRequested = false
                lastNotificationPercent = -1
                stopSelf()
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        cancellationRequested = true
        FFmpegKit.cancel()
        analysisJob?.cancel(CancellationException("Auto trim foreground timeout"))
        releaseWakeLock()
        stopSelf(startId)
    }

    override fun onDestroy() {
        if (analysisJob?.isActive == true) {
            cancellationRequested = true
            FFmpegKit.cancel()
            analysisJob?.cancel()
        }
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("NewApi")
    private fun startAnalysisForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 35) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateProgressNotification(phase: AutoTrimPhase, percent: Int) {
        val safePercent = percent.coerceIn(0, 99)
        if (safePercent == lastNotificationPercent) return
        lastNotificationPercent = safePercent
        val progress = AutoTrimStateStore.state.value.progress
        val etaText = progress?.estimatedRemainingMs()?.let(::formatShortDuration)
        val message = buildString {
            append("$safePercent% ・ ${phase.label}")
            if (etaText != null) append(" ・ 残り約$etaText")
        }
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(
                title = "前後を自動解析中",
                message = message,
                ongoing = true,
                progressPercent = safePercent,
            ),
        )
    }

    private fun finishNotification(message: String) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification("ClipForge", message, ongoing = false),
        )
    }

    private fun buildNotification(
        title: String,
        message: String,
        ongoing: Boolean,
        progressPercent: Int? = null,
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(pending)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setAutoCancel(!ongoing)

        if (ongoing) {
            val progress = progressPercent?.coerceIn(0, 99)
            if (progress == null) {
                builder.setProgress(0, 0, true)
            } else {
                builder.setProgress(100, progress, false)
            }
            val cancelPending = PendingIntent.getService(
                this,
                1,
                Intent(this, AutoTrimAnalysisService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "キャンセル",
                cancelPending,
            )
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "自動解析",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "ClipForgeの前後不要動画の自動解析"
            },
        )
    }

    private fun acquireWakeLock() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClipForge:auto-trim-analysis")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) runCatching { lock.release() }
        }
        wakeLock = null
    }

    private fun formatShortDuration(ms: Long): String {
        val totalSeconds = ((ms.coerceAtLeast(0L) + 999L) / 1_000L).coerceAtLeast(1L)
        return if (totalSeconds < 60L) {
            "${totalSeconds}秒"
        } else {
            val minutes = totalSeconds / 60L
            val seconds = totalSeconds % 60L
            if (seconds == 0L) "${minutes}分" else String.format(Locale.JAPAN, "%d分%02d秒", minutes, seconds)
        }
    }

    companion object {
        private const val CHANNEL_ID = "clipforge_auto_trim_analysis"
        private const val NOTIFICATION_ID = 4102
        private const val ACTION_ANALYZE = "app.clipforge.action.AUTO_TRIM_ANALYZE"
        private const val ACTION_CANCEL = "app.clipforge.action.AUTO_TRIM_CANCEL"
        private const val EXTRA_SOURCE_URI = "sourceUri"
        private const val EXTRA_SESSION_PATH = "sessionPath"
        private const val EXTRA_LOCAL_INPUT = "localInput"
        private const val EXTRA_DURATION_MS = "durationMs"

        fun start(
            context: Context,
            sourceUri: String,
            sessionPath: String,
            localInputPath: String?,
            durationMs: Long,
        ) {
            val intent = Intent(context, AutoTrimAnalysisService::class.java)
                .setAction(ACTION_ANALYZE)
                .putExtra(EXTRA_SOURCE_URI, sourceUri)
                .putExtra(EXTRA_SESSION_PATH, sessionPath)
                .putExtra(EXTRA_DURATION_MS, durationMs)
            localInputPath?.let { intent.putExtra(EXTRA_LOCAL_INPUT, it) }
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            context.startService(
                Intent(context, AutoTrimAnalysisService::class.java).setAction(ACTION_CANCEL),
            )
        }
    }
}
