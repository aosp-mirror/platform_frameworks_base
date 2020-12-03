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

package com.android.systemui.bubbles;

import static android.app.Notification.FLAG_BUBBLE;
import static android.app.NotificationManager.BUBBLE_PREFERENCE_NONE;
import static android.app.NotificationManager.BUBBLE_PREFERENCE_SELECTED;
import static android.service.notification.NotificationListenerService.REASON_APP_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_APP_CANCEL_ALL;
import static android.service.notification.NotificationListenerService.REASON_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_CANCEL_ALL;
import static android.service.notification.NotificationListenerService.REASON_CLICK;
import static android.service.notification.NotificationListenerService.REASON_GROUP_SUMMARY_CANCELED;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

import static com.android.systemui.bubbles.BubbleDebugConfig.DEBUG_BUBBLE_CONTROLLER;
import static com.android.systemui.bubbles.BubbleDebugConfig.TAG_BUBBLES;
import static com.android.systemui.bubbles.BubbleDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.systemui.statusbar.StatusBarState.SHADE;
import static com.android.systemui.statusbar.notification.NotificationEntryManager.UNDEFINED_DISMISS_REASON;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.LOCAL_VARIABLE;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.os.Binder;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.ZenModeConfig;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.util.SparseSetArray;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.annotation.IntDef;
import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.Dumpable;
import com.android.systemui.bubbles.dagger.BubbleModule;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.model.SysUiState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.PinnedStackListenerForwarder;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.WindowManagerWrapper;
import com.android.systemui.statusbar.FeatureFlags;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationRemoveInterceptor;
import com.android.systemui.statusbar.ScrimView;
import com.android.systemui.statusbar.notification.NotificationChannelHelper;
import com.android.systemui.statusbar.notification.NotificationEntryListener;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotifCollection;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.phone.NotificationShadeWindowController;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.phone.ShadeController;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.util.FloatingContentCoordinator;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bubbles are a special type of content that can "float" on top of other apps or System UI.
 * Bubbles can be expanded to show more content.
 *
 * The controller manages addition, removal, and visible state of bubbles on screen.
 */
public class BubbleController implements ConfigurationController.ConfigurationListener, Dumpable {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "BubbleController" : TAG_BUBBLES;

    @Retention(SOURCE)
    @IntDef({DISMISS_USER_GESTURE, DISMISS_AGED, DISMISS_TASK_FINISHED, DISMISS_BLOCKED,
            DISMISS_NOTIF_CANCEL, DISMISS_ACCESSIBILITY_ACTION, DISMISS_NO_LONGER_BUBBLE,
            DISMISS_USER_CHANGED, DISMISS_GROUP_CANCELLED, DISMISS_INVALID_INTENT,
            DISMISS_OVERFLOW_MAX_REACHED, DISMISS_SHORTCUT_REMOVED, DISMISS_PACKAGE_REMOVED,
            DISMISS_NO_BUBBLE_UP})
    @Target({FIELD, LOCAL_VARIABLE, PARAMETER})
    @interface DismissReason {}

    static final int DISMISS_USER_GESTURE = 1;
    static final int DISMISS_AGED = 2;
    static final int DISMISS_TASK_FINISHED = 3;
    static final int DISMISS_BLOCKED = 4;
    static final int DISMISS_NOTIF_CANCEL = 5;
    static final int DISMISS_ACCESSIBILITY_ACTION = 6;
    static final int DISMISS_NO_LONGER_BUBBLE = 7;
    static final int DISMISS_USER_CHANGED = 8;
    static final int DISMISS_GROUP_CANCELLED = 9;
    static final int DISMISS_INVALID_INTENT = 10;
    static final int DISMISS_OVERFLOW_MAX_REACHED = 11;
    static final int DISMISS_SHORTCUT_REMOVED = 12;
    static final int DISMISS_PACKAGE_REMOVED = 13;
    static final int DISMISS_NO_BUBBLE_UP = 14;

    private final Context mContext;
    private final NotificationEntryManager mNotificationEntryManager;
    private final NotifPipeline mNotifPipeline;
    private final BubbleTaskStackListener mTaskStackListener;
    private BubbleExpandListener mExpandListener;
    @Nullable private BubbleStackView.SurfaceSynchronizer mSurfaceSynchronizer;
    private final NotificationGroupManager mNotificationGroupManager;
    private final ShadeController mShadeController;
    private final FloatingContentCoordinator mFloatingContentCoordinator;
    private final BubbleDataRepository mDataRepository;
    private BubbleLogger mLogger = new BubbleLoggerImpl();

    private BubbleData mBubbleData;
    private ScrimView mBubbleScrim;
    @Nullable private BubbleStackView mStackView;
    private BubbleIconFactory mBubbleIconFactory;

    /**
     * The relative position of the stack when we removed it and nulled it out. If the stack is
     * re-created, it will re-appear at this position.
     */
    @Nullable private BubbleStackView.RelativeStackPosition mPositionFromRemovedStack;

    // Tracks the id of the current (foreground) user.
    private int mCurrentUserId;
    // Saves notification keys of active bubbles when users are switched.
    private final SparseSetArray<String> mSavedBubbleKeysPerUser;

    // Used when ranking updates occur and we check if things should bubble / unbubble
    private NotificationListenerService.Ranking mTmpRanking;

    // Bubbles get added to the status bar view
    private final NotificationShadeWindowController mNotificationShadeWindowController;
    private final ZenModeController mZenModeController;
    private StatusBarStateListener mStatusBarStateListener;
    private INotificationManager mINotificationManager;

    // Callback that updates BubbleOverflowActivity on data change.
    @Nullable private Runnable mOverflowCallback = null;

    // Only load overflow data from disk once
    private boolean mOverflowDataLoaded = false;

    /**
     * When the shade status changes to SHADE (from anything but SHADE, like LOCKED) we'll select
     * this bubble and expand the stack.
     */
    @Nullable private NotificationEntry mNotifEntryToExpandOnShadeUnlock;

    private final NotificationInterruptStateProvider mNotificationInterruptStateProvider;
    private IStatusBarService mBarService;
    private WindowManager mWindowManager;
    private SysUiState mSysUiState;

    // Used to post to main UI thread
    private Handler mHandler = new Handler();

    /** LayoutParams used to add the BubbleStackView to the window manager. */
    private WindowManager.LayoutParams mWmLayoutParams;
    /** Whether or not the BubbleStackView has been added to the WindowManager. */
    private boolean mAddedToWindowManager = false;

    // Listens to user switch so bubbles can be saved and restored.
    private final NotificationLockscreenUserManager mNotifUserManager;

    /** Last known orientation, used to detect orientation changes in {@link #onConfigChanged}. */
    private int mOrientation = Configuration.ORIENTATION_UNDEFINED;

    /**
     * Last known screen density, used to detect display size changes in {@link #onConfigChanged}.
     */
    private int mDensityDpi = Configuration.DENSITY_DPI_UNDEFINED;

    /** Last known direction, used to detect layout direction changes @link #onConfigChanged}. */
    private int mLayoutDirection = View.LAYOUT_DIRECTION_UNDEFINED;

    private boolean mInflateSynchronously;

    // TODO (b/145659174): allow for multiple callbacks to support the "shadow" new notif pipeline
    private final List<NotifCallback> mCallbacks = new ArrayList<>();

    /**
     * Whether the IME is visible, as reported by the BubbleStackView. If it is, we'll make the
     * Bubbles window NOT_FOCUSABLE so that touches on the Bubbles UI doesn't steal focus from the
     * ActivityView and hide the IME.
     */
    private boolean mImeVisible = false;

    /**
     * Listener to find out about stack expansion / collapse events.
     */
    public interface BubbleExpandListener {
        /**
         * Called when the expansion state of the bubble stack changes.
         *
         * @param isExpanding whether it's expanding or collapsing
         * @param key the notification key associated with bubble being expanded
         */
        void onBubbleExpandChanged(boolean isExpanding, String key);
    }

    /**
     * Listener to be notified when a bubbles' notification suppression state changes.
     */
    public interface NotificationSuppressionChangedListener {
        /**
         * Called when the notification suppression state of a bubble changes.
         */
        void onBubbleNotificationSuppressionChange(Bubble bubble);
    }

    /**
     * Listener to be notified when a pending intent has been canceled for a bubble.
     */
    public interface PendingIntentCanceledListener {
        /**
         * Called when the pending intent for a bubble has been canceled.
         */
        void onPendingIntentCanceled(Bubble bubble);
    }

    /**
     * Callback for when the BubbleController wants to interact with the notification pipeline to:
     * - Remove a previously bubbled notification
     * - Update the notification shade since bubbled notification should/shouldn't be showing
     */
    public interface NotifCallback {
        /**
         * Called when a bubbled notification that was hidden from the shade is now being removed
         * This can happen when an app cancels a bubbled notification or when the user dismisses a
         * bubble.
         */
        void removeNotification(@NonNull NotificationEntry entry, int reason);

        /**
         * Called when a bubbled notification has changed whether it should be
         * filtered from the shade.
         */
        void invalidateNotifications(@NonNull String reason);

        /**
         * Called on a bubbled entry that has been removed when there are no longer
         * bubbled entries in its group.
         *
         * Checks whether its group has any other (non-bubbled) children. If it doesn't,
         * removes all remnants of the group's summary from the notification pipeline.
         * TODO: (b/145659174) Only old pipeline needs this - delete post-migration.
         */
        void maybeCancelSummary(@NonNull NotificationEntry entry);
    }

    /**
     * Listens for the current state of the status bar and updates the visibility state
     * of bubbles as needed.
     */
    private class StatusBarStateListener implements StatusBarStateController.StateListener {
        private int mState;
        /**
         * Returns the current status bar state.
         */
        public int getCurrentState() {
            return mState;
        }

        @Override
        public void onStateChanged(int newState) {
            mState = newState;
            boolean shouldCollapse = (mState != SHADE);
            if (shouldCollapse) {
                collapseStack();
            }

            if (mNotifEntryToExpandOnShadeUnlock != null) {
                expandStackAndSelectBubble(mNotifEntryToExpandOnShadeUnlock);
                mNotifEntryToExpandOnShadeUnlock = null;
            }

            updateStack();
        }
    }

    /**
     * Injected constructor. See {@link BubbleModule}.
     */
    public BubbleController(Context context,
            NotificationShadeWindowController notificationShadeWindowController,
            StatusBarStateController statusBarStateController,
            ShadeController shadeController,
            BubbleData data,
            @Nullable BubbleStackView.SurfaceSynchronizer synchronizer,
            ConfigurationController configurationController,
            NotificationInterruptStateProvider interruptionStateProvider,
            ZenModeController zenModeController,
            NotificationLockscreenUserManager notifUserManager,
            NotificationGroupManager groupManager,
            NotificationEntryManager entryManager,
            NotifPipeline notifPipeline,
            FeatureFlags featureFlags,
            DumpManager dumpManager,
            FloatingContentCoordinator floatingContentCoordinator,
            BubbleDataRepository dataRepository,
            SysUiState sysUiState,
            INotificationManager notificationManager,
            @Nullable IStatusBarService statusBarService,
            WindowManager windowManager,
            LauncherApps launcherApps) {
        dumpManager.registerDumpable(TAG, this);
        mContext = context;
        mShadeController = shadeController;
        mNotificationInterruptStateProvider = interruptionStateProvider;
        mNotifUserManager = notifUserManager;
        mZenModeController = zenModeController;
        mFloatingContentCoordinator = floatingContentCoordinator;
        mDataRepository = dataRepository;
        mINotificationManager = notificationManager;
        mZenModeController.addCallback(new ZenModeController.Callback() {
            @Override
            public void onZenChanged(int zen) {
                for (Bubble b : mBubbleData.getBubbles()) {
                    b.setShowDot(b.showInShade());
                }
            }

            @Override
            public void onConfigChanged(ZenModeConfig config) {
                for (Bubble b : mBubbleData.getBubbles()) {
                    b.setShowDot(b.showInShade());
                }
            }
        });

        configurationController.addCallback(this /* configurationListener */);
        mSysUiState = sysUiState;

        mBubbleData = data;
        mBubbleData.setListener(mBubbleDataListener);
        mBubbleData.setSuppressionChangedListener(new NotificationSuppressionChangedListener() {
            @Override
            public void onBubbleNotificationSuppressionChange(Bubble bubble) {
                // Make sure NoMan knows it's not showing in the shade anymore so anyone querying it
                // can tell.
                try {
                    mBarService.onBubbleNotificationSuppressionChanged(bubble.getKey(),
                            !bubble.showInShade());
                } catch (RemoteException e) {
                    // Bad things have happened
                }
            }
        });
        mBubbleData.setPendingIntentCancelledListener(bubble -> {
            if (bubble.getBubbleIntent() == null) {
                return;
            }
            if (bubble.isIntentActive()
                    || mBubbleData.hasBubbleInStackWithKey(bubble.getKey())) {
                bubble.setPendingIntentCanceled();
                return;
            }
            mHandler.post(
                    () -> removeBubble(bubble.getKey(),
                            BubbleController.DISMISS_INVALID_INTENT));
        });

        mNotificationEntryManager = entryManager;
        mNotificationGroupManager = groupManager;
        mNotifPipeline = notifPipeline;

        if (!featureFlags.isNewNotifPipelineRenderingEnabled()) {
            setupNEM();
        } else {
            setupNotifPipeline();
        }

        mNotificationShadeWindowController = notificationShadeWindowController;
        mStatusBarStateListener = new StatusBarStateListener();
        statusBarStateController.addCallback(mStatusBarStateListener);

        mTaskStackListener = new BubbleTaskStackListener();
        ActivityManagerWrapper.getInstance().registerTaskStackListener(mTaskStackListener);

        try {
            WindowManagerWrapper.getInstance().addPinnedStackListener(new BubblesImeListener());
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        mSurfaceSynchronizer = synchronizer;

        mWindowManager = windowManager;
        mBarService = statusBarService == null
                ? IStatusBarService.Stub.asInterface(
                        ServiceManager.getService(Context.STATUS_BAR_SERVICE))
                : statusBarService;

        mBubbleScrim = new ScrimView(mContext);
        mBubbleScrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        mSavedBubbleKeysPerUser = new SparseSetArray<>();
        mCurrentUserId = mNotifUserManager.getCurrentUserId();
        mNotifUserManager.addUserChangedListener(
                new NotificationLockscreenUserManager.UserChangedListener() {
                    @Override
                    public void onUserChanged(int newUserId) {
                        BubbleController.this.saveBubbles(mCurrentUserId);
                        mBubbleData.dismissAll(DISMISS_USER_CHANGED);
                        BubbleController.this.restoreBubbles(newUserId);
                        mCurrentUserId = newUserId;
                    }
                });

        mBubbleIconFactory = new BubbleIconFactory(context);

        launcherApps.registerCallback(new LauncherApps.Callback() {
            @Override
            public void onPackageAdded(String s, UserHandle userHandle) {}

            @Override
            public void onPackageChanged(String s, UserHandle userHandle) {}

            @Override
            public void onPackageRemoved(String s, UserHandle userHandle) {
                // Remove bubbles with this package name, since it has been uninstalled and attempts
                // to open a bubble from an uninstalled app can cause issues.
                mBubbleData.removeBubblesWithPackageName(s, DISMISS_PACKAGE_REMOVED);
            }

            @Override
            public void onPackagesAvailable(String[] strings, UserHandle userHandle,
                    boolean b) {

            }

            @Override
            public void onPackagesUnavailable(String[] packages, UserHandle userHandle,
                    boolean b) {
                for (String packageName : packages) {
                    // Remove bubbles from unavailable apps. This can occur when the app is on
                    // external storage that has been removed.
                    mBubbleData.removeBubblesWithPackageName(packageName, DISMISS_PACKAGE_REMOVED);
                }
            }

            @Override
            public void onShortcutsChanged(String packageName, List<ShortcutInfo> validShortcuts,
                    UserHandle user) {
                super.onShortcutsChanged(packageName, validShortcuts, user);

                // Remove bubbles whose shortcuts aren't in the latest list of valid shortcuts.
                mBubbleData.removeBubblesWithInvalidShortcuts(
                        packageName, validShortcuts, DISMISS_SHORTCUT_REMOVED);
            }
        });
    }

    /**
     * See {@link NotifCallback}.
     */
    public void addNotifCallback(NotifCallback callback) {
        mCallbacks.add(callback);
    }

    /**
     * Hides the current input method, wherever it may be focused, via InputMethodManagerInternal.
     */
    public void hideCurrentInputMethod() {
        try {
            mBarService.hideCurrentInputMethodForBubbles();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private void setupNEM() {
        mNotificationEntryManager.addNotificationEntryListener(
                new NotificationEntryListener() {
                    @Override
                    public void onPendingEntryAdded(NotificationEntry entry) {
                        onEntryAdded(entry);
                    }

                    @Override
                    public void onPreEntryUpdated(NotificationEntry entry) {
                        onEntryUpdated(entry);
                    }

                    @Override
                    public void onEntryRemoved(
                            NotificationEntry entry,
                            @android.annotation.Nullable NotificationVisibility visibility,
                            boolean removedByUser,
                            int reason) {
                        BubbleController.this.onEntryRemoved(entry);
                    }

                    @Override
                    public void onNotificationRankingUpdated(RankingMap rankingMap) {
                        onRankingUpdated(rankingMap);
                    }
                });

        mNotificationEntryManager.addNotificationRemoveInterceptor(
                new NotificationRemoveInterceptor() {
                    @Override
                    public boolean onNotificationRemoveRequested(
                            String key,
                            NotificationEntry entry,
                            int dismissReason) {
                        final boolean isClearAll = dismissReason == REASON_CANCEL_ALL;
                        final boolean isUserDimiss = dismissReason == REASON_CANCEL
                                || dismissReason == REASON_CLICK;
                        final boolean isAppCancel = dismissReason == REASON_APP_CANCEL
                                || dismissReason == REASON_APP_CANCEL_ALL;
                        final boolean isSummaryCancel =
                                dismissReason == REASON_GROUP_SUMMARY_CANCELED;

                        // Need to check for !appCancel here because the notification may have
                        // previously been dismissed & entry.isRowDismissed would still be true
                        boolean userRemovedNotif =
                                (entry != null && entry.isRowDismissed() && !isAppCancel)
                                || isClearAll || isUserDimiss || isSummaryCancel;

                        if (userRemovedNotif) {
                            return handleDismissalInterception(entry);
                        }
                        return false;
                    }
                });

        mNotificationGroupManager.addOnGroupChangeListener(
                new NotificationGroupManager.OnGroupChangeListener() {
                    @Override
                    public void onGroupSuppressionChanged(
                            NotificationGroupManager.NotificationGroup group,
                            boolean suppressed) {
                        // More notifications could be added causing summary to no longer
                        // be suppressed -- in this case need to remove the key.
                        final String groupKey = group.summary != null
                                ? group.summary.getSbn().getGroupKey()
                                : null;
                        if (!suppressed && groupKey != null
                                && mBubbleData.isSummarySuppressed(groupKey)) {
                            mBubbleData.removeSuppressedSummary(groupKey);
                        }
                    }
                });

        addNotifCallback(new NotifCallback() {
            @Override
            public void removeNotification(NotificationEntry entry, int reason) {
                mNotificationEntryManager.performRemoveNotification(entry.getSbn(), reason);
            }

            @Override
            public void invalidateNotifications(String reason) {
                mNotificationEntryManager.updateNotifications(reason);
            }

            @Override
            public void maybeCancelSummary(NotificationEntry entry) {
                // Check if removed bubble has an associated suppressed group summary that needs
                // to be removed now.
                final String groupKey = entry.getSbn().getGroupKey();
                if (mBubbleData.isSummarySuppressed(groupKey)) {
                    mBubbleData.removeSuppressedSummary(groupKey);

                    final NotificationEntry summary =
                            mNotificationEntryManager.getActiveNotificationUnfiltered(
                                    mBubbleData.getSummaryKey(groupKey));
                    if (summary != null) {
                        mNotificationEntryManager.performRemoveNotification(summary.getSbn(),
                                UNDEFINED_DISMISS_REASON);
                    }
                }

                // Check if we still need to remove the summary from NoManGroup because the summary
                // may not be in the mBubbleData.mSuppressedGroupKeys list and removed above.
                // For example:
                // 1. Bubbled notifications (group) is posted to shade and are visible bubbles
                // 2. User expands bubbles so now their respective notifications in the shade are
                // hidden, including the group summary
                // 3. User removes all bubbles
                // 4. We expect all the removed bubbles AND the summary (note: the summary was
                // never added to the suppressedSummary list in BubbleData, so we add this check)
                NotificationEntry summary =
                        mNotificationGroupManager.getLogicalGroupSummary(entry.getSbn());
                if (summary != null) {
                    ArrayList<NotificationEntry> summaryChildren =
                            mNotificationGroupManager.getLogicalChildren(summary.getSbn());
                    boolean isSummaryThisNotif = summary.getKey().equals(entry.getKey());
                    if (!isSummaryThisNotif && (summaryChildren == null
                            || summaryChildren.isEmpty())) {
                        mNotificationEntryManager.performRemoveNotification(summary.getSbn(),
                                UNDEFINED_DISMISS_REASON);
                    }
                }
            }
        });
    }

    private void setupNotifPipeline() {
        mNotifPipeline.addCollectionListener(new NotifCollectionListener() {
            @Override
            public void onEntryAdded(NotificationEntry entry) {
                BubbleController.this.onEntryAdded(entry);
            }

            @Override
            public void onEntryUpdated(NotificationEntry entry) {
                BubbleController.this.onEntryUpdated(entry);
            }

            @Override
            public void onRankingUpdate(RankingMap rankingMap) {
                onRankingUpdated(rankingMap);
            }

            @Override
            public void onEntryRemoved(NotificationEntry entry,
                    @NotifCollection.CancellationReason int reason) {
                BubbleController.this.onEntryRemoved(entry);
            }
        });
    }

    /**
     * Returns the scrim drawn behind the bubble stack. This is managed by {@link ScrimController}
     * since we want the scrim's appearance and behavior to be identical to that of the notification
     * shade scrim.
     */
    public ScrimView getScrimForBubble() {
        return mBubbleScrim;
    }

    /**
     * Called when the status bar has become visible or invisible (either permanently or
     * temporarily).
     */
    public void onStatusBarVisibilityChanged(boolean visible) {
        if (mStackView != null) {
            // Hide the stack temporarily if the status bar has been made invisible, and the stack
            // is collapsed. An expanded stack should remain visible until collapsed.
            mStackView.setTemporarilyInvisible(!visible && !isStackExpanded());
        }
    }

    /**
     * Sets whether to perform inflation on the same thread as the caller. This method should only
     * be used in tests, not in production.
     */
    @VisibleForTesting
    void setInflateSynchronously(boolean inflateSynchronously) {
        mInflateSynchronously = inflateSynchronously;
    }

    void setOverflowCallback(Runnable updateOverflow) {
        mOverflowCallback = updateOverflow;
    }

    /**
     * @return Bubbles for updating overflow.
     */
    List<Bubble> getOverflowBubbles() {
        return mBubbleData.getOverflowBubbles();
    }

    /**
     * BubbleStackView is lazily created by this method the first time a Bubble is added. This
     * method initializes the stack view and adds it to the StatusBar just above the scrim.
     */
    private void ensureStackViewCreated() {
        if (mStackView == null) {
            mStackView = new BubbleStackView(
                    mContext, mBubbleData, mSurfaceSynchronizer, mFloatingContentCoordinator,
                    mSysUiState, this::onAllBubblesAnimatedOut, this::onImeVisibilityChanged,
                    this::hideCurrentInputMethod);
            mStackView.setStackStartPosition(mPositionFromRemovedStack);
            mStackView.addView(mBubbleScrim);
            if (mExpandListener != null) {
                mStackView.setExpandListener(mExpandListener);
            }

            mStackView.setUnbubbleConversationCallback(key -> {
                final NotificationEntry entry =
                        mNotificationEntryManager.getPendingOrActiveNotif(key);
                if (entry != null) {
                    onUserChangedBubble(entry, false /* shouldBubble */);
                }
            });
        }

        addToWindowManagerMaybe();
    }

    /** Adds the BubbleStackView to the WindowManager if it's not already there. */
    private void addToWindowManagerMaybe() {
        // If the stack is null, or already added, don't add it.
        if (mStackView == null || mAddedToWindowManager) {
            return;
        }

        mWmLayoutParams = new WindowManager.LayoutParams(
                // Fill the screen so we can use translation animations to position the bubble
                // stack. We'll use touchable regions to ignore touches that are not on the bubbles
                // themselves.
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_TRUSTED_APPLICATION_OVERLAY,
                // Start not focusable - we'll become focusable when expanded so the ActivityView
                // can use the IME.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);

        mWmLayoutParams.setFitInsetsTypes(0);
        mWmLayoutParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        mWmLayoutParams.token = new Binder();
        mWmLayoutParams.setTitle("Bubbles!");
        mWmLayoutParams.packageName = mContext.getPackageName();
        mWmLayoutParams.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

        try {
            mAddedToWindowManager = true;
            mWindowManager.addView(mStackView, mWmLayoutParams);
        } catch (IllegalStateException e) {
            // This means the stack has already been added. This shouldn't happen, since we keep
            // track of that, but just in case, update the previously added view's layout params.
            e.printStackTrace();
            updateWmFlags();
        }
    }

    private void onImeVisibilityChanged(boolean imeVisible) {
        mImeVisible = imeVisible;
        updateWmFlags();
    }

    /** Removes the BubbleStackView from the WindowManager if it's there. */
    private void removeFromWindowManagerMaybe() {
        if (!mAddedToWindowManager) {
            return;
        }

        try {
            mAddedToWindowManager = false;
            if (mStackView != null) {
                mPositionFromRemovedStack = mStackView.getRelativeStackPosition();
                mWindowManager.removeView(mStackView);
                mStackView.removeView(mBubbleScrim);
                mStackView = null;
            } else {
                Log.w(TAG, "StackView added to WindowManager, but was null when removing!");
            }
        } catch (IllegalArgumentException e) {
            // This means the stack has already been removed - it shouldn't happen, but ignore if it
            // does, since we wanted it removed anyway.
            e.printStackTrace();
        }
    }

    /**
     * Updates the BubbleStackView's WindowManager.LayoutParams, and updates the WindowManager with
     * the new params if the stack has been added.
     */
    private void updateWmFlags() {
        if (mStackView == null) {
            return;
        }
        if (isStackExpanded() && !mImeVisible) {
            // If we're expanded, and the IME isn't visible, we want to be focusable. This ensures
            // that any taps within Bubbles (including on the ActivityView) results in Bubbles
            // receiving focus and clearing it from any other windows that might have it.
            mWmLayoutParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        } else {
            // If we're collapsed, we don't want to be focusable since tapping on the stack would
            // steal focus from apps. We also don't want to be focusable if the IME is visible,
            mWmLayoutParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }

        if (mAddedToWindowManager) {
            try {
                mWindowManager.updateViewLayout(mStackView, mWmLayoutParams);
            } catch (IllegalArgumentException e) {
                // If the stack is somehow not there, ignore the attempt to update it.
                e.printStackTrace();
            }
        }
    }

    /**
     * Called by the BubbleStackView and whenever all bubbles have animated out, and none have been
     * added in the meantime.
     */
    private void onAllBubblesAnimatedOut() {
        if (mStackView != null) {
            mStackView.setVisibility(INVISIBLE);
            removeFromWindowManagerMaybe();
        }
    }

    /**
     * Records the notification key for any active bubbles. These are used to restore active
     * bubbles when the user returns to the foreground.
     *
     * @param userId the id of the user
     */
    private void saveBubbles(@UserIdInt int userId) {
        // First clear any existing keys that might be stored.
        mSavedBubbleKeysPerUser.remove(userId);
        // Add in all active bubbles for the current user.
        for (Bubble bubble: mBubbleData.getBubbles()) {
            mSavedBubbleKeysPerUser.add(userId, bubble.getKey());
        }
    }

    /**
     * Promotes existing notifications to Bubbles if they were previously bubbles.
     *
     * @param userId the id of the user
     */
    private void restoreBubbles(@UserIdInt int userId) {
        ArraySet<String> savedBubbleKeys = mSavedBubbleKeysPerUser.get(userId);
        if (savedBubbleKeys == null) {
            // There were no bubbles saved for this used.
            return;
        }
        for (NotificationEntry e :
                mNotificationEntryManager.getActiveNotificationsForCurrentUser()) {
            if (savedBubbleKeys.contains(e.getKey())
                    && mNotificationInterruptStateProvider.shouldBubbleUp(e)
                    && e.isBubble()
                    && canLaunchInActivityView(mContext, e)) {
                updateBubble(e, true /* suppressFlyout */, false /* showInShade */);
            }
        }
        // Finally, remove the entries for this user now that bubbles are restored.
        mSavedBubbleKeysPerUser.remove(mCurrentUserId);
    }

    @Override
    public void onUiModeChanged() {
        updateForThemeChanges();
    }

    @Override
    public void onOverlayChanged() {
        updateForThemeChanges();
    }

    private void updateForThemeChanges() {
        if (mStackView != null) {
            mStackView.onThemeChanged();
        }
        mBubbleIconFactory = new BubbleIconFactory(mContext);
        // Reload each bubble
        for (Bubble b: mBubbleData.getBubbles()) {
            b.inflate(null /* callback */, mContext, mStackView, mBubbleIconFactory,
                    false /* skipInflation */);
        }
        for (Bubble b: mBubbleData.getOverflowBubbles()) {
            b.inflate(null /* callback */, mContext, mStackView, mBubbleIconFactory,
                    false /* skipInflation */);
        }
    }

    @Override
    public void onConfigChanged(Configuration newConfig) {
        if (mStackView != null && newConfig != null) {
            if (newConfig.orientation != mOrientation) {
                mOrientation = newConfig.orientation;
                mStackView.onOrientationChanged(newConfig.orientation);
            }
            if (newConfig.densityDpi != mDensityDpi) {
                mDensityDpi = newConfig.densityDpi;
                mBubbleIconFactory = new BubbleIconFactory(mContext);
                mStackView.onDisplaySizeChanged();
            }
            if (newConfig.getLayoutDirection() != mLayoutDirection) {
                mLayoutDirection = newConfig.getLayoutDirection();
                mStackView.onLayoutDirectionChanged(mLayoutDirection);
            }
        }
    }

    boolean inLandscape() {
        return mOrientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    /**
     * Set a listener to be notified of bubble expand events.
     */
    public void setExpandListener(BubbleExpandListener listener) {
        mExpandListener = ((isExpanding, key) -> {
            if (listener != null) {
                listener.onBubbleExpandChanged(isExpanding, key);
            }

            updateWmFlags();
        });
        if (mStackView != null) {
            mStackView.setExpandListener(mExpandListener);
        }
    }

    /**
     * Whether or not there are bubbles present, regardless of them being visible on the
     * screen (e.g. if on AOD).
     */
    @VisibleForTesting
    boolean hasBubbles() {
        if (mStackView == null) {
            return false;
        }
        return mBubbleData.hasBubbles();
    }

    /**
     * Whether the stack of bubbles is expanded or not.
     */
    public boolean isStackExpanded() {
        return mBubbleData.isExpanded();
    }

    /**
     * Tell the stack of bubbles to collapse.
     */
    public void collapseStack() {
        mBubbleData.setExpanded(false /* expanded */);
    }

    /**
     * True if either:
     * (1) There is a bubble associated with the provided key and if its notification is hidden
     *     from the shade.
     * (2) There is a group summary associated with the provided key that is hidden from the shade
     *     because it has been dismissed but still has child bubbles active.
     *
     * False otherwise.
     */
    public boolean isBubbleNotificationSuppressedFromShade(NotificationEntry entry) {
        String key = entry.getKey();
        boolean isSuppressedBubble = (mBubbleData.hasAnyBubbleWithKey(key)
                && !mBubbleData.getAnyBubbleWithkey(key).showInShade());

        String groupKey = entry.getSbn().getGroupKey();
        boolean isSuppressedSummary = mBubbleData.isSummarySuppressed(groupKey);
        boolean isSummary = key.equals(mBubbleData.getSummaryKey(groupKey));
        return (isSummary && isSuppressedSummary) || isSuppressedBubble;
    }

    /**
     * True if:
     * (1) The current notification entry same as selected bubble notification entry and the
     * stack is currently expanded.
     *
     * False otherwise.
     */
    public boolean isBubbleExpanded(NotificationEntry entry) {
        return isStackExpanded() && mBubbleData != null && mBubbleData.getSelectedBubble() != null
                && mBubbleData.getSelectedBubble().getKey().equals(entry.getKey()) ? true : false;
    }

    void promoteBubbleFromOverflow(Bubble bubble) {
        mLogger.log(bubble, BubbleLogger.Event.BUBBLE_OVERFLOW_REMOVE_BACK_TO_STACK);
        bubble.setInflateSynchronously(mInflateSynchronously);
        bubble.setShouldAutoExpand(true);
        bubble.markAsAccessedAt(System.currentTimeMillis());
        setIsBubble(bubble, true /* isBubble */);
    }

    /**
     * Request the stack expand if needed, then select the specified Bubble as current.
     * If no bubble exists for this entry, one is created.
     *
     * @param entry the notification for the bubble to be selected
     */
    public void expandStackAndSelectBubble(NotificationEntry entry) {
        if (mStatusBarStateListener.getCurrentState() == SHADE) {
            mNotifEntryToExpandOnShadeUnlock = null;

            String key = entry.getKey();
            Bubble bubble = mBubbleData.getBubbleInStackWithKey(key);
            if (bubble != null) {
                mBubbleData.setSelectedBubble(bubble);
                mBubbleData.setExpanded(true);
            } else {
                bubble = mBubbleData.getOverflowBubbleWithKey(key);
                if (bubble != null) {
                    promoteBubbleFromOverflow(bubble);
                } else if (entry.canBubble()) {
                    // It can bubble but it's not -- it got aged out of the overflow before it
                    // was dismissed or opened, make it a bubble again.
                    setIsBubble(entry, true /* isBubble */, true /* autoExpand */);
                }
            }
        } else {
            // Wait until we're unlocked to expand, so that the user can see the expand animation
            // and also to work around bugs with expansion animation + shade unlock happening at the
            // same time.
            mNotifEntryToExpandOnShadeUnlock = entry;
        }
    }

    /**
     * When a notification is marked Priority, expand the stack if needed,
     * then (maybe create and) select the given bubble.
     *
     * @param entry the notification for the bubble to show
     */
    public void onUserChangedImportance(NotificationEntry entry) {
        try {
            int flags = Notification.BubbleMetadata.FLAG_SUPPRESS_NOTIFICATION;
            flags |= Notification.BubbleMetadata.FLAG_AUTO_EXPAND_BUBBLE;
            mBarService.onNotificationBubbleChanged(entry.getKey(), true, flags);
        } catch (RemoteException e) {
            Log.e(TAG, e.getMessage());
        }
        mShadeController.collapsePanel(true);
        if (entry.getRow() != null) {
            entry.getRow().updateBubbleButton();
        }
    }

    /**
     * Directs a back gesture at the bubble stack. When opened, the current expanded bubble
     * is forwarded a back key down/up pair.
     */
    public void performBackPressIfNeeded() {
        if (mStackView != null) {
            mStackView.performBackPressIfNeeded();
        }
    }

    /**
     * Adds or updates a bubble associated with the provided notification entry.
     *
     * @param notif the notification associated with this bubble.
     */
    void updateBubble(NotificationEntry notif) {
        updateBubble(notif, false /* suppressFlyout */, true /* showInShade */);
    }

    /**
     * Fills the overflow bubbles by loading them from disk.
     */
    void loadOverflowBubblesFromDisk() {
        if (!mBubbleData.getOverflowBubbles().isEmpty() || mOverflowDataLoaded) {
            // we don't need to load overflow bubbles from disk if it is already in memory
            return;
        }
        mOverflowDataLoaded = true;
        mDataRepository.loadBubbles((bubbles) -> {
            bubbles.forEach(bubble -> {
                if (mBubbleData.hasAnyBubbleWithKey(bubble.getKey())) {
                    // if the bubble is already active, there's no need to push it to overflow
                    return;
                }
                bubble.inflate((b) -> mBubbleData.overflowBubble(DISMISS_AGED, bubble),
                        mContext, mStackView, mBubbleIconFactory, true /* skipInflation */);
            });
            return null;
        });
    }

    void updateBubble(NotificationEntry notif, boolean suppressFlyout, boolean showInShade) {
        // If this is an interruptive notif, mark that it's interrupted
        if (notif.getImportance() >= NotificationManager.IMPORTANCE_HIGH) {
            notif.setInterruption();
        }
        if (!notif.getRanking().visuallyInterruptive()
                && (notif.getBubbleMetadata() != null
                    && !notif.getBubbleMetadata().getAutoExpandBubble())
                && mBubbleData.hasOverflowBubbleWithKey(notif.getKey())) {
            // Update the bubble but don't promote it out of overflow
            Bubble b = mBubbleData.getOverflowBubbleWithKey(notif.getKey());
            b.setEntry(notif);
        } else {
            Bubble bubble = mBubbleData.getOrCreateBubble(notif, null /* persistedBubble */);
            inflateAndAdd(bubble, suppressFlyout, showInShade);
        }
    }

    void inflateAndAdd(Bubble bubble, boolean suppressFlyout, boolean showInShade) {
        // Lazy init stack view when a bubble is created
        ensureStackViewCreated();
        bubble.setInflateSynchronously(mInflateSynchronously);
        bubble.inflate(b -> mBubbleData.notificationEntryUpdated(b, suppressFlyout, showInShade),
                mContext, mStackView, mBubbleIconFactory, false /* skipInflation */);
    }

    /**
     * Called when a user has indicated that an active notification should be shown as a bubble.
     * <p>
     * This method will collapse the shade, create the bubble without a flyout or dot, and suppress
     * the notification from appearing in the shade.
     *
     * @param entry the notification to change bubble state for.
     * @param shouldBubble whether the notification should show as a bubble or not.
     */
    public void onUserChangedBubble(@NonNull final NotificationEntry entry, boolean shouldBubble) {
        NotificationChannel channel = entry.getChannel();
        final String appPkg = entry.getSbn().getPackageName();
        final int appUid = entry.getSbn().getUid();
        if (channel == null || appPkg == null) {
            return;
        }

        // Update the state in NotificationManagerService
        try {
            int flags = Notification.BubbleMetadata.FLAG_SUPPRESS_NOTIFICATION;
            flags |= Notification.BubbleMetadata.FLAG_AUTO_EXPAND_BUBBLE;
            mBarService.onNotificationBubbleChanged(entry.getKey(), shouldBubble, flags);
        } catch (RemoteException e) {
        }

        // Change the settings
        channel = NotificationChannelHelper.createConversationChannelIfNeeded(mContext,
                mINotificationManager, entry, channel);
        channel.setAllowBubbles(shouldBubble);
        try {
            int currentPref = mINotificationManager.getBubblePreferenceForPackage(appPkg, appUid);
            if (shouldBubble && currentPref == BUBBLE_PREFERENCE_NONE) {
                mINotificationManager.setBubblesAllowed(appPkg, appUid, BUBBLE_PREFERENCE_SELECTED);
            }
            mINotificationManager.updateNotificationChannelForPackage(appPkg, appUid, channel);
        } catch (RemoteException e) {
            Log.e(TAG, e.getMessage());
        }

        if (shouldBubble) {
            mShadeController.collapsePanel(true);
            if (entry.getRow() != null) {
                entry.getRow().updateBubbleButton();
            }
        }
    }

    /**
     * Removes the bubble with the given key.
     * <p>
     * Must be called from the main thread.
     */
    @MainThread
    void removeBubble(String key, int reason) {
        if (mBubbleData.hasAnyBubbleWithKey(key)) {
            mBubbleData.dismissBubbleWithKey(key, reason);
        }
    }

    private void onEntryAdded(NotificationEntry entry) {
        if (mNotificationInterruptStateProvider.shouldBubbleUp(entry)
                && entry.isBubble()
                && canLaunchInActivityView(mContext, entry)) {
            updateBubble(entry);
        }
    }

    private void onEntryUpdated(NotificationEntry entry) {
        // shouldBubbleUp checks canBubble & for bubble metadata
        boolean shouldBubble = mNotificationInterruptStateProvider.shouldBubbleUp(entry)
                && canLaunchInActivityView(mContext, entry);
        if (!shouldBubble && mBubbleData.hasAnyBubbleWithKey(entry.getKey())) {
            // It was previously a bubble but no longer a bubble -- lets remove it
            removeBubble(entry.getKey(), DISMISS_NO_LONGER_BUBBLE);
        } else if (shouldBubble && entry.isBubble()) {
            updateBubble(entry);
        }
    }

    private void onEntryRemoved(NotificationEntry entry) {
        if (isSummaryOfBubbles(entry)) {
            final String groupKey = entry.getSbn().getGroupKey();
            mBubbleData.removeSuppressedSummary(groupKey);

            // Remove any associated bubble children with the summary
            final List<Bubble> bubbleChildren = mBubbleData.getBubblesInGroup(
                    groupKey, mNotificationEntryManager);
            for (int i = 0; i < bubbleChildren.size(); i++) {
                removeBubble(bubbleChildren.get(i).getKey(), DISMISS_GROUP_CANCELLED);
            }
        } else {
            removeBubble(entry.getKey(), DISMISS_NOTIF_CANCEL);
        }
    }

    /**
     * Called when NotificationListener has received adjusted notification rank and reapplied
     * filtering and sorting. This is used to dismiss or create bubbles based on changes in
     * permissions on the notification channel or the global setting.
     *
     * @param rankingMap the updated ranking map from NotificationListenerService
     */
    private void onRankingUpdated(RankingMap rankingMap) {
        if (mTmpRanking == null) {
            mTmpRanking = new NotificationListenerService.Ranking();
        }
        String[] orderedKeys = rankingMap.getOrderedKeys();
        for (int i = 0; i < orderedKeys.length; i++) {
            String key = orderedKeys[i];
            NotificationEntry entry = mNotificationEntryManager.getPendingOrActiveNotif(key);
            rankingMap.getRanking(key, mTmpRanking);
            boolean isActiveBubble = mBubbleData.hasAnyBubbleWithKey(key);
            if (isActiveBubble && !mTmpRanking.canBubble()) {
                // If this entry is no longer allowed to bubble, dismiss with the BLOCKED reason.
                // This means that the app or channel's ability to bubble has been revoked.
                mBubbleData.dismissBubbleWithKey(
                        key, BubbleController.DISMISS_BLOCKED);
            } else if (isActiveBubble
                    && !mNotificationInterruptStateProvider.shouldBubbleUp(entry)) {
                // If this entry is allowed to bubble, but cannot currently bubble up, dismiss it.
                // This happens when DND is enabled and configured to hide bubbles. Dismissing with
                // the reason DISMISS_NO_BUBBLE_UP will retain the underlying notification, so that
                // the bubble will be re-created if shouldBubbleUp returns true.
                mBubbleData.dismissBubbleWithKey(
                        key, BubbleController.DISMISS_NO_BUBBLE_UP);
            } else if (entry != null && mTmpRanking.isBubble() && !isActiveBubble) {
                entry.setFlagBubble(true);
                onEntryUpdated(entry);
            }
        }
    }

    private void setIsBubble(@NonNull final NotificationEntry entry, final boolean isBubble,
            final boolean autoExpand) {
        Objects.requireNonNull(entry);
        if (isBubble) {
            entry.getSbn().getNotification().flags |= FLAG_BUBBLE;
        } else {
            entry.getSbn().getNotification().flags &= ~FLAG_BUBBLE;
        }
        try {
            int flags = 0;
            if (autoExpand) {
                flags = Notification.BubbleMetadata.FLAG_SUPPRESS_NOTIFICATION;
                flags |= Notification.BubbleMetadata.FLAG_AUTO_EXPAND_BUBBLE;
            }
            mBarService.onNotificationBubbleChanged(entry.getKey(), isBubble, flags);
        } catch (RemoteException e) {
            // Bad things have happened
        }
    }

    private void setIsBubble(@NonNull final Bubble b, final boolean isBubble) {
        Objects.requireNonNull(b);
        b.setIsBubble(isBubble);
        final NotificationEntry entry = mNotificationEntryManager
                .getPendingOrActiveNotif(b.getKey());
        if (entry != null) {
            // Updating the entry to be a bubble will trigger our normal update flow
            setIsBubble(entry, isBubble, b.shouldAutoExpand());
        } else if (isBubble) {
            // If bubble doesn't exist, it's a persisted bubble so we need to add it to the
            // stack ourselves
            Bubble bubble = mBubbleData.getOrCreateBubble(null, b /* persistedBubble */);
            inflateAndAdd(bubble, bubble.shouldAutoExpand() /* suppressFlyout */,
                    !bubble.shouldAutoExpand() /* showInShade */);
        }
    }

    @SuppressWarnings("FieldCanBeLocal")
    private final BubbleData.Listener mBubbleDataListener = new BubbleData.Listener() {

        @Override
        public void applyUpdate(BubbleData.Update update) {
            ensureStackViewCreated();

            // Lazy load overflow bubbles from disk
            loadOverflowBubblesFromDisk();
            // Update bubbles in overflow.
            if (mOverflowCallback != null) {
                mOverflowCallback.run();
            }

            // Collapsing? Do this first before remaining steps.
            if (update.expandedChanged && !update.expanded) {
                mStackView.setExpanded(false);
                mNotificationShadeWindowController.setRequestTopUi(false, TAG);
            }

            // Do removals, if any.
            ArrayList<Pair<Bubble, Integer>> removedBubbles =
                    new ArrayList<>(update.removedBubbles);
            ArrayList<Bubble> bubblesToBeRemovedFromRepository = new ArrayList<>();
            for (Pair<Bubble, Integer> removed : removedBubbles) {
                final Bubble bubble = removed.first;
                @DismissReason final int reason = removed.second;

                if (mStackView != null) {
                    mStackView.removeBubble(bubble);
                }

                // Leave the notification in place if we're dismissing due to user switching, or
                // because DND is suppressing the bubble. In both of those cases, we need to be able
                // to restore the bubble from the notification later.
                if (reason == DISMISS_USER_CHANGED || reason == DISMISS_NO_BUBBLE_UP) {
                    continue;
                }
                if (reason == DISMISS_NOTIF_CANCEL) {
                    bubblesToBeRemovedFromRepository.add(bubble);
                }
                final NotificationEntry entry = mNotificationEntryManager.getPendingOrActiveNotif(
                        bubble.getKey());
                if (!mBubbleData.hasBubbleInStackWithKey(bubble.getKey())) {
                    if (!mBubbleData.hasOverflowBubbleWithKey(bubble.getKey())
                        && (!bubble.showInShade()
                            || reason == DISMISS_NOTIF_CANCEL
                            || reason == DISMISS_GROUP_CANCELLED)) {
                        // The bubble is now gone & the notification is hidden from the shade, so
                        // time to actually remove it
                        for (NotifCallback cb : mCallbacks) {
                            if (entry != null) {
                                cb.removeNotification(entry, REASON_CANCEL);
                            }
                        }
                    } else {
                        if (bubble.isBubble()) {
                            setIsBubble(bubble, false /* isBubble */);
                        }
                        if (entry != null && entry.getRow() != null) {
                            entry.getRow().updateBubbleButton();
                        }
                    }

                }
                if (entry != null) {
                    final String groupKey = entry.getSbn().getGroupKey();
                    if (mBubbleData.getBubblesInGroup(
                            groupKey, mNotificationEntryManager).isEmpty()) {
                        // Time to potentially remove the summary
                        for (NotifCallback cb : mCallbacks) {
                            cb.maybeCancelSummary(entry);
                        }
                    }
                }
            }
            mDataRepository.removeBubbles(mCurrentUserId, bubblesToBeRemovedFromRepository);

            if (update.addedBubble != null && mStackView != null) {
                mDataRepository.addBubble(mCurrentUserId, update.addedBubble);
                mStackView.addBubble(update.addedBubble);
            }

            if (update.updatedBubble != null && mStackView != null) {
                mStackView.updateBubble(update.updatedBubble);
            }

            // At this point, the correct bubbles are inflated in the stack.
            // Make sure the order in bubble data is reflected in bubble row.
            if (update.orderChanged && mStackView != null) {
                mDataRepository.addBubbles(mCurrentUserId, update.bubbles);
                mStackView.updateBubbleOrder(update.bubbles);
            }

            if (update.selectionChanged && mStackView != null) {
                mStackView.setSelectedBubble(update.selectedBubble);
                if (update.selectedBubble != null) {
                    final NotificationEntry entry = mNotificationEntryManager
                            .getPendingOrActiveNotif(update.selectedBubble.getKey());
                    if (entry != null) {
                        mNotificationGroupManager.updateSuppression(entry);
                    }
                }
            }

            // Expanding? Apply this last.
            if (update.expandedChanged && update.expanded) {
                if (mStackView != null) {
                    mStackView.setExpanded(true);
                    mNotificationShadeWindowController.setRequestTopUi(true, TAG);
                }
            }

            for (NotifCallback cb : mCallbacks) {
                cb.invalidateNotifications("BubbleData.Listener.applyUpdate");
            }
            updateStack();

            if (DEBUG_BUBBLE_CONTROLLER) {
                Log.d(TAG, "\n[BubbleData] bubbles:");
                Log.d(TAG, BubbleDebugConfig.formatBubblesString(mBubbleData.getBubbles(),
                        mBubbleData.getSelectedBubble()));

                if (mStackView != null) {
                    Log.d(TAG, "\n[BubbleStackView]");
                    Log.d(TAG, BubbleDebugConfig.formatBubblesString(mStackView.getBubblesOnScreen(),
                            mStackView.getExpandedBubble()));
                }
                Log.d(TAG, "\n[BubbleData] overflow:");
                Log.d(TAG, BubbleDebugConfig.formatBubblesString(mBubbleData.getOverflowBubbles(),
                        null) + "\n");
            }
        }
    };

    /**
     * We intercept notification entries (including group summaries) dismissed by the user when
     * there is an active bubble associated with it. We do this so that developers can still
     * cancel it (and hence the bubbles associated with it). However, these intercepted
     * notifications should then be hidden from the shade since the user has cancelled them, so we
     *  {@link Bubble#setSuppressNotification}.  For the case of suppressed summaries, we also add
     *  {@link BubbleData#addSummaryToSuppress}.
     *
     * @return true if we want to intercept the dismissal of the entry, else false.
     */
    public boolean handleDismissalInterception(NotificationEntry entry) {
        if (entry == null) {
            return false;
        }
        if (isSummaryOfBubbles(entry)) {
            handleSummaryDismissalInterception(entry);
        } else {
            Bubble bubble = mBubbleData.getBubbleInStackWithKey(entry.getKey());
            if (bubble == null || !entry.isBubble()) {
                bubble = mBubbleData.getOverflowBubbleWithKey(entry.getKey());
            }
            if (bubble == null) {
                return false;
            }
            bubble.setSuppressNotification(true);
            bubble.setShowDot(false /* show */);
        }
        // Update the shade
        for (NotifCallback cb : mCallbacks) {
            cb.invalidateNotifications("BubbleController.handleDismissalInterception");
        }
        return true;
    }

    private boolean isSummaryOfBubbles(NotificationEntry entry) {
        if (entry == null) {
            return false;
        }

        String groupKey = entry.getSbn().getGroupKey();
        ArrayList<Bubble> bubbleChildren = mBubbleData.getBubblesInGroup(
                groupKey, mNotificationEntryManager);
        boolean isSuppressedSummary = (mBubbleData.isSummarySuppressed(groupKey)
                && mBubbleData.getSummaryKey(groupKey).equals(entry.getKey()));
        boolean isSummary = entry.getSbn().getNotification().isGroupSummary();
        return (isSuppressedSummary || isSummary)
                && bubbleChildren != null
                && !bubbleChildren.isEmpty();
    }

    private void handleSummaryDismissalInterception(NotificationEntry summary) {
        // current children in the row:
        final List<NotificationEntry> children = summary.getAttachedNotifChildren();
        if (children != null) {
            for (int i = 0; i < children.size(); i++) {
                NotificationEntry child = children.get(i);
                if (mBubbleData.hasAnyBubbleWithKey(child.getKey())) {
                    // Suppress the bubbled child
                    // As far as group manager is concerned, once a child is no longer shown
                    // in the shade, it is essentially removed.
                    Bubble bubbleChild = mBubbleData.getAnyBubbleWithkey(child.getKey());
                    if (bubbleChild != null) {
                        final NotificationEntry entry = mNotificationEntryManager
                                .getPendingOrActiveNotif(bubbleChild.getKey());
                        if (entry != null) {
                            mNotificationGroupManager.onEntryRemoved(entry);
                        }
                        bubbleChild.setSuppressNotification(true);
                        bubbleChild.setShowDot(false /* show */);
                    }
                } else {
                    // non-bubbled children can be removed
                    for (NotifCallback cb : mCallbacks) {
                        cb.removeNotification(child, REASON_GROUP_SUMMARY_CANCELED);
                    }
                }
            }
        }

        // And since all children are removed, remove the summary.
        mNotificationGroupManager.onEntryRemoved(summary);

        // TODO: (b/145659174) remove references to mSuppressedGroupKeys once fully migrated
        mBubbleData.addSummaryToSuppress(summary.getSbn().getGroupKey(),
                summary.getKey());
    }

    /**
     * Updates the visibility of the bubbles based on current state.
     * Does not un-bubble, just hides or un-hides.
     * Updates stack description for TalkBack focus.
     */
    public void updateStack() {
        if (mStackView == null) {
            return;
        }

        if (mStatusBarStateListener.getCurrentState() != SHADE) {
            // Bubbles don't appear over the locked shade.
            mStackView.setVisibility(INVISIBLE);
        } else if (hasBubbles()) {
            // If we're unlocked, show the stack if we have bubbles. If we don't have bubbles, the
            // stack will be set to INVISIBLE in onAllBubblesAnimatedOut after the bubbles animate
            // out.
            mStackView.setVisibility(VISIBLE);
        }

        mStackView.updateContentDescription();
    }

    /**
     * The display id of the expanded view, if the stack is expanded and not occluded by the
     * status bar, otherwise returns {@link Display#INVALID_DISPLAY}.
     */
    public int getExpandedDisplayId(Context context) {
        if (mStackView == null) {
            return INVALID_DISPLAY;
        }
        final boolean defaultDisplay = context.getDisplay() != null
                && context.getDisplay().getDisplayId() == DEFAULT_DISPLAY;
        final BubbleViewProvider expandedViewProvider = mStackView.getExpandedBubble();
        if (defaultDisplay && expandedViewProvider != null && isStackExpanded()
                && !mNotificationShadeWindowController.getPanelExpanded()) {
            return expandedViewProvider.getDisplayId();
        }
        return INVALID_DISPLAY;
    }

    @VisibleForTesting
    BubbleStackView getStackView() {
        return mStackView;
    }

    /**
     * Description of current bubble state.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("BubbleController state:");
        mBubbleData.dump(fd, pw, args);
        pw.println();
        if (mStackView != null) {
            mStackView.dump(fd, pw, args);
        }
        pw.println();
    }

    /**
     * This task stack listener is responsible for responding to tasks moved to the front
     * which are on the default (main) display. When this happens, expanded bubbles must be
     * collapsed so the user may interact with the app which was just moved to the front.
     * <p>
     * This listener is registered with SystemUI's ActivityManagerWrapper which dispatches
     * these calls via a main thread Handler.
     */
    @MainThread
    private class BubbleTaskStackListener extends TaskStackChangeListener {

        @Override
        public void onTaskMovedToFront(RunningTaskInfo taskInfo) {
            if (mStackView != null && taskInfo.displayId == Display.DEFAULT_DISPLAY) {
                if (!mStackView.isExpansionAnimating()) {
                    mBubbleData.setExpanded(false);
                }
            }
        }

        @Override
        public void onActivityRestartAttempt(RunningTaskInfo task, boolean homeTaskVisible,
                boolean clearedTask, boolean wasVisible) {
            for (Bubble b : mBubbleData.getBubbles()) {
                if (b.getDisplayId() == task.displayId) {
                    mBubbleData.setSelectedBubble(b);
                    mBubbleData.setExpanded(true);
                    return;
                }
            }
        }

        @Override
        public void onActivityLaunchOnSecondaryDisplayRerouted() {
            if (mStackView != null) {
                mBubbleData.setExpanded(false);
            }
        }

        @Override
        public void onBackPressedOnTaskRoot(RunningTaskInfo taskInfo) {
            if (mStackView != null && taskInfo.displayId == getExpandedDisplayId(mContext)) {
                if (mImeVisible) {
                    hideCurrentInputMethod();
                } else {
                    mBubbleData.setExpanded(false);
                }
            }
        }

        @Override
        public void onSingleTaskDisplayDrawn(int displayId) {
            if (mStackView == null) {
                return;
            }
            mStackView.showExpandedViewContents(displayId);
        }

        @Override
        public void onSingleTaskDisplayEmpty(int displayId) {
            final BubbleViewProvider expandedBubble = mStackView != null
                    ? mStackView.getExpandedBubble()
                    : null;
            int expandedId = expandedBubble != null ? expandedBubble.getDisplayId() : -1;
            if (mStackView != null && mStackView.isExpanded() && expandedId == displayId) {
                mBubbleData.setExpanded(false);
            }
            mBubbleData.notifyDisplayEmpty(displayId);
        }
    }

    /**
     * Whether an intent is properly configured to display in an {@link android.app.ActivityView}.
     *
     * Keep checks in sync with NotificationManagerService#canLaunchInActivityView. Typically
     * that should filter out any invalid bubbles, but should protect SysUI side just in case.
     *
     * @param context the context to use.
     * @param entry the entry to bubble.
     */
    static boolean canLaunchInActivityView(Context context, NotificationEntry entry) {
        PendingIntent intent = entry.getBubbleMetadata() != null
                ? entry.getBubbleMetadata().getIntent()
                : null;
        if (entry.getBubbleMetadata() != null
                && entry.getBubbleMetadata().getShortcutId() != null) {
            return true;
        }
        if (intent == null) {
            Log.w(TAG, "Unable to create bubble -- no intent: " + entry.getKey());
            return false;
        }
        PackageManager packageManager = StatusBar.getPackageManagerForUser(
                context, entry.getSbn().getUser().getIdentifier());
        ActivityInfo info =
                intent.getIntent().resolveActivityInfo(packageManager, 0);
        if (info == null) {
            Log.w(TAG, "Unable to send as bubble, "
                    + entry.getKey() + " couldn't find activity info for intent: "
                    + intent);
            return false;
        }
        if (!ActivityInfo.isResizeableMode(info.resizeMode)) {
            Log.w(TAG, "Unable to send as bubble, "
                    + entry.getKey() + " activity is not resizable for intent: "
                    + intent);
            return false;
        }
        return true;
    }

    /** PinnedStackListener that dispatches IME visibility updates to the stack. */
    private class BubblesImeListener extends PinnedStackListenerForwarder.PinnedStackListener {
        @Override
        public void onImeVisibilityChanged(boolean imeVisible, int imeHeight) {
            if (mStackView != null) {
                mStackView.post(() -> mStackView.onImeVisibilityChanged(imeVisible, imeHeight));
            }
        }
    }
}
