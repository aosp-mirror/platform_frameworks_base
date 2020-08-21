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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.Car;
import android.car.VehicleUnit;
import android.car.hardware.property.CarPropertyManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.car.CarServiceProvider;
import com.android.systemui.car.CarSystemUiTest;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@CarSystemUiTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class HvacControllerTest extends SysuiTestCase {

    private static final int AREA_ID = 1;
    private static final float TEMP = 72.0f;

    private HvacController mHvacController;

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
        mHvacController = new HvacController(carServiceProvider,
                new FakeExecutor(new FakeSystemClock()));
        mHvacController.connectToCarService();
    }

    @Test
    public void connectToCarService_registersCallback() {
        verify(mCarPropertyManager).registerCallback(any(), eq(HVAC_TEMPERATURE_SET), anyFloat());
        verify(mCarPropertyManager).registerCallback(any(), eq(HVAC_TEMPERATURE_DISPLAY_UNITS),
                anyFloat());
    }

    @Test
    public void addTemperatureViewToController_usingTemperatureView_registersView() {
        TemperatureTextView v = setupMockTemperatureTextView(AREA_ID, TEMP);
        mHvacController.addTemperatureViewToController(v);

        verify(v).setTemp(TEMP);
    }

    @Test
    public void addTemperatureViewToController_usingSameTemperatureView_registersFirstView() {
        TemperatureTextView v = setupMockTemperatureTextView(AREA_ID, TEMP);
        mHvacController.addTemperatureViewToController(v);
        verify(v).setTemp(TEMP);
        resetTemperatureView(v, AREA_ID);

        mHvacController.addTemperatureViewToController(v);
        verify(v, never()).setTemp(TEMP);
    }

    @Test
    public void addTemperatureViewToController_usingDifferentTemperatureView_registersBothViews() {
        TemperatureTextView v1 = setupMockTemperatureTextView(AREA_ID, TEMP);
        mHvacController.addTemperatureViewToController(v1);
        verify(v1).setTemp(TEMP);

        TemperatureTextView v2 = setupMockTemperatureTextView(
                AREA_ID + 1,
                TEMP + 1);
        mHvacController.addTemperatureViewToController(v2);
        verify(v2).setTemp(TEMP + 1);
    }

    @Test
    public void setTemperatureToFahrenheit_callsViewSetDisplayInFahrenheit() {
        when(mCarPropertyManager.isPropertyAvailable(HVAC_TEMPERATURE_DISPLAY_UNITS,
                VEHICLE_AREA_TYPE_GLOBAL)).thenReturn(true);
        when(mCarPropertyManager.getIntProperty(HVAC_TEMPERATURE_DISPLAY_UNITS,
                VEHICLE_AREA_TYPE_GLOBAL)).thenReturn(VehicleUnit.FAHRENHEIT);
        TemperatureTextView v = setupMockTemperatureTextView(AREA_ID, TEMP);

        mHvacController.addTemperatureViewToController(v);

        verify(v).setDisplayInFahrenheit(true);
        verify(v).setTemp(TEMP);
    }

    private TemperatureTextView setupMockTemperatureTextView(int areaId, float value) {
        TemperatureTextView v = mock(TemperatureTextView.class);
        resetTemperatureView(v, areaId);
        when(mCarPropertyManager.isPropertyAvailable(HVAC_TEMPERATURE_SET, areaId)).thenReturn(
                true);
        when(mCarPropertyManager.getFloatProperty(HVAC_TEMPERATURE_SET, areaId)).thenReturn(value);
        return v;
    }

    private void resetTemperatureView(TemperatureTextView view, int areaId) {
        reset(view);
        when(view.getAreaId()).thenReturn(areaId);
    }
}
