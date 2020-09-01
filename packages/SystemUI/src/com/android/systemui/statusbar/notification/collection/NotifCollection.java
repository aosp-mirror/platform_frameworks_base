/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection;

import static android.service.notification.NotificationListenerService.REASON_APP_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_APP_CANCEL_ALL;
import static android.service.notification.NotificationListenerService.REASON_CANCEL_ALL;
import static android.service.notification.NotificationListenerService.REASON_CHANNEL_BANNED;
import static android.service.notification.NotificationListenerService.REASON_CLICK;
import static android.service.notification.NotificationListenerService.REASON_ERROR;
import static android.service.notification.NotificationListenerService.REASON_GROUP_OPTIMIZATION;
import static android.service.notification.NotificationListenerService.REASON_GROUP_SUMMARY_CANCELED;
import static android.service.notification.NotificationListenerService.REASON_LISTENER_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_LISTENER_CANCEL_ALL;
import static android.service.notification.NotificationListenerService.REASON_PACKAGE_BANNED;
import static android.service.notification.NotificationListenerService.REASON_PACKAGE_CHANGED;
import static android.service.notification.NotificationListenerService.REASON_PACKAGE_SUSPENDED;
import static android.service.notification.NotificationListenerService.REASON_PROFILE_TURNED_OFF;
import static android.service.notification.NotificationListenerService.REASON_SNOOZED;
import static android.service.notification.NotificationListenerService.REASON_TIMEOUT;
import static android.service.notification.NotificationListenerService.REASON_UNAUTOBUNDLED;
import static android.service.notification.NotificationListenerService.REASON_USER_STOPPED;

import static com.android.systemui.statusbar.notification.collection.NotificationEntry.DismissState.DISMISSED;
import static com.android.systemui.statusbar.notification.collection.NotificationEntry.DismissState.NOT_DISMISSED;
import static com.android.systemui.statusbar.notification.collection.NotificationEntry.DismissState.PARENT_DISMISSED;

import static java.util.Objects.requireNonNull;

import android.annotation.IntDef;
import android.annotation.MainThread;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.Notification;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import android.util.ArrayMap;
import android.util.Pair;

import androidx.annotation.NonNull;

import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.Dumpable;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.dump.LogBufferEulogizer;
import com.android.systemui.statusbar.FeatureFlags;
import com.android.systemui.statusbar.notification.collection.coalescer.CoalescedEvent;
import com.android.systemui.statusbar.notification.collection.coalescer.GroupCoalescer;
import com.android.systemui.statusbar.notification.collection.coalescer.GroupCoalescer.BatchableNotificationHandler;
import com.android.systemui.statusbar.notification.collection.notifcollection.BindEntryEvent;
import com.android.systemui.statusbar.notification.collection.notifcollection.CleanUpEntryEvent;
import com.android.systemui.statusbar.notification.collection.notifcollection.CollectionReadyForBuildListener;
import com.android.systemui.statusbar.notification.collection.notifcollection.DismissedByUserStats;
import com.android.systemui.statusbar.notification.collection.notifcollection.EntryAddedEvent;
import com.android.systemui.statusbar.notification.collection.notifcollection.EntryRemovedEvent;
import com.android.systemui.statusbar.notification.collection.notifcollection.EntryUpdatedEvent;
import com.android.systemui.statusbar.notification.collection.notifcollection.InitEntryEvent;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionLogger;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifDismissInterceptor;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifEvent;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifLifetimeExtender;
import com.android.systemui.statusbar.notification.collection.notifcollection.RankingAppliedEvent;
import com.android.systemui.statusbar.notification.collection.notifcollection.RankingUpdatedEvent;
import com.android.systemui.util.Assert;
import com.android.systemui.util.time.SystemClock;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Keeps a record of all of the "active" notifications, i.e. the notifications that are currently
 * posted to the phone. This collection is unsorted, ungrouped, and unfiltered. Just because a
 * notification appears in this collection doesn't mean that it's currently present in the shade
 * (notifications can be hidden for a variety of reasons). Code that cares about what notifications
 * are *visible* right now should register listeners later in the pipeline.
 *
 * Each notification is represented by a {@link NotificationEntry}, which is itself made up of two
 * parts: a {@link StatusBarNotification} and a {@link Ranking}. When notifications are updated,
 * their underlying SBNs and Rankings are swapped out, but the enclosing NotificationEntry (and its
 * associated key) remain the same. In general, an SBN can only be updated when the notification is
 * reposted by the source app; Rankings are updated much more often, usually every time there is an
 * update from any kind from NotificationManager.
 *
 * In general, this collection closely mirrors the list maintained by NotificationManager, but it
 * can occasionally diverge due to lifetime extenders (see
 * {@link #addNotificationLifetimeExtender(NotifLifetimeExtender)}).
 *
 * Interested parties can register listeners
 * ({@link #addCollectionListener(NotifCollectionListener)}) to be informed when notifications
 * events occur.
 */
@MainThread
@Singleton
public class NotifCollection implements Dumpable {
    private final IStatusBarService mStatusBarService;
    private final SystemClock mClock;
    private final FeatureFlags mFeatureFlags;
    private final NotifCollectionLogger mLogger;
    private final LogBufferEulogizer mEulogizer;

    private final Map<String, NotificationEntry> mNotificationSet = new ArrayMap<>();
    private final Collection<NotificationEntry> mReadOnlyNotificationSet =
            Collections.unmodifiableCollection(mNotificationSet.values());

    @Nullable private CollectionReadyForBuildListener mBuildListener;
    private final List<NotifCollectionListener> mNotifCollectionListeners = new ArrayList<>();
    private final List<NotifLifetimeExtender> mLifetimeExtenders = new ArrayList<>();
    private final List<NotifDismissInterceptor> mDismissInterceptors = new ArrayList<>();

    private Queue<NotifEvent> mEventQueue = new ArrayDeque<>();

    private boolean mAttached = false;
    private boolean mAmDispatchingToOtherCode;
    private long mInitializedTimestamp = 0;

    @Inject
    public NotifCollection(
            IStatusBarService statusBarService,
            SystemClock clock,
            FeatureFlags featureFlags,
            NotifCollectionLogger logger,
            LogBufferEulogizer logBufferEulogizer,
            DumpManager dumpManager) {
        Assert.isMainThread();
        mStatusBarService = statusBarService;
        mClock = clock;
        mFeatureFlags = featureFlags;
        mLogger = logger;
        mEulogizer = logBufferEulogizer;

        dumpManager.registerDumpable(TAG, this);
    }

    /** Initializes the NotifCollection and registers it to receive notification events. */
    public void attach(GroupCoalescer groupCoalescer) {
        Assert.isMainThread();
        if (mAttached) {
            throw new RuntimeException("attach() called twice");
        }
        mAttached = true;

        groupCoalescer.setNotificationHandler(mNotifHandler);
    }

    /**
     * Sets the class responsible for converting the collection into the list of currently-visible
     * notifications.
     */
    void setBuildListener(CollectionReadyForBuildListener buildListener) {
        Assert.isMainThread();
        mBuildListener = buildListener;
    }

    /** @see NotifPipeline#getAllNotifs() */
    Collection<NotificationEntry> getAllNotifs() {
        Assert.isMainThread();
        return mReadOnlyNotificationSet;
    }

    /** @see NotifPipeline#addCollectionListener(NotifCollectionListener) */
    void addCollectionListener(NotifCollectionListener listener) {
        Assert.isMainThread();
        mNotifCollectionListeners.add(listener);
    }

    /** @see NotifPipeline#addNotificationLifetimeExtender(NotifLifetimeExtender) */
    void addNotificationLifetimeExtender(NotifLifetimeExtender extender) {
        Assert.isMainThread();
        checkForReentrantCall();
        if (mLifetimeExtenders.contains(extender)) {
            throw new IllegalArgumentException("Extender " + extender + " already added.");
        }
        mLifetimeExtenders.add(extender);
        extender.setCallback(this::onEndLifetimeExtension);
    }

    /** @see NotifPipeline#addNotificationDismissInterceptor(NotifDismissInterceptor) */
    void addNotificationDismissInterceptor(NotifDismissInterceptor interceptor) {
        Assert.isMainThread();
        checkForReentrantCall();
        if (mDismissInterceptors.contains(interceptor)) {
            throw new IllegalArgumentException("Interceptor " + interceptor + " already added.");
        }
        mDismissInterceptors.add(interceptor);
        interceptor.setCallback(this::onEndDismissInterception);
    }

    /**
     * Dismisses multiple notifications on behalf of the user.
     */
    public void dismissNotifications(
            List<Pair<NotificationEntry, DismissedByUserStats>> entriesToDismiss) {
        Assert.isMainThread();
        checkForReentrantCall();

        final List<NotificationEntry> entriesToLocallyDismiss = new ArrayList<>();
        for (int i = 0; i < entriesToDismiss.size(); i++) {
            NotificationEntry entry = entriesToDismiss.get(i).first;
            DismissedByUserStats stats = entriesToDismiss.get(i).second;

            requireNonNull(stats);
            if (entry != mNotificationSet.get(entry.getKey())) {
                throw mEulogizer.record(
                        new IllegalStateException("Invalid entry: " + entry.getKey()));
            }

            if (entry.getDismissState() == DISMISSED) {
                continue;
            }

            updateDismissInterceptors(entry);
            if (isDismissIntercepted(entry)) {
                mLogger.logNotifDismissedIntercepted(entry.getKey());
                continue;
            }

            entriesToLocallyDismiss.add(entry);
            if (!isCanceled(entry)) {
                // send message to system server if this notification hasn't already been cancelled
                try {
                    mStatusBarService.onNotificationClear(
                            entry.getSbn().getPackageName(),
                            entry.getSbn().getTag(),
                            entry.getSbn().getId(),
                            entry.getSbn().getUser().getIdentifier(),
                            entry.getSbn().getKey(),
                            stats.dismissalSurface,
                            stats.dismissalSentiment,
                            stats.notificationVisibility);
                } catch (RemoteException e) {
                    // system process is dead if we're here.
                    mLogger.logRemoteExceptionOnNotificationClear(entry.getKey(), e);
                }
            }
        }

        locallyDismissNotifications(entriesToLocallyDismiss);
        dispatchEventsAndRebuildList();
    }

    /**
     * Dismisses a single notification on behalf of the user.
     */
    public void dismissNotification(
            NotificationEntry entry,
            @NonNull DismissedByUserStats stats) {
        dismissNotifications(List.of(new Pair<>(entry, stats)));
    }

    /**
     * Dismisses all clearable notifications for a given userid on behalf of the user.
     */
    public void dismissAllNotifications(@UserIdInt int userId) {
        Assert.isMainThread();
        checkForReentrantCall();

        mLogger.logDismissAll(userId);

        try {
            mStatusBarService.onClearAllNotifications(userId);
        } catch (RemoteException e) {
            // system process is dead if we're here.
            mLogger.logRemoteExceptionOnClearAllNotifications(e);
        }

        final List<NotificationEntry> entries = new ArrayList<>(getAllNotifs());
        for (int i = entries.size() - 1; i >= 0; i--) {
            NotificationEntry entry = entries.get(i);
            if (!shouldDismissOnClearAll(entry, userId)) {
                // system server won't be removing these notifications, but we still give dismiss
                // interceptors the chance to filter the notification
                updateDismissInterceptors(entry);
                if (isDismissIntercepted(entry)) {
                    mLogger.logNotifClearAllDismissalIntercepted(entry.getKey());
                }
                entries.remove(i);
            }
        }

        locallyDismissNotifications(entries);
        dispatchEventsAndRebuildList();
    }

    /**
     * Optimistically marks the given notifications as dismissed -- we'll wait for the signal
     * from system server before removing it from our notification set.
     */
    private void locallyDismissNotifications(List<NotificationEntry> entries) {
        final List<NotificationEntry> canceledEntries = new ArrayList<>();

        for (int i = 0; i < entries.size(); i++) {
            NotificationEntry entry = entries.get(i);

            entry.setDismissState(DISMISSED);
            mLogger.logNotifDismissed(entry.getKey());

            if (isCanceled(entry)) {
                canceledEntries.add(entry);
            } else {
                // Mark any children as dismissed as system server will auto-dismiss them as well
                if (entry.getSbn().getNotification().isGroupSummary()) {
                    for (NotificationEntry otherEntry : mNotificationSet.values()) {
                        if (shouldAutoDismissChildren(otherEntry, entry.getSbn().getGroupKey())) {
                            otherEntry.setDismissState(PARENT_DISMISSED);
                            mLogger.logChildDismissed(otherEntry);
                            if (isCanceled(otherEntry)) {
                                canceledEntries.add(otherEntry);
                            }
                        }
                    }
                }
            }
        }

        // Immediately remove any dismissed notifs that have already been canceled by system server
        // (probably due to being lifetime-extended up until this point).
        for (NotificationEntry canceledEntry : canceledEntries) {
            mLogger.logDismissOnAlreadyCanceledEntry(canceledEntry);
            tryRemoveNotification(canceledEntry);
        }
    }

    private void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        Assert.isMainThread();

        postNotification(sbn, requireRanking(rankingMap, sbn.getKey()));
        applyRanking(rankingMap);
        dispatchEventsAndRebuildList();
    }

    private void onNotificationGroupPosted(List<CoalescedEvent> batch) {
        Assert.isMainThread();

        mLogger.logNotifGroupPosted(batch.get(0).getSbn().getGroupKey(), batch.size());

        for (CoalescedEvent event : batch) {
            postNotification(event.getSbn(), event.getRanking());
        }
        dispatchEventsAndRebuildList();
    }

    private void onNotificationRemoved(
            StatusBarNotification sbn,
            RankingMap rankingMap,
            int reason) {
        Assert.isMainThread();

        mLogger.logNotifRemoved(sbn.getKey(), reason);

        final NotificationEntry entry = mNotificationSet.get(sbn.getKey());
        if (entry == null) {
            // TODO (b/160008901): Throw an exception here
            mLogger.logNoNotificationToRemoveWithKey(sbn.getKey());
            return;
        }

        entry.mCancellationReason = reason;
        tryRemoveNotification(entry);
        applyRanking(rankingMap);
        dispatchEventsAndRebuildList();
    }

    private void onNotificationRankingUpdate(RankingMap rankingMap) {
        Assert.isMainThread();
        mEventQueue.add(new RankingUpdatedEvent(rankingMap));
        applyRanking(rankingMap);
        dispatchEventsAndRebuildList();
    }

    private void onNotificationsInitialized() {
        mInitializedTimestamp = mClock.uptimeMillis();
    }

    private void postNotification(
            StatusBarNotification sbn,
            Ranking ranking) {
        NotificationEntry entry = mNotificationSet.get(sbn.getKey());

        if (entry == null) {
            // A new notification!
            entry = new NotificationEntry(sbn, ranking, mClock.uptimeMillis());
            mEventQueue.add(new InitEntryEvent(entry));
            mEventQueue.add(new BindEntryEvent(entry, sbn));
            mNotificationSet.put(sbn.getKey(), entry);

            mLogger.logNotifPosted(sbn.getKey());
            mEventQueue.add(new EntryAddedEvent(entry));

        } else {
            // Update to an existing entry

            // Notification is updated so it is essentially re-added and thus alive again, so we
            // can reset its state.
            // TODO: If a coalesced event ever gets here, it's possible to lose track of children,
            //  since their rankings might have been updated earlier (and thus we may no longer
            //  think a child is associated with this locally-dismissed entry).
            cancelLocalDismissal(entry);
            cancelLifetimeExtension(entry);
            cancelDismissInterception(entry);
            entry.mCancellationReason = REASON_NOT_CANCELED;

            entry.setSbn(sbn);
            mEventQueue.add(new BindEntryEvent(entry, sbn));

            mLogger.logNotifUpdated(sbn.getKey());
            mEventQueue.add(new EntryUpdatedEvent(entry));
        }
    }

    /**
     * Tries to remove a notification from the notification set. This removal may be blocked by
     * lifetime extenders. Does not trigger a rebuild of the list; caller must do that manually.
     *
     * @return True if the notification was removed, false otherwise.
     */
    private boolean tryRemoveNotification(NotificationEntry entry) {
        if (mNotificationSet.get(entry.getKey()) != entry) {
            throw mEulogizer.record(
                    new IllegalStateException("No notification to remove with key "
                            + entry.getKey()));
        }

        if (!isCanceled(entry)) {
            throw mEulogizer.record(
                    new IllegalStateException("Cannot remove notification " + entry.getKey()
                            + ": has not been marked for removal"));
        }

        if (isDismissedByUser(entry)) {
            // User-dismissed notifications cannot be lifetime-extended
            cancelLifetimeExtension(entry);
        } else {
            updateLifetimeExtension(entry);
        }

        if (!isLifetimeExtended(entry)) {
            mLogger.logNotifReleased(entry.getKey());
            mNotificationSet.remove(entry.getKey());
            cancelDismissInterception(entry);
            mEventQueue.add(new EntryRemovedEvent(entry, entry.mCancellationReason));
            mEventQueue.add(new CleanUpEntryEvent(entry));
            return true;
        } else {
            return false;
        }
    }

    private void applyRanking(@NonNull RankingMap rankingMap) {
        for (NotificationEntry entry : mNotificationSet.values()) {
            if (!isCanceled(entry)) {

                // TODO: (b/148791039) We should crash if we are ever handed a ranking with
                //  incomplete entries. Right now, there's a race condition in NotificationListener
                //  that means this might occur when SystemUI is starting up.
                Ranking ranking = new Ranking();
                if (rankingMap.getRanking(entry.getKey(), ranking)) {
                    entry.setRanking(ranking);

                    // TODO: (b/145659174) update the sbn's overrideGroupKey in
                    //  NotificationEntry.setRanking instead of here once we fully migrate to the
                    //  NewNotifPipeline
                    if (mFeatureFlags.isNewNotifPipelineRenderingEnabled()) {
                        final String newOverrideGroupKey = ranking.getOverrideGroupKey();
                        if (!Objects.equals(entry.getSbn().getOverrideGroupKey(),
                                newOverrideGroupKey)) {
                            entry.getSbn().setOverrideGroupKey(newOverrideGroupKey);
                        }
                    }
                } else {
                    mLogger.logRankingMissing(entry.getKey(), rankingMap);
                }
            }
        }
        mEventQueue.add(new RankingAppliedEvent());
    }

    private void dispatchEventsAndRebuildList() {
        mAmDispatchingToOtherCode = true;
        while (!mEventQueue.isEmpty()) {
            mEventQueue.remove().dispatchTo(mNotifCollectionListeners);
        }
        mAmDispatchingToOtherCode = false;

        if (mBuildListener != null) {
            mBuildListener.onBuildList(mReadOnlyNotificationSet);
        }
    }

    private void onEndLifetimeExtension(NotifLifetimeExtender extender, NotificationEntry entry) {
        Assert.isMainThread();
        if (!mAttached) {
            return;
        }
        checkForReentrantCall();

        if (!entry.mLifetimeExtenders.remove(extender)) {
            throw mEulogizer.record(new IllegalStateException(
                    String.format(
                            "Cannot end lifetime extension for extender \"%s\" (%s)",
                            extender.getName(),
                            extender)));
        }

        mLogger.logLifetimeExtensionEnded(
                entry.getKey(),
                extender,
                entry.mLifetimeExtenders.size());

        if (!isLifetimeExtended(entry)) {
            if (tryRemoveNotification(entry)) {
                dispatchEventsAndRebuildList();
            }
        }
    }

    private void cancelLifetimeExtension(NotificationEntry entry) {
        mAmDispatchingToOtherCode = true;
        for (NotifLifetimeExtender extender : entry.mLifetimeExtenders) {
            extender.cancelLifetimeExtension(entry);
        }
        mAmDispatchingToOtherCode = false;
        entry.mLifetimeExtenders.clear();
    }

    private boolean isLifetimeExtended(NotificationEntry entry) {
        return entry.mLifetimeExtenders.size() > 0;
    }

    private void updateLifetimeExtension(NotificationEntry entry) {
        entry.mLifetimeExtenders.clear();
        mAmDispatchingToOtherCode = true;
        for (NotifLifetimeExtender extender : mLifetimeExtenders) {
            if (extender.shouldExtendLifetime(entry, entry.mCancellationReason)) {
                mLogger.logLifetimeExtended(entry.getKey(), extender);
                entry.mLifetimeExtenders.add(extender);
            }
        }
        mAmDispatchingToOtherCode = false;
    }

    private void updateDismissInterceptors(@NonNull NotificationEntry entry) {
        entry.mDismissInterceptors.clear();
        mAmDispatchingToOtherCode = true;
        for (NotifDismissInterceptor interceptor : mDismissInterceptors) {
            if (interceptor.shouldInterceptDismissal(entry)) {
                entry.mDismissInterceptors.add(interceptor);
            }
        }
        mAmDispatchingToOtherCode = false;
    }

    private void cancelLocalDismissal(NotificationEntry entry) {
        if (isDismissedByUser(entry)) {
            entry.setDismissState(NOT_DISMISSED);
            if (entry.getSbn().getNotification().isGroupSummary()) {
                for (NotificationEntry otherEntry : mNotificationSet.values()) {
                    if (otherEntry.getSbn().getGroupKey().equals(entry.getSbn().getGroupKey())
                            && otherEntry.getDismissState() == PARENT_DISMISSED) {
                        otherEntry.setDismissState(NOT_DISMISSED);
                    }
                }
            }
        }
    }

    private void onEndDismissInterception(
            NotifDismissInterceptor interceptor,
            NotificationEntry entry,
            @NonNull DismissedByUserStats stats) {
        Assert.isMainThread();
        if (!mAttached) {
            return;
        }
        checkForReentrantCall();

        if (!entry.mDismissInterceptors.remove(interceptor)) {
            throw mEulogizer.record(new IllegalStateException(
                    String.format(
                            "Cannot end dismiss interceptor for interceptor \"%s\" (%s)",
                            interceptor.getName(),
                            interceptor)));
        }

        if (!isDismissIntercepted(entry)) {
            dismissNotification(entry, stats);
        }
    }

    private void cancelDismissInterception(NotificationEntry entry) {
        mAmDispatchingToOtherCode = true;
        for (NotifDismissInterceptor interceptor : entry.mDismissInterceptors) {
            interceptor.cancelDismissInterception(entry);
        }
        mAmDispatchingToOtherCode = false;
        entry.mDismissInterceptors.clear();
    }

    private boolean isDismissIntercepted(NotificationEntry entry) {
        return entry.mDismissInterceptors.size() > 0;
    }

    private void checkForReentrantCall() {
        if (mAmDispatchingToOtherCode) {
            throw mEulogizer.record(new IllegalStateException("Reentrant call detected"));
        }
    }

    // While the NotificationListener is connecting to NotificationManager, there is a short period
    // during which it's possible for us to receive events about notifications we don't yet know
    // about (or that otherwise don't make sense). Until that race condition is fixed, we create a
    // "forgiveness window" of five seconds during which we won't crash if we receive nonsensical
    // messages from system server.
    private void crashIfNotInitializing(RuntimeException exception) {
        final boolean isRecentlyInitialized = mInitializedTimestamp == 0
                || mClock.uptimeMillis() - mInitializedTimestamp
                        < INITIALIZATION_FORGIVENESS_WINDOW;

        if (isRecentlyInitialized) {
            mLogger.logIgnoredError(exception.getMessage());
        } else {
            throw mEulogizer.record(exception);
        }
    }

    private static Ranking requireRanking(RankingMap rankingMap, String key) {
        // TODO: Modify RankingMap so that we don't have to make a copy here
        Ranking ranking = new Ranking();
        if (!rankingMap.getRanking(key, ranking)) {
            throw new IllegalArgumentException("Ranking map doesn't contain key: " + key);
        }
        return ranking;
    }

    /**
     * True if the notification has been canceled by system server. Usually, such notifications are
     * immediately removed from the collection, but can sometimes stick around due to lifetime
     * extenders.
     */
    private static boolean isCanceled(NotificationEntry entry) {
        return entry.mCancellationReason != REASON_NOT_CANCELED;
    }

    private static boolean isDismissedByUser(NotificationEntry entry) {
        return entry.getDismissState() != NOT_DISMISSED;
    }

    /**
     * When a group summary is dismissed, NotificationManager will also try to dismiss its children.
     * Returns true if we think dismissing the group summary with group key
     * <code>dismissedGroupKey</code> will cause NotificationManager to also dismiss
     * <code>entry</code>.
     *
     * See NotificationManager.cancelGroupChildrenByListLocked() for corresponding code.
     */
    private static boolean shouldAutoDismissChildren(
            NotificationEntry entry,
            String dismissedGroupKey) {
        return entry.getSbn().getGroupKey().equals(dismissedGroupKey)
                && !entry.getSbn().getNotification().isGroupSummary()
                && !hasFlag(entry, Notification.FLAG_FOREGROUND_SERVICE)
                && !hasFlag(entry, Notification.FLAG_BUBBLE)
                && entry.getDismissState() != DISMISSED;
    }

    /**
     * When the user 'clears all notifications' through SystemUI, NotificationManager will not
     * dismiss unclearable notifications.
     * @return true if we think NotificationManager will dismiss the entry when asked to
     * cancel this notification with {@link NotificationListenerService#REASON_CANCEL_ALL}
     *
     * See NotificationManager.cancelAllLocked for corresponding code.
     */
    private static boolean shouldDismissOnClearAll(
            NotificationEntry entry,
            @UserIdInt int userId) {
        return userIdMatches(entry, userId)
                && entry.isClearable()
                && !hasFlag(entry, Notification.FLAG_BUBBLE)
                && entry.getDismissState() != DISMISSED;
    }

    private static boolean hasFlag(NotificationEntry entry, int flag) {
        return (entry.getSbn().getNotification().flags & flag) != 0;
    }

    /**
     * Determine whether the userId applies to the notification in question, either because
     * they match exactly, or one of them is USER_ALL (which is treated as a wildcard).
     *
     * See NotificationManager#notificationMatchesUserId
     */
    private static boolean userIdMatches(NotificationEntry entry, int userId) {
        return userId == UserHandle.USER_ALL
                || entry.getSbn().getUser().getIdentifier() == UserHandle.USER_ALL
                || entry.getSbn().getUser().getIdentifier() == userId;
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, PrintWriter pw, @NonNull String[] args) {
        final List<NotificationEntry> entries = new ArrayList<>(getAllNotifs());

        pw.println("\t" + TAG + " unsorted/unfiltered notifications:");
        if (entries.size() == 0) {
            pw.println("\t\t None");
        }
        pw.println(
                ListDumper.dumpList(
                        entries,
                        true,
                        "\t\t"));
    }

    private final BatchableNotificationHandler mNotifHandler = new BatchableNotificationHandler() {
        @Override
        public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
            NotifCollection.this.onNotificationPosted(sbn, rankingMap);
        }

        @Override
        public void onNotificationBatchPosted(List<CoalescedEvent> events) {
            NotifCollection.this.onNotificationGroupPosted(events);
        }

        @Override
        public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap) {
            NotifCollection.this.onNotificationRemoved(sbn, rankingMap, REASON_UNKNOWN);
        }

        @Override
        public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap,
                int reason) {
            NotifCollection.this.onNotificationRemoved(sbn, rankingMap, reason);
        }

        @Override
        public void onNotificationRankingUpdate(RankingMap rankingMap) {
            NotifCollection.this.onNotificationRankingUpdate(rankingMap);
        }

        @Override
        public void onNotificationsInitialized() {
            NotifCollection.this.onNotificationsInitialized();
        }
    };

    private static final String TAG = "NotifCollection";

    @IntDef(prefix = { "REASON_" }, value = {
            REASON_NOT_CANCELED,
            REASON_UNKNOWN,
            REASON_CLICK,
            REASON_CANCEL_ALL,
            REASON_ERROR,
            REASON_PACKAGE_CHANGED,
            REASON_USER_STOPPED,
            REASON_PACKAGE_BANNED,
            REASON_APP_CANCEL,
            REASON_APP_CANCEL_ALL,
            REASON_LISTENER_CANCEL,
            REASON_LISTENER_CANCEL_ALL,
            REASON_GROUP_SUMMARY_CANCELED,
            REASON_GROUP_OPTIMIZATION,
            REASON_PACKAGE_SUSPENDED,
            REASON_PROFILE_TURNED_OFF,
            REASON_UNAUTOBUNDLED,
            REASON_CHANNEL_BANNED,
            REASON_SNOOZED,
            REASON_TIMEOUT,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CancellationReason {}

    static final int REASON_NOT_CANCELED = -1;
    public static final int REASON_UNKNOWN = 0;

    private static final long INITIALIZATION_FORGIVENESS_WINDOW = TimeUnit.SECONDS.toMillis(5);
}
