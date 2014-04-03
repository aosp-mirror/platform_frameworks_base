/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;

import com.android.systemui.R;
import com.android.systemui.statusbar.GestureRecorder;
import com.android.systemui.statusbar.stack.NotificationStackScrollLayout;

public class NotificationPanelView extends PanelView {
    public static final boolean DEBUG_GESTURES = true;

    PhoneStatusBar mStatusBar;
    private NotificationStackScrollLayout mNotificationStackScroller;
    private int[] mTempLocation = new int[2];
    private int[] mTempChildLocation = new int[2];
    private View mNotificationParent;


    public NotificationPanelView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setStatusBar(PhoneStatusBar bar) {
        if (mStatusBar != null) {
            mStatusBar.setOnFlipRunnable(null);
        }
        mStatusBar = bar;
        if (bar != null) {
            mStatusBar.setOnFlipRunnable(new Runnable() {
                @Override
                public void run() {
                    requestPanelHeightUpdate();
                }
            });
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mNotificationStackScroller = (NotificationStackScrollLayout)
                findViewById(R.id.notification_stack_scroller);
        mNotificationParent = findViewById(R.id.notification_container_parent);
    }

    @Override
    public void fling(float vel, boolean always) {
        GestureRecorder gr = ((PhoneStatusBarView) mBar).mBar.getGestureRecorder();
        if (gr != null) {
            gr.tag(
                "fling " + ((vel > 0) ? "open" : "closed"),
                "notifications,v=" + vel);
        }
        super.fling(vel, always);
    }

    @Override
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.getText()
                    .add(getContext().getString(R.string.accessibility_desc_notification_shade));
            return true;
        }

        return super.dispatchPopulateAccessibilityEvent(event);
    }

    /**
     * Gets the relative position of a view on the screen in regard to this view.
     *
     * @param requestedView the view we want to find the relative position for
     * @return
     */
    private int getRelativeTop(View requestedView) {
        getLocationOnScreen(mTempLocation);
        requestedView.getLocationOnScreen(mTempChildLocation);
        return mTempChildLocation[1] - mTempLocation[1];
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // TODO: Handle doublefinger swipe to notifications again. Look at history for a reference
        // implementation.
        return super.onTouchEvent(event);
    }

    @Override
    protected boolean isScrolledToBottom() {
        if (!isInSettings()) {
            return mNotificationStackScroller.isScrolledToBottom();
        }
        return super.isScrolledToBottom();
    }

    @Override
    protected int getMaxPanelHeight() {
        if (!isInSettings()) {
            int maxPanelHeight = super.getMaxPanelHeight();
            int emptyBottomMargin = mNotificationStackScroller.getEmptyBottomMargin();
            return maxPanelHeight - emptyBottomMargin;
        }
        return super.getMaxPanelHeight();
    }

    private boolean isInSettings() {
        return mStatusBar != null && mStatusBar.isFlippedToSettings();
    }

    @Override
    protected void onHeightUpdated(float expandedHeight) {
        updateNotificationStackHeight(expandedHeight);
    }

    /**
     * Update the height of the {@link #mNotificationStackScroller} to the new expanded height.
     * This is much more efficient than doing it over the layout pass.
     *
     * @param expandedHeight the new expanded height
     */
    private void updateNotificationStackHeight(float expandedHeight) {
        float childOffset = getRelativeTop(mNotificationStackScroller)
                - mNotificationParent.getTranslationY();
        int newStackHeight = (int) (expandedHeight - childOffset);
        int itemHeight = mNotificationStackScroller.getItemHeight();
        int bottomStackPeekSize = mNotificationStackScroller.getBottomStackPeekSize();
        int minStackHeight = itemHeight + bottomStackPeekSize;
        if (newStackHeight >= minStackHeight) {
            mNotificationParent.setTranslationY(0);
            mNotificationStackScroller.setCurrentStackHeight(newStackHeight);
        } else {

            // We did not reach the position yet where we actually start growing,
            // so we translate the stack upwards.
            int translationY = (newStackHeight - minStackHeight);
            // A slight parallax effect is introduced in order for the stack to catch up with
            // the top card.
            float partiallyThere = (float) newStackHeight / minStackHeight;
            partiallyThere = Math.max(0, partiallyThere);
            translationY += (1 - partiallyThere) * bottomStackPeekSize;
            mNotificationParent.setTranslationY(translationY);
            mNotificationStackScroller.setCurrentStackHeight(
                    (int) (expandedHeight - (childOffset + translationY)));
        }
    }

    @Override
    protected int getDesiredMeasureHeight() {
        return mMaxPanelHeight;
    }
}
