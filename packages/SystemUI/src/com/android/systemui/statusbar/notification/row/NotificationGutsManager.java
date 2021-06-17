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

import android.app.INotificationManager;
import android.app.NotificationChannel;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.util.ArraySet;
import android.util.IconDrawableFactory;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.settingslib.notification.ConversationIconFactory;
import com.android.systemui.Dependency;
import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.people.widget.PeopleSpaceWidgetManager;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.settings.UserContextProvider;
import com.android.systemui.statusbar.NotificationLifetimeExtender;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.StatusBarStateControllerImpl;
import com.android.systemui.statusbar.notification.AssistantFeedbackController;
import com.android.systemui.statusbar.notification.NotificationActivityStarter;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.provider.HighPriorityProvider;
import com.android.systemui.statusbar.notification.dagger.NotificationsModule;
import com.android.systemui.statusbar.notification.row.NotificationInfo.CheckSaveListener;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.phone.ShadeController;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.wmshell.BubblesManager;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Optional;

import dagger.Lazy;

/**
 * Handles various NotificationGuts related tasks, such as binding guts to a row, opening and
 * closing guts, and keeping track of the currently exposed notification guts.
 */
public class NotificationGutsManager implements Dumpable, NotificationLifetimeExtender {
    private static final String TAG = "NotificationGutsManager";

    // Must match constant in Settings. Used to highlight preferences when linking to Settings.
    private static final String EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key";

    private final MetricsLogger mMetricsLogger = Dependency.get(MetricsLogger.class);
    private final Context mContext;
    private final AccessibilityManager mAccessibilityManager;
    private final HighPriorityProvider mHighPriorityProvider;
    private final ChannelEditorDialogController mChannelEditorDialogController;
    private final OnUserInteractionCallback mOnUserInteractionCallback;

    // Dependencies:
    private final NotificationLockscreenUserManager mLockscreenUserManager =
            Dependency.get(NotificationLockscreenUserManager.class);
    private final StatusBarStateController mStatusBarStateController =
            Dependency.get(StatusBarStateController.class);
    private final DeviceProvisionedController mDeviceProvisionedController =
            Dependency.get(DeviceProvisionedController.class);
    private final AssistantFeedbackController mAssistantFeedbackController;

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

    private final Lazy<StatusBar> mStatusBarLazy;
    private final Handler mMainHandler;
    private final Handler mBgHandler;
    private final Optional<BubblesManager> mBubblesManagerOptional;
    private Runnable mOpenRunnable;
    private final INotificationManager mNotificationManager;
    private final NotificationEntryManager mNotificationEntryManager;
    private final PeopleSpaceWidgetManager mPeopleSpaceWidgetManager;
    private final LauncherApps mLauncherApps;
    private final ShortcutManager mShortcutManager;
    private final UserContextProvider mContextTracker;
    private final UiEventLogger mUiEventLogger;
    private final ShadeController mShadeController;
    private final AppWidgetManager mAppWidgetManager;

    /**
     * Injected constructor. See {@link NotificationsModule}.
     */
    public NotificationGutsManager(Context context,
            Lazy<StatusBar> statusBarLazy,
            @Main Handler mainHandler,
            @Background Handler bgHandler,
            AccessibilityManager accessibilityManager,
            HighPriorityProvider highPriorityProvider,
            INotificationManager notificationManager,
            NotificationEntryManager notificationEntryManager,
            PeopleSpaceWidgetManager peopleSpaceWidgetManager,
            LauncherApps launcherApps,
            ShortcutManager shortcutManager,
            ChannelEditorDialogController channelEditorDialogController,
            UserContextProvider contextTracker,
            AssistantFeedbackController assistantFeedbackController,
            Optional<BubblesManager> bubblesManagerOptional,
            UiEventLogger uiEventLogger,
            OnUserInteractionCallback onUserInteractionCallback,
            ShadeController shadeController) {
        mContext = context;
        mStatusBarLazy = statusBarLazy;
        mMainHandler = mainHandler;
        mBgHandler = bgHandler;
        mAccessibilityManager = accessibilityManager;
        mHighPriorityProvider = highPriorityProvider;
        mNotificationManager = notificationManager;
        mNotificationEntryManager = notificationEntryManager;
        mPeopleSpaceWidgetManager = peopleSpaceWidgetManager;
        mLauncherApps = launcherApps;
        mShortcutManager = shortcutManager;
        mContextTracker = contextTracker;
        mChannelEditorDialogController = channelEditorDialogController;
        mAssistantFeedbackController = assistantFeedbackController;
        mBubblesManagerOptional = bubblesManagerOptional;
        mUiEventLogger = uiEventLogger;
        mOnUserInteractionCallback = onUserInteractionCallback;
        mShadeController = shadeController;
        mAppWidgetManager = AppWidgetManager.getInstance(context);
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
     * Sends an intent to open the notification settings for a particular package and optional
     * channel.
     */
    public static final String EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args";
    private void startAppNotificationSettingsActivity(String packageName, final int appUid,
            final NotificationChannel channel, ExpandableNotificationRow row) {
        final Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName);
        intent.putExtra(Settings.EXTRA_APP_UID, appUid);

        if (channel != null) {
            final Bundle args = new Bundle();
            intent.putExtra(EXTRA_FRAGMENT_ARG_KEY, channel.getId());
            args.putString(EXTRA_FRAGMENT_ARG_KEY, channel.getId());
            intent.putExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS, args);
        }
        mNotificationActivityStarter.startNotificationGutsIntent(intent, appUid, row);
    }

    private void startAppDetailsSettingsActivity(String packageName, final int appUid,
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
                startAppDetailsSettingsActivity(pkg, uid, null, row);
            } else {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_OVERLAY_PERMISSION);
                intent.setData(Uri.fromParts("package", pkg, null));
                mNotificationActivityStarter.startNotificationGutsIntent(intent, uid, row);
            }
        } else if (ops.contains(OP_CAMERA) || ops.contains(OP_RECORD_AUDIO)) {
            Intent intent = new Intent(Intent.ACTION_MANAGE_APP_PERMISSIONS);
            intent.putExtra(Intent.EXTRA_PACKAGE_NAME, pkg);
            mNotificationActivityStarter.startNotificationGutsIntent(intent, uid, row);
        }
    }

    private void startConversationSettingsActivity(int uid, ExpandableNotificationRow row) {
        final Intent intent = new Intent(Settings.ACTION_CONVERSATION_SETTINGS);
        mNotificationActivityStarter.startNotificationGutsIntent(intent, uid, row);
    }

    private boolean bindGuts(final ExpandableNotificationRow row) {
        row.ensureGutsInflated();
        return bindGuts(row, mGutsMenuItem);
    }

    @VisibleForTesting
    protected boolean bindGuts(final ExpandableNotificationRow row,
            NotificationMenuRowPlugin.MenuItem item) {
        StatusBarNotification sbn = row.getEntry().getSbn();

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
            } else if (gutsView instanceof NotificationInfo) {
                initializeNotificationInfo(row, (NotificationInfo) gutsView);
            } else if (gutsView instanceof NotificationConversationInfo) {
                initializeConversationNotificationInfo(
                        row, (NotificationConversationInfo) gutsView);
            } else if (gutsView instanceof PartialConversationInfo) {
                initializePartialConversationNotificationInfo(row,
                        (PartialConversationInfo) gutsView);
            } else if (gutsView instanceof FeedbackInfo) {
                initializeFeedbackInfo(row, (FeedbackInfo) gutsView);
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
        StatusBarNotification sbn = row.getEntry().getSbn();

        notificationSnoozeView.setSnoozeListener(mListContainer.getSwipeActionHelper());
        notificationSnoozeView.setStatusBarNotification(sbn);
        notificationSnoozeView.setSnoozeOptions(row.getEntry().getSnoozeCriteria());
        guts.setHeightChangedListener((NotificationGuts g) -> {
            mListContainer.onHeightChanged(row, row.isShown() /* needsAnimation */);
        });
    }

    /**
     * Sets up the {@link FeedbackInfo} inside the notification row's guts.
     *
     * @param row view to set up the guts for
     * @param feedbackInfo view to set up/bind within {@code row}
     */
    private void initializeFeedbackInfo(
            final ExpandableNotificationRow row,
            FeedbackInfo feedbackInfo) {
        StatusBarNotification sbn = row.getEntry().getSbn();
        UserHandle userHandle = sbn.getUser();
        PackageManager pmUser = StatusBar.getPackageManagerForUser(mContext,
                userHandle.getIdentifier());

        if (mAssistantFeedbackController.showFeedbackIndicator(row.getEntry())) {
            feedbackInfo.bindGuts(pmUser, sbn, row.getEntry(), row, mAssistantFeedbackController);
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
        StatusBarNotification sbn = row.getEntry().getSbn();
        String packageName = sbn.getPackageName();
        // Settings link is only valid for notifications that specify a non-system user
        NotificationInfo.OnSettingsClickListener onSettingsClick = null;
        UserHandle userHandle = sbn.getUser();
        PackageManager pmUser = StatusBar.getPackageManagerForUser(
                mContext, userHandle.getIdentifier());
        final NotificationInfo.OnAppSettingsClickListener onAppSettingsClick =
                (View v, Intent intent) -> {
                    mMetricsLogger.action(MetricsProto.MetricsEvent.ACTION_APP_NOTE_SETTINGS);
                    guts.resetFalsingCheck();
                    mNotificationActivityStarter.startNotificationGutsIntent(intent, sbn.getUid(),
                            row);
                };

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
                mNotificationManager,
                mOnUserInteractionCallback,
                mChannelEditorDialogController,
                packageName,
                row.getEntry().getChannel(),
                row.getUniqueChannels(),
                row.getEntry(),
                onSettingsClick,
                onAppSettingsClick,
                mUiEventLogger,
                mDeviceProvisionedController.isDeviceProvisioned(),
                row.getIsNonblockable(),
                mHighPriorityProvider.isHighPriority(row.getEntry()),
                mAssistantFeedbackController);
    }

    /**
     * Sets up the {@link PartialConversationInfo} inside the notification row's guts.
     * @param row view to set up the guts for
     * @param notificationInfoView view to set up/bind within {@code row}
     */
    @VisibleForTesting
    void initializePartialConversationNotificationInfo(
            final ExpandableNotificationRow row,
            PartialConversationInfo notificationInfoView) throws Exception {
        NotificationGuts guts = row.getGuts();
        StatusBarNotification sbn = row.getEntry().getSbn();
        String packageName = sbn.getPackageName();
        // Settings link is only valid for notifications that specify a non-system user
        NotificationInfo.OnSettingsClickListener onSettingsClick = null;
        UserHandle userHandle = sbn.getUser();
        PackageManager pmUser = StatusBar.getPackageManagerForUser(
                mContext, userHandle.getIdentifier());

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
                mNotificationManager,
                mChannelEditorDialogController,
                packageName,
                row.getEntry().getChannel(),
                row.getUniqueChannels(),
                row.getEntry(),
                onSettingsClick,
                mDeviceProvisionedController.isDeviceProvisioned(),
                row.getIsNonblockable());
    }

    /**
     * Sets up the {@link ConversationInfo} inside the notification row's guts.
     * @param row view to set up the guts for
     * @param notificationInfoView view to set up/bind within {@code row}
     */
    @VisibleForTesting
    void initializeConversationNotificationInfo(
            final ExpandableNotificationRow row,
            NotificationConversationInfo notificationInfoView) throws Exception {
        NotificationGuts guts = row.getGuts();
        NotificationEntry entry = row.getEntry();
        StatusBarNotification sbn = entry.getSbn();
        String packageName = sbn.getPackageName();
        // Settings link is only valid for notifications that specify a non-system user
        NotificationConversationInfo.OnSettingsClickListener onSettingsClick = null;
        UserHandle userHandle = sbn.getUser();
        PackageManager pmUser = StatusBar.getPackageManagerForUser(
                mContext, userHandle.getIdentifier());
        final NotificationConversationInfo.OnAppSettingsClickListener onAppSettingsClick =
                (View v, Intent intent) -> {
                    mMetricsLogger.action(MetricsProto.MetricsEvent.ACTION_APP_NOTE_SETTINGS);
                    guts.resetFalsingCheck();
                    mNotificationActivityStarter.startNotificationGutsIntent(intent, sbn.getUid(),
                            row);
                };

        final NotificationConversationInfo.OnConversationSettingsClickListener
                onConversationSettingsListener =
                () -> {
                    startConversationSettingsActivity(sbn.getUid(), row);
                };

        if (!userHandle.equals(UserHandle.ALL)
                || mLockscreenUserManager.getCurrentUserId() == UserHandle.USER_SYSTEM) {
            onSettingsClick = (View v, NotificationChannel channel, int appUid) -> {
                mMetricsLogger.action(MetricsProto.MetricsEvent.ACTION_NOTE_INFO);
                guts.resetFalsingCheck();
                mOnSettingsClickListener.onSettingsClick(sbn.getKey());
                startAppNotificationSettingsActivity(packageName, appUid, channel, row);
            };
        }
        ConversationIconFactory iconFactoryLoader = new ConversationIconFactory(mContext,
                mLauncherApps, pmUser, IconDrawableFactory.newInstance(mContext, false),
                mContext.getResources().getDimensionPixelSize(
                        R.dimen.notification_guts_conversation_icon_size));

        notificationInfoView.bindNotification(
                notificationInfoView.getSelectedAction(),
                mShortcutManager,
                pmUser,
                mPeopleSpaceWidgetManager,
                mNotificationManager,
                mOnUserInteractionCallback,
                packageName,
                entry.getChannel(),
                entry,
                entry.getBubbleMetadata(),
                onSettingsClick,
                iconFactoryLoader,
                mContextTracker.getUserContext(),
                mDeviceProvisionedController.isDeviceProvisioned(),
                mMainHandler,
                mBgHandler,
                onConversationSettingsListener,
                mBubblesManagerOptional,
                mShadeController);
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
            mNotificationGutsExposed.removeCallbacks(mOpenRunnable);
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
        if (menuItem.getGutsView() instanceof NotificationGuts.GutsContent) {
            NotificationGuts.GutsContent gutsView =
                    (NotificationGuts.GutsContent)  menuItem.getGutsView();
            if (gutsView.needsFalsingProtection()) {
                if (mStatusBarStateController instanceof StatusBarStateControllerImpl) {
                    ((StatusBarStateControllerImpl) mStatusBarStateController)
                            .setLeaveOpenOnKeyguardHide(true);
                }

                Runnable r = () -> mMainHandler.post(
                        () -> openGutsInternal(view, x, y, menuItem));

                mStatusBarLazy.get().executeRunnableDismissingKeyguard(
                        r,
                        null /* cancelAction */,
                        false /* dismissShade */,
                        true /* afterKeyguardGone */,
                        true /* deferred */);

                return true;
            }
        }
        return openGutsInternal(view, x, y, menuItem);
    }

    @VisibleForTesting
    boolean openGutsInternal(
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
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (row.areGutsExposed()) {
            closeAndSaveGuts(false /* removeLeavebehind */, false /* force */,
                    true /* removeControls */, -1 /* x */, -1 /* y */,
                    true /* resetMenu */);
            return false;
        }

        row.ensureGutsInflated();
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
        mOpenRunnable = new Runnable() {
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
        };
        guts.post(mOpenRunnable);
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
            mKeyToRemoveOnGutsClosed = entry.getKey();
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Keeping notification because it's showing guts. " + entry.getKey());
            }
        } else {
            if (mKeyToRemoveOnGutsClosed != null
                    && mKeyToRemoveOnGutsClosed.equals(entry.getKey())) {
                mKeyToRemoveOnGutsClosed = null;
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Notification that was kept for guts was updated. "
                            + entry.getKey());
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
