package app.clipforge.processing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import app.clipforge.MainActivity
import app.clipforge.media.FfmpegMediaEngine
import app.clipforge.media.MediaSegment
import app.clipforge.workflow.ExternalEditPipeline
import app.clipforge.workflow.MultiCutExporter
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
            ACTION_PREPARE_CUT -> "カット編集を準備中"
            ACTION_CONCAT -> "動画を結合中"
            ACTION_CUT -> "編集結果を保存中"
            else -> return START_NOT_STICKY
        }
        updateProgress(title, "処理を準備しています")
        startForeground(NOTIFICATION_ID, buildNotification(title, "処理を準備しています", true, null))
        acquireWakeLock()

        processingJob = serviceScope.launch {
            val mediaEngine = FfmpegMediaEngine()
            val pipeline = ExternalEditPipeline(
                cacheRoot = cacheDir,
                context = applicationContext,
                mediaEngine = mediaEngine,
            )
            val multiCutExporter = MultiCutExporter(
                cacheRoot = cacheDir,
                context = applicationContext,
                mediaEngine = mediaEngine,
            )
            try {
                when (request.action) {
                    ACTION_PREPARE_CUT -> prepareCut(request, title, pipeline)
                    ACTION_CONCAT -> concat(request, title, pipeline)
                    ACTION_CUT -> cut(request, title, multiCutExporter)
                }
            } catch (error: Throwable) {
                val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                finishFailure("処理に失敗しました: $detail")
            } finally {
                releaseOutputGrant(request.getStringExtra(EXTRA_OUTPUT_URI))
                releaseWakeLock()
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun prepareCut(
        request: Intent,
        title: String,
        pipeline: ExternalEditPipeline,
    ) {
        val source = sourceFrom(request)
        val prepared = pipeline.prepareCut(source) { message, percent ->
            updateProgress(title, message, percent)
        }
        ProcessingStateStore.cutPrepared(
            sourceUri = prepared.source.uri,
            sourceName = prepared.source.displayName,
            sessionPath = prepared.sessionDir.absolutePath,
            localPath = prepared.localFile?.absolutePath,
            durationMs = prepared.durationMs,
            thumbnailPaths = prepared.thumbnailPaths,
        )
        finishPrepared("カット編集の準備が完了しました")
    }

    private suspend fun concat(
        request: Intent,
        title: String,
        pipeline: ExternalEditPipeline,
    ) {
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
        finishSuccess("処理が完了しました")
    }

    private suspend fun cut(
        request: Intent,
        title: String,
        exporter: MultiCutExporter,
    ) {
        val source = sourceFrom(request)
        val sessionPath = requireNotNull(request.getStringExtra(EXTRA_SESSION_PATH))
        val localInput = request.getStringExtra(EXTRA_LOCAL_INPUT)
        val outputUri = requireNotNull(request.getStringExtra(EXTRA_OUTPUT_URI))
        val outputName = requireNotNull(request.getStringExtra(EXTRA_OUTPUT_NAME))
        val durationMs = request.getLongExtra(EXTRA_DURATION_MS, -1L)
        val starts = request.getLongArrayExtra(EXTRA_CUT_STARTS).orEmpty()
        val ends = request.getLongArrayExtra(EXTRA_CUT_ENDS).orEmpty()
        require(durationMs > 0L) { "動画の長さが不正です" }
        require(starts.isNotEmpty() && starts.size == ends.size) { "削除する範囲がありません" }
        val cutRanges = starts.indices.map { index -> MediaSegment(starts[index], ends[index]) }

        exporter.export(
            source = source,
            localInputPath = localInput,
            sessionPath = sessionPath,
            durationMs = durationMs,
            cutRanges = cutRanges,
            outputUri = outputUri,
            outputName = outputName,
        ) { message -> updateProgress(title, message) }
        finishSuccess("編集結果を保存しました")
    }

    private fun sourceFrom(request: Intent): PickedVideo {
        val sourceUri = requireNotNull(request.getStringExtra(EXTRA_INPUT_URI))
        val sourceName = requireNotNull(request.getStringExtra(EXTRA_INPUT_NAME))
        val sourceSize = request.getLongExtra(EXTRA_INPUT_SIZE_BYTES, -1L).takeIf { it >= 0L }
        return PickedVideo(
            uri = sourceUri,
            displayName = sourceName,
            sizeBytes = sourceSize,
        )
    }

    override fun onDestroy() {
        processingJob?.cancel()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateProgress(title: String, message: String, progressPercent: Int? = null) {
        ProcessingStateStore.running(title, message, progressPercent)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(title, message, true, progressPercent))
    }

    private fun finishPrepared(message: String) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification("ClipForge", message, false, 100))
    }

    private fun finishSuccess(message: String) {
        ProcessingStateStore.success(message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification("ClipForge", message, false, 100))
    }

    private fun finishFailure(message: String) {
        ProcessingStateStore.failure(message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification("ClipForge", message, false, null))
    }

    private fun buildNotification(
        title: String,
        message: String,
        ongoing: Boolean,
        progressPercent: Int?,
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
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(pending)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(ongoing)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setAutoCancel(!ongoing)

        if (progressPercent != null) {
            builder.setProgress(100, progressPercent.coerceIn(0, 100), false)
        } else {
            builder.setProgress(0, 0, ongoing)
        }
        return builder.build()
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

    private fun releaseOutputGrant(uriString: String?) {
        val uri = uriString?.let(Uri::parse) ?: return
        runCatching {
            contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
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
        private const val ACTION_PREPARE_CUT = "app.clipforge.action.PREPARE_CUT"
        private const val ACTION_CONCAT = "app.clipforge.action.CONCAT"
        private const val ACTION_CUT = "app.clipforge.action.CUT"
        private const val EXTRA_INPUT_URI = "inputUri"
        private const val EXTRA_INPUT_NAME = "inputName"
        private const val EXTRA_INPUT_SIZE_BYTES = "inputSizeBytes"
        private const val EXTRA_INPUT_URIS = "inputUris"
        private const val EXTRA_INPUT_NAMES = "inputNames"
        private const val EXTRA_SESSION_PATH = "sessionPath"
        private const val EXTRA_LOCAL_INPUT = "localInput"
        private const val EXTRA_OUTPUT_URI = "outputUri"
        private const val EXTRA_OUTPUT_NAME = "outputName"
        private const val EXTRA_DURATION_MS = "durationMs"
        private const val EXTRA_CUT_STARTS = "cutStarts"
        private const val EXTRA_CUT_ENDS = "cutEnds"

        fun startPrepareCut(context: Context, source: PickedVideo) {
            val intent = Intent(context, ClipForgeProcessingService::class.java)
                .setAction(ACTION_PREPARE_CUT)
                .putSource(source)
            context.startForegroundService(intent)
        }

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
            source: PickedVideo,
            sessionPath: String,
            localInputPath: String?,
            outputUri: String,
            outputName: String,
            durationMs: Long,
            cutRanges: List<MediaSegment>,
        ) {
            require(cutRanges.isNotEmpty()) { "削除する範囲がありません" }
            val intent = Intent(context, ClipForgeProcessingService::class.java)
                .setAction(ACTION_CUT)
                .putSource(source)
                .putExtra(EXTRA_SESSION_PATH, sessionPath)
                .putExtra(EXTRA_OUTPUT_URI, outputUri)
                .putExtra(EXTRA_OUTPUT_NAME, outputName)
                .putExtra(EXTRA_DURATION_MS, durationMs)
                .putExtra(EXTRA_CUT_STARTS, cutRanges.map(MediaSegment::startMs).toLongArray())
                .putExtra(EXTRA_CUT_ENDS, cutRanges.map(MediaSegment::endMs).toLongArray())
            localInputPath?.let { intent.putExtra(EXTRA_LOCAL_INPUT, it) }
            context.startForegroundService(intent)
        }

        private fun Intent.putSource(source: PickedVideo): Intent =
            putExtra(EXTRA_INPUT_URI, source.uri)
                .putExtra(EXTRA_INPUT_NAME, source.displayName)
                .putExtra(EXTRA_INPUT_SIZE_BYTES, source.sizeBytes ?: -1L)
    }
}
