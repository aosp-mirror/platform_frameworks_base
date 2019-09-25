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
import static android.content.pm.ActivityInfo.DOCUMENT_LAUNCH_ALWAYS;
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
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
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
import com.android.systemui.Dependency;
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
import com.android.systemui.statusbar.notification.collection.NotificationData;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.phone.StatusBarWindowController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ZenModeController;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;

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

    private BubbleData mBubbleData;
    @Nullable private BubbleStackView mStackView;

    // Tracks the id of the current (foreground) user.
    private int mCurrentUserId;
    // Saves notification keys of active bubbles when users are switched.
    private final SparseSetArray<String> mSavedBubbleKeysPerUser;

    // Bubbles get added to the status bar view
    private final StatusBarWindowController mStatusBarWindowController;
    private final ZenModeController mZenModeController;
    private StatusBarStateListener mStatusBarStateListener;

    private final NotificationInterruptionStateProvider mNotificationInterruptionStateProvider;
    private IStatusBarService mBarService;

    // Used for determining view rect for touch interaction
    private Rect mTempRect = new Rect();

    // Listens to user switch so bubbles can be saved and restored.
    private final NotificationLockscreenUserManager mNotifUserManager;

    /** Last known orientation, used to detect orientation changes in {@link #onConfigChanged}. */
    private int mOrientation = Configuration.ORIENTATION_UNDEFINED;

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
    public BubbleController(Context context, StatusBarWindowController statusBarWindowController,
            BubbleData data, ConfigurationController configurationController,
            NotificationInterruptionStateProvider interruptionStateProvider,
            ZenModeController zenModeController,
            NotificationLockscreenUserManager notifUserManager,
            NotificationGroupManager groupManager) {
        this(context, statusBarWindowController, data, null /* synchronizer */,
                configurationController, interruptionStateProvider, zenModeController,
                notifUserManager, groupManager);
    }

    public BubbleController(Context context, StatusBarWindowController statusBarWindowController,
            BubbleData data, @Nullable BubbleStackView.SurfaceSynchronizer synchronizer,
            ConfigurationController configurationController,
            NotificationInterruptionStateProvider interruptionStateProvider,
            ZenModeController zenModeController,
            NotificationLockscreenUserManager notifUserManager,
            NotificationGroupManager groupManager) {
        mContext = context;
        mNotificationInterruptionStateProvider = interruptionStateProvider;
        mNotifUserManager = notifUserManager;
        mZenModeController = zenModeController;
        mZenModeController.addCallback(new ZenModeController.Callback() {
            @Override
            public void onZenChanged(int zen) {
                if (mStackView != null) {
                    mStackView.updateDots();
                }
            }

            @Override
            public void onConfigChanged(ZenModeConfig config) {
                if (mStackView != null) {
                    mStackView.updateDots();
                }
            }
        });

        configurationController.addCallback(this /* configurationListener */);

        mBubbleData = data;
        mBubbleData.setListener(mBubbleDataListener);

        mNotificationEntryManager = Dependency.get(NotificationEntryManager.class);
        mNotificationEntryManager.addNotificationEntryListener(mEntryListener);
        mNotificationEntryManager.setNotificationRemoveInterceptor(mRemoveInterceptor);
        mNotificationGroupManager = groupManager;
        mNotificationGroupManager.addOnGroupChangeListener(
                new NotificationGroupManager.OnGroupChangeListener() {
                    @Override
                    public void onGroupSuppressionChanged(
                            NotificationGroupManager.NotificationGroup group,
                            boolean suppressed) {
                        // More notifications could be added causing summary to no longer
                        // be suppressed -- in this case need to remove the key.
                        final String groupKey = group.summary != null
                                ? group.summary.notification.getGroupKey()
                                : null;
                        if (!suppressed && groupKey != null
                                && mBubbleData.isSummarySuppressed(groupKey)) {
                            mBubbleData.removeSuppressedSummary(groupKey);
                        }
                    }
                });

        mStatusBarWindowController = statusBarWindowController;
        mStatusBarStateListener = new StatusBarStateListener();
        Dependency.get(StatusBarStateController.class).addCallback(mStatusBarStateListener);

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
                newUserId -> {
                    saveBubbles(mCurrentUserId);
                    mBubbleData.dismissAll(DISMISS_USER_CHANGED);
                    restoreBubbles(newUserId);
                    mCurrentUserId = newUserId;
                });
    }

    /**
     * BubbleStackView is lazily created by this method the first time a Bubble is added. This
     * method initializes the stack view and adds it to the StatusBar just above the scrim.
     */
    private void ensureStackViewCreated() {
        if (mStackView == null) {
            mStackView = new BubbleStackView(mContext, mBubbleData, mSurfaceSynchronizer);
            ViewGroup sbv = mStatusBarWindowController.getStatusBarView();
            int bubbleScrimIndex = sbv.indexOfChild(sbv.findViewById(R.id.scrim_for_bubble));
            int stackIndex = bubbleScrimIndex + 1;  // Show stack above bubble scrim.
            sbv.addView(mStackView, stackIndex,
                    new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
            if (mExpandListener != null) {
                mStackView.setExpandListener(mExpandListener);
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
        NotificationData notificationData =
                mNotificationEntryManager.getNotificationData();
        ArraySet<String> savedBubbleKeys = mSavedBubbleKeysPerUser.get(userId);
        if (savedBubbleKeys == null) {
            // There were no bubbles saved for this used.
            return;
        }
        for (NotificationEntry e : notificationData.getNotificationsForCurrentUser()) {
            if (savedBubbleKeys.contains(e.key)
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
        if (mStackView != null) {
            mStackView.onThemeChanged();
        }
    }

    @Override
    public void onOverlayChanged() {
        if (mStackView != null) {
            mStackView.onThemeChanged();
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
            mStatusBarWindowController.setBubbleExpanded(isExpanding);
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
    public boolean isBubbleNotificationSuppressedFromShade(String key) {
        boolean isBubbleAndSuppressed = mBubbleData.hasBubbleWithKey(key)
                && !mBubbleData.getBubbleWithKey(key).showInShadeWhenBubble();
        NotificationEntry entry = mNotificationEntryManager.getNotificationData().get(key);
        String groupKey = entry != null ? entry.notification.getGroupKey() : null;
        boolean isSuppressedSummary = mBubbleData.isSummarySuppressed(groupKey);
        boolean isSummary = key.equals(mBubbleData.getSummaryKey(groupKey));
        return (isSummary && isSuppressedSummary) || isBubbleAndSuppressed;
    }

    void selectBubble(Bubble bubble) {
        mBubbleData.setSelectedBubble(bubble);
    }

    @VisibleForTesting
    void selectBubble(String key) {
        Bubble bubble = mBubbleData.getBubbleWithKey(key);
        selectBubble(bubble);
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
        updateBubble(notif, /* supressFlyout */ false);
    }

    void updateBubble(NotificationEntry notif, boolean suppressFlyout) {
        // If this is an interruptive notif, mark that it's interrupted
        if (notif.getImportance() >= NotificationManager.IMPORTANCE_HIGH) {
            notif.setInterruption();
        }
        mBubbleData.notificationEntryUpdated(notif, suppressFlyout);
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

    @SuppressWarnings("FieldCanBeLocal")
    private final NotificationRemoveInterceptor mRemoveInterceptor =
            new NotificationRemoveInterceptor() {
            @Override
            public boolean onNotificationRemoveRequested(String key, int reason) {
                NotificationEntry entry = mNotificationEntryManager.getNotificationData().get(key);
                String groupKey = entry != null ? entry.notification.getGroupKey() : null;
                ArrayList<Bubble> bubbleChildren = mBubbleData.getBubblesInGroup(groupKey);

                boolean inBubbleData = mBubbleData.hasBubbleWithKey(key);
                boolean isSuppressedSummary = (mBubbleData.isSummarySuppressed(groupKey)
                        && mBubbleData.getSummaryKey(groupKey).equals(key));
                boolean isSummary = entry != null
                        && entry.notification.getNotification().isGroupSummary();
                boolean isSummaryOfBubbles = (isSuppressedSummary || isSummary)
                        && bubbleChildren != null && !bubbleChildren.isEmpty();

                if (!inBubbleData && !isSummaryOfBubbles) {
                    return false;
                }

                final boolean isClearAll = reason == REASON_CANCEL_ALL;
                final boolean isUserDimiss = reason == REASON_CANCEL || reason == REASON_CLICK;
                final boolean isAppCancel = reason == REASON_APP_CANCEL
                        || reason == REASON_APP_CANCEL_ALL;
                final boolean isSummaryCancel = reason == REASON_GROUP_SUMMARY_CANCELED;

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
                    bubble.setShowInShadeWhenBubble(false);
                    bubble.setShowBubbleDot(false);
                    if (mStackView != null) {
                        mStackView.updateDotVisibility(entry.key);
                    }
                    mNotificationEntryManager.updateNotifications();
                    return true;
                } else if (!userRemovedNotif && entry != null) {
                    // This wasn't a user removal so we should remove the bubble as well
                    mBubbleData.notificationEntryRemoved(entry, DISMISS_NOTIF_CANCEL);
                    return false;
                }
                return false;
            }
        };

    private boolean handleSummaryRemovalInterception(NotificationEntry summary,
            boolean userRemovedNotif) {
        String groupKey = summary.notification.getGroupKey();
        ArrayList<Bubble> bubbleChildren = mBubbleData.getBubblesInGroup(groupKey);

        if (userRemovedNotif) {
            // If it's a user dismiss we mark the children to be hidden from the shade.
            for (int i = 0; i < bubbleChildren.size(); i++) {
                Bubble bubbleChild = bubbleChildren.get(i);
                // As far as group manager is concerned, once a child is no longer shown
                // in the shade, it is essentially removed.
                mNotificationGroupManager.onEntryRemoved(bubbleChild.getEntry());
                bubbleChild.setShowInShadeWhenBubble(false);
                bubbleChild.setShowBubbleDot(false);
                if (mStackView != null) {
                    mStackView.updateDotVisibility(bubbleChild.getKey());
                }
            }
            // And since all children are removed, remove the summary.
            mNotificationGroupManager.onEntryRemoved(summary);

            // If the summary was auto-generated we don't need to keep that notification around
            // because apps can't cancel it; so we only intercept & suppress real summaries.
            boolean isAutogroupSummary = (summary.notification.getNotification().flags
                    & FLAG_AUTOGROUP_SUMMARY) != 0;
            if (!isAutogroupSummary) {
                mBubbleData.addSummaryToSuppress(summary.notification.getGroupKey(),
                        summary.key);
                // Tell shade to update for the suppression
                mNotificationEntryManager.updateNotifications();
            }
            return !isAutogroupSummary;
        } else {
            // If it's not a user dismiss it's a cancel.
            mBubbleData.removeSuppressedSummary(groupKey);

            // Remove any associated bubble children.
            for (int i = 0; i < bubbleChildren.size(); i++) {
                Bubble bubbleChild = bubbleChildren.get(i);
                mBubbleData.notificationEntryRemoved(bubbleChild.getEntry(),
                        DISMISS_GROUP_CANCELLED);
            }
            return false;
        }
    }

    @SuppressWarnings("FieldCanBeLocal")
    private final NotificationEntryListener mEntryListener = new NotificationEntryListener() {
        @Override
        public void onPendingEntryAdded(NotificationEntry entry) {
            if (mNotificationInterruptionStateProvider.shouldBubbleUp(entry)
                    && canLaunchInActivityView(mContext, entry)) {
                updateBubble(entry);
            }
        }

        @Override
        public void onPreEntryUpdated(NotificationEntry entry) {
            boolean shouldBubble = mNotificationInterruptionStateProvider.shouldBubbleUp(entry)
                    && canLaunchInActivityView(mContext, entry);
            if (!shouldBubble && mBubbleData.hasBubbleWithKey(entry.key)) {
                // It was previously a bubble but no longer a bubble -- lets remove it
                removeBubble(entry.key, DISMISS_NO_LONGER_BUBBLE);
            } else if (shouldBubble) {
                Bubble b = mBubbleData.getBubbleWithKey(entry.key);
                updateBubble(entry);
            }
        }

        @Override
        public void onNotificationRankingUpdated(RankingMap rankingMap) {
            // Forward to BubbleData to block any bubbles which should no longer be shown
            mBubbleData.notificationRankingUpdated(rankingMap);
        }
    };

    @SuppressWarnings("FieldCanBeLocal")
    private final BubbleData.Listener mBubbleDataListener = new BubbleData.Listener() {

        @Override
        public void applyUpdate(BubbleData.Update update) {
            if (mStackView == null && update.addedBubble != null) {
                // Lazy init stack view when the first bubble is added.
                ensureStackViewCreated();
            }

            // If not yet initialized, ignore all other changes.
            if (mStackView == null) {
                return;
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
                            && !bubble.showInShadeWhenBubble()) {
                        // The bubble is gone & the notification is gone, time to actually remove it
                        mNotificationEntryManager.performRemoveNotification(
                                bubble.getEntry().notification, UNDEFINED_DISMISS_REASON);
                    } else {
                        // Update the flag for SysUI
                        bubble.getEntry().notification.getNotification().flags &= ~FLAG_BUBBLE;

                        // Make sure NoMan knows it's not a bubble anymore so anyone querying it
                        // will get right result back
                        try {
                            mBarService.onNotificationBubbleChanged(bubble.getKey(),
                                    false /* isBubble */);
                        } catch (RemoteException e) {
                            // Bad things have happened
                        }
                    }

                    // Check if removed bubble has an associated suppressed group summary that needs
                    // to be removed now.
                    final String groupKey = bubble.getEntry().notification.getGroupKey();
                    if (mBubbleData.isSummarySuppressed(groupKey)
                            && mBubbleData.getBubblesInGroup(groupKey).isEmpty()) {
                        // Time to actually remove the summary.
                        String notifKey = mBubbleData.getSummaryKey(groupKey);
                        mBubbleData.removeSuppressedSummary(groupKey);
                        NotificationEntry entry =
                                mNotificationEntryManager.getNotificationData().get(notifKey);
                        mNotificationEntryManager.performRemoveNotification(
                                entry.notification, UNDEFINED_DISMISS_REASON);
                    }

                    // Check if summary should be removed from NoManGroup
                    NotificationEntry summary = mNotificationGroupManager.getLogicalGroupSummary(
                            bubble.getEntry().notification);
                    if (summary != null) {
                        ArrayList<NotificationEntry> summaryChildren =
                                mNotificationGroupManager.getLogicalChildren(summary.notification);
                        boolean isSummaryThisNotif = summary.key.equals(bubble.getEntry().key);
                        if (!isSummaryThisNotif
                                && (summaryChildren == null || summaryChildren.isEmpty())) {
                            mNotificationEntryManager.performRemoveNotification(
                                    summary.notification, UNDEFINED_DISMISS_REASON);
                        }
                    }
                }
            }

            if (update.updatedBubble != null) {
                mStackView.updateBubble(update.updatedBubble);
            }

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

            mNotificationEntryManager.updateNotifications();
            updateStack();

            if (DEBUG_BUBBLE_CONTROLLER) {
                Log.d(TAG, "[BubbleData]");
                Log.d(TAG, formatBubblesString(mBubbleData.getBubbles(),
                        mBubbleData.getSelectedBubble()));

                if (mStackView != null) {
                    Log.d(TAG, "[BubbleStackView]");
                    Log.d(TAG, formatBubblesString(mStackView.getBubblesOnScreen(),
                            mStackView.getExpandedBubble()));
                }
            }
        }
    };

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
        boolean hadBubbles = mStatusBarWindowController.getBubblesShowing();
        boolean hasBubblesShowing = hasBubbles() && mStackView.getVisibility() == VISIBLE;
        mStatusBarWindowController.setBubblesShowing(hasBubblesShowing);
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
                && !mStatusBarWindowController.getPanelExpanded()) {
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

    static String formatBubblesString(List<Bubble> bubbles, Bubble selected) {
        StringBuilder sb = new StringBuilder();
        for (Bubble bubble : bubbles) {
            if (bubble == null) {
                sb.append("   <null> !!!!!\n");
            } else {
                boolean isSelected = (bubble == selected);
                sb.append(String.format("%s Bubble{act=%12d, ongoing=%d, key=%s}\n",
                        ((isSelected) ? "->" : "  "),
                        bubble.getLastActivity(),
                        (bubble.isOngoing() ? 1 : 0),
                        bubble.getKey()));
            }
        }
        return sb.toString();
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
            final Bubble expandedBubble = mStackView != null
                    ? mStackView.getExpandedBubble()
                    : null;
            if (expandedBubble != null && expandedBubble.getDisplayId() == displayId) {
                expandedBubble.setContentVisibility(true);
            }
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
                ? entry.getBubbleMetadata().getIntent()
                : null;
        if (intent == null) {
            Log.w(TAG, "Unable to create bubble -- no intent");
            return false;
        }
        ActivityInfo info =
                intent.getIntent().resolveActivityInfo(context.getPackageManager(), 0);
        if (info == null) {
            Log.w(TAG, "Unable to send as bubble -- couldn't find activity info for intent: "
                    + intent);
            return false;
        }
        if (!ActivityInfo.isResizeableMode(info.resizeMode)) {
            Log.w(TAG, "Unable to send as bubble -- activity is not resizable for intent: "
                    + intent);
            return false;
        }
        if (info.documentLaunchMode != DOCUMENT_LAUNCH_ALWAYS) {
            Log.w(TAG, "Unable to send as bubble -- activity is not documentLaunchMode=always "
                    + "for intent: " + intent);
            return false;
        }
        if ((info.flags & ActivityInfo.FLAG_ALLOW_EMBEDDED) == 0) {
            Log.w(TAG, "Unable to send as bubble -- activity is not embeddable for intent: "
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
}
