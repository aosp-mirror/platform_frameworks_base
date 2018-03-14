/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.plugins.statusbar.phone;

import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;

import com.android.systemui.plugins.Plugin;
import com.android.systemui.plugins.annotations.ProvidesInterface;

@ProvidesInterface(action = NavGesture.ACTION, version = NavGesture.VERSION)
public interface NavGesture extends Plugin {

    public static final String ACTION = "com.android.systemui.action.PLUGIN_NAV_GESTURE";

    public static final int VERSION = 1;

    public GestureHelper getGestureHelper();

    public interface GestureHelper {
        public boolean onTouchEvent(MotionEvent event);

        public boolean onInterceptTouchEvent(MotionEvent event);

        public void setBarState(boolean vertical, boolean isRtl);

        public void onDraw(Canvas canvas);

        public void onDarkIntensityChange(float intensity);

        public void onLayout(boolean changed, int left, int top, int right, int bottom);

        public void onNavigationButtonLongPress(View v);

        public default void destroy() { }
    }

}
