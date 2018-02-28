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

import static android.app.AppOpsManager.OP_CAMERA;
import static android.app.AppOpsManager.OP_RECORD_AUDIO;
import static android.app.AppOpsManager.OP_SYSTEM_ALERT_WINDOW;
import static android.service.notification.NotificationListenerService.Ranking
        .USER_SENTIMENT_NEGATIVE;

import android.app.AppOpsManager;
import android.app.INotificationManager;
import android.app.NotificationChannel;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.Uri;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.util.ArraySet;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.systemui.Dependency;
import com.android.systemui.Dumpable;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;
import com.android.systemui.statusbar.phone.StatusBar;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Handles various NotificationGuts related tasks, such as binding guts to a row, opening and
 * closing guts, and keeping track of the currently exposed notification guts.
 */
public class NotificationGutsManager implements Dumpable {
    private static final String TAG = "NotificationGutsManager";

    // Must match constant in Settings. Used to highlight preferences when linking to Settings.
    private static final String EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key";

    private final MetricsLogger mMetricsLogger = Dependency.get(MetricsLogger.class);
    private final Set<String> mNonBlockablePkgs;
    private final Context mContext;
    private final AccessibilityManager mAccessibilityManager;

    // Dependencies:
    private final NotificationLockscreenUserManager mLockscreenUserManager =
            Dependency.get(NotificationLockscreenUserManager.class);

    // which notification is currently being longpress-examined by the user
    private NotificationGuts mNotificationGutsExposed;
    private NotificationMenuRowPlugin.MenuItem mGutsMenuItem;
    protected NotificationPresenter mPresenter;
    protected NotificationEntryManager mEntryManager;
    private NotificationListContainer mListContainer;
    private NotificationInfo.CheckSaveListener mCheckSaveListener;
    private OnSettingsClickListener mOnSettingsClickListener;
    private String mKeyToRemoveOnGutsClosed;

    public NotificationGutsManager(Context context) {
        mContext = context;
        Resources res = context.getResources();

        mNonBlockablePkgs = new HashSet<>();
        Collections.addAll(mNonBlockablePkgs, res.getStringArray(
                com.android.internal.R.array.config_nonBlockableNotificationPackages));

        mAccessibilityManager = (AccessibilityManager)
                mContext.getSystemService(Context.ACCESSIBILITY_SERVICE);
    }

    public void setUpWithPresenter(NotificationPresenter presenter,
            NotificationEntryManager entryManager, NotificationListContainer listContainer,
            NotificationInfo.CheckSaveListener checkSaveListener,
            OnSettingsClickListener onSettingsClickListener) {
        mPresenter = presenter;
        mEntryManager = entryManager;
        mListContainer = listContainer;
        mCheckSaveListener = checkSaveListener;
        mOnSettingsClickListener = onSettingsClickListener;
    }

    public String getKeyToRemoveOnGutsClosed() {
        return mKeyToRemoveOnGutsClosed;
    }

    public void setKeyToRemoveOnGutsClosed(String keyToRemoveOnGutsClosed) {
        mKeyToRemoveOnGutsClosed = keyToRemoveOnGutsClosed;
    }

    public void onDensityOrFontScaleChanged(ExpandableNotificationRow row) {
        setExposedGuts(row.getGuts());
        bindGuts(row);
    }

    private void saveAndCloseNotificationMenu(
            ExpandableNotificationRow row, NotificationGuts guts, View done) {
        guts.resetFalsingCheck();
        int[] rowLocation = new int[2];
        int[] doneLocation = new int[2];
        row.getLocationOnScreen(rowLocation);
        done.getLocationOnScreen(doneLocation);

        final int centerX = done.getWidth() / 2;
        final int centerY = done.getHeight() / 2;
        final int x = doneLocation[0] - rowLocation[0] + centerX;
        final int y = doneLocation[1] - rowLocation[1] + centerY;
        closeAndSaveGuts(false /* removeLeavebehind */, false /* force */,
                true /* removeControls */, x, y, true /* resetMenu */);
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
        mPresenter.startNotificationGutsIntent(intent, appUid, row);
    }

    protected void startAppOpsSettingsActivity(String pkg, int uid, ArraySet<Integer> ops,
            ExpandableNotificationRow row) {
        if (ops.contains(OP_SYSTEM_ALERT_WINDOW)) {
            if (ops.contains(OP_CAMERA) || ops.contains(OP_RECORD_AUDIO)) {
                startAppNotificationSettingsActivity(pkg, uid, null, row);
            } else {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                intent.setData(Uri.fromParts("package", pkg, null));
                mPresenter.startNotificationGutsIntent(intent, uid, row);
            }
        } else if (ops.contains(OP_CAMERA) || ops.contains(OP_RECORD_AUDIO)) {
            Intent intent = new Intent(Intent.ACTION_MANAGE_APP_PERMISSIONS);
            intent.putExtra(Intent.EXTRA_PACKAGE_NAME, pkg);
            mPresenter.startNotificationGutsIntent(intent, uid, row);
        }
    }

    public void bindGuts(final ExpandableNotificationRow row) {
        bindGuts(row, mGutsMenuItem);
    }

    private void bindGuts(final ExpandableNotificationRow row,
            NotificationMenuRowPlugin.MenuItem item) {
        row.inflateGuts();
        row.setGutsView(item);
        final StatusBarNotification sbn = row.getStatusBarNotification();
        row.setTag(sbn.getPackageName());
        final NotificationGuts guts = row.getGuts();
        guts.setClosedListener((NotificationGuts g) -> {
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
                mEntryManager.removeNotification(key, mEntryManager.getLatestRankingMap());
            }
        });

        View gutsView = item.getGutsView();
        if (gutsView instanceof NotificationSnooze) {
            NotificationSnooze snoozeGuts = (NotificationSnooze) gutsView;
            snoozeGuts.setSnoozeListener(mListContainer.getSwipeActionHelper());
            snoozeGuts.setStatusBarNotification(sbn);
            snoozeGuts.setSnoozeOptions(row.getEntry().snoozeCriteria);
            guts.setHeightChangedListener((NotificationGuts g) -> {
                mListContainer.onHeightChanged(row, row.isShown() /* needsAnimation */);
            });
        }

        if (gutsView instanceof AppOpsInfo) {
            AppOpsInfo info = (AppOpsInfo) gutsView;
            final UserHandle userHandle = sbn.getUser();
            PackageManager pmUser = StatusBar.getPackageManagerForUser(mContext,
                    userHandle.getIdentifier());
            final AppOpsInfo.OnSettingsClickListener onSettingsClick = (View v,
                    String pkg, int uid, ArraySet<Integer> ops) -> {
                mMetricsLogger.action(MetricsProto.MetricsEvent.ACTION_OPS_GUTS_SETTINGS);
                guts.resetFalsingCheck();
                startAppOpsSettingsActivity(pkg, uid, ops, row);
            };
            if (!row.getEntry().mActiveAppOps.isEmpty()) {
                info.bindGuts(pmUser, onSettingsClick, sbn, row.getEntry().mActiveAppOps);
            }
        }

        if (gutsView instanceof NotificationInfo) {
            final UserHandle userHandle = sbn.getUser();
            PackageManager pmUser = StatusBar.getPackageManagerForUser(mContext,
                    userHandle.getIdentifier());
            final INotificationManager iNotificationManager = INotificationManager.Stub.asInterface(
                    ServiceManager.getService(Context.NOTIFICATION_SERVICE));
            final String pkg = sbn.getPackageName();
            NotificationInfo info = (NotificationInfo) gutsView;
            // Settings link is only valid for notifications that specify a user, unless this is the
            // system user.
            NotificationInfo.OnSettingsClickListener onSettingsClick = null;
            if (!userHandle.equals(UserHandle.ALL)
                    || mLockscreenUserManager.getCurrentUserId() == UserHandle.USER_SYSTEM) {
                onSettingsClick = (View v, NotificationChannel channel, int appUid) -> {
                    mMetricsLogger.action(MetricsProto.MetricsEvent.ACTION_NOTE_INFO);
                    guts.resetFalsingCheck();
                    mOnSettingsClickListener.onClick(sbn.getKey());
                    startAppNotificationSettingsActivity(pkg, appUid, channel, row);
                };
            }
            final NotificationInfo.OnAppSettingsClickListener onAppSettingsClick = (View v,
                    Intent intent) -> {
                mMetricsLogger.action(MetricsProto.MetricsEvent.ACTION_APP_NOTE_SETTINGS);
                guts.resetFalsingCheck();
                mPresenter.startNotificationGutsIntent(intent, sbn.getUid(), row);
            };
            final View.OnClickListener onDoneClick = (View v) -> {
                saveAndCloseNotificationMenu(row, guts, v);
            };

            ArraySet<NotificationChannel> channels = new ArraySet<>();
            channels.add(row.getEntry().channel);
            if (row.isSummaryWithChildren()) {
                // If this is a summary, then add in the children notification channels for the
                // same user and pkg.
                final List<ExpandableNotificationRow> childrenRows = row.getNotificationChildren();
                final int numChildren = childrenRows.size();
                for (int i = 0; i < numChildren; i++) {
                    final ExpandableNotificationRow childRow = childrenRows.get(i);
                    final NotificationChannel childChannel = childRow.getEntry().channel;
                    final StatusBarNotification childSbn = childRow.getStatusBarNotification();
                    if (childSbn.getUser().equals(userHandle) &&
                            childSbn.getPackageName().equals(pkg)) {
                        channels.add(childChannel);
                    }
                }
            }
            try {
                info.bindNotification(pmUser, iNotificationManager, pkg, row.getEntry().channel,
                        channels.size(), sbn, mCheckSaveListener, onSettingsClick,
                        onAppSettingsClick, mNonBlockablePkgs,
                        row.getEntry().userSentiment == USER_SENTIMENT_NEGATIVE);
            } catch (RemoteException e) {
                Log.e(TAG, e.toString());
            }
        }
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

    /**
     * Opens guts on the given ExpandableNotificationRow |v|.
     *
     * @param v ExpandableNotificationRow to open guts on
     * @param x x coordinate of origin of circular reveal
     * @param y y coordinate of origin of circular reveal
     * @param item MenuItem the guts should display
     * @return true if guts was opened
     */
    public boolean openGuts(View v, int x, int y,
            NotificationMenuRowPlugin.MenuItem item) {
        if (!(v instanceof ExpandableNotificationRow)) {
            return false;
        }

        if (v.getWindowToken() == null) {
            Log.e(TAG, "Trying to show notification guts, but not attached to window");
            return false;
        }

        final ExpandableNotificationRow row = (ExpandableNotificationRow) v;
        if (row.isDark()) {
            return false;
        }
        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (row.areGutsExposed()) {
            closeAndSaveGuts(false /* removeLeavebehind */, false /* force */,
                    true /* removeControls */, -1 /* x */, -1 /* y */,
                    true /* resetMenu */);
            return false;
        }
        bindGuts(row, item);
        NotificationGuts guts = row.getGuts();

        // Assume we are a status_bar_notification_row
        if (guts == null) {
            // This view has no guts. Examples are the more card or the dismiss all view
            return false;
        }

        mMetricsLogger.action(MetricsProto.MetricsEvent.ACTION_NOTE_CONTROLS);

        // ensure that it's laid but not visible until actually laid out
        guts.setVisibility(View.INVISIBLE);
        // Post to ensure the the guts are properly laid out.
        guts.post(new Runnable() {
            @Override
            public void run() {
                if (row.getWindowToken() == null) {
                    Log.e(TAG, "Trying to show notification guts, but not attached to "
                            + "window");
                    return;
                }
                closeAndSaveGuts(true /* removeLeavebehind */, true /* force */,
                        true /* removeControls */, -1 /* x */, -1 /* y */,
                        false /* resetMenu */);
                guts.setVisibility(View.VISIBLE);

                final boolean needsFalsingProtection =
                        (mPresenter.isPresenterLocked() &&
                                !mAccessibilityManager.isTouchExplorationEnabled());
                guts.openControls(x, y, needsFalsingProtection, () -> {
                    // Move the notification view back over the menu
                    row.resetTranslation();
                });

                row.closeRemoteInput();
                mListContainer.onHeightChanged(row, true /* needsAnimation */);
                mNotificationGutsExposed = guts;
                mGutsMenuItem = item;
            }
        });
        return true;
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("NotificationGutsManager state:");
        pw.print("  mKeyToRemoveOnGutsClosed: ");
        pw.println(mKeyToRemoveOnGutsClosed);
    }

    public interface OnSettingsClickListener {
        void onClick(String key);
    }
}
