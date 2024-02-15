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
import com.android.server.display.feature.DeviceConfigParameterProvider;
import com.android.server.display.utils.DeviceConfigParsingUtils;

import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;


class BrightnessThermalClamper extends
        BrightnessClamper<BrightnessThermalClamper.ThermalData> {

    private static final String TAG = "BrightnessThermalClamper";

    @Nullable
    private final IThermalService mThermalService;
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
    private boolean mStarted = false;
    @Nullable
    private String mUniqueDisplayId = null;
    @Nullable
    private String mDataId = null;
    @Temperature.ThrottlingStatus
    private int mThrottlingStatus = Temperature.THROTTLING_NONE;

    private final IThermalEventListener mThermalEventListener = new IThermalEventListener.Stub() {
        @Override
        public void notifyThrottling(Temperature temperature) {
            @Temperature.ThrottlingStatus int status = temperature.getStatus();
            mHandler.post(() -> thermalStatusChanged(status));
        }
    };

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
        mThermalService = injector.getThermalService();
        mConfigParameterProvider = injector.getDeviceConfigParameterProvider();
        mHandler.post(() -> {
            setDisplayData(thermalData);
            loadOverrideData();
            start();
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
        if (!mStarted) {
            return;
        }
        try {
            mThermalService.unregisterThermalEventListener(mThermalEventListener);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to unregister thermal status listener", e);
        }
        mStarted = false;
    }

    @Override
    void dump(PrintWriter writer) {
        writer.println("BrightnessThermalClamper:");
        writer.println("  mStarted: " + mStarted);
        if (mThermalService != null) {
            writer.println("  ThermalService available");
        } else {
            writer.println("  ThermalService not available");
        }
        writer.println("  mThrottlingStatus: " + mThrottlingStatus);
        writer.println("  mUniqueDisplayId: " + mUniqueDisplayId);
        writer.println("  mDataId: " + mDataId);
        writer.println("  mDataOverride: " + mThermalThrottlingDataOverride);
        writer.println("  mDataFromDeviceConfig: " + mThermalThrottlingDataFromDeviceConfig);
        writer.println("  mDataActive: " + mThermalThrottlingDataActive);
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

    private void start() {
        if (mThermalService == null) {
            Slog.e(TAG, "Could not observe thermal status. Service not available");
            return;
        }
        try {
            // We get a callback immediately upon registering so there's no need to query
            // for the current value.
            mThermalService.registerThermalEventListenerWithType(mThermalEventListener,
                    Temperature.TYPE_SKIN);
            mStarted = true;
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to register thermal status listener", e);
        }
    }

    interface ThermalData {
        @NonNull
        String getUniqueDisplayId();

        @NonNull
        String getThermalThrottlingDataId();

        @Nullable
        ThermalBrightnessThrottlingData getThermalBrightnessThrottlingData();
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
