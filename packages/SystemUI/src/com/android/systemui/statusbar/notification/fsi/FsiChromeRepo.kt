package com.android.systemui.statusbar.notification.fsi

import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.RemoteException
import android.service.dreams.IDreamManager
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.provider.LaunchFullScreenIntentProvider
import com.android.systemui.statusbar.notification.fsi.FsiDebug.Companion.log
import com.android.systemui.statusbar.phone.CentralSurfaces
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Class that bridges the gap between clean app architecture and existing code. Provides new
 * implementation of StatusBarNotificationActivityStarter launchFullscreenIntent that pipes
 * one-directional data => FsiChromeViewModel => FsiChromeView.
 */
@SysUISingleton
class FsiChromeRepo
@Inject
constructor(
    private val context: Context,
    private val pm: PackageManager,
    private val keyguardRepo: KeyguardRepository,
    private val launchFullScreenIntentProvider: LaunchFullScreenIntentProvider,
    private val featureFlags: FeatureFlags,
    private val uiBgExecutor: Executor,
    private val dreamManager: IDreamManager,
    private val centralSurfaces: CentralSurfaces
) : CoreStartable {

    companion object {
        private const val classTag = "FsiChromeRepo"
    }

    data class FSIInfo(
        val appName: String,
        val appIcon: Drawable,
        val fullscreenIntent: PendingIntent
    )

    private val _infoFlow = MutableStateFlow<FSIInfo?>(null)
    val infoFlow: StateFlow<FSIInfo?> = _infoFlow

    override fun start() {
        log("$classTag start listening for FSI notifications")

        // Listen for FSI launch events for the lifetime of SystemUI.
        launchFullScreenIntentProvider.registerListener { entry -> launchFullscreenIntent(entry) }
    }

    fun dismiss() {
        _infoFlow.value = null
    }

    fun onFullscreen() {
        // TODO(b/243421660) implement transition from container to fullscreen
    }

    fun stopScreenSaver() {
        uiBgExecutor.execute {
            try {
                dreamManager.awaken()
            } catch (e: RemoteException) {
                e.printStackTrace()
            }
        }
    }

    fun launchFullscreenIntent(entry: NotificationEntry) {
        if (!featureFlags.isEnabled(Flags.FSI_CHROME)) {
            return
        }
        if (!keyguardRepo.isKeyguardShowing()) {
            return
        }
        stopScreenSaver()

        var appName = pm.getApplicationLabel(context.applicationInfo) as String
        val appIcon = pm.getApplicationIcon(context.packageName)
        val fullscreenIntent = entry.sbn.notification.fullScreenIntent

        log("FsiChromeRepo launchFullscreenIntent appName=$appName appIcon $appIcon")
        _infoFlow.value = FSIInfo(appName, appIcon, fullscreenIntent)

        // If screen is off or we're showing AOD, show lockscreen.
        centralSurfaces.wakeUpForFullScreenIntent()

        // Don't show HUN since we're already showing FSI.
        entry.notifyFullScreenIntentLaunched()
    }
}
