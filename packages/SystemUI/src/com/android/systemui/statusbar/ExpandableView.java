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
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/**
 * An abstract view for expandable views.
 */
public abstract class ExpandableView extends FrameLayout {

    private OnHeightChangedListener mOnHeightChangedListener;
    protected int mActualHeight;
    protected int mClipTopAmount;
    private boolean mActualHeightInitialized;

    public ExpandableView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (!mActualHeightInitialized && mActualHeight == 0) {
            mActualHeight = getHeight();
        }
        mActualHeightInitialized = true;
    }

    /**
     * Sets the actual height of this notification. This is different than the laid out
     * {@link View#getHeight()}, as we want to avoid layouting during scrolling and expanding.
     */
    public void setActualHeight(int actualHeight) {
        mActualHeight = actualHeight;
        notifyHeightChanged();
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
     * @return The desired notification height.
     */
    public int getIntrinsicHeight() {
        return mActualHeight;
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

    /**
     * A listener notifying when {@link #getActualHeight} changes.
     */
    public interface OnHeightChangedListener {
        void onHeightChanged(ExpandableView view);
    }
}
