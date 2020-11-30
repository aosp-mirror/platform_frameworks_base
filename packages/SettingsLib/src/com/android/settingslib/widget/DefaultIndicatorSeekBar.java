/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.settingslib.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.widget.SeekBar;

/**
 * Customized default indicator seekbar
 */
public class DefaultIndicatorSeekBar extends SeekBar {

    private int mDefaultProgress = -1;

    public DefaultIndicatorSeekBar(Context context) {
        super(context);
    }

    public DefaultIndicatorSeekBar(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public DefaultIndicatorSeekBar(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public DefaultIndicatorSeekBar(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    /**
     * N.B. Only draws the default indicator tick mark, NOT equally spaced tick marks.
     */
    @Override
    protected void drawTickMarks(Canvas canvas) {
        if (isEnabled() && mDefaultProgress <= getMax() && mDefaultProgress >= getMin()) {
            final Drawable defaultIndicator = getTickMark();

            // Adjust the drawable's bounds to center it at the point where it's drawn.
            final int w = defaultIndicator.getIntrinsicWidth();
            final int h = defaultIndicator.getIntrinsicHeight();
            final int halfW = w >= 0 ? w / 2 : 1;
            final int halfH = h >= 0 ? h / 2 : 1;
            defaultIndicator.setBounds(-halfW, -halfH, halfW, halfH);

            // This mimics the computation of the thumb position, to get the true "default."
            final int availableWidth = getWidth() - mPaddingLeft - mPaddingRight;
            final int range = getMax() - getMin();
            final float scale = range > 0f ? mDefaultProgress / (float) range : 0f;
            final int offset = (int) ((scale * availableWidth) + 0.5f);
            final int indicatorPosition = isLayoutRtl() && getMirrorForRtl()
                    ? availableWidth - offset + mPaddingRight : offset + mPaddingLeft;

            final int saveCount = canvas.save();
            canvas.translate(indicatorPosition, getHeight() / 2);
            defaultIndicator.draw(canvas);
            canvas.restoreToCount(saveCount);
        }
    }

    /**
     * N.B. This sets the default *unadjusted* progress, i.e. in the SeekBar's [0 - max] terms.
     */
    public void setDefaultProgress(int defaultProgress) {
        if (mDefaultProgress != defaultProgress) {
            mDefaultProgress = defaultProgress;
            invalidate();
        }
    }

    public int getDefaultProgress() {
        return mDefaultProgress;
    }
}
