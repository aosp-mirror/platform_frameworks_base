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

package com.android.systemui.accessibility.accessibilitymenu;

import android.accessibilityservice.AccessibilityService;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;

import com.android.systemui.accessibility.accessibilitymenu.view.A11yMenuOverlayLayout;

/** @hide */
public class AccessibilityMenuService extends AccessibilityService implements View.OnTouchListener {
    private static final String TAG = "A11yMenuService";

    private static final long BUFFER_MILLISECONDS_TO_PREVENT_UPDATE_FAILURE = 100L;

    private A11yMenuOverlayLayout mA11yMenuLayout;

    private static boolean sInitialized = false;

    // TODO(b/136716947): Support multi-display once a11y framework side is ready.
    private DisplayManager mDisplayManager;
    final DisplayManager.DisplayListener mDisplayListener = new DisplayManager.DisplayListener() {
        int mRotation;

        @Override
        public void onDisplayAdded(int displayId) {}

        @Override
        public void onDisplayRemoved(int displayId) {
            // TODO(b/136716947): Need to reset A11yMenuOverlayLayout by display id.
        }

        @Override
        public void onDisplayChanged(int displayId) {
            Display display = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
            if (mRotation != display.getRotation()) {
                mRotation = display.getRotation();
                mA11yMenuLayout.updateViewLayout();
            }
        }
    };

    // Update layout.
    private final Handler mHandler = new Handler(getMainLooper());
    private final Runnable mOnConfigChangedRunnable = new Runnable() {
        @Override
        public void run() {
            if (!sInitialized) {
                return;
            }
            // Re-assign theme to service after onConfigurationChanged
            getTheme().applyStyle(R.style.ServiceTheme, true);
            // Caches & updates the page index to ViewPager when a11y menu is refreshed.
            // Otherwise, the menu page would reset on a UI update.
            int cachedPageIndex = mA11yMenuLayout.getPageIndex();
            mA11yMenuLayout.configureLayout(cachedPageIndex);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        if (mHandler.hasCallbacks(mOnConfigChangedRunnable)) {
            mHandler.removeCallbacks(mOnConfigChangedRunnable);
        }

        super.onDestroy();
    }

    @Override
    protected void onServiceConnected() {
        mA11yMenuLayout = new A11yMenuOverlayLayout(this);

        // Temporary measure to force visibility
        mA11yMenuLayout.toggleVisibility();

        mDisplayManager = getSystemService(DisplayManager.class);
        mDisplayManager.registerDisplayListener(mDisplayListener, null);

        sInitialized = true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    /**
     * This method would notify service when device configuration, such as display size,
     * localization, orientation or theme, is changed.
     *
     * @param newConfig the new device configuration.
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        // Prevent update layout failure
        // if multiple onConfigurationChanged are called at the same time.
        if (mHandler.hasCallbacks(mOnConfigChangedRunnable)) {
            mHandler.removeCallbacks(mOnConfigChangedRunnable);
        }
        mHandler.postDelayed(
                mOnConfigChangedRunnable, BUFFER_MILLISECONDS_TO_PREVENT_UPDATE_FAILURE);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        return false;
    }
}
