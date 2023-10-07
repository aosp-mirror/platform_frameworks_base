/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.server.input.debug;

import static android.util.TypedValue.COMPLEX_UNIT_SP;

import android.content.Context;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Typeface;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.ViewConfiguration;
import android.widget.TextView;

import com.android.internal.R;

import java.util.Locale;

/**
 * Draws the most recent rotary input value and indicates whether the source is active.
 */
public class RotaryInputValueView extends TextView {

    private static final int INACTIVE_TEXT_COLOR = 0xffff00ff;
    private static final int ACTIVE_TEXT_COLOR = 0xff420f28;
    private static final int TEXT_SIZE_SP = 8;
    private static final int SIDE_PADDING_SP = 4;
    /** Determines how long the active status lasts. */
    private static final int ACTIVE_STATUS_DURATION = 250 /* milliseconds */;
    private static final ColorFilter ACTIVE_BACKGROUND_FILTER =
            new ColorMatrixColorFilter(new float[]{
                    0, 0, 0, 0, 255, // red
                    0, 0, 0, 0,   0, // green
                    0, 0, 0, 0, 255, // blue
                    0, 0, 0, 0, 200  // alpha
            });

    private final Runnable mUpdateActivityStatusCallback = () -> updateActivityStatus(false);
    private final float mScaledVerticalScrollFactor;
    private final Locale mDefaultLocale = Locale.getDefault();

    public RotaryInputValueView(Context c) {
        super(c);

        DisplayMetrics dm = mContext.getResources().getDisplayMetrics();
        mScaledVerticalScrollFactor = ViewConfiguration.get(c).getScaledVerticalScrollFactor();

        setText(getFormattedValue(0));
        setTextColor(INACTIVE_TEXT_COLOR);
        setTextSize(applyDimensionSp(TEXT_SIZE_SP, dm));
        setPaddingRelative(applyDimensionSp(SIDE_PADDING_SP, dm), 0,
                applyDimensionSp(SIDE_PADDING_SP, dm), 0);
        setTypeface(null, Typeface.BOLD);
        setBackgroundResource(R.drawable.focus_event_rotary_input_background);
    }

    /** Updates the shown text with the formatted value. */
    public void updateValue(float value) {
        removeCallbacks(mUpdateActivityStatusCallback);

        setText(getFormattedValue(value * mScaledVerticalScrollFactor));

        updateActivityStatus(true);
        postDelayed(mUpdateActivityStatusCallback, ACTIVE_STATUS_DURATION);
    }

    /** Updates whether or not there's active rotary input. */
    public void updateActivityStatus(boolean active) {
        if (active) {
            setTextColor(ACTIVE_TEXT_COLOR);
            getBackground().setColorFilter(ACTIVE_BACKGROUND_FILTER);
        } else {
            setTextColor(INACTIVE_TEXT_COLOR);
            getBackground().clearColorFilter();
        }
    }

    private String getFormattedValue(float value) {
        return String.format(mDefaultLocale, "%s%.1f", value < 0 ? "-" : "+", Math.abs(value));
    }

    /**
     * Converts a dimension in scaled pixel units to integer display pixels.
     */
    private static int applyDimensionSp(int dimensionSp, DisplayMetrics dm) {
        return (int) TypedValue.applyDimension(COMPLEX_UNIT_SP, dimensionSp, dm);
    }
}
