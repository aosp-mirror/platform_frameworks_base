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

import static com.android.systemui.statusbar.NotificationRemoteInputManager.ENABLE_REMOTE_INPUT;
import static com.android.systemui.statusbar.NotificationRemoteInputManager
        .FORCE_REMOTE_INPUT_HISTORY;
import static com.android.systemui.statusbar.notification.row.NotificationInflater
        .FLAG_CONTENT_VIEW_AMBIENT;
import static com.android.systemui.statusbar.notification.row.NotificationInflater
        .FLAG_CONTENT_VIEW_HEADS_UP;

import android.annotation.Nullable;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationStats;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.internal.util.NotificationMessagingUtil;
import com.android.systemui.DejankUtils;
import com.android.systemui.Dependency;
import com.android.systemui.Dumpable;
import com.android.systemui.EventLogTags;
import com.android.systemui.ForegroundServiceController;
import com.android.systemui.R;
import com.android.systemui.UiOffloadThread;
import com.android.systemui.statusbar.NotificationLifetimeExtender;
import com.android.systemui.statusbar.AlertingNotificationManager;
import com.android.systemui.statusbar.AmbientPulseManager;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationUiAdjustment;
import com.android.systemui.statusbar.NotificationUpdateHandler;
import com.android.systemui.statusbar.notification.row.NotificationInflater;
import com.android.systemui.statusbar.notification.row.NotificationInflater.InflationFlag;
import com.android.systemui.statusbar.notification.row.RowInflaterTask;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.util.leak.LeakDetector;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * NotificationEntryManager is responsible for the adding, removing, and updating of notifications.
 * It also handles tasks such as their inflation and their interaction with other
 * Notification.*Manager objects.
 */
public class NotificationEntryManager implements Dumpable, NotificationInflater.InflationCallback,
        ExpandableNotificationRow.ExpansionLogger, NotificationUpdateHandler,
        VisualStabilityManager.Callback {
    private static final String TAG = "NotificationEntryMgr";
    protected static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    protected static final boolean ENABLE_HEADS_UP = true;
    protected static final String SETTING_HEADS_UP_TICKER = "ticker_gets_heads_up";

    protected final NotificationMessagingUtil mMessagingUtil;
    protected final Context mContext;
    protected final HashMap<String, NotificationData.Entry> mPendingNotifications = new HashMap<>();
    protected final NotificationClicker mNotificationClicker = new NotificationClicker();

    // Dependencies:
    protected final NotificationLockscreenUserManager mLockscreenUserManager =
            Dependency.get(NotificationLockscreenUserManager.class);
    protected final NotificationGroupManager mGroupManager =
            Dependency.get(NotificationGroupManager.class);
    protected final NotificationGutsManager mGutsManager =
            Dependency.get(NotificationGutsManager.class);
    protected final NotificationRemoteInputManager mRemoteInputManager =
            Dependency.get(NotificationRemoteInputManager.class);
    protected final NotificationMediaManager mMediaManager =
            Dependency.get(NotificationMediaManager.class);
    protected final MetricsLogger mMetricsLogger = Dependency.get(MetricsLogger.class);
    protected final DeviceProvisionedController mDeviceProvisionedController =
            Dependency.get(DeviceProvisionedController.class);
    protected final VisualStabilityManager mVisualStabilityManager =
            Dependency.get(VisualStabilityManager.class);
    protected final UiOffloadThread mUiOffloadThread = Dependency.get(UiOffloadThread.class);
    protected final ForegroundServiceController mForegroundServiceController =
            Dependency.get(ForegroundServiceController.class);
    protected final NotificationListener mNotificationListener =
            Dependency.get(NotificationListener.class);
    protected AmbientPulseManager mAmbientPulseManager = Dependency.get(AmbientPulseManager.class);

    protected IDreamManager mDreamManager;
    protected IStatusBarService mBarService;
    protected NotificationPresenter mPresenter;
    protected Callback mCallback;
    protected PowerManager mPowerManager;
    protected NotificationListenerService.RankingMap mLatestRankingMap;
    protected HeadsUpManager mHeadsUpManager;
    protected NotificationData mNotificationData;
    protected ContentObserver mHeadsUpObserver;
    protected boolean mUseHeadsUp = false;
    protected boolean mDisableNotificationAlerts;
    protected NotificationListContainer mListContainer;
    protected final ArrayList<NotificationLifetimeExtender> mNotificationLifetimeExtenders
            = new ArrayList<>();
    private ExpandableNotificationRow.OnAppOpsClickListener mOnAppOpsClickListener;


    private final class NotificationClicker implements View.OnClickListener {

        @Override
        public void onClick(final View v) {
            if (!(v instanceof ExpandableNotificationRow)) {
                Log.e(TAG, "NotificationClicker called on a view that is not a notification row.");
                return;
            }

            mPresenter.wakeUpIfDozing(SystemClock.uptimeMillis(), v);

            final ExpandableNotificationRow row = (ExpandableNotificationRow) v;
            final StatusBarNotification sbn = row.getStatusBarNotification();
            if (sbn == null) {
                Log.e(TAG, "NotificationClicker called on an unclickable notification,");
                return;
            }

            // Check if the notification is displaying the menu, if so slide notification back
            if (row.getProvider() != null && row.getProvider().isMenuVisible()) {
                row.animateTranslateNotification(0);
                return;
            }

            // Mark notification for one frame.
            row.setJustClicked(true);
            DejankUtils.postAfterTraversal(() -> row.setJustClicked(false));

            mCallback.onNotificationClicked(sbn, row);
        }

        public void register(ExpandableNotificationRow row, StatusBarNotification sbn) {
            Notification notification = sbn.getNotification();
            if (notification.contentIntent != null || notification.fullScreenIntent != null) {
                row.setOnClickListener(this);
            } else {
                row.setOnClickListener(null);
            }
        }
    }

    private final DeviceProvisionedController.DeviceProvisionedListener
            mDeviceProvisionedListener =
            new DeviceProvisionedController.DeviceProvisionedListener() {
                @Override
                public void onDeviceProvisionedChanged() {
                    updateNotifications();
                }
            };

    public void setDisableNotificationAlerts(boolean disableNotificationAlerts) {
        mDisableNotificationAlerts = disableNotificationAlerts;
        mHeadsUpObserver.onChange(true);
    }

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
        pw.print("  mUseHeadsUp=");
        pw.println(mUseHeadsUp);
    }

    public NotificationEntryManager(Context context) {
        mContext = context;
        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));
        mDreamManager = IDreamManager.Stub.asInterface(
                ServiceManager.checkService(DreamService.DREAM_SERVICE));
        mMessagingUtil = new NotificationMessagingUtil(context);
        mGroupManager.setPendingEntries(mPendingNotifications);
    }

    public void setUpWithPresenter(NotificationPresenter presenter,
            NotificationListContainer listContainer, Callback callback,
            HeadsUpManager headsUpManager) {
        mPresenter = presenter;
        mCallback = callback;
        mNotificationData = new NotificationData(presenter);
        mHeadsUpManager = headsUpManager;
        mNotificationData.setHeadsUpManager(mHeadsUpManager);
        mListContainer = listContainer;

        mHeadsUpObserver = new ContentObserver(mPresenter.getHandler()) {
            @Override
            public void onChange(boolean selfChange) {
                boolean wasUsing = mUseHeadsUp;
                mUseHeadsUp = ENABLE_HEADS_UP && !mDisableNotificationAlerts
                        && Settings.Global.HEADS_UP_OFF != Settings.Global.getInt(
                        mContext.getContentResolver(),
                        Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED,
                        Settings.Global.HEADS_UP_OFF);
                Log.d(TAG, "heads up is " + (mUseHeadsUp ? "enabled" : "disabled"));
                if (wasUsing != mUseHeadsUp) {
                    if (!mUseHeadsUp) {
                        Log.d(TAG,
                                "dismissing any existing heads up notification on disable event");
                        mHeadsUpManager.releaseAllImmediately();
                    }
                }
            }
        };

        if (ENABLE_HEADS_UP) {
            mContext.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED),
                    true,
                    mHeadsUpObserver);
            mContext.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(SETTING_HEADS_UP_TICKER), true,
                    mHeadsUpObserver);
        }

        mNotificationLifetimeExtenders.add(mHeadsUpManager);
        mNotificationLifetimeExtenders.add(mAmbientPulseManager);
        mNotificationLifetimeExtenders.add(mGutsManager);
        mNotificationLifetimeExtenders.addAll(mRemoteInputManager.getLifetimeExtenders());

        for (NotificationLifetimeExtender extender : mNotificationLifetimeExtenders) {
            extender.setCallback(key -> removeNotification(key, mLatestRankingMap));
        }

        mDeviceProvisionedController.addCallback(mDeviceProvisionedListener);

        mHeadsUpObserver.onChange(true); // set up
        mOnAppOpsClickListener = mGutsManager::openGuts;
    }

    public NotificationData getNotificationData() {
        return mNotificationData;
    }

    public ExpandableNotificationRow.LongPressListener getNotificationLongClicker() {
        return mGutsManager::openGuts;
    }

    @Override
    public void logNotificationExpansion(String key, boolean userAction, boolean expanded) {
        mUiOffloadThread.submit(() -> {
            try {
                mBarService.onNotificationExpansionChanged(key, userAction, expanded);
            } catch (RemoteException e) {
                // Ignore.
            }
        });
    }

    @Override
    public void onReorderingAllowed() {
        updateNotifications();
    }

    private boolean shouldSuppressFullScreenIntent(NotificationData.Entry entry) {
        if (mPresenter.isDeviceInVrMode()) {
            return true;
        }

        return mNotificationData.shouldSuppressFullScreenIntent(entry);
    }

    private void inflateViews(NotificationData.Entry entry, ViewGroup parent) {
        PackageManager pmUser = StatusBar.getPackageManagerForUser(mContext,
                entry.notification.getUser().getIdentifier());

        final StatusBarNotification sbn = entry.notification;
        if (entry.row != null) {
            entry.reset();
            updateNotification(entry, pmUser, sbn, entry.row);
        } else {
            new RowInflaterTask().inflate(mContext, parent, entry,
                    row -> {
                        bindRow(entry, pmUser, sbn, row);
                        updateNotification(entry, pmUser, sbn, row);
                    });
        }
    }

    private void bindRow(NotificationData.Entry entry, PackageManager pmUser,
            StatusBarNotification sbn, ExpandableNotificationRow row) {
        row.setExpansionLogger(this, entry.notification.getKey());
        row.setGroupManager(mGroupManager);
        row.setHeadsUpManager(mHeadsUpManager);
        row.setOnExpandClickListener(mPresenter);
        row.setInflationCallback(this);
        row.setLongPressListener(getNotificationLongClicker());
        mListContainer.bindRow(row);
        mRemoteInputManager.bindRow(row);

        // Get the app name.
        // Note that Notification.Builder#bindHeaderAppName has similar logic
        // but since this field is used in the guts, it must be accurate.
        // Therefore we will only show the application label, or, failing that, the
        // package name. No substitutions.
        final String pkg = sbn.getPackageName();
        String appname = pkg;
        try {
            final ApplicationInfo info = pmUser.getApplicationInfo(pkg,
                    PackageManager.MATCH_UNINSTALLED_PACKAGES
                            | PackageManager.MATCH_DISABLED_COMPONENTS);
            if (info != null) {
                appname = String.valueOf(pmUser.getApplicationLabel(info));
            }
        } catch (PackageManager.NameNotFoundException e) {
            // Do nothing
        }
        row.setAppName(appname);
        row.setOnDismissRunnable(() ->
                performRemoveNotification(row.getStatusBarNotification()));
        row.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        if (ENABLE_REMOTE_INPUT) {
            row.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        }

        row.setAppOpsOnClickListener(mOnAppOpsClickListener);

        mCallback.onBindRow(entry, pmUser, sbn, row);
    }

    public void performRemoveNotification(StatusBarNotification n) {
        final int rank = mNotificationData.getRank(n.getKey());
        final int count = mNotificationData.getActiveNotifications().size();
        final NotificationVisibility nv = NotificationVisibility.obtain(n.getKey(), rank, count,
                true);
        NotificationData.Entry entry = mNotificationData.get(n.getKey());

        mRemoteInputManager.onPerformRemoveNotification(n, entry);
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

    /**
     * Cancel this notification and tell the StatusBarManagerService / NotificationManagerService
     * about the failure.
     *
     * WARNING: this will call back into us.  Don't hold any locks.
     */
    void handleNotificationError(StatusBarNotification n, String message) {
        removeNotificationInternal(n.getKey(), null, true /* forceRemove */);
        try {
            mBarService.onNotificationError(n.getPackageName(), n.getTag(), n.getId(), n.getUid(),
                    n.getInitialPid(), message, n.getUserId());
        } catch (RemoteException ex) {
            // The end is nigh.
        }
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

    @Override
    public void handleInflationException(StatusBarNotification notification, Exception e) {
        handleNotificationError(notification, e.getMessage());
    }

    private void addEntry(NotificationData.Entry shadeEntry) {
        addNotificationViews(shadeEntry);
        mCallback.onNotificationAdded(shadeEntry);
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
            if (shouldHeadsUp(entry)) {
                mHeadsUpManager.showNotification(entry);
                // Mark as seen immediately
                setNotificationShown(entry.notification);
            } else {
                entry.row.freeContentViewWhenSafe(FLAG_CONTENT_VIEW_HEADS_UP);
            }
        }
        if ((inflatedFlags & FLAG_CONTENT_VIEW_AMBIENT) != 0) {
            if (shouldPulse(entry)) {
                mAmbientPulseManager.showNotification(entry);
            } else {
                entry.row.freeContentViewWhenSafe(FLAG_CONTENT_VIEW_AMBIENT);
            }
        }
    }

    @Override
    public void onAsyncInflationFinished(NotificationData.Entry entry,
            @InflationFlag int inflatedFlags) {
        mPendingNotifications.remove(entry.key);
        // If there was an async task started after the removal, we don't want to add it back to
        // the list, otherwise we might get leaks.
        boolean isNew = mNotificationData.get(entry.key) == null;
        if (isNew && !entry.row.isRemoved()) {
            showAlertingView(entry, inflatedFlags);
            addEntry(entry);
        } else if (!isNew && entry.row.hasLowPriorityStateUpdated()) {
            mVisualStabilityManager.onLowPriorityUpdated(entry);
            mPresenter.updateNotificationViews();
        }
        entry.row.setLowPriorityStateUpdated(false);
    }

    @Override
    public void removeNotification(String key, NotificationListenerService.RankingMap ranking) {
        removeNotificationInternal(key, ranking, false /* forceRemove */);
    }

    private void removeNotificationInternal(String key,
            @Nullable NotificationListenerService.RankingMap ranking, boolean forceRemove) {
        abortExistingInflation(key);

        // Attempt to remove notifications from their alert managers (heads up, ambient pulse).
        // Though the remove itself may fail, it lets the manager know to remove as soon as
        // possible.
        if (mHeadsUpManager.isAlerting(key)) {
            // A cancel() in response to a remote input shouldn't be delayed, as it makes the
            // sending look longer than it takes.
            // Also we should not defer the removal if reordering isn't allowed since otherwise
            // some notifications can't disappear before the panel is closed.
            boolean ignoreEarliestRemovalTime = mRemoteInputManager.getController().isSpinning(key)
                    && !FORCE_REMOTE_INPUT_HISTORY
                    || !mVisualStabilityManager.isReorderingAllowed();
            mHeadsUpManager.removeNotification(key, ignoreEarliestRemovalTime);
        }
        if (mAmbientPulseManager.isAlerting(key)) {
            mAmbientPulseManager.removeNotification(key, false /* ignoreEarliestRemovalTime */);
        }

        NotificationData.Entry entry = mNotificationData.get(key);

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

        mMediaManager.onNotificationRemoved(key);
        mForegroundServiceController.removeNotification(entry.notification);

        if (entry.row != null) {
            entry.row.setRemoved();
            mListContainer.cleanUpViewState(entry.row);
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
        if (entry != null && entry.row != null
                && entry.row.isSummaryWithChildren()) {
            if (entry.notification.getOverrideGroupKey() != null && !entry.row.isDismissed()) {
                // We don't want to remove children for autobundled notifications as they are not
                // always cancelled. We only remove them if they were dismissed by the user.
                return;
            }
            List<ExpandableNotificationRow> notificationChildren =
                    entry.row.getNotificationChildren();
            for (int i = 0; i < notificationChildren.size(); i++) {
                ExpandableNotificationRow row = notificationChildren.get(i);
                NotificationData.Entry childEntry = row.getEntry();
                boolean isForeground = (row.getStatusBarNotification().getNotification().flags
                        & Notification.FLAG_FOREGROUND_SERVICE) != 0;
                boolean keepForReply =
                        mRemoteInputManager.shouldKeepForRemoteInputHistory(childEntry)
                        || mRemoteInputManager.shouldKeepForSmartReplyHistory(childEntry);
                if (isForeground || keepForReply) {
                    // the child is a foreground service notification which we can't remove or it's
                    // a child we're keeping around for reply!
                    continue;
                }
                row.setKeepInParent(true);
                // we need to set this state earlier as otherwise we might generate some weird
                // animations
                row.setRemoved();
            }
        }
    }

    public void updateNotificationsOnDensityOrFontScaleChanged() {
        ArrayList<NotificationData.Entry> userNotifications =
                mNotificationData.getNotificationsForCurrentUser();
        for (int i = 0; i < userNotifications.size(); i++) {
            NotificationData.Entry entry = userNotifications.get(i);
            boolean exposedGuts = mGutsManager.getExposedGuts() != null
                    && entry.row.getGuts() == mGutsManager.getExposedGuts();
            entry.row.onDensityOrFontScaleChanged();
            if (exposedGuts) {
                mGutsManager.onDensityOrFontScaleChanged(entry.row);
            }
        }
    }

    protected void updateNotification(NotificationData.Entry entry, PackageManager pmUser,
            StatusBarNotification sbn, ExpandableNotificationRow row) {
        row.setNeedsRedaction(mLockscreenUserManager.needsRedaction(entry));
        boolean isLowPriority = mNotificationData.isAmbient(sbn.getKey());
        boolean isUpdate = mNotificationData.get(entry.key) != null;
        boolean wasLowPriority = row.isLowPriority();
        row.setIsLowPriority(isLowPriority);
        row.setLowPriorityStateUpdated(isUpdate && (wasLowPriority != isLowPriority));
        // bind the click event to the content area
        mNotificationClicker.register(row, sbn);

        // Extract target SDK version.
        try {
            ApplicationInfo info = pmUser.getApplicationInfo(sbn.getPackageName(), 0);
            entry.targetSdk = info.targetSdkVersion;
        } catch (PackageManager.NameNotFoundException ex) {
            Log.e(TAG, "Failed looking up ApplicationInfo for " + sbn.getPackageName(), ex);
        }
        row.setLegacy(entry.targetSdk >= Build.VERSION_CODES.GINGERBREAD
                && entry.targetSdk < Build.VERSION_CODES.LOLLIPOP);
        entry.setIconTag(R.id.icon_is_pre_L, entry.targetSdk < Build.VERSION_CODES.LOLLIPOP);
        entry.autoRedacted = entry.notification.getNotification().publicVersion == null;

        entry.row = row;
        entry.row.setOnActivatedListener(mPresenter);

        boolean useIncreasedCollapsedHeight = mMessagingUtil.isImportantMessaging(sbn,
                mNotificationData.getImportance(sbn.getKey()));
        boolean useIncreasedHeadsUp = useIncreasedCollapsedHeight
                && !mPresenter.isPresenterFullyCollapsed();
        row.setUseIncreasedCollapsedHeight(useIncreasedCollapsedHeight);
        row.setUseIncreasedHeadsUpHeight(useIncreasedHeadsUp);
        row.setSmartActions(entry.smartActions);
        row.setEntry(entry);

        row.updateInflationFlag(FLAG_CONTENT_VIEW_HEADS_UP, shouldHeadsUp(entry));
        row.updateInflationFlag(FLAG_CONTENT_VIEW_AMBIENT, shouldPulse(entry));
        row.inflateViews();
    }

    protected void addNotificationViews(NotificationData.Entry entry) {
        if (entry == null) {
            return;
        }
        // Add the expanded view and icon.
        mNotificationData.add(entry);
        tagForeground(entry.notification);
        updateNotifications();
    }

    protected NotificationData.Entry createNotificationViews(
            StatusBarNotification sbn, NotificationListenerService.Ranking ranking)
            throws InflationException {
        if (DEBUG) {
            Log.d(TAG, "createNotificationViews(notification=" + sbn + " " + ranking);
        }
        NotificationData.Entry entry = new NotificationData.Entry(sbn, ranking);
        Dependency.get(LeakDetector.class).trackInstance(entry);
        entry.createIcons(mContext, sbn);
        // Construct the expanded view.
        inflateViews(entry, mListContainer.getViewParentForNotification(entry));
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
        NotificationData.Entry shadeEntry = createNotificationViews(notification, ranking);
        boolean isHeadsUped = shouldHeadsUp(shadeEntry);
        if (!isHeadsUped && notification.getNotification().fullScreenIntent != null) {
            if (shouldSuppressFullScreenIntent(shadeEntry)) {
                if (DEBUG) {
                    Log.d(TAG, "No Fullscreen intent: suppressed by DND: " + key);
                }
            } else if (mNotificationData.getImportance(key)
                    < NotificationManager.IMPORTANCE_HIGH) {
                if (DEBUG) {
                    Log.d(TAG, "No Fullscreen intent: not important enough: "
                            + key);
                }
            } else {
                // Stop screensaver if the notification has a fullscreen intent.
                // (like an incoming phone call)
                Dependency.get(UiOffloadThread.class).submit(() -> {
                    try {
                        mDreamManager.awaken();
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                });

                // not immersive & a fullscreen alert should be shown
                if (DEBUG)
                    Log.d(TAG, "Notification has fullScreenIntent; sending fullScreenIntent");
                try {
                    EventLog.writeEvent(EventLogTags.SYSUI_FULLSCREEN_NOTIFICATION,
                            key);
                    notification.getNotification().fullScreenIntent.send();
                    shadeEntry.notifyFullScreenIntentLaunched();
                    mMetricsLogger.count("note_fullscreen", 1);
                } catch (PendingIntent.CanceledException e) {
                }
            }
        }
        abortExistingInflation(key);

        mForegroundServiceController.addNotification(notification,
                mNotificationData.getImportance(key));

        mPendingNotifications.put(key, shadeEntry);
        mGroupManager.onPendingEntryAdded(shadeEntry);
    }

    @VisibleForTesting
    protected void tagForeground(StatusBarNotification notification) {
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
        inflateViews(entry, mListContainer.getViewParentForNotification(entry));

        mForegroundServiceController.updateNotification(notification,
                mNotificationData.getImportance(key));

        boolean alertAgain = alertAgain(entry, entry.notification.getNotification());
        if (mPresenter.isDozing()) {
            updateAlertState(entry, shouldPulse(entry), alertAgain, mAmbientPulseManager);
        } else {
            updateAlertState(entry, shouldHeadsUp(entry), alertAgain, mHeadsUpManager);
        }
        updateNotifications();

        if (!notification.isClearable()) {
            // The user may have performed a dismiss action on the notification, since it's
            // not clearable we should snap it back.
            mListContainer.snapViewIfNeeded(entry.row);
        }

        if (DEBUG) {
            // Is this for you?
            boolean isForCurrentUser = mPresenter.isNotificationForCurrentProfiles(notification);
            Log.d(TAG, "notification is " + (isForCurrentUser ? "" : "not ") + "for you");
        }

        mCallback.onNotificationUpdated(notification);
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
        for (NotificationData.Entry entry : entries) {
            NotificationUiAdjustment adjustment =
                    NotificationUiAdjustment.extractFromNotificationEntry(entry);
            oldAdjustments.put(entry.key, adjustment);
        }

        // Populate notification entries from the new rankings.
        mNotificationData.updateRanking(rankingMap);
        updateRankingOfPendingNotifications(rankingMap);

        // By comparing the old and new UI adjustments, reinflate the view accordingly.
        for (NotificationData.Entry entry : entries) {
            NotificationUiAdjustment newAdjustment =
                    NotificationUiAdjustment.extractFromNotificationEntry(entry);

            if (NotificationUiAdjustment.needReinflate(
                    oldAdjustments.get(entry.key), newAdjustment)) {
                if (entry.row != null) {
                    entry.reset();
                    PackageManager pmUser = StatusBar.getPackageManagerForUser(mContext,
                            entry.notification.getUser().getIdentifier());
                    updateNotification(entry, pmUser, entry.notification, entry.row);
                } else {
                    // Once the RowInflaterTask is done, it will pick up the updated entry, so
                    // no-op here.
                }
            }
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

    /**
     * Whether the notification should peek in from the top and alert the user.
     *
     * @param entry the entry to check
     * @return true if the entry should heads up, false otherwise
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PROTECTED)
    public boolean shouldHeadsUp(NotificationData.Entry entry) {
        StatusBarNotification sbn = entry.notification;

        if (mPresenter.isDozing()) {
            if (DEBUG) {
                Log.d(TAG, "No heads up: device is dozing: " + sbn.getKey());
            }
            return false;
        }

        if (!canAlertCommon(entry)) {
            if (DEBUG) {
                Log.d(TAG, "No heads up: notification shouldn't alert: " + sbn.getKey());
            }
            return false;
        }

        if (!mUseHeadsUp || mPresenter.isDeviceInVrMode()) {
            if (DEBUG) {
                Log.d(TAG, "No heads up: no huns or vr mode");
            }
            return false;
        }

        boolean isDreaming = false;
        try {
            isDreaming = mDreamManager.isDreaming();
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to query dream manager.", e);
        }
        boolean inUse = mPowerManager.isScreenOn() && !isDreaming;

        if (!inUse) {
            if (DEBUG) {
                Log.d(TAG, "No heads up: not in use: " + sbn.getKey());
            }
            return false;
        }

        if (mNotificationData.shouldSuppressPeek(entry)) {
            if (DEBUG) {
                Log.d(TAG, "No heads up: suppressed by DND: " + sbn.getKey());
            }
            return false;
        }

        if (isSnoozedPackage(sbn)) {
            if (DEBUG) {
                Log.d(TAG, "No heads up: snoozed package: " + sbn.getKey());
            }
            return false;
        }

        if (entry.hasJustLaunchedFullScreenIntent()) {
            if (DEBUG) {
                Log.d(TAG, "No heads up: recent fullscreen: " + sbn.getKey());
            }
            return false;
        }

        if (mNotificationData.getImportance(sbn.getKey()) < NotificationManager.IMPORTANCE_HIGH) {
            if (DEBUG) {
                Log.d(TAG, "No heads up: unimportant notification: " + sbn.getKey());
            }
            return false;
        }

        if (!mCallback.canHeadsUp(entry, sbn)) {
            return false;
        }

        return true;
    }

    /**
     * Whether or not the notification should "pulse" on the user's display when the phone is
     * dozing.  This displays the ambient view of the notification.
     *
     * @param entry the entry to check
     * @return true if the entry should ambient pulse, false otherwise
     */
    protected boolean shouldPulse(NotificationData.Entry entry) {
        StatusBarNotification sbn = entry.notification;

        if (!mPresenter.isDozing()) {
            if (DEBUG) {
                Log.d(TAG, "No pulsing: not dozing: " + sbn.getKey());
            }
            return false;
        }

        if (!canAlertCommon(entry)) {
            if (DEBUG) {
                Log.d(TAG, "No pulsing: notification shouldn't alert: " + sbn.getKey());
            }
            return false;
        }

        if (mNotificationData.shouldSuppressAmbient(entry)) {
            if (DEBUG) {
                Log.d(TAG, "No pulsing: ambient effect suppressed: " + sbn.getKey());
            }
            return false;
        }

        if (mNotificationData.getImportance(sbn.getKey())
                < NotificationManager.IMPORTANCE_DEFAULT) {
            if (DEBUG) {
                Log.d(TAG, "No pulsing: not important enough: " + sbn.getKey());
            }
            return false;
        }

        Bundle extras = sbn.getNotification().extras;
        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
        if (TextUtils.isEmpty(title) && TextUtils.isEmpty(text)) {
            if (DEBUG) {
                Log.d(TAG, "No pulsing: title and text are empty: " + sbn.getKey());
            }
            return false;
        }

        return true;
    }

    /**
     * Common checks between heads up alerting and ambient pulse alerting.  See
     * {@link NotificationEntryManager#shouldHeadsUp(NotificationData.Entry)} and
     * {@link NotificationEntryManager#shouldPulse(NotificationData.Entry)}.  Notifications that
     * fail any of these checks should not alert at all.
     *
     * @param entry the entry to check
     * @return true if these checks pass, false if the notification should not alert
     */
    protected boolean canAlertCommon(NotificationData.Entry entry) {
        StatusBarNotification sbn = entry.notification;

        if (mNotificationData.shouldFilterOut(entry)) {
            if (DEBUG) {
                Log.d(TAG, "No alerting: filtered notification: " + sbn.getKey());
            }
            return false;
        }

        // Don't alert notifications that are suppressed due to group alert behavior
        if (sbn.isGroup() && sbn.getNotification().suppressAlertingDueToGrouping()) {
            if (DEBUG) {
                Log.d(TAG, "No alerting: suppressed due to group alert behavior");
            }
            return false;
        }

        return true;
    }

    protected void setNotificationShown(StatusBarNotification n) {
        setNotificationsShown(new String[]{n.getKey()});
    }

    protected void setNotificationsShown(String[] keys) {
        try {
            mNotificationListener.setNotificationsShown(keys);
        } catch (RuntimeException e) {
            Log.d(TAG, "failed setNotificationsShown: ", e);
        }
    }

    protected boolean isSnoozedPackage(StatusBarNotification sbn) {
        return mHeadsUpManager.isSnoozed(sbn.getPackageName());
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
         * Called when a notification is clicked.
         *
         * @param sbn notification that was clicked
         * @param row row for that notification
         */
        void onNotificationClicked(StatusBarNotification sbn, ExpandableNotificationRow row);

        /**
         * Called when a new notification and row is created.
         *
         * @param entry entry for the notification
         * @param pmUser package manager for user
         * @param sbn notification
         * @param row row for the notification
         */
        void onBindRow(NotificationData.Entry entry, PackageManager pmUser,
                StatusBarNotification sbn, ExpandableNotificationRow row);

        /**
         * Removes a notification immediately.
         *
         * @param statusBarNotification notification that is being removed
         */
        void onPerformRemoveNotification(StatusBarNotification statusBarNotification);

        /**
         * Returns true if NotificationEntryManager can heads up this notification.
         *
         * @param entry entry of the notification that might be heads upped
         * @param sbn notification that might be heads upped
         * @return true if the notification can be heads upped
         */
        boolean canHeadsUp(NotificationData.Entry entry, StatusBarNotification sbn);
    }
}
