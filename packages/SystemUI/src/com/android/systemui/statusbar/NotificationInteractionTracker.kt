package com.android.systemui.statusbar

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.notification.NotificationEntryManager
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import javax.inject.Inject

/**
 * Class to track user interaction with notifications. It's a glorified map of key : bool that can
 * merge multiple "user interacted with notification" signals into a single place.
 */
@SysUISingleton
class NotificationInteractionTracker @Inject constructor(
    private val clicker: NotificationClickNotifier,
    private val entryManager: NotificationEntryManager
) : NotifCollectionListener, NotificationInteractionListener {
    private val interactions = mutableMapOf<String, Boolean>()

    init {
        clicker.addNotificationInteractionListener(this)
        entryManager.addCollectionListener(this)
    }

    fun hasUserInteractedWith(key: String): Boolean {
        return interactions[key] ?: false
    }

    override fun onEntryAdded(entry: NotificationEntry) {
        interactions[entry.key] = false
    }

    override fun onEntryCleanUp(entry: NotificationEntry) {
        interactions.remove(entry.key)
    }

    override fun onNotificationInteraction(key: String) {
        interactions[key] = true
    }
}

private const val TAG = "NotificationInteractionTracker"
