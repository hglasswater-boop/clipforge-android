package app.clipforge.processing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import app.clipforge.MainActivity
import app.clipforge.media.FfmpegMediaEngine
import app.clipforge.workflow.ExternalEditPipeline
import app.clipforge.workflow.PickedVideo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ClipForgeProcessingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var processingJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = intent ?: return START_NOT_STICKY
        if (processingJob?.isActive == true) return START_NOT_STICKY

        val title = when (request.action) {
            ACTION_CONCAT -> "動画を結合中"
            ACTION_CUT -> "動画をカット中"
            else -> return START_NOT_STICKY
        }
        updateProgress(title, "処理を準備しています")
        startForeground(NOTIFICATION_ID, buildNotification(title, "処理を準備しています", true))
        acquireWakeLock()

        processingJob = serviceScope.launch {
            val pipeline = ExternalEditPipeline(
                cacheRoot = cacheDir,
                contentResolver = contentResolver,
                mediaEngine = FfmpegMediaEngine(),
            )
            try {
                when (request.action) {
                    ACTION_CONCAT -> {
                        val uris = request.getStringArrayListExtra(EXTRA_INPUT_URIS).orEmpty()
                        val names = request.getStringArrayListExtra(EXTRA_INPUT_NAMES).orEmpty()
                        require(uris.size >= 2 && uris.size == names.size) { "結合する動画情報が不足しています" }
                        val inputs = uris.indices.map { index ->
                            PickedVideo(uri = uris[index], displayName = names[index])
                        }
                        val outputUri = requireNotNull(request.getStringExtra(EXTRA_OUTPUT_URI))
                        val outputName = requireNotNull(request.getStringExtra(EXTRA_OUTPUT_NAME))
                        pipeline.concatDirect(
                            inputs = inputs,
                            outputUri = outputUri,
                            outputName = outputName,
                        ) { message -> updateProgress(title, message) }
                    }
                    ACTION_CUT -> {
                        val localInput = requireNotNull(request.getStringExtra(EXTRA_LOCAL_INPUT))
                        val outputUri = requireNotNull(request.getStringExtra(EXTRA_OUTPUT_URI))
                        val outputName = requireNotNull(request.getStringExtra(EXTRA_OUTPUT_NAME))
                        val startMs = request.getLongExtra(EXTRA_START_MS, -1L)
                        val endMs = request.getLongExtra(EXTRA_END_MS, -1L)
                        require(startMs >= 0L && endMs > startMs) { "カット範囲が不正です" }
                        pipeline.cutPreparedDirect(
                            localInputPath = localInput,
                            outputUri = outputUri,
                            outputName = outputName,
                            startMs = startMs,
                            endMs = endMs,
                        ) { message -> updateProgress(title, message) }
                    }
                }
                finishSuccess("処理が完了しました")
            } catch (error: Throwable) {
                val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                finishFailure("処理に失敗しました: $detail")
            } finally {
                releaseWakeLock()
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        processingJob?.cancel()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateProgress(title: String, message: String) {
        ProcessingStateStore.running(title, message)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(title, message, true))
    }

    private fun finishSuccess(message: String) {
        ProcessingStateStore.success(message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification("ClipForge", message, false))
    }

    private fun finishFailure(message: String) {
        ProcessingStateStore.failure(message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification("ClipForge", message, false))
    }

    private fun buildNotification(title: String, message: String, ongoing: Boolean): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(pending)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(ongoing)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setProgress(0, 0, ongoing)
            .setAutoCancel(!ongoing)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "動画処理",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "ClipForgeの結合・カット処理の進捗"
            },
        )
    }

    private fun acquireWakeLock() {
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClipForge:video-processing")
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

    companion object {
        private const val CHANNEL_ID = "clipforge_processing"
        private const val NOTIFICATION_ID = 4101
        private const val ACTION_CONCAT = "app.clipforge.action.CONCAT"
        private const val ACTION_CUT = "app.clipforge.action.CUT"
        private const val EXTRA_INPUT_URIS = "inputUris"
        private const val EXTRA_INPUT_NAMES = "inputNames"
        private const val EXTRA_LOCAL_INPUT = "localInput"
        private const val EXTRA_OUTPUT_URI = "outputUri"
        private const val EXTRA_OUTPUT_NAME = "outputName"
        private const val EXTRA_START_MS = "startMs"
        private const val EXTRA_END_MS = "endMs"

        fun startConcat(
            context: Context,
            inputs: List<PickedVideo>,
            outputUri: String,
            outputName: String,
        ) {
            val intent = Intent(context, ClipForgeProcessingService::class.java)
                .setAction(ACTION_CONCAT)
                .putStringArrayListExtra(EXTRA_INPUT_URIS, ArrayList(inputs.map { it.uri }))
                .putStringArrayListExtra(EXTRA_INPUT_NAMES, ArrayList(inputs.map { it.displayName }))
                .putExtra(EXTRA_OUTPUT_URI, outputUri)
                .putExtra(EXTRA_OUTPUT_NAME, outputName)
            context.startForegroundService(intent)
        }

        fun startCut(
            context: Context,
            localInputPath: String,
            outputUri: String,
            outputName: String,
            startMs: Long,
            endMs: Long,
        ) {
            val intent = Intent(context, ClipForgeProcessingService::class.java)
                .setAction(ACTION_CUT)
                .putExtra(EXTRA_LOCAL_INPUT, localInputPath)
                .putExtra(EXTRA_OUTPUT_URI, outputUri)
                .putExtra(EXTRA_OUTPUT_NAME, outputName)
                .putExtra(EXTRA_START_MS, startMs)
                .putExtra(EXTRA_END_MS, endMs)
            context.startForegroundService(intent)
        }
    }
}
