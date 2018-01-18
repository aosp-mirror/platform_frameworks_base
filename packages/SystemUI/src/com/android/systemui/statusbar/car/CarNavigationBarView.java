/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.statusbar.car;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

import com.android.systemui.R;
import com.android.systemui.plugins.statusbar.phone.NavGesture;
import com.android.systemui.statusbar.phone.NavigationBarGestureHelper;
import com.android.systemui.statusbar.phone.NavigationBarView;

/**
 * A custom navigation bar for the automotive use case.
 * <p>
 * The navigation bar in the automotive use case is more like a list of shortcuts, rendered
 * in a linear layout.
 */
class CarNavigationBarView extends NavigationBarView {
    private LinearLayout mNavButtons;
    private LinearLayout mLightsOutButtons;

    public CarNavigationBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void onFinishInflate() {
        mNavButtons = findViewById(R.id.nav_buttons);
        mLightsOutButtons = findViewById(R.id.lights_out);
    }

    public void addButton(CarNavigationButton button, CarNavigationButton lightsOutButton){
        mNavButtons.addView(button);
        mLightsOutButtons.addView(lightsOutButton);
    }

    @Override
    public void setDisabledFlags(int disabledFlags, boolean force) {
        // TODO: Populate.
    }

    @Override
    public void reorient() {
        // We expect the car head unit to always have a fixed rotation so we ignore this. The super
        // class implentation expects mRotatedViews to be populated, so if you call into it, there
        // is a possibility of a NullPointerException.
    }

    @Override
    public View getCurrentView() {
        return this;
    }

    @Override
    public void setNavigationIconHints(int hints, boolean force) {
        // We do not need to set the navigation icon hints for a vehicle
        // Calling setNavigationIconHints in the base class will result in a NPE as the car
        // navigation bar does not have a back button.
    }

    @Override
    public void onPluginConnected(NavGesture plugin, Context context) {
        // set to null version of the plugin ignoring incoming arg.
        super.onPluginConnected(new NullNavGesture(), context);
    }

    @Override
    public void onPluginDisconnected(NavGesture plugin) {
        // reinstall the null nav gesture plugin
        super.onPluginConnected(new NullNavGesture(), getContext());
    }

    /**
     * Null object pattern to work around expectations of the base class.
     * This is a temporary solution to have the car system ui working.
     * Already underway is a refactor of they car sys ui as to not use this class
     * hierarchy.
     */
    private static class NullNavGesture implements NavGesture {
        @Override
        public GestureHelper getGestureHelper() {
            return new GestureHelper() {
                @Override
                public boolean onTouchEvent(MotionEvent event) {
                    return false;
                }

                @Override
                public boolean onInterceptTouchEvent(MotionEvent event) {
                    return false;
                }

                @Override
                public void setBarState(boolean vertical, boolean isRtl) {
                }

                @Override
                public void onDraw(Canvas canvas) {
                }

                @Override
                public void onDarkIntensityChange(float intensity) {
                }

                @Override
                public void onLayout(boolean changed, int left, int top, int right, int bottom) {
                }
            };
        }

        @Override
        public int getVersion() {
            return 0;
        }

        @Override
        public void onCreate(Context sysuiContext, Context pluginContext) {
        }

        @Override
        public void onDestroy() {
        }
    }
}
