package com.android.systemui.statusbar.notification.interruption

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.CoreStartable
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.provider.HighPriorityProvider
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.ListenerSet
import java.util.function.Consumer
import javax.inject.Inject

/**
 * Determines if notifications should be visible based on the state of the keyguard
 */
class KeyguardNotificationVisibilityProvider @Inject constructor(
    context: Context,
    @Main private val handler: Handler,
    private val keyguardStateController: KeyguardStateController,
    private val lockscreenUserManager: NotificationLockscreenUserManager,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val highPriorityProvider: HighPriorityProvider,
    private val statusBarStateController: StatusBarStateController,
    private val broadcastDispatcher: BroadcastDispatcher
) : CoreStartable(context) {
    private val onStateChangedListeners = ListenerSet<Consumer<String>>()
    private var hideSilentNotificationsOnLockscreen: Boolean = false

    override fun start() {
        readShowSilentNotificationSetting()
        keyguardStateController.addCallback(object : KeyguardStateController.Callback {
            override fun onUnlockedChanged() {
                notifyStateChanged("onUnlockedChanged")
            }

            override fun onKeyguardShowingChanged() {
                notifyStateChanged("onKeyguardShowingChanged")
            }
        })
        keyguardUpdateMonitor.registerCallback(object : KeyguardUpdateMonitorCallback() {
            override fun onStrongAuthStateChanged(userId: Int) {
                notifyStateChanged("onStrongAuthStateChanged")
            }
        })

        // register lockscreen settings changed callbacks:
        val settingsObserver: ContentObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri) {
                if (keyguardStateController.isShowing) {
                    notifyStateChanged("Settings $uri changed")
                }
            }
        }

        mContext.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS),
                false,
                settingsObserver,
                UserHandle.USER_ALL)

        mContext.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS),
                true,
                settingsObserver,
                UserHandle.USER_ALL)

        mContext.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ZEN_MODE),
                false,
                settingsObserver)

        mContext.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.LOCK_SCREEN_SHOW_SILENT_NOTIFICATIONS),
                false,
                settingsObserver,
                UserHandle.USER_ALL)

        // register (maybe) public mode changed callbacks:
        statusBarStateController.addCallback(object : StatusBarStateController.StateListener {
            override fun onStateChanged(state: Int) {
                notifyStateChanged("onStatusBarStateChanged")
            }
        })
        broadcastDispatcher.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (keyguardStateController.isShowing()) {
                    // maybe public mode changed
                    notifyStateChanged(intent.action)
                }
            }
        }, IntentFilter(Intent.ACTION_USER_SWITCHED))
    }

    fun addOnStateChangedListener(listener: Consumer<String>) {
        onStateChangedListeners.addIfAbsent(listener)
    }

    fun removeOnStateChangedListener(listener: Consumer<String>) {
        onStateChangedListeners.remove(listener)
    }

    private fun notifyStateChanged(reason: String) {
        onStateChangedListeners.forEach({ it.accept(reason) })
    }

    /**
     * Determines if the given notification should be hidden based on the current keyguard state.
     * If Listener#onKeyguardStateChanged is invoked, the results of this method may no longer
     * be valid, and so should be re-queried
     */
    fun hideNotification(entry: NotificationEntry): Boolean {
        val sbn = entry.sbn
        // FILTER OUT the notification when the keyguard is showing and...
        if (keyguardStateController.isShowing()) {
            // ... user settings or the device policy manager doesn't allow lockscreen
            // notifications;
            if (!lockscreenUserManager.shouldShowLockscreenNotifications()) {
                return true
            }
            val currUserId: Int = lockscreenUserManager.getCurrentUserId()
            val notifUserId =
                    if (sbn.user.identifier == UserHandle.USER_ALL) currUserId
                    else sbn.user.identifier

            // ... user is in lockdown
            if (keyguardUpdateMonitor.isUserInLockdown(currUserId) ||
                    keyguardUpdateMonitor.isUserInLockdown(notifUserId)) {
                return true
            }

            // ... device is in public mode and the user's settings doesn't allow
            // notifications to show in public mode
            if (lockscreenUserManager.isLockscreenPublicMode(currUserId) ||
                    lockscreenUserManager.isLockscreenPublicMode(notifUserId)) {
                if (entry.ranking.lockscreenVisibilityOverride == Notification.VISIBILITY_SECRET) {
                    return true
                }
                if (!lockscreenUserManager.userAllowsNotificationsInPublic(currUserId) ||
                        !lockscreenUserManager.userAllowsNotificationsInPublic(
                                notifUserId)) {
                    return true
                }
            }

            // ... neither this notification nor its group have high enough priority
            // to be shown on the lockscreen
            if (entry.parent != null) {
                val parent = entry.parent
                if (priorityExceedsLockscreenShowingThreshold(parent)) {
                    return false
                }
            }
            return !priorityExceedsLockscreenShowingThreshold(entry)
        }
        return false
    }

    private fun priorityExceedsLockscreenShowingThreshold(entry: ListEntry?): Boolean =
        when {
            entry == null -> false
            hideSilentNotificationsOnLockscreen -> highPriorityProvider.isHighPriority(entry)
            else -> entry.representativeEntry?.ranking?.isAmbient == false
        }

    private fun readShowSilentNotificationSetting() {
        hideSilentNotificationsOnLockscreen = Settings.Secure.getInt(
                mContext.getContentResolver(),
                Settings.Secure.LOCK_SCREEN_SHOW_SILENT_NOTIFICATIONS,
                1) == 0
    }
}