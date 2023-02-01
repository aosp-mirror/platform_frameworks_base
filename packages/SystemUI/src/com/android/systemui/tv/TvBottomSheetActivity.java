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

package com.android.systemui.tv;

import android.app.Activity;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import com.android.systemui.R;

import java.util.Collections;
import java.util.function.Consumer;

/**
 * Generic bottom sheet with up to two icons in the beginning and two buttons.
 */
public abstract class TvBottomSheetActivity extends Activity {

    private static final String TAG = TvBottomSheetActivity.class.getSimpleName();
    private Drawable mBackgroundWithBlur;
    private Drawable mBackgroundWithoutBlur;

    private final Consumer<Boolean> mBlurConsumer = this::onBlurChanged;

    private void onBlurChanged(boolean enabled) {
        Log.v(TAG, "blur enabled: " + enabled);
        getWindow().setBackgroundDrawable(enabled ? mBackgroundWithBlur : mBackgroundWithoutBlur);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.tv_bottom_sheet);

        overridePendingTransition(R.anim.tv_bottom_sheet_enter, 0);

        mBackgroundWithBlur = getResources()
                .getDrawable(R.drawable.bottom_sheet_background_with_blur);
        mBackgroundWithoutBlur = getResources().getDrawable(R.drawable.bottom_sheet_background);

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int screenWidth = metrics.widthPixels;
        int screenHeight = metrics.heightPixels;
        int marginPx = getResources().getDimensionPixelSize(R.dimen.bottom_sheet_margin);

        WindowManager.LayoutParams windowParams = getWindow().getAttributes();
        windowParams.width = screenWidth - marginPx * 2;
        windowParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
        windowParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        windowParams.horizontalMargin = 0f;
        windowParams.verticalMargin = (float) marginPx / screenHeight;
        windowParams.format = PixelFormat.TRANSPARENT;
        windowParams.type = WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG;
        windowParams.flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        windowParams.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        getWindow().setAttributes(windowParams);
        getWindow().setElevation(getWindow().getElevation() + 5);
        getWindow().setBackgroundBlurRadius(getResources().getDimensionPixelSize(
                R.dimen.bottom_sheet_background_blur_radius));

        final View rootView = findViewById(R.id.bottom_sheet);
        rootView.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            rootView.setUnrestrictedPreferKeepClearRects(
                    Collections.singletonList(new Rect(0, 0, r - l, b - t)));
        });
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        getWindowManager().addCrossWindowBlurEnabledListener(mBlurConsumer);
    }

    @Override
    public void onDetachedFromWindow() {
        getWindowManager().removeCrossWindowBlurEnabledListener(mBlurConsumer);
        super.onDetachedFromWindow();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, R.anim.tv_bottom_sheet_exit);
    }

}
