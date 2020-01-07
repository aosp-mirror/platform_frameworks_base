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

import android.annotation.MainThread;
import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.ImageView;

import com.android.systemui.R;

import javax.inject.Singleton;

/**
 * Class to control magnification mode switch button. Shows the button UI when both full-screen
 * and window magnification mode are capable, and when the magnification scale is changed. And
 * the button UI would automatically be dismissed after displaying for a period of time.
 */
@Singleton
public class ModeSwitchesController {

    private static final String TAG = "ModeSwitchesController";

    private static final int DURATION_MS = 5000;
    private static final int START_DELAY_MS = 3000;

    private final Context mContext;
    private final DisplayManager mDisplayManager;

    private final SparseArray<MagnificationSwitchController> mDisplaysToSwitches =
            new SparseArray<>();
    private final WindowManager.LayoutParams mParams;
    private final int mPadding;

    ModeSwitchesController(Context context) {
        mContext = context;
        mDisplayManager = mContext.getSystemService(DisplayManager.class);

        // Initialize view param and dimen.
        mPadding = context.getResources().getDimensionPixelSize(
                R.dimen.magnification_switch_button_padding);
        mParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSPARENT);
        mParams.gravity = Gravity.BOTTOM | Gravity.RIGHT;
    }

    /**
     * Shows a button that a user can click the button to switch magnification mode. And the
     * button would be dismissed automatically after the button is displayed for a period of time.
     *
     * @param displayId The logical display id
     * @param mode      The magnification mode
     * @see Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW
     * @see Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN
     */
    @MainThread
    void showButton(int displayId, int mode) {
        if (mDisplaysToSwitches.get(displayId) == null) {
            final MagnificationSwitchController magnificationSwitchController =
                    createMagnificationSwitchController(displayId);
            if (magnificationSwitchController == null) {
                return;
            }
        }
        mDisplaysToSwitches.get(displayId).showButton(mode);
    }

    /**
     * Removes magnification mode switch button immediately.
     *
     * @param displayId The logical display id
     */
    void removeButton(int displayId) {
        if (mDisplaysToSwitches.get(displayId) == null) {
            return;
        }
        mDisplaysToSwitches.get(displayId).removeButton();
    }

    private MagnificationSwitchController createMagnificationSwitchController(int displayId) {
        if (mDisplayManager.getDisplay(displayId) == null) {
            Log.w(TAG, "createMagnificationSwitchController displayId is invalid.");
            return null;
        }
        final MagnificationSwitchController
                magnificationSwitchController = new MagnificationSwitchController(
                getDisplayContext(displayId));
        mDisplaysToSwitches.put(displayId, magnificationSwitchController);
        return magnificationSwitchController;
    }

    private Context getDisplayContext(int displayId) {
        final Display display = mDisplayManager.getDisplay(displayId);
        final Context context = (displayId == Display.DEFAULT_DISPLAY)
                ? mContext
                : mContext.createDisplayContext(display);
        return context;
    }

    private class MagnificationSwitchController {

        private final Context mContext;
        private final WindowManager mWindowManager;
        private ImageView mImageView;
        private int mMagnificationMode;

        MagnificationSwitchController(Context context) {
            mContext = context;
            mWindowManager = (WindowManager) mContext.getSystemService(
                    Context.WINDOW_SERVICE);
        }

        void removeButton() {
            if (mImageView == null) {
                return;
            }
            mWindowManager.removeView(mImageView);
            mImageView = null;
        }

        void showButton(int mode) {
            if (mImageView == null) {
                createView();
                mWindowManager.addView(mImageView, mParams);
            } else if (mMagnificationMode != mode) {
                // TODO(b/145780606): wait for designer provide icon asset for window mode.
                final int resId = R.drawable.ic_open_in_new_fullscreen;
                mImageView.setImageResource(resId);
            }
            mMagnificationMode = mode;

            // TODO(b/143852371): use accessibility timeout as a delay.
            // Dismiss the magnification switch button after the button is displayed for a period of
            // time.
            mImageView.animate().cancel();
            mImageView
                    .animate()
                    .alpha(0f)
                    .setStartDelay(START_DELAY_MS)
                    .setDuration(DURATION_MS)
                    .withEndAction(
                            () -> removeButton());
        }

        private void createView() {
            // TODO(b/145780606): wait for designer provide icon asset for window mode.
            final int resId = R.drawable.ic_open_in_new_fullscreen;
            mImageView = new ImageView(mContext);
            mImageView.setImageResource(resId);
            mImageView.setClickable(true);
            mImageView.setFocusable(true);
            mImageView.setPadding(mPadding, mPadding, mPadding, mPadding);
            mImageView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            // TODO(b/145780606): switch magnification mode between full-screen and window by
            //  clicking the button.
            mImageView.setOnClickListener(
                    view -> {
                        if (view != null) {
                            view.animate().cancel();
                            removeButton();
                            toggleMagnificationMode();
                        }
                    });
        }

        private void toggleMagnificationMode() {
            final int newMode =
                    mMagnificationMode ^ Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_ALL;
            mMagnificationMode = newMode;
            Settings.Secure.putInt(mContext.getContentResolver(),
                    Settings.Secure.WINDOW_MAGNIFICATION, newMode);
        }
    }
}
