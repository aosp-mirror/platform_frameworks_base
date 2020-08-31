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

import static android.car.VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL;
import static android.car.VehiclePropertyIds.HVAC_TEMPERATURE_DISPLAY_UNITS;
import static android.car.VehiclePropertyIds.HVAC_TEMPERATURE_SET;

import static com.android.systemui.car.hvac.HvacController.convertToFahrenheit;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.car.Car;
import android.car.VehicleUnit;
import android.car.hardware.property.CarPropertyManager;
import android.content.Context;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.car.CarServiceProvider;
import com.android.systemui.car.CarSystemUiTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@CarSystemUiTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class TemperatureTextViewTest extends SysuiTestCase {
    private static final float TEMP = 72.0f;
    private final String mFormat = getContext().getString(R.string.hvac_temperature_format);
    private HvacController mHvacController;
    private TemperatureTextView mTextView;

    @Mock
    private Context mContext;

    @Mock
    private Car mCar;
    @Mock
    private CarPropertyManager mCarPropertyManager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mCar.isConnected()).thenReturn(true);
        when(mCar.getCarManager(Car.PROPERTY_SERVICE)).thenReturn(mCarPropertyManager);

        CarServiceProvider carServiceProvider = new CarServiceProvider(mContext, mCar);
        mHvacController = new HvacController(carServiceProvider);
        mHvacController.connectToCarService();
        mTextView = new TemperatureTextView(getContext(), /* attrs= */ null);
    }

    @Test
    public void addTemperatureViewToController_usingTemperatureView_registersView() {
        when(mCarPropertyManager.isPropertyAvailable(eq(HVAC_TEMPERATURE_SET),
                anyInt())).thenReturn(true);
        when(mCarPropertyManager.getFloatProperty(eq(HVAC_TEMPERATURE_SET), anyInt())).thenReturn(
                TEMP);

        mHvacController.addTemperatureViewToController(mTextView);

        assertEquals(mTextView.getText(), String.format(mFormat, TEMP));
    }

    @Test
    public void setTemperatureToFahrenheit_callsViewSetDisplayInFahrenheit() {
        when(mCarPropertyManager.isPropertyAvailable(eq(HVAC_TEMPERATURE_SET),
                anyInt())).thenReturn(true);
        when(mCarPropertyManager.getFloatProperty(eq(HVAC_TEMPERATURE_SET), anyInt())).thenReturn(
                TEMP);
        when(mCarPropertyManager.isPropertyAvailable(HVAC_TEMPERATURE_DISPLAY_UNITS,
                VEHICLE_AREA_TYPE_GLOBAL)).thenReturn(true);
        when(mCarPropertyManager.getIntProperty(HVAC_TEMPERATURE_DISPLAY_UNITS,
                VEHICLE_AREA_TYPE_GLOBAL)).thenReturn(VehicleUnit.FAHRENHEIT);

        mHvacController.addTemperatureViewToController(mTextView);

        assertEquals(mTextView.getText(), String.format(mFormat, convertToFahrenheit(TEMP)));
    }
}
