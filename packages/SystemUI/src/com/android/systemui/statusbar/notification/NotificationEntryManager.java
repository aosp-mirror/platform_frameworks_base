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

import static com.android.systemui.bubbles.BubbleController.DEBUG_DEMOTE_TO_NOTIF;
import static com.android.systemui.statusbar.NotificationRemoteInputManager.FORCE_REMOTE_INPUT_HISTORY;
import static com.android.systemui.statusbar.notification.row.NotificationInflater.FLAG_CONTENT_VIEW_AMBIENT;
import static com.android.systemui.statusbar.notification.row.NotificationInflater.FLAG_CONTENT_VIEW_HEADS_UP;

import android.annotation.Nullable;
import android.app.Notification;
import android.content.Context;
import android.os.Handler;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationStats;
import android.service.notification.StatusBarNotification;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.Dependency;
import com.android.systemui.Dumpable;
import com.android.systemui.ForegroundServiceController;
import com.android.systemui.bubbles.BubbleController;
import com.android.systemui.statusbar.AlertingNotificationManager;
import com.android.systemui.statusbar.AmbientPulseManager;
import com.android.systemui.statusbar.NotificationLifetimeExtender;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationUiAdjustment;
import com.android.systemui.statusbar.NotificationUpdateHandler;
import com.android.systemui.statusbar.notification.NotificationData.KeyguardEnvironment;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.notification.row.NotificationInflater;
import com.android.systemui.statusbar.notification.row.NotificationInflater.InflationFlag;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.phone.ShadeController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.util.leak.LeakDetector;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * NotificationEntryManager is responsible for the adding, removing, and updating of notifications.
 * It also handles tasks such as their inflation and their interaction with other
 * Notification.*Manager objects.
 */
public class NotificationEntryManager implements
        Dumpable,
        NotificationInflater.InflationCallback,
        NotificationUpdateHandler,
        VisualStabilityManager.Callback,
        BubbleController.BubbleDismissListener {
    private static final String TAG = "NotificationEntryMgr";
    protected static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    public static final long RECENTLY_ALERTED_THRESHOLD_MS = TimeUnit.SECONDS.toMillis(30);

    protected final Context mContext;
    protected final HashMap<String, NotificationData.Entry> mPendingNotifications = new HashMap<>();

    private final NotificationGroupManager mGroupManager =
            Dependency.get(NotificationGroupManager.class);
    private final NotificationGutsManager mGutsManager =
            Dependency.get(NotificationGutsManager.class);
    private final DeviceProvisionedController mDeviceProvisionedController =
            Dependency.get(DeviceProvisionedController.class);
    private final VisualStabilityManager mVisualStabilityManager =
            Dependency.get(VisualStabilityManager.class);
    private final ForegroundServiceController mForegroundServiceController =
            Dependency.get(ForegroundServiceController.class);
    private final AmbientPulseManager mAmbientPulseManager =
            Dependency.get(AmbientPulseManager.class);
    private final BubbleController mBubbleController = Dependency.get(BubbleController.class);
    private final NotificationInterruptionStateProvider mNotificationInterruptionStateProvider =
            Dependency.get(NotificationInterruptionStateProvider.class);

    // Lazily retrieved dependencies
    private NotificationRemoteInputManager mRemoteInputManager;
    private NotificationMediaManager mMediaManager;
    private NotificationListener mNotificationListener;
    private ShadeController mShadeController;
    private NotificationRowBinder mNotificationRowBinder;

    private final Handler mDeferredNotificationViewUpdateHandler;
    private Runnable mUpdateNotificationViewsCallback;

    protected IStatusBarService mBarService;
    private NotificationPresenter mPresenter;
    private Callback mCallback;
    protected PowerManager mPowerManager;
    private NotificationListenerService.RankingMap mLatestRankingMap;
    protected HeadsUpManager mHeadsUpManager;
    protected NotificationData mNotificationData;
    protected NotificationListContainer mListContainer;
    @VisibleForTesting
    final ArrayList<NotificationLifetimeExtender> mNotificationLifetimeExtenders
            = new ArrayList<>();
    private final List<NotificationEntryListener> mNotificationEntryListeners = new ArrayList<>();

    private final DeviceProvisionedController.DeviceProvisionedListener
            mDeviceProvisionedListener =
            new DeviceProvisionedController.DeviceProvisionedListener() {
                @Override
                public void onDeviceProvisionedChanged() {
                    updateNotifications();
                }
            };

    public void destroy() {
        mDeviceProvisionedController.removeCallback(mDeviceProvisionedListener);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("NotificationEntryManager state:");
        pw.print("  mPendingNotifications=");
        if (mPendingNotifications.size() == 0) {
            pw.println("null");
        } else {
            for (NotificationData.Entry entry : mPendingNotifications.values()) {
                pw.println(entry.notification);
            }
        }
    }

    public NotificationEntryManager(Context context) {
        mContext = context;
        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));
        mBubbleController.setDismissListener(this /* bubbleEventListener */);
        mNotificationData = new NotificationData();
        mDeferredNotificationViewUpdateHandler = new Handler();
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

    private NotificationMediaManager getMediaManager() {
        if (mMediaManager == null) {
            mMediaManager = Dependency.get(NotificationMediaManager.class);
        }
        return mMediaManager;
    }

    private NotificationListener getNotificationListener() {
        if (mNotificationListener == null) {
            mNotificationListener = Dependency.get(NotificationListener.class);
        }
        return mNotificationListener;
    }

    private ShadeController getShadeController() {
        if (mShadeController == null) {
            mShadeController = Dependency.get(ShadeController.class);
        }
        return mShadeController;
    }

    private NotificationRowBinder getRowBinder() {
        if (mNotificationRowBinder == null) {
            mNotificationRowBinder = Dependency.get(NotificationRowBinder.class);
        }
        return mNotificationRowBinder;
    }

    public void setUpWithPresenter(NotificationPresenter presenter,
            NotificationListContainer listContainer, Callback callback,
            HeadsUpManager headsUpManager) {
        mPresenter = presenter;
        mUpdateNotificationViewsCallback = mPresenter::updateNotificationViews;
        mCallback = callback;
        mHeadsUpManager = headsUpManager;
        mNotificationData.setHeadsUpManager(mHeadsUpManager);
        mListContainer = listContainer;

        mNotificationLifetimeExtenders.add(mHeadsUpManager);
        mNotificationLifetimeExtenders.add(mAmbientPulseManager);
        mNotificationLifetimeExtenders.add(mGutsManager);
        mNotificationLifetimeExtenders.addAll(getRemoteInputManager().getLifetimeExtenders());

        for (NotificationLifetimeExtender extender : mNotificationLifetimeExtenders) {
            extender.setCallback(key -> removeNotification(key, mLatestRankingMap));
        }

        mDeviceProvisionedController.addCallback(mDeviceProvisionedListener);
    }

    public NotificationData getNotificationData() {
        return mNotificationData;
    }

    protected Context getContext() {
        return mContext;
    }

    protected NotificationPresenter getPresenter() {
        return mPresenter;
    }

    public ExpandableNotificationRow.LongPressListener getNotificationLongClicker() {
        return getRowBinder().getNotificationLongClicker();
    }

    @Override
    public void onReorderingAllowed() {
        updateNotifications();
    }

    public void performRemoveNotification(StatusBarNotification n) {
        final int rank = mNotificationData.getRank(n.getKey());
        final int count = mNotificationData.getActiveNotifications().size();
        final NotificationVisibility nv = NotificationVisibility.obtain(n.getKey(), rank, count,
                true);
        NotificationData.Entry entry = mNotificationData.get(n.getKey());

        getRemoteInputManager().onPerformRemoveNotification(n, entry);
        final String pkg = n.getPackageName();
        final String tag = n.getTag();
        final int id = n.getId();
        final int userId = n.getUserId();
        try {
            int dismissalSurface = NotificationStats.DISMISSAL_SHADE;
            if (mHeadsUpManager.isAlerting(n.getKey())) {
                dismissalSurface = NotificationStats.DISMISSAL_PEEK;
            } else if (mListContainer.hasPulsingNotifications()) {
                dismissalSurface = NotificationStats.DISMISSAL_AOD;
            }
            int dismissalSentiment = NotificationStats.DISMISS_SENTIMENT_NEUTRAL;
            mBarService.onNotificationClear(pkg, tag, id, userId, n.getKey(), dismissalSurface,
                    dismissalSentiment, nv);
            removeNotification(n.getKey(), null);

        } catch (RemoteException ex) {
            // system process is dead if we're here.
        }

        mCallback.onPerformRemoveNotification(n);
    }

    @Override
    public void onStackDismissed() {
        updateNotifications();
    }

    @Override
    public void onBubbleDismissed(String key) {
        NotificationData.Entry entry = mNotificationData.get(key);
        if (entry != null) {
            entry.setBubbleDismissed(true);
            if (!DEBUG_DEMOTE_TO_NOTIF) {
                performRemoveNotification(entry.notification);
            }
        }
        updateNotifications();
    }

    private void abortExistingInflation(String key) {
        if (mPendingNotifications.containsKey(key)) {
            NotificationData.Entry entry = mPendingNotifications.get(key);
            entry.abortTask();
            mPendingNotifications.remove(key);
        }
        NotificationData.Entry addedEntry = mNotificationData.get(key);
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
        removeNotificationInternal(n.getKey(), null, true /* forceRemove */);
        try {
            mBarService.onNotificationError(n.getPackageName(), n.getTag(), n.getId(), n.getUid(),
                    n.getInitialPid(), e.getMessage(), n.getUserId());
        } catch (RemoteException ex) {
            // The end is nigh.
        }
    }

    private void addEntry(NotificationData.Entry shadeEntry) {
        if (shadeEntry == null) {
            return;
        }
        // Add the expanded view and icon.
        mNotificationData.add(shadeEntry);
        tagForeground(shadeEntry.notification);
        updateNotifications();
        mCallback.onNotificationAdded(shadeEntry);

        maybeScheduleUpdateNotificationViews(shadeEntry);
    }

    private void maybeScheduleUpdateNotificationViews(NotificationData.Entry entry) {
        long audibleAlertTimeout = RECENTLY_ALERTED_THRESHOLD_MS
                - (System.currentTimeMillis() - entry.lastAudiblyAlertedMs);
        if (audibleAlertTimeout > 0) {
            mDeferredNotificationViewUpdateHandler.postDelayed(
                    mUpdateNotificationViewsCallback, audibleAlertTimeout);
        }
    }

    /**
     * Adds the entry to the respective alerting manager if the content view was inflated and
     * the entry should still alert.
     *
     * @param entry entry to add
     * @param inflatedFlags flags representing content views that were inflated
     */
    private void showAlertingView(NotificationData.Entry entry,
            @InflationFlag int inflatedFlags) {
        if ((inflatedFlags & FLAG_CONTENT_VIEW_HEADS_UP) != 0) {
            // Possible for shouldHeadsUp to change between the inflation starting and ending.
            // If it does and we no longer need to heads up, we should free the view.
            if (mNotificationInterruptionStateProvider.shouldHeadsUp(entry)) {
                mHeadsUpManager.showNotification(entry);
                // Mark as seen immediately
                setNotificationShown(entry.notification);
            } else {
                entry.freeContentViewWhenSafe(FLAG_CONTENT_VIEW_HEADS_UP);
            }
        }
        if ((inflatedFlags & FLAG_CONTENT_VIEW_AMBIENT) != 0) {
            if (mNotificationInterruptionStateProvider.shouldPulse(entry)) {
                mAmbientPulseManager.showNotification(entry);
            } else {
                entry.freeContentViewWhenSafe(FLAG_CONTENT_VIEW_AMBIENT);
            }
        }
    }

    @Override
    public void onAsyncInflationFinished(NotificationData.Entry entry,
            @InflationFlag int inflatedFlags) {
        mPendingNotifications.remove(entry.key);
        // If there was an async task started after the removal, we don't want to add it back to
        // the list, otherwise we might get leaks.
        if (!entry.isRowRemoved()) {
            boolean isNew = mNotificationData.get(entry.key) == null;
            if (isNew) {
                showAlertingView(entry, inflatedFlags);
                addEntry(entry);
            } else {
                if (entry.getRow().hasLowPriorityStateUpdated()) {
                    mVisualStabilityManager.onLowPriorityUpdated(entry);
                    mPresenter.updateNotificationViews();
                }
                for (NotificationEntryListener listener : mNotificationEntryListeners) {
                    listener.onEntryReinflated(entry);
                }
            }
        }
        entry.setLowPriorityStateUpdated(false);
    }

    @Override
    public void removeNotification(String key, NotificationListenerService.RankingMap ranking) {
        removeNotificationInternal(key, ranking, false /* forceRemove */);
    }

    private void removeNotificationInternal(String key,
            @Nullable NotificationListenerService.RankingMap ranking, boolean forceRemove) {
        final NotificationData.Entry entry = mNotificationData.get(key);

        abortExistingInflation(key);
        if (entry != null) {
            for (NotificationEntryListener listener : mNotificationEntryListeners) {
                listener.onEntryRemoved(entry);
            }
        }

        // Attempt to remove notifications from their alert managers (heads up, ambient pulse).
        // Though the remove itself may fail, it lets the manager know to remove as soon as
        // possible.
        if (mHeadsUpManager.isAlerting(key)) {
            // A cancel() in response to a remote input shouldn't be delayed, as it makes the
            // sending look longer than it takes.
            // Also we should not defer the removal if reordering isn't allowed since otherwise
            // some notifications can't disappear before the panel is closed.
            boolean ignoreEarliestRemovalTime = getRemoteInputManager().getController().isSpinning(key)
                    && !FORCE_REMOTE_INPUT_HISTORY
                    || !mVisualStabilityManager.isReorderingAllowed();
            mHeadsUpManager.removeNotification(key, ignoreEarliestRemovalTime);
        }
        if (mAmbientPulseManager.isAlerting(key)) {
            mAmbientPulseManager.removeNotification(key, false /* ignoreEarliestRemovalTime */);
        }

        if (entry == null) {
            mCallback.onNotificationRemoved(key, null /* old */);
            return;
        }

        // If a manager needs to keep the notification around for whatever reason, we return early
        // and keep the notification
        if (!forceRemove) {
            for (NotificationLifetimeExtender extender : mNotificationLifetimeExtenders) {
                if (extender.shouldExtendLifetime(entry)) {
                    mLatestRankingMap = ranking;
                    extender.setShouldManageLifetime(entry, true /* shouldManage */);
                    return;
                }
            }
        }

        // At this point, we are guaranteed the notification will be removed

        // Ensure any managers keeping the lifetime extended stop managing the entry
        for (NotificationLifetimeExtender extender: mNotificationLifetimeExtenders) {
            extender.setShouldManageLifetime(entry, false /* shouldManage */);
        }

        getMediaManager().onNotificationRemoved(key);
        mForegroundServiceController.removeNotification(entry.notification);

        if (entry.rowExists()) {
            entry.removeRow();
            mListContainer.cleanUpViewStateForEntry(entry);
        }

        // Let's remove the children if this was a summary
        handleGroupSummaryRemoved(key);

        StatusBarNotification old = removeNotificationViews(key, ranking);

        mCallback.onNotificationRemoved(key, old);
    }

    private StatusBarNotification removeNotificationViews(String key,
            NotificationListenerService.RankingMap ranking) {
        NotificationData.Entry entry = mNotificationData.remove(key, ranking);
        if (entry == null) {
            Log.w(TAG, "removeNotification for unknown key: " + key);
            return null;
        }
        updateNotifications();
        Dependency.get(LeakDetector.class).trackGarbage(entry);
        return entry.notification;
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
        NotificationData.Entry entry = mNotificationData.get(key);
        if (entry != null && entry.rowExists() && entry.isSummaryWithChildren()) {
            if (entry.notification.getOverrideGroupKey() != null && !entry.isRowDismissed()) {
                // We don't want to remove children for autobundled notifications as they are not
                // always cancelled. We only remove them if they were dismissed by the user.
                return;
            }
            List<NotificationData.Entry> childEntries = entry.getChildren();
            if (childEntries == null) {
                return;
            }
            for (int i = 0; i < childEntries.size(); i++) {
                NotificationData.Entry childEntry = childEntries.get(i);
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

    public void updateNotificationsOnDensityOrFontScaleChanged() {
        ArrayList<NotificationData.Entry> userNotifications =
                mNotificationData.getNotificationsForCurrentUser();
        for (int i = 0; i < userNotifications.size(); i++) {
            NotificationData.Entry entry = userNotifications.get(i);
            entry.onDensityOrFontScaleChanged();
            boolean exposedGuts = entry.areGutsExposed();
            if (exposedGuts) {
                mGutsManager.onDensityOrFontScaleChanged(entry);
            }
        }
    }

    private NotificationData.Entry createNotificationEntry(
            StatusBarNotification sbn, NotificationListenerService.Ranking ranking)
            throws InflationException {
        if (DEBUG) {
            Log.d(TAG, "createNotificationEntry(notification=" + sbn + " " + ranking);
        }

        NotificationData.Entry entry = new NotificationData.Entry(sbn, ranking);
        if (BubbleController.shouldAutoBubble(getContext(), entry)) {
            entry.setIsBubble(true);
        }

        Dependency.get(LeakDetector.class).trackInstance(entry);
        entry.createIcons(mContext, sbn);
        // Construct the expanded view.
        getRowBinder().inflateViews(entry, () -> performRemoveNotification(sbn),
                mNotificationData.get(entry.key) != null);
        return entry;
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
        NotificationData.Entry entry = createNotificationEntry(notification, ranking);
        abortExistingInflation(key);

        mForegroundServiceController.addNotification(notification,
                mNotificationData.getImportance(key));

        mPendingNotifications.put(key, entry);
        for (NotificationEntryListener listener : mNotificationEntryListeners) {
            listener.onPendingEntryAdded(entry);
        }
    }

    @VisibleForTesting
    void tagForeground(StatusBarNotification notification) {
        ArraySet<Integer> activeOps = mForegroundServiceController.getAppOps(
                notification.getUserId(), notification.getPackageName());
        if (activeOps != null) {
            int N = activeOps.size();
            for (int i = 0; i < N; i++) {
                updateNotificationsForAppOp(activeOps.valueAt(i), notification.getUid(),
                        notification.getPackageName(), true);
            }
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

    public void updateNotificationsForAppOp(int appOp, int uid, String pkg, boolean showIcon) {
        String foregroundKey = mForegroundServiceController.getStandardLayoutKey(
                UserHandle.getUserId(uid), pkg);
        if (foregroundKey != null) {
            mNotificationData.updateAppOp(appOp, uid, pkg, foregroundKey, showIcon);
            updateNotifications();
        }
    }

    private boolean alertAgain(NotificationData.Entry oldEntry, Notification newNotification) {
        return oldEntry == null || !oldEntry.hasInterrupted()
                || (newNotification.flags & Notification.FLAG_ONLY_ALERT_ONCE) == 0;
    }

    private void updateNotificationInternal(StatusBarNotification notification,
            NotificationListenerService.RankingMap ranking) throws InflationException {
        if (DEBUG) Log.d(TAG, "updateNotification(" + notification + ")");

        final String key = notification.getKey();
        abortExistingInflation(key);
        NotificationData.Entry entry = mNotificationData.get(key);
        if (entry == null) {
            return;
        }

        // Notification is updated so it is essentially re-added and thus alive again.  Don't need
        // to keep its lifetime extended.
        for (NotificationLifetimeExtender extender : mNotificationLifetimeExtenders) {
            extender.setShouldManageLifetime(entry, false /* shouldManage */);
        }

        mNotificationData.updateRanking(ranking);

        final StatusBarNotification oldNotification = entry.notification;
        entry.notification = notification;
        mGroupManager.onEntryUpdated(entry, oldNotification);

        entry.updateIcons(mContext, notification);
        getRowBinder().inflateViews(entry, () -> performRemoveNotification(notification),
                mNotificationData.get(entry.key) != null);

        mForegroundServiceController.updateNotification(notification,
                mNotificationData.getImportance(key));

        boolean alertAgain = alertAgain(entry, entry.notification.getNotification());
        if (getShadeController().isDozing()) {
            updateAlertState(entry, mNotificationInterruptionStateProvider.shouldPulse(entry),
                    alertAgain, mAmbientPulseManager);
        } else {
            updateAlertState(entry, mNotificationInterruptionStateProvider.shouldHeadsUp(entry),
                    alertAgain, mHeadsUpManager);
        }
        updateNotifications();

        if (!notification.isClearable()) {
            // The user may have performed a dismiss action on the notification, since it's
            // not clearable we should snap it back.
            mListContainer.snapViewIfNeeded(entry);
        }

        if (DEBUG) {
            // Is this for you?
            boolean isForCurrentUser = Dependency.get(KeyguardEnvironment.class)
                    .isNotificationForCurrentProfiles(notification);
            Log.d(TAG, "notification is " + (isForCurrentUser ? "" : "not ") + "for you");
        }

        mCallback.onNotificationUpdated(notification);

        maybeScheduleUpdateNotificationViews(entry);
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

        mPresenter.updateNotificationViews();
    }

    public void updateNotificationRanking(NotificationListenerService.RankingMap rankingMap) {
        List<NotificationData.Entry> entries = new ArrayList<>();
        entries.addAll(mNotificationData.getActiveNotifications());
        entries.addAll(mPendingNotifications.values());

        // Has a copy of the current UI adjustments.
        ArrayMap<String, NotificationUiAdjustment> oldAdjustments = new ArrayMap<>();
        ArrayMap<String, Integer> oldImportances = new ArrayMap<>();
        for (NotificationData.Entry entry : entries) {
            NotificationUiAdjustment adjustment =
                    NotificationUiAdjustment.extractFromNotificationEntry(entry);
            oldAdjustments.put(entry.key, adjustment);
            oldImportances.put(entry.key, entry.importance);
        }

        // Populate notification entries from the new rankings.
        mNotificationData.updateRanking(rankingMap);
        updateRankingOfPendingNotifications(rankingMap);

        // By comparing the old and new UI adjustments, reinflate the view accordingly.
        for (NotificationData.Entry entry : entries) {
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
        for (NotificationData.Entry pendingNotification : mPendingNotifications.values()) {
            rankingMap.getRanking(pendingNotification.key, tmpRanking);
            pendingNotification.populateFromRanking(tmpRanking);
        }
    }

    private void setNotificationShown(StatusBarNotification n) {
        setNotificationsShown(new String[]{n.getKey()});
    }

    protected void setNotificationsShown(String[] keys) {
        try {
            getNotificationListener().setNotificationsShown(keys);
        } catch (RuntimeException e) {
            Log.d(TAG, "failed setNotificationsShown: ", e);
        }
    }

    /**
     * Update the entry's alert state and call the appropriate {@link AlertingNotificationManager}
     * method.
     * @param entry entry to update
     * @param shouldAlert whether or not it should be alerting
     * @param alertAgain whether or not an alert should actually come in as if it were new
     * @param alertManager the alerting notification manager that manages the alert state
     */
    private void updateAlertState(NotificationData.Entry entry, boolean shouldAlert,
            boolean alertAgain, AlertingNotificationManager alertManager) {
        final boolean wasAlerting = alertManager.isAlerting(entry.key);
        if (wasAlerting) {
            if (!shouldAlert) {
                // We don't want this to be interrupting anymore, lets remove it
                alertManager.removeNotification(entry.key,
                        false /* ignoreEarliestRemovalTime */);
            } else {
                alertManager.updateNotification(entry.key, alertAgain);
            }
        } else if (shouldAlert && alertAgain) {
            // This notification was updated to be alerting, show it!
            alertManager.showNotification(entry);
        }
    }

    /**
     * @return An iterator for all "pending" notifications. Pending notifications are newly-posted
     * notifications whose views have not yet been inflated. In general, the system pretends like
     * these don't exist, although there are a couple exceptions.
     */
    public Iterable<NotificationData.Entry> getPendingNotificationsIterator() {
        return mPendingNotifications.values();
    }

    /**
     * Callback for NotificationEntryManager.
     */
    public interface Callback {

        /**
         * Called when a new entry is created.
         *
         * @param shadeEntry entry that was created
         */
        void onNotificationAdded(NotificationData.Entry shadeEntry);

        /**
         * Called when a notification was updated.
         *
         * @param notification notification that was updated
         */
        void onNotificationUpdated(StatusBarNotification notification);

        /**
         * Called when a notification was removed.
         *
         * @param key key of notification that was removed
         * @param old StatusBarNotification of the notification before it was removed
         */
        void onNotificationRemoved(String key, StatusBarNotification old);

        /**
         * Removes a notification immediately.
         *
         * @param statusBarNotification notification that is being removed
         */
        void onPerformRemoveNotification(StatusBarNotification statusBarNotification);
    }
}
