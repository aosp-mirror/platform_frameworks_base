/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static com.android.internal.util.Preconditions.checkNotNull;
import static com.android.systemui.statusbar.NotificationRemoteInputManager.ENABLE_REMOTE_INPUT;
import static com.android.systemui.statusbar.notification.row.NotificationContentInflater.FLAG_CONTENT_VIEW_HEADS_UP;

import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.ViewGroup;

import com.android.internal.util.NotificationMessagingUtil;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.UiOffloadThread;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationUiAdjustment;
import com.android.systemui.statusbar.notification.InflationException;
import com.android.systemui.statusbar.notification.NotificationClicker;
import com.android.systemui.statusbar.notification.NotificationInterruptionStateProvider;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationContentInflater;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.notification.row.RowInflaterTask;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.policy.HeadsUpManager;

/** Handles inflating and updating views for notifications. */
public class NotificationRowBinderImpl implements NotificationRowBinder {

    private static final String TAG = "NotificationViewManager";

    private final NotificationGroupManager mGroupManager =
            Dependency.get(NotificationGroupManager.class);
    private final NotificationGutsManager mGutsManager =
            Dependency.get(NotificationGutsManager.class);
    private final UiOffloadThread mUiOffloadThread = Dependency.get(UiOffloadThread.class);
    private final NotificationInterruptionStateProvider mNotificationInterruptionStateProvider =
            Dependency.get(NotificationInterruptionStateProvider.class);

    private final Context mContext;
    private final NotificationMessagingUtil mMessagingUtil;
    private final ExpandableNotificationRow.ExpansionLogger mExpansionLogger =
            this::logNotificationExpansion;
    private final boolean mAllowLongPress;
    private final KeyguardBypassController mKeyguardBypassController;
    private final StatusBarStateController mStatusBarStateController;

    private NotificationRemoteInputManager mRemoteInputManager;
    private NotificationPresenter mPresenter;
    private NotificationListContainer mListContainer;
    private HeadsUpManager mHeadsUpManager;
    private NotificationContentInflater.InflationCallback mInflationCallback;
    private ExpandableNotificationRow.OnAppOpsClickListener mOnAppOpsClickListener;
    private BindRowCallback mBindRowCallback;
    private NotificationClicker mNotificationClicker;
    private final NotificationLogger mNotificationLogger = Dependency.get(NotificationLogger.class);

    public NotificationRowBinderImpl(Context context, boolean allowLongPress,
            KeyguardBypassController keyguardBypassController,
            StatusBarStateController statusBarStateController) {
        mContext = context;
        mMessagingUtil = new NotificationMessagingUtil(context);
        mAllowLongPress = allowLongPress;
        mKeyguardBypassController = keyguardBypassController;
        mStatusBarStateController = statusBarStateController;
    }

    private NotificationRemoteInputManager getRemoteInputManager() {
        if (mRemoteInputManager == null) {
            mRemoteInputManager = Dependency.get(NotificationRemoteInputManager.class);
        }
        return mRemoteInputManager;
    }

    /**
     * Sets up late-bound dependencies for this component.
     */
    public void setUpWithPresenter(NotificationPresenter presenter,
            NotificationListContainer listContainer,
            HeadsUpManager headsUpManager,
            NotificationContentInflater.InflationCallback inflationCallback,
            BindRowCallback bindRowCallback) {
        mPresenter = presenter;
        mListContainer = listContainer;
        mHeadsUpManager = headsUpManager;
        mInflationCallback = inflationCallback;
        mBindRowCallback = bindRowCallback;
        mOnAppOpsClickListener = mGutsManager::openGuts;
    }

    public void setNotificationClicker(NotificationClicker clicker) {
        mNotificationClicker = clicker;
    }

    /**
     * Inflates the views for the given entry (possibly asynchronously).
     */
    @Override
    public void inflateViews(
            NotificationEntry entry,
            Runnable onDismissRunnable)
            throws InflationException {
        ViewGroup parent = mListContainer.getViewParentForNotification(entry);
        PackageManager pmUser = StatusBar.getPackageManagerForUser(mContext,
                entry.notification.getUser().getIdentifier());

        final StatusBarNotification sbn = entry.notification;
        if (entry.rowExists()) {
            entry.updateIcons(mContext, sbn);
            entry.reset();
            updateNotification(entry, pmUser, sbn, entry.getRow());
            entry.getRow().setOnDismissRunnable(onDismissRunnable);
        } else {
            entry.createIcons(mContext, sbn);
            new RowInflaterTask().inflate(mContext, parent, entry,
                    row -> {
                        bindRow(entry, pmUser, sbn, row, onDismissRunnable);
                        updateNotification(entry, pmUser, sbn, row);
                    });
        }
    }

    private void bindRow(NotificationEntry entry, PackageManager pmUser,
            StatusBarNotification sbn, ExpandableNotificationRow row,
            Runnable onDismissRunnable) {
        row.setExpansionLogger(mExpansionLogger, entry.notification.getKey());
        row.setBypassController(mKeyguardBypassController);
        row.setStatusBarStateController(mStatusBarStateController);
        row.setGroupManager(mGroupManager);
        row.setHeadsUpManager(mHeadsUpManager);
        row.setOnExpandClickListener(mPresenter);
        row.setInflationCallback(mInflationCallback);
        if (mAllowLongPress) {
            row.setLongPressListener(mGutsManager::openGuts);
        }
        mListContainer.bindRow(row);
        getRemoteInputManager().bindRow(row);

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
        row.setOnDismissRunnable(onDismissRunnable);
        row.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        if (ENABLE_REMOTE_INPUT) {
            row.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        }

        row.setAppOpsOnClickListener(mOnAppOpsClickListener);

        mBindRowCallback.onBindRow(entry, pmUser, sbn, row);
    }

    /**
     * Updates the views bound to an entry when the entry's ranking changes, either in-place or by
     * reinflating them.
     */
    @Override
    public void onNotificationRankingUpdated(
            NotificationEntry entry,
            @Nullable Integer oldImportance,
            NotificationUiAdjustment oldAdjustment,
            NotificationUiAdjustment newAdjustment) {
        if (NotificationUiAdjustment.needReinflate(oldAdjustment, newAdjustment)) {
            if (entry.rowExists()) {
                entry.reset();
                PackageManager pmUser = StatusBar.getPackageManagerForUser(
                        mContext,
                        entry.notification.getUser().getIdentifier());
                updateNotification(entry, pmUser, entry.notification, entry.getRow());
            } else {
                // Once the RowInflaterTask is done, it will pick up the updated entry, so
                // no-op here.
            }
        } else {
            if (oldImportance != null && entry.importance != oldImportance) {
                if (entry.rowExists()) {
                    entry.getRow().onNotificationRankingUpdated();
                }
            }
        }
    }

    //TODO: This method associates a row with an entry, but eventually needs to not do that
    private void updateNotification(
            NotificationEntry entry,
            PackageManager pmUser,
            StatusBarNotification sbn,
            ExpandableNotificationRow row) {
        row.setIsLowPriority(entry.ambient);

        // Extract target SDK version.
        try {
            ApplicationInfo info = pmUser.getApplicationInfo(sbn.getPackageName(), 0);
            entry.targetSdk = info.targetSdkVersion;
        } catch (PackageManager.NameNotFoundException ex) {
            Log.e(TAG, "Failed looking up ApplicationInfo for " + sbn.getPackageName(), ex);
        }
        row.setLegacy(entry.targetSdk >= Build.VERSION_CODES.GINGERBREAD
                && entry.targetSdk < Build.VERSION_CODES.LOLLIPOP);

        // TODO: should updates to the entry be happening somewhere else?
        entry.setIconTag(R.id.icon_is_pre_L, entry.targetSdk < Build.VERSION_CODES.LOLLIPOP);
        entry.autoRedacted = entry.notification.getNotification().publicVersion == null;

        entry.setRow(row);
        row.setOnActivatedListener(mPresenter);

        boolean useIncreasedCollapsedHeight =
                mMessagingUtil.isImportantMessaging(sbn, entry.importance);
        boolean useIncreasedHeadsUp = useIncreasedCollapsedHeight
                && !mPresenter.isPresenterFullyCollapsed();
        row.setUseIncreasedCollapsedHeight(useIncreasedCollapsedHeight);
        row.setUseIncreasedHeadsUpHeight(useIncreasedHeadsUp);
        row.setEntry(entry);

        if (mNotificationInterruptionStateProvider.shouldHeadsUp(entry)) {
            row.updateInflationFlag(FLAG_CONTENT_VIEW_HEADS_UP, true /* shouldInflate */);
        }
        row.setNeedsRedaction(
                Dependency.get(NotificationLockscreenUserManager.class).needsRedaction(entry));
        row.inflateViews();

        // bind the click event to the content area
        checkNotNull(mNotificationClicker).register(row, sbn);
    }

    private void logNotificationExpansion(String key, boolean userAction, boolean expanded) {
        mNotificationLogger.onExpansionChanged(key, userAction, expanded);
    }

    /** Callback for when a row is bound to an entry. */
    public interface BindRowCallback {
        /**
         * Called when a new notification and row is created.
         *
         * @param entry  entry for the notification
         * @param pmUser package manager for user
         * @param sbn    notification
         * @param row    row for the notification
         */
        void onBindRow(NotificationEntry entry, PackageManager pmUser,
                StatusBarNotification sbn, ExpandableNotificationRow row);
    }
}
