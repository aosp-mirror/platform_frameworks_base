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

import static com.android.systemui.car.hvac.HvacController.convertToCelsius;
import static com.android.systemui.car.hvac.HvacController.convertToFahrenheit;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.R;

/**
 * Displays temperature with a button to decrease and a button to increase on either side.
 * Properties configured in the XML:
 * hvacAreaId - Example: VehicleSeat.SEAT_ROW_1_LEFT (1)
 */
public class AdjustableTemperatureView extends LinearLayout implements TemperatureView {

    private HvacController mHvacController;
    private float mCurrentTempC;
    private TextView mTempTextView;
    private boolean mDisplayInFahrenheit = false;

    private final float mMinTempC;
    private final float mMaxTempC;
    private final int mAreaId;
    private final String mTempFormat;

    public AdjustableTemperatureView(Context context, AttributeSet attrs) {
        super(context, attrs);
        TypedArray typedArray = context.obtainStyledAttributes(attrs, R.styleable.TemperatureView);
        mAreaId = typedArray.getInt(R.styleable.TemperatureView_hvacAreaId, -1);

        LayoutInflater.from(context).inflate(R.layout.adjustable_temperature_view, /* root= */this);
        mTempFormat = getResources().getString(R.string.hvac_temperature_format);
        mMinTempC = getResources().getFloat(R.dimen.hvac_min_value_celsius);
        mMaxTempC = getResources().getFloat(R.dimen.hvac_max_value_celsius);
        initializeButtons();
    }

    @Override
    public void setHvacController(HvacController controller) {
        mHvacController = controller;
    }

    @Override
    public void setTemperatureView(float tempC) {
        if (tempC > mMaxTempC || tempC < mMinTempC) {
            return;
        }
        if (mTempTextView == null) {
            mTempTextView = findViewById(R.id.hvac_temperature_text);
        }
        mTempTextView.setText(String.format(mTempFormat,
                mDisplayInFahrenheit ? convertToFahrenheit(tempC) : tempC));
        mCurrentTempC = tempC;
    }

    @Override
    public void setDisplayInFahrenheit(boolean displayFahrenheit) {
        mDisplayInFahrenheit = displayFahrenheit;
        setTemperatureView(mCurrentTempC);
    }

    @Override
    public int getAreaId() {
        return mAreaId;
    }

    private void initializeButtons() {
        findViewById(R.id.hvac_decrease_button).setOnClickListener(v -> {
            float newTemp = mDisplayInFahrenheit ? convertToCelsius(
                    convertToFahrenheit(mCurrentTempC) - 1) : (mCurrentTempC - 1);
            setTemperature(newTemp, mAreaId);
        });

        findViewById(R.id.hvac_increase_button).setOnClickListener(v -> {
            float newTemp = mDisplayInFahrenheit ? convertToCelsius(
                    convertToFahrenheit(mCurrentTempC) + 1) : (mCurrentTempC + 1);
            setTemperature(newTemp, mAreaId);
        });
    }

    private void setTemperature(float tempC, int zone) {
        if (tempC < mMaxTempC && tempC > mMinTempC && mHvacController != null) {
            mHvacController.setTemperature(tempC, zone);
        }
    }
}
