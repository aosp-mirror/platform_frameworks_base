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
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

import com.android.systemui.R;
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
}
