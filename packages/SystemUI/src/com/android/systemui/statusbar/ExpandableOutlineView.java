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
import android.graphics.Outline;
import android.graphics.RectF;
import android.util.AttributeSet;

/**
 * Like {@link ExpandableView}, but setting an outline for the height and clipping.
 */
public abstract class ExpandableOutlineView extends ExpandableView {

    private final Outline mOutline = new Outline();
    private boolean mCustomOutline;
    private float mDensity;

    public ExpandableOutlineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
    }

    @Override
    public void setActualHeight(int actualHeight, boolean notifyListeners) {
        super.setActualHeight(actualHeight, notifyListeners);
        updateOutline();
    }

    @Override
    public void setClipTopAmount(int clipTopAmount) {
        super.setClipTopAmount(clipTopAmount);
        updateOutline();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updateOutline();
    }

    protected void setOutlineRect(RectF rect) {
        if (rect != null) {
            setOutlineRect(rect.left, rect.top, rect.right, rect.bottom);
        } else {
            mCustomOutline = false;
            updateOutline();
        }
    }

    protected void setOutlineRect(float left, float top, float right, float bottom) {
        mCustomOutline = true;

        int rectLeft = (int) left;
        int rectTop = (int) top;
        int rectRight = (int) right;
        int rectBottom = (int) bottom;

        // Outlines need to be at least 1 dp
        rectBottom = (int) Math.max(top + mDensity, rectBottom);
        rectRight = (int) Math.max(left + mDensity, rectRight);
        mOutline.setRect(rectLeft, rectTop, rectRight, rectBottom);
        setOutline(mOutline);
    }

    private void updateOutline() {
        if (!mCustomOutline) {
            mOutline.setRect(0,
                    mClipTopAmount,
                    getWidth(),
                    Math.max(mActualHeight, mClipTopAmount));
            setOutline(mOutline);
        }
    }
}
