/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.recents.views;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * This is an optimized FrameLayout whose layout is completely directed by its parent, and as a
 * result, does not propagate <code>requestLayout()</code> up the view hierarchy. Instead, it will
 * relayout its children with the last known layout bounds when a layout is requested from a child
 * view.
 */
public class FixedSizeFrameLayout extends FrameLayout {

    private final Rect mLayoutBounds = new Rect();

    public FixedSizeFrameLayout(Context context) {
        super(context);
    }

    public FixedSizeFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public FixedSizeFrameLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public FixedSizeFrameLayout(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected final void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        measureContents(MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.getSize(heightMeasureSpec));
    }

    @Override
    protected final void onLayout(boolean changed, int left, int top, int right, int bottom) {
        mLayoutBounds.set(left, top, right, bottom);
        layoutContents(mLayoutBounds, changed);
    }

    @Override
    public final void requestLayout() {
        // The base ViewGroup constructor attempts to call requestLayout() before this class's
        // members are initialized so we should just propagate in that case
        if (mLayoutBounds == null || mLayoutBounds.isEmpty()) {
            super.requestLayout();
        } else {
            // If we are already laid out, then just reuse the same bounds to layout the children
            // (but not itself)
            // TODO: Investigate whether we should coalesce these to the next frame if needed
            measureContents(getMeasuredWidth(), getMeasuredHeight());
            layoutContents(mLayoutBounds, false);
        }
    }

    /**
     * Measures the contents of this fixed layout.
     */
    protected void measureContents(int width, int height) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST));
    }

    /**
     * Lays out the contents of this fixed layout.
     */
    protected void layoutContents(Rect bounds, boolean changed) {
        super.onLayout(changed, bounds.left, bounds.top, bounds.right, bounds.bottom);

        int width = getMeasuredWidth();
        int height = getMeasuredHeight();
        onSizeChanged(width, height, width, height);
    }

}
