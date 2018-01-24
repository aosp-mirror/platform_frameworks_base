/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.charging;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.android.systemui.R;

import java.text.NumberFormat;

/**
 * @hide
 */
public class WirelessChargingLayout extends FrameLayout {
    private final static int UNKNOWN_BATTERY_LEVEL = -1;

    public WirelessChargingLayout(Context context) {
        super(context);
        init(context, null);
    }

    public WirelessChargingLayout(Context context, int batterylLevel) {
        super(context);
        init(context, null, batterylLevel);
    }

    public WirelessChargingLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    private void init(Context c, AttributeSet attrs) {
        init(c, attrs, -1);
    }

    private void init(Context c, AttributeSet attrs, int batteryLevel) {
        final int mBatteryLevel = batteryLevel;

        inflate(c, R.layout.wireless_charging_layout, this);

        // where the circle animation occurs:
        final WirelessChargingView mChargingView = findViewById(R.id.wireless_charging_view);

        // amount of battery:
        final TextView mPercentage = findViewById(R.id.wireless_charging_percentage);

        // (optional) time until full charge if available
        final TextView mSecondaryText = findViewById(R.id.wireless_charging_secondary_text);

        if (batteryLevel != UNKNOWN_BATTERY_LEVEL) {
            mPercentage.setText(NumberFormat.getPercentInstance().format(mBatteryLevel / 100f));

            ValueAnimator animator = ObjectAnimator.ofFloat(mPercentage, "textSize",
                    getContext().getResources().getFloat(R.dimen.config_batteryLevelTextSizeStart),
                    getContext().getResources().getFloat(R.dimen.config_batteryLevelTextSizeEnd));

            animator.setDuration((long) getContext().getResources().getInteger(
                    R.integer.config_batteryLevelTextAnimationDuration));
            animator.start();
        }
    }
}
