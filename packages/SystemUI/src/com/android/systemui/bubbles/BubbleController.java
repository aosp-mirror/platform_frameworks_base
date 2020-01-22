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

import static android.app.Notification.FLAG_AUTOGROUP_SUMMARY;
import static android.app.Notification.FLAG_BUBBLE;
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
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import static com.android.systemui.bubbles.BubbleDebugConfig.DEBUG_BUBBLE_CONTROLLER;
import static com.android.systemui.bubbles.BubbleDebugConfig.DEBUG_EXPERIMENTS;
import static com.android.systemui.bubbles.BubbleDebugConfig.TAG_BUBBLES;
import static com.android.systemui.bubbles.BubbleDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.systemui.statusbar.StatusBarState.SHADE;
import static com.android.systemui.statusbar.notification.NotificationEntryManager.UNDEFINED_DISMISS_REASON;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.LOCAL_VARIABLE;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.UserIdInt;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutManager;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.ZenModeConfig;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.util.SparseSetArray;
import android.view.Display;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.IntDef;
import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.util.ScreenshotHelper;
import com.android.systemui.R;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.PinnedStackListenerForwarder;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.WindowManagerWrapper;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationRemoveInterceptor;
import com.android.systemui.statusbar.notification.NotificationEntryListener;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.NotificationInterruptionStateProvider;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.phone.NotificationShadeWindowController;
import com.android.systemui.statusbar.phone.ShadeController;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.RemoteInputUriController;
import com.android.systemui.statusbar.policy.ZenModeController;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Bubbles are a special type of content that can "float" on top of other apps or System UI.
 * Bubbles can be expanded to show more content.
 *
 * The controller manages addition, removal, and visible state of bubbles on screen.
 */
@Singleton
public class BubbleController implements ConfigurationController.ConfigurationListener {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "BubbleController" : TAG_BUBBLES;

    @Retention(SOURCE)
    @IntDef({DISMISS_USER_GESTURE, DISMISS_AGED, DISMISS_TASK_FINISHED, DISMISS_BLOCKED,
            DISMISS_NOTIF_CANCEL, DISMISS_ACCESSIBILITY_ACTION, DISMISS_NO_LONGER_BUBBLE,
            DISMISS_USER_CHANGED, DISMISS_GROUP_CANCELLED, DISMISS_INVALID_INTENT})
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

    private final Context mContext;
    private final NotificationEntryManager mNotificationEntryManager;
    private final BubbleTaskStackListener mTaskStackListener;
    private BubbleStateChangeListener mStateChangeListener;
    private BubbleExpandListener mExpandListener;
    @Nullable private BubbleStackView.SurfaceSynchronizer mSurfaceSynchronizer;
    private final NotificationGroupManager mNotificationGroupManager;
    private final ShadeController mShadeController;
    private final RemoteInputUriController mRemoteInputUriController;
    private Handler mHandler = new Handler() {};

    private BubbleData mBubbleData;
    @Nullable private BubbleStackView mStackView;
    private BubbleIconFactory mBubbleIconFactory;
    private int mMaxBubbles;

    // Tracks the id of the current (foreground) user.
    private int mCurrentUserId;
    // Saves notification keys of active bubbles when users are switched.
    private final SparseSetArray<String> mSavedBubbleKeysPerUser;

    // Saves notification keys of user created "fake" bubbles so that we can allow notifications
    // like these to bubble by default. Doesn't persist across reboots, not a long-term solution.
    private final HashSet<String> mUserCreatedBubbles;
    // If we're auto-bubbling bubbles via a whitelist, we need to track which notifs from that app
    // have been "demoted" back to a notification so that we don't auto-bubbles those again.
    // Doesn't persist across reboots, not a long-term solution.
    private final HashSet<String> mUserBlockedBubbles;

    // Bubbles get added to the status bar view
    private final NotificationShadeWindowController mNotificationShadeWindowController;
    private final ZenModeController mZenModeController;
    private StatusBarStateListener mStatusBarStateListener;
    private final ScreenshotHelper mScreenshotHelper;

    // Callback that updates BubbleOverflowActivity on data change.
    @Nullable private Runnable mOverflowCallback = null;

    private final NotificationInterruptionStateProvider mNotificationInterruptionStateProvider;
    private IStatusBarService mBarService;

    // Used for determining view rect for touch interaction
    private Rect mTempRect = new Rect();

    // Listens to user switch so bubbles can be saved and restored.
    private final NotificationLockscreenUserManager mNotifUserManager;

    /** Last known orientation, used to detect orientation changes in {@link #onConfigChanged}. */
    private int mOrientation = Configuration.ORIENTATION_UNDEFINED;

    private boolean mInflateSynchronously;

    // TODO (b/145659174): allow for multiple callbacks to support the "shadow" new notif pipeline
    private final List<NotifCallback> mCallbacks = new ArrayList<>();

    /**
     * Listener to be notified when some states of the bubbles change.
     */
    public interface BubbleStateChangeListener {
        /**
         * Called when the stack has bubbles or no longer has bubbles.
         */
        void onHasBubblesChanged(boolean hasBubbles);
    }

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
     * Listener for handling bubble screenshot events.
     */
    public interface BubbleScreenshotListener {
        /**
         * Called to trigger taking a screenshot and sending the result to a bubble.
         */
        void onBubbleScreenshot(Bubble bubble);
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
     * Callback for when the BubbleController wants to interact with the notification pipeline to:
     * - Remove a previously bubbled notification
     * - Update the notification shade since bubbled notification should/shouldn't be showing
     */
    public interface NotifCallback {
        /**
         * Called when the BubbleController wants to remove an entry that it was previously hiding
         * from the shade. See {@link BubbleController#isBubbleNotificationSuppressedFromShade}.
         */
        void removeNotification(NotificationEntry entry);

        /**
         * Called when a bubbled notification has changed whether it should be
         * filtered from the shade.
         */
        void invalidateNotificationFilter(String reason);

        /**
         * Called on a bubbled entry that has been removed when there are no longer
         * bubbled entries in its group.
         *
         * Checks whether its group has any other (non-bubbled) children. If it doesn't,
         * removes all remnants of the group's summary from the notification pipeline.
         * TODO: (b/145659174) Only old pipeline needs this - delete post-migration.
         */
        void maybeCancelSummary(NotificationEntry entry);
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
            updateStack();
        }
    }

    @Inject
    public BubbleController(Context context,
            NotificationShadeWindowController notificationShadeWindowController,
            StatusBarStateController statusBarStateController,
            ShadeController shadeController,
            BubbleData data,
            ConfigurationController configurationController,
            NotificationInterruptionStateProvider interruptionStateProvider,
            ZenModeController zenModeController,
            NotificationLockscreenUserManager notifUserManager,
            NotificationGroupManager groupManager,
            NotificationEntryManager entryManager,
            RemoteInputUriController remoteInputUriController) {
        this(context, notificationShadeWindowController, statusBarStateController, shadeController,
                data, null /* synchronizer */, configurationController, interruptionStateProvider,
                zenModeController, notifUserManager, groupManager, entryManager,
                remoteInputUriController);
    }

    public BubbleController(Context context,
            NotificationShadeWindowController notificationShadeWindowController,
            StatusBarStateController statusBarStateController,
            ShadeController shadeController,
            BubbleData data,
            @Nullable BubbleStackView.SurfaceSynchronizer synchronizer,
            ConfigurationController configurationController,
            NotificationInterruptionStateProvider interruptionStateProvider,
            ZenModeController zenModeController,
            NotificationLockscreenUserManager notifUserManager,
            NotificationGroupManager groupManager,
            NotificationEntryManager entryManager,
            RemoteInputUriController remoteInputUriController) {
        mContext = context;
        mShadeController = shadeController;
        mNotificationInterruptionStateProvider = interruptionStateProvider;
        mNotifUserManager = notifUserManager;
        mZenModeController = zenModeController;
        mRemoteInputUriController = remoteInputUriController;
        mZenModeController.addCallback(new ZenModeController.Callback() {
            @Override
            public void onZenChanged(int zen) {
                for (Bubble b : mBubbleData.getBubbles()) {
                    b.setShowDot(b.showInShade(), true /* animate */);
                }
            }

            @Override
            public void onConfigChanged(ZenModeConfig config) {
                for (Bubble b : mBubbleData.getBubbles()) {
                    b.setShowDot(b.showInShade(), true /* animate */);
                }
            }
        });

        configurationController.addCallback(this /* configurationListener */);

        mMaxBubbles = mContext.getResources().getInteger(R.integer.bubbles_max_rendered);
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

        mNotificationEntryManager = entryManager;
        mNotificationGroupManager = groupManager;
        setupNEM();

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

        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));

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

        mUserCreatedBubbles = new HashSet<>();
        mUserBlockedBubbles = new HashSet<>();

        mScreenshotHelper = new ScreenshotHelper(context);
        mBubbleIconFactory = new BubbleIconFactory(context);
    }

    /**
     * See {@link NotifCallback}.
     */
    public void addNotifCallback(NotifCallback callback) {
        mCallbacks.add(callback);
    }

    private void setupNEM() {
        mNotificationEntryManager.addNotificationEntryListener(
                new NotificationEntryListener() {
                    @Override
                    public void onNotificationAdded(NotificationEntry entry) {
                        onEntryAdded(entry);
                    }

                    @Override
                    public void onPreEntryUpdated(NotificationEntry entry) {
                        onEntryUpdated(entry);
                    }

                    @Override
                    public void onNotificationRankingUpdated(RankingMap rankingMap) {
                        onRankingUpdated(rankingMap);
                    }
                });

        mNotificationEntryManager.setNotificationRemoveInterceptor(
                new NotificationRemoveInterceptor() {
                    @Override
                    public boolean onNotificationRemoveRequested(String key, int reason) {
                        NotificationEntry entry =
                                mNotificationEntryManager.getActiveNotificationUnfiltered(key);
                        return shouldInterceptDismissal(entry, reason);
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
            public void removeNotification(NotificationEntry entry) {
                mNotificationEntryManager.performRemoveNotification(entry.getSbn(),
                        UNDEFINED_DISMISS_REASON);
            }

            @Override
            public void invalidateNotificationFilter(String reason) {
                mNotificationEntryManager.updateNotifications(reason);
            }

            @Override
            public void maybeCancelSummary(NotificationEntry entry) {
                // Check if removed bubble has an associated suppressed group summary that needs
                // to be removed now.
                final String groupKey = entry.getSbn().getGroup();
                if (mBubbleData.isSummarySuppressed(groupKey)) {
                    mBubbleData.removeSuppressedSummary(entry.getSbn().getGroupKey());

                    final NotificationEntry summary =
                            mNotificationEntryManager.getActiveNotificationUnfiltered(
                                    mBubbleData.getSummaryKey(groupKey));
                    mNotificationEntryManager.performRemoveNotification(summary.getSbn(),
                            UNDEFINED_DISMISS_REASON);
                }

                // Check if summary should be removed from NoManGroup
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
            mStackView = new BubbleStackView(mContext, mBubbleData, mSurfaceSynchronizer);
            ViewGroup nsv = mNotificationShadeWindowController.getNotificationShadeView();
            int bubbleScrimIndex = nsv.indexOfChild(nsv.findViewById(R.id.scrim_for_bubble));
            int stackIndex = bubbleScrimIndex + 1;  // Show stack above bubble scrim.
            nsv.addView(mStackView, stackIndex,
                    new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
            if (mExpandListener != null) {
                mStackView.setExpandListener(mExpandListener);
            }
            if (mBubbleScreenshotListener != null) {
                mStackView.setBubbleScreenshotListener(mBubbleScreenshotListener);
            }
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
                    && mNotificationInterruptionStateProvider.shouldBubbleUp(e)
                    && canLaunchInActivityView(mContext, e)) {
                updateBubble(e, /* suppressFlyout= */ true);
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
        for (Bubble b: mBubbleData.getBubbles()) {
            // Reload each bubble
            b.inflate(null /* callback */, mContext, mStackView, mBubbleIconFactory);
        }
    }

    @Override
    public void onConfigChanged(Configuration newConfig) {
        if (mStackView != null && newConfig != null && newConfig.orientation != mOrientation) {
            mOrientation = newConfig.orientation;
            mStackView.onOrientationChanged(newConfig.orientation);
        }
    }

    /**
     * Set a listener to be notified when some states of the bubbles change.
     */
    public void setBubbleStateChangeListener(BubbleStateChangeListener listener) {
        mStateChangeListener = listener;
    }

    /**
     * Set a listener to be notified of bubble expand events.
     */
    public void setExpandListener(BubbleExpandListener listener) {
        mExpandListener = ((isExpanding, key) -> {
            if (listener != null) {
                listener.onBubbleExpandChanged(isExpanding, key);
            }
            mNotificationShadeWindowController.setBubbleExpanded(isExpanding);
        });
        if (mStackView != null) {
            mStackView.setExpandListener(mExpandListener);
        }
    }

    /**
     * Whether or not there are bubbles present, regardless of them being visible on the
     * screen (e.g. if on AOD).
     */
    public boolean hasBubbles() {
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
     * Tell the stack of bubbles to expand.
     */
    public void expandStack() {
        mBubbleData.setExpanded(true);
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
        boolean isBubbleAndSuppressed = mBubbleData.hasBubbleWithKey(key)
                && !mBubbleData.getBubbleWithKey(key).showInShade();

        String groupKey = entry.getSbn().getGroupKey();
        boolean isSuppressedSummary = mBubbleData.isSummarySuppressed(groupKey);
        boolean isSummary = key.equals(mBubbleData.getSummaryKey(groupKey));

        return (isSummary && isSuppressedSummary) || isBubbleAndSuppressed;
    }

    @VisibleForTesting
    void selectBubble(String key) {
        Bubble bubble = mBubbleData.getBubbleWithKey(key);
        mBubbleData.setSelectedBubble(bubble);
    }

    void promoteBubbleFromOverflow(Bubble bubble) {
        mBubbleData.promoteBubbleFromOverflow(bubble);
    }

    /**
     * Request the stack expand if needed, then select the specified Bubble as current.
     *
     * @param notificationKey the notification key for the bubble to be selected
     */
    public void expandStackAndSelectBubble(String notificationKey) {
        Bubble bubble = mBubbleData.getBubbleWithKey(notificationKey);
        if (bubble != null) {
            mBubbleData.setSelectedBubble(bubble);
            mBubbleData.setExpanded(true);
        }
    }

    /**
     * Tell the stack of bubbles to be dismissed, this will remove all of the bubbles in the stack.
     */
    void dismissStack(@DismissReason int reason) {
        mBubbleData.dismissAll(reason);
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
        updateBubble(notif, false /* suppressFlyout */);
    }

    void updateBubble(NotificationEntry notif, boolean suppressFlyout) {
        updateBubble(notif, suppressFlyout, true /* showInShade */);
    }

    void updateBubble(NotificationEntry notif, boolean suppressFlyout, boolean showInShade) {
        if (mStackView == null) {
            // Lazy init stack view when a bubble is created
            ensureStackViewCreated();
        }
        // If this is an interruptive notif, mark that it's interrupted
        if (notif.getImportance() >= NotificationManager.IMPORTANCE_HIGH) {
            notif.setInterruption();
        }
        Bubble bubble = mBubbleData.getOrCreateBubble(notif);
        bubble.setInflateSynchronously(mInflateSynchronously);
        bubble.inflate(
                b -> mBubbleData.notificationEntryUpdated(b, suppressFlyout, showInShade),
                mContext, mStackView, mBubbleIconFactory);
    }

    /**
     * Called when a user has indicated that an active notification should be shown as a bubble.
     * <p>
     * This method will collapse the shade, create the bubble without a flyout or dot, and suppress
     * the notification from appearing in the shade.
     *
     * @param entry the notification to show as a bubble.
     */
    public void onUserCreatedBubbleFromNotification(NotificationEntry entry) {
        if (DEBUG_EXPERIMENTS || DEBUG_BUBBLE_CONTROLLER) {
            Log.d(TAG, "onUserCreatedBubble: " + entry.getKey());
        }
        mShadeController.collapsePanel(true);
        entry.setFlagBubble(true);
        updateBubble(entry, true /* suppressFlyout */, false /* showInShade */);
        mUserCreatedBubbles.add(entry.getKey());
        mUserBlockedBubbles.remove(entry.getKey());
    }

    /**
     * Called when a user has indicated that an active notification appearing as a bubble should
     * no longer be shown as a bubble.
     *
     * @param entry the notification to no longer show as a bubble.
     */
    public void onUserDemotedBubbleFromNotification(NotificationEntry entry) {
        if (DEBUG_EXPERIMENTS || DEBUG_BUBBLE_CONTROLLER) {
            Log.d(TAG, "onUserDemotedBubble: " + entry.getKey());
        }
        entry.setFlagBubble(false);
        removeBubble(entry.getKey(), DISMISS_BLOCKED);
        mUserCreatedBubbles.remove(entry.getKey());
        if (BubbleExperimentConfig.isPackageWhitelistedToAutoBubble(
                mContext, entry.getSbn().getPackageName())) {
            // This package is whitelist but user demoted the bubble, let's save it so we don't
            // auto-bubble for the whitelist again.
            mUserBlockedBubbles.add(entry.getKey());
        }
    }

    /**
     * Whether this bubble was explicitly created by the user via a SysUI affordance.
     */
    boolean isUserCreatedBubble(String key) {
        return mUserCreatedBubbles.contains(key);
    }

    /**
     * Removes the bubble associated with the {@param uri}.
     * <p>
     * Must be called from the main thread.
     */
    @MainThread
    void removeBubble(String key, int reason) {
        // TEMP: refactor to change this to pass entry
        Bubble bubble = mBubbleData.getBubbleWithKey(key);
        if (bubble != null) {
            mBubbleData.notificationEntryRemoved(bubble.getEntry(), reason);
        }
    }

    private void onEntryAdded(NotificationEntry entry) {
        boolean previouslyUserCreated = mUserCreatedBubbles.contains(entry.getKey());
        boolean userBlocked = mUserBlockedBubbles.contains(entry.getKey());
        boolean wasAdjusted = BubbleExperimentConfig.adjustForExperiments(
                mContext, entry, previouslyUserCreated, userBlocked);

        if (mNotificationInterruptionStateProvider.shouldBubbleUp(entry)
                && (canLaunchInActivityView(mContext, entry) || wasAdjusted)) {
            if (wasAdjusted && !previouslyUserCreated) {
                // Gotta treat the auto-bubbled / whitelisted packaged bubbles as usercreated
                mUserCreatedBubbles.add(entry.getKey());
            }
            updateBubble(entry);
        }
    }

    private void onEntryUpdated(NotificationEntry entry) {
        boolean previouslyUserCreated = mUserCreatedBubbles.contains(entry.getKey());
        boolean userBlocked = mUserBlockedBubbles.contains(entry.getKey());
        boolean wasAdjusted = BubbleExperimentConfig.adjustForExperiments(
                mContext, entry, previouslyUserCreated, userBlocked);

        boolean shouldBubble = mNotificationInterruptionStateProvider.shouldBubbleUp(entry)
                && (canLaunchInActivityView(mContext, entry) || wasAdjusted);
        if (!shouldBubble && mBubbleData.hasBubbleWithKey(entry.getKey())) {
            // It was previously a bubble but no longer a bubble -- lets remove it
            removeBubble(entry.getKey(), DISMISS_NO_LONGER_BUBBLE);
        } else if (shouldBubble) {
            if (wasAdjusted && !previouslyUserCreated) {
                // Gotta treat the auto-bubbled / whitelisted packaged bubbles as usercreated
                mUserCreatedBubbles.add(entry.getKey());
            }
            updateBubble(entry);
        }
    }

    private void onRankingUpdated(RankingMap rankingMap) {
        // Forward to BubbleData to block any bubbles which should no longer be shown
        mBubbleData.notificationRankingUpdated(rankingMap);
    }

    @SuppressWarnings("FieldCanBeLocal")
    private final BubbleData.Listener mBubbleDataListener = new BubbleData.Listener() {

        @Override
        public void applyUpdate(BubbleData.Update update) {
            // Update bubbles in overflow.
            if (mOverflowCallback != null) {
                mOverflowCallback.run();
            }

            if (update.addedBubble != null) {
                mStackView.addBubble(update.addedBubble);
            }

            // Collapsing? Do this first before remaining steps.
            if (update.expandedChanged && !update.expanded) {
                mStackView.setExpanded(false);
            }

            // Do removals, if any.
            ArrayList<Pair<Bubble, Integer>> removedBubbles =
                    new ArrayList<>(update.removedBubbles);
            for (Pair<Bubble, Integer> removed : removedBubbles) {
                final Bubble bubble = removed.first;
                @DismissReason final int reason = removed.second;
                mStackView.removeBubble(bubble);

                // If the bubble is removed for user switching, leave the notification in place.
                if (reason != DISMISS_USER_CHANGED) {
                    if (!mBubbleData.hasBubbleWithKey(bubble.getKey())
                            && !bubble.showInShade()) {
                        // The bubble is now gone & the notification is hidden from the shade, so
                        // time to actually remove it
                        for (NotifCallback cb : mCallbacks) {
                            cb.removeNotification(bubble.getEntry());
                        }
                    } else {
                        // Update the flag for SysUI
                        bubble.getEntry().getSbn().getNotification().flags &= ~FLAG_BUBBLE;

                        // Make sure NoMan knows it's not a bubble anymore so anyone querying it
                        // will get right result back
                        try {
                            mBarService.onNotificationBubbleChanged(bubble.getKey(),
                                    false /* isBubble */);
                        } catch (RemoteException e) {
                            // Bad things have happened
                        }
                    }

                    final String groupKey = bubble.getEntry().getSbn().getGroupKey();
                    if (mBubbleData.getBubblesInGroup(groupKey).isEmpty()) {
                        // Time to potentially remove the summary
                        for (NotifCallback cb : mCallbacks) {
                            cb.maybeCancelSummary(bubble.getEntry());
                        }
                    }
                }
            }

            if (update.updatedBubble != null) {
                mStackView.updateBubble(update.updatedBubble);
            }

            // At this point, the correct bubbles are inflated in the stack.
            // Make sure the order in bubble data is reflected in bubble row.
            if (update.orderChanged) {
                mStackView.updateBubbleOrder(update.bubbles);
            }

            if (update.selectionChanged) {
                mStackView.setSelectedBubble(update.selectedBubble);
                if (update.selectedBubble != null) {
                    mNotificationGroupManager.updateSuppression(
                            update.selectedBubble.getEntry());
                }
            }

            // Expanding? Apply this last.
            if (update.expandedChanged && update.expanded) {
                mStackView.setExpanded(true);
            }

            for (NotifCallback cb : mCallbacks) {
                cb.invalidateNotificationFilter("BubbleData.Listener.applyUpdate");
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
                        null));
            }
        }
    };

    /**
     * We intercept notification entries cancelled by the user (i.e. dismissed) when there is an
     * active bubble associated with it. We do this so that developers can still cancel it
     * (and hence the bubbles associated with it). However, these intercepted notifications
     * should then be hidden from the shade since the user has cancelled them, so we update
     * {@link Bubble#showInShade}.
     *
     * The cancellation of summaries with children associated with bubbles are also handled in this
     * method. User-cancelled summaries are tracked by {@link BubbleData#addSummaryToSuppress}.
     *
     * @return true if we want to intercept the dismissal of the entry, else false
     */
    public boolean shouldInterceptDismissal(NotificationEntry entry, int dismissReason) {
        if (entry == null) {
            return false;
        }
        String key = entry.getKey();
        String groupKey = entry != null ? entry.getSbn().getGroupKey() : null;
        ArrayList<Bubble> bubbleChildren = mBubbleData.getBubblesInGroup(groupKey);

        boolean inBubbleData = mBubbleData.hasBubbleWithKey(key);
        boolean isSuppressedSummary = (mBubbleData.isSummarySuppressed(groupKey)
                && mBubbleData.getSummaryKey(groupKey).equals(key));
        boolean isSummary = entry != null
                && entry.getSbn().getNotification().isGroupSummary();
        boolean isSummaryOfBubbles = (isSuppressedSummary || isSummary)
                && bubbleChildren != null && !bubbleChildren.isEmpty();

        if (!inBubbleData && !isSummaryOfBubbles) {
            return false;
        }

        final boolean isClearAll = dismissReason == REASON_CANCEL_ALL;
        final boolean isUserDimiss = dismissReason == REASON_CANCEL
                || dismissReason == REASON_CLICK;
        final boolean isAppCancel = dismissReason == REASON_APP_CANCEL
                || dismissReason == REASON_APP_CANCEL_ALL;
        final boolean isSummaryCancel = dismissReason == REASON_GROUP_SUMMARY_CANCELED;

        // Need to check for !appCancel here because the notification may have
        // previously been dismissed & entry.isRowDismissed would still be true
        boolean userRemovedNotif = (entry != null && entry.isRowDismissed() && !isAppCancel)
                || isClearAll || isUserDimiss || isSummaryCancel;
        if (isSummaryOfBubbles) {
            return handleSummaryRemovalInterception(entry, userRemovedNotif);
        }

        // The bubble notification sticks around in the data as long as the bubble is
        // not dismissed and the app hasn't cancelled the notification.
        Bubble bubble = mBubbleData.getBubbleWithKey(key);
        boolean bubbleExtended = entry != null && entry.isBubble() && userRemovedNotif;
        if (bubbleExtended) {
            bubble.setSuppressNotification(true);
            bubble.setShowDot(false /* show */, true /* animate */);
            for (NotifCallback cb : mCallbacks) {
                cb.invalidateNotificationFilter("BubbleController"
                        + ".shouldInterceptDismissal");
            }
            return true;
        } else if (!userRemovedNotif && entry != null
                && !isUserCreatedBubble(bubble.getKey())) {
            // This wasn't a user removal so we should remove the bubble as well
            mBubbleData.notificationEntryRemoved(entry, DISMISS_NOTIF_CANCEL);
            return false;
        }
        return false;
    }

    private boolean handleSummaryRemovalInterception(NotificationEntry summary,
            boolean userRemovedNotif) {
        String groupKey = summary.getSbn().getGroupKey();
        ArrayList<Bubble> bubbleChildren = mBubbleData.getBubblesInGroup(groupKey);

        if (userRemovedNotif) {
            // If it's a user dismiss we mark the children to be hidden from the shade.
            for (int i = 0; i < bubbleChildren.size(); i++) {
                Bubble bubbleChild = bubbleChildren.get(i);
                // As far as group manager is concerned, once a child is no longer shown
                // in the shade, it is essentially removed.
                mNotificationGroupManager.onEntryRemoved(bubbleChild.getEntry());
                bubbleChild.setSuppressNotification(true);
                bubbleChild.setShowDot(false /* show */, true /* animate */);
            }
            // And since all children are removed, remove the summary.
            mNotificationGroupManager.onEntryRemoved(summary);

            // If the summary was auto-generated we don't need to keep that notification around
            // because apps can't cancel it; so we only intercept & suppress real summaries.
            boolean isAutogroupSummary = (summary.getSbn().getNotification().flags
                    & FLAG_AUTOGROUP_SUMMARY) != 0;
            if (!isAutogroupSummary) {
                // TODO: (b/145659174) remove references to mSuppressedGroupKeys once fully migrated
                mBubbleData.addSummaryToSuppress(summary.getSbn().getGroupKey(),
                        summary.getKey());
                // Tell shade to update for the suppression
                mNotificationEntryManager.updateNotifications("BubbleController"
                        + ".handleSummaryRemovalInterception");
            }
            return !isAutogroupSummary;
        } else {
            // If it's not a user dismiss it's a cancel.
            for (int i = 0; i < bubbleChildren.size(); i++) {
                // First check if any of these are user-created (i.e. experimental bubbles)
                if (mUserCreatedBubbles.contains(bubbleChildren.get(i).getKey())) {
                    // Experimental bubble! Intercept the removal.
                    return true;
                }
            }

            // Not an experimental bubble, safe to remove.
            mBubbleData.removeSuppressedSummary(groupKey);
            // Remove any associated bubble children with the summary.
            for (int i = 0; i < bubbleChildren.size(); i++) {
                Bubble bubbleChild = bubbleChildren.get(i);
                mBubbleData.notificationEntryRemoved(bubbleChild.getEntry(),
                        DISMISS_GROUP_CANCELLED);
            }
            return false;
        }
    }

    /**
     * Lets any listeners know if bubble state has changed.
     * Updates the visibility of the bubbles based on current state.
     * Does not un-bubble, just hides or un-hides. Notifies any
     * {@link BubbleStateChangeListener}s of visibility changes.
     * Updates stack description for TalkBack focus.
     */
    public void updateStack() {
        if (mStackView == null) {
            return;
        }
        if (mStatusBarStateListener.getCurrentState() == SHADE && hasBubbles()) {
            // Bubbles only appear in unlocked shade
            mStackView.setVisibility(hasBubbles() ? VISIBLE : INVISIBLE);
        } else if (mStackView != null) {
            mStackView.setVisibility(INVISIBLE);
        }

        // Let listeners know if bubble state changed.
        boolean hadBubbles = mNotificationShadeWindowController.getBubblesShowing();
        boolean hasBubblesShowing = hasBubbles() && mStackView.getVisibility() == VISIBLE;
        mNotificationShadeWindowController.setBubblesShowing(hasBubblesShowing);
        if (mStateChangeListener != null && hadBubbles != hasBubblesShowing) {
            mStateChangeListener.onHasBubblesChanged(hasBubblesShowing);
        }

        mStackView.updateContentDescription();
    }

    /**
     * Rect indicating the touchable region for the bubble stack / expanded stack.
     */
    public Rect getTouchableRegion() {
        if (mStackView == null || mStackView.getVisibility() != VISIBLE) {
            return null;
        }
        mStackView.getBoundsOnScreen(mTempRect);
        return mTempRect;
    }

    /**
     * The display id of the expanded view, if the stack is expanded and not occluded by the
     * status bar, otherwise returns {@link Display#INVALID_DISPLAY}.
     */
    public int getExpandedDisplayId(Context context) {
        final Bubble bubble = getExpandedBubble(context);
        return bubble != null ? bubble.getDisplayId() : INVALID_DISPLAY;
    }

    @Nullable
    private Bubble getExpandedBubble(Context context) {
        if (mStackView == null) {
            return null;
        }
        final boolean defaultDisplay = context.getDisplay() != null
                && context.getDisplay().getDisplayId() == DEFAULT_DISPLAY;
        final Bubble expandedBubble = mStackView.getExpandedBubble();
        if (defaultDisplay && expandedBubble != null && isStackExpanded()
                && !mNotificationShadeWindowController.getPanelExpanded()) {
            return expandedBubble;
        }
        return null;
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
        public void onActivityLaunchOnSecondaryDisplayRerouted() {
            if (mStackView != null) {
                mBubbleData.setExpanded(false);
            }
        }

        @Override
        public void onBackPressedOnTaskRoot(RunningTaskInfo taskInfo) {
            if (mStackView != null && taskInfo.displayId == getExpandedDisplayId(mContext)) {
                mBubbleData.setExpanded(false);
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
            final Bubble expandedBubble = mStackView != null
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
                ? entry.getBubbleMetadata().getBubbleIntent()
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
            if (mStackView != null && mStackView.getBubbleCount() > 0) {
                mStackView.post(() -> mStackView.onImeVisibilityChanged(imeVisible, imeHeight));
            }
        }
    }

    // TODO: Copied from RemoteInputView. Consolidate RemoteInput intent logic.
    private Intent prepareRemoteInputFromData(String contentType, Uri data,
            RemoteInput remoteInput, NotificationEntry entry) {
        HashMap<String, Uri> results = new HashMap<>();
        results.put(contentType, data);
        mRemoteInputUriController.grantInlineReplyUriPermission(entry.getSbn(), data);
        Intent fillInIntent = new Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        RemoteInput.addDataResultToIntent(remoteInput, fillInIntent, results);

        return fillInIntent;
    }

    // TODO: Copied from RemoteInputView. Consolidate RemoteInput intent logic.
    private void sendRemoteInput(Intent intent, NotificationEntry entry,
            PendingIntent pendingIntent) {
        // Tell ShortcutManager that this package has been "activated".  ShortcutManager
        // will reset the throttling for this package.
        // Strictly speaking, the intent receiver may be different from the notification publisher,
        // but that's an edge case, and also because we can't always know which package will receive
        // an intent, so we just reset for the publisher.
        mContext.getSystemService(ShortcutManager.class).onApplicationActive(
                entry.getSbn().getPackageName(),
                entry.getSbn().getUser().getIdentifier());

        try {
            pendingIntent.send(mContext, 0, intent);
        } catch (PendingIntent.CanceledException e) {
            Log.i(TAG, "Unable to send remote input result", e);
        }
    }

    private void sendScreenshotToBubble(Bubble bubble) {
        mScreenshotHelper.takeScreenshot(
                android.view.WindowManager.TAKE_SCREENSHOT_FULLSCREEN,
                true /* hasStatus */,
                true /* hasNav */,
                mHandler,
                new Consumer<Uri>() {
                    @Override
                    public void accept(Uri uri) {
                        if (uri != null) {
                            NotificationEntry entry = bubble.getEntry();
                            Pair<RemoteInput, Notification.Action> pair = entry.getSbn()
                                    .getNotification().findRemoteInputActionPair(false);
                            if (pair != null) {
                                RemoteInput remoteInput = pair.first;
                                Notification.Action action = pair.second;
                                Intent dataIntent = prepareRemoteInputFromData("image/png", uri,
                                        remoteInput, entry);
                                sendRemoteInput(dataIntent, entry, action.actionIntent);
                                mBubbleData.setSelectedBubble(bubble);
                                mBubbleData.setExpanded(true);
                            } else {
                                Log.w(TAG, "No RemoteInput found for notification: "
                                        + entry.getSbn().getKey());
                            }
                        }
                    }
                });
    }

    private final BubbleScreenshotListener mBubbleScreenshotListener =
            bubble -> sendScreenshotToBubble(bubble);
}
