package com.android.systemui.statusbar.policy

import android.graphics.Region
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import dagger.Binds
import dagger.Module
import java.io.PrintWriter
import java.util.stream.Stream
import javax.inject.Inject

/**
 * A manager which handles heads up notifications which is a special mode where they simply peek
 * from the top of the screen.
 */
interface HeadsUpManager : Dumpable {
    /** The stream of all current notifications managed by this manager. */
    val allEntries: Stream<NotificationEntry>

    /** Add a listener to receive callbacks onHeadsUpGoingAway. */
    fun addHeadsUpPhoneListener(listener: OnHeadsUpPhoneListenerChange)

    /** Adds an OnHeadUpChangedListener to observe events. */
    fun addListener(listener: OnHeadsUpChangedListener)

    fun addSwipedOutNotification(key: String)

    /**
     * Whether or not the alert can be removed currently. If it hasn't been on screen long enough it
     * should not be removed unless forced
     *
     * @param key the key to check if removable
     * @return true if the alert entry can be removed
     */
    fun canRemoveImmediately(key: String): Boolean

    /**
     * Compare two entries and decide how they should be ranked.
     *
     * @return -1 if the first argument should be ranked higher than the second, 1 if the second one
     *   should be ranked higher and 0 if they are equal.
     */
    fun compare(a: NotificationEntry?, b: NotificationEntry?): Int

    /**
     * Extends the lifetime of the currently showing pulsing notification so that the pulse lasts
     * longer.
     */
    fun extendHeadsUp()

    /** Returns when a HUN entry should be removed in milliseconds from now. */
    fun getEarliestRemovalTime(key: String?): Long

    /** Returns the top Heads Up Notification, which appears to show at first. */
    fun getTopEntry(): NotificationEntry?

    /**
     * Gets the touchable region needed for heads up notifications. Returns null if no touchable
     * region is required (ie: no heads up notification currently exists).
     */
    fun getTouchableRegion(): Region?

    /**
     * Whether or not there are any entries managed by HeadsUpManager.
     *
     * @return true if there is a heads up entry, false otherwise
     */
    fun hasNotifications(): Boolean = false

    /** Returns whether there are any pinned Heads Up Notifications or not. */
    fun hasPinnedHeadsUp(): Boolean

    /** Returns whether or not the given notification is managed by this manager. */
    fun isHeadsUpEntry(key: String): Boolean

    /** @see setHeadsUpAnimatingAway */
    fun isHeadsUpAnimatingAwayValue(): Boolean

    /** Returns if the given notification is snoozed or not. */
    fun isSnoozed(packageName: String): Boolean

    /** Returns whether the entry is (pinned and expanded) or (has an active remote input). */
    fun isSticky(key: String?): Boolean

    fun isTrackingHeadsUp(): Boolean

    fun onExpandingFinished()

    /** Removes the OnHeadUpChangedListener from the observer list. */
    fun removeListener(listener: OnHeadsUpChangedListener)

    /**
     * Try to remove the notification. May not succeed if the notification has not been shown long
     * enough and needs to be kept around.
     *
     * @param key the key of the notification to remove
     * @param releaseImmediately force a remove regardless of earliest removal time
     * @param reason reason for removing the notification
     * @return true if notification is removed, false otherwise
     */
    fun removeNotification(key: String, releaseImmediately: Boolean, reason: String): Boolean

    /**
     * Try to remove the notification. May not succeed if the notification has not been shown long
     * enough and needs to be kept around.
     *
     * @param key the key of the notification to remove
     * @param releaseImmediately force a remove regardless of earliest removal time
     * @param animate if true, animate the removal
     * @param reason reason for removing the notification
     * @return true if notification is removed, false otherwise
     */
    fun removeNotification(
        key: String,
        releaseImmediately: Boolean,
        animate: Boolean,
        reason: String
    ): Boolean

    /** Clears all managed notifications. */
    fun releaseAllImmediately()

    fun setAnimationStateHandler(handler: AnimationStateHandler)

    /**
     * Set an entry to be expanded and therefore stick in the heads up area if it's pinned until
     * it's collapsed again.
     */
    fun setExpanded(entry: NotificationEntry, expanded: Boolean)

    /**
     * Sets whether an entry's guts are exposed and therefore it should stick in the heads up area
     * if it's pinned until it's hidden again.
     */
    fun setGutsShown(entry: NotificationEntry, gutsShown: Boolean)

    /**
     * Set that we are exiting the headsUp pinned mode, but some notifications might still be
     * animating out. This is used to keep the touchable regions in a reasonable state.
     */
    fun setHeadsUpAnimatingAway(headsUpAnimatingAway: Boolean)

    /**
     * Notifies that a remote input textbox in notification gets active or inactive.
     *
     * @param entry The entry of the target notification.
     * @param remoteInputActive True to notify active, False to notify inactive.
     */
    fun setRemoteInputActive(entry: NotificationEntry, remoteInputActive: Boolean)

    fun setTrackingHeadsUp(tracking: Boolean)

    /** Sets the current user. */
    fun setUser(user: Int)

    /**
     * Notes that the user took an action on an entry that might indirectly cause the system or the
     * app to remove the notification.
     *
     * @param entry the entry that might be indirectly removed by the user's action
     * @see
     *   com.android.systemui.statusbar.notification.collection.coordinator.HeadsUpCoordinator.mActionPressListener
     * @see .canRemoveImmediately
     */
    fun setUserActionMayIndirectlyRemove(entry: NotificationEntry)

    /**
     * Decides whether a click is invalid for a notification, i.e. it has not been shown long enough
     * that a user might have consciously clicked on it.
     *
     * @param key the key of the touched notification
     * @return whether the touch is invalid and should be discarded
     */
    fun shouldSwallowClick(key: String): Boolean

    /**
     * Called when posting a new notification that should alert the user and appear on screen. Adds
     * the notification to be managed.
     *
     * @param entry entry to show
     */
    fun showNotification(entry: NotificationEntry)

    fun snooze()

    /**
     * Unpins all pinned Heads Up Notifications.
     *
     * @param userUnPinned The unpinned action is trigger by user real operation.
     */
    fun unpinAll(userUnPinned: Boolean)

    fun updateNotification(key: String, shouldHeadsUpAgain: Boolean)

    fun onEntryAnimatingAwayEnded(entry: NotificationEntry)
}

/** Sets the animation state of the HeadsUpManager. */
interface AnimationStateHandler {
    fun setHeadsUpGoingAwayAnimationsAllowed(allowed: Boolean)
}

/** Listener to register for HeadsUpNotification Phone changes. */
interface OnHeadsUpPhoneListenerChange {
    /**
     * Called when a heads up notification is 'going away' or no longer 'going away'. See
     * [HeadsUpManager.setHeadsUpAnimatingAway].
     */
    // TODO(b/325936094) delete this callback, and listen to the flow instead
    fun onHeadsUpAnimatingAwayStateChanged(headsUpAnimatingAway: Boolean)
}

/* No op impl of HeadsUpManager. */
class HeadsUpManagerEmptyImpl @Inject constructor() : HeadsUpManager {
    override val allEntries = Stream.empty<NotificationEntry>()

    override fun addHeadsUpPhoneListener(listener: OnHeadsUpPhoneListenerChange) {}

    override fun addListener(listener: OnHeadsUpChangedListener) {}

    override fun addSwipedOutNotification(key: String) {}

    override fun canRemoveImmediately(key: String) = false

    override fun compare(a: NotificationEntry?, b: NotificationEntry?) = 0

    override fun dump(pw: PrintWriter, args: Array<out String>) {}

    override fun extendHeadsUp() {}

    override fun getEarliestRemovalTime(key: String?) = 0L

    override fun getTouchableRegion(): Region? = null

    override fun getTopEntry() = null

    override fun hasPinnedHeadsUp() = false

    override fun isHeadsUpEntry(key: String) = false

    override fun isHeadsUpAnimatingAwayValue() = false

    override fun isSnoozed(packageName: String) = false

    override fun isSticky(key: String?) = false

    override fun isTrackingHeadsUp() = false

    override fun onExpandingFinished() {}

    override fun releaseAllImmediately() {}

    override fun removeListener(listener: OnHeadsUpChangedListener) {}

    override fun removeNotification(key: String, releaseImmediately: Boolean, reason: String) =
        false

    override fun removeNotification(
        key: String,
        releaseImmediately: Boolean,
        animate: Boolean,
        reason: String
    ) = false

    override fun setAnimationStateHandler(handler: AnimationStateHandler) {}

    override fun setExpanded(entry: NotificationEntry, expanded: Boolean) {}

    override fun setGutsShown(entry: NotificationEntry, gutsShown: Boolean) {}

    override fun setHeadsUpAnimatingAway(headsUpAnimatingAway: Boolean) {}

    override fun setRemoteInputActive(entry: NotificationEntry, remoteInputActive: Boolean) {}

    override fun setTrackingHeadsUp(tracking: Boolean) {}

    override fun setUser(user: Int) {}

    override fun setUserActionMayIndirectlyRemove(entry: NotificationEntry) {}

    override fun shouldSwallowClick(key: String): Boolean = false

    override fun showNotification(entry: NotificationEntry) {}

    override fun snooze() {}

    override fun unpinAll(userUnPinned: Boolean) {}

    override fun updateNotification(key: String, alert: Boolean) {}

    override fun onEntryAnimatingAwayEnded(entry: NotificationEntry) {}
}

@Module
interface HeadsUpEmptyImplModule {
    @Binds @SysUISingleton fun bindsHeadsUpManager(noOpHum: HeadsUpManagerEmptyImpl): HeadsUpManager
}
