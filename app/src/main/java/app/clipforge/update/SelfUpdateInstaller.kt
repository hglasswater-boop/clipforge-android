package app.clipforge.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import java.io.File

object SelfUpdateInstaller {
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0L, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }

            val callbackIntent = Intent(context, SelfUpdateInstallReceiver::class.java)
            val callback = PendingIntent.getBroadcast(
                context,
                sessionId,
                callbackIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            session.commit(callback.intentSender)
        } catch (error: Throwable) {
            runCatching { session.abandon() }
            throw error
        } finally {
            session.close()
        }
    }
}

class SelfUpdateInstallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, statusIntent: Intent) {
        when (
            statusIntent.getIntExtra(
                PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE
            )
        ) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    statusIntent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    statusIntent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirmation != null) {
                    confirmation.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
                    context.startActivity(confirmation)
                } else {
                    showFailure(context, "インストール確認画面を開けませんでした")
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    context.startActivity(launchIntent)
                }
            }

            else -> {
                val message = statusIntent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?: "ClipForgeの更新に失敗しました"
                showFailure(context, message)
            }
        }
    }

    private fun showFailure(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}
