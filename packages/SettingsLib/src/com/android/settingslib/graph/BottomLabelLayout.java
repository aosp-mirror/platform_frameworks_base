/*
 * Copyright (C) 2017 The Android Open Source Project
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
 *
 *
 */

package com.android.settingslib.graph;

import android.annotation.Nullable;
import android.content.Context;
import android.support.annotation.VisibleForTesting;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

import com.android.settingslib.R;

/**
 * An extension of LinearLayout that automatically switches to vertical
 * orientation when it can't fit its child views horizontally.
 *
 * Main logic in this class comes from {@link android.support.v7.widget.ButtonBarLayout}.
 * Compared with {@link android.support.v7.widget.ButtonBarLayout}, this layout won't reverse
 * children's order and won't update the minimum height
 */
public class BottomLabelLayout extends LinearLayout {
    private static final String TAG = "BottomLabelLayout";

    public BottomLabelLayout(Context context,
            @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        final boolean isStacked = isStacked();
        boolean needsRemeasure = false;

        // If we're not stacked, make sure the measure spec is AT_MOST rather
        // than EXACTLY. This ensures that we'll still get TOO_SMALL so that we
        // know to stack the buttons.
        final int initialWidthMeasureSpec;
        if (!isStacked && MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) {
            initialWidthMeasureSpec = MeasureSpec.makeMeasureSpec(widthSize, MeasureSpec.AT_MOST);

            // We'll need to remeasure again to fill excess space.
            needsRemeasure = true;
        } else {
            initialWidthMeasureSpec = widthMeasureSpec;
        }

        super.onMeasure(initialWidthMeasureSpec, heightMeasureSpec);
        if (!isStacked) {
            final int measuredWidth = getMeasuredWidthAndState();
            final int measuredWidthState = measuredWidth & View.MEASURED_STATE_MASK;

            if (measuredWidthState == View.MEASURED_STATE_TOO_SMALL) {
                setStacked(true);
                // Measure again in the new orientation.
                needsRemeasure = true;
            }
        }

        if (needsRemeasure) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }

    }

    @VisibleForTesting
    void setStacked(boolean stacked) {
        setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        setGravity(stacked ? Gravity.START : Gravity.BOTTOM);

        final View spacer = findViewById(R.id.spacer);
        if (spacer != null) {
            spacer.setVisibility(stacked ? View.GONE : View.VISIBLE);
        }
    }

    private boolean isStacked() {
        return getOrientation() == LinearLayout.VERTICAL;
    }
}
