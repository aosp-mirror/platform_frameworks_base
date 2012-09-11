/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.animation.LayoutTransition;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.android.systemui.R;
import com.android.systemui.statusbar.BaseStatusBar;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.statusbar.policy.LocationController;
import com.android.systemui.statusbar.policy.NetworkController;

public class SettingsPanelView extends PanelView {

    private QuickSettings mQS;
    private QuickSettingsContainerView mQSContainer;

    public SettingsPanelView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mQSContainer = (QuickSettingsContainerView) findViewById(R.id.quick_settings_container);
        mQS = new QuickSettings(getContext(), mQSContainer);
    }

    @Override
    public void setBar(PanelBar panelBar) {
        super.setBar(panelBar);

        if (mQS != null) {
            mQS.setBar(panelBar);
        }
    }

    @Override
    public void setup(NetworkController networkController, BluetoothController bluetoothController,
            BatteryController batteryController, LocationController locationController) {
        super.setup(networkController, bluetoothController, batteryController, locationController);

        if (mQS != null) {
            mQS.setup(networkController, bluetoothController, batteryController,
                    locationController);
        }
    }

    void updateResources() {
        if (mQS != null) {
            mQS.updateResources();
        }
        if (mQSContainer != null) {
            mQSContainer.updateResources();
        }
        requestLayout();
    }

    @Override
    public void fling(float vel, boolean always) {
        ((PhoneStatusBarView) mBar).mBar.getGestureRecorder().tag(
            "fling " + ((vel > 0) ? "open" : "closed"),
            "settings,v=" + vel);
        super.fling(vel, always);
    }
}
