/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.accessibility;

import android.content.Context;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.ImageView;

import com.android.systemui.R;

/**
 * Shows/hides a {@link android.widget.ImageView} on the screen and changes the values of
 * {@link Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE} when the UI is toggled.
 */
class MagnificationModeSwitch {

    private static final int DURATION_MS = 5000;
    private static final int START_DELAY_MS = 3000;

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final ImageView mImageView;
    private int mMagnificationMode = Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN;
    private final WindowManager.LayoutParams mParams;
    private boolean mIsVisible = false;

    MagnificationModeSwitch(Context context) {
        mContext = context;
        mWindowManager = (WindowManager) mContext.getSystemService(
                Context.WINDOW_SERVICE);
        mParams = createLayoutParams();
        mImageView = createView(mContext, mMagnificationMode);
        mImageView.setOnClickListener(
                view -> {
                    removeButton();
                    toggleMagnificationMode();
                });
    }

    void removeButton() {
        mImageView.animate().cancel();
        if (!mIsVisible) {
            return;
        }
        mWindowManager.removeView(mImageView);
        mIsVisible = false;
    }

    void showButton(int mode) {
        if (mMagnificationMode != mode) {
            mMagnificationMode = mode;
            mImageView.setImageResource(getIconResId(mode));
        }
        if (!mIsVisible) {
            mWindowManager.addView(mImageView, mParams);
            mIsVisible = true;
        }

        // TODO(b/143852371): use accessibility timeout as a delay.
        // Dismiss the magnification switch button after the button is displayed for a period of
        // time.
        mImageView.animate().cancel();
        mImageView.animate()
                .alpha(0f)
                .setStartDelay(START_DELAY_MS)
                .setDuration(DURATION_MS)
                .withEndAction(
                        () -> removeButton());
    }

    private void toggleMagnificationMode() {
        final int newMode =
                mMagnificationMode ^ Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_ALL;
        mMagnificationMode = newMode;
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE, newMode);
    }

    private static ImageView createView(Context context, int mode) {
        final int padding = context.getResources().getDimensionPixelSize(
                R.dimen.magnification_switch_button_padding);
        ImageView imageView  = new ImageView(context);
        imageView.setImageResource(getIconResId(mode));
        imageView.setClickable(true);
        imageView.setFocusable(true);
        imageView.setPadding(padding, padding, padding, padding);
        imageView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        return imageView;
    }

    private static int getIconResId(int mode) {
        return (mode == Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN)
                ? R.drawable.ic_open_in_new_window
                : R.drawable.ic_open_in_new_fullscreen;
    }

    private static WindowManager.LayoutParams createLayoutParams() {
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSPARENT);
        params.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        return params;
    }
}
