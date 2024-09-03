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
import com.android.server.display.DisplayBrightnessState;
import com.android.server.display.DisplayDeviceConfig.PowerThrottlingConfigData;
import com.android.server.display.DisplayDeviceConfig.PowerThrottlingData;
import com.android.server.display.DisplayDeviceConfig.PowerThrottlingData.ThrottlingLevel;
import com.android.server.display.brightness.BrightnessUtils;
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
    @NonNull
    private final ThermalLevelListener mThermalLevelListener;
    @NonNull
    private final PowerChangeListener mPowerChangeListener;
    private @Temperature.ThrottlingStatus int mCurrentThermalLevel = Temperature.THROTTLING_NONE;
    private boolean mCurrentThermalLevelChanged = false;
    private float mCurrentAvgPowerConsumed = 0;
    @Nullable
    private String mUniqueDisplayId = null;
    @Nullable
    private String mDataId = null;
    private float mCurrentBrightness = PowerManager.BRIGHTNESS_INVALID;
    private float mCustomAnimationRateSec = DisplayBrightnessState.CUSTOM_ANIMATION_RATE_NOT_SET;
    private float mCustomAnimationRateSecDeviceConfig =
                        DisplayBrightnessState.CUSTOM_ANIMATION_RATE_NOT_SET;
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
            PowerData powerData, float currentBrightness) {
        this(new Injector(), handler, listener, powerData, currentBrightness);
    }

    @VisibleForTesting
    BrightnessPowerClamper(Injector injector, Handler handler, ClamperChangeListener listener,
                           PowerData powerData, float currentBrightness) {
        super(handler, listener);
        mInjector = injector;
        mCurrentBrightness = currentBrightness;
        mPowerChangeListener = (powerConsumed, thermalStatus) -> {
            recalculatePowerQuotaChange(powerConsumed, thermalStatus);
        };
        mPowerThrottlingConfigData = powerData.getPowerThrottlingConfigData();
        if (mPowerThrottlingConfigData != null) {
            mCustomAnimationRateSecDeviceConfig = mPowerThrottlingConfigData.customAnimationRateSec;
        }
        mThermalLevelListener = new ThermalLevelListener(handler);
        mPmicMonitor =
            mInjector.getPmicMonitor(mPowerChangeListener,
                    mThermalLevelListener.getThermalService(),
                    mPowerThrottlingConfigData.pollingWindowMaxMillis,
                    mPowerThrottlingConfigData.pollingWindowMinMillis);

        mConfigParameterProvider = injector.getDeviceConfigParameterProvider();
        mHandler.post(() -> {
            setDisplayData(powerData);
            loadOverrideData();
            start();
        });
    }

    @VisibleForTesting
    PowerChangeListener getPowerChangeListener() {
        return mPowerChangeListener;
    }

    @Override
    @NonNull
    BrightnessClamper.Type getType() {
        return Type.POWER;
    }

    @Override
    float getCustomAnimationRate() {
        return mCustomAnimationRateSec;
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
        if (mThermalLevelListener != null) {
            mThermalLevelListener.stop();
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
        pw.println("  mCurrentThermalLevelChanged=" + mCurrentThermalLevelChanged);
        pw.println("  mPowerThrottlingDataFromDDC=" + (mPowerThrottlingDataFromDDC == null ? "null"
                : mPowerThrottlingDataFromDDC.toString()));
        mThermalLevelListener.dump(pw);
        super.dump(pw);
    }

    /**
     * Updates current brightness, for power calculations.
     */
    public void updateCurrentBrightness(float currentBrightness) {
        mCurrentBrightness = currentBrightness;
    }

    private void recalculateActiveData() {
        if (mUniqueDisplayId == null || mDataId == null) {
            return;
        }
        mPowerThrottlingDataActive = mPowerThrottlingDataOverride
                .getOrDefault(mUniqueDisplayId, Map.of()).getOrDefault(mDataId,
                        mPowerThrottlingDataFromDDC);
        if (mPowerThrottlingDataActive == null) {
            if (mPmicMonitor != null) {
                mPmicMonitor.stop();
            }
        }
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
        if (powerQuota > 0) {
            if (BrightnessUtils.isValidBrightnessValue(mCurrentBrightness)
                    && (mCurrentAvgPowerConsumed > powerQuota)) {
                isActive = true;
                // calculate new brightness Cap.
                // Brightness has a linear relation to power-consumed.
                targetBrightnessCap =
                    (powerQuota / mCurrentAvgPowerConsumed) * mCurrentBrightness;
            } else if (mCurrentThermalLevelChanged) {
                if (mCurrentThermalLevel == Temperature.THROTTLING_NONE) {
                    // reset pmic and remove the power-throttling cap.
                    isActive = true;
                    targetBrightnessCap = PowerManager.BRIGHTNESS_MAX;
                    mPmicMonitor.stop();
                } else {
                    isActive = true;
                    // Since the thermal status has changed, we need to remove power-throttling cap.
                    // Instead of recalculating and changing brightness again, adding flicker,
                    // we will wait for the next pmic cycle to re-evaluate this value
                    // make act on it, if needed.
                    targetBrightnessCap = PowerManager.BRIGHTNESS_MAX;
                    if (mPmicMonitor.isStopped()) {
                        mPmicMonitor.start();
                    }
                }
            } else { // Current power consumed is under the quota.
                isActive = true;
                targetBrightnessCap = PowerManager.BRIGHTNESS_MAX;
            }
        }

        // Cap to lowest allowed brightness on device.
        if (mPowerThrottlingConfigData != null) {
            targetBrightnessCap = Math.max(targetBrightnessCap,
                                mPowerThrottlingConfigData.brightnessLowestCapAllowed);
        }

        if (mBrightnessCap != targetBrightnessCap || mIsActive != isActive) {
            mIsActive = isActive;
            Slog.i(TAG, "Power clamper changing current brightness cap mBrightnessCap: "
                    + mBrightnessCap + " to target brightness cap:" + targetBrightnessCap
                    + " for current screen brightness: " + mCurrentBrightness);
            mBrightnessCap = targetBrightnessCap;
            Slog.i(TAG, "Power clamper changed state: thermalStatus:" + mCurrentThermalLevel
                    + " mCurrentThermalLevelChanged:" + mCurrentThermalLevelChanged
                    + " mCurrentAvgPowerConsumed:" + mCurrentAvgPowerConsumed
                    + " mCustomAnimationRateSec:" + mCustomAnimationRateSecDeviceConfig);
            mCustomAnimationRateSec = mCustomAnimationRateSecDeviceConfig;
            mChangeListener.onChanged();
        } else {
            mCustomAnimationRateSec = DisplayBrightnessState.CUSTOM_ANIMATION_RATE_NOT_SET;
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
            if (mCurrentThermalLevel != thermalStatus) {
                mCurrentThermalLevelChanged = true;
            } else {
                mCurrentThermalLevelChanged = false;
            }
            mCurrentThermalLevel = thermalStatus;
            mCurrentAvgPowerConsumed = avgPowerConsumed;
            recalculateBrightnessCap();
        });
    }

    private void start() {
        if (mPowerThrottlingConfigData == null) {
            return;
        }
        if (mPowerThrottlingConfigData.pollingWindowMaxMillis
                <= mPowerThrottlingConfigData.pollingWindowMinMillis) {
            Slog.e(TAG, "Brightness power max polling window:"
                    + mPowerThrottlingConfigData.pollingWindowMaxMillis
                    + " msec, should be greater than brightness min polling window:"
                    + mPowerThrottlingConfigData.pollingWindowMinMillis + " msec.");
            return;
        }
        if ((mPowerThrottlingConfigData.pollingWindowMaxMillis
                % mPowerThrottlingConfigData.pollingWindowMinMillis) != 0) {
            Slog.e(TAG, "Brightness power max polling window:"
                    + mPowerThrottlingConfigData.pollingWindowMaxMillis
                    + " msec, is not divisible by brightness min polling window:"
                    + mPowerThrottlingConfigData.pollingWindowMinMillis + " msec.");
            return;
        }
        mCustomAnimationRateSecDeviceConfig = mPowerThrottlingConfigData.customAnimationRateSec;
        mThermalLevelListener.start();
    }

    private void activatePmicMonitor() {
        if (!mPmicMonitor.isStopped()) {
            return;
        }
        mPmicMonitor.start();
    }

    private void deactivatePmicMonitor(@Temperature.ThrottlingStatus int status) {
        if (status != Temperature.THROTTLING_NONE) {
            return;
        }
        if (mPmicMonitor.isStopped()) {
            return;
        }
        mPmicMonitor.stop();
    }

    private final class ThermalLevelListener extends IThermalEventListener.Stub {
        private final Handler mHandler;
        private IThermalService mThermalService;
        private boolean mStarted;

        ThermalLevelListener(Handler handler) {
            mHandler = handler;
            mStarted = false;
            mThermalService = IThermalService.Stub.asInterface(
                    ServiceManager.getService(Context.THERMAL_SERVICE));
        }

        IThermalService getThermalService() {
            return mThermalService;
        }

        void start() {
            if (mStarted) {
                return;
            }
            if (mThermalService == null) {
                return;
            }
            try {
                // TODO b/279114539 Try DISPLAY first and then fallback to SKIN.
                mThermalService.registerThermalEventListenerWithType(this, Temperature.TYPE_SKIN);
                mStarted = true;
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to register thermal status listener", e);
            }
        }

        @Override
        public void notifyThrottling(Temperature temp) {
            @Temperature.ThrottlingStatus int status = temp.getStatus();
            if (status >= Temperature.THROTTLING_LIGHT) {
                Slog.d(TAG, "Activating pmic monitor due to thermal state:" + status);
                mHandler.post(() -> activatePmicMonitor());
            } else {
                if (!mPmicMonitor.isStopped()) {
                    mHandler.post(() -> deactivatePmicMonitor(status));
                }
            }
        }

        void stop() {
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
            writer.println("  ThermalLevelObserver:");
            writer.println("    mStarted: " + mStarted);
        }
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
        PmicMonitor getPmicMonitor(PowerChangeListener powerChangeListener,
                                   IThermalService thermalService,
                                   int pollingMaxTimeMillis,
                                   int pollingMinTimeMillis) {
            return new PmicMonitor(powerChangeListener, thermalService, pollingMaxTimeMillis,
                                        pollingMinTimeMillis);
        }

        DeviceConfigParameterProvider getDeviceConfigParameterProvider() {
            return new DeviceConfigParameterProvider(DeviceConfigInterface.REAL);
        }
    }
}
