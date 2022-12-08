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
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;

import com.android.systemui.accessibility.accessibilitymenu.view.A11yMenuOverlayLayout;

/** @hide */
public class AccessibilityMenuService extends AccessibilityService implements View.OnTouchListener {
    private static final String TAG = "A11yMenuService";

    private A11yMenuOverlayLayout mA11yMenuLayout;

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    protected void onServiceConnected() {
        mA11yMenuLayout = new A11yMenuOverlayLayout(this);
        super.onServiceConnected();
        mA11yMenuLayout.toggleVisibility();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        return false;
    }
}
