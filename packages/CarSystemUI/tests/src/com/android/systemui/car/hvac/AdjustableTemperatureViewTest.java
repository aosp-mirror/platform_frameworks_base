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

import static com.android.systemui.car.hvac.HvacController.convertToCelsius;
import static com.android.systemui.car.hvac.HvacController.convertToFahrenheit;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.Car;
import android.car.VehicleUnit;
import android.car.hardware.property.CarPropertyManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.widget.TextView;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.car.CarServiceProvider;
import com.android.systemui.car.CarSystemUiTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.Executor;

@CarSystemUiTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class AdjustableTemperatureViewTest extends SysuiTestCase {

    private static final float TEMP_CELSIUS = 22.0f;
    private final String mFormat = getContext().getString(R.string.hvac_temperature_format);
    private AdjustableTemperatureView mAdjustableTemperatureView;
    private HvacController mHvacController;

    @Mock
    private Car mCar;
    @Mock
    private CarPropertyManager mCarPropertyManager;
    @Mock
    private Executor mExecutor;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mCar.isConnected()).thenReturn(true);
        when(mCar.getCarManager(Car.PROPERTY_SERVICE)).thenReturn(mCarPropertyManager);

        CarServiceProvider carServiceProvider = new CarServiceProvider(mContext, mCar);
        mHvacController = new HvacController(carServiceProvider, mExecutor);
        mHvacController.connectToCarService();
        mAdjustableTemperatureView = new AdjustableTemperatureView(getContext(), /* attrs= */ null);
        mAdjustableTemperatureView.onFinishInflate();
        mAdjustableTemperatureView.setHvacController(mHvacController);
    }

    @Test
    public void addTemperatureViewToController_setsTemperatureView() {
        when(mCarPropertyManager.isPropertyAvailable(eq(HVAC_TEMPERATURE_SET),
                anyInt())).thenReturn(true);
        when(mCarPropertyManager.getFloatProperty(eq(HVAC_TEMPERATURE_SET), anyInt())).thenReturn(
                TEMP_CELSIUS);

        mHvacController.addTemperatureViewToController(mAdjustableTemperatureView);

        TextView tempText = mAdjustableTemperatureView.findViewById(R.id.hvac_temperature_text);
        assertEquals(tempText.getText(), String.format(mFormat, TEMP_CELSIUS));
    }

    @Test
    public void setTemp_tempNaN_setsTextToNaNText() {
        when(mCarPropertyManager.isPropertyAvailable(eq(HVAC_TEMPERATURE_SET),
                anyInt())).thenReturn(true);
        when(mCarPropertyManager.getFloatProperty(eq(HVAC_TEMPERATURE_SET), anyInt())).thenReturn(
                Float.NaN);

        mHvacController.addTemperatureViewToController(mAdjustableTemperatureView);

        TextView tempText = mAdjustableTemperatureView.findViewById(R.id.hvac_temperature_text);
        assertEquals(tempText.getText(),
                getContext().getResources().getString(R.string.hvac_null_temp_text));
    }

    @Test
    public void setTemp_tempBelowMin_setsTextToMinTempText() {
        when(mCarPropertyManager.isPropertyAvailable(eq(HVAC_TEMPERATURE_SET),
                anyInt())).thenReturn(true);
        when(mCarPropertyManager.getFloatProperty(eq(HVAC_TEMPERATURE_SET), anyInt())).thenReturn(
                getContext().getResources().getFloat(R.dimen.hvac_min_value_celsius));

        mHvacController.addTemperatureViewToController(mAdjustableTemperatureView);

        TextView tempText = mAdjustableTemperatureView.findViewById(R.id.hvac_temperature_text);
        assertEquals(tempText.getText(),
                getContext().getResources().getString(R.string.hvac_min_text));
    }

    @Test
    public void setTemp_tempAboveMax_setsTextToMaxTempText() {
        when(mCarPropertyManager.isPropertyAvailable(eq(HVAC_TEMPERATURE_SET),
                anyInt())).thenReturn(true);
        when(mCarPropertyManager.getFloatProperty(eq(HVAC_TEMPERATURE_SET), anyInt())).thenReturn(
                getContext().getResources().getFloat(R.dimen.hvac_max_value_celsius));

        mHvacController.addTemperatureViewToController(mAdjustableTemperatureView);

        TextView tempText = mAdjustableTemperatureView.findViewById(R.id.hvac_temperature_text);
        assertEquals(tempText.getText(),
                getContext().getResources().getString(R.string.hvac_max_text));
    }

    @Test
    public void setTemperatureToFahrenheit_callsViewSetDisplayInFahrenheit() {
        when(mCarPropertyManager.isPropertyAvailable(eq(HVAC_TEMPERATURE_SET),
                anyInt())).thenReturn(true);
        when(mCarPropertyManager.getFloatProperty(eq(HVAC_TEMPERATURE_SET), anyInt())).thenReturn(
                TEMP_CELSIUS);
        when(mCarPropertyManager.isPropertyAvailable(HVAC_TEMPERATURE_DISPLAY_UNITS,
                VEHICLE_AREA_TYPE_GLOBAL)).thenReturn(true);
        when(mCarPropertyManager.getIntProperty(HVAC_TEMPERATURE_DISPLAY_UNITS,
                VEHICLE_AREA_TYPE_GLOBAL)).thenReturn(VehicleUnit.FAHRENHEIT);

        mHvacController.addTemperatureViewToController(mAdjustableTemperatureView);

        TextView tempText = mAdjustableTemperatureView.findViewById(R.id.hvac_temperature_text);
        assertEquals(tempText.getText(), String.format(mFormat, convertToFahrenheit(TEMP_CELSIUS)));
    }

    @Test
    public void adjustableViewIncreaseButton_setsTempWithCarPropertyManager() {
        when(mCarPropertyManager.isPropertyAvailable(eq(HVAC_TEMPERATURE_SET),
                anyInt())).thenReturn(true);
        when(mCarPropertyManager.getFloatProperty(eq(HVAC_TEMPERATURE_SET), anyInt())).thenReturn(
                TEMP_CELSIUS);
        mHvacController.addTemperatureViewToController(mAdjustableTemperatureView);

        mAdjustableTemperatureView.findViewById(R.id.hvac_increase_button).callOnClick();

        ArgumentCaptor<Runnable> setTempRunnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(mExecutor).execute(setTempRunnableCaptor.capture());
        setTempRunnableCaptor.getValue().run();
        verify(mCarPropertyManager).setFloatProperty(eq(HVAC_TEMPERATURE_SET), anyInt(),
                eq(TEMP_CELSIUS + 1));
    }

    @Test
    public void adjustableViewDecreaseButton_setsTempWithCarPropertyManager() {
        when(mCarPropertyManager.isPropertyAvailable(eq(HVAC_TEMPERATURE_SET),
                anyInt())).thenReturn(true);
        when(mCarPropertyManager.getFloatProperty(eq(HVAC_TEMPERATURE_SET), anyInt())).thenReturn(
                TEMP_CELSIUS);
        mHvacController.addTemperatureViewToController(mAdjustableTemperatureView);

        mAdjustableTemperatureView.findViewById(R.id.hvac_decrease_button).callOnClick();

        ArgumentCaptor<Runnable> setTempRunnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(mExecutor).execute(setTempRunnableCaptor.capture());
        setTempRunnableCaptor.getValue().run();
        verify(mCarPropertyManager).setFloatProperty(eq(HVAC_TEMPERATURE_SET), anyInt(),
                eq(TEMP_CELSIUS - 1));
    }

    @Test
    public void adjustableViewIncreaseButton_inFahrenheit_setsTempWithCarPropertyManager() {
        when(mCarPropertyManager.isPropertyAvailable(eq(HVAC_TEMPERATURE_SET),
                anyInt())).thenReturn(true);
        when(mCarPropertyManager.getFloatProperty(eq(HVAC_TEMPERATURE_SET), anyInt())).thenReturn(
                TEMP_CELSIUS);
        when(mCarPropertyManager.isPropertyAvailable(HVAC_TEMPERATURE_DISPLAY_UNITS,
                VEHICLE_AREA_TYPE_GLOBAL)).thenReturn(true);
        when(mCarPropertyManager.getIntProperty(HVAC_TEMPERATURE_DISPLAY_UNITS,
                VEHICLE_AREA_TYPE_GLOBAL)).thenReturn(VehicleUnit.FAHRENHEIT);
        mHvacController.addTemperatureViewToController(mAdjustableTemperatureView);

        mAdjustableTemperatureView.findViewById(R.id.hvac_increase_button).callOnClick();

        ArgumentCaptor<Runnable> setTempRunnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(mExecutor).execute(setTempRunnableCaptor.capture());
        setTempRunnableCaptor.getValue().run();
        verify(mCarPropertyManager).setFloatProperty(eq(HVAC_TEMPERATURE_SET), anyInt(),
                eq(convertToCelsius(convertToFahrenheit(TEMP_CELSIUS) + 1)));
    }

    @Test
    public void adjustableViewDecreaseButton_inFahrenheit_setsTempWithCarPropertyManager() {
        when(mCarPropertyManager.isPropertyAvailable(eq(HVAC_TEMPERATURE_SET),
                anyInt())).thenReturn(true);
        when(mCarPropertyManager.getFloatProperty(eq(HVAC_TEMPERATURE_SET), anyInt())).thenReturn(
                TEMP_CELSIUS);
        when(mCarPropertyManager.isPropertyAvailable(HVAC_TEMPERATURE_DISPLAY_UNITS,
                VEHICLE_AREA_TYPE_GLOBAL)).thenReturn(true);
        when(mCarPropertyManager.getIntProperty(HVAC_TEMPERATURE_DISPLAY_UNITS,
                VEHICLE_AREA_TYPE_GLOBAL)).thenReturn(VehicleUnit.FAHRENHEIT);
        mHvacController.addTemperatureViewToController(mAdjustableTemperatureView);

        mAdjustableTemperatureView.findViewById(R.id.hvac_decrease_button).callOnClick();

        ArgumentCaptor<Runnable> setTempRunnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(mExecutor).execute(setTempRunnableCaptor.capture());
        setTempRunnableCaptor.getValue().run();
        verify(mCarPropertyManager).setFloatProperty(eq(HVAC_TEMPERATURE_SET), anyInt(),
                eq(convertToCelsius(convertToFahrenheit(TEMP_CELSIUS) - 1)));
    }
}
