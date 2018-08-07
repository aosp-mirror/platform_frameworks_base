/*
 * Copyright 2018 The Android Open Source Project
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

package com.android.systemui.statusbar.car.hvac;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.widget.TextView;

import com.android.systemui.Dependency;
import com.android.systemui.R;

/**
 * Simple text display of HVAC properties, It is designed to show temperature and is configured in
 * the XML.
 * XML properties:
 * hvacPropertyId - Example: CarHvacManager.ID_ZONED_TEMP_SETPOINT (16385)
 * hvacAreaId - Example: VehicleSeat.SEAT_ROW_1_LEFT (1)
 * hvacTempFormat - Example: "%.1f\u00B0" (1 decimal and the degree symbol)
 *
 * Note: It registers itself with {@link HvacController}
 */
public class TemperatureTextView extends TextView implements TemperatureView {

    private final int mAreaId;
    private final int mPropertyId;
    private final String mTempFormat;

    public TemperatureTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        TypedArray typedArray = context.obtainStyledAttributes(attrs, R.styleable.TemperatureView);
        mAreaId = typedArray.getInt(R.styleable.TemperatureView_hvacAreaId,-1);
        mPropertyId = typedArray.getInt(R.styleable.TemperatureView_hvacPropertyId, -1);
        String format = typedArray.getString(R.styleable.TemperatureView_hvacTempFormat);
        mTempFormat = (format == null) ? "%.1f\u00B0" : format;

        // register with controller
        HvacController hvacController = Dependency.get(HvacController.class);
        hvacController.addHvacTextView(this);
    }

    /**
     * Formats the float for display
     * @param temp - The current temp or NaN
     */
    @Override
    public void setTemp(float temp) {
        if (Float.isNaN(temp)) {
            setText("--");
            return;
        }
        setText(String.format(mTempFormat, temp));
    }

    /**
     * @return propertiyId  Example: CarHvacManager.ID_ZONED_TEMP_SETPOINT (16385)
     */
    @Override
    public int getPropertyId() {
        return mPropertyId;
    }

    /**
     * @return hvac AreaId - Example: VehicleSeat.SEAT_ROW_1_LEFT (1)
     */
    @Override
    public int getAreaId() {
        return mAreaId;
    }
}

