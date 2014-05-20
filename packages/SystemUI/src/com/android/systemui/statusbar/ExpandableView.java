/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

/**
 * An abstract view for expandable views.
 */
public abstract class ExpandableView extends ViewGroup {

    private OnHeightChangedListener mOnHeightChangedListener;
    protected int mActualHeight;
    protected int mClipTopAmount;
    private boolean mActualHeightInitialized;

    public ExpandableView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            int height = child.getMeasuredHeight();
            int width = child.getMeasuredWidth();
            int center = getWidth() / 2;
            int childLeft = center - width / 2;
            child.layout(childLeft,
                    0,
                    childLeft + width,
                    height);
        }
        if (!mActualHeightInitialized && mActualHeight == 0) {
            mActualHeight = getInitialHeight();
        }
        mActualHeightInitialized = true;
    }

    protected int getInitialHeight() {
        return getHeight();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (filterMotionEvent(ev)) {
            return super.dispatchTouchEvent(ev);
        }
        return false;
    }

    private boolean filterMotionEvent(MotionEvent event) {
        return event.getActionMasked() != MotionEvent.ACTION_DOWN
                || event.getY() > mClipTopAmount && event.getY() < mActualHeight;
    }

    /**
     * Sets the actual height of this notification. This is different than the laid out
     * {@link View#getHeight()}, as we want to avoid layouting during scrolling and expanding.
     *
     * @param actualHeight The height of this notification.
     * @param notifyListeners Whether the listener should be informed about the change.
     */
    public void setActualHeight(int actualHeight, boolean notifyListeners) {
        mActualHeight = actualHeight;
        if (notifyListeners) {
            notifyHeightChanged();
        }
    }

    public void setActualHeight(int actualHeight) {
        setActualHeight(actualHeight, true);
    }

    /**
     * See {@link #setActualHeight}.
     *
     * @return The current actual height of this notification.
     */
    public int getActualHeight() {
        return mActualHeight;
    }

    /**
     * @return The maximum height of this notification.
     */
    public int getMaxHeight() {
        return getHeight();
    }

    /**
     * @return The minimum height of this notification.
     */
    public int getMinHeight() {
        return getHeight();
    }

    /**
     * Sets the notification as dimmed. The default implementation does nothing.
     *
     * @param dimmed Whether the notification should be dimmed.
     * @param fade Whether an animation should be played to change the state.
     */
    public void setDimmed(boolean dimmed, boolean fade) {
    }

    /**
     * @return The desired notification height.
     */
    public int getIntrinsicHeight() {
        return getHeight();
    }

    /**
     * Sets the amount this view should be clipped from the top. This is used when an expanded
     * notification is scrolling in the top or bottom stack.
     *
     * @param clipTopAmount The amount of pixels this view should be clipped from top.
     */
    public void setClipTopAmount(int clipTopAmount) {
        mClipTopAmount = clipTopAmount;
    }

    public int getClipTopAmount() {
        return mClipTopAmount;
    }

    public void setOnHeightChangedListener(OnHeightChangedListener listener) {
        mOnHeightChangedListener = listener;
    }

    /**
     * @return Whether we can expand this views content.
     */
    public boolean isContentExpandable() {
        return false;
    }

    public void notifyHeightChanged() {
        if (mOnHeightChangedListener != null) {
            mOnHeightChangedListener.onHeightChanged(this);
        }
    }

    public boolean isTransparent() {
        return false;
    }

    /**
     * A listener notifying when {@link #getActualHeight} changes.
     */
    public interface OnHeightChangedListener {
        void onHeightChanged(ExpandableView view);
    }
}
