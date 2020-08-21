/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.car.hvac;

import static com.android.systemui.car.hvac.HvacController.convertToFahrenheit;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.widget.TextView;

import com.android.systemui.R;

/**
 * Simple text display of HVAC properties, It is designed to show temperature and is configured in
 * the XML.
 * XML properties:
 * hvacAreaId - Example: VehicleAreaSeat.SEAT_ROW_1_LEFT (1)
 */
public class TemperatureTextView extends TextView implements TemperatureView {

    private final int mAreaId;
    private final String mTempFormat;
    private HvacController mHvacController;
    private boolean mDisplayFahrenheit = false;

    public TemperatureTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        TypedArray typedArray = context.obtainStyledAttributes(attrs, R.styleable.TemperatureView);
        mAreaId = typedArray.getInt(R.styleable.TemperatureView_hvacAreaId, -1);
        mTempFormat = getResources().getString(R.string.hvac_temperature_format);
    }

    @Override
    public void setHvacController(HvacController controller) {
        mHvacController = controller;
    }

    /**
     * Formats the float for display
     *
     * @param temp - The current temp or NaN
     */
    @Override
    public void setTemperatureView(float temp) {
        if (Float.isNaN(temp)) {
            setText("--");
            return;
        }
        if (mDisplayFahrenheit) {
            temp = convertToFahrenheit(temp);
        }
        setText(String.format(mTempFormat, temp));
    }

    @Override
    public void setDisplayInFahrenheit(boolean displayFahrenheit) {
        mDisplayFahrenheit = displayFahrenheit;
    }

    /**
     * @return hvac AreaId - Example: VehicleAreaSeat.SEAT_ROW_1_LEFT (1)
     */
    @Override
    public int getAreaId() {
        return mAreaId;
    }
}

