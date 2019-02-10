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
package com.android.systemui.statusbar.notification.row;

import static android.app.AppOpsManager.OP_CAMERA;
import static android.app.AppOpsManager.OP_RECORD_AUDIO;
import static android.app.AppOpsManager.OP_SYSTEM_ALERT_WINDOW;
import static android.service.notification.NotificationListenerService.Ranking.USER_SENTIMENT_NEGATIVE;

import android.app.INotificationManager;
import android.app.NotificationChannel;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.util.ArraySet;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.systemui.Dependency;
import com.android.systemui.Dumpable;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.NotificationLifetimeExtender;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.NotificationActivityStarter;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.NotificationInfo.CheckSaveListener;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;

import java.io.FileDescriptor;
import java.io.PrintWriter;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Handles various NotificationGuts related tasks, such as binding guts to a row, opening and
 * closing guts, and keeping track of the currently exposed notification guts.
 */
@Singleton
public class NotificationGutsManager implements Dumpable, NotificationLifetimeExtender {
    private static final String TAG = "NotificationGutsManager";

    // Must match constant in Settings. Used to highlight preferences when linking to Settings.
    private static final String EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key";

    private final MetricsLogger mMetricsLogger = Dependency.get(MetricsLogger.class);
    private final Context mContext;
    private final AccessibilityManager mAccessibilityManager;

    // Dependencies:
    private final NotificationLockscreenUserManager mLockscreenUserManager =
            Dependency.get(NotificationLockscreenUserManager.class);
    private final StatusBarStateController mStatusBarStateController =
            Dependency.get(StatusBarStateController.class);
    private final DeviceProvisionedController mDeviceProvisionedController =
            Dependency.get(DeviceProvisionedController.class);

    // which notification is currently being longpress-examined by the user
    private NotificationGuts mNotificationGutsExposed;
    private NotificationMenuRowPlugin.MenuItem mGutsMenuItem;
    private NotificationSafeToRemoveCallback mNotificationLifetimeFinishedCallback;
    private NotificationPresenter mPresenter;
    private NotificationActivityStarter mNotificationActivityStarter;
    private NotificationListContainer mListContainer;
    private CheckSaveListener mCheckSaveListener;
    private OnSettingsClickListener mOnSettingsClickListener;
    @VisibleForTesting
    protected String mKeyToRemoveOnGutsClosed;

    @Inject
    public NotificationGutsManager(Context context) {
        mContext = context;
        mAccessibilityManager = (AccessibilityManager)
                mContext.getSystemService(Context.ACCESSIBILITY_SERVICE);
    }

    public void setUpWithPresenter(NotificationPresenter presenter,
            NotificationListContainer listContainer,
            CheckSaveListener checkSave, OnSettingsClickListener onSettingsClick) {
        mPresenter = presenter;
        mListContainer = listContainer;
        mCheckSaveListener = checkSave;
        mOnSettingsClickListener = onSettingsClick;
    }

    public void setNotificationActivityStarter(
            NotificationActivityStarter notificationActivityStarter) {
        mNotificationActivityStarter = notificationActivityStarter;
    }

    public void onDensityOrFontScaleChanged(NotificationEntry entry) {
        setExposedGuts(entry.getGuts());
        bindGuts(entry.getRow());
    }

    /**
     * Sends an intent to open the app settings for a particular package and optional
     * channel.
     */
    private void startAppNotificationSettingsActivity(String packageName, final int appUid,
            final NotificationChannel channel, ExpandableNotificationRow row) {
        final Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", packageName, null));
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName);
        intent.putExtra(Settings.EXTRA_APP_UID, appUid);
        if (channel != null) {
            intent.putExtra(EXTRA_FRAGMENT_ARG_KEY, channel.getId());
        }
        mNotificationActivityStarter.startNotificationGutsIntent(intent, appUid, row);
    }

    protected void startAppOpsSettingsActivity(String pkg, int uid, ArraySet<Integer> ops,
            ExpandableNotificationRow row) {
        if (ops.contains(OP_SYSTEM_ALERT_WINDOW)) {
            if (ops.contains(OP_CAMERA) || ops.contains(OP_RECORD_AUDIO)) {
                startAppNotificationSettingsActivity(pkg, uid, null, row);
            } else {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                intent.setData(Uri.fromParts("package", pkg, null));
                mNotificationActivityStarter.startNotificationGutsIntent(intent, uid, row);
            }
        } else if (ops.contains(OP_CAMERA) || ops.contains(OP_RECORD_AUDIO)) {
            Intent intent = new Intent(Intent.ACTION_MANAGE_APP_PERMISSIONS);
            intent.putExtra(Intent.EXTRA_PACKAGE_NAME, pkg);
            mNotificationActivityStarter.startNotificationGutsIntent(intent, uid, row);
        }
    }

    public boolean bindGuts(final ExpandableNotificationRow row) {
        row.inflateGuts();
        return bindGuts(row, mGutsMenuItem);
    }

    @VisibleForTesting
    protected boolean bindGuts(final ExpandableNotificationRow row,
            NotificationMenuRowPlugin.MenuItem item) {
        StatusBarNotification sbn = row.getStatusBarNotification();

        row.setGutsView(item);
        row.setTag(sbn.getPackageName());
        row.getGuts().setClosedListener((NotificationGuts g) -> {
            row.onGutsClosed();
            if (!g.willBeRemoved() && !row.isRemoved()) {
                mListContainer.onHeightChanged(
                        row, !mPresenter.isPresenterFullyCollapsed() /* needsAnimation */);
            }
            if (mNotificationGutsExposed == g) {
                mNotificationGutsExposed = null;
                mGutsMenuItem = null;
            }
            String key = sbn.getKey();
            if (key.equals(mKeyToRemoveOnGutsClosed)) {
                mKeyToRemoveOnGutsClosed = null;
                if (mNotificationLifetimeFinishedCallback != null) {
                    mNotificationLifetimeFinishedCallback.onSafeToRemove(key);
                }
            }
        });

        View gutsView = item.getGutsView();
        try {
            if (gutsView instanceof NotificationSnooze) {
                initializeSnoozeView(row, (NotificationSnooze) gutsView);
            } else if (gutsView instanceof AppOpsInfo) {
                initializeAppOpsInfo(row, (AppOpsInfo) gutsView);
            } else if (gutsView instanceof NotificationInfo) {
                initializeNotificationInfo(row, (NotificationInfo) gutsView);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "error binding guts", e);
            return false;
        }
    }

    /**
     * Sets up the {@link NotificationSnooze} inside the notification row's guts.
     *
     * @param row view to set up the guts for
     * @param notificationSnoozeView view to set up/bind within {@code row}
     */
    private void initializeSnoozeView(
            final ExpandableNotificationRow row,
            NotificationSnooze notificationSnoozeView) {
        NotificationGuts guts = row.getGuts();
        StatusBarNotification sbn = row.getStatusBarNotification();

        notificationSnoozeView.setSnoozeListener(mListContainer.getSwipeActionHelper());
        notificationSnoozeView.setStatusBarNotification(sbn);
        notificationSnoozeView.setSnoozeOptions(row.getEntry().snoozeCriteria);
        guts.setHeightChangedListener((NotificationGuts g) -> {
            mListContainer.onHeightChanged(row, row.isShown() /* needsAnimation */);
        });
    }

    /**
     * Sets up the {@link AppOpsInfo} inside the notification row's guts.
     *
     * @param row view to set up the guts for
     * @param appOpsInfoView view to set up/bind within {@code row}
     */
    private void initializeAppOpsInfo(
            final ExpandableNotificationRow row,
            AppOpsInfo appOpsInfoView) {
        NotificationGuts guts = row.getGuts();
        StatusBarNotification sbn = row.getStatusBarNotification();
        UserHandle userHandle = sbn.getUser();
        PackageManager pmUser = StatusBar.getPackageManagerForUser(mContext,
                userHandle.getIdentifier());

        AppOpsInfo.OnSettingsClickListener onSettingsClick =
                (View v, String pkg, int uid, ArraySet<Integer> ops) -> {
            mMetricsLogger.action(MetricsProto.MetricsEvent.ACTION_OPS_GUTS_SETTINGS);
            guts.resetFalsingCheck();
            startAppOpsSettingsActivity(pkg, uid, ops, row);
        };
        if (!row.getEntry().mActiveAppOps.isEmpty()) {
            appOpsInfoView.bindGuts(pmUser, onSettingsClick, sbn, row.getEntry().mActiveAppOps);
        }
    }

    /**
     * Sets up the {@link NotificationInfo} inside the notification row's guts.
     * @param row view to set up the guts for
     * @param notificationInfoView view to set up/bind within {@code row}
     */
    @VisibleForTesting
    void initializeNotificationInfo(
            final ExpandableNotificationRow row,
            NotificationInfo notificationInfoView) throws Exception {
        NotificationGuts guts = row.getGuts();
        StatusBarNotification sbn = row.getStatusBarNotification();
        String packageName = sbn.getPackageName();
        // Settings link is only valid for notifications that specify a non-system user
        NotificationInfo.OnSettingsClickListener onSettingsClick = null;
        UserHandle userHandle = sbn.getUser();
        PackageManager pmUser = StatusBar.getPackageManagerForUser(
                mContext, userHandle.getIdentifier());
        INotificationManager iNotificationManager = INotificationManager.Stub.asInterface(
                ServiceManager.getService(Context.NOTIFICATION_SERVICE));
        final NotificationInfo.OnAppSettingsClickListener onAppSettingsClick =
                (View v, Intent intent) -> {
                    mMetricsLogger.action(MetricsProto.MetricsEvent.ACTION_APP_NOTE_SETTINGS);
                    guts.resetFalsingCheck();
                    mNotificationActivityStarter.startNotificationGutsIntent(intent, sbn.getUid(),
                            row);
                };
        boolean isForBlockingHelper = row.isBlockingHelperShowing();

        if (!userHandle.equals(UserHandle.ALL)
                || mLockscreenUserManager.getCurrentUserId() == UserHandle.USER_SYSTEM) {
            onSettingsClick = (View v, NotificationChannel channel, int appUid) -> {
                mMetricsLogger.action(MetricsProto.MetricsEvent.ACTION_NOTE_INFO);
                guts.resetFalsingCheck();
                mOnSettingsClickListener.onSettingsClick(sbn.getKey());
                startAppNotificationSettingsActivity(packageName, appUid, channel, row);
            };
        }

        notificationInfoView.bindNotification(
                pmUser,
                iNotificationManager,
                packageName,
                row.getEntry().channel,
                row.getNumUniqueChannels(),
                sbn,
                mCheckSaveListener,
                onSettingsClick,
                onAppSettingsClick,
                mDeviceProvisionedController.isDeviceProvisioned(),
                row.getIsNonblockable(),
                isForBlockingHelper,
                row.getEntry().userSentiment == USER_SENTIMENT_NEGATIVE,
                row.getEntry().importance,
                row.getEntry().isHighPriority());

    }

    /**
     * Closes guts or notification menus that might be visible and saves any changes.
     *
     * @param removeLeavebehinds true if leavebehinds (e.g. snooze) should be closed.
     * @param force true if guts should be closed regardless of state (used for snooze only).
     * @param removeControls true if controls (e.g. info) should be closed.
     * @param x if closed based on touch location, this is the x touch location.
     * @param y if closed based on touch location, this is the y touch location.
     * @param resetMenu if any notification menus that might be revealed should be closed.
     */
    public void closeAndSaveGuts(boolean removeLeavebehinds, boolean force, boolean removeControls,
            int x, int y, boolean resetMenu) {
        if (mNotificationGutsExposed != null) {
            mNotificationGutsExposed.closeControls(removeLeavebehinds, removeControls, x, y, force);
        }
        if (resetMenu) {
            mListContainer.resetExposedMenuView(false /* animate */, true /* force */);
        }
    }

    /**
     * Returns the exposed NotificationGuts or null if none are exposed.
     */
    public NotificationGuts getExposedGuts() {
        return mNotificationGutsExposed;
    }

    public void setExposedGuts(NotificationGuts guts) {
        mNotificationGutsExposed = guts;
    }

    public ExpandableNotificationRow.LongPressListener getNotificationLongClicker() {
        return this::openGuts;
    }

    /**
     * Opens guts on the given ExpandableNotificationRow {@code view}. This handles opening guts for
     * the normal half-swipe and long-press use cases via a circular reveal. When the blocking
     * helper needs to be shown on the row, this will skip the circular reveal.
     *
     * @param view ExpandableNotificationRow to open guts on
     * @param x x coordinate of origin of circular reveal
     * @param y y coordinate of origin of circular reveal
     * @param menuItem MenuItem the guts should display
     * @return true if guts was opened
     */
    public boolean openGuts(
            View view,
            int x,
            int y,
            NotificationMenuRowPlugin.MenuItem menuItem) {
        if (!(view instanceof ExpandableNotificationRow)) {
            return false;
        }

        if (view.getWindowToken() == null) {
            Log.e(TAG, "Trying to show notification guts, but not attached to window");
            return false;
        }

        final ExpandableNotificationRow row = (ExpandableNotificationRow) view;
        if (row.isDark()) {
            return false;
        }
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (row.areGutsExposed()) {
            closeAndSaveGuts(false /* removeLeavebehind */, false /* force */,
                    true /* removeControls */, -1 /* x */, -1 /* y */,
                    true /* resetMenu */);
            return false;
        }

        row.inflateGuts();
        NotificationGuts guts = row.getGuts();
        mNotificationGutsExposed = guts;
        if (!bindGuts(row, menuItem)) {
            // exception occurred trying to fill in all the data, bail.
            return false;
        }


        // Assume we are a status_bar_notification_row
        if (guts == null) {
            // This view has no guts. Examples are the more card or the dismiss all view
            return false;
        }

        // ensure that it's laid but not visible until actually laid out
        guts.setVisibility(View.INVISIBLE);
        // Post to ensure the the guts are properly laid out.
        guts.post(new Runnable() {
            @Override
            public void run() {
                if (row.getWindowToken() == null) {
                    Log.e(TAG, "Trying to show notification guts in post(), but not attached to "
                            + "window");
                    return;
                }
                guts.setVisibility(View.VISIBLE);

                final boolean needsFalsingProtection =
                        (mStatusBarStateController.getState() == StatusBarState.KEYGUARD &&
                                !mAccessibilityManager.isTouchExplorationEnabled());

                guts.openControls(
                        !row.isBlockingHelperShowing(),
                        x,
                        y,
                        needsFalsingProtection,
                        row::onGutsOpened);

                row.closeRemoteInput();
                mListContainer.onHeightChanged(row, true /* needsAnimation */);
                mGutsMenuItem = menuItem;
            }
        });
        return true;
    }

    @Override
    public void setCallback(NotificationSafeToRemoveCallback callback) {
        mNotificationLifetimeFinishedCallback = callback;
    }

    @Override
    public boolean shouldExtendLifetime(NotificationEntry entry) {
        return entry != null
                &&(mNotificationGutsExposed != null
                    && entry.getGuts() != null
                    && mNotificationGutsExposed == entry.getGuts()
                    && !mNotificationGutsExposed.isLeavebehind());
    }

    @Override
    public void setShouldManageLifetime(NotificationEntry entry, boolean shouldExtend) {
        if (shouldExtend) {
            mKeyToRemoveOnGutsClosed = entry.key;
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Keeping notification because it's showing guts. " + entry.key);
            }
        } else {
            if (mKeyToRemoveOnGutsClosed != null && mKeyToRemoveOnGutsClosed.equals(entry.key)) {
                mKeyToRemoveOnGutsClosed = null;
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Notification that was kept for guts was updated. " + entry.key);
                }
            }
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("NotificationGutsManager state:");
        pw.print("  mKeyToRemoveOnGutsClosed: ");
        pw.println(mKeyToRemoveOnGutsClosed);
    }

    public interface OnSettingsClickListener {
        public void onSettingsClick(String key);
    }
}
