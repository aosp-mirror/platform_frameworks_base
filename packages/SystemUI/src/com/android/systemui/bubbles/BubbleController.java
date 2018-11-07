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

import android.content.Context;
import android.graphics.Point;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.statusbar.notification.NotificationData;
import com.android.systemui.statusbar.phone.StatusBarWindowController;

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

    private Context mContext;

    private Map<String, BubbleView> mBubbles = new HashMap<>();
    private BubbleStackView mStackView;
    private Point mDisplaySize;

    // Bubbles get added to the status bar view
    private StatusBarWindowController mStatusBarWindowController;

    public BubbleController(Context context) {
        mContext = context;
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mDisplaySize = new Point();
        wm.getDefaultDisplay().getSize(mDisplaySize);
        mStatusBarWindowController = Dependency.get(StatusBarWindowController.class);
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

}
