/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.display.brightness.clamper;

import static  com.android.server.display.DisplayDeviceConfig.DEFAULT_ID;
import static com.android.server.display.brightness.clamper.BrightnessClamperController.ClamperChangeListener;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.Handler;
import android.os.IThermalEventListener;
import android.os.IThermalService;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.Temperature;
import android.provider.DeviceConfigInterface;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.display.DisplayDeviceConfig.ThermalBrightnessThrottlingData;
import com.android.server.display.DisplayDeviceConfig.ThermalBrightnessThrottlingData.ThrottlingLevel;
import com.android.server.display.config.SensorData;
import com.android.server.display.feature.DeviceConfigParameterProvider;
import com.android.server.display.utils.DeviceConfigParsingUtils;
import com.android.server.display.utils.SensorUtils;

import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;


class BrightnessThermalClamper extends
        BrightnessClamper<BrightnessThermalClamper.ThermalData> {

    private static final String TAG = "BrightnessThermalClamper";
    @NonNull
    private final ThermalStatusObserver mThermalStatusObserver;
    @NonNull
    private final DeviceConfigParameterProvider mConfigParameterProvider;
    // data from DeviceConfig, for all displays, for all dataSets
    // mapOf(uniqueDisplayId to mapOf(dataSetId to ThermalBrightnessThrottlingData))
    @NonNull
    private Map<String, Map<String, ThermalBrightnessThrottlingData>>
            mThermalThrottlingDataOverride = Map.of();
    // data from DisplayDeviceConfig, for particular display+dataSet
    @Nullable
    private ThermalBrightnessThrottlingData mThermalThrottlingDataFromDeviceConfig = null;
    // Active data, if mDataOverride contains data for mUniqueDisplayId, mDataId, then use it,
    // otherwise mDataFromDeviceConfig
    @Nullable
    private ThermalBrightnessThrottlingData mThermalThrottlingDataActive = null;
    @Nullable
    private String mUniqueDisplayId = null;
    @Nullable
    private String mDataId = null;
    @Temperature.ThrottlingStatus
    private int mThrottlingStatus = Temperature.THROTTLING_NONE;

    private final BiFunction<String, String, ThrottlingLevel> mDataPointMapper = (key, value) -> {
        try {
            int status = DeviceConfigParsingUtils.parseThermalStatus(key);
            float brightnessPoint = DeviceConfigParsingUtils.parseBrightness(value);
            return new ThrottlingLevel(status, brightnessPoint);
        } catch (IllegalArgumentException iae) {
            return null;
        }
    };

    private final Function<List<ThrottlingLevel>, ThermalBrightnessThrottlingData>
            mDataSetMapper = ThermalBrightnessThrottlingData::create;


    BrightnessThermalClamper(Handler handler, ClamperChangeListener listener,
            ThermalData thermalData) {
        this(new Injector(), handler, listener, thermalData);
    }

    @VisibleForTesting
    BrightnessThermalClamper(Injector injector, Handler handler,
            ClamperChangeListener listener, ThermalData thermalData) {
        super(handler, listener);
        mConfigParameterProvider = injector.getDeviceConfigParameterProvider();
        mThermalStatusObserver = new ThermalStatusObserver(injector, handler);
        mHandler.post(() -> {
            setDisplayData(thermalData);
            loadOverrideData();
        });

    }

    @Override
    @NonNull
    Type getType() {
        return Type.THERMAL;
    }

    @Override
    void onDeviceConfigChanged() {
        mHandler.post(() -> {
            loadOverrideData();
            recalculateActiveData();
        });
    }

    @Override
    void onDisplayChanged(ThermalData data) {
        mHandler.post(() -> {
            setDisplayData(data);
            recalculateActiveData();
        });
    }

    @Override
    void stop() {
        mThermalStatusObserver.stopObserving();
    }

    @Override
    void dump(PrintWriter writer) {
        writer.println("BrightnessThermalClamper:");
        writer.println("  mThrottlingStatus: " + mThrottlingStatus);
        writer.println("  mUniqueDisplayId: " + mUniqueDisplayId);
        writer.println("  mDataId: " + mDataId);
        writer.println("  mDataOverride: " + mThermalThrottlingDataOverride);
        writer.println("  mDataFromDeviceConfig: " + mThermalThrottlingDataFromDeviceConfig);
        writer.println("  mDataActive: " + mThermalThrottlingDataActive);
        mThermalStatusObserver.dump(writer);
        super.dump(writer);
    }

    private void recalculateActiveData() {
        if (mUniqueDisplayId == null || mDataId == null) {
            return;
        }
        mThermalThrottlingDataActive = mThermalThrottlingDataOverride
                .getOrDefault(mUniqueDisplayId, Map.of()).getOrDefault(mDataId,
                        mThermalThrottlingDataFromDeviceConfig);

        recalculateBrightnessCap();
    }

    private void loadOverrideData() {
        String throttlingDataOverride = mConfigParameterProvider.getBrightnessThrottlingData();
        mThermalThrottlingDataOverride = DeviceConfigParsingUtils.parseDeviceConfigMap(
                throttlingDataOverride, mDataPointMapper, mDataSetMapper);
    }

    private void setDisplayData(@NonNull ThermalData data) {
        mUniqueDisplayId = data.getUniqueDisplayId();
        mDataId = data.getThermalThrottlingDataId();
        mThermalThrottlingDataFromDeviceConfig = data.getThermalBrightnessThrottlingData();
        if (mThermalThrottlingDataFromDeviceConfig == null && !DEFAULT_ID.equals(mDataId)) {
            Slog.wtf(TAG,
                    "Thermal throttling data is missing for thermalThrottlingDataId=" + mDataId);
        }
        mThermalStatusObserver.registerSensor(data.getTempSensor());
    }

    private void recalculateBrightnessCap() {
        float brightnessCap = PowerManager.BRIGHTNESS_MAX;
        boolean isActive = false;

        if (mThermalThrottlingDataActive != null) {
            // Throttling levels are sorted by increasing severity
            for (ThrottlingLevel level : mThermalThrottlingDataActive.throttlingLevels) {
                if (level.thermalStatus <= mThrottlingStatus) {
                    brightnessCap = level.brightness;
                    isActive = true;
                } else {
                    // Throttling levels that are greater than the current status are irrelevant
                    break;
                }
            }
        }

        if (brightnessCap  != mBrightnessCap || mIsActive != isActive) {
            mBrightnessCap = brightnessCap;
            mIsActive = isActive;
            mChangeListener.onChanged();
        }
    }

    private void thermalStatusChanged(@Temperature.ThrottlingStatus int status) {
        if (mThrottlingStatus != status) {
            mThrottlingStatus = status;
            recalculateBrightnessCap();
        }
    }


    private final class ThermalStatusObserver extends IThermalEventListener.Stub {
        private final Injector mInjector;
        private final Handler mHandler;
        private IThermalService mThermalService;
        private boolean mStarted;
        private SensorData mObserverTempSensor;

        ThermalStatusObserver(Injector injector, Handler handler) {
            mInjector = injector;
            mHandler = handler;
            mStarted = false;
        }

        void registerSensor(SensorData tempSensor) {
            if (!mStarted || mObserverTempSensor == null) {
                mObserverTempSensor = tempSensor;
                registerThermalListener();
                return;
            }

            String curType = mObserverTempSensor.type;
            mObserverTempSensor = tempSensor;
            if (curType.equals(tempSensor.type)) {
                Slog.d(TAG, "Thermal status observer already started");
                return;
            }
            stopObserving();
            registerThermalListener();
        }

        void registerThermalListener() {
            mThermalService = mInjector.getThermalService();
            if (mThermalService == null) {
                Slog.e(TAG, "Could not observe thermal status. Service not available");
                return;
            }
            int temperatureType = SensorUtils.getSensorTemperatureType(mObserverTempSensor);
            try {
                // We get a callback immediately upon registering so there's no need to query
                // for the current value.
                mThermalService.registerThermalEventListenerWithType(this, temperatureType);
                mStarted = true;
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to register thermal status listener", e);
            }
        }

        @Override
        public void notifyThrottling(Temperature temp) {
            Slog.d(TAG, "New thermal throttling status = " + temp.getStatus());
            if (mObserverTempSensor.name != null
                    && !mObserverTempSensor.name.equals(temp.getName())) {
                Slog.i(TAG, "Skipping thermal throttling notification as monitored sensor: "
                            + mObserverTempSensor.name
                            + " != notified sensor: "
                            + temp.getName());
                return;
            }
            @Temperature.ThrottlingStatus int status = temp.getStatus();
            mHandler.post(() -> thermalStatusChanged(status));
        }

        void stopObserving() {
            if (!mStarted) {
                return;
            }
            try {
                mThermalService.unregisterThermalEventListener(this);
                mStarted = false;
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to unregister thermal status listener", e);
            }
            mThermalService = null;
        }

        void dump(PrintWriter writer) {
            writer.println("  ThermalStatusObserver:");
            writer.println("    mStarted: " + mStarted);
            writer.println("    mObserverTempSensor: " + mObserverTempSensor);
            if (mThermalService != null) {
                writer.println("    ThermalService available");
            } else {
                writer.println("    ThermalService not available");
            }
        }
    }

    interface ThermalData {
        @NonNull
        String getUniqueDisplayId();

        @NonNull
        String getThermalThrottlingDataId();

        @Nullable
        ThermalBrightnessThrottlingData getThermalBrightnessThrottlingData();

        @NonNull
        SensorData getTempSensor();
    }

    @VisibleForTesting
    static class Injector {
        IThermalService getThermalService() {
            return IThermalService.Stub.asInterface(
                    ServiceManager.getService(Context.THERMAL_SERVICE));
        }

        DeviceConfigParameterProvider getDeviceConfigParameterProvider() {
            return new DeviceConfigParameterProvider(DeviceConfigInterface.REAL);
        }
    }
}
