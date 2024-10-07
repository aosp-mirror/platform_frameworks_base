/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.policy;

import static com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_HEADS_UP;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.os.Handler;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.systemui.EventLogTags;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.InflationFlag;
import com.android.systemui.statusbar.notification.shared.NotificationThrottleHun;
import com.android.systemui.statusbar.phone.ExpandHeadsUpOnInlineReply;
import com.android.systemui.util.ListenerSet;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.settings.GlobalSettings;
import com.android.systemui.util.time.SystemClock;

import org.jetbrains.annotations.NotNull;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * A manager which handles heads up notifications which is a special mode where
 * they simply peek from the top of the screen.
 */
public abstract class BaseHeadsUpManager implements HeadsUpManager {
    private static final String TAG = "BaseHeadsUpManager";
    private static final String SETTING_HEADS_UP_SNOOZE_LENGTH_MS = "heads_up_snooze_length_ms";

    protected final ListenerSet<OnHeadsUpChangedListener> mListeners = new ListenerSet<>();

    protected final Context mContext;

    protected int mTouchAcceptanceDelay;
    protected int mSnoozeLengthMs;
    protected boolean mHasPinnedNotification;
    protected int mUser;

    private final ArrayMap<String, Long> mSnoozedPackages;
    private final AccessibilityManagerWrapper mAccessibilityMgr;

    private final UiEventLogger mUiEventLogger;
    private final AvalancheController mAvalancheController;

    protected final SystemClock mSystemClock;
    protected final ArrayMap<String, HeadsUpEntry> mHeadsUpEntryMap = new ArrayMap<>();
    protected final HeadsUpManagerLogger mLogger;
    protected int mMinimumDisplayTime;
    protected int mStickyForSomeTimeAutoDismissTime;
    protected int mAutoDismissTime;
    protected DelayableExecutor mExecutor;

    /**
     * Enum entry for notification peek logged from this class.
     */
    enum NotificationPeekEvent implements UiEventLogger.UiEventEnum {
        @UiEvent(doc = "Heads-up notification peeked on screen.")
        NOTIFICATION_PEEK(801);

        private final int mId;
        NotificationPeekEvent(int id) {
            mId = id;
        }
        @Override public int getId() {
            return mId;
        }
    }

    public BaseHeadsUpManager(@NonNull final Context context,
            HeadsUpManagerLogger logger,
            @Main Handler handler,
            GlobalSettings globalSettings,
            SystemClock systemClock,
            @Main DelayableExecutor executor,
            AccessibilityManagerWrapper accessibilityManagerWrapper,
            UiEventLogger uiEventLogger,
            AvalancheController avalancheController) {
        mLogger = logger;
        mExecutor = executor;
        mSystemClock = systemClock;
        mContext = context;
        mAccessibilityMgr = accessibilityManagerWrapper;
        mUiEventLogger = uiEventLogger;
        mAvalancheController = avalancheController;
        mAvalancheController.setBaseEntryMapStr(this::getEntryMapStr);
        Resources resources = context.getResources();
        mMinimumDisplayTime = NotificationThrottleHun.isEnabled()
                ? 500 : resources.getInteger(R.integer.heads_up_notification_minimum_time);
        mStickyForSomeTimeAutoDismissTime = resources.getInteger(
                R.integer.sticky_heads_up_notification_time);
        mAutoDismissTime = resources.getInteger(R.integer.heads_up_notification_decay);
        mTouchAcceptanceDelay = resources.getInteger(R.integer.touch_acceptance_delay);
        mSnoozedPackages = new ArrayMap<>();
        int defaultSnoozeLengthMs =
                resources.getInteger(R.integer.heads_up_default_snooze_length_ms);

        mSnoozeLengthMs = globalSettings.getInt(SETTING_HEADS_UP_SNOOZE_LENGTH_MS,
                defaultSnoozeLengthMs);
        ContentObserver settingsObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                final int packageSnoozeLengthMs = globalSettings.getInt(
                        SETTING_HEADS_UP_SNOOZE_LENGTH_MS, -1);
                if (packageSnoozeLengthMs > -1 && packageSnoozeLengthMs != mSnoozeLengthMs) {
                    mSnoozeLengthMs = packageSnoozeLengthMs;
                    mLogger.logSnoozeLengthChange(packageSnoozeLengthMs);
                }
            }
        };
        globalSettings.registerContentObserverSync(
                globalSettings.getUriFor(SETTING_HEADS_UP_SNOOZE_LENGTH_MS),
                /* notifyForDescendants = */ false,
                settingsObserver);
    }

    /**
     * Adds an OnHeadUpChangedListener to observe events.
     */
    public void addListener(@NonNull OnHeadsUpChangedListener listener) {
        mListeners.addIfAbsent(listener);
    }

    /**
     * Removes the OnHeadUpChangedListener from the observer list.
     */
    public void removeListener(@NonNull OnHeadsUpChangedListener listener) {
        mListeners.remove(listener);
    }

    /**
     * Called when posting a new notification that should appear on screen.
     * Adds the notification to be managed.
     * @param entry entry to show
     */
    @Override
    public void showNotification(@NonNull NotificationEntry entry) {
        HeadsUpEntry headsUpEntry = createHeadsUpEntry(entry);

        mLogger.logShowNotificationRequest(entry);

        Runnable runnable = () -> {
            mLogger.logShowNotification(entry);

            // Add new entry and begin managing it
            mHeadsUpEntryMap.put(entry.getKey(), headsUpEntry);
            onEntryAdded(headsUpEntry);
            // TODO(b/328390331) move accessibility events to the view layer
            entry.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
            entry.setIsHeadsUpEntry(true);

            updateNotificationInternal(entry.getKey(), true /* shouldHeadsUpAgain */);
            entry.setInterruption();
        };
        mAvalancheController.update(headsUpEntry, runnable, "showNotification");
    }

    /**
     * Try to remove the notification.  May not succeed if the notification has not been shown long
     * enough and needs to be kept around.
     * @param key the key of the notification to remove
     * @param releaseImmediately force a remove regardless of earliest removal time
     * @param reason reason for removing the notification
     * @return true if notification is removed, false otherwise
     */
    @Override
    public boolean removeNotification(@NotNull String key, boolean releaseImmediately,
            @NonNull String reason) {
        final boolean isWaiting = mAvalancheController.isWaiting(key);
        mLogger.logRemoveNotification(key, releaseImmediately, isWaiting, reason);

        if (mAvalancheController.isWaiting(key)) {
            removeEntry(key, "removeNotification (isWaiting)");
            return true;
        }
        HeadsUpEntry headsUpEntry = mHeadsUpEntryMap.get(key);
        if (headsUpEntry == null) {
            mLogger.logNullEntry(key, reason);
            return true;
        }
        if (releaseImmediately) {
            removeEntry(key, "removeNotification (releaseImmediately)");
            return true;
        }
        if (canRemoveImmediately(key)) {
            removeEntry(key, "removeNotification (canRemoveImmediately)");
            return true;
        }
        headsUpEntry.removeAsSoonAsPossible();
        return false;
    }

    /**
     * Called when the notification state has been updated.
     * @param key the key of the entry that was updated
     * @param shouldHeadsUpAgain whether the notification should show again and force reevaluation
     *                           of removal time
     */
    public void updateNotification(@NonNull String key, boolean shouldHeadsUpAgain) {
        HeadsUpEntry headsUpEntry = mHeadsUpEntryMap.get(key);
        mLogger.logUpdateNotificationRequest(key, shouldHeadsUpAgain, headsUpEntry != null);

        Runnable runnable = () -> {
            updateNotificationInternal(key, shouldHeadsUpAgain);
        };
        mAvalancheController.update(headsUpEntry, runnable, "updateNotification");
    }

    private void updateNotificationInternal(@NonNull String key, boolean shouldHeadsUpAgain) {
        HeadsUpEntry headsUpEntry = mHeadsUpEntryMap.get(key);
        mLogger.logUpdateNotification(key, shouldHeadsUpAgain, headsUpEntry != null);
        if (headsUpEntry == null) {
            // the entry was released before this update (i.e by a listener) This can happen
            // with the groupmanager
            return;
        }
        // TODO(b/328390331) move accessibility events to the view layer
        if (headsUpEntry.mEntry != null) {
            headsUpEntry.mEntry.sendAccessibilityEvent(
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        }
        if (shouldHeadsUpAgain) {
            headsUpEntry.updateEntry(true /* updatePostTime */, "updateNotification");
            if (headsUpEntry != null) {
                setEntryPinned(headsUpEntry, shouldHeadsUpBecomePinned(headsUpEntry.mEntry),
                        "updateNotificationInternal");
            }
        }
    }

    /**
     * Clears all managed notifications.
     */
    public void releaseAllImmediately() {
        mLogger.logReleaseAllImmediately();
        // A copy is necessary here as we are changing the underlying map.  This would cause
        // undefined behavior if we iterated over the key set directly.
        ArraySet<String> keysToRemove = new ArraySet<>(mHeadsUpEntryMap.keySet());

        // Must get waiting keys before calling removeEntry, which clears waiting entries in
        // AvalancheController
        List<String> waitingKeysToRemove = mAvalancheController.getWaitingKeys();

        for (String key : keysToRemove) {
            removeEntry(key, "releaseAllImmediately (keysToRemove)");
        }
        for (String key : waitingKeysToRemove) {
            removeEntry(key, "releaseAllImmediately (waitingKeysToRemove)");
        }
    }

    /**
     * Returns the entry if it is managed by this manager.
     * @param key key of notification
     * @return the entry
     */
    @Nullable
    public NotificationEntry getEntry(@NonNull String key) {
        HeadsUpEntry headsUpEntry = mHeadsUpEntryMap.get(key);
        return headsUpEntry != null ? headsUpEntry.mEntry : null;
    }

    /**
     * Returns the stream of all current notifications managed by this manager.
     * @return all entries
     */
    @NonNull
    @Override
    public Stream<NotificationEntry> getAllEntries() {
        return getHeadsUpEntryList().stream().map(headsUpEntry -> headsUpEntry.mEntry);
    }

    public List<HeadsUpEntry> getHeadsUpEntryList() {
        List<HeadsUpEntry> entryList = new ArrayList<>(mHeadsUpEntryMap.values());
        entryList.addAll(mAvalancheController.getWaitingEntryList());
        return entryList;
    }

    /**
     * Whether or not there are any active notifications.
     * @return true if there is an entry, false otherwise
     */
    @Override
    public boolean hasNotifications() {
        return !mHeadsUpEntryMap.isEmpty()
                || !mAvalancheController.getWaitingEntryList().isEmpty();
    }

    /**
     * @return true if the notification is managed by this manager
     */
    public boolean isHeadsUpEntry(@NonNull String key) {
        return mHeadsUpEntryMap.containsKey(key) || mAvalancheController.isWaiting(key);
    }

    /**
     * @param key
     * @return When a HUN entry should be removed in milliseconds from now
     */
    @Override
    public long getEarliestRemovalTime(String key) {
        HeadsUpEntry entry = mHeadsUpEntryMap.get(key);
        if (entry != null) {
            return Math.max(0, entry.mEarliestRemovalTime - mSystemClock.elapsedRealtime());
        }
        return 0;
    }

    protected boolean shouldHeadsUpBecomePinned(@NonNull NotificationEntry entry) {
        if (entry == null) {
            return false;
        }
        final HeadsUpEntry headsUpEntry = getHeadsUpEntry(entry.getKey());
        if (headsUpEntry == null) {
            // This should not happen since shouldHeadsUpBecomePinned is always called after adding
            // the NotificationEntry into mHeadsUpEntryMap.
            return hasFullScreenIntent(entry);
        }
        return hasFullScreenIntent(entry) && !headsUpEntry.mWasUnpinned;
    }

    protected boolean hasFullScreenIntent(@NonNull NotificationEntry entry) {
        if (entry == null) {
            return false;
        }
        if (entry.getSbn() == null) {
            return false;
        }
        if (entry.getSbn().getNotification() == null) {
            return false;
        }
        return entry.getSbn().getNotification().fullScreenIntent != null;
    }

    protected void setEntryPinned(
            @NonNull BaseHeadsUpManager.HeadsUpEntry headsUpEntry, boolean isPinned,
            String reason) {
        mLogger.logSetEntryPinned(headsUpEntry.mEntry, isPinned, reason);
        NotificationEntry entry = headsUpEntry.mEntry;
        if (!isPinned) {
            headsUpEntry.mWasUnpinned = true;
        }
        if (headsUpEntry.isRowPinned() != isPinned) {
            headsUpEntry.setRowPinned(isPinned);
            updatePinnedMode();
            if (isPinned && entry.getSbn() != null) {
               mUiEventLogger.logWithInstanceId(
                        NotificationPeekEvent.NOTIFICATION_PEEK, entry.getSbn().getUid(),
                        entry.getSbn().getPackageName(), entry.getSbn().getInstanceId());
            }
        // TODO(b/325936094) use the isPinned Flow instead
            for (OnHeadsUpChangedListener listener : mListeners) {
                if (isPinned) {
                    listener.onHeadsUpPinned(entry);
                } else {
                    listener.onHeadsUpUnPinned(entry);
                }
            }
        }
    }

    public @InflationFlag int getContentFlag() {
        return FLAG_CONTENT_VIEW_HEADS_UP;
    }

    /**
     * Manager-specific logic that should occur when an entry is added.
     * @param headsUpEntry entry added
     */
    protected void onEntryAdded(HeadsUpEntry headsUpEntry) {
        NotificationEntry entry = headsUpEntry.mEntry;
        entry.setHeadsUp(true);

        final boolean shouldPin = shouldHeadsUpBecomePinned(entry);
        setEntryPinned(headsUpEntry, shouldPin, "onEntryAdded");
        EventLogTags.writeSysuiHeadsUpStatus(entry.getKey(), 1 /* visible */);
        for (OnHeadsUpChangedListener listener : mListeners) {
            listener.onHeadsUpStateChanged(entry, true);
        }
    }

    /**
     * Remove a notification from the alerting entries.
     * @param key key of notification to remove
     */
    protected final void removeEntry(@NonNull String key, String reason) {
        HeadsUpEntry headsUpEntry = mHeadsUpEntryMap.get(key);
        boolean isWaiting;
        if (headsUpEntry == null) {
            headsUpEntry = mAvalancheController.getWaitingEntry(key);
            isWaiting = true;
        } else {
            isWaiting = false;
        }
        mLogger.logRemoveEntryRequest(key, reason, isWaiting);
        HeadsUpEntry finalHeadsUpEntry = headsUpEntry;
        Runnable runnable = () -> {
            mLogger.logRemoveEntry(key, reason, isWaiting);

            if (finalHeadsUpEntry == null) {
                return;
            }
            NotificationEntry entry = finalHeadsUpEntry.mEntry;

            // If the notification is animating, we will remove it at the end of the animation.
            if (entry != null && entry.isExpandAnimationRunning()) {
                return;
            }
            entry.demoteStickyHun();
            mHeadsUpEntryMap.remove(key);
            onEntryRemoved(finalHeadsUpEntry);
            // TODO(b/328390331) move accessibility events to the view layer
            entry.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
            if (NotificationThrottleHun.isEnabled()) {
                finalHeadsUpEntry.cancelAutoRemovalCallbacks("removeEntry");
            } else {
                finalHeadsUpEntry.reset();
            }
        };
        mAvalancheController.delete(headsUpEntry, runnable, "removeEntry");
    }

    /**
     * Manager-specific logic that should occur when an entry is removed.
     * @param headsUpEntry entry removed
     */
    protected void onEntryRemoved(HeadsUpEntry headsUpEntry) {
        NotificationEntry entry = headsUpEntry.mEntry;
        entry.setHeadsUp(false);
        setEntryPinned(headsUpEntry, false /* isPinned */, "onEntryRemoved");
        EventLogTags.writeSysuiHeadsUpStatus(entry.getKey(), 0 /* visible */);
        mLogger.logNotificationActuallyRemoved(entry);
        for (OnHeadsUpChangedListener listener : mListeners) {
            listener.onHeadsUpStateChanged(entry, false);
        }
    }

    /**
     * Called to notify the listeners that the HUN animating away animation has ended.
     */
    public void onEntryAnimatingAwayEnded(@NonNull NotificationEntry entry) {
        for (OnHeadsUpChangedListener listener : mListeners) {
            listener.onHeadsUpAnimatingAwayEnded(entry);
        }
    }

    /**
     * Manager-specific logic, that should occur, when the entry is updated, and its posted time has
     * changed.
     *
     * @param headsUpEntry entry updated
     */
    protected void onEntryUpdated(HeadsUpEntry headsUpEntry) {
    }

    protected void updatePinnedMode() {
        boolean hasPinnedNotification = hasPinnedNotificationInternal();
        if (hasPinnedNotification == mHasPinnedNotification) {
            return;
        }
        mLogger.logUpdatePinnedMode(hasPinnedNotification);
        mHasPinnedNotification = hasPinnedNotification;
        if (mHasPinnedNotification) {
            MetricsLogger.count(mContext, "note_peek", 1);
        }
        for (OnHeadsUpChangedListener listener : mListeners) {
            listener.onHeadsUpPinnedModeChanged(hasPinnedNotification);
        }
    }

    /**
     * Returns if the given notification is snoozed or not.
     */
    public boolean isSnoozed(@NonNull String packageName) {
        final String key = snoozeKey(packageName, mUser);
        Long snoozedUntil = mSnoozedPackages.get(key);
        if (snoozedUntil != null) {
            if (snoozedUntil > mSystemClock.elapsedRealtime()) {
                mLogger.logIsSnoozedReturned(key);
                return true;
            }
            mLogger.logPackageUnsnoozed(key);
            mSnoozedPackages.remove(key);
        }
        return false;
    }

    /**
     * Snoozes all current Heads Up Notifications.
     */
    public void snooze() {
        List<String> keySet = new ArrayList<>(mHeadsUpEntryMap.keySet());
        keySet.addAll(mAvalancheController.getWaitingKeys());
        for (String key : keySet) {
            HeadsUpEntry entry = getHeadsUpEntry(key);
            if (entry.mEntry == null) {
                continue;
            }
            String packageName = entry.mEntry.getSbn().getPackageName();
            String snoozeKey = snoozeKey(packageName, mUser);
            mLogger.logPackageSnoozed(snoozeKey);
            mSnoozedPackages.put(snoozeKey, mSystemClock.elapsedRealtime() + mSnoozeLengthMs);
        }
    }

    @NonNull
    private static String snoozeKey(@NonNull String packageName, int user) {
        return user + "," + packageName;
    }

    @Nullable
    protected HeadsUpEntry getHeadsUpEntry(@NonNull String key) {
        if (mHeadsUpEntryMap.containsKey(key)) {
            return mHeadsUpEntryMap.get(key);
        }
        return mAvalancheController.getWaitingEntry(key);
    }

    /**
     * Returns the top Heads Up Notification, which appears to show at first.
     */
    @Nullable
    public NotificationEntry getTopEntry() {
        HeadsUpEntry topEntry = getTopHeadsUpEntry();
        return (topEntry != null) ? topEntry.mEntry : null;
    }

    @Nullable
    protected HeadsUpEntry getTopHeadsUpEntry() {
        if (mHeadsUpEntryMap.isEmpty()) {
            return null;
        }
        HeadsUpEntry topEntry = null;
        for (HeadsUpEntry entry: mHeadsUpEntryMap.values()) {
            if (topEntry == null || entry.compareTo(topEntry) < 0) {
                topEntry = entry;
            }
        }
        return topEntry;
    }

    /**
     * Sets the current user.
     */
    public void setUser(int user) {
        mUser = user;
    }

    /** Returns the ID of the current user. */
    public int getUser() {
        return  mUser;
    }

    private String getEntryMapStr() {
        if (mHeadsUpEntryMap.isEmpty()) {
            return "EMPTY";
        }
        StringBuilder entryMapStr = new StringBuilder();
        for (HeadsUpEntry entry: mHeadsUpEntryMap.values()) {
            entryMapStr.append("\n\t").append(
                    entry.mEntry == null ? "null" : entry.mEntry.getKey());
        }
        return entryMapStr.toString();
    }

    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("HeadsUpManager state:");
        dumpInternal(pw, args);
    }

    protected void dumpInternal(@NonNull PrintWriter pw, @NonNull String[] args) {
        pw.print("  mTouchAcceptanceDelay="); pw.println(mTouchAcceptanceDelay);
        pw.print("  mSnoozeLengthMs="); pw.println(mSnoozeLengthMs);
        pw.print("  now="); pw.println(mSystemClock.elapsedRealtime());
        pw.print("  mUser="); pw.println(mUser);
        for (HeadsUpEntry entry: mHeadsUpEntryMap.values()) {
            pw.println(entry.mEntry == null ? "null" : entry.mEntry);
        }
        int n = mSnoozedPackages.size();
        pw.println("  snoozed packages: " + n);
        for (int i = 0; i < n; i++) {
            pw.print("    "); pw.print(mSnoozedPackages.valueAt(i));
            pw.print(", "); pw.println(mSnoozedPackages.keyAt(i));
        }
    }

    /**
     * Returns if there are any pinned Heads Up Notifications or not.
     */
    public boolean hasPinnedHeadsUp() {
        return mHasPinnedNotification;
    }

    private boolean hasPinnedNotificationInternal() {
        for (String key : mHeadsUpEntryMap.keySet()) {
            HeadsUpEntry entry = getHeadsUpEntry(key);
            if (entry.mEntry != null && entry.mEntry.isRowPinned()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Unpins all pinned Heads Up Notifications.
     * @param userUnPinned The unpinned action is trigger by user real operation.
     */
    public void unpinAll(boolean userUnPinned) {
        for (String key : mHeadsUpEntryMap.keySet()) {
            HeadsUpEntry headsUpEntry = getHeadsUpEntry(key);
            mLogger.logUnpinEntryRequest(key);
            Runnable runnable = () -> {
                mLogger.logUnpinEntry(key);

                setEntryPinned(headsUpEntry, false /* isPinned */, "unpinAll");
                // maybe it got un sticky
                headsUpEntry.updateEntry(false /* updatePostTime */, "unpinAll");

                // when the user unpinned all of HUNs by moving one HUN, all of HUNs should not stay
                // on the screen.
                if (userUnPinned && headsUpEntry.mEntry != null) {
                    if (headsUpEntry.mEntry != null && headsUpEntry.mEntry.mustStayOnScreen()) {
                        headsUpEntry.mEntry.setHeadsUpIsVisible();
                    }
                }
            };
            mAvalancheController.delete(headsUpEntry, runnable, "unpinAll");
        }
    }

    /**
     * Returns the value of the tracking-heads-up flag. See the doc of {@code setTrackingHeadsUp} as
     * well.
     */
    public boolean isTrackingHeadsUp() {
        // Might be implemented in subclass.
        return false;
    }

    /**
     * Compare two entries and decide how they should be ranked.
     *
     * @return -1 if the first argument should be ranked higher than the second, 1 if the second
     * one should be ranked higher and 0 if they are equal.
     */
    public int compare(@Nullable NotificationEntry a, @Nullable NotificationEntry b) {
        if (a == null || b == null) {
            return Boolean.compare(a == null, b == null);
        }
        HeadsUpEntry aEntry = getHeadsUpEntry(a.getKey());
        HeadsUpEntry bEntry = getHeadsUpEntry(b.getKey());
        if (aEntry == null || bEntry == null) {
            return Boolean.compare(aEntry == null, bEntry == null);
        }
        return aEntry.compareTo(bEntry);
    }

    /**
     * Set an entry to be expanded and therefore stick in the heads up area if it's pinned
     * until it's collapsed again.
     */
    public void setExpanded(@NonNull NotificationEntry entry, boolean expanded) {
        HeadsUpEntry headsUpEntry = getHeadsUpEntry(entry.getKey());
        if (headsUpEntry != null && entry.isRowPinned()) {
            headsUpEntry.setExpanded(expanded);
        }
    }

    /**
     * Notes that the user took an action on an entry that might indirectly cause the system or the
     * app to remove the notification.
     *
     * @param entry the entry that might be indirectly removed by the user's action
     *
     * @see HeadsUpCoordinator#mActionPressListener
     * @see #canRemoveImmediately(String)
     */
    public void setUserActionMayIndirectlyRemove(@NonNull NotificationEntry entry) {
        HeadsUpEntry headsUpEntry = getHeadsUpEntry(entry.getKey());
        if (headsUpEntry != null) {
            headsUpEntry.mUserActionMayIndirectlyRemove = true;
        }
    }

    /**
     * Whether or not the entry can be removed currently.  If it hasn't been on screen long enough
     * it should not be removed unless forced
     * @param key the key to check if removable
     * @return true if the entry can be removed
     */
    @Override
    public boolean canRemoveImmediately(@NonNull String key) {
        HeadsUpEntry headsUpEntry = getHeadsUpEntry(key);
        if (headsUpEntry != null && headsUpEntry.mUserActionMayIndirectlyRemove) {
            return true;
        }
        return headsUpEntry == null || headsUpEntry.wasShownLongEnough()
                || (headsUpEntry.mEntry != null && headsUpEntry.mEntry.isRowDismissed());
    }

    /**
     * @param key
     * @return true if the entry is (pinned and expanded) or (has an active remote input)
     */
    @Override
    public boolean isSticky(String key) {
        HeadsUpEntry headsUpEntry = getHeadsUpEntry(key);
        if (headsUpEntry != null) {
            return headsUpEntry.isSticky();
        }
        return false;
    }

    @NonNull
    protected HeadsUpEntry createHeadsUpEntry(NotificationEntry entry) {
        return new HeadsUpEntry(entry);
    }

    /**
     * Determines if the notification is for a critical call that must display on top of an active
     * input notification.
     * The call isOngoing check is for a special case of incoming calls (see b/164291424).
     */
    private static boolean isCriticalCallNotif(NotificationEntry entry) {
        Notification n = entry.getSbn().getNotification();
        boolean isIncomingCall = n.isStyle(Notification.CallStyle.class) && n.extras.getInt(
                Notification.EXTRA_CALL_TYPE) == Notification.CallStyle.CALL_TYPE_INCOMING;
        return isIncomingCall || (entry.getSbn().isOngoing()
                && Notification.CATEGORY_CALL.equals(n.category));
    }

    /**
     * This represents a notification and how long it is in a heads up mode. It also manages its
     * lifecycle automatically when created. This class is public because it is exposed by methods
     * of AvalancheController that take it as param.
     */
    public class HeadsUpEntry implements Comparable<HeadsUpEntry> {
        public boolean mRemoteInputActivatedAtLeastOnce;
        public boolean mRemoteInputActive;
        public boolean mUserActionMayIndirectlyRemove;

        protected boolean mExpanded;
        protected boolean mWasUnpinned;

        @Nullable public NotificationEntry mEntry;
        public long mPostTime;
        public long mEarliestRemovalTime;

        @Nullable protected Runnable mRemoveRunnable;

        @Nullable private Runnable mCancelRemoveRunnable;

        public HeadsUpEntry() {
            NotificationThrottleHun.assertInLegacyMode();
        }

        public HeadsUpEntry(NotificationEntry entry) {
            // Attach NotificationEntry for AvalancheController to log key and
            // record mPostTime for AvalancheController sorting
            setEntry(entry, createRemoveRunnable(entry));
        }

        /** Attach a NotificationEntry. */
        public void setEntry(@NonNull final NotificationEntry entry) {
            NotificationThrottleHun.assertInLegacyMode();
            setEntry(entry, createRemoveRunnable(entry));
        }

        protected void setEntry(@NonNull final NotificationEntry entry,
                @Nullable Runnable removeRunnable) {
            mEntry = entry;
            mRemoveRunnable = removeRunnable;

            mPostTime = calculatePostTime();
            updateEntry(true /* updatePostTime */, "setEntry");
        }

        protected boolean isRowPinned() {
            return mEntry != null && mEntry.isRowPinned();
        }

        protected void setRowPinned(boolean pinned) {
            if (mEntry != null) mEntry.setRowPinned(pinned);
        }

        /**
         * An interface that returns the amount of time left this HUN should show.
         */
        interface FinishTimeUpdater {
            long updateAndGetTimeRemaining();
        }

        /**
         * Updates an entry's removal time.
         * @param updatePostTime whether or not to refresh the post time
         */
        public void updateEntry(boolean updatePostTime, @Nullable String reason) {
            updateEntry(updatePostTime, /* updateEarliestRemovalTime= */ true, reason);
        }

        /**
         * Updates an entry's removal time.
         * @param updatePostTime whether or not to refresh the post time
         * @param updateEarliestRemovalTime whether this update should further delay removal
         */
        public void updateEntry(boolean updatePostTime, boolean updateEarliestRemovalTime,
                @Nullable String reason) {
            Runnable runnable = () -> {
                mLogger.logUpdateEntry(mEntry, updatePostTime, reason);

                final long now = mSystemClock.elapsedRealtime();
                if (updateEarliestRemovalTime) {
                    mEarliestRemovalTime = now + mMinimumDisplayTime;
                }

                if (updatePostTime) {
                    mPostTime = Math.max(mPostTime, now);
                }
            };
            mAvalancheController.update(this, runnable, "updateEntry (updatePostTime)");

            if (isSticky()) {
                cancelAutoRemovalCallbacks("updateEntry (sticky)");
                return;
            }

            FinishTimeUpdater finishTimeCalculator = () -> {
                final long finishTime = calculateFinishTime();
                final long now = mSystemClock.elapsedRealtime();
                final long timeLeft = NotificationThrottleHun.isEnabled()
                        ? Math.max(finishTime, mEarliestRemovalTime) - now
                        : Math.max(finishTime - now, mMinimumDisplayTime);
                return timeLeft;
            };
            scheduleAutoRemovalCallback(finishTimeCalculator, "updateEntry (not sticky)");

            // Notify the manager, that the posted time has changed.
            onEntryUpdated(this);
        }

        /**
         * Whether or not the notification is "sticky" i.e. should stay on screen regardless
         * of the timer (forever) and should be removed externally.
         * @return true if the notification is sticky
         */
        public boolean isSticky() {
            if (mEntry == null) return false;

            if (ExpandHeadsUpOnInlineReply.isEnabled()) {
                // we don't consider pinned and expanded huns as sticky after the remote input
                // has been activated for them
                if (!mRemoteInputActive && mRemoteInputActivatedAtLeastOnce) {
                    return false;
                }
            }

            return (mEntry.isRowPinned() && mExpanded)
                    || mRemoteInputActive
                    || hasFullScreenIntent(mEntry);
        }

        public boolean isStickyForSomeTime() {
            if (mEntry == null) return false;

            return mEntry.isStickyAndNotDemoted();
        }

        /**
         * Whether the notification has been on screen long enough and can be removed.
         * @return true if the notification has been on screen long enough
         */
        public boolean wasShownLongEnough() {
            return mEarliestRemovalTime < mSystemClock.elapsedRealtime();
        }

        public int compareNonTimeFields(HeadsUpEntry headsUpEntry) {
            if (mEntry == null && headsUpEntry.mEntry == null) {
                return 0;
            } else if (headsUpEntry.mEntry == null) {
                return -1;
            } else if (mEntry == null) {
                return 1;
            }

            boolean selfFullscreen = hasFullScreenIntent(mEntry);
            boolean otherFullscreen = hasFullScreenIntent(headsUpEntry.mEntry);
            if (selfFullscreen && !otherFullscreen) {
                return -1;
            } else if (!selfFullscreen && otherFullscreen) {
                return 1;
            }

            boolean selfCall = isCriticalCallNotif(mEntry);
            boolean otherCall = isCriticalCallNotif(headsUpEntry.mEntry);

            if (selfCall && !otherCall) {
                return -1;
            } else if (!selfCall && otherCall) {
                return 1;
            }

            if (mRemoteInputActive && !headsUpEntry.mRemoteInputActive) {
                return -1;
            } else if (!mRemoteInputActive && headsUpEntry.mRemoteInputActive) {
                return 1;
            }
            return 0;
        }

        public int compareTo(@NonNull HeadsUpEntry headsUpEntry) {
            if (mEntry == null && headsUpEntry.mEntry == null) {
                return 0;
            } else if (headsUpEntry.mEntry == null) {
                return -1;
            } else if (mEntry == null) {
                return 1;
            }
            boolean isPinned = mEntry.isRowPinned();
            boolean otherPinned = headsUpEntry.mEntry.isRowPinned();
            if (isPinned && !otherPinned) {
                return -1;
            } else if (!isPinned && otherPinned) {
                return 1;
            }
            int nonTimeCompareResult = compareNonTimeFields(headsUpEntry);
            if (nonTimeCompareResult != 0) {
                return nonTimeCompareResult;
            }
            if (mPostTime > headsUpEntry.mPostTime) {
                return -1;
            } else if (mPostTime == headsUpEntry.mPostTime) {
                return mEntry.getKey().compareTo(headsUpEntry.mEntry.getKey());
            } else {
                return 1;
            }
        }

        @Override
        public int hashCode() {
            if (mEntry == null) return super.hashCode();
            int result = mEntry.getKey().hashCode();
            result = 31 * result;
            return result;
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) return true;
            if (o == null || !(o instanceof HeadsUpEntry)) return false;
            HeadsUpEntry otherHeadsUpEntry = (HeadsUpEntry) o;
            if (mEntry != null && otherHeadsUpEntry.mEntry != null) {
                return mEntry.getKey().equals(otherHeadsUpEntry.mEntry.getKey());
            }
            return false;
        }

        public void setExpanded(boolean expanded) {
            this.mExpanded = expanded;
        }

        public void reset() {
            NotificationThrottleHun.assertInLegacyMode();
            cancelAutoRemovalCallbacks("reset()");
            mEntry = null;
            mRemoveRunnable = null;
            mExpanded = false;
            mRemoteInputActive = false;
        }

        /**
         * Clear any pending removal runnables.
         */
        public void cancelAutoRemovalCallbacks(@Nullable String reason) {
            Runnable runnable = () -> {
                final boolean removed = cancelAutoRemovalCallbackInternal();

                if (removed) {
                    mLogger.logAutoRemoveCanceled(mEntry, reason);
                }
            };
            if (mEntry != null && isHeadsUpEntry(mEntry.getKey())) {
                mLogger.logAutoRemoveCancelRequest(this.mEntry, reason);
                mAvalancheController.update(this, runnable, reason + " cancelAutoRemovalCallbacks");
            } else {
                // Just removed
                runnable.run();
            }
        }

        public void scheduleAutoRemovalCallback(FinishTimeUpdater finishTimeCalculator,
                @NonNull String reason) {

            mLogger.logAutoRemoveRequest(this.mEntry, reason);
            Runnable runnable = () -> {
                long delayMs = finishTimeCalculator.updateAndGetTimeRemaining();

                if (mRemoveRunnable == null) {
                    Log.wtf(TAG, "scheduleAutoRemovalCallback with no callback set");
                    return;
                }

                final boolean deletedExistingRemovalRunnable = cancelAutoRemovalCallbackInternal();
                mCancelRemoveRunnable = mExecutor.executeDelayed(mRemoveRunnable,
                        delayMs);

                if (deletedExistingRemovalRunnable) {
                    mLogger.logAutoRemoveRescheduled(mEntry, delayMs, reason);
                } else {
                    mLogger.logAutoRemoveScheduled(mEntry, delayMs, reason);
                }
            };
            mAvalancheController.update(this, runnable,
                    reason + " scheduleAutoRemovalCallback");
        }

        public boolean cancelAutoRemovalCallbackInternal() {
            final boolean scheduled = (mCancelRemoveRunnable != null);

            if (scheduled) {
                mCancelRemoveRunnable.run();  // Delete removal runnable from Executor queue
                mCancelRemoveRunnable = null;
            }

            return scheduled;
        }

        /**
         * Remove the entry at the earliest allowed removal time.
         */
        public void removeAsSoonAsPossible() {
            if (mRemoveRunnable != null) {

                FinishTimeUpdater finishTimeCalculator = () -> {
                    final long timeLeft = mEarliestRemovalTime - mSystemClock.elapsedRealtime();
                    return timeLeft;
                };
                scheduleAutoRemovalCallback(finishTimeCalculator, "removeAsSoonAsPossible");
            }
        }

        /** Creates a runnable to remove this notification from the alerting entries. */
        protected Runnable createRemoveRunnable(NotificationEntry entry) {
            return () -> removeEntry(entry.getKey(), "createRemoveRunnable");
        }

        /**
         * Calculate what the post time of a notification is at some current time.
         * @return the post time
         */
        protected long calculatePostTime() {
            // The actual post time will be just after the heads-up really slided in
            return mSystemClock.elapsedRealtime() + mTouchAcceptanceDelay;
        }

        /**
         * @return When the notification should auto-dismiss itself, based on
         * {@link SystemClock#elapsedRealtime()}
         */
        protected long calculateFinishTime() {
            int requestedTimeOutMs;
            if (isStickyForSomeTime()) {
                requestedTimeOutMs = mStickyForSomeTimeAutoDismissTime;
            } else {
                requestedTimeOutMs = mAvalancheController.getDurationMs(this, mAutoDismissTime);
            }
            final long duration = getRecommendedHeadsUpTimeoutMs(requestedTimeOutMs);
            return mPostTime + duration;
        }

        /**
         * Get user-preferred or default timeout duration. The larger one will be returned.
         * @return milliseconds before auto-dismiss
         * @param requestedTimeout
         */
        protected int getRecommendedHeadsUpTimeoutMs(int requestedTimeout) {
            return mAccessibilityMgr.getRecommendedTimeoutMillis(
                    requestedTimeout,
                    AccessibilityManager.FLAG_CONTENT_CONTROLS
                            | AccessibilityManager.FLAG_CONTENT_ICONS
                            | AccessibilityManager.FLAG_CONTENT_TEXT);
        }
    }
}
