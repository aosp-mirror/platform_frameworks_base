/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.internal.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

import com.android.internal.R;

/**
 * An extension of LinearLayout that automatically switches to vertical
 * orientation when it can't fit its child views horizontally.
 */
public class ButtonBarLayout extends LinearLayout {
    /** Spacer used in horizontal orientation. */
    private final View mSpacer;

    /** Whether the current configuration allows stacking. */
    private final boolean mAllowStacked;

    /** Whether the layout is currently stacked. */
    private boolean mStacked;

    public ButtonBarLayout(Context context, AttributeSet attrs) {
        super(context, attrs);

        mAllowStacked = context.getResources().getBoolean(R.bool.allow_stacked_button_bar);
        mSpacer = findViewById(R.id.spacer);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        // Maybe we can fit the content now?
        if (w > oldw && mStacked) {
            setStacked(false);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        if (mAllowStacked && getOrientation() == LinearLayout.HORIZONTAL) {
            final int measuredWidth = getMeasuredWidthAndState();
            final int measuredWidthState = measuredWidth & MEASURED_STATE_MASK;
            if (measuredWidthState == MEASURED_STATE_TOO_SMALL) {
                setStacked(true);

                // Measure again in the new orientation.
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
        }
    }

    private void setStacked(boolean stacked) {
        setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        setGravity(stacked ? Gravity.RIGHT : Gravity.BOTTOM);

        if (mSpacer != null) {
            mSpacer.setVisibility(stacked ? View.GONE : View.INVISIBLE);
        }

        // Reverse the child order. This is specific to the Material button
        // bar's layout XML and will probably not generalize.
        final int childCount = getChildCount();
        for (int i = childCount - 2; i >= 0; i--) {
            bringChildToFront(getChildAt(i));
        }

        mStacked = stacked;
    }
}
