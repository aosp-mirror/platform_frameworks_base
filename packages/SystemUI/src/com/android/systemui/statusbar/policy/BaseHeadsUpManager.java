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
import android.view.accessibility.AccessibilityManager;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.systemui.EventLogTags;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.AlertingNotificationManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.InflationFlag;
import com.android.systemui.util.ListenerSet;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.settings.GlobalSettings;
import com.android.systemui.util.time.SystemClock;

import java.io.PrintWriter;

/**
 * A manager which handles heads up notifications which is a special mode where
 * they simply peek from the top of the screen.
 */
public abstract class BaseHeadsUpManager extends AlertingNotificationManager implements
        HeadsUpManager {
    private static final String TAG = "HeadsUpManager";
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
            UiEventLogger uiEventLogger) {
        super(logger, systemClock, executor);
        mContext = context;
        mAccessibilityMgr = accessibilityManagerWrapper;
        mUiEventLogger = uiEventLogger;
        Resources resources = context.getResources();
        mMinimumDisplayTime = resources.getInteger(R.integer.heads_up_notification_minimum_time);
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
        globalSettings.registerContentObserver(
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

    /** Updates the notification with the given key. */
    public void updateNotification(@NonNull String key, boolean alert) {
        super.updateNotification(key, alert);
        HeadsUpEntry headsUpEntry = getHeadsUpEntry(key);
        if (alert && headsUpEntry != null) {
            setEntryPinned(headsUpEntry, shouldHeadsUpBecomePinned(headsUpEntry.mEntry));
        }
    }

    protected boolean shouldHeadsUpBecomePinned(@NonNull NotificationEntry entry) {
        final HeadsUpEntry headsUpEntry = getHeadsUpEntry(entry.getKey());
        if (headsUpEntry == null) {
            // This should not happen since shouldHeadsUpBecomePinned is always called after adding
            // the NotificationEntry into AlertingNotificationManager's mAlertEntries map.
            return hasFullScreenIntent(entry);
        }
        return hasFullScreenIntent(entry) && !headsUpEntry.mWasUnpinned;
    }

    protected boolean hasFullScreenIntent(@NonNull NotificationEntry entry) {
        return entry.getSbn().getNotification().fullScreenIntent != null;
    }

    protected void setEntryPinned(
            @NonNull BaseHeadsUpManager.HeadsUpEntry headsUpEntry, boolean isPinned) {
        mLogger.logSetEntryPinned(headsUpEntry.mEntry, isPinned);
        NotificationEntry entry = headsUpEntry.mEntry;
        if (!isPinned) {
            headsUpEntry.mWasUnpinned = true;
        }
        if (entry.isRowPinned() != isPinned) {
            entry.setRowPinned(isPinned);
            updatePinnedMode();
            if (isPinned && entry.getSbn() != null) {
                mUiEventLogger.logWithInstanceId(
                        NotificationPeekEvent.NOTIFICATION_PEEK, entry.getSbn().getUid(),
                        entry.getSbn().getPackageName(), entry.getSbn().getInstanceId());
            }
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

    @Override
    protected void onAlertEntryAdded(AlertEntry alertEntry) {
        NotificationEntry entry = alertEntry.mEntry;
        entry.setHeadsUp(true);

        final boolean shouldPin = shouldHeadsUpBecomePinned(entry);
        setEntryPinned((HeadsUpEntry) alertEntry, shouldPin);
        EventLogTags.writeSysuiHeadsUpStatus(entry.getKey(), 1 /* visible */);
        for (OnHeadsUpChangedListener listener : mListeners) {
            listener.onHeadsUpStateChanged(entry, true);
        }
    }

    @Override
    protected void onAlertEntryRemoved(AlertEntry alertEntry) {
        NotificationEntry entry = alertEntry.mEntry;
        entry.setHeadsUp(false);
        setEntryPinned((HeadsUpEntry) alertEntry, false /* isPinned */);
        EventLogTags.writeSysuiHeadsUpStatus(entry.getKey(), 0 /* visible */);
        mLogger.logNotificationActuallyRemoved(entry);
        for (OnHeadsUpChangedListener listener : mListeners) {
            listener.onHeadsUpStateChanged(entry, false);
        }
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
        for (String key : mAlertEntries.keySet()) {
            AlertEntry entry = getHeadsUpEntry(key);
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
        return (HeadsUpEntry) mAlertEntries.get(key);
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
        if (mAlertEntries.isEmpty()) {
            return null;
        }
        HeadsUpEntry topEntry = null;
        for (AlertEntry entry: mAlertEntries.values()) {
            if (topEntry == null || entry.compareTo(topEntry) < 0) {
                topEntry = (HeadsUpEntry) entry;
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
        for (AlertEntry entry: mAlertEntries.values()) {
            pw.print("  HeadsUpEntry="); pw.println(entry.mEntry);
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
        for (String key : mAlertEntries.keySet()) {
            AlertEntry entry = getHeadsUpEntry(key);
            if (entry.mEntry.isRowPinned()) {
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
        for (String key : mAlertEntries.keySet()) {
            HeadsUpEntry entry = getHeadsUpEntry(key);
            setEntryPinned(entry, false /* isPinned */);
            // maybe it got un sticky
            entry.updateEntry(false /* updatePostTime */, "unpinAll");

            // when the user unpinned all of HUNs by moving one HUN, all of HUNs should not stay
            // on the screen.
            if (userUnPinned && entry.mEntry != null) {
                if (entry.mEntry.mustStayOnScreen()) {
                    entry.mEntry.setHeadsUpIsVisible();
                }
            }
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
        AlertEntry aEntry = getHeadsUpEntry(a.getKey());
        AlertEntry bEntry = getHeadsUpEntry(b.getKey());
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

    @Override
    public boolean canRemoveImmediately(@NonNull String key) {
        HeadsUpEntry headsUpEntry = getHeadsUpEntry(key);
        if (headsUpEntry != null && headsUpEntry.mUserActionMayIndirectlyRemove) {
            return true;
        }
        return super.canRemoveImmediately(key);
    }

    @NonNull
    @Override
    protected HeadsUpEntry createAlertEntry() {
        return new HeadsUpEntry();
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
     * lifecycle automatically when created.
     */
    protected class HeadsUpEntry extends AlertEntry {
        public boolean mRemoteInputActive;
        public boolean mUserActionMayIndirectlyRemove;

        protected boolean mExpanded;
        protected boolean mWasUnpinned;

        @Override
        public boolean isSticky() {
            return (mEntry.isRowPinned() && mExpanded)
                    || mRemoteInputActive
                    || hasFullScreenIntent(mEntry);
        }

        @Override
        public boolean isStickyForSomeTime() {
            return mEntry.isStickyAndNotDemoted();
        }

        @Override
        public int compareTo(@NonNull AlertEntry alertEntry) {
            HeadsUpEntry headsUpEntry = (HeadsUpEntry) alertEntry;
            boolean isPinned = mEntry.isRowPinned();
            boolean otherPinned = headsUpEntry.mEntry.isRowPinned();
            if (isPinned && !otherPinned) {
                return -1;
            } else if (!isPinned && otherPinned) {
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

            return super.compareTo(headsUpEntry);
        }

        public void setExpanded(boolean expanded) {
            this.mExpanded = expanded;
        }

        @Override
        public void reset() {
            super.reset();
            mExpanded = false;
            mRemoteInputActive = false;
        }

        @Override
        protected long calculatePostTime() {
            // The actual post time will be just after the heads-up really slided in
            return super.calculatePostTime() + mTouchAcceptanceDelay;
        }

        /**
         * @return When the notification should auto-dismiss itself, based on
         * {@link SystemClock#elapsedRealtime()}
         */
        @Override
        protected long calculateFinishTime() {
            final long duration = getRecommendedHeadsUpTimeoutMs(
                    isStickyForSomeTime() ? mStickyForSomeTimeAutoDismissTime : mAutoDismissTime);

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
