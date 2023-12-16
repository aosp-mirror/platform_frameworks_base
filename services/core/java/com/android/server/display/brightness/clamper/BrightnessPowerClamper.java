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

import static com.android.server.display.DisplayDeviceConfig.DEFAULT_ID;
import static com.android.server.display.brightness.clamper.BrightnessClamperController.ClamperChangeListener;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Temperature;
import android.provider.DeviceConfigInterface;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.display.DisplayDeviceConfig.PowerThrottlingConfigData;
import com.android.server.display.DisplayDeviceConfig.PowerThrottlingData;
import com.android.server.display.DisplayDeviceConfig.PowerThrottlingData.ThrottlingLevel;
import com.android.server.display.feature.DeviceConfigParameterProvider;
import com.android.server.display.utils.DeviceConfigParsingUtils;

import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;


class BrightnessPowerClamper extends
        BrightnessClamper<BrightnessPowerClamper.PowerData> {

    private static final String TAG = "BrightnessPowerClamper";
    @NonNull
    private final Injector mInjector;
    @NonNull
    private final DeviceConfigParameterProvider mConfigParameterProvider;
    @Nullable
    private PmicMonitor mPmicMonitor;
    // data from DeviceConfig, for all displays, for all dataSets
    // mapOf(uniqueDisplayId to mapOf(dataSetId to PowerThrottlingData))
    @NonNull
    private Map<String, Map<String, PowerThrottlingData>>
            mPowerThrottlingDataOverride = Map.of();
    // data from DisplayDeviceConfig, for particular display+dataSet
    @Nullable
    private PowerThrottlingData mPowerThrottlingDataFromDDC = null;
    // Active data, if mPowerThrottlingDataOverride contains data for mUniqueDisplayId,
    // mDataId, then use it, otherwise mPowerThrottlingDataFromDDC.
    @Nullable
    private PowerThrottlingData mPowerThrottlingDataActive = null;
    @Nullable
    private PowerThrottlingConfigData mPowerThrottlingConfigData = null;

    private @Temperature.ThrottlingStatus int mCurrentThermalLevel = Temperature.THROTTLING_NONE;
    private float mCurrentAvgPowerConsumed = 0;
    @Nullable
    private String mUniqueDisplayId = null;
    @Nullable
    private String mDataId = null;

    private final BiFunction<String, String, ThrottlingLevel> mDataPointMapper = (key, value) -> {
        try {
            int status = DeviceConfigParsingUtils.parseThermalStatus(key);
            float powerQuota = Float.parseFloat(value);
            return new ThrottlingLevel(status, powerQuota);
        } catch (IllegalArgumentException iae) {
            return null;
        }
    };

    private final Function<List<ThrottlingLevel>, PowerThrottlingData>
            mDataSetMapper = PowerThrottlingData::create;


    BrightnessPowerClamper(Handler handler, ClamperChangeListener listener,
            PowerData powerData) {
        this(new Injector(), handler, listener, powerData);
    }

    @VisibleForTesting
    BrightnessPowerClamper(Injector injector, Handler handler, ClamperChangeListener listener,
            PowerData powerData) {
        super(handler, listener);
        mInjector = injector;
        mConfigParameterProvider = injector.getDeviceConfigParameterProvider();

        mHandler.post(() -> {
            setDisplayData(powerData);
            loadOverrideData();
            start();
        });

    }

    @Override
    @NonNull
    BrightnessClamper.Type getType() {
        return Type.POWER;
    }

    @Override
    void onDeviceConfigChanged() {
        mHandler.post(() -> {
            loadOverrideData();
            recalculateActiveData();
        });
    }

    @Override
    void onDisplayChanged(PowerData data) {
        mHandler.post(() -> {
            setDisplayData(data);
            recalculateActiveData();
        });
    }

    @Override
    void stop() {
        if (mPmicMonitor != null) {
            mPmicMonitor.shutdown();
        }
    }

    /**
     * Dumps the state of BrightnessPowerClamper.
     */
    public void dump(PrintWriter pw) {
        pw.println("BrightnessPowerClamper:");
        pw.println("  mCurrentAvgPowerConsumed=" + mCurrentAvgPowerConsumed);
        pw.println("  mUniqueDisplayId=" + mUniqueDisplayId);
        pw.println("  mCurrentThermalLevel=" + mCurrentThermalLevel);
        pw.println("  mPowerThrottlingDataFromDDC=" + (mPowerThrottlingDataFromDDC == null ? "null"
                : mPowerThrottlingDataFromDDC.toString()));
        super.dump(pw);
    }

    private void recalculateActiveData() {
        if (mUniqueDisplayId == null || mDataId == null) {
            return;
        }
        mPowerThrottlingDataActive = mPowerThrottlingDataOverride
                .getOrDefault(mUniqueDisplayId, Map.of()).getOrDefault(mDataId,
                        mPowerThrottlingDataFromDDC);
        if (mPowerThrottlingDataActive != null) {
            if (mPmicMonitor != null) {
                mPmicMonitor.stop();
                mPmicMonitor.start();
            }
        } else {
            if (mPmicMonitor != null) {
                mPmicMonitor.stop();
            }
        }
        recalculateBrightnessCap();
    }

    private void loadOverrideData() {
        String throttlingDataOverride = mConfigParameterProvider.getPowerThrottlingData();
        mPowerThrottlingDataOverride = DeviceConfigParsingUtils.parseDeviceConfigMap(
                throttlingDataOverride, mDataPointMapper, mDataSetMapper);
    }

    private void setDisplayData(@NonNull PowerData data) {
        mUniqueDisplayId = data.getUniqueDisplayId();
        mDataId = data.getPowerThrottlingDataId();
        mPowerThrottlingDataFromDDC = data.getPowerThrottlingData();
        if (mPowerThrottlingDataFromDDC == null && !DEFAULT_ID.equals(mDataId)) {
            Slog.wtf(TAG,
                    "Power throttling data is missing for powerThrottlingDataId=" + mDataId);
        }

        mPowerThrottlingConfigData = data.getPowerThrottlingConfigData();
        if (mPowerThrottlingConfigData == null) {
            Slog.d(TAG,
                    "Power throttling data is missing for configuration data.");
        }
    }

    private void recalculateBrightnessCap() {
        boolean isActive = false;
        float targetBrightnessCap = PowerManager.BRIGHTNESS_MAX;
        float powerQuota = getPowerQuotaForThermalStatus(mCurrentThermalLevel);
        if (mPowerThrottlingDataActive == null) {
            return;
        }
        if (powerQuota > 0 && mCurrentAvgPowerConsumed > powerQuota) {
            isActive = true;
            // calculate new brightness Cap.
            // Brightness has a linear relation to power-consumed.
            targetBrightnessCap =
                    (powerQuota / mCurrentAvgPowerConsumed) * PowerManager.BRIGHTNESS_MAX;
            // Cap to lowest allowed brightness on device.
            targetBrightnessCap = Math.max(targetBrightnessCap,
                    mPowerThrottlingConfigData.brightnessLowestCapAllowed);
        }

        if (mBrightnessCap != targetBrightnessCap || mIsActive != isActive) {
            mIsActive = isActive;
            mBrightnessCap = targetBrightnessCap;
            mChangeListener.onChanged();
        }
    }

    private float getPowerQuotaForThermalStatus(@Temperature.ThrottlingStatus int thermalStatus) {
        float powerQuota = 0f;
        if (mPowerThrottlingDataActive != null) {
            // Throttling levels are sorted by increasing severity
            for (ThrottlingLevel level : mPowerThrottlingDataActive.throttlingLevels) {
                if (level.thermalStatus <= thermalStatus) {
                    powerQuota = level.powerQuotaMilliWatts;
                } else {
                    // Throttling levels that are greater than the current status are irrelevant
                    break;
                }
            }
        }
        return powerQuota;
    }

    private void recalculatePowerQuotaChange(float avgPowerConsumed, int thermalStatus) {
        mHandler.post(() -> {
            mCurrentThermalLevel = thermalStatus;
            mCurrentAvgPowerConsumed = avgPowerConsumed;
            recalculateBrightnessCap();
        });
    }

    private void start() {
        if (mPowerThrottlingConfigData == null) {
            return;
        }
        PowerChangeListener listener = (powerConsumed, thermalStatus) -> {
            recalculatePowerQuotaChange(powerConsumed, thermalStatus);
        };
        mPmicMonitor =
            mInjector.getPmicMonitor(listener, mPowerThrottlingConfigData.pollingWindowMillis);
        mPmicMonitor.start();
    }

    public interface PowerData {
        @NonNull
        String getUniqueDisplayId();

        @NonNull
        String getPowerThrottlingDataId();

        @Nullable
        PowerThrottlingData getPowerThrottlingData();

        @Nullable
        PowerThrottlingConfigData getPowerThrottlingConfigData();
    }

    /**
     * Power change listener
     */
    @FunctionalInterface
    public interface PowerChangeListener {
        /**
         * Notifies that power state changed from power controller.
         */
        void onChanged(float avgPowerConsumed, @Temperature.ThrottlingStatus int thermalStatus);
    }

    @VisibleForTesting
    static class Injector {
        PmicMonitor getPmicMonitor(PowerChangeListener listener, int pollingTime) {
            return new PmicMonitor(listener, pollingTime);
        }

        DeviceConfigParameterProvider getDeviceConfigParameterProvider() {
            return new DeviceConfigParameterProvider(DeviceConfigInterface.REAL);
        }
    }
}
