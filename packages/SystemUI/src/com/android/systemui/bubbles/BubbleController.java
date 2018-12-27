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

import android.app.Notification;
import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;
import com.android.systemui.statusbar.notification.NotificationData;
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
    public static final boolean DEBUG_ENABLE_AUTO_BUBBLE = false;
    // When a bubble is dismissed, recreate it as a notification
    public static final boolean DEBUG_DEMOTE_TO_NOTIF = false;

    // Secure settings
    private static final String ENABLE_AUTO_BUBBLE_MESSAGES = "experiment_autobubble_messaging";
    private static final String ENABLE_AUTO_BUBBLE_ONGOING = "experiment_autobubble_ongoing";
    private static final String ENABLE_AUTO_BUBBLE_ALL = "experiment_autobubble_all";

    private Context mContext;
    private BubbleDismissListener mDismissListener;
    private BubbleStateChangeListener mStateChangeListener;
    private BubbleExpandListener mExpandListener;

    private Map<String, BubbleView> mBubbles = new HashMap<>();
    private BubbleStackView mStackView;
    private Point mDisplaySize;

    // Bubbles get added to the status bar view
    @VisibleForTesting
    protected StatusBarWindowController mStatusBarWindowController;

    // Used for determining view rect for touch interaction
    private Rect mTempRect = new Rect();

    /**
     * Listener to find out about bubble / bubble stack dismissal events.
     */
    public interface BubbleDismissListener {
        /**
         * Called when the entire stack of bubbles is dismissed by the user.
         */
        void onStackDismissed();

        /**
         * Called when a specific bubble is dismissed by the user.
         */
        void onBubbleDismissed(String key);
    }

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
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mDisplaySize = new Point();
        wm.getDefaultDisplay().getSize(mDisplaySize);
        mStatusBarWindowController = statusBarWindowController;
    }

    /**
     * Set a listener to be notified of bubble dismissal events.
     */
    public void setDismissListener(BubbleDismissListener listener) {
        mDismissListener = listener;
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
    public void dismissStack() {
        if (mStackView == null) {
            return;
        }
        Point startPoint = getStartPoint(mStackView.getStackWidth(), mDisplaySize);
        // Reset the position of the stack (TODO - or should we save / respect last user position?)
        mStackView.setPosition(startPoint.x, startPoint.y);
        for (String key: mBubbles.keySet()) {
            removeBubble(key);
        }
        if (mDismissListener != null) {
            mDismissListener.onStackDismissed();
        }
        updateBubblesShowing();
    }

    /**
     * Adds a bubble associated with the provided notification entry or updates it if it exists.
     */
    public void addBubble(NotificationData.Entry notif) {
        if (mBubbles.containsKey(notif.key)) {
            // It's an update
            BubbleView bubble = mBubbles.get(notif.key);
            mStackView.updateBubble(bubble, notif);
        } else {
            // It's new
            BubbleView bubble = new BubbleView(mContext);
            bubble.setNotif(notif);
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

    /**
     * Removes the bubble associated with the {@param uri}.
     */
    public void removeBubble(String key) {
        BubbleView bv = mBubbles.get(key);
        if (mStackView != null && bv != null) {
            mStackView.removeBubble(bv);
            bv.getEntry().setBubbleDismissed(true);
        }
        if (mDismissListener != null) {
            mDismissListener.onBubbleDismissed(key);
        }
        updateBubblesShowing();
    }

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
            NotificationData.Entry entry = bv.getEntry();
            if (entry != null) {
                if (entry.isRowRemoved() || entry.isBubbleDismissed() || entry.isRowDismissed()) {
                    viewsToRemove.add(bv);
                }
            }
        }
        for (BubbleView view : viewsToRemove) {
            mBubbles.remove(view.getKey());
            mStackView.removeBubble(view);
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

    @VisibleForTesting
    public BubbleStackView getStackView() {
        return mStackView;
    }

    // TODO: factor in PIP location / maybe last place user had it
    /**
     * Gets an appropriate starting point to position the bubble stack.
     */
    public static Point getStartPoint(int size, Point displaySize) {
        final int x = displaySize.x - size + EDGE_OVERLAP;
        final int y = displaySize.y / 4;
        return new Point(x, y);
    }

    /**
     * Gets an appropriate position for the bubble when the stack is expanded.
     */
    public static Point getExpandPoint(BubbleStackView view, int size, Point displaySize) {
        // Same place for now..
        return new Point(EDGE_OVERLAP, size);
    }

    /**
     * Whether the notification should bubble or not.
     */
    public static boolean shouldAutoBubble(Context context, NotificationData.Entry entry) {
        if (entry.isBubbleDismissed()) {
            return false;
        }

        boolean autoBubbleMessages = shouldAutoBubbleMessages(context) || DEBUG_ENABLE_AUTO_BUBBLE;
        boolean autoBubbleOngoing = shouldAutoBubbleOngoing(context) || DEBUG_ENABLE_AUTO_BUBBLE;
        boolean autoBubbleAll = shouldAutoBubbleAll(context) || DEBUG_ENABLE_AUTO_BUBBLE;

        StatusBarNotification n = entry.notification;
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
}
