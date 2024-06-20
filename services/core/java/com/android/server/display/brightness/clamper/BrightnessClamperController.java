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

import static android.view.Display.STATE_ON;

import static com.android.server.display.brightness.clamper.BrightnessClamper.Type;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Resources;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.BrightnessInfo;
import android.hardware.display.DisplayManagerInternal;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.DeviceConfig;
import android.provider.DeviceConfigInterface;
import android.util.IndentingPrintWriter;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.display.DisplayBrightnessState;
import com.android.server.display.DisplayDeviceConfig;
import com.android.server.display.DisplayDeviceConfig.PowerThrottlingConfigData;
import com.android.server.display.DisplayDeviceConfig.PowerThrottlingData;
import com.android.server.display.DisplayDeviceConfig.ThermalBrightnessThrottlingData;
import com.android.server.display.brightness.BrightnessReason;
import com.android.server.display.config.SensorData;
import com.android.server.display.feature.DeviceConfigParameterProvider;
import com.android.server.display.feature.DisplayManagerFlags;
import com.android.server.display.utils.AmbientFilter;
import com.android.server.display.utils.AmbientFilterFactory;
import com.android.server.display.utils.DebugUtils;
import com.android.server.display.utils.SensorUtils;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Clampers controller, all in DisplayControllerHandler
 */
public class BrightnessClamperController {
    private static final String TAG = "BrightnessClamperController";
    // To enable these logs, run:
    // 'adb shell setprop persist.log.tag.BrightnessClamperController DEBUG && adb reboot'
    private static final boolean DEBUG = DebugUtils.isDebuggable(TAG);
    public static final float INVALID_LUX = -1f;

    private final DeviceConfigParameterProvider mDeviceConfigParameterProvider;
    private final Handler mHandler;
    private final SensorManager mSensorManager;
    private final ClamperChangeListener mClamperChangeListenerExternal;
    private final Executor mExecutor;
    private final List<BrightnessClamper<? super DisplayDeviceData>> mClampers;

    private final List<BrightnessStateModifier> mModifiers;
    private final DeviceConfig.OnPropertiesChangedListener mOnPropertiesChangedListener;
    private float mBrightnessCap = PowerManager.BRIGHTNESS_MAX;

    private float mCustomAnimationRate = DisplayBrightnessState.CUSTOM_ANIMATION_RATE_NOT_SET;
    @Nullable
    private Type mClamperType = null;
    private final SensorEventListener mLightSensorListener;
    private Sensor mRegisteredLightSensor = null;
    private Sensor mLightSensor;
    private String mLightSensorType;
    private String mLightSensorName;
    private AmbientFilter mAmbientFilter;
    private final DisplayDeviceConfig mDisplayDeviceConfig;
    private final Resources mResources;
    private final int mLightSensorRate;

    private final Injector mInjector;
    private boolean mClamperApplied = false;

    public BrightnessClamperController(Handler handler,
            ClamperChangeListener clamperChangeListener, DisplayDeviceData data, Context context,
            DisplayManagerFlags flags, SensorManager sensorManager) {
        this(null, handler, clamperChangeListener, data, context, flags, sensorManager);
    }

    @VisibleForTesting
    BrightnessClamperController(Injector injector, Handler handler,
            ClamperChangeListener clamperChangeListener, DisplayDeviceData data, Context context,
            DisplayManagerFlags flags, SensorManager sensorManager) {
        mInjector = injector == null ? new Injector() : injector;
        mDeviceConfigParameterProvider = mInjector.getDeviceConfigParameterProvider();
        mHandler = handler;
        mSensorManager = sensorManager;
        mDisplayDeviceConfig = data.mDisplayDeviceConfig;
        mLightSensorListener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                long now = SystemClock.elapsedRealtime();
                mAmbientFilter.addValue(TimeUnit.NANOSECONDS.toMillis(event.timestamp),
                        event.values[0]);
                final float lux = mAmbientFilter.getEstimate(now);
                mModifiers.forEach(mModifier -> mModifier.setAmbientLux(lux));
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
                // unused
            }
        };

        mClamperChangeListenerExternal = clamperChangeListener;
        mExecutor = new HandlerExecutor(handler);
        mResources = context.getResources();
        mLightSensorRate = context.getResources().getInteger(
                R.integer.config_autoBrightnessLightSensorRate);

        Runnable clamperChangeRunnableInternal = this::recalculateBrightnessCap;

        ClamperChangeListener clamperChangeListenerInternal = () -> {
            if (!mHandler.hasCallbacks(clamperChangeRunnableInternal)) {
                mHandler.post(clamperChangeRunnableInternal);
            }
        };

        mClampers = mInjector.getClampers(handler, clamperChangeListenerInternal, data, flags,
                context);
        mModifiers = mInjector.getModifiers(flags, context, handler, clamperChangeListener,
                data.mDisplayDeviceConfig, mSensorManager);
        mOnPropertiesChangedListener =
                properties -> mClampers.forEach(BrightnessClamper::onDeviceConfigChanged);
        start();
    }

    /**
     * Should be called when display changed. Forwards the call to individual clampers
     */
    public void onDisplayChanged(DisplayDeviceData data) {
        mClampers.forEach(clamper -> clamper.onDisplayChanged(data));
    }

    /**
     * Applies clamping
     * Called in DisplayControllerHandler
     */
    public DisplayBrightnessState clamp(DisplayManagerInternal.DisplayPowerRequest request,
            float brightnessValue, boolean slowChange, int displayState) {
        float cappedBrightness = Math.min(brightnessValue, mBrightnessCap);

        DisplayBrightnessState.Builder builder = DisplayBrightnessState.builder();
        builder.setIsSlowChange(slowChange);
        builder.setBrightness(cappedBrightness);
        builder.setMaxBrightness(mBrightnessCap);
        builder.setCustomAnimationRate(mCustomAnimationRate);

        if (mClamperType != null) {
            builder.getBrightnessReason().addModifier(BrightnessReason.MODIFIER_THROTTLED);
            if (!mClamperApplied) {
                builder.setIsSlowChange(false);
            }
            mClamperApplied = true;
        } else {
            mClamperApplied = false;
        }

        if (displayState != STATE_ON) {
            unregisterSensorListener();
        } else {
            maybeRegisterLightSensor();
        }

        for (int i = 0; i < mModifiers.size(); i++) {
            mModifiers.get(i).apply(request, builder);
        }

        return builder.build();
    }

    /**
     * See BrightnessThrottler.getBrightnessMaxReason:
     * used in:
     * 1) DPC2.CachedBrightnessInfo to determine changes
     * 2) DPC2.logBrightnessEvent
     * 3) HBMController - for logging
     * Method is called in mHandler thread (DisplayControllerHandler), in the same thread
     * recalculateBrightnessCap and DPC2.updatePowerStateInternal are called.
     * Should be moved to DisplayBrightnessState OR derived from DisplayBrightnessState
     * TODO: b/263362199
     */
    @BrightnessInfo.BrightnessMaxReason
    public int getBrightnessMaxReason() {
        if (mClamperType == null) {
            return BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE;
        } else if (mClamperType == Type.THERMAL) {
            return BrightnessInfo.BRIGHTNESS_MAX_REASON_THERMAL;
        } else if (mClamperType == Type.POWER) {
            return BrightnessInfo.BRIGHTNESS_MAX_REASON_POWER_IC;
        } else if (mClamperType == Type.WEAR_BEDTIME_MODE) {
            return BrightnessInfo.BRIGHTNESS_MAX_REASON_WEAR_BEDTIME_MODE;
        } else {
            Slog.wtf(TAG, "BrightnessMaxReason not mapped for type=" + mClamperType);
            return BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE;
        }
    }

    /**
     * Used to dump ClampersController state.
     */
    public void dump(PrintWriter writer) {
        writer.println("BrightnessClamperController:");
        writer.println("  mBrightnessCap: " + mBrightnessCap);
        writer.println("  mClamperType: " + mClamperType);
        writer.println("  mClamperApplied: " + mClamperApplied);
        writer.println("  mLightSensor=" + mLightSensor);
        writer.println("  mRegisteredLightSensor=" + mRegisteredLightSensor);
        IndentingPrintWriter ipw = new IndentingPrintWriter(writer, "    ");
        mClampers.forEach(clamper -> clamper.dump(ipw));
        mModifiers.forEach(modifier -> modifier.dump(ipw));
    }

    /**
     * This method should be called when the ClamperController is no longer in use.
     * Called in DisplayControllerHandler
     */
    public void stop() {
        mDeviceConfigParameterProvider.removeOnPropertiesChangedListener(
                mOnPropertiesChangedListener);
        mClampers.forEach(BrightnessClamper::stop);
        mModifiers.forEach(BrightnessStateModifier::stop);
    }


    // Called in DisplayControllerHandler
    private void recalculateBrightnessCap() {
        float brightnessCap = PowerManager.BRIGHTNESS_MAX;
        Type clamperType = null;
        float customAnimationRate = DisplayBrightnessState.CUSTOM_ANIMATION_RATE_NOT_SET;

        BrightnessClamper<?> minClamper = mClampers.stream()
                .filter(BrightnessClamper::isActive)
                .min((clamper1, clamper2) -> Float.compare(clamper1.getBrightnessCap(),
                        clamper2.getBrightnessCap())).orElse(null);

        if (minClamper != null) {
            brightnessCap = minClamper.getBrightnessCap();
            clamperType = minClamper.getType();
            customAnimationRate = minClamper.getCustomAnimationRate();
        }

        if (mBrightnessCap != brightnessCap
                || mClamperType != clamperType
                || mCustomAnimationRate != customAnimationRate) {
            mBrightnessCap = brightnessCap;
            mClamperType = clamperType;
            mCustomAnimationRate = customAnimationRate;
            mClamperChangeListenerExternal.onChanged();
        }
    }

    private void start() {
        if (!mClampers.isEmpty()) {
            mDeviceConfigParameterProvider.addOnPropertiesChangedListener(
                    mExecutor, mOnPropertiesChangedListener);
            reloadLightSensorData(mDisplayDeviceConfig);
            mLightSensor = mInjector.getLightSensor(
                    mSensorManager, mLightSensorType, mLightSensorName);
            maybeRegisterLightSensor();
        }
    }

    /**
     * Clampers change listener
     */
    public interface ClamperChangeListener {
        /**
         * Notifies that clamper state changed
         */
        void onChanged();
    }

    @VisibleForTesting
    static class Injector {
        DeviceConfigParameterProvider getDeviceConfigParameterProvider() {
            return new DeviceConfigParameterProvider(DeviceConfigInterface.REAL);
        }

        List<BrightnessClamper<? super DisplayDeviceData>> getClampers(Handler handler,
                ClamperChangeListener clamperChangeListener, DisplayDeviceData data,
                DisplayManagerFlags flags, Context context) {
            List<BrightnessClamper<? super DisplayDeviceData>> clampers = new ArrayList<>();
            clampers.add(
                    new BrightnessThermalClamper(handler, clamperChangeListener, data));
            if (flags.isPowerThrottlingClamperEnabled()) {
                clampers.add(new BrightnessPowerClamper(handler, clamperChangeListener,
                        data));
            }
            if (flags.isBrightnessWearBedtimeModeClamperEnabled()) {
                clampers.add(new BrightnessWearBedtimeModeClamper(handler, context,
                        clamperChangeListener, data));
            }
            return clampers;
        }

        List<BrightnessStateModifier> getModifiers(DisplayManagerFlags flags, Context context,
                Handler handler, ClamperChangeListener listener,
                DisplayDeviceConfig displayDeviceConfig, SensorManager sensorManager) {
            List<BrightnessStateModifier> modifiers = new ArrayList<>();
            modifiers.add(new DisplayDimModifier(context));
            modifiers.add(new BrightnessLowPowerModeModifier());
            if (flags.isEvenDimmerEnabled() && displayDeviceConfig != null
                    && displayDeviceConfig.isEvenDimmerAvailable()) {
                modifiers.add(new BrightnessLowLuxModifier(handler, listener, context,
                        displayDeviceConfig));
            }
            return modifiers;
        }

        Sensor getLightSensor(SensorManager sensorManager, String type, String name) {
            return SensorUtils.findSensor(sensorManager, type,
                    name, Sensor.TYPE_LIGHT);
        }

    }

    /**
     * Config Data for clampers
     */
    public static class DisplayDeviceData implements BrightnessThermalClamper.ThermalData,
            BrightnessPowerClamper.PowerData,
            BrightnessWearBedtimeModeClamper.WearBedtimeModeData {
        @NonNull
        private final String mUniqueDisplayId;
        @NonNull
        private final String mThermalThrottlingDataId;
        @NonNull
        private final String mPowerThrottlingDataId;

        private final DisplayDeviceConfig mDisplayDeviceConfig;

        public DisplayDeviceData(@NonNull String uniqueDisplayId,
                @NonNull String thermalThrottlingDataId,
                @NonNull String powerThrottlingDataId,
                @NonNull DisplayDeviceConfig displayDeviceConfig) {
            mUniqueDisplayId = uniqueDisplayId;
            mThermalThrottlingDataId = thermalThrottlingDataId;
            mPowerThrottlingDataId = powerThrottlingDataId;
            mDisplayDeviceConfig = displayDeviceConfig;
        }


        @NonNull
        @Override
        public String getUniqueDisplayId() {
            return mUniqueDisplayId;
        }

        @NonNull
        @Override
        public String getThermalThrottlingDataId() {
            return mThermalThrottlingDataId;
        }

        @Nullable
        @Override
        public ThermalBrightnessThrottlingData getThermalBrightnessThrottlingData() {
            return mDisplayDeviceConfig.getThermalBrightnessThrottlingDataMapByThrottlingId().get(
                    mThermalThrottlingDataId);
        }

        @NonNull
        @Override
        public String getPowerThrottlingDataId() {
            return mPowerThrottlingDataId;
        }

        @Nullable
        @Override
        public PowerThrottlingData getPowerThrottlingData() {
            return mDisplayDeviceConfig.getPowerThrottlingDataMapByThrottlingId().get(
                    mPowerThrottlingDataId);
        }

        @Nullable
        @Override
        public PowerThrottlingConfigData getPowerThrottlingConfigData() {
            return mDisplayDeviceConfig.getPowerThrottlingConfigData();
        }

        @Override
        public float getBrightnessWearBedtimeModeCap() {
            return mDisplayDeviceConfig.getBrightnessCapForWearBedtimeMode();
        }

        @NonNull
        public SensorData getTempSensor() {
            return mDisplayDeviceConfig.getTempSensor();
        }
    }

    private void maybeRegisterLightSensor() {
        if (mModifiers.stream().noneMatch(BrightnessStateModifier::shouldListenToLightSensor)) {
            return;
        }

        if (mRegisteredLightSensor == mLightSensor) {
            return;
        }

        if (mRegisteredLightSensor != null) {
            unregisterSensorListener();
        }

        mAmbientFilter = AmbientFilterFactory.createBrightnessFilter(TAG, mResources);
        mSensorManager.registerListener(mLightSensorListener,
                mLightSensor, mLightSensorRate * 1000, mHandler);
        mRegisteredLightSensor = mLightSensor;

        if (DEBUG) {
            Slog.d(TAG, "maybeRegisterLightSensor");
        }
    }

    private void unregisterSensorListener() {
        mSensorManager.unregisterListener(mLightSensorListener);
        mRegisteredLightSensor = null;
        mModifiers.forEach(mModifier -> mModifier.setAmbientLux(INVALID_LUX)); // set lux to invalid
        if (DEBUG) {
            Slog.d(TAG, "unregisterSensorListener");
        }
    }

    private void reloadLightSensorData(DisplayDeviceConfig displayDeviceConfig) {
        // The displayDeviceConfig (ddc) contains display specific preferences. When loaded,
        // it naturally falls back to the global config.xml.
        if (displayDeviceConfig != null
                && displayDeviceConfig.getAmbientLightSensor() != null) {
            // This covers both the ddc and the config.xml fallback
            mLightSensorType = displayDeviceConfig.getAmbientLightSensor().type;
            mLightSensorName = displayDeviceConfig.getAmbientLightSensor().name;
        } else if (mLightSensorName == null && mLightSensorType == null) {
            mLightSensorType = mResources.getString(
                    com.android.internal.R.string.config_displayLightSensorType);
            mLightSensorName = "";
        }
    }
}
