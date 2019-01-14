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

import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import static com.android.systemui.bubbles.BubbleMovementHelper.EDGE_OVERLAP;

import android.annotation.Nullable;
import android.app.INotificationManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.statusbar.notification.NotificationEntryListener;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.phone.StatusBarWindowController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Bubbles are a special type of content that can "float" on top of other apps or System UI.
 * Bubbles can be expanded to show more content.
 *
 * The controller manages addition, removal, and visible state of bubbles on screen.
 */
@Singleton
public class BubbleController {
    private static final int MAX_BUBBLES = 5; // TODO: actually enforce this

    private static final String TAG = "BubbleController";

    // Enables some subset of notifs to automatically become bubbles
    private static final boolean DEBUG_ENABLE_AUTO_BUBBLE = false;
    // When a bubble is dismissed, recreate it as a notification
    private static final boolean DEBUG_DEMOTE_TO_NOTIF = false;

    // Secure settings
    private static final String ENABLE_AUTO_BUBBLE_MESSAGES = "experiment_autobubble_messaging";
    private static final String ENABLE_AUTO_BUBBLE_ONGOING = "experiment_autobubble_ongoing";
    private static final String ENABLE_AUTO_BUBBLE_ALL = "experiment_autobubble_all";
    private static final String ENABLE_BUBBLE_ACTIVITY_VIEW = "experiment_bubble_activity_view";
    private static final String ENABLE_BUBBLE_CONTENT_INTENT = "experiment_bubble_content_intent";

    private final Context mContext;
    private final NotificationEntryManager mNotificationEntryManager;
    private BubbleStateChangeListener mStateChangeListener;
    private BubbleExpandListener mExpandListener;

    private final Map<String, BubbleView> mBubbles = new HashMap<>();
    private BubbleStackView mStackView;
    private final Point mDisplaySize;

    // Bubbles get added to the status bar view
    private final StatusBarWindowController mStatusBarWindowController;

    private INotificationManager mNotificationManagerService;

    // Used for determining view rect for touch interaction
    private Rect mTempRect = new Rect();

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
         * @param amount fraction of how expanded or collapsed it is, 1 being fully, 0 at the start
         */
        void onBubbleExpandChanged(boolean isExpanding, float amount);
    }

    @Inject
    public BubbleController(Context context, StatusBarWindowController statusBarWindowController) {
        mContext = context;
        mNotificationEntryManager = Dependency.get(NotificationEntryManager.class);
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mDisplaySize = new Point();
        wm.getDefaultDisplay().getSize(mDisplaySize);
        mStatusBarWindowController = statusBarWindowController;

        mNotificationEntryManager.addNotificationEntryListener(mEntryListener);

        try {
            mNotificationManagerService = INotificationManager.Stub.asInterface(
                    ServiceManager.getServiceOrThrow(Context.NOTIFICATION_SERVICE));
        } catch (ServiceManager.ServiceNotFoundException e) {
            e.printStackTrace();
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
        mExpandListener = listener;
        if (mStackView != null) {
            mStackView.setExpandListener(mExpandListener);
        }
    }

    /**
     * Whether or not there are bubbles present, regardless of them being visible on the
     * screen (e.g. if on AOD).
     */
    public boolean hasBubbles() {
        return mBubbles.size() > 0;
    }

    /**
     * Whether the stack of bubbles is expanded or not.
     */
    public boolean isStackExpanded() {
        return mStackView != null && mStackView.isExpanded();
    }

    /**
     * Tell the stack of bubbles to collapse.
     */
    public void collapseStack() {
        if (mStackView != null) {
            mStackView.animateExpansion(false);
        }
    }

    /**
     * Tell the stack of bubbles to be dismissed, this will remove all of the bubbles in the stack.
     */
    void dismissStack() {
        if (mStackView == null) {
            return;
        }
        Point startPoint = getStartPoint(mStackView.getStackWidth(), mDisplaySize);
        // Reset the position of the stack (TODO - or should we save / respect last user position?)
        mStackView.setPosition(startPoint.x, startPoint.y);
        for (String key: mBubbles.keySet()) {
            removeBubble(key);
        }
        mNotificationEntryManager.updateNotifications();
        updateBubblesShowing();
    }

    /**
     * Adds a bubble associated with the provided notification entry or updates it if it exists.
     */
    public void addBubble(NotificationEntry notif) {
        if (mBubbles.containsKey(notif.key)) {
            // It's an update
            BubbleView bubble = mBubbles.get(notif.key);
            mStackView.updateBubble(bubble, notif);
        } else {
            // It's new
            BubbleView bubble = new BubbleView(mContext);
            bubble.setNotif(notif);
            if (shouldUseActivityView(mContext)) {
                bubble.setAppOverlayIntent(getAppOverlayIntent(notif));
            }
            mBubbles.put(bubble.getKey(), bubble);

            boolean setPosition = mStackView != null && mStackView.getVisibility() != VISIBLE;
            if (mStackView == null) {
                setPosition = true;
                mStackView = new BubbleStackView(mContext);
                ViewGroup sbv = mStatusBarWindowController.getStatusBarView();
                // XXX: Bug when you expand the shade on top of expanded bubble, there is no scrim
                // between bubble and the shade
                int bubblePosition = sbv.indexOfChild(sbv.findViewById(R.id.scrim_behind)) + 1;
                sbv.addView(mStackView, bubblePosition,
                        new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
                if (mExpandListener != null) {
                    mStackView.setExpandListener(mExpandListener);
                }
            }
            mStackView.addBubble(bubble);
            if (setPosition) {
                // Need to add the bubble to the stack before we can know the width
                Point startPoint = getStartPoint(mStackView.getStackWidth(), mDisplaySize);
                mStackView.setPosition(startPoint.x, startPoint.y);
                mStackView.setVisibility(VISIBLE);
            }
            updateBubblesShowing();
        }
    }

    @Nullable
    private PendingIntent getAppOverlayIntent(NotificationEntry notif) {
        Notification notification = notif.notification.getNotification();
        if (canLaunchInActivityView(notification.getAppOverlayIntent())) {
            return notification.getAppOverlayIntent();
        } else if (shouldUseContentIntent(mContext)
                && canLaunchInActivityView(notification.contentIntent)) {
            Log.d(TAG, "[addBubble " + notif.key
                    + "]: No appOverlayIntent, using contentIntent.");
            return notification.contentIntent;
        }
        Log.d(TAG, "[addBubble " + notif.key + "]: No supported intent for ActivityView.");
        return null;
    }

    /**
     * Removes the bubble associated with the {@param uri}.
     */
    void removeBubble(String key) {
        BubbleView bv = mBubbles.get(key);
        if (mStackView != null && bv != null) {
            mStackView.removeBubble(bv);
            bv.destroyActivityView(mStackView);
            bv.getEntry().setBubbleDismissed(true);
        }

        NotificationEntry entry = mNotificationEntryManager.getNotificationData().get(key);
        if (entry != null) {
            entry.setBubbleDismissed(true);
            if (!DEBUG_DEMOTE_TO_NOTIF) {
                mNotificationEntryManager.performRemoveNotification(entry.notification);
            }
        }
        mNotificationEntryManager.updateNotifications();

        updateBubblesShowing();
    }

    @SuppressWarnings("FieldCanBeLocal")
    private final NotificationEntryListener mEntryListener = new NotificationEntryListener() {
        @Override
        public void onPendingEntryAdded(NotificationEntry entry) {
            if (shouldAutoBubble(mContext, entry) || shouldBubble(entry)) {
                entry.setIsBubble(true);
            }
        }
    };

    private void updateBubblesShowing() {
        boolean hasBubblesShowing = false;
        for (BubbleView bv : mBubbles.values()) {
            if (!bv.getEntry().isBubbleDismissed()) {
                hasBubblesShowing = true;
                break;
            }
        }
        boolean hadBubbles = mStatusBarWindowController.getBubblesShowing();
        mStatusBarWindowController.setBubblesShowing(hasBubblesShowing);
        if (mStackView != null && !hasBubblesShowing) {
            mStackView.setVisibility(INVISIBLE);
        }
        if (mStateChangeListener != null && hadBubbles != hasBubblesShowing) {
            mStateChangeListener.onHasBubblesChanged(hasBubblesShowing);
        }
    }

    /**
     * Sets the visibility of the bubbles, doesn't un-bubble them, just changes visibility.
     */
    public void updateVisibility(boolean visible) {
        if (mStackView == null) {
            return;
        }
        ArrayList<BubbleView> viewsToRemove = new ArrayList<>();
        for (BubbleView bv : mBubbles.values()) {
            NotificationEntry entry = bv.getEntry();
            if (entry != null) {
                if (entry.isRowRemoved() || entry.isBubbleDismissed() || entry.isRowDismissed()) {
                    viewsToRemove.add(bv);
                }
            }
        }
        for (BubbleView bubbleView : viewsToRemove) {
            mBubbles.remove(bubbleView.getKey());
            mStackView.removeBubble(bubbleView);
            bubbleView.destroyActivityView(mStackView);
        }
        if (mStackView != null) {
            mStackView.setVisibility(visible ? VISIBLE : INVISIBLE);
            if (!visible) {
                collapseStack();
            }
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

    private boolean canLaunchInActivityView(PendingIntent intent) {
        if (intent == null) {
            return false;
        }
        ActivityInfo info =
                intent.getIntent().resolveActivityInfo(mContext.getPackageManager(), 0);
        return info != null
                && ActivityInfo.isResizeableMode(info.resizeMode)
                && (info.flags & ActivityInfo.FLAG_ALLOW_EMBEDDED) != 0;
    }

    @VisibleForTesting
    BubbleStackView getStackView() {
        return mStackView;
    }

    // TODO: factor in PIP location / maybe last place user had it
    /**
     * Gets an appropriate starting point to position the bubble stack.
     */
    private static Point getStartPoint(int size, Point displaySize) {
        final int x = displaySize.x - size + EDGE_OVERLAP;
        final int y = displaySize.y / 4;
        return new Point(x, y);
    }

    /**
     * Gets an appropriate position for the bubble when the stack is expanded.
     */
    static Point getExpandPoint(BubbleStackView view, int size, Point displaySize) {
        // Same place for now..
        return new Point(EDGE_OVERLAP, size);
    }

    /**
     * Whether the notification has been developer configured to bubble and is allowed by the user.
     */
    private boolean shouldBubble(NotificationEntry entry) {
        StatusBarNotification n = entry.notification;
        boolean canAppOverlay = false;
        try {
            canAppOverlay = mNotificationManagerService.areAppOverlaysAllowedForPackage(
                    n.getPackageName(), n.getUid());
        } catch (RemoteException e) {
            Log.w(TAG, "Error calling NoMan to determine if app can overlay", e);
        }

        boolean canChannelOverlay = mNotificationEntryManager.getNotificationData().getChannel(
                entry.key).canOverlayApps();
        boolean hasOverlayIntent = n.getNotification().getAppOverlayIntent() != null;
        return hasOverlayIntent && canChannelOverlay && canAppOverlay;
    }

    /**
     * Whether the notification should bubble or not.
     */
    private boolean shouldAutoBubble(Context context, NotificationEntry entry) {
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

    private static boolean shouldUseActivityView(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(),
                ENABLE_BUBBLE_ACTIVITY_VIEW, 0) != 0;
    }

    private static boolean shouldUseContentIntent(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(),
                ENABLE_BUBBLE_CONTENT_INTENT, 0) != 0;
    }
}
