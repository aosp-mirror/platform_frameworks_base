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

package android.inputmethodservice;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.annotation.FractionRes;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.LinearLayout;

/**
 * A special purpose layout for the editor extract view for tiny (sub 250dp) screens.
 * The layout is based on sizes proportional to screen pixel size to provide for the
 * best layout fidelity on varying pixel sizes and densities.
 *
 * @hide
 */
public class CompactExtractEditLayout extends LinearLayout {
    private View mInputExtractEditText;
    private View mInputExtractAccessories;
    private View mInputExtractAction;
    private boolean mPerformLayoutChanges;

    public CompactExtractEditLayout(Context context) {
        super(context);
    }

    public CompactExtractEditLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public CompactExtractEditLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mInputExtractEditText = findViewById(com.android.internal.R.id.inputExtractEditText);
        mInputExtractAccessories = findViewById(com.android.internal.R.id.inputExtractAccessories);
        mInputExtractAction = findViewById(com.android.internal.R.id.inputExtractAction);

        if (mInputExtractEditText != null && mInputExtractAccessories != null
                && mInputExtractAction != null) {
            mPerformLayoutChanges = true;
        }
    }

    private int applyFractionInt(@FractionRes int fraction, int whole) {
        return Math.round(getResources().getFraction(fraction, whole, whole));
    }

    private static void setLayoutHeight(View v, int px) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        lp.height = px;
        v.setLayoutParams(lp);
    }

    private static void setLayoutMarginBottom(View v, int px) {
        ViewGroup.MarginLayoutParams lp = (MarginLayoutParams) v.getLayoutParams();
        lp.bottomMargin = px;
        v.setLayoutParams(lp);
    }

    private void applyProportionalLayout(int screenWidthPx, int screenHeightPx) {
        if (getResources().getConfiguration().isScreenRound()) {
            setGravity(Gravity.BOTTOM);
        }
        setLayoutHeight(this, applyFractionInt(
                com.android.internal.R.fraction.input_extract_layout_height, screenHeightPx));

        setPadding(
                applyFractionInt(com.android.internal.R.fraction.input_extract_layout_padding_left,
                        screenWidthPx),
                0,
                applyFractionInt(com.android.internal.R.fraction.input_extract_layout_padding_right,
                        screenWidthPx),
                0);

        setLayoutMarginBottom(mInputExtractEditText,
                applyFractionInt(com.android.internal.R.fraction.input_extract_text_margin_bottom,
                        screenHeightPx));

        setLayoutMarginBottom(mInputExtractAccessories,
                applyFractionInt(com.android.internal.R.fraction.input_extract_action_margin_bottom,
                        screenHeightPx));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mPerformLayoutChanges) {
            Resources res = getResources();
            Configuration cfg = res.getConfiguration();
            DisplayMetrics dm = res.getDisplayMetrics();
            int widthPixels = dm.widthPixels;
            int heightPixels = dm.heightPixels;

            // Percentages must be based on the pixel height of the full (apparent) display height
            // which is sometimes different from display metrics.
            //
            // On a round device, a display height smaller than width indicates a chin (cropped
            // edge of the display) for which there is no screen buffer allocated. This is
            // typically 25-35px in height.
            //
            // getRootWindowInsets() does not function for InputMethod windows (always null).
            // Instead just set height to match width if less. This is safe because round wear
            // devices are by definition 1:1 aspect ratio.

            if (cfg.isScreenRound() && heightPixels < widthPixels) {
                heightPixels = widthPixels;
            }
            applyProportionalLayout(widthPixels, heightPixels);
        }
    }
}
