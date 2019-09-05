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

package com.android.systemui.statusbar.car.hvac;

import static android.car.VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL;
import static android.car.VehiclePropertyIds.HVAC_TEMPERATURE_DISPLAY_UNITS;

import android.car.Car;
import android.car.VehicleUnit;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.hvac.CarHvacManager;
import android.car.hardware.hvac.CarHvacManager.CarHvacEventCallback;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Manages the connection to the Car service and delegates value changes to the registered
 * {@link TemperatureView}s
 */
public class HvacController {

    public static final String TAG = "HvacController";
    public static final int BIND_TO_HVAC_RETRY_DELAY = 5000;

    private Context mContext;
    private Handler mHandler;
    private Car mCar;
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
    /**
     * If the connection to car service goes away then restart it.
     */
    private final IBinder.DeathRecipient mRestart = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            Log.d(TAG, "Death of HVAC triggering a restart");
            if (mCar != null) {
                mCar.disconnect();
            }
            destroyHvacManager();
            mHandler.postDelayed(() -> mCar.connect(), BIND_TO_HVAC_RETRY_DELAY);
        }
    };
    /**
     * Registers callbacks and initializes components upon connection.
     */
    private ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            try {
                service.linkToDeath(mRestart, 0);
                mHvacManager = (CarHvacManager) mCar.getCarManager(Car.HVAC_SERVICE);
                mHvacManager.registerCallback(mHardwareCallback);
                initComponents();
            } catch (Exception e) {
                Log.e(TAG, "Failed to correctly connect to HVAC", e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            destroyHvacManager();
        }
    };

    public HvacController(Context context) {
        mContext = context;
    }

    /**
     * Create connection to the Car service. Note: call backs from the Car service
     * ({@link CarHvacManager}) will happen on the same thread this method was called from.
     */
    public void connectToCarService() {
        mHandler = new Handler();
        mCar = Car.createCar(mContext, mServiceConnection, mHandler);
        if (mCar != null) {
            // note: this connect call handles the retries
            mCar.connect();
        }
    }

    private void destroyHvacManager() {
        if (mHvacManager != null) {
            mHvacManager.unregisterCallback(mHardwareCallback);
            mHvacManager = null;
        }
    }

    /**
     * Add component to list and initialize it if the connection is up.
     */
    public void addHvacTextView(TemperatureView temperatureView) {

        HvacKey hvacKey = new HvacKey(temperatureView.getPropertyId(), temperatureView.getAreaId());
        if (!mTempComponents.containsKey(hvacKey)) {
            mTempComponents.put(hvacKey, new ArrayList<>());
        }
        mTempComponents.get(hvacKey).add(temperatureView);
        initComponent(temperatureView);
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
