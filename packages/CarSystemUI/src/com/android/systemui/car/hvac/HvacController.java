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

import android.car.Car;
import android.car.VehicleUnit;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import com.android.systemui.car.CarServiceProvider;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.UiBackground;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Manages the connection to the Car service and delegates value changes to the registered
 * {@link TemperatureView}s
 */
@SysUISingleton
public class HvacController {
    public static final String TAG = "HvacController";
    private static final boolean DEBUG = true;

    private final Executor mBackgroundExecutor;
    private final CarServiceProvider mCarServiceProvider;
    private final Set<TemperatureView> mRegisteredViews = new HashSet<>();

    private CarPropertyManager mCarPropertyManager;
    private HashMap<Integer, List<TemperatureView>> mTempComponents = new HashMap<>();

    private final CarPropertyManager.CarPropertyEventCallback mHvacTemperatureSetCallback =
            new CarPropertyManager.CarPropertyEventCallback() {
                @Override
                public void onChangeEvent(CarPropertyValue value) {
                    try {
                        int areaId = value.getAreaId();
                        List<TemperatureView> temperatureViews = mTempComponents.get(areaId);
                        if (temperatureViews != null && !temperatureViews.isEmpty()) {
                            float newTemp = (float) value.getValue();
                            if (DEBUG) {
                                Log.d(TAG, "onChangeEvent: " + areaId + ":" + value);
                            }
                            for (TemperatureView view : temperatureViews) {
                                view.setTemp(newTemp);
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed handling hvac change event", e);
                    }
                }

                @Override
                public void onErrorEvent(int propId, int zone) {
                    Log.d(TAG, "HVAC error event, propertyId: " + propId + " zone: " + zone);
                }
            };

    private final CarPropertyManager.CarPropertyEventCallback mTemperatureUnitChangeCallback =
            new CarPropertyManager.CarPropertyEventCallback() {
                @Override
                public void onChangeEvent(CarPropertyValue value) {
                    if (!mRegisteredViews.isEmpty()) {
                        for (TemperatureView view : mRegisteredViews) {
                            view.setDisplayInFahrenheit(
                                    value.getValue().equals(VehicleUnit.FAHRENHEIT));
                        }
                    }
                }

                @Override
                public void onErrorEvent(int propId, int zone) {
                    Log.d(TAG, "HVAC error event, propertyId: " + propId + " zone: " + zone);
                }
            };

    private final CarServiceProvider.CarServiceOnConnectedListener mCarServiceLifecycleListener =
            car -> {
                try {
                    mCarPropertyManager = (CarPropertyManager) car.getCarManager(
                            Car.PROPERTY_SERVICE);
                    mCarPropertyManager.registerCallback(mHvacTemperatureSetCallback,
                            HVAC_TEMPERATURE_SET, CarPropertyManager.SENSOR_RATE_ONCHANGE);
                    mCarPropertyManager.registerCallback(mTemperatureUnitChangeCallback,
                            HVAC_TEMPERATURE_DISPLAY_UNITS,
                            CarPropertyManager.SENSOR_RATE_ONCHANGE);
                    initComponents();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to correctly connect to HVAC", e);
                }
            };

    @Inject
    public HvacController(CarServiceProvider carServiceProvider,
            @UiBackground Executor backgroundExecutor) {
        mCarServiceProvider = carServiceProvider;
        mBackgroundExecutor = backgroundExecutor;
    }

    /**
     * Create connection to the Car service.
     */
    public void connectToCarService() {
        mCarServiceProvider.addListener(mCarServiceLifecycleListener);
    }

    /**
     * Add component to list and initialize it if the connection is up.
     */
    private void addHvacTextView(TemperatureView temperatureView) {
        if (mRegisteredViews.contains(temperatureView)) {
            return;
        }

        int areaId = temperatureView.getAreaId();
        if (!mTempComponents.containsKey(areaId)) {
            mTempComponents.put(areaId, new ArrayList<>());
        }
        mTempComponents.get(areaId).add(temperatureView);
        initComponent(temperatureView);

        mRegisteredViews.add(temperatureView);
    }

    private void initComponents() {
        for (Map.Entry<Integer, List<TemperatureView>> next : mTempComponents.entrySet()) {
            List<TemperatureView> temperatureViews = next.getValue();
            for (TemperatureView view : temperatureViews) {
                initComponent(view);
            }
        }
    }

    private void initComponent(TemperatureView view) {
        int zone = view.getAreaId();
        if (DEBUG) {
            Log.d(TAG, "initComponent: " + zone);
        }

        try {
            if (mCarPropertyManager != null && mCarPropertyManager.isPropertyAvailable(
                    HVAC_TEMPERATURE_DISPLAY_UNITS, VEHICLE_AREA_TYPE_GLOBAL)) {
                if (mCarPropertyManager.getIntProperty(HVAC_TEMPERATURE_DISPLAY_UNITS,
                        VEHICLE_AREA_TYPE_GLOBAL) == VehicleUnit.FAHRENHEIT) {
                    view.setDisplayInFahrenheit(true);
                }
            }
            if (mCarPropertyManager == null || !mCarPropertyManager.isPropertyAvailable(
                    HVAC_TEMPERATURE_SET, zone)) {
                view.setTemp(Float.NaN);
                return;
            }
            view.setTemp(
                    mCarPropertyManager.getFloatProperty(HVAC_TEMPERATURE_SET, zone));
            view.setHvacController(this);
        } catch (Exception e) {
            view.setTemp(Float.NaN);
            Log.e(TAG, "Failed to get value from hvac service", e);
        }
    }

    /**
     * Removes all registered components. This is useful if you need to rebuild the UI since
     * components self register.
     */
    public void removeAllComponents() {
        mTempComponents.clear();
        mRegisteredViews.clear();
    }

    /**
     * Iterate through a view, looking for {@link TemperatureView} instances and add them to the
     * controller if found.
     */
    public void addTemperatureViewToController(View v) {
        if (v instanceof TemperatureView) {
            addHvacTextView((TemperatureView) v);
        } else if (v instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) v;
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                addTemperatureViewToController(viewGroup.getChildAt(i));
            }
        }
    }

    /**
     * Set the temperature in Celsius of the specified zone
     */
    public void setTemperature(float tempC, int zone) {
        if (mCarPropertyManager != null) {
            // Internally, all temperatures are represented in floating point Celsius
            mBackgroundExecutor.execute(
                    () -> mCarPropertyManager.setFloatProperty(HVAC_TEMPERATURE_SET, zone, tempC));
        }
    }

    /**
     * Convert the given temperature in Celsius into Fahrenheit
     *
     * @param tempC - The temperature in Celsius
     * @return Temperature in Fahrenheit.
     */
    public static float convertToFahrenheit(float tempC) {
        return (tempC * 9f / 5f) + 32;
    }

    /**
     * Convert the given temperature in Fahrenheit to Celsius
     *
     * @param tempF - The temperature in Fahrenheit.
     * @return Temperature in Celsius.
     */
    public static float convertToCelsius(float tempF) {
        return (tempF - 32) * 5f / 9f;
    }
}
