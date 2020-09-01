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

import android.car.Car;
import android.car.VehicleUnit;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.hvac.CarHvacManager;
import android.car.hardware.hvac.CarHvacManager.CarHvacEventCallback;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import com.android.systemui.car.CarServiceProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Manages the connection to the Car service and delegates value changes to the registered
 * {@link TemperatureView}s
 */
@Singleton
public class HvacController {
    public static final String TAG = "HvacController";
    private static final boolean DEBUG = true;

    private final CarServiceProvider mCarServiceProvider;
    private final Set<TemperatureView> mRegisteredViews = new HashSet<>();

    private CarHvacManager mHvacManager;
    private HashMap<HvacKey, List<TemperatureView>> mTempComponents = new HashMap<>();

    /**
     * Callback for getting changes from {@link CarHvacManager} and setting the UI elements to
     * match.
     */
    private final CarHvacEventCallback mHardwareCallback = new CarHvacEventCallback() {
        @Override
        public void onChangeEvent(final CarPropertyValue val) {
            try {
                int areaId = val.getAreaId();
                int propertyId = val.getPropertyId();
                List<TemperatureView> temperatureViews = mTempComponents.get(
                        new HvacKey(propertyId, areaId));
                if (temperatureViews != null && !temperatureViews.isEmpty()) {
                    float value = (float) val.getValue();
                    if (DEBUG) {
                        Log.d(TAG, "onChangeEvent: " + areaId + ":" + propertyId + ":" + value);
                    }
                    for (TemperatureView tempView : temperatureViews) {
                        tempView.setTemp(value);
                    }
                } // else the data is not of interest
            } catch (Exception e) {
                // catch all so we don't take down the sysui if a new data type is
                // introduced.
                Log.e(TAG, "Failed handling hvac change event", e);
            }
        }

        @Override
        public void onErrorEvent(final int propertyId, final int zone) {
            Log.d(TAG, "HVAC error event, propertyId: " + propertyId
                    + " zone: " + zone);
        }
    };

    private final CarServiceProvider.CarServiceOnConnectedListener mCarServiceLifecycleListener =
            car -> {
                try {
                    mHvacManager = (CarHvacManager) car.getCarManager(Car.HVAC_SERVICE);
                    mHvacManager.registerCallback(mHardwareCallback);
                    initComponents();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to correctly connect to HVAC", e);
                }
            };

    @Inject
    public HvacController(CarServiceProvider carServiceProvider) {
        mCarServiceProvider = carServiceProvider;
    }

    /**
     * Create connection to the Car service. Note: call backs from the Car service
     * ({@link CarHvacManager}) will happen on the same thread this method was called from.
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

        HvacKey hvacKey = new HvacKey(temperatureView.getPropertyId(), temperatureView.getAreaId());
        if (!mTempComponents.containsKey(hvacKey)) {
            mTempComponents.put(hvacKey, new ArrayList<>());
        }
        mTempComponents.get(hvacKey).add(temperatureView);
        initComponent(temperatureView);

        mRegisteredViews.add(temperatureView);
    }

    private void initComponents() {
        Iterator<Map.Entry<HvacKey, List<TemperatureView>>> iterator =
                mTempComponents.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<HvacKey, List<TemperatureView>> next = iterator.next();
            List<TemperatureView> temperatureViews = next.getValue();
            for (TemperatureView view : temperatureViews) {
                initComponent(view);
            }
        }
    }

    private void initComponent(TemperatureView view) {
        int id = view.getPropertyId();
        int zone = view.getAreaId();
        if (DEBUG) {
            Log.d(TAG, "initComponent: " + zone + ":" + id);
        }

        try {
            if (mHvacManager != null
                    && mHvacManager.isPropertyAvailable(HVAC_TEMPERATURE_DISPLAY_UNITS,
                            VEHICLE_AREA_TYPE_GLOBAL)) {
                if (mHvacManager.getIntProperty(HVAC_TEMPERATURE_DISPLAY_UNITS,
                        VEHICLE_AREA_TYPE_GLOBAL) == VehicleUnit.FAHRENHEIT) {
                    view.setDisplayInFahrenheit(true);
                }

            }
            if (mHvacManager == null || !mHvacManager.isPropertyAvailable(id, zone)) {
                view.setTemp(Float.NaN);
                return;
            }
            view.setTemp(mHvacManager.getFloatProperty(id, zone));
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
     * Key for storing {@link TemperatureView}s in a hash map
     */
    private static class HvacKey {

        int mPropertyId;
        int mAreaId;

        private HvacKey(int propertyId, int areaId) {
            mPropertyId = propertyId;
            mAreaId = areaId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            HvacKey hvacKey = (HvacKey) o;
            return mPropertyId == hvacKey.mPropertyId
                    && mAreaId == hvacKey.mAreaId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mPropertyId, mAreaId);
        }
    }
}
