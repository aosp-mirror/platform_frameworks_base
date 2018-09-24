/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;
import com.android.systemui.SysUiServiceProvider;
import com.android.systemui.plugins.statusbar.phone.NavGesture.GestureHelper;

/**
 * TODO: Remove and replace with QuickStepController
 */
public class NavigationBarGestureHelper implements GestureHelper {

    private static final String TAG = "NavigationBarGestureHelper";

    private NavigationBarView mNavigationBarView;

    private final QuickStepController mQuickStepController;
    private final StatusBar mStatusBar;

    public NavigationBarGestureHelper(Context context) {
        mStatusBar = SysUiServiceProvider.getComponent(context, StatusBar.class);
        mQuickStepController = new QuickStepController(context);
    }

    public void setComponents(NavigationBarView navigationBarView) {
        mNavigationBarView = navigationBarView;
        mQuickStepController.setComponents(mNavigationBarView);
    }

    public void setBarState(boolean isVertical, boolean isRTL) {
        mQuickStepController.setBarState(isVertical, isRTL);
    }

    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (!canHandleGestures()) {
            return false;
        }
        return mQuickStepController.onInterceptTouchEvent(event);
    }

    public boolean onTouchEvent(MotionEvent event) {
        if (!canHandleGestures()) {
            return false;
        }
        return mQuickStepController.onTouchEvent(event);
    }

    public void onDraw(Canvas canvas) {
        mQuickStepController.onDraw(canvas);
    }

    public void onLayout(boolean changed, int left, int top, int right, int bottom) {
        mQuickStepController.onLayout(changed, left, top, right, bottom);
    }

    public void onDarkIntensityChange(float intensity) {
        mQuickStepController.onDarkIntensityChange(intensity);
    }

    public void onNavigationButtonLongPress(View v) {
        mQuickStepController.onNavigationButtonLongPress(v);
    }

    private boolean canHandleGestures() {
        return !mStatusBar.isKeyguardShowing();
    }
}
