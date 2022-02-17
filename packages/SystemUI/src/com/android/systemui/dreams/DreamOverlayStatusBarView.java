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

package com.android.systemui.dreams;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.android.internal.util.Preconditions;
import com.android.systemui.R;
import com.android.systemui.battery.BatteryMeterView;
import com.android.systemui.statusbar.policy.BatteryController.BatteryStateChangeCallback;

/**
 * {@link DreamOverlayStatusBarView} is the view responsible for displaying the status bar in a
 * dream. The status bar includes status icons such as battery and wifi.
 */
public class DreamOverlayStatusBarView extends ConstraintLayout implements
        BatteryStateChangeCallback {

    private BatteryMeterView mBatteryView;
    private ImageView mWifiStatusView;

    public DreamOverlayStatusBarView(Context context) {
        this(context, null);
    }

    public DreamOverlayStatusBarView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DreamOverlayStatusBarView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public DreamOverlayStatusBarView(
            Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mBatteryView = Preconditions.checkNotNull(findViewById(R.id.dream_overlay_battery),
                "R.id.dream_overlay_battery must not be null");
        mWifiStatusView = Preconditions.checkNotNull(findViewById(R.id.dream_overlay_wifi_status),
                "R.id.dream_overlay_wifi_status must not be null");

        mWifiStatusView.setImageDrawable(getContext().getDrawable(R.drawable.ic_signal_wifi_off));
    }

    /**
     * Whether to show the battery percent text next to the battery status icons.
     * @param show True if the battery percent text should be shown.
     */
    void showBatteryPercentText(boolean show) {
        mBatteryView.setForceShowPercent(show);
    }

    /**
     * Whether to show the wifi status icon.
     * @param show True if the wifi status icon should be shown.
     */
    void showWifiStatus(boolean show) {
        // Only show the wifi status icon when wifi isn't available.
        mWifiStatusView.setVisibility(show ? View.VISIBLE : View.GONE);
    }
}
