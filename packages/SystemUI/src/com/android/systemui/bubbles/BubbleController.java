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
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_BADGE;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK;
import static android.content.pm.ActivityInfo.DOCUMENT_LAUNCH_ALWAYS;
import static android.service.notification.NotificationListenerService.REASON_APP_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_APP_CANCEL_ALL;
import static android.service.notification.NotificationListenerService.REASON_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_CANCEL_ALL;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import static com.android.systemui.statusbar.StatusBarState.SHADE;
import static com.android.systemui.statusbar.notification.NotificationEntryManager.UNDEFINED_DISMISS_REASON;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.LOCAL_VARIABLE;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ParceledListSlice;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import android.service.notification.ZenModeConfig;
import android.util.Log;
import android.util.Pair;
import android.view.Display;
import android.view.IPinnedStackController;
import android.view.IPinnedStackListener;
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
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.WindowManagerWrapper;
import com.android.systemui.statusbar.NotificationRemoveInterceptor;
import com.android.systemui.statusbar.notification.NotificationEntryListener;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.NotificationInterruptionStateProvider;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.NotificationContentInflater.InflationFlag;
import com.android.systemui.statusbar.phone.StatusBarWindowController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ZenModeController;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
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

    private static final String TAG = "BubbleController";
    private static final boolean DEBUG = false;

    @Retention(SOURCE)
    @IntDef({DISMISS_USER_GESTURE, DISMISS_AGED, DISMISS_TASK_FINISHED, DISMISS_BLOCKED,
            DISMISS_NOTIF_CANCEL, DISMISS_ACCESSIBILITY_ACTION, DISMISS_NO_LONGER_BUBBLE})
    @Target({FIELD, LOCAL_VARIABLE, PARAMETER})
    @interface DismissReason {}

    static final int DISMISS_USER_GESTURE = 1;
    static final int DISMISS_AGED = 2;
    static final int DISMISS_TASK_FINISHED = 3;
    static final int DISMISS_BLOCKED = 4;
    static final int DISMISS_NOTIF_CANCEL = 5;
    static final int DISMISS_ACCESSIBILITY_ACTION = 6;
    static final int DISMISS_NO_LONGER_BUBBLE = 7;

    public static final int MAX_BUBBLES = 5; // TODO: actually enforce this

    // Enables some subset of notifs to automatically become bubbles
    public static final boolean DEBUG_ENABLE_AUTO_BUBBLE = false;

    /** Flag to enable or disable the entire feature */
    private static final String ENABLE_BUBBLES = "experiment_enable_bubbles";
    /** Auto bubble flags set whether different notif types should be presented as a bubble */
    private static final String ENABLE_AUTO_BUBBLE_MESSAGES = "experiment_autobubble_messaging";
    private static final String ENABLE_AUTO_BUBBLE_ONGOING = "experiment_autobubble_ongoing";
    private static final String ENABLE_AUTO_BUBBLE_ALL = "experiment_autobubble_all";

    /** Use an activityView for an auto-bubbled notifs if it has an appropriate content intent */
    private static final String ENABLE_BUBBLE_CONTENT_INTENT = "experiment_bubble_content_intent";

    private static final String BUBBLE_STIFFNESS = "experiment_bubble_stiffness";
    private static final String BUBBLE_BOUNCINESS = "experiment_bubble_bounciness";

    private final Context mContext;
    private final NotificationEntryManager mNotificationEntryManager;
    private final BubbleTaskStackListener mTaskStackListener;
    private BubbleStateChangeListener mStateChangeListener;
    private BubbleExpandListener mExpandListener;
    @Nullable private BubbleStackView.SurfaceSynchronizer mSurfaceSynchronizer;

    private BubbleData mBubbleData;
    @Nullable private BubbleStackView mStackView;

    // Bubbles get added to the status bar view
    private final StatusBarWindowController mStatusBarWindowController;
    private final ZenModeController mZenModeController;
    private StatusBarStateListener mStatusBarStateListener;

    private final NotificationInterruptionStateProvider mNotificationInterruptionStateProvider;
    private IStatusBarService mBarService;

    // Used for determining view rect for touch interaction
    private Rect mTempRect = new Rect();

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
            ZenModeController zenModeController) {
        this(context, statusBarWindowController, data, null /* synchronizer */,
                configurationController, interruptionStateProvider, zenModeController);
    }

    public BubbleController(Context context, StatusBarWindowController statusBarWindowController,
            BubbleData data, @Nullable BubbleStackView.SurfaceSynchronizer synchronizer,
            ConfigurationController configurationController,
            NotificationInterruptionStateProvider interruptionStateProvider,
            ZenModeController zenModeController) {
        mContext = context;
        mNotificationInterruptionStateProvider = interruptionStateProvider;
        mZenModeController = zenModeController;
        mZenModeController.addCallback(new ZenModeController.Callback() {
            @Override
            public void onZenChanged(int zen) {
                updateStackViewForZenConfig();
            }

            @Override
            public void onConfigChanged(ZenModeConfig config) {
                updateStackViewForZenConfig();
            }
        });

        configurationController.addCallback(this /* configurationListener */);

        mBubbleData = data;
        mBubbleData.setListener(mBubbleDataListener);

        mNotificationEntryManager = Dependency.get(NotificationEntryManager.class);
        mNotificationEntryManager.addNotificationEntryListener(mEntryListener);
        mNotificationEntryManager.setNotificationRemoveInterceptor(mRemoveInterceptor);

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
    }

    /**
     * BubbleStackView is lazily created by this method the first time a Bubble is added. This
     * method initializes the stack view and adds it to the StatusBar just above the scrim.
     */
    private void ensureStackViewCreated() {
        if (mStackView == null) {
            mStackView = new BubbleStackView(mContext, mBubbleData, mSurfaceSynchronizer);
            ViewGroup sbv = mStatusBarWindowController.getStatusBarView();
            // TODO(b/130237686): When you expand the shade on top of expanded bubble, there is no
            //  scrim between bubble and the shade
            int bubblePosition = sbv.indexOfChild(sbv.findViewById(R.id.scrim_behind)) + 1;
            sbv.addView(mStackView, bubblePosition,
                    new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
            if (mExpandListener != null) {
                mStackView.setExpandListener(mExpandListener);
            }

            updateStackViewForZenConfig();
        }
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
            mStackView.onOrientationChanged();
            mOrientation = newConfig.orientation;
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
        // If this is an interruptive notif, mark that it's interrupted
        if (notif.importance >= NotificationManager.IMPORTANCE_HIGH) {
            notif.setInterruption();
        }
        mBubbleData.notificationEntryUpdated(notif);
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
            mBubbleData.notificationEntryRemoved(bubble.entry, reason);
        }
    }

    @SuppressWarnings("FieldCanBeLocal")
    private final NotificationRemoveInterceptor mRemoveInterceptor =
            new NotificationRemoveInterceptor() {
            @Override
            public boolean onNotificationRemoveRequested(String key, int reason) {
                if (!mBubbleData.hasBubbleWithKey(key)) {
                    return false;
                }
                NotificationEntry entry = mBubbleData.getBubbleWithKey(key).entry;

                final boolean isClearAll = reason == REASON_CANCEL_ALL;
                final boolean isUserDimiss = reason == REASON_CANCEL;
                final boolean isAppCancel = reason == REASON_APP_CANCEL
                        || reason == REASON_APP_CANCEL_ALL;

                // Need to check for !appCancel here because the notification may have
                // previously been dismissed & entry.isRowDismissed would still be true
                boolean userRemovedNotif = (entry.isRowDismissed() && !isAppCancel)
                        || isClearAll || isUserDimiss;

                // The bubble notification sticks around in the data as long as the bubble is
                // not dismissed and the app hasn't cancelled the notification.
                boolean bubbleExtended = entry.isBubble() && !entry.isBubbleDismissed()
                        && userRemovedNotif;
                if (bubbleExtended) {
                    entry.setShowInShadeWhenBubble(false);
                    if (mStackView != null) {
                        mStackView.updateDotVisibility(entry.key);
                    }
                    mNotificationEntryManager.updateNotifications();
                    return true;
                } else if (!userRemovedNotif && !entry.isBubbleDismissed()) {
                    // This wasn't a user removal so we should remove the bubble as well
                    mBubbleData.notificationEntryRemoved(entry, DISMISS_NOTIF_CANCEL);
                    return false;
                }
                return false;
            }
        };

    @SuppressWarnings("FieldCanBeLocal")
    private final NotificationEntryListener mEntryListener = new NotificationEntryListener() {
        @Override
        public void onPendingEntryAdded(NotificationEntry entry) {
            if (!areBubblesEnabled(mContext)) {
                return;
            }
            if (mNotificationInterruptionStateProvider.shouldBubbleUp(entry)
                    && canLaunchInActivityView(mContext, entry)) {
                updateShowInShadeForSuppressNotification(entry);
            }
        }

        @Override
        public void onEntryInflated(NotificationEntry entry, @InflationFlag int inflatedFlags) {
            if (!areBubblesEnabled(mContext)) {
                return;
            }
            if (mNotificationInterruptionStateProvider.shouldBubbleUp(entry)
                    && canLaunchInActivityView(mContext, entry)) {
                updateBubble(entry);
            }
        }

        @Override
        public void onPreEntryUpdated(NotificationEntry entry) {
            if (!areBubblesEnabled(mContext)) {
                return;
            }
            boolean shouldBubble = mNotificationInterruptionStateProvider.shouldBubbleUp(entry)
                    && canLaunchInActivityView(mContext, entry);
            if (!shouldBubble && mBubbleData.hasBubbleWithKey(entry.key)) {
                // It was previously a bubble but no longer a bubble -- lets remove it
                removeBubble(entry.key, DISMISS_NO_LONGER_BUBBLE);
            } else if (shouldBubble) {
                updateShowInShadeForSuppressNotification(entry);
                entry.setBubbleDismissed(false); // updates come back as bubbles even if dismissed
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
            for (Pair<Bubble, Integer> removed : update.removedBubbles) {
                final Bubble bubble = removed.first;
                @DismissReason final int reason = removed.second;
                mStackView.removeBubble(bubble);

                if (!mBubbleData.hasBubbleWithKey(bubble.getKey())
                        && !bubble.entry.showInShadeWhenBubble()) {
                    // The bubble is gone & the notification is gone, time to actually remove it
                    mNotificationEntryManager.performRemoveNotification(bubble.entry.notification,
                            UNDEFINED_DISMISS_REASON);
                } else {
                    // Update the flag for SysUI
                    bubble.entry.notification.getNotification().flags &= ~FLAG_BUBBLE;

                    // Make sure NoMan knows it's not a bubble anymore so anyone querying it will
                    // get right result back
                    try {
                        mBarService.onNotificationBubbleChanged(bubble.getKey(),
                                false /* isBubble */);
                    } catch (RemoteException e) {
                        // Bad things have happened
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
            }

            // Expanding? Apply this last.
            if (update.expandedChanged && update.expanded) {
                mStackView.setExpanded(true);
            }

            mNotificationEntryManager.updateNotifications();
            updateStack();

            if (DEBUG) {
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
     * Updates the stack view's suppression flags from the latest config from the zen (do not
     * disturb) controller.
     */
    private void updateStackViewForZenConfig() {
        final ZenModeConfig zenModeConfig = mZenModeController.getConfig();

        if (zenModeConfig == null || mStackView == null) {
            return;
        }

        final int suppressedEffects = zenModeConfig.suppressedVisualEffects;
        final boolean hideNotificationDotsSelected =
                (suppressedEffects & SUPPRESSED_EFFECT_BADGE) != 0;
        final boolean dontPopNotifsOnScreenSelected =
                (suppressedEffects & SUPPRESSED_EFFECT_PEEK) != 0;
        final boolean hideFromPullDownShadeSelected =
                (suppressedEffects & SUPPRESSED_EFFECT_NOTIFICATION_LIST) != 0;

        final boolean dndEnabled = mZenModeController.getZen() != Settings.Global.ZEN_MODE_OFF;

        mStackView.setSuppressNewDot(
                dndEnabled && hideNotificationDotsSelected);
        mStackView.setSuppressFlyout(
                dndEnabled && (dontPopNotifsOnScreenSelected
                        || hideFromPullDownShadeSelected));
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
        if (mStackView == null) {
            return INVALID_DISPLAY;
        }
        boolean defaultDisplay = context.getDisplay() != null
                && context.getDisplay().getDisplayId() == DEFAULT_DISPLAY;
        Bubble b = mStackView.getExpandedBubble();
        if (defaultDisplay && b != null && isStackExpanded()
                && !mStatusBarWindowController.getPanelExpanded()) {
            return b.expandedView.getVirtualDisplayId();
        }
        return INVALID_DISPLAY;
    }

    @VisibleForTesting
    BubbleStackView getStackView() {
        return mStackView;
    }

    /**
     * Whether the notification should automatically bubble or not. Gated by secure settings flags.
     */
    @VisibleForTesting
    protected boolean shouldAutoBubbleForFlags(Context context, NotificationEntry entry) {
        if (entry.isBubbleDismissed()) {
            return false;
        }
        StatusBarNotification n = entry.notification;

        boolean autoBubbleMessages = shouldAutoBubbleMessages(context) || DEBUG_ENABLE_AUTO_BUBBLE;
        boolean autoBubbleOngoing = shouldAutoBubbleOngoing(context) || DEBUG_ENABLE_AUTO_BUBBLE;
        boolean autoBubbleAll = shouldAutoBubbleAll(context) || DEBUG_ENABLE_AUTO_BUBBLE;

        boolean hasRemoteInput = false;
        if (n.getNotification().actions != null) {
            for (Notification.Action action : n.getNotification().actions) {
                if (action.getRemoteInputs() != null) {
                    hasRemoteInput = true;
                    break;
                }
            }
        }
        boolean isCall = Notification.CATEGORY_CALL.equals(n.getNotification().category)
                && n.isOngoing();
        boolean isMusic = n.getNotification().hasMediaSession();
        boolean isImportantOngoing = isMusic || isCall;

        Class<? extends Notification.Style> style = n.getNotification().getNotificationStyle();
        boolean isMessageType = Notification.CATEGORY_MESSAGE.equals(n.getNotification().category);
        boolean isMessageStyle = Notification.MessagingStyle.class.equals(style);
        return (((isMessageType && hasRemoteInput) || isMessageStyle) && autoBubbleMessages)
                || (isImportantOngoing && autoBubbleOngoing)
                || autoBubbleAll;
    }

    private void updateShowInShadeForSuppressNotification(NotificationEntry entry) {
        boolean suppressNotification = entry.getBubbleMetadata() != null
                && entry.getBubbleMetadata().isNotificationSuppressed()
                && isForegroundApp(mContext, entry.notification.getPackageName());
        entry.setShowInShadeWhenBubble(!suppressNotification);
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
     * Return true if the applications with the package name is running in foreground.
     *
     * @param context application context.
     * @param pkgName application package name.
     */
    public static boolean isForegroundApp(Context context, String pkgName) {
        ActivityManager am = context.getSystemService(ActivityManager.class);
        List<RunningTaskInfo> tasks = am.getRunningTasks(1 /* maxNum */);
        return !tasks.isEmpty() && pkgName.equals(tasks.get(0).topActivity.getPackageName());
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
                mBubbleData.setExpanded(false);
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
    }

    private static boolean shouldAutoBubbleMessages(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(),
                ENABLE_AUTO_BUBBLE_MESSAGES, 0) != 0;
    }

    private static boolean shouldAutoBubbleOngoing(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(),
                ENABLE_AUTO_BUBBLE_ONGOING, 0) != 0;
    }

    private static boolean shouldAutoBubbleAll(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(),
                ENABLE_AUTO_BUBBLE_ALL, 0) != 0;
    }

    static boolean shouldUseContentIntent(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(),
                ENABLE_BUBBLE_CONTENT_INTENT, 0) != 0;
    }

    private static boolean areBubblesEnabled(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(),
                ENABLE_BUBBLES, 1) != 0;
    }

    /** Default stiffness to use for bubble physics animations. */
    public static int getBubbleStiffness(Context context, int defaultStiffness) {
        return Settings.Secure.getInt(
                context.getContentResolver(), BUBBLE_STIFFNESS, defaultStiffness);
    }

    /** Default bounciness/damping ratio to use for bubble physics animations. */
    public static float getBubbleBounciness(Context context, float defaultBounciness) {
        return Settings.Secure.getInt(
                context.getContentResolver(),
                BUBBLE_BOUNCINESS,
                (int) (defaultBounciness * 100)) / 100f;
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
    private class BubblesImeListener extends IPinnedStackListener.Stub {

        @Override
        public void onListenerRegistered(IPinnedStackController controller) throws RemoteException {
        }

        @Override
        public void onMovementBoundsChanged(Rect insetBounds, Rect normalBounds,
                Rect animatingBounds, boolean fromImeAdjustment, boolean fromShelfAdjustment,
                int displayRotation) throws RemoteException {}

        @Override
        public void onImeVisibilityChanged(boolean imeVisible, int imeHeight) {
            if (mStackView != null && mStackView.getBubbleCount() > 0) {
                mStackView.post(() -> mStackView.onImeVisibilityChanged(imeVisible, imeHeight));
            }
        }

        @Override
        public void onShelfVisibilityChanged(boolean shelfVisible, int shelfHeight)
                throws RemoteException {}

        @Override
        public void onMinimizedStateChanged(boolean isMinimized) throws RemoteException {}

        @Override
        public void onActionsChanged(ParceledListSlice actions) throws RemoteException {}
    }
}
