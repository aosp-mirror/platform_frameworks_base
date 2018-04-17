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
package com.android.systemui.statusbar;

import static com.android.systemui.statusbar.NotificationRemoteInputManager.ENABLE_REMOTE_INPUT;
import static com.android.systemui.statusbar.NotificationRemoteInputManager
        .FORCE_REMOTE_INPUT_HISTORY;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Build;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationStats;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.util.NotificationMessagingUtil;
import com.android.systemui.DejankUtils;
import com.android.systemui.Dependency;
import com.android.systemui.Dumpable;
import com.android.systemui.EventLogTags;
import com.android.systemui.ForegroundServiceController;
import com.android.systemui.R;
import com.android.systemui.UiOffloadThread;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.statusbar.notification.InflationException;
import com.android.systemui.statusbar.notification.NotificationInflater;
import com.android.systemui.statusbar.notification.RowInflaterTask;
import com.android.systemui.statusbar.notification.VisualStabilityManager;
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
    protected static final boolean DEBUG = false;
    protected static final boolean ENABLE_HEADS_UP = true;
    protected static final String SETTING_HEADS_UP_TICKER = "ticker_gets_heads_up";

    protected final NotificationMessagingUtil mMessagingUtil;
    protected final Context mContext;
    protected final HashMap<String, NotificationData.Entry> mPendingNotifications = new HashMap<>();
    protected final NotificationClicker mNotificationClicker = new NotificationClicker();
    protected final ArraySet<NotificationData.Entry> mHeadsUpEntriesToRemoveOnSwitch =
            new ArraySet<>();

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

    protected IStatusBarService mBarService;
    protected NotificationPresenter mPresenter;
    protected Callback mCallback;
    protected PowerManager mPowerManager;
    protected SystemServicesProxy mSystemServicesProxy;
    protected NotificationListenerService.RankingMap mLatestRankingMap;
    protected HeadsUpManager mHeadsUpManager;
    protected NotificationData mNotificationData;
    protected ContentObserver mHeadsUpObserver;
    protected boolean mUseHeadsUp = false;
    protected boolean mDisableNotificationAlerts;
    protected NotificationListContainer mListContainer;
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

    public NotificationListenerService.RankingMap getLatestRankingMap() {
        return mLatestRankingMap;
    }

    public void setLatestRankingMap(NotificationListenerService.RankingMap latestRankingMap) {
        mLatestRankingMap = latestRankingMap;
    }

    public void setDisableNotificationAlerts(boolean disableNotificationAlerts) {
        mDisableNotificationAlerts = disableNotificationAlerts;
        mHeadsUpObserver.onChange(true);
    }

    public void destroy() {
        mDeviceProvisionedController.removeCallback(mDeviceProvisionedListener);
    }

    public void onHeadsUpStateChanged(NotificationData.Entry entry, boolean isHeadsUp) {
        if (!isHeadsUp && mHeadsUpEntriesToRemoveOnSwitch.contains(entry)) {
            removeNotification(entry.key, getLatestRankingMap());
            mHeadsUpEntriesToRemoveOnSwitch.remove(entry);
            if (mHeadsUpEntriesToRemoveOnSwitch.isEmpty()) {
                setLatestRankingMap(null);
            }
        } else {
            updateNotificationRanking(null);
        }
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
        mMessagingUtil = new NotificationMessagingUtil(context);
        mSystemServicesProxy = SystemServicesProxy.getInstance(mContext);
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
        NotificationData.Entry entry = mNotificationData.get(n.getKey());
        mRemoteInputManager.onPerformRemoveNotification(n, entry);
        final String pkg = n.getPackageName();
        final String tag = n.getTag();
        final int id = n.getId();
        final int userId = n.getUserId();
        try {
            int dismissalSurface = NotificationStats.DISMISSAL_SHADE;
            if (isHeadsUp(n.getKey())) {
                dismissalSurface = NotificationStats.DISMISSAL_PEEK;
            } else if (mListContainer.hasPulsingNotifications()) {
                dismissalSurface = NotificationStats.DISMISSAL_AOD;
            }
            mBarService.onNotificationClear(pkg, tag, id, userId, n.getKey(), dismissalSurface);
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
        removeNotification(n.getKey(), null);
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
        boolean isHeadsUped = shouldPeek(shadeEntry);
        if (isHeadsUped) {
            mHeadsUpManager.showNotification(shadeEntry);
            // Mark as seen immediately
            setNotificationShown(shadeEntry.notification);
        }
        addNotificationViews(shadeEntry);
        mCallback.onNotificationAdded(shadeEntry);
    }

    @Override
    public void onAsyncInflationFinished(NotificationData.Entry entry) {
        mPendingNotifications.remove(entry.key);
        // If there was an async task started after the removal, we don't want to add it back to
        // the list, otherwise we might get leaks.
        boolean isNew = mNotificationData.get(entry.key) == null;
        if (isNew && !entry.row.isRemoved()) {
            addEntry(entry);
        } else if (!isNew && entry.row.hasLowPriorityStateUpdated()) {
            mVisualStabilityManager.onLowPriorityUpdated(entry);
            mPresenter.updateNotificationViews();
        }
        entry.row.setLowPriorityStateUpdated(false);
    }

    @Override
    public void removeNotification(String key, NotificationListenerService.RankingMap ranking) {
        boolean deferRemoval = false;
        abortExistingInflation(key);
        if (mHeadsUpManager.isHeadsUp(key)) {
            // A cancel() in response to a remote input shouldn't be delayed, as it makes the
            // sending look longer than it takes.
            // Also we should not defer the removal if reordering isn't allowed since otherwise
            // some notifications can't disappear before the panel is closed.
            boolean ignoreEarliestRemovalTime = mRemoteInputManager.getController().isSpinning(key)
                    && !FORCE_REMOTE_INPUT_HISTORY
                    || !mVisualStabilityManager.isReorderingAllowed();
            deferRemoval = !mHeadsUpManager.removeNotification(key,  ignoreEarliestRemovalTime);
        }
        mMediaManager.onNotificationRemoved(key);

        NotificationData.Entry entry = mNotificationData.get(key);
        if (FORCE_REMOTE_INPUT_HISTORY
                && shouldKeepForRemoteInput(entry)
                && entry.row != null && !entry.row.isDismissed()) {
            StatusBarNotification sbn = entry.notification;

            Notification.Builder b = Notification.Builder
                    .recoverBuilder(mContext, sbn.getNotification().clone());
            CharSequence[] oldHistory = sbn.getNotification().extras
                    .getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY);
            CharSequence[] newHistory;
            if (oldHistory == null) {
                newHistory = new CharSequence[1];
            } else {
                newHistory = new CharSequence[oldHistory.length + 1];
                System.arraycopy(oldHistory, 0, newHistory, 1, oldHistory.length);
            }
            CharSequence remoteInputText = entry.remoteInputText;
            if (TextUtils.isEmpty(remoteInputText)) {
                remoteInputText = entry.remoteInputTextWhenReset;
            }
            newHistory[0] = String.valueOf(remoteInputText);
            b.setRemoteInputHistory(newHistory);

            Notification newNotification = b.build();

            // Undo any compatibility view inflation
            newNotification.contentView = sbn.getNotification().contentView;
            newNotification.bigContentView = sbn.getNotification().bigContentView;
            newNotification.headsUpContentView = sbn.getNotification().headsUpContentView;

            StatusBarNotification newSbn = new StatusBarNotification(sbn.getPackageName(),
                    sbn.getOpPkg(),
                    sbn.getId(), sbn.getTag(), sbn.getUid(), sbn.getInitialPid(),
                    newNotification, sbn.getUser(), sbn.getOverrideGroupKey(), sbn.getPostTime());
            boolean updated = false;
            entry.onRemoteInputInserted();
            try {
                updateNotificationInternal(newSbn, null);
                updated = true;
            } catch (InflationException e) {
                deferRemoval = false;
            }
            if (updated) {
                Log.w(TAG, "Keeping notification around after sending remote input "+ entry.key);
                mRemoteInputManager.getKeysKeptForRemoteInput().add(entry.key);
                return;
            }
        }
        if (deferRemoval) {
            mLatestRankingMap = ranking;
            mHeadsUpEntriesToRemoveOnSwitch.add(mHeadsUpManager.getEntry(key));
            return;
        }

        if (mRemoteInputManager.onRemoveNotification(entry)) {
            mLatestRankingMap = ranking;
            return;
        }

        if (entry != null && mGutsManager.getExposedGuts() != null
                && mGutsManager.getExposedGuts() == entry.row.getGuts()
                && entry.row.getGuts() != null && !entry.row.getGuts().isLeavebehind()) {
            Log.w(TAG, "Keeping notification because it's showing guts. " + key);
            mLatestRankingMap = ranking;
            mGutsManager.setKeyToRemoveOnGutsClosed(key);
            return;
        }

        if (entry != null) {
            mForegroundServiceController.removeNotification(entry.notification);
        }

        if (entry != null && entry.row != null) {
            entry.row.setRemoved();
            mListContainer.cleanUpViewState(entry.row);
        }
        // Let's remove the children if this was a summary
        handleGroupSummaryRemoved(key);
        StatusBarNotification old = removeNotificationViews(key, ranking);

        mCallback.onNotificationRemoved(key, old);
    }

    private boolean shouldKeepForRemoteInput(NotificationData.Entry entry) {
        if (entry == null) {
            return false;
        }
        if (mRemoteInputManager.getController().isSpinning(entry.key)) {
            return true;
        }
        if (entry.hasJustSentRemoteInput()) {
            return true;
        }
        return false;
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
                if ((row.getStatusBarNotification().getNotification().flags
                        & Notification.FLAG_FOREGROUND_SERVICE) != 0) {
                    // the child is a foreground service notification which we can't remove!
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
        ArrayList<NotificationData.Entry> activeNotifications =
                mNotificationData.getActiveNotifications();
        for (int i = 0; i < activeNotifications.size(); i++) {
            NotificationData.Entry entry = activeNotifications.get(i);
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
        row.updateNotification(entry);
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

    protected NotificationData.Entry createNotificationViews(StatusBarNotification sbn)
            throws InflationException {
        if (DEBUG) {
            Log.d(TAG, "createNotificationViews(notification=" + sbn);
        }
        NotificationData.Entry entry = new NotificationData.Entry(sbn);
        Dependency.get(LeakDetector.class).trackInstance(entry);
        entry.createIcons(mContext, sbn);
        // Construct the expanded view.
        inflateViews(entry, mListContainer.getViewParentForNotification(entry));
        return entry;
    }

    private void addNotificationInternal(StatusBarNotification notification,
            NotificationListenerService.RankingMap ranking) throws InflationException {
        String key = notification.getKey();
        if (DEBUG) Log.d(TAG, "addNotification key=" + key);

        mNotificationData.updateRanking(ranking);
        NotificationData.Entry shadeEntry = createNotificationViews(notification);
        boolean isHeadsUped = shouldPeek(shadeEntry);
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
                SystemServicesProxy.getInstance(mContext).awakenDreamsAsync();

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
        mHeadsUpEntriesToRemoveOnSwitch.remove(entry);
        mRemoteInputManager.onUpdateNotification(entry);

        if (key.equals(mGutsManager.getKeyToRemoveOnGutsClosed())) {
            mGutsManager.setKeyToRemoveOnGutsClosed(null);
            Log.w(TAG, "Notification that was kept for guts was updated. " + key);
        }

        Notification n = notification.getNotification();
        mNotificationData.updateRanking(ranking);

        final StatusBarNotification oldNotification = entry.notification;
        entry.notification = notification;
        mGroupManager.onEntryUpdated(entry, oldNotification);

        entry.updateIcons(mContext, notification);
        inflateViews(entry, mListContainer.getViewParentForNotification(entry));

        mForegroundServiceController.updateNotification(notification,
                mNotificationData.getImportance(key));

        boolean shouldPeek = shouldPeek(entry, notification);
        boolean alertAgain = alertAgain(entry, n);

        updateHeadsUp(key, entry, shouldPeek, alertAgain);
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

    public void updateNotificationRanking(NotificationListenerService.RankingMap ranking) {
        mNotificationData.updateRanking(ranking);
        updateNotifications();
    }

    protected boolean shouldPeek(NotificationData.Entry entry) {
        return shouldPeek(entry, entry.notification);
    }

    public boolean shouldPeek(NotificationData.Entry entry, StatusBarNotification sbn) {
        if (!mUseHeadsUp || mPresenter.isDeviceInVrMode()) {
            if (DEBUG) Log.d(TAG, "No peeking: no huns or vr mode");
            return false;
        }

        if (mNotificationData.shouldFilterOut(entry)) {
            if (DEBUG) Log.d(TAG, "No peeking: filtered notification: " + sbn.getKey());
            return false;
        }

        boolean inUse = mPowerManager.isScreenOn() && !mSystemServicesProxy.isDreaming();

        if (!inUse && !mPresenter.isDozing()) {
            if (DEBUG) {
                Log.d(TAG, "No peeking: not in use: " + sbn.getKey());
            }
            return false;
        }

        if (!mPresenter.isDozing() && mNotificationData.shouldSuppressPeek(entry)) {
            if (DEBUG) Log.d(TAG, "No peeking: suppressed by DND: " + sbn.getKey());
            return false;
        }

        // Peeking triggers an ambient display pulse, so disable peek is ambient is active
        if (mPresenter.isDozing() && mNotificationData.shouldSuppressAmbient(entry)) {
            if (DEBUG) Log.d(TAG, "No peeking: suppressed by DND: " + sbn.getKey());
            return false;
        }

        if (entry.hasJustLaunchedFullScreenIntent()) {
            if (DEBUG) Log.d(TAG, "No peeking: recent fullscreen: " + sbn.getKey());
            return false;
        }

        if (isSnoozedPackage(sbn)) {
            if (DEBUG) Log.d(TAG, "No peeking: snoozed package: " + sbn.getKey());
            return false;
        }

        // Allow peeking for DEFAULT notifications only if we're on Ambient Display.
        int importanceLevel = mPresenter.isDozing() ? NotificationManager.IMPORTANCE_DEFAULT
                : NotificationManager.IMPORTANCE_HIGH;
        if (mNotificationData.getImportance(sbn.getKey()) < importanceLevel) {
            if (DEBUG) Log.d(TAG, "No peeking: unimportant notification: " + sbn.getKey());
            return false;
        }

        // Don't peek notifications that are suppressed due to group alert behavior
        if (sbn.isGroup() && sbn.getNotification().suppressAlertingDueToGrouping()) {
            if (DEBUG) Log.d(TAG, "No peeking: suppressed due to group alert behavior");
            return false;
        }

        if (!mCallback.shouldPeek(entry, sbn)) {
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

    protected void updateHeadsUp(String key, NotificationData.Entry entry, boolean shouldPeek,
            boolean alertAgain) {
        final boolean wasHeadsUp = isHeadsUp(key);
        if (wasHeadsUp) {
            if (!shouldPeek) {
                // We don't want this to be interrupting anymore, lets remove it
                mHeadsUpManager.removeNotification(key, false /* ignoreEarliestRemovalTime */);
            } else {
                mHeadsUpManager.updateNotification(entry, alertAgain);
            }
        } else if (shouldPeek && alertAgain) {
            // This notification was updated to be a heads-up, show it!
            mHeadsUpManager.showNotification(entry);
        }
    }

    protected boolean isHeadsUp(String key) {
        return mHeadsUpManager.isHeadsUp(key);
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
         * Returns true if NotificationEntryManager should peek this notification.
         *
         * @param entry entry of the notification that might be peeked
         * @param sbn notification that might be peeked
         * @return true if the notification should be peeked
         */
        boolean shouldPeek(NotificationData.Entry entry, StatusBarNotification sbn);
    }
}
