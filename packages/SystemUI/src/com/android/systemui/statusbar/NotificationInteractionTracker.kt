package com.android.systemui.statusbar

import com.android.internal.statusbar.NotificationVisibility
import com.android.systemui.statusbar.notification.NotificationEntryManager
import com.android.systemui.statusbar.notification.NotificationEntryListener
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Class to track user interaction with notifications. It's a glorified map of key : bool that can
 * merge multiple "user interacted with notification" signals into a single place.
 */
@Singleton
class NotificationInteractionTracker @Inject constructor(
    private val clicker: NotificationClickNotifier,
    private val entryManager: NotificationEntryManager
) : NotificationEntryListener, NotificationInteractionListener {
    private val interactions = mutableMapOf<String, Boolean>()

    init {
        clicker.addNotificationInteractionListener(this)
        entryManager.addNotificationEntryListener(this)
    }

    fun hasUserInteractedWith(key: String): Boolean = key in interactions

    override fun onNotificationAdded(entry: NotificationEntry) {
        interactions[entry.key] = false
    }

    override fun onEntryRemoved(
        entry: NotificationEntry,
        visibility: NotificationVisibility?,
        removedByUser: Boolean
    ) {
        interactions.remove(entry.key)
    }

    override fun onNotificationInteraction(key: String) {
        interactions[key] = true
    }
}

private const val TAG = "NotificationInteractionTracker"
