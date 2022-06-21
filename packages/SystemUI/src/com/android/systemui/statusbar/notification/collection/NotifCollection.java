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
import static android.service.notification.NotificationListenerService.REASON_ASSISTANT_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_CANCEL_ALL;
import static android.service.notification.NotificationListenerService.REASON_CHANNEL_BANNED;
import static android.service.notification.NotificationListenerService.REASON_CHANNEL_REMOVED;
import static android.service.notification.NotificationListenerService.REASON_CLEAR_DATA;
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

import static com.android.systemui.statusbar.notification.NotificationUtils.logKey;
import static com.android.systemui.statusbar.notification.collection.NotificationEntry.DismissState.DISMISSED;
import static com.android.systemui.statusbar.notification.collection.NotificationEntry.DismissState.NOT_DISMISSED;
import static com.android.systemui.statusbar.notification.collection.NotificationEntry.DismissState.PARENT_DISMISSED;
import static com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionLoggerKt.cancellationReasonDebugString;

import static java.util.Objects.requireNonNull;

import android.annotation.IntDef;
import android.annotation.MainThread;
import android.annotation.UserIdInt;
import android.app.Notification;
import android.app.NotificationChannel;
import android.os.Handler;
import android.os.RemoteException;
import android.os.Trace;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import android.util.ArrayMap;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.Dumpable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.dump.LogBufferEulogizer;
import com.android.systemui.statusbar.notification.NotifPipelineFlags;
import com.android.systemui.statusbar.notification.collection.coalescer.CoalescedEvent;
import com.android.systemui.statusbar.notification.collection.coalescer.GroupCoalescer;
import com.android.systemui.statusbar.notification.collection.coalescer.GroupCoalescer.BatchableNotificationHandler;
import com.android.systemui.statusbar.notification.collection.notifcollection.BindEntryEvent;
import com.android.systemui.statusbar.notification.collection.notifcollection.ChannelChangedEvent;
import com.android.systemui.statusbar.notification.collection.notifcollection.CleanUpEntryEvent;
import com.android.systemui.statusbar.notification.collection.notifcollection.CollectionReadyForBuildListener;
import com.android.systemui.statusbar.notification.collection.notifcollection.DismissedByUserStats;
import com.android.systemui.statusbar.notification.collection.notifcollection.EntryAddedEvent;
import com.android.systemui.statusbar.notification.collection.notifcollection.EntryRemovedEvent;
import com.android.systemui.statusbar.notification.collection.notifcollection.EntryUpdatedEvent;
import com.android.systemui.statusbar.notification.collection.notifcollection.InitEntryEvent;
import com.android.systemui.statusbar.notification.collection.notifcollection.InternalNotifUpdater;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionLogger;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifDismissInterceptor;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifEvent;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifLifetimeExtender;
import com.android.systemui.statusbar.notification.collection.notifcollection.RankingAppliedEvent;
import com.android.systemui.statusbar.notification.collection.notifcollection.RankingUpdatedEvent;
import com.android.systemui.util.Assert;
import com.android.systemui.util.time.SystemClock;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

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
@SysUISingleton
public class NotifCollection implements Dumpable {
    private final IStatusBarService mStatusBarService;
    private final SystemClock mClock;
    private final NotifPipelineFlags mNotifPipelineFlags;
    private final NotifCollectionLogger mLogger;
    private final Handler mMainHandler;
    private final LogBufferEulogizer mEulogizer;
    private final DumpManager mDumpManager;

    private final Map<String, NotificationEntry> mNotificationSet = new ArrayMap<>();
    private final Collection<NotificationEntry> mReadOnlyNotificationSet =
            Collections.unmodifiableCollection(mNotificationSet.values());
    private final HashMap<String, FutureDismissal> mFutureDismissals = new HashMap<>();

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
            NotifPipelineFlags notifPipelineFlags,
            NotifCollectionLogger logger,
            @Main Handler mainHandler,
            LogBufferEulogizer logBufferEulogizer,
            DumpManager dumpManager) {
        mStatusBarService = statusBarService;
        mClock = clock;
        mNotifPipelineFlags = notifPipelineFlags;
        mLogger = logger;
        mMainHandler = mainHandler;
        mEulogizer = logBufferEulogizer;
        mDumpManager = dumpManager;
    }

    /** Initializes the NotifCollection and registers it to receive notification events. */
    public void attach(GroupCoalescer groupCoalescer) {
        Assert.isMainThread();
        if (mAttached) {
            throw new RuntimeException("attach() called twice");
        }
        mAttached = true;
        mDumpManager.registerDumpable(TAG, this);
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

    /** @see NotifPipeline#getEntry(String) () */
    @Nullable
    NotificationEntry getEntry(@NonNull String key) {
        return mNotificationSet.get(key);
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

    /** @see NotifPipeline#removeCollectionListener(NotifCollectionListener) */
    void removeCollectionListener(NotifCollectionListener listener) {
        Assert.isMainThread();
        mNotifCollectionListeners.remove(listener);
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

        // TODO (b/206842750): This method is called from (silent) clear all and non-clear all
        // contexts and should be checking the NO_CLEAR flag, rather than depending on NSSL
        // to pass in a properly filtered list of notifications

        final List<NotificationEntry> entriesToLocallyDismiss = new ArrayList<>();
        for (int i = 0; i < entriesToDismiss.size(); i++) {
            NotificationEntry entry = entriesToDismiss.get(i).first;
            DismissedByUserStats stats = entriesToDismiss.get(i).second;

            requireNonNull(stats);
            NotificationEntry storedEntry = mNotificationSet.get(entry.getKey());
            if (storedEntry == null) {
                mLogger.logNonExistentNotifDismissed(entry);
                continue;
            }
            if (entry != storedEntry) {
                throw mEulogizer.record(
                        new IllegalStateException("Invalid entry: "
                                + "different stored and dismissed entries for " + logKey(entry)
                                + " stored=@" + Integer.toHexString(storedEntry.hashCode())));
            }

            if (entry.getDismissState() == DISMISSED) {
                continue;
            }

            updateDismissInterceptors(entry);
            if (isDismissIntercepted(entry)) {
                mLogger.logNotifDismissedIntercepted(entry);
                continue;
            }

            entriesToLocallyDismiss.add(entry);
            if (!isCanceled(entry)) {
                // send message to system server if this notification hasn't already been cancelled
                try {
                    mStatusBarService.onNotificationClear(
                            entry.getSbn().getPackageName(),
                            entry.getSbn().getUser().getIdentifier(),
                            entry.getSbn().getKey(),
                            stats.dismissalSurface,
                            stats.dismissalSentiment,
                            stats.notificationVisibility);
                } catch (RemoteException e) {
                    // system process is dead if we're here.
                    mLogger.logRemoteExceptionOnNotificationClear(entry, e);
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
            // TODO(b/169585328): Do not clear media player notifications
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
                    mLogger.logNotifClearAllDismissalIntercepted(entry);
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
            mLogger.logNotifDismissed(entry);

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

        mLogger.logNotifRemoved(sbn, reason);

        final NotificationEntry entry = mNotificationSet.get(sbn.getKey());
        if (entry == null) {
            // TODO (b/160008901): Throw an exception here
            mLogger.logNoNotificationToRemoveWithKey(sbn, reason);
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

    private void onNotificationChannelModified(
            String pkgName,
            UserHandle user,
            NotificationChannel channel,
            int modificationType) {
        Assert.isMainThread();
        mEventQueue.add(new ChannelChangedEvent(pkgName, user, channel, modificationType));
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

            mLogger.logNotifPosted(entry);
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

            mLogger.logNotifUpdated(entry);
            mEventQueue.add(new EntryUpdatedEvent(entry, true /* fromSystem */));
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
                            + logKey(entry)));
        }

        if (!isCanceled(entry)) {
            throw mEulogizer.record(
                    new IllegalStateException("Cannot remove notification " + logKey(entry)
                            + ": has not been marked for removal"));
        }

        if (cannotBeLifetimeExtended(entry)) {
            cancelLifetimeExtension(entry);
        } else {
            updateLifetimeExtension(entry);
        }

        if (!isLifetimeExtended(entry)) {
            mLogger.logNotifReleased(entry);
            mNotificationSet.remove(entry.getKey());
            cancelDismissInterception(entry);
            mEventQueue.add(new EntryRemovedEvent(entry, entry.mCancellationReason));
            mEventQueue.add(new CleanUpEntryEvent(entry));
            handleFutureDismissal(entry);
            return true;
        } else {
            return false;
        }
    }

    /**
     * Get the group summary entry
     * @param groupKey
     * @return
     */
    @Nullable
    public NotificationEntry getGroupSummary(String groupKey) {
        return mNotificationSet
                .values()
                .stream()
                .filter(it -> Objects.equals(it.getSbn().getGroupKey(), groupKey))
                .filter(it -> it.getSbn().getNotification().isGroupSummary())
                .findFirst().orElse(null);
    }

    /**
     * Checks if the entry is the only child in the logical group;
     * it need not have a summary to qualify
     *
     * @param entry the entry to check
     */
    public boolean isOnlyChildInGroup(NotificationEntry entry) {
        String groupKey = entry.getSbn().getGroupKey();
        return mNotificationSet.get(entry.getKey()) == entry
                && mNotificationSet
                .values()
                .stream()
                .filter(it -> Objects.equals(it.getSbn().getGroupKey(), groupKey))
                .filter(it -> !it.getSbn().getNotification().isGroupSummary())
                .count() == 1;
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
                    if (mNotifPipelineFlags.isNewPipelineEnabled()) {
                        final String newOverrideGroupKey = ranking.getOverrideGroupKey();
                        if (!Objects.equals(entry.getSbn().getOverrideGroupKey(),
                                newOverrideGroupKey)) {
                            entry.getSbn().setOverrideGroupKey(newOverrideGroupKey);
                        }
                    }
                } else {
                    mLogger.logRankingMissing(entry, rankingMap);
                }
            }
        }
        mEventQueue.add(new RankingAppliedEvent());
    }

    private void dispatchEventsAndRebuildList() {
        Trace.beginSection("NotifCollection.dispatchEventsAndRebuildList");
        mAmDispatchingToOtherCode = true;
        while (!mEventQueue.isEmpty()) {
            mEventQueue.remove().dispatchTo(mNotifCollectionListeners);
        }
        mAmDispatchingToOtherCode = false;

        if (mBuildListener != null) {
            mBuildListener.onBuildList(mReadOnlyNotificationSet);
        }
        Trace.endSection();
    }

    private void onEndLifetimeExtension(
            @NonNull NotifLifetimeExtender extender,
            @NonNull NotificationEntry entry) {
        Assert.isMainThread();
        if (!mAttached) {
            return;
        }
        checkForReentrantCall();

        NotificationEntry collectionEntry = getEntry(entry.getKey());
        String logKey = logKey(entry);
        String collectionEntryIs = collectionEntry == null ? "null"
                : entry == collectionEntry ? "same" : "different";

        if (entry != collectionEntry) {
            // TODO: We should probably make this throw, but that's too risky right now
            mLogger.logEntryBeingExtendedNotInCollection(entry, extender, collectionEntryIs);
        }

        if (!entry.mLifetimeExtenders.remove(extender)) {
            throw mEulogizer.record(new IllegalStateException(
                    String.format("Cannot end lifetime extension for extender \"%s\""
                                    + " of entry %s (collection entry is %s)",
                            extender.getName(), logKey, collectionEntryIs)));
        }

        mLogger.logLifetimeExtensionEnded(entry, extender, entry.mLifetimeExtenders.size());

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
            if (extender.maybeExtendLifetime(entry, entry.mCancellationReason)) {
                mLogger.logLifetimeExtended(entry, extender);
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
        if (entry.getDismissState() != NOT_DISMISSED) {
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
    private boolean isCanceled(NotificationEntry entry) {
        return entry.mCancellationReason != REASON_NOT_CANCELED;
    }

    private boolean cannotBeLifetimeExtended(NotificationEntry entry) {
        final boolean locallyDismissedByUser = entry.getDismissState() != NOT_DISMISSED;
        final boolean systemServerReportedUserCancel =
                entry.mCancellationReason == REASON_CLICK
                        || entry.mCancellationReason == REASON_CANCEL;
        return locallyDismissedByUser || systemServerReportedUserCancel;
    }

    /**
     * When a group summary is dismissed, NotificationManager will also try to dismiss its children.
     * Returns true if we think dismissing the group summary with group key
     * <code>dismissedGroupKey</code> will cause NotificationManager to also dismiss
     * <code>entry</code>.
     *
     * See NotificationManager.cancelGroupChildrenByListLocked() for corresponding code.
     */
    @VisibleForTesting
    static boolean shouldAutoDismissChildren(
            NotificationEntry entry,
            String dismissedGroupKey) {
        return entry.getSbn().getGroupKey().equals(dismissedGroupKey)
                && !entry.getSbn().getNotification().isGroupSummary()
                && !hasFlag(entry, Notification.FLAG_ONGOING_EVENT)
                && !hasFlag(entry, Notification.FLAG_BUBBLE)
                && !hasFlag(entry, Notification.FLAG_NO_CLEAR)
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
    public void dump(PrintWriter pw, @NonNull String[] args) {
        final List<NotificationEntry> entries = new ArrayList<>(getAllNotifs());
        entries.sort(Comparator.comparing(NotificationEntry::getKey));

        pw.println("\t" + TAG + " unsorted/unfiltered notifications: " + entries.size());
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
        public void onNotificationChannelModified(
                String pkgName,
                UserHandle user,
                NotificationChannel channel,
                int modificationType) {
            NotifCollection.this.onNotificationChannelModified(
                    pkgName,
                    user,
                    channel,
                    modificationType);
        }

        @Override
        public void onNotificationsInitialized() {
            NotifCollection.this.onNotificationsInitialized();
        }
    };

    private static final String TAG = "NotifCollection";

    /**
     * Get an object which can be used to update a notification (internally to the pipeline)
     * in response to a user action.
     *
     * @param name the name of the component that will update notifiations
     * @return an updater
     */
    public InternalNotifUpdater getInternalNotifUpdater(String name) {
        return (sbn, reason) -> mMainHandler.post(
                () -> updateNotificationInternally(sbn, name, reason));
    }

    /**
     * Provide an updated StatusBarNotification for an existing entry.  If no entry exists for the
     * given notification key, this method does nothing.
     *
     * @param sbn the updated notification
     * @param name the component which is updating the notification
     * @param reason the reason the notification is being updated
     */
    private void updateNotificationInternally(StatusBarNotification sbn, String name,
            String reason) {
        Assert.isMainThread();
        checkForReentrantCall();

        // Make sure we have the notification to update
        NotificationEntry entry = mNotificationSet.get(sbn.getKey());
        if (entry == null) {
            mLogger.logNotifInternalUpdateFailed(sbn, name, reason);
            return;
        }
        mLogger.logNotifInternalUpdate(entry, name, reason);

        // First do the pieces of postNotification which are not about assuming the notification
        // was sent by the app
        entry.setSbn(sbn);
        mEventQueue.add(new BindEntryEvent(entry, sbn));

        mLogger.logNotifUpdated(entry);
        mEventQueue.add(new EntryUpdatedEvent(entry, false /* fromSystem */));

        // Skip the applyRanking step and go straight to dispatching the events
        dispatchEventsAndRebuildList();
    }

    /**
     * A method to alert the collection that an async operation is happening, at the end of which a
     * dismissal request will be made.  This method has the additional guarantee that if a parent
     * notification exists for a single child, then that notification will also be dismissed.
     *
     * The runnable returned must be run at the end of the async operation to enact the cancellation
     *
     * @param entry the notification we want to dismiss
     * @param cancellationReason the reason for the cancellation
     * @param statsCreator the callback for generating the stats for an entry
     * @return the runnable to be run when the dismissal is ready to happen
     */
    public Runnable registerFutureDismissal(NotificationEntry entry, int cancellationReason,
            DismissedByUserStatsCreator statsCreator) {
        FutureDismissal dismissal = mFutureDismissals.get(entry.getKey());
        if (dismissal != null) {
            mLogger.logFutureDismissalReused(dismissal);
            return dismissal;
        }
        dismissal = new FutureDismissal(entry, cancellationReason, statsCreator);
        mFutureDismissals.put(entry.getKey(), dismissal);
        mLogger.logFutureDismissalRegistered(dismissal);
        return dismissal;
    }

    private void handleFutureDismissal(NotificationEntry entry) {
        final FutureDismissal futureDismissal = mFutureDismissals.remove(entry.getKey());
        if (futureDismissal != null) {
            futureDismissal.onSystemServerCancel(entry.mCancellationReason);
        }
    }

    /** A single method interface that callers can pass in when registering future dismissals */
    public interface DismissedByUserStatsCreator {
        DismissedByUserStats createDismissedByUserStats(NotificationEntry entry);
    }

    /** A class which tracks the double dismissal events coming in from both the system server and
     * the ui */
    public class FutureDismissal implements Runnable {
        private final NotificationEntry mEntry;
        private final DismissedByUserStatsCreator mStatsCreator;
        @Nullable
        private final NotificationEntry mSummaryToDismiss;
        private final String mLabel;

        private boolean mDidRun;
        private boolean mDidSystemServerCancel;

        private FutureDismissal(NotificationEntry entry, @CancellationReason int cancellationReason,
                DismissedByUserStatsCreator statsCreator) {
            mEntry = entry;
            mStatsCreator = statsCreator;
            mSummaryToDismiss = fetchSummaryToDismiss(entry);
            mLabel = "<FutureDismissal@" + Integer.toHexString(hashCode())
                    + " entry=" + logKey(mEntry)
                    + " reason=" + cancellationReasonDebugString(cancellationReason)
                    + " summary=" + logKey(mSummaryToDismiss)
                    + ">";
        }

        @Nullable
        private NotificationEntry fetchSummaryToDismiss(NotificationEntry entry) {
            if (isOnlyChildInGroup(entry)) {
                String group = entry.getSbn().getGroupKey();
                NotificationEntry summary = getGroupSummary(group);
                if (summary != null && summary.isDismissable()) return summary;
            }
            return null;
        }

        /** called when the entry has been removed from the collection */
        public void onSystemServerCancel(@CancellationReason int cancellationReason) {
            Assert.isMainThread();
            if (mDidSystemServerCancel) {
                mLogger.logFutureDismissalDoubleCancelledByServer(this);
                return;
            }
            mLogger.logFutureDismissalGotSystemServerCancel(this, cancellationReason);
            mDidSystemServerCancel = true;
            // TODO: Internally dismiss the summary now instead of waiting for onUiCancel
        }

        private void onUiCancel() {
            mFutureDismissals.remove(mEntry.getKey());
            final NotificationEntry currentEntry = getEntry(mEntry.getKey());
            // generate stats for the entry before dismissing summary, which could affect state
            final DismissedByUserStats stats = mStatsCreator.createDismissedByUserStats(mEntry);
            // dismiss the summary (if it exists)
            if (mSummaryToDismiss != null) {
                final NotificationEntry currentSummary = getEntry(mSummaryToDismiss.getKey());
                if (currentSummary == mSummaryToDismiss) {
                    mLogger.logFutureDismissalDismissing(this, "summary");
                    dismissNotification(mSummaryToDismiss,
                            mStatsCreator.createDismissedByUserStats(mSummaryToDismiss));
                } else {
                    mLogger.logFutureDismissalMismatchedEntry(this, "summary", currentSummary);
                }
            }
            // dismiss this entry (if it is still around)
            if (mDidSystemServerCancel) {
                mLogger.logFutureDismissalAlreadyCancelledByServer(this);
            } else if (currentEntry == mEntry) {
                mLogger.logFutureDismissalDismissing(this, "entry");
                dismissNotification(mEntry, stats);
            } else {
                mLogger.logFutureDismissalMismatchedEntry(this, "entry", currentEntry);
            }
        }

        /** called when the dismissal should be completed */
        @Override
        public void run() {
            Assert.isMainThread();
            if (mDidRun) {
                mLogger.logFutureDismissalDoubleRun(this);
                return;
            }
            mDidRun = true;
            onUiCancel();
        }

        /** provides a debug label for this instance */
        public String getLabel() {
            return mLabel;
        }
    }

    @IntDef(prefix = { "REASON_" }, value = {
            REASON_NOT_CANCELED,
            REASON_UNKNOWN,
            REASON_CLICK,
            REASON_CANCEL,
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
            REASON_CHANNEL_REMOVED,
            REASON_CLEAR_DATA,
            REASON_ASSISTANT_CANCEL,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CancellationReason {}

    static final int REASON_NOT_CANCELED = -1;
    public static final int REASON_UNKNOWN = 0;

    private static final long INITIALIZATION_FORGIVENESS_WINDOW = TimeUnit.SECONDS.toMillis(5);
}
