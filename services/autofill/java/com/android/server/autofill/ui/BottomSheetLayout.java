/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.autofill.ui;

import static com.android.server.autofill.Helper.sDebug;

import android.annotation.Nullable;
import android.content.Context;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.widget.LinearLayout;

import com.android.internal.R;

/**
 {@link LinearLayout} that displays content of save dialog.
 */
public class BottomSheetLayout extends LinearLayout {

    private static final String TAG = "BottomSheetLayout";

    public BottomSheetLayout(Context context) {
        super(context);
    }

    public BottomSheetLayout(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public BottomSheetLayout(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public void onMeasure(int widthSpec, int heightSpec) {
        if (getContext() == null || getContext().getResources() == null) {
            super.onMeasure(widthSpec, heightSpec);
            Slog.w(TAG, "onMeasure failed due to missing context or missing resources.");
            return;
        }

        if (getChildCount() == 0) {
            // Should not happen
            super.onMeasure(widthSpec, heightSpec);
            Slog.wtf(TAG, "onMeasure failed due to missing children views.");
            return;
        }

        DisplayMetrics displayMetrics = getContext().getResources().getDisplayMetrics();

        final int pxOffset = getContext().getResources().getDimensionPixelSize(
                R.dimen.autofill_dialog_offset);
        final int outerMargin = getContext().getResources().getDimensionPixelSize(
                R.dimen.autofill_save_outer_margin);

        final boolean includeHorizontalSpace =
                getContext().getResources().getBoolean(
                        R.bool.autofill_dialog_horizontal_space_included);


        final int screenHeight = displayMetrics.heightPixels;
        final int screenWidth = displayMetrics.widthPixels;

        final int maxHeight = screenHeight - pxOffset - outerMargin;

        int maxWidth = screenWidth;

        if (includeHorizontalSpace) {
            maxWidth -= 2 * pxOffset;
        }

        maxWidth =
                Math.min(maxWidth, getContext().getResources().getDimensionPixelSize(
                        R.dimen.autofill_dialog_max_width));

        super.onMeasure(MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST));


        if (sDebug) {
            Slog.d(TAG, "onMeasure() values in dp:"
                    + " screenHeight: " + screenHeight / displayMetrics.density + ", screenWidth: "
                    + screenWidth / displayMetrics.density
                    + ", maxHeight: " + maxHeight / displayMetrics.density
                    + ", maxWidth: " + maxWidth / displayMetrics.density + ", getMeasuredWidth(): "
                    + getMeasuredWidth() / displayMetrics.density + ", getMeasuredHeight(): "
                    + getMeasuredHeight() / displayMetrics.density);
        }
        setMeasuredDimension(getMeasuredWidth(), getMeasuredHeight());
    }


}
