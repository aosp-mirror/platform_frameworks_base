package com.android.systemui.statusbar

import android.app.Notification
import android.os.RemoteException
import com.android.internal.statusbar.IStatusBarService
import com.android.internal.statusbar.NotificationVisibility
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.util.Assert
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Class to shim calls to IStatusBarManager#onNotificationClick/#onNotificationActionClick that
 * allow an in-process notification to go out (e.g., for tracking interactions) as well as
 * sending the messages along to system server.
 *
 * NOTE: this class eats exceptions from system server, as no current client of these APIs cares
 * about errors
 */
@Singleton
public class NotificationClickNotifier @Inject constructor(
    val barService: IStatusBarService,
    @Main val mainExecutor: Executor
) {
    val listeners = mutableListOf<NotificationInteractionListener>()

    fun addNotificationInteractionListener(listener: NotificationInteractionListener) {
        Assert.isMainThread()
        listeners.add(listener)
    }

    fun removeNotificationInteractionListener(listener: NotificationInteractionListener) {
        Assert.isMainThread()
        listeners.remove(listener)
    }

    private fun notifyListenersAboutInteraction(key: String) {
        for (l in listeners) {
            l.onNotificationInteraction(key)
        }
    }

    fun onNotificationActionClick(
        key: String,
        actionIndex: Int,
        action: Notification.Action,
        visibility: NotificationVisibility,
        generatedByAssistant: Boolean
    ) {
        try {
            barService.onNotificationActionClick(
                    key, actionIndex, action, visibility, generatedByAssistant)
        } catch (e: RemoteException) {
            // nothing
        }

        mainExecutor.execute {
            notifyListenersAboutInteraction(key)
        }
    }

    fun onNotificationClick(
        key: String,
        visibility: NotificationVisibility
    ) {
        try {
            barService.onNotificationClick(key, visibility)
        } catch (e: RemoteException) {
            // nothing
        }

        mainExecutor.execute {
            notifyListenersAboutInteraction(key)
        }
    }
}

/**
 * Interface for listeners to get notified when a notification is interacted with via a click or
 * interaction with remote input or actions
 */
interface NotificationInteractionListener {
    fun onNotificationInteraction(key: String)
}

private const val TAG = "NotificationClickNotifier"
