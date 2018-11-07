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

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import static com.android.systemui.bubbles.BubbleMovementHelper.EDGE_OVERLAP;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Point;
import android.service.notification.StatusBarNotification;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.statusbar.notification.NotificationData;
import com.android.systemui.statusbar.phone.StatusBarWindowController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Bubbles are a special type of content that can "float" on top of other apps or System UI.
 * Bubbles can be expanded to show more content.
 *
 * The controller manages addition, removal, and visible state of bubbles on screen.
 */
public class BubbleController {
    private static final int MAX_BUBBLES = 5; // TODO: actually enforce this

    private static final String TAG = "BubbleController";

    // Enables some subset of notifs to automatically become bubbles
    public static final boolean DEBUG_ENABLE_AUTO_BUBBLE = false;
    // When a bubble is dismissed, recreate it as a notification
    public static final boolean DEBUG_DEMOTE_TO_NOTIF = false;

    private Context mContext;
    private BubbleDismissListener mDismissListener;

    private Map<String, BubbleView> mBubbles = new HashMap<>();
    private BubbleStackView mStackView;
    private Point mDisplaySize;

    // Bubbles get added to the status bar view
    private StatusBarWindowController mStatusBarWindowController;

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

    public BubbleController(Context context) {
        mContext = context;
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mDisplaySize = new Point();
        wm.getDefaultDisplay().getSize(mDisplaySize);
        mStatusBarWindowController = Dependency.get(StatusBarWindowController.class);
    }

    /**
     * Set a listener to be notified of bubble dismissal events.
     */
    public void setDismissListener(BubbleDismissListener listener) {
        mDismissListener = listener;
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
        mStackView.setVisibility(GONE);
        Point startPoint = getStartPoint(mStackView.getStackWidth(), mDisplaySize);
        // Reset the position of the stack (TODO - or should we save / respect last user position?)
        mStackView.setPosition(startPoint.x, startPoint.y);
        for (String key: mBubbles.keySet()) {
            removeBubble(key);
        }
        if (mDismissListener != null) {
            mDismissListener.onStackDismissed();
        }
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

            boolean setPosition = false;
            if (mStackView == null) {
                setPosition = true;
                mStackView = new BubbleStackView(mContext);
                ViewGroup sbv = (ViewGroup) mStatusBarWindowController.getStatusBarView();
                // XXX: Bug when you expand the shade on top of expanded bubble, there is no scrim
                // between bubble and the shade
                int bubblePosition = sbv.indexOfChild(sbv.findViewById(R.id.scrim_behind)) + 1;
                sbv.addView(mStackView, bubblePosition,
                        new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
            }
            mStackView.setVisibility(VISIBLE);
            mStackView.addBubble(bubble);

            if (setPosition) {
                // Need to add the bubble to the stack before we can know the width
                Point startPoint = getStartPoint(mStackView.getStackWidth(), mDisplaySize);
                mStackView.setPosition(startPoint.x, startPoint.y);
            }
        }
    }

    /**
     * Removes the bubble associated with the {@param uri}.
     */
    public void removeBubble(String key) {
        BubbleView bv = mBubbles.get(key);
        if (bv != null) {
            mStackView.removeBubble(bv);
            bv.getEntry().setBubbleDismissed(true);
        }
        if (mDismissListener != null) {
            mDismissListener.onBubbleDismissed(key);
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
                if (entry.row.isRemoved() || entry.isBubbleDismissed() || entry.row.isDismissed()) {
                    viewsToRemove.add(bv);
                }
            }
        }
        for (BubbleView view : viewsToRemove) {
            mBubbles.remove(view.getKey());
            mStackView.removeBubble(view);
            if (mBubbles.size() == 0) {
                ((ViewGroup) mStatusBarWindowController.getStatusBarView()).removeView(mStackView);
                mStackView = null;
            }
        }
        if (mStackView != null) {
            mStackView.setVisibility(visible ? VISIBLE : GONE);
        }
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
    public static boolean shouldAutoBubble(NotificationData.Entry entry, int priority,
            boolean canAppOverlay) {
        if (!DEBUG_ENABLE_AUTO_BUBBLE || entry.isBubbleDismissed()) {
            return false;
        }
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
        Class<? extends Notification.Style> style = n.getNotification().getNotificationStyle();
        boolean shouldBubble = priority >= NotificationManager.IMPORTANCE_HIGH
                || Notification.MessagingStyle.class.equals(style)
                || Notification.CATEGORY_MESSAGE.equals(n.getNotification().category)
                || hasRemoteInput
                || canAppOverlay;
        return shouldBubble && !entry.isBubbleDismissed();
    }
}
