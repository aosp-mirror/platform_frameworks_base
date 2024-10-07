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

package com.android.systemui.accessibility;

import android.annotation.NonNull;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.IBinder;
import android.view.Display;
import android.view.KeyboardShortcutGroup;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;

public class TestableWindowManager implements WindowManager {

    private final WindowManager mWindowManager;
    private View mView;
    private Rect mWindowBounds = null;
    private WindowInsets mWindowInsets = null;

    TestableWindowManager(WindowManager windowManager) {
        mWindowManager = windowManager;
    }

    @Override
    public Display getDefaultDisplay() {
        return mWindowManager.getDefaultDisplay();
    }

    @Override
    public void removeViewImmediate(View view) {
        mWindowManager.removeViewImmediate(view);
    }

    @Override
    public void requestAppKeyboardShortcuts(WindowManager.KeyboardShortcutsReceiver receiver,
            int deviceId) {
        mWindowManager.requestAppKeyboardShortcuts(receiver, deviceId);
    }

    @Override
    public KeyboardShortcutGroup getApplicationLaunchKeyboardShortcuts(int deviceId) {
        return mWindowManager.getApplicationLaunchKeyboardShortcuts(deviceId);
    }

    @Override
    public Region getCurrentImeTouchRegion() {
        return mWindowManager.getCurrentImeTouchRegion();
    }

    @Override
    public void addView(View view, ViewGroup.LayoutParams params) {
        mView = view;
        mWindowManager.addView(view, params);
    }

    @Override
    public void updateViewLayout(View view, ViewGroup.LayoutParams params) {
        mWindowManager.updateViewLayout(view, params);
    }

    @Override
    public void removeView(View view) {
        mView = null;
        mWindowManager.removeView(view);
    }

    @Override
    public WindowMetrics getCurrentWindowMetrics() {
        final WindowMetrics realMetrics = mWindowManager.getCurrentWindowMetrics();
        final WindowMetrics windowMetrics = new WindowMetrics(
                mWindowBounds == null ? realMetrics.getBounds()
                        : mWindowBounds,
                mWindowInsets == null ?  realMetrics.getWindowInsets() : mWindowInsets);
        return windowMetrics;
    }

    @Override
    public WindowMetrics getMaximumWindowMetrics() {
        return mWindowManager.getMaximumWindowMetrics();
    }

    @Override
    public @NonNull IBinder getDefaultToken() {
        return mWindowManager.getDefaultToken();
    }

    public View getAttachedView() {
        return mView;
    }

    public WindowManager.LayoutParams getLayoutParamsFromAttachedView() {
        if (mView == null) {
            return null;
        }
        return (WindowManager.LayoutParams) mView.getLayoutParams();
    }

    /**
     * Sets the given window bounds to current window metrics.
     *
     * @param bounds the window bounds
     */
    public void setWindowBounds(Rect bounds) {
        mWindowBounds = bounds;
    }

    /**
     * Sets the given window insets to the current window metrics.
     *
     * @param insets the window insets.
     */
    public void setWindowInsets(WindowInsets insets) {
        mWindowInsets = insets;
    }
}
