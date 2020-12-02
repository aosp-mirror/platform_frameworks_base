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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.Car;
import android.car.hardware.hvac.CarHvacManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

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
public class HvacControllerTest extends SysuiTestCase {

    private static final int PROPERTY_ID = 1;
    private static final int AREA_ID = 1;
    private static final float VALUE = 72.0f;

    private HvacController mHvacController;
    private CarServiceProvider mCarServiceProvider;

    @Mock
    private Car mCar;
    @Mock
    private CarHvacManager mCarHvacManager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mCar.isConnected()).thenReturn(true);
        when(mCar.getCarManager(Car.HVAC_SERVICE)).thenReturn(mCarHvacManager);

        mCarServiceProvider = new CarServiceProvider(mContext, mCar);
        mHvacController = new HvacController(mCarServiceProvider);
        mHvacController.connectToCarService();
    }

    @Test
    public void connectToCarService_registersCallback() {
        verify(mCarHvacManager).registerCallback(any());
    }

    @Test
    public void addTemperatureViewToController_usingTemperatureView_registersView() {
        TemperatureTextView v = setupMockTemperatureTextView(PROPERTY_ID, AREA_ID, VALUE);
        mHvacController.addTemperatureViewToController(v);

        verify(v).setTemp(VALUE);
    }

    @Test
    public void addTemperatureViewToController_usingSameTemperatureView_registersFirstView() {
        TemperatureTextView v = setupMockTemperatureTextView(PROPERTY_ID, AREA_ID, VALUE);
        mHvacController.addTemperatureViewToController(v);
        verify(v).setTemp(VALUE);
        resetTemperatureView(v, PROPERTY_ID, AREA_ID);

        mHvacController.addTemperatureViewToController(v);
        verify(v, never()).setTemp(VALUE);
    }

    @Test
    public void addTemperatureViewToController_usingDifferentTemperatureView_registersBothViews() {
        TemperatureTextView v1 = setupMockTemperatureTextView(PROPERTY_ID, AREA_ID, VALUE);
        mHvacController.addTemperatureViewToController(v1);
        verify(v1).setTemp(VALUE);

        TemperatureTextView v2 = setupMockTemperatureTextView(
                PROPERTY_ID + 1,
                AREA_ID + 1,
                VALUE + 1);
        mHvacController.addTemperatureViewToController(v2);
        verify(v2).setTemp(VALUE + 1);
    }

    @Test
    public void removeAllComponents_ableToRegisterSameView() {
        TemperatureTextView v = setupMockTemperatureTextView(PROPERTY_ID, AREA_ID, VALUE);
        mHvacController.addTemperatureViewToController(v);
        verify(v).setTemp(VALUE);

        mHvacController.removeAllComponents();
        resetTemperatureView(v, PROPERTY_ID, AREA_ID);

        mHvacController.addTemperatureViewToController(v);
        verify(v).setTemp(VALUE);
    }

    private TemperatureTextView setupMockTemperatureTextView(int propertyId, int areaId,
            float value) {
        TemperatureTextView v = mock(TemperatureTextView.class);
        resetTemperatureView(v, propertyId, areaId);
        when(mCarHvacManager.isPropertyAvailable(propertyId, areaId)).thenReturn(true);
        when(mCarHvacManager.getFloatProperty(propertyId, areaId)).thenReturn(value);
        return v;
    }

    private void resetTemperatureView(TemperatureTextView view, int propertyId, int areaId) {
        reset(view);
        when(view.getPropertyId()).thenReturn(propertyId);
        when(view.getAreaId()).thenReturn(areaId);
    }
}
