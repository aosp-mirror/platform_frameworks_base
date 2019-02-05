/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.systemui.statusbar.notification;

import android.annotation.Nullable;
import android.app.Notification;
import android.content.Context;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.Dependency;
import com.android.systemui.Dumpable;
import com.android.systemui.statusbar.NotificationLifetimeExtender;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationUiAdjustment;
import com.android.systemui.statusbar.NotificationUpdateHandler;
import com.android.systemui.statusbar.notification.collection.NotificationData;
import com.android.systemui.statusbar.notification.collection.NotificationData.KeyguardEnvironment;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
import com.android.systemui.statusbar.notification.row.NotificationInflater;
import com.android.systemui.statusbar.notification.row.NotificationInflater.InflationFlag;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.util.leak.LeakDetector;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * NotificationEntryManager is responsible for the adding, removing, and updating of notifications.
 * It also handles tasks such as their inflation and their interaction with other
 * Notification.*Manager objects.
 */
public class NotificationEntryManager implements
        Dumpable,
        NotificationInflater.InflationCallback,
        NotificationUpdateHandler,
        VisualStabilityManager.Callback {
    private static final String TAG = "NotificationEntryMgr";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    @VisibleForTesting
    protected final HashMap<String, NotificationEntry> mPendingNotifications = new HashMap<>();

    private final Map<NotificationEntry, NotificationLifetimeExtender> mRetainedNotifications =
            new ArrayMap<>();

    // Lazily retrieved dependencies
    private NotificationRemoteInputManager mRemoteInputManager;
    private NotificationRowBinder mNotificationRowBinder;

    private NotificationPresenter mPresenter;
    private NotificationListenerService.RankingMap mLatestRankingMap;
    @VisibleForTesting
    protected NotificationData mNotificationData;

    @VisibleForTesting
    final ArrayList<NotificationLifetimeExtender> mNotificationLifetimeExtenders
            = new ArrayList<>();
    private final List<NotificationEntryListener> mNotificationEntryListeners = new ArrayList<>();

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("NotificationEntryManager state:");
        pw.print("  mPendingNotifications=");
        if (mPendingNotifications.size() == 0) {
            pw.println("null");
        } else {
            for (NotificationEntry entry : mPendingNotifications.values()) {
                pw.println(entry.notification);
            }
        }
        pw.println("  Lifetime-extended notifications:");
        if (mRetainedNotifications.isEmpty()) {
            pw.println("    None");
        } else {
            for (Map.Entry<NotificationEntry, NotificationLifetimeExtender> entry
                    : mRetainedNotifications.entrySet()) {
                pw.println("    " + entry.getKey().notification + " retained by "
                        + entry.getValue().getClass().getName());
            }
        }
    }

    public NotificationEntryManager(Context context) {
        mNotificationData = new NotificationData();
    }

    /** Adds a {@link NotificationEntryListener}. */
    public void addNotificationEntryListener(NotificationEntryListener listener) {
        mNotificationEntryListeners.add(listener);
    }

    /**
     * Our dependencies can have cyclic references, so some need to be lazy
     */
    private NotificationRemoteInputManager getRemoteInputManager() {
        if (mRemoteInputManager == null) {
            mRemoteInputManager = Dependency.get(NotificationRemoteInputManager.class);
        }
        return mRemoteInputManager;
    }

    private NotificationRowBinder getRowBinder() {
        if (mNotificationRowBinder == null) {
            mNotificationRowBinder = Dependency.get(NotificationRowBinder.class);
        }
        return mNotificationRowBinder;
    }

    // TODO: Remove this once we can always use a mocked row binder in our tests
    @VisibleForTesting
    void setRowBinder(NotificationRowBinder notificationRowBinder) {
        mNotificationRowBinder = notificationRowBinder;
    }

    public void setUpWithPresenter(NotificationPresenter presenter,
            NotificationListContainer listContainer,
            HeadsUpManager headsUpManager) {
        mPresenter = presenter;
        mNotificationData.setHeadsUpManager(headsUpManager);
    }

    /** Adds multiple {@link NotificationLifetimeExtender}s. */
    public void addNotificationLifetimeExtenders(List<NotificationLifetimeExtender> extenders) {
        for (NotificationLifetimeExtender extender : extenders) {
            addNotificationLifetimeExtender(extender);
        }
    }

    /** Adds a {@link NotificationLifetimeExtender}. */
    public void addNotificationLifetimeExtender(NotificationLifetimeExtender extender) {
        mNotificationLifetimeExtenders.add(extender);
        extender.setCallback(key -> removeNotification(key, mLatestRankingMap));
    }

    public NotificationData getNotificationData() {
        return mNotificationData;
    }

    @Override
    public void onReorderingAllowed() {
        updateNotifications();
    }

    public void performRemoveNotification(StatusBarNotification n) {
        final int rank = mNotificationData.getRank(n.getKey());
        final int count = mNotificationData.getActiveNotifications().size();
        NotificationVisibility.NotificationLocation location =
                NotificationLogger.getNotificationLocation(getNotificationData().get(n.getKey()));
        final NotificationVisibility nv = NotificationVisibility.obtain(n.getKey(), rank, count,
                true, location);
        removeNotificationInternal(
                n.getKey(), null, nv, false /* forceRemove */, true /* removedByUser */);
    }

    private void abortExistingInflation(String key) {
        if (mPendingNotifications.containsKey(key)) {
            NotificationEntry entry = mPendingNotifications.get(key);
            entry.abortTask();
            mPendingNotifications.remove(key);
        }
        NotificationEntry addedEntry = mNotificationData.get(key);
        if (addedEntry != null) {
            addedEntry.abortTask();
        }
    }

    /**
     * Cancel this notification and tell the StatusBarManagerService / NotificationManagerService
     * about the failure.
     *
     * WARNING: this will call back into us.  Don't hold any locks.
     */
    @Override
    public void handleInflationException(StatusBarNotification n, Exception e) {
        removeNotificationInternal(
                n.getKey(), null, null, true /* forceRemove */, false /* removedByUser */);
        for (NotificationEntryListener listener : mNotificationEntryListeners) {
            listener.onInflationError(n, e);
        }
    }

    @Override
    public void onAsyncInflationFinished(NotificationEntry entry,
            @InflationFlag int inflatedFlags) {
        mPendingNotifications.remove(entry.key);
        // If there was an async task started after the removal, we don't want to add it back to
        // the list, otherwise we might get leaks.
        if (!entry.isRowRemoved()) {
            boolean isNew = mNotificationData.get(entry.key) == null;
            if (isNew) {
                for (NotificationEntryListener listener : mNotificationEntryListeners) {
                    listener.onEntryInflated(entry, inflatedFlags);
                }
                mNotificationData.add(entry);
                for (NotificationEntryListener listener : mNotificationEntryListeners) {
                    listener.onBeforeNotificationAdded(entry);
                }
                updateNotifications();
                for (NotificationEntryListener listener : mNotificationEntryListeners) {
                    listener.onNotificationAdded(entry);
                }
            } else {
                for (NotificationEntryListener listener : mNotificationEntryListeners) {
                    listener.onEntryReinflated(entry);
                }
            }
        }
        entry.setLowPriorityStateUpdated(false);
    }

    @Override
    public void removeNotification(String key, NotificationListenerService.RankingMap ranking) {
        removeNotificationInternal(
                key, ranking, null, false /* forceRemove */, false /* removedByUser */);
    }

    private void removeNotificationInternal(
            String key,
            @Nullable NotificationListenerService.RankingMap ranking,
            @Nullable NotificationVisibility visibility,
            boolean forceRemove,
            boolean removedByUser) {
        final NotificationEntry entry = mNotificationData.get(key);

        abortExistingInflation(key);

        boolean lifetimeExtended = false;

        if (entry != null) {
            // If a manager needs to keep the notification around for whatever reason, we
            // keep the notification
            if (!forceRemove) {
                for (NotificationLifetimeExtender extender : mNotificationLifetimeExtenders) {
                    if (extender.shouldExtendLifetime(entry)) {
                        mLatestRankingMap = ranking;
                        extendLifetime(entry, extender);
                        lifetimeExtended = true;
                        break;
                    }
                }
            }

            if (!lifetimeExtended) {
                // At this point, we are guaranteed the notification will be removed

                // Ensure any managers keeping the lifetime extended stop managing the entry
                cancelLifetimeExtension(entry);

                if (entry.rowExists()) {
                    entry.removeRow();
                }

                // Let's remove the children if this was a summary
                handleGroupSummaryRemoved(key);

                mNotificationData.remove(key, ranking);
                updateNotifications();
                Dependency.get(LeakDetector.class).trackGarbage(entry);

                for (NotificationEntryListener listener : mNotificationEntryListeners) {
                    listener.onEntryRemoved(entry, visibility, removedByUser);
                }
            }
        }
    }

    /**
     * Ensures that the group children are cancelled immediately when the group summary is cancelled
     * instead of waiting for the notification manager to send all cancels. Otherwise this could
     * lead to flickers.
     *
     * This also ensures that the animation looks nice and only consists of a single disappear
     * animation instead of multiple.
     *  @param key the key of the notification was removed
     *
     */
    private void handleGroupSummaryRemoved(String key) {
        NotificationEntry entry = mNotificationData.get(key);
        if (entry != null && entry.rowExists() && entry.isSummaryWithChildren()) {
            if (entry.notification.getOverrideGroupKey() != null && !entry.isRowDismissed()) {
                // We don't want to remove children for autobundled notifications as they are not
                // always cancelled. We only remove them if they were dismissed by the user.
                return;
            }
            List<NotificationEntry> childEntries = entry.getChildren();
            if (childEntries == null) {
                return;
            }
            for (int i = 0; i < childEntries.size(); i++) {
                NotificationEntry childEntry = childEntries.get(i);
                boolean isForeground = (entry.notification.getNotification().flags
                        & Notification.FLAG_FOREGROUND_SERVICE) != 0;
                boolean keepForReply =
                        getRemoteInputManager().shouldKeepForRemoteInputHistory(childEntry)
                        || getRemoteInputManager().shouldKeepForSmartReplyHistory(childEntry);
                if (isForeground || keepForReply) {
                    // the child is a foreground service notification which we can't remove or it's
                    // a child we're keeping around for reply!
                    continue;
                }
                entry.setKeepInParent(true);
                // we need to set this state earlier as otherwise we might generate some weird
                // animations
                entry.removeRow();
            }
        }
    }

    private void addNotificationInternal(StatusBarNotification notification,
            NotificationListenerService.RankingMap rankingMap) throws InflationException {
        String key = notification.getKey();
        if (DEBUG) {
            Log.d(TAG, "addNotification key=" + key);
        }

        mNotificationData.updateRanking(rankingMap);
        NotificationListenerService.Ranking ranking = new NotificationListenerService.Ranking();
        rankingMap.getRanking(key, ranking);

        NotificationEntry entry = new NotificationEntry(notification, ranking);

        Dependency.get(LeakDetector.class).trackInstance(entry);
        // Construct the expanded view.
        getRowBinder().inflateViews(entry, () -> performRemoveNotification(notification),
                mNotificationData.get(entry.key) != null);

        abortExistingInflation(key);

        mPendingNotifications.put(key, entry);
        for (NotificationEntryListener listener : mNotificationEntryListeners) {
            listener.onPendingEntryAdded(entry);
        }
    }

    @Override
    public void addNotification(StatusBarNotification notification,
            NotificationListenerService.RankingMap ranking) {
        try {
            addNotificationInternal(notification, ranking);
        } catch (InflationException e) {
            handleInflationException(notification, e);
        }
    }

    private void updateNotificationInternal(StatusBarNotification notification,
            NotificationListenerService.RankingMap ranking) throws InflationException {
        if (DEBUG) Log.d(TAG, "updateNotification(" + notification + ")");

        final String key = notification.getKey();
        abortExistingInflation(key);
        NotificationEntry entry = mNotificationData.get(key);
        if (entry == null) {
            return;
        }

        // Notification is updated so it is essentially re-added and thus alive again.  Don't need
        // to keep its lifetime extended.
        cancelLifetimeExtension(entry);

        mNotificationData.update(entry, ranking, notification);

        getRowBinder().inflateViews(entry, () -> performRemoveNotification(notification),
                mNotificationData.get(entry.key) != null);

        for (NotificationEntryListener listener : mNotificationEntryListeners) {
            listener.onPreEntryUpdated(entry);
        }

        updateNotifications();

        if (DEBUG) {
            // Is this for you?
            boolean isForCurrentUser = Dependency.get(KeyguardEnvironment.class)
                    .isNotificationForCurrentProfiles(notification);
            Log.d(TAG, "notification is " + (isForCurrentUser ? "" : "not ") + "for you");
        }

        for (NotificationEntryListener listener : mNotificationEntryListeners) {
            listener.onPostEntryUpdated(entry);
        }
    }

    @Override
    public void updateNotification(StatusBarNotification notification,
            NotificationListenerService.RankingMap ranking) {
        try {
            updateNotificationInternal(notification, ranking);
        } catch (InflationException e) {
            handleInflationException(notification, e);
        }
    }

    public void updateNotifications() {
        mNotificationData.filterAndSort();
        if (mPresenter != null) {
            mPresenter.updateNotificationViews();
        }
    }

    public void updateNotificationRanking(NotificationListenerService.RankingMap rankingMap) {
        List<NotificationEntry> entries = new ArrayList<>();
        entries.addAll(mNotificationData.getActiveNotifications());
        entries.addAll(mPendingNotifications.values());

        // Has a copy of the current UI adjustments.
        ArrayMap<String, NotificationUiAdjustment> oldAdjustments = new ArrayMap<>();
        ArrayMap<String, Integer> oldImportances = new ArrayMap<>();
        for (NotificationEntry entry : entries) {
            NotificationUiAdjustment adjustment =
                    NotificationUiAdjustment.extractFromNotificationEntry(entry);
            oldAdjustments.put(entry.key, adjustment);
            oldImportances.put(entry.key, entry.importance);
        }

        // Populate notification entries from the new rankings.
        mNotificationData.updateRanking(rankingMap);
        updateRankingOfPendingNotifications(rankingMap);

        // By comparing the old and new UI adjustments, reinflate the view accordingly.
        for (NotificationEntry entry : entries) {
            getRowBinder().onNotificationRankingUpdated(
                    entry,
                    oldImportances.get(entry.key),
                    oldAdjustments.get(entry.key),
                    NotificationUiAdjustment.extractFromNotificationEntry(entry),
                    mNotificationData.get(entry.key) != null);
        }

        updateNotifications();
    }

    private void updateRankingOfPendingNotifications(
            @Nullable NotificationListenerService.RankingMap rankingMap) {
        if (rankingMap == null) {
            return;
        }
        NotificationListenerService.Ranking tmpRanking = new NotificationListenerService.Ranking();
        for (NotificationEntry pendingNotification : mPendingNotifications.values()) {
            rankingMap.getRanking(pendingNotification.key, tmpRanking);
            pendingNotification.populateFromRanking(tmpRanking);
        }
    }

    /**
     * @return An iterator for all "pending" notifications. Pending notifications are newly-posted
     * notifications whose views have not yet been inflated. In general, the system pretends like
     * these don't exist, although there are a couple exceptions.
     */
    public Iterable<NotificationEntry> getPendingNotificationsIterator() {
        return mPendingNotifications.values();
    }

    private void extendLifetime(NotificationEntry entry, NotificationLifetimeExtender extender) {
        NotificationLifetimeExtender activeExtender = mRetainedNotifications.get(entry);
        if (activeExtender != null && activeExtender != extender) {
            activeExtender.setShouldManageLifetime(entry, false);
        }
        mRetainedNotifications.put(entry, extender);
        extender.setShouldManageLifetime(entry, true);
    }

    private void cancelLifetimeExtension(NotificationEntry entry) {
        NotificationLifetimeExtender activeExtender = mRetainedNotifications.remove(entry);
        if (activeExtender != null) {
            activeExtender.setShouldManageLifetime(entry, false);
        }
    }
}
