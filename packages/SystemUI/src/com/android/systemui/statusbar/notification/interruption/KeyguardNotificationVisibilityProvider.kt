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
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.provider.HighPriorityProvider
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.ListenerSet
import com.android.systemui.util.settings.GlobalSettings
import com.android.systemui.util.settings.SecureSettings
import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import java.util.function.Consumer
import javax.inject.Inject

/** Determines if notifications should be visible based on the state of the keyguard. */
interface KeyguardNotificationVisibilityProvider {
    /**
     * Determines if the given notification should be hidden based on the current keyguard state.
     * If a [Consumer] registered via [addOnStateChangedListener] is invoked, the results of this
     * method may no longer be valid and should be re-queried.
     */
    fun shouldHideNotification(entry: NotificationEntry): Boolean

    /** Registers a listener to be notified when the internal keyguard state has been updated. */
    fun addOnStateChangedListener(listener: Consumer<String>)

    /** Unregisters a listener previously registered with [addOnStateChangedListener]. */
    fun removeOnStateChangedListener(listener: Consumer<String>)
}

/** Provides a [KeyguardNotificationVisibilityProvider] in [SysUISingleton] scope. */
@Module(includes = [KeyguardNotificationVisibilityProviderImplModule::class])
object KeyguardNotificationVisibilityProviderModule

@Module
private interface KeyguardNotificationVisibilityProviderImplModule {
    @Binds
    fun bindImpl(impl: KeyguardNotificationVisibilityProviderImpl):
            KeyguardNotificationVisibilityProvider

    @Binds
    @IntoMap
    @ClassKey(KeyguardNotificationVisibilityProvider::class)
    fun bindStartable(impl: KeyguardNotificationVisibilityProviderImpl): CoreStartable
}

@SysUISingleton
private class KeyguardNotificationVisibilityProviderImpl @Inject constructor(
    context: Context,
    @Main private val handler: Handler,
    private val keyguardStateController: KeyguardStateController,
    private val lockscreenUserManager: NotificationLockscreenUserManager,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val highPriorityProvider: HighPriorityProvider,
    private val statusBarStateController: StatusBarStateController,
    private val broadcastDispatcher: BroadcastDispatcher,
    private val secureSettings: SecureSettings,
    private val globalSettings: GlobalSettings
) : CoreStartable(context), KeyguardNotificationVisibilityProvider {
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
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                if (keyguardStateController.isShowing) {
                    notifyStateChanged("Settings $uri changed")
                }
            }
        }

        secureSettings.registerContentObserverForUser(
                Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS,
                settingsObserver,
                UserHandle.USER_ALL)

        secureSettings.registerContentObserverForUser(
                Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS,
                true,
                settingsObserver,
                UserHandle.USER_ALL)

        globalSettings.registerContentObserver(Settings.Global.ZEN_MODE, settingsObserver)

        secureSettings.registerContentObserverForUser(
                Settings.Secure.LOCK_SCREEN_SHOW_SILENT_NOTIFICATIONS,
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
                if (keyguardStateController.isShowing) {
                    // maybe public mode changed
                    notifyStateChanged(intent.action!!)
                }
            }
        }, IntentFilter(Intent.ACTION_USER_SWITCHED))
    }

    override fun addOnStateChangedListener(listener: Consumer<String>) {
        onStateChangedListeners.addIfAbsent(listener)
    }

    override fun removeOnStateChangedListener(listener: Consumer<String>) {
        onStateChangedListeners.remove(listener)
    }

    private fun notifyStateChanged(reason: String) {
        onStateChangedListeners.forEach { it.accept(reason) }
    }

    override fun shouldHideNotification(entry: NotificationEntry): Boolean {
        val sbn = entry.sbn
        // FILTER OUT the notification when the keyguard is showing and...
        if (keyguardStateController.isShowing) {
            // ... user settings or the device policy manager doesn't allow lockscreen
            // notifications;
            if (!lockscreenUserManager.shouldShowLockscreenNotifications()) {
                return true
            }
            val currUserId: Int = lockscreenUserManager.currentUserId
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
        hideSilentNotificationsOnLockscreen =
                secureSettings.getBool(Settings.Secure.LOCK_SCREEN_SHOW_SILENT_NOTIFICATIONS, true)
    }
}