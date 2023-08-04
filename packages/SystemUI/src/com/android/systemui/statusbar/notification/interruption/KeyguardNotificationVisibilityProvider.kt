package com.android.systemui.statusbar.notification.interruption

import android.app.Notification
import android.app.Notification.VISIBILITY_SECRET
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerExecutor
import android.os.UserHandle
import android.provider.Settings
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.provider.HighPriorityProvider
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.ListenerSet
import com.android.systemui.util.asIndenting
import com.android.systemui.util.settings.GlobalSettings
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.withIncreasedIndent
import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import java.io.PrintWriter
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
    @Main private val handler: Handler,
    private val keyguardStateController: KeyguardStateController,
    private val lockscreenUserManager: NotificationLockscreenUserManager,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val highPriorityProvider: HighPriorityProvider,
    private val statusBarStateController: SysuiStatusBarStateController,
    private val userTracker: UserTracker,
    private val secureSettings: SecureSettings,
    private val globalSettings: GlobalSettings
) : CoreStartable, KeyguardNotificationVisibilityProvider {
    private val showSilentNotifsUri =
            secureSettings.getUriFor(Settings.Secure.LOCK_SCREEN_SHOW_SILENT_NOTIFICATIONS)
    private val onStateChangedListeners = ListenerSet<Consumer<String>>()
    private var hideSilentNotificationsOnLockscreen: Boolean = false

    private val userTrackerCallback = object : UserTracker.Callback {
        override fun onUserChanged(newUser: Int, userContext: Context) {
            readShowSilentNotificationSetting()
            if (isLockedOrLocking) {
                // maybe public mode changed
                notifyStateChanged("onUserSwitched")
            }
        }
    }

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
                if (uri == showSilentNotifsUri) {
                    readShowSilentNotificationSetting()
                }
                if (isLockedOrLocking) {
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
            override fun onStateChanged(newState: Int) {
                notifyStateChanged("onStatusBarStateChanged")
            }
            override fun onUpcomingStateChanged(state: Int) {
                notifyStateChanged("onStatusBarUpcomingStateChanged")
            }
        })
        userTracker.addCallback(userTrackerCallback, HandlerExecutor(handler))
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

    override fun shouldHideNotification(entry: NotificationEntry): Boolean = when {
        // Keyguard state doesn't matter if the keyguard is not showing.
        !isLockedOrLocking -> false
        // Notifications not allowed on the lockscreen, always hide.
        !lockscreenUserManager.shouldShowLockscreenNotifications() -> true
        // User settings do not allow this notification on the lockscreen, so hide it.
        userSettingsDisallowNotification(entry) -> true
        // Entry is explicitly marked SECRET, so hide it.
        entry.sbn.notification.visibility == VISIBILITY_SECRET -> true
        // if entry is silent, apply custom logic to see if should hide
        shouldHideIfEntrySilent(entry) -> true
        else -> false
    }

    private fun shouldHideIfEntrySilent(entry: ListEntry): Boolean = when {
        // Show if high priority (not hidden)
        highPriorityProvider.isHighPriority(entry) -> false
        // Ambient notifications are hidden always from lock screen
        entry.representativeEntry?.isAmbient == true -> true
        // [Now notification is silent]
        // Hide regardless of parent priority if user wants silent notifs hidden
        hideSilentNotificationsOnLockscreen -> true
        // Parent priority is high enough to be shown on the lockscreen, do not hide.
        entry.parent?.let(::shouldHideIfEntrySilent) == false -> false
        // Show when silent notifications are allowed on lockscreen
        else -> false
    }

    private fun userSettingsDisallowNotification(entry: NotificationEntry): Boolean {
        fun disallowForUser(user: Int) = when {
            // user is in lockdown, always disallow
            keyguardUpdateMonitor.isUserInLockdown(user) -> true
            // device isn't public, no need to check public-related settings, so allow
            !lockscreenUserManager.isLockscreenPublicMode(user) -> false
            // entry is meant to be secret on the lockscreen, disallow
            entry.ranking.lockscreenVisibilityOverride == Notification.VISIBILITY_SECRET -> true
            // disallow if user disallows notifications in public
            else -> !lockscreenUserManager.userAllowsNotificationsInPublic(user)
        }
        val currentUser = lockscreenUserManager.currentUserId
        val notifUser = entry.sbn.user.identifier
        return when {
            disallowForUser(currentUser) -> true
            notifUser == UserHandle.USER_ALL -> false
            notifUser == currentUser -> false
            else -> disallowForUser(notifUser)
        }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) = pw.asIndenting().run {
        println("isLockedOrLocking=$isLockedOrLocking")
        withIncreasedIndent {
            println("keyguardStateController.isShowing=${keyguardStateController.isShowing}")
            println("statusBarStateController.currentOrUpcomingState=" +
                    "${statusBarStateController.currentOrUpcomingState}")
        }
        println("hideSilentNotificationsOnLockscreen=$hideSilentNotificationsOnLockscreen")
    }

    private val isLockedOrLocking get() =
        keyguardStateController.isShowing ||
                statusBarStateController.currentOrUpcomingState == StatusBarState.KEYGUARD

    private fun readShowSilentNotificationSetting() {
        val showSilentNotifs =
                secureSettings.getBoolForUser(Settings.Secure.LOCK_SCREEN_SHOW_SILENT_NOTIFICATIONS,
                        false, UserHandle.USER_CURRENT)
        hideSilentNotificationsOnLockscreen = !showSilentNotifs
    }
}
