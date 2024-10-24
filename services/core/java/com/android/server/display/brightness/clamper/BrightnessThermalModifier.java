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
import android.hardware.display.BrightnessInfo;
import android.hardware.display.DisplayManagerInternal;
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
import com.android.server.display.DisplayBrightnessState;
import com.android.server.display.DisplayDeviceConfig.ThermalBrightnessThrottlingData;
import com.android.server.display.DisplayDeviceConfig.ThermalBrightnessThrottlingData.ThrottlingLevel;
import com.android.server.display.brightness.BrightnessReason;
import com.android.server.display.config.SensorData;
import com.android.server.display.feature.DeviceConfigParameterProvider;
import com.android.server.display.utils.DeviceConfigParsingUtils;
import com.android.server.display.utils.SensorUtils;

import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;


class BrightnessThermalModifier implements BrightnessStateModifier,
        BrightnessClamperController.DisplayDeviceDataListener,
        BrightnessClamperController.StatefulModifier,
        BrightnessClamperController.DeviceConfigListener {

    private static final String TAG = "BrightnessThermalClamper";
    @NonNull
    private final ThermalStatusObserver mThermalStatusObserver;
    @NonNull
    private final DeviceConfigParameterProvider mConfigParameterProvider;
    // data from DeviceConfig, for all displays, for all dataSets
    // mapOf(uniqueDisplayId to mapOf(dataSetId to ThermalBrightnessThrottlingData))
    @NonNull
    protected final Handler mHandler;
    @NonNull
    protected final BrightnessClamperController.ClamperChangeListener mChangeListener;

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
    private float mBrightnessCap = PowerManager.BRIGHTNESS_MAX;
    private boolean mApplied = false;

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


    BrightnessThermalModifier(Handler handler, ClamperChangeListener listener,
            BrightnessClamperController.DisplayDeviceData data) {
        this(new Injector(), handler, listener, data);
    }

    @VisibleForTesting
    BrightnessThermalModifier(Injector injector, @NonNull Handler handler,
            @NonNull ClamperChangeListener listener,
            @NonNull BrightnessClamperController.DisplayDeviceData data) {
        mHandler = handler;
        mChangeListener = listener;
        mConfigParameterProvider = injector.getDeviceConfigParameterProvider();
        mThermalStatusObserver = new ThermalStatusObserver(injector, handler);
        mHandler.post(() -> {
            setDisplayData(data);
            loadOverrideData();
        });
    }
    //region BrightnessStateModifier
    @Override
    public void apply(DisplayManagerInternal.DisplayPowerRequest request,
            DisplayBrightnessState.Builder stateBuilder) {
        if (stateBuilder.getMaxBrightness() > mBrightnessCap) {
            stateBuilder.setMaxBrightness(mBrightnessCap);
            stateBuilder.setBrightness(Math.min(stateBuilder.getBrightness(), mBrightnessCap));
            stateBuilder.setBrightnessMaxReason(BrightnessInfo.BRIGHTNESS_MAX_REASON_THERMAL);
            stateBuilder.getBrightnessReason().addModifier(BrightnessReason.MODIFIER_THROTTLED);
            // set fast change only when modifier is activated.
            // this will allow auto brightness to apply slow change even when modifier is active
            if (!mApplied) {
                stateBuilder.setIsSlowChange(false);
            }
            mApplied = true;
        } else {
            mApplied = false;
        }
    }

    @Override
    public void stop() {
        mThermalStatusObserver.stopObserving();
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("BrightnessThermalClamper:");
        writer.println("  mThrottlingStatus: " + mThrottlingStatus);
        writer.println("  mUniqueDisplayId: " + mUniqueDisplayId);
        writer.println("  mDataId: " + mDataId);
        writer.println("  mDataOverride: " + mThermalThrottlingDataOverride);
        writer.println("  mDataFromDeviceConfig: " + mThermalThrottlingDataFromDeviceConfig);
        writer.println("  mDataActive: " + mThermalThrottlingDataActive);
        writer.println("  mBrightnessCap:" + mBrightnessCap);
        writer.println("  mApplied:" + mApplied);
        mThermalStatusObserver.dump(writer);
    }

    @Override
    public boolean shouldListenToLightSensor() {
        return false;
    }

    @Override
    public void setAmbientLux(float lux) {
        // noop
    }
    //endregion

    //region DisplayDeviceDataListener
    @Override
    public void onDisplayChanged(BrightnessClamperController.DisplayDeviceData data) {
        mHandler.post(() -> {
            setDisplayData(data);
            recalculateActiveData();
        });
    }
    //endregion

    //region StatefulModifier
    @Override
    public void applyStateChange(
            BrightnessClamperController.ModifiersAggregatedState aggregatedState) {
        if (aggregatedState.mMaxBrightness > mBrightnessCap) {
            aggregatedState.mMaxBrightness = mBrightnessCap;
            aggregatedState.mMaxBrightnessReason = BrightnessInfo.BRIGHTNESS_MAX_REASON_THERMAL;
        }
    }
    //endregion

    //region DeviceConfigListener
    @Override
    public void onDeviceConfigChanged() {
        mHandler.post(() -> {
            loadOverrideData();
            recalculateActiveData();
        });
    }
    //endregion

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
        if (mThermalThrottlingDataActive != null) {
            // Throttling levels are sorted by increasing severity
            for (ThrottlingLevel level : mThermalThrottlingDataActive.throttlingLevels) {
                if (level.thermalStatus <= mThrottlingStatus) {
                    brightnessCap = level.brightness;
                } else {
                    // Throttling levels that are greater than the current status are irrelevant
                    break;
                }
            }
        }

        if (brightnessCap  != mBrightnessCap) {
            mBrightnessCap = brightnessCap;
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
            if (Objects.equals(curType, tempSensor.type)) {
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
