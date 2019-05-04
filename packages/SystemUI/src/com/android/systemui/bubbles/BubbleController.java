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

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityTaskManager;
import android.app.IActivityTaskManager;
import android.app.Notification;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.Display;
import android.view.IPinnedStackController;
import android.view.IPinnedStackListener;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.IntDef;
import androidx.annotation.MainThread;

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

import java.lang.annotation.Retention;
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
    private static final boolean DEBUG = true;

    @Retention(SOURCE)
    @IntDef({DISMISS_USER_GESTURE, DISMISS_AGED, DISMISS_TASK_FINISHED, DISMISS_BLOCKED,
            DISMISS_NOTIF_CANCEL, DISMISS_ACCESSIBILITY_ACTION, DISMISS_NO_LONGER_BUBBLE})
    @interface DismissReason {}

    static final int DISMISS_USER_GESTURE = 1;
    static final int DISMISS_AGED = 2;
    static final int DISMISS_TASK_FINISHED = 3;
    static final int DISMISS_BLOCKED = 4;
    static final int DISMISS_NOTIF_CANCEL = 5;
    static final int DISMISS_ACCESSIBILITY_ACTION = 6;
    static final int DISMISS_NO_LONGER_BUBBLE = 7;

    static final int MAX_BUBBLES = 5; // TODO: actually enforce this

    // Enables some subset of notifs to automatically become bubbles
    private static final boolean DEBUG_ENABLE_AUTO_BUBBLE = false;

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
    private final IActivityTaskManager mActivityTaskManager;
    private final BubbleTaskStackListener mTaskStackListener;
    private BubbleStateChangeListener mStateChangeListener;
    private BubbleExpandListener mExpandListener;
    @Nullable private BubbleStackView.SurfaceSynchronizer mSurfaceSynchronizer;

    private BubbleData mBubbleData;
    private BubbleStackView mStackView;

    // Bubbles get added to the status bar view
    private final StatusBarWindowController mStatusBarWindowController;
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
            updateVisibility();
        }
    }

    @Inject
    public BubbleController(Context context, StatusBarWindowController statusBarWindowController,
            BubbleData data, ConfigurationController configurationController,
            NotificationInterruptionStateProvider interruptionStateProvider) {
        this(context, statusBarWindowController, data, null /* synchronizer */,
                configurationController, interruptionStateProvider);
    }

    public BubbleController(Context context, StatusBarWindowController statusBarWindowController,
            BubbleData data, @Nullable BubbleStackView.SurfaceSynchronizer synchronizer,
            ConfigurationController configurationController,
            NotificationInterruptionStateProvider interruptionStateProvider) {
        mContext = context;
        mNotificationInterruptionStateProvider = interruptionStateProvider;

        configurationController.addCallback(this /* configurationListener */);

        mBubbleData = data;
        mBubbleData.setListener(mBubbleDataListener);

        mNotificationEntryManager = Dependency.get(NotificationEntryManager.class);
        mNotificationEntryManager.addNotificationEntryListener(mEntryListener);
        mNotificationEntryManager.setNotificationRemoveInterceptor(mRemoveInterceptor);

        mStatusBarWindowController = statusBarWindowController;
        mStatusBarStateListener = new StatusBarStateListener();
        Dependency.get(StatusBarStateController.class).addCallback(mStatusBarStateListener);

        mActivityTaskManager = ActivityTaskManager.getService();
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
            if (mNotificationInterruptionStateProvider.shouldBubbleUp(entry)) {
                updateShowInShadeForSuppressNotification(entry);
            }
        }

        @Override
        public void onEntryInflated(NotificationEntry entry, @InflationFlag int inflatedFlags) {
            if (!areBubblesEnabled(mContext)) {
                return;
            }
            if (mNotificationInterruptionStateProvider.shouldBubbleUp(entry)) {
                updateBubble(entry);
            }
        }

        @Override
        public void onPreEntryUpdated(NotificationEntry entry) {
            if (!areBubblesEnabled(mContext)) {
                return;
            }
            boolean shouldBubble = mNotificationInterruptionStateProvider.shouldBubbleUp(entry);
            if (!shouldBubble && mBubbleData.hasBubbleWithKey(entry.key)) {
                // It was previously a bubble but no longer a bubble -- lets remove it
                removeBubble(entry.key, DISMISS_NO_LONGER_BUBBLE);
            } else if (shouldBubble) {
                updateShowInShadeForSuppressNotification(entry);
                entry.setBubbleDismissed(false); // updates come back as bubbles even if dismissed
                updateBubble(entry);
            }
        }
    };

    @SuppressWarnings("FieldCanBeLocal")
    private final BubbleData.Listener mBubbleDataListener = new BubbleData.Listener() {

        @Override
        public void onBubbleAdded(Bubble bubble) {
            ensureStackViewCreated();
            mStackView.addBubble(bubble);
        }

        @Override
        public void onBubbleRemoved(Bubble bubble, @DismissReason int reason) {
            if (mStackView != null) {
                mStackView.removeBubble(bubble);
            }
            if (!mBubbleData.hasBubbleWithKey(bubble.getKey())
                    && !bubble.entry.showInShadeWhenBubble()) {
                // The bubble is gone & the notification is gone, time to actually remove it
                mNotificationEntryManager.performRemoveNotification(bubble.entry.notification,
                        UNDEFINED_DISMISS_REASON);
            } else {
                // The notification is still in the shade but we've removed the bubble so
                // lets make sure NoMan knows it's not a bubble anymore
                try {
                    mBarService.onNotificationBubbleChanged(bubble.getKey(), false /* isBubble */);
                } catch (RemoteException e) {
                    // Bad things have happened
                }
            }
        }

        public void onBubbleUpdated(Bubble bubble) {
            if (mStackView != null) {
                mStackView.updateBubble(bubble);
            }
        }

        @Override
        public void onOrderChanged(List<Bubble> bubbles) {
        }

        @Override
        public void onSelectionChanged(@Nullable Bubble selectedBubble) {
            if (mStackView != null) {
                mStackView.setSelectedBubble(selectedBubble);
            }
        }

        @Override
        public void onExpandedChanged(boolean expanded) {
            if (mStackView != null) {
                mStackView.setExpanded(expanded);
            }
        }

        @Override
        public void showFlyoutText(Bubble bubble, String text) {
            if (mStackView != null) {
                mStackView.animateInFlyoutForBubble(bubble);
            }
        }

        @Override
        public void apply() {
            mNotificationEntryManager.updateNotifications();
            updateVisibility();

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
     * Lets any listeners know if bubble state has changed.
     */
    private void updateBubblesShowing() {
        if (mStackView == null) {
            return;
        }

        boolean hadBubbles = mStatusBarWindowController.getBubblesShowing();
        boolean hasBubblesShowing = hasBubbles() && mStackView.getVisibility() == VISIBLE;
        mStatusBarWindowController.setBubblesShowing(hasBubblesShowing);
        if (mStateChangeListener != null && hadBubbles != hasBubblesShowing) {
            mStateChangeListener.onHasBubblesChanged(hasBubblesShowing);
        }
    }

    /**
     * Updates the visibility of the bubbles based on current state.
     * Does not un-bubble, just hides or un-hides. Will notify any
     * {@link BubbleStateChangeListener}s if visibility changes.
     */
    public void updateVisibility() {
        if (mStatusBarStateListener.getCurrentState() == SHADE && hasBubbles()) {
            // Bubbles only appear in unlocked shade
            mStackView.setVisibility(hasBubbles() ? VISIBLE : INVISIBLE);
        } else if (mStackView != null) {
            mStackView.setVisibility(INVISIBLE);
        }
        updateBubblesShowing();
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

    private boolean shouldAutoExpand(NotificationEntry entry) {
        Notification.BubbleMetadata metadata = entry.getBubbleMetadata();
        return metadata != null && metadata.getAutoExpandBubble()
                && isForegroundApp(mContext, entry.notification.getPackageName());
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
