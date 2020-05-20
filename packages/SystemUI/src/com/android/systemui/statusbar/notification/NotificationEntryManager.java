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

import static android.service.notification.NotificationListenerService.REASON_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_ERROR;

import static com.android.systemui.statusbar.notification.collection.NotifCollection.REASON_UNKNOWN;
import static com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.InflationCallback;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.Dumpable;
import com.android.systemui.statusbar.FeatureFlags;
import com.android.systemui.statusbar.NotificationLifetimeExtender;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.NotificationListener.NotificationHandler;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationRemoveInterceptor;
import com.android.systemui.statusbar.NotificationUiAdjustment;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationRankingManager;
import com.android.systemui.statusbar.notification.collection.inflation.NotificationRowBinder;
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.statusbar.notification.dagger.NotificationsModule;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.util.Assert;
import com.android.systemui.util.leak.LeakDetector;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dagger.Lazy;

/**
 * NotificationEntryManager is responsible for the adding, removing, and updating of
 * {@link NotificationEntry}s. It also handles tasks such as their inflation and their interaction
 * with other Notification.*Manager objects.
 *
 * We track notification entries through this lifecycle:
 *      1. Pending
 *      2. Active
 *      3. Sorted / filtered (visible)
 *
 * Every entry spends some amount of time in the pending state, while it is being inflated. Once
 * inflated, an entry moves into the active state, where it _could_ potentially be shown to the
 * user. After an entry makes its way into the active state, we sort and filter the entire set to
 * repopulate the visible set.
 *
 * There are a few different things that other classes may be interested in, and most of them
 * involve the current set of notifications. Here's a brief overview of things you may want to know:
 * @see #getVisibleNotifications() for the visible set
 * @see #getActiveNotificationUnfiltered(String) to check if a key exists
 * @see #getPendingNotificationsIterator() for an iterator over the pending notifications
 * @see #getPendingOrActiveNotif(String) to find a notification exists for that key in any list
 * @see #getActiveNotificationsForCurrentUser() to see every notification that the current user owns
 */
public class NotificationEntryManager implements
        CommonNotifCollection,
        Dumpable,
        VisualStabilityManager.Callback {
    private static final String TAG = "NotificationEntryMgr";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    /**
     * Used when a notification is removed and it doesn't have a reason that maps to one of the
     * reasons defined in NotificationListenerService
     * (e.g. {@link NotificationListenerService#REASON_CANCEL})
     */
    public static final int UNDEFINED_DISMISS_REASON = 0;

    private final Set<NotificationEntry> mAllNotifications = new ArraySet<>();
    private final Set<NotificationEntry> mReadOnlyAllNotifications =
            Collections.unmodifiableSet(mAllNotifications);

    /** Pending notifications are ones awaiting inflation */
    @VisibleForTesting
    protected final HashMap<String, NotificationEntry> mPendingNotifications = new HashMap<>();
    /**
     * Active notifications have been inflated / prepared and could become visible, but may get
     * filtered out if for instance they are not for the current user
     */
    private final ArrayMap<String, NotificationEntry> mActiveNotifications = new ArrayMap<>();
    @VisibleForTesting
    /** This is the list of "active notifications for this user in this context" */
    protected final ArrayList<NotificationEntry> mSortedAndFiltered = new ArrayList<>();
    private final List<NotificationEntry> mReadOnlyNotifications =
            Collections.unmodifiableList(mSortedAndFiltered);

    private final Map<NotificationEntry, NotificationLifetimeExtender> mRetainedNotifications =
            new ArrayMap<>();

    private final NotificationEntryManagerLogger mLogger;

    // Lazily retrieved dependencies
    private final Lazy<NotificationRowBinder> mNotificationRowBinderLazy;
    private final Lazy<NotificationRemoteInputManager> mRemoteInputManagerLazy;
    private final LeakDetector mLeakDetector;
    private final List<NotifCollectionListener> mNotifCollectionListeners = new ArrayList<>();

    private final KeyguardEnvironment mKeyguardEnvironment;
    private final NotificationGroupManager mGroupManager;
    private final NotificationRankingManager mRankingManager;
    private final FeatureFlags mFeatureFlags;
    private final ForegroundServiceDismissalFeatureController mFgsFeatureController;

    private NotificationPresenter mPresenter;
    private RankingMap mLatestRankingMap;

    @VisibleForTesting
    final ArrayList<NotificationLifetimeExtender> mNotificationLifetimeExtenders
            = new ArrayList<>();
    private final List<NotificationEntryListener> mNotificationEntryListeners = new ArrayList<>();
    private final List<NotificationRemoveInterceptor> mRemoveInterceptors = new ArrayList<>();

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("NotificationEntryManager state:");
        pw.println("  mAllNotifications=");
        if (mAllNotifications.size() == 0) {
            pw.println("null");
        } else {
            int i = 0;
            for (NotificationEntry entry : mAllNotifications) {
                dumpEntry(pw, "  ", i, entry);
                i++;
            }
        }
        pw.print("  mPendingNotifications=");
        if (mPendingNotifications.size() == 0) {
            pw.println("null");
        } else {
            for (NotificationEntry entry : mPendingNotifications.values()) {
                pw.println(entry.getSbn());
            }
        }
        pw.println("  Remove interceptors registered:");
        for (NotificationRemoveInterceptor interceptor : mRemoveInterceptors) {
            pw.println("    " + interceptor.getClass().getSimpleName());
        }
        pw.println("  Lifetime extenders registered:");
        for (NotificationLifetimeExtender extender : mNotificationLifetimeExtenders) {
            pw.println("    " + extender.getClass().getSimpleName());
        }
        pw.println("  Lifetime-extended notifications:");
        if (mRetainedNotifications.isEmpty()) {
            pw.println("    None");
        } else {
            for (Map.Entry<NotificationEntry, NotificationLifetimeExtender> entry
                    : mRetainedNotifications.entrySet()) {
                pw.println("    " + entry.getKey().getSbn() + " retained by "
                        + entry.getValue().getClass().getName());
            }
        }
    }

    /**
     * Injected constructor. See {@link NotificationsModule}.
     */
    public NotificationEntryManager(
            NotificationEntryManagerLogger logger,
            NotificationGroupManager groupManager,
            NotificationRankingManager rankingManager,
            KeyguardEnvironment keyguardEnvironment,
            FeatureFlags featureFlags,
            Lazy<NotificationRowBinder> notificationRowBinderLazy,
            Lazy<NotificationRemoteInputManager> notificationRemoteInputManagerLazy,
            LeakDetector leakDetector,
            ForegroundServiceDismissalFeatureController fgsFeatureController) {
        mLogger = logger;
        mGroupManager = groupManager;
        mRankingManager = rankingManager;
        mKeyguardEnvironment = keyguardEnvironment;
        mFeatureFlags = featureFlags;
        mNotificationRowBinderLazy = notificationRowBinderLazy;
        mRemoteInputManagerLazy = notificationRemoteInputManagerLazy;
        mLeakDetector = leakDetector;
        mFgsFeatureController = fgsFeatureController;
    }

    /** Once called, the NEM will start processing notification events from system server. */
    public void attach(NotificationListener notificationListener) {
        notificationListener.addNotificationHandler(mNotifListener);
    }

    /** Adds a {@link NotificationEntryListener}. */
    public void addNotificationEntryListener(NotificationEntryListener listener) {
        mNotificationEntryListeners.add(listener);
    }

    /**
     * Removes a {@link NotificationEntryListener} previously registered via
     * {@link #addNotificationEntryListener(NotificationEntryListener)}.
     */
    public void removeNotificationEntryListener(NotificationEntryListener listener) {
        mNotificationEntryListeners.remove(listener);
    }

    /** Add a {@link NotificationRemoveInterceptor}. */
    public void addNotificationRemoveInterceptor(NotificationRemoveInterceptor interceptor) {
        mRemoveInterceptors.add(interceptor);
    }

    /** Remove a {@link NotificationRemoveInterceptor} */
    public void removeNotificationRemoveInterceptor(NotificationRemoveInterceptor interceptor) {
        mRemoveInterceptors.remove(interceptor);
    }

    public void setUpWithPresenter(NotificationPresenter presenter) {
        mPresenter = presenter;
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
        extender.setCallback(key -> removeNotification(key, mLatestRankingMap,
                UNDEFINED_DISMISS_REASON));
    }

    @Override
    public void onChangeAllowed() {
        updateNotifications("reordering is now allowed");
    }

    /**
     * Requests a notification to be removed.
     *
     * @param n the notification to remove.
     * @param reason why it is being removed e.g. {@link NotificationListenerService#REASON_CANCEL},
     *               or 0 if unknown.
     */
    public void performRemoveNotification(StatusBarNotification n, int reason) {
        final NotificationVisibility nv = obtainVisibility(n.getKey());
        removeNotificationInternal(
                n.getKey(), null, nv, false /* forceRemove */, true /* removedByUser */,
                reason);
    }

    private NotificationVisibility obtainVisibility(String key) {
        NotificationEntry e = mActiveNotifications.get(key);
        final int rank;
        if (e != null) {
            rank = e.getRanking().getRank();
        } else {
            rank = 0;
        }

        final int count = mActiveNotifications.size();
        NotificationVisibility.NotificationLocation location =
                NotificationLogger.getNotificationLocation(getActiveNotificationUnfiltered(key));
        return NotificationVisibility.obtain(key, rank, count, true, location);
    }

    private void abortExistingInflation(String key, String reason) {
        if (mPendingNotifications.containsKey(key)) {
            NotificationEntry entry = mPendingNotifications.get(key);
            entry.abortTask();
            mPendingNotifications.remove(key);
            for (NotifCollectionListener listener : mNotifCollectionListeners) {
                listener.onEntryCleanUp(entry);
            }
            mLogger.logInflationAborted(key, "pending", reason);
        }
        NotificationEntry addedEntry = getActiveNotificationUnfiltered(key);
        if (addedEntry != null) {
            addedEntry.abortTask();
            mLogger.logInflationAborted(key, "active", reason);
        }
    }

    /**
     * Cancel this notification and tell the StatusBarManagerService / NotificationManagerService
     * about the failure.
     *
     * WARNING: this will call back into us.  Don't hold any locks.
     */
    private void handleInflationException(StatusBarNotification n, Exception e) {
        removeNotificationInternal(
                n.getKey(), null, null, true /* forceRemove */, false /* removedByUser */,
                REASON_ERROR);
        for (NotificationEntryListener listener : mNotificationEntryListeners) {
            listener.onInflationError(n, e);
        }
    }

    private final InflationCallback mInflationCallback = new InflationCallback() {
        @Override
        public void handleInflationException(NotificationEntry entry, Exception e) {
            NotificationEntryManager.this.handleInflationException(entry.getSbn(), e);
        }

        @Override
        public void onAsyncInflationFinished(NotificationEntry entry) {
            mPendingNotifications.remove(entry.getKey());
            // If there was an async task started after the removal, we don't want to add it back to
            // the list, otherwise we might get leaks.
            if (!entry.isRowRemoved()) {
                boolean isNew = getActiveNotificationUnfiltered(entry.getKey()) == null;
                mLogger.logNotifInflated(entry.getKey(), isNew);
                if (isNew) {
                    for (NotificationEntryListener listener : mNotificationEntryListeners) {
                        listener.onEntryInflated(entry);
                    }
                    addActiveNotification(entry);
                    updateNotifications("onAsyncInflationFinished");
                    for (NotificationEntryListener listener : mNotificationEntryListeners) {
                        listener.onNotificationAdded(entry);
                    }
                } else {
                    for (NotificationEntryListener listener : mNotificationEntryListeners) {
                        listener.onEntryReinflated(entry);
                    }
                }
            }
        }
    };

    private final NotificationHandler mNotifListener = new NotificationHandler() {
        @Override
        public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
            final boolean isUpdateToInflatedNotif = mActiveNotifications.containsKey(sbn.getKey());
            if (isUpdateToInflatedNotif) {
                updateNotification(sbn, rankingMap);
            } else {
                addNotification(sbn, rankingMap);
            }
        }

        @Override
        public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap) {
            removeNotification(sbn.getKey(), rankingMap, UNDEFINED_DISMISS_REASON);
        }

        @Override
        public void onNotificationRemoved(
                StatusBarNotification sbn,
                RankingMap rankingMap,
                int reason) {
            removeNotification(sbn.getKey(), rankingMap, reason);
        }

        @Override
        public void onNotificationRankingUpdate(RankingMap rankingMap) {
            updateNotificationRanking(rankingMap);
        }
    };

    /**
     * Equivalent to the old NotificationData#add
     * @param entry - an entry which is prepared for display
     */
    private void addActiveNotification(NotificationEntry entry) {
        Assert.isMainThread();

        mActiveNotifications.put(entry.getKey(), entry);
        mGroupManager.onEntryAdded(entry);
        updateRankingAndSort(mRankingManager.getRankingMap(), "addEntryInternalInternal");
    }

    /**
     * Available so that tests can directly manipulate the list of active notifications easily
     *
     * @param entry the entry to add directly to the visible notification map
     */
    @VisibleForTesting
    public void addActiveNotificationForTest(NotificationEntry entry) {
        mActiveNotifications.put(entry.getKey(), entry);
        mGroupManager.onEntryAdded(entry);

        reapplyFilterAndSort("addVisibleNotification");
    }


    public void removeNotification(String key, RankingMap ranking,
            int reason) {
        removeNotificationInternal(key, ranking, obtainVisibility(key), false /* forceRemove */,
                false /* removedByUser */, reason);
    }

    private void removeNotificationInternal(
            String key,
            @Nullable RankingMap ranking,
            @Nullable NotificationVisibility visibility,
            boolean forceRemove,
            boolean removedByUser,
            int reason) {

        final NotificationEntry entry = getActiveNotificationUnfiltered(key);

        for (NotificationRemoveInterceptor interceptor : mRemoveInterceptors) {
            if (interceptor.onNotificationRemoveRequested(key, entry, reason)) {
                // Remove intercepted; log and skip
                mLogger.logRemovalIntercepted(key);
                return;
            }
        }

        boolean lifetimeExtended = false;

        // Notification was canceled before it got inflated
        if (entry == null) {
            NotificationEntry pendingEntry = mPendingNotifications.get(key);
            if (pendingEntry != null) {
                for (NotificationLifetimeExtender extender : mNotificationLifetimeExtenders) {
                    if (extender.shouldExtendLifetimeForPendingNotification(pendingEntry)) {
                        extendLifetime(pendingEntry, extender);
                        lifetimeExtended = true;
                        mLogger.logLifetimeExtended(key, extender.getClass().getName(), "pending");
                    }
                }
                if (!lifetimeExtended) {
                    // At this point, we are guaranteed the notification will be removed
                    abortExistingInflation(key, "removeNotification");
                    mAllNotifications.remove(pendingEntry);
                    mLeakDetector.trackGarbage(pendingEntry);
                }
            }
        } else {
            // If a manager needs to keep the notification around for whatever reason, we
            // keep the notification
            boolean entryDismissed = entry.isRowDismissed();
            if (!forceRemove && !entryDismissed) {
                for (NotificationLifetimeExtender extender : mNotificationLifetimeExtenders) {
                    if (extender.shouldExtendLifetime(entry)) {
                        mLatestRankingMap = ranking;
                        extendLifetime(entry, extender);
                        lifetimeExtended = true;
                        mLogger.logLifetimeExtended(key, extender.getClass().getName(), "active");
                        break;
                    }
                }
            }

            if (!lifetimeExtended) {
                // At this point, we are guaranteed the notification will be removed
                abortExistingInflation(key, "removeNotification");
                mAllNotifications.remove(entry);

                // Ensure any managers keeping the lifetime extended stop managing the entry
                cancelLifetimeExtension(entry);

                if (entry.rowExists()) {
                    entry.removeRow();
                }

                // Let's remove the children if this was a summary
                handleGroupSummaryRemoved(key);
                removeVisibleNotification(key);
                updateNotifications("removeNotificationInternal");
                removedByUser |= entryDismissed;

                mLogger.logNotifRemoved(entry.getKey(), removedByUser);
                for (NotificationEntryListener listener : mNotificationEntryListeners) {
                    listener.onEntryRemoved(entry, visibility, removedByUser, reason);
                }
                for (NotifCollectionListener listener : mNotifCollectionListeners) {
                    // NEM doesn't have a good knowledge of reasons so defaulting to unknown.
                    listener.onEntryRemoved(entry, REASON_UNKNOWN);
                }
                for (NotifCollectionListener listener : mNotifCollectionListeners) {
                    listener.onEntryCleanUp(entry);
                }
                mLeakDetector.trackGarbage(entry);
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
        NotificationEntry entry = getActiveNotificationUnfiltered(key);
        if (entry != null && entry.rowExists() && entry.isSummaryWithChildren()) {
            if (entry.getSbn().getOverrideGroupKey() != null && !entry.isRowDismissed()) {
                // We don't want to remove children for autobundled notifications as they are not
                // always cancelled. We only remove them if they were dismissed by the user.
                return;
            }
            List<NotificationEntry> childEntries = entry.getAttachedNotifChildren();
            if (childEntries == null) {
                return;
            }
            for (int i = 0; i < childEntries.size(); i++) {
                NotificationEntry childEntry = childEntries.get(i);
                boolean isForeground = (entry.getSbn().getNotification().flags
                        & Notification.FLAG_FOREGROUND_SERVICE) != 0;
                boolean keepForReply =
                        mRemoteInputManagerLazy.get().shouldKeepForRemoteInputHistory(childEntry)
                        || mRemoteInputManagerLazy.get().shouldKeepForSmartReplyHistory(childEntry);
                if (isForeground || keepForReply) {
                    // the child is a foreground service notification which we can't remove or it's
                    // a child we're keeping around for reply!
                    continue;
                }
                childEntry.setKeepInParent(true);
                // we need to set this state earlier as otherwise we might generate some weird
                // animations
                childEntry.removeRow();
            }
        }
    }

    private void addNotificationInternal(
            StatusBarNotification notification,
            RankingMap rankingMap) throws InflationException {
        String key = notification.getKey();
        if (DEBUG) {
            Log.d(TAG, "addNotification key=" + key);
        }

        updateRankingAndSort(rankingMap, "addNotificationInternal");

        Ranking ranking = new Ranking();
        rankingMap.getRanking(key, ranking);

        NotificationEntry entry = mPendingNotifications.get(key);
        if (entry != null) {
            entry.setSbn(notification);
        } else {
            entry = new NotificationEntry(
                    notification,
                    ranking,
                    mFgsFeatureController.isForegroundServiceDismissalEnabled(),
                    SystemClock.uptimeMillis());
            mAllNotifications.add(entry);
            mLeakDetector.trackInstance(entry);

            for (NotifCollectionListener listener : mNotifCollectionListeners) {
                listener.onEntryInit(entry);
            }
        }

        for (NotifCollectionListener listener : mNotifCollectionListeners) {
            listener.onEntryBind(entry, notification);
        }

        // Construct the expanded view.
        if (!mFeatureFlags.isNewNotifPipelineRenderingEnabled()) {
            mNotificationRowBinderLazy.get()
                    .inflateViews(
                            entry,
                            () -> performRemoveNotification(notification, REASON_CANCEL),
                            mInflationCallback);
        }

        mPendingNotifications.put(key, entry);
        mLogger.logNotifAdded(entry.getKey());
        for (NotificationEntryListener listener : mNotificationEntryListeners) {
            listener.onPendingEntryAdded(entry);
        }
        for (NotifCollectionListener listener : mNotifCollectionListeners) {
            listener.onEntryAdded(entry);
        }
        for (NotifCollectionListener listener : mNotifCollectionListeners) {
            listener.onRankingApplied();
        }
    }

    public void addNotification(StatusBarNotification notification, RankingMap ranking) {
        try {
            addNotificationInternal(notification, ranking);
        } catch (InflationException e) {
            handleInflationException(notification, e);
        }
    }

    private void updateNotificationInternal(StatusBarNotification notification,
            RankingMap ranking) throws InflationException {
        if (DEBUG) Log.d(TAG, "updateNotification(" + notification + ")");

        final String key = notification.getKey();
        abortExistingInflation(key, "updateNotification");
        NotificationEntry entry = getActiveNotificationUnfiltered(key);
        if (entry == null) {
            return;
        }

        // Notification is updated so it is essentially re-added and thus alive again.  Don't need
        // to keep its lifetime extended.
        cancelLifetimeExtension(entry);

        updateRankingAndSort(ranking, "updateNotificationInternal");
        StatusBarNotification oldSbn = entry.getSbn();
        entry.setSbn(notification);
        for (NotifCollectionListener listener : mNotifCollectionListeners) {
            listener.onEntryBind(entry, notification);
        }
        mGroupManager.onEntryUpdated(entry, oldSbn);

        mLogger.logNotifUpdated(entry.getKey());
        for (NotificationEntryListener listener : mNotificationEntryListeners) {
            listener.onPreEntryUpdated(entry);
        }
        for (NotifCollectionListener listener : mNotifCollectionListeners) {
            listener.onEntryUpdated(entry);
        }

        if (!mFeatureFlags.isNewNotifPipelineRenderingEnabled()) {
            mNotificationRowBinderLazy.get()
                    .inflateViews(
                            entry,
                            () -> performRemoveNotification(notification, REASON_CANCEL),
                            mInflationCallback);
        }

        updateNotifications("updateNotificationInternal");

        if (DEBUG) {
            // Is this for you?
            boolean isForCurrentUser = mKeyguardEnvironment
                    .isNotificationForCurrentProfiles(notification);
            Log.d(TAG, "notification is " + (isForCurrentUser ? "" : "not ") + "for you");
        }

        for (NotificationEntryListener listener : mNotificationEntryListeners) {
            listener.onPostEntryUpdated(entry);
        }
        for (NotifCollectionListener listener : mNotifCollectionListeners) {
            listener.onRankingApplied();
        }
    }

    public void updateNotification(StatusBarNotification notification, RankingMap ranking) {
        try {
            updateNotificationInternal(notification, ranking);
        } catch (InflationException e) {
            handleInflationException(notification, e);
        }
    }

    /**
     * Update the notifications
     * @param reason why the notifications are updating
     */
    public void updateNotifications(String reason) {
        reapplyFilterAndSort(reason);
        if (mPresenter != null && !mFeatureFlags.isNewNotifPipelineRenderingEnabled()) {
            mPresenter.updateNotificationViews(reason);
        }
    }

    public void updateNotificationRanking(RankingMap rankingMap) {
        List<NotificationEntry> entries = new ArrayList<>();
        entries.addAll(getVisibleNotifications());
        entries.addAll(mPendingNotifications.values());

        // Has a copy of the current UI adjustments.
        ArrayMap<String, NotificationUiAdjustment> oldAdjustments = new ArrayMap<>();
        ArrayMap<String, Integer> oldImportances = new ArrayMap<>();
        for (NotificationEntry entry : entries) {
            NotificationUiAdjustment adjustment =
                    NotificationUiAdjustment.extractFromNotificationEntry(entry);
            oldAdjustments.put(entry.getKey(), adjustment);
            oldImportances.put(entry.getKey(), entry.getImportance());
        }

        // Populate notification entries from the new rankings.
        updateRankingAndSort(rankingMap, "updateNotificationRanking");
        updateRankingOfPendingNotifications(rankingMap);

        // By comparing the old and new UI adjustments, reinflate the view accordingly.
        for (NotificationEntry entry : entries) {
            mNotificationRowBinderLazy.get()
                    .onNotificationRankingUpdated(
                            entry,
                            oldImportances.get(entry.getKey()),
                            oldAdjustments.get(entry.getKey()),
                            NotificationUiAdjustment.extractFromNotificationEntry(entry),
                            mInflationCallback);
        }

        updateNotifications("updateNotificationRanking");

        for (NotificationEntryListener listener : mNotificationEntryListeners) {
            listener.onNotificationRankingUpdated(rankingMap);
        }
        for (NotifCollectionListener listener : mNotifCollectionListeners) {
            listener.onRankingUpdate(rankingMap);
        }
        for (NotifCollectionListener listener : mNotifCollectionListeners) {
            listener.onRankingApplied();
        }
    }

    private void updateRankingOfPendingNotifications(@Nullable RankingMap rankingMap) {
        if (rankingMap == null) {
            return;
        }
        for (NotificationEntry pendingNotification : mPendingNotifications.values()) {
            Ranking ranking = new Ranking();
            if (rankingMap.getRanking(pendingNotification.getKey(), ranking)) {
                pendingNotification.setRanking(ranking);
            }
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

    /**
     * Use this method to retrieve a notification entry that has been prepared for presentation.
     * Note that the notification may be filtered out and never shown to the user.
     *
     * @see #getVisibleNotifications() for the currently sorted and filtered list
     *
     * @return a {@link NotificationEntry} if it has been prepared, else null
     */
    public NotificationEntry getActiveNotificationUnfiltered(String key) {
        return mActiveNotifications.get(key);
    }

    /**
     * Gets the pending or visible notification entry with the given key. Returns null if
     * notification doesn't exist.
     */
    public NotificationEntry getPendingOrActiveNotif(String key) {
        if (mPendingNotifications.containsKey(key)) {
            return mPendingNotifications.get(key);
        } else {
            return mActiveNotifications.get(key);
        }
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

    /*
     * -----
     * Annexed from NotificationData below:
     * Some of these methods may be redundant but require some reworking to remove. For now
     * we'll try to keep the behavior the same and can simplify these interfaces in another pass
     */

    /** Internalization of NotificationData#remove */
    private void removeVisibleNotification(String key) {
        // no need to synchronize if we're on the main thread dawg
        Assert.isMainThread();

        NotificationEntry removed = mActiveNotifications.remove(key);

        if (removed == null) return;
        mGroupManager.onEntryRemoved(removed);
    }

    /** @return list of active notifications filtered for the current user */
    public List<NotificationEntry> getActiveNotificationsForCurrentUser() {
        Assert.isMainThread();
        ArrayList<NotificationEntry> filtered = new ArrayList<>();

        final int len = mActiveNotifications.size();
        for (int i = 0; i < len; i++) {
            NotificationEntry entry = mActiveNotifications.valueAt(i);
            final StatusBarNotification sbn = entry.getSbn();
            if (!mKeyguardEnvironment.isNotificationForCurrentProfiles(sbn)) {
                continue;
            }
            filtered.add(entry);
        }

        return filtered;
    }

    //TODO: Get rid of this in favor of NotificationUpdateHandler#updateNotificationRanking
    /**
     * @param rankingMap the {@link RankingMap} to apply to the current notification list
     * @param reason the reason for calling this method, which will be logged
     */
    public void updateRanking(RankingMap rankingMap, String reason) {
        updateRankingAndSort(rankingMap, reason);
        for (NotifCollectionListener listener : mNotifCollectionListeners) {
            listener.onRankingApplied();
        }
    }

    /** Resorts / filters the current notification set with the current RankingMap */
    public void reapplyFilterAndSort(String reason) {
        updateRankingAndSort(mRankingManager.getRankingMap(), reason);
    }

    /** Calls to NotificationRankingManager and updates mSortedAndFiltered */
    private void updateRankingAndSort(@NonNull RankingMap rankingMap, String reason) {
        mSortedAndFiltered.clear();
        mSortedAndFiltered.addAll(mRankingManager.updateRanking(
                rankingMap, mActiveNotifications.values(), reason));
    }

    /** dump the current active notification list. Called from StatusBar */
    public void dump(PrintWriter pw, String indent) {
        pw.println("NotificationEntryManager");
        int filteredLen = mSortedAndFiltered.size();
        pw.print(indent);
        pw.println("active notifications: " + filteredLen);
        int active;
        for (active = 0; active < filteredLen; active++) {
            NotificationEntry e = mSortedAndFiltered.get(active);
            dumpEntry(pw, indent, active, e);
        }
        synchronized (mActiveNotifications) {
            int totalLen = mActiveNotifications.size();
            pw.print(indent);
            pw.println("inactive notifications: " + (totalLen - active));
            int inactiveCount = 0;
            for (int i = 0; i < totalLen; i++) {
                NotificationEntry entry = mActiveNotifications.valueAt(i);
                if (!mSortedAndFiltered.contains(entry)) {
                    dumpEntry(pw, indent, inactiveCount, entry);
                    inactiveCount++;
                }
            }
        }
    }

    private void dumpEntry(PrintWriter pw, String indent, int i, NotificationEntry e) {
        pw.print(indent);
        pw.println("  [" + i + "] key=" + e.getKey() + " icon=" + e.getIcons().getStatusBarIcon());
        StatusBarNotification n = e.getSbn();
        pw.print(indent);
        pw.println("      pkg=" + n.getPackageName() + " id=" + n.getId() + " importance="
                + e.getRanking().getImportance());
        pw.print(indent);
        pw.println("      notification=" + n.getNotification());
    }

    /**
     * This is the answer to the question "what notifications should the user be seeing right now?"
     * These are sorted and filtered, and directly inform the notification shade what to show
     *
     * @return A read-only list of the currently active notifications
     */
    public List<NotificationEntry> getVisibleNotifications() {
        return mReadOnlyNotifications;
    }

    /**
     * Returns a collections containing ALL notifications we know about, including ones that are
     * hidden or for other users. See {@link CommonNotifCollection#getAllNotifs()}.
     */
    @Override
    public Collection<NotificationEntry> getAllNotifs() {
        return mReadOnlyAllNotifications;
    }

    /** @return A count of the active notifications */
    public int getActiveNotificationsCount() {
        return mReadOnlyNotifications.size();
    }

    /**
     * @return {@code true} if there is at least one notification that should be visible right now
     */
    public boolean hasActiveNotifications() {
        return mReadOnlyNotifications.size() != 0;
    }

    @Override
    public void addCollectionListener(NotifCollectionListener listener) {
        mNotifCollectionListeners.add(listener);
    }

    /*
     * End annexation
     * -----
     */


    /**
     * Provides access to keyguard state and user settings dependent data.
     */
    public interface KeyguardEnvironment {
        /** true if the device is provisioned (should always be true in practice) */
        boolean isDeviceProvisioned();
        /** true if the notification is for the current profiles */
        boolean isNotificationForCurrentProfiles(StatusBarNotification sbn);
    }
}
