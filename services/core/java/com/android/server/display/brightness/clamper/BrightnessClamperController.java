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
import android.hardware.SensorManager;
import android.hardware.display.BrightnessInfo;
import android.hardware.display.DisplayManagerInternal;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.DeviceConfig;
import android.provider.DeviceConfigInterface;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.Spline;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.display.BrightnessSynchronizer;
import com.android.server.display.DisplayBrightnessState;
import com.android.server.display.DisplayDeviceConfig;
import com.android.server.display.DisplayDeviceConfig.PowerThrottlingConfigData;
import com.android.server.display.DisplayDeviceConfig.PowerThrottlingData;
import com.android.server.display.DisplayDeviceConfig.ThermalBrightnessThrottlingData;
import com.android.server.display.brightness.BrightnessReason;
import com.android.server.display.config.SensorData;
import com.android.server.display.feature.DeviceConfigParameterProvider;
import com.android.server.display.feature.DisplayManagerFlags;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Clampers controller, all in DisplayControllerHandler
 */
public class BrightnessClamperController {
    private static final String TAG = "BrightnessClamperController";

    private final DeviceConfigParameterProvider mDeviceConfigParameterProvider;
    private final Handler mHandler;
    private final LightSensorController mLightSensorController;

    private final ClamperChangeListener mClamperChangeListenerExternal;
    private final Executor mExecutor;
    private final List<BrightnessClamper<? super DisplayDeviceData>> mClampers;

    private final List<BrightnessStateModifier> mModifiers;

    private final List<DisplayDeviceDataListener> mDisplayDeviceDataListeners = new ArrayList<>();
    private final List<StatefulModifier> mStatefulModifiers = new ArrayList<>();
    private final List<UserSwitchListener> mUserSwitchListeners = new ArrayList<>();
    private final List<DeviceConfigListener> mDeviceConfigListeners = new ArrayList<>();

    private ModifiersAggregatedState mModifiersAggregatedState = new ModifiersAggregatedState();

    private final DeviceConfig.OnPropertiesChangedListener mOnPropertiesChangedListener;
    private final DisplayManagerFlags mDisplayManagerFlags;
    private float mBrightnessCap = PowerManager.BRIGHTNESS_MAX;

    private float mCustomAnimationRate = DisplayBrightnessState.CUSTOM_ANIMATION_RATE_NOT_SET;
    @Nullable
    private BrightnessPowerClamper mPowerClamper;
    @Nullable
    private Type mClamperType = null;

    private boolean mClamperApplied = false;

    private final LightSensorController.LightSensorListener mLightSensorListener =
            new LightSensorController.LightSensorListener() {
                @Override
                public void onAmbientLuxChange(float lux) {
                    mModifiers.forEach(mModifier -> mModifier.setAmbientLux(lux));
                }
            };

    public BrightnessClamperController(Handler handler,
            ClamperChangeListener clamperChangeListener, DisplayDeviceData data, Context context,
            DisplayManagerFlags flags, SensorManager sensorManager, float currentBrightness) {
        this(new Injector(), handler, clamperChangeListener, data, context, flags, sensorManager,
                currentBrightness);
    }

    @VisibleForTesting
    BrightnessClamperController(Injector injector, Handler handler,
            ClamperChangeListener clamperChangeListener, DisplayDeviceData data, Context context,
            DisplayManagerFlags flags, SensorManager sensorManager, float currentBrightness) {
        mDeviceConfigParameterProvider = injector.getDeviceConfigParameterProvider();
        mHandler = handler;
        mDisplayManagerFlags = flags;
        mLightSensorController = injector.getLightSensorController(sensorManager, context,
                mLightSensorListener, mHandler);

        mClamperChangeListenerExternal = clamperChangeListener;
        mExecutor = new HandlerExecutor(handler);

        Runnable clamperChangeRunnableInternal = this::recalculateBrightnessCap;
        ClamperChangeListener clamperChangeListenerInternal = () -> {
            if (!mHandler.hasCallbacks(clamperChangeRunnableInternal)) {
                mHandler.post(clamperChangeRunnableInternal);
            }
        };

        mClampers = injector.getClampers(handler, clamperChangeListenerInternal, data, flags,
                context, currentBrightness);
        if (mDisplayManagerFlags.isPowerThrottlingClamperEnabled()) {
            for (BrightnessClamper clamper: mClampers) {
                if (clamper.getType() == Type.POWER) {
                    mPowerClamper = (BrightnessPowerClamper) clamper;
                    break;
                }
            }
        }
        mModifiers = injector.getModifiers(flags, context, handler, clamperChangeListener,
                data);

        mModifiers.forEach(m -> {
            if (m instanceof  DisplayDeviceDataListener l) {
                mDisplayDeviceDataListeners.add(l);
            }
            if (m instanceof StatefulModifier s) {
                mStatefulModifiers.add(s);
            }
            if (m instanceof UserSwitchListener l) {
                mUserSwitchListeners.add(l);
            }
            if (m instanceof DeviceConfigListener l) {
                mDeviceConfigListeners.add(l);
            }
        });
        mOnPropertiesChangedListener = properties -> {
            mClampers.forEach(BrightnessClamper::onDeviceConfigChanged);
            mDeviceConfigListeners.forEach(DeviceConfigListener::onDeviceConfigChanged);
        };
        mLightSensorController.configure(data.getAmbientLightSensor(), data.getDisplayId());
        start();
    }

    /**
     * Should be called when display changed. Forwards the call to individual clampers
     */
    public void onDisplayChanged(DisplayDeviceData data) {
        mLightSensorController.configure(data.getAmbientLightSensor(), data.getDisplayId());
        mClampers.forEach(clamper -> clamper.onDisplayChanged(data));
        mDisplayDeviceDataListeners.forEach(l -> l.onDisplayChanged(data));
        adjustLightSensorSubscription();
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
        builder.setBrightnessMaxReason(getBrightnessMaxReason());

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
            mLightSensorController.stop();
        } else {
            adjustLightSensorSubscription();
        }

        for (int i = 0; i < mModifiers.size(); i++) {
            mModifiers.get(i).apply(request, builder);
        }

        if (mDisplayManagerFlags.isPowerThrottlingClamperEnabled()) {
            if (mPowerClamper != null) {
                mPowerClamper.updateCurrentBrightness(cappedBrightness);
            }
        }

        return builder.build();
    }

    @BrightnessInfo.BrightnessMaxReason
    private int getBrightnessMaxReason() {
        if (mClamperType == null) {
            return BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE;
        } else if (mClamperType == Type.POWER) {
            return BrightnessInfo.BRIGHTNESS_MAX_REASON_POWER_IC;
        }  else {
            Slog.wtf(TAG, "BrightnessMaxReason not mapped for type=" + mClamperType);
            return BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE;
        }
    }

    /**
     * Called when the user switches.
     */
    public void onUserSwitch() {
        mUserSwitchListeners.forEach(UserSwitchListener::onSwitchUser);
    }

    /**
     * Used to dump ClampersController state.
     */
    public void dump(PrintWriter writer) {
        writer.println("BrightnessClamperController:");
        writer.println("----------------------------");
        writer.println("  mBrightnessCap: " + mBrightnessCap);
        writer.println("  mClamperType: " + mClamperType);
        writer.println("  mClamperApplied: " + mClamperApplied);
        IndentingPrintWriter ipw = new IndentingPrintWriter(writer, "    ");
        mLightSensorController.dump(ipw);
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
        mLightSensorController.stop();
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

        ModifiersAggregatedState newAggregatedState = new ModifiersAggregatedState();
        mStatefulModifiers.forEach((clamper) -> clamper.applyStateChange(newAggregatedState));

        if (mBrightnessCap != brightnessCap
                || mClamperType != clamperType
                || mCustomAnimationRate != customAnimationRate
                || needToNotifyExternalListener(mModifiersAggregatedState, newAggregatedState)) {
            mBrightnessCap = brightnessCap;
            mClamperType = clamperType;
            mCustomAnimationRate = customAnimationRate;
            mClamperChangeListenerExternal.onChanged();
        }
        mModifiersAggregatedState = newAggregatedState;
    }

    private boolean needToNotifyExternalListener(ModifiersAggregatedState state1,
            ModifiersAggregatedState state2) {
        return !BrightnessSynchronizer.floatEquals(state1.mMaxDesiredHdrRatio,
                state2.mMaxDesiredHdrRatio)
                || !BrightnessSynchronizer.floatEquals(state1.mMaxHdrBrightness,
                state2.mMaxHdrBrightness)
                || state1.mSdrHdrRatioSpline != state2.mSdrHdrRatioSpline
                || state1.mMaxBrightnessReason != state2.mMaxBrightnessReason
                || !BrightnessSynchronizer.floatEquals(state1.mMaxBrightness,
                state2.mMaxBrightness);
    }

    private void start() {
        if (!mClampers.isEmpty() || !mDeviceConfigListeners.isEmpty()) {
            mDeviceConfigParameterProvider.addOnPropertiesChangedListener(
                    mExecutor, mOnPropertiesChangedListener);
        }
        adjustLightSensorSubscription();
    }

    private void adjustLightSensorSubscription() {
        if (mModifiers.stream().anyMatch(BrightnessStateModifier::shouldListenToLightSensor)) {
            mLightSensorController.restart();
        } else {
            mLightSensorController.stop();
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
                DisplayManagerFlags flags, Context context, float currentBrightness) {
            List<BrightnessClamper<? super DisplayDeviceData>> clampers = new ArrayList<>();

            if (flags.isPowerThrottlingClamperEnabled()) {
                // Check if power-throttling config is present.
                PowerThrottlingConfigData configData = data.getPowerThrottlingConfigData();
                if (configData != null) {
                    clampers.add(new BrightnessPowerClamper(handler, clamperChangeListener,
                            data, currentBrightness));
                }
            }
            return clampers;
        }

        List<BrightnessStateModifier> getModifiers(DisplayManagerFlags flags, Context context,
                Handler handler, ClamperChangeListener listener,
                DisplayDeviceData data) {
            List<BrightnessStateModifier> modifiers = new ArrayList<>();
            modifiers.add(new BrightnessThermalModifier(handler, listener, data));
            if (flags.isBrightnessWearBedtimeModeClamperEnabled()) {
                modifiers.add(new BrightnessWearBedtimeModeModifier(handler, context,
                        listener, data));
            }

            modifiers.add(new DisplayDimModifier(context));
            modifiers.add(new BrightnessLowPowerModeModifier());
            if (flags.isEvenDimmerEnabled() && data.mDisplayDeviceConfig.isEvenDimmerAvailable()) {
                modifiers.add(new BrightnessLowLuxModifier(handler, listener, context,
                        data.mDisplayDeviceConfig));
            }
            if (flags.useNewHdrBrightnessModifier()) {
                modifiers.add(new HdrBrightnessModifier(handler, context, listener, data));
            }
            return modifiers;
        }

        LightSensorController getLightSensorController(SensorManager sensorManager,
                Context context, LightSensorController.LightSensorListener listener,
                Handler handler) {
            return new LightSensorController(sensorManager, context.getResources(),
                    listener, handler);
        }
    }

    /**
     * Modifier should implement this interface in order to receive display change updates
     */
    interface DisplayDeviceDataListener {
        void onDisplayChanged(DisplayDeviceData displayData);
    }

    /**
     * Config Data for clampers/modifiers
     */
    public static class DisplayDeviceData implements BrightnessThermalModifier.ThermalData,
            BrightnessPowerClamper.PowerData,
            BrightnessWearBedtimeModeModifier.WearBedtimeModeData {
        @NonNull
        private final String mUniqueDisplayId;
        @NonNull
        private final String mThermalThrottlingDataId;
        @NonNull
        private final String mPowerThrottlingDataId;
        @NonNull
        final DisplayDeviceConfig mDisplayDeviceConfig;

        final int mWidth;

        final int mHeight;

        final IBinder mDisplayToken;

        final int mDisplayId;

        public DisplayDeviceData(@NonNull String uniqueDisplayId,
                @NonNull String thermalThrottlingDataId,
                @NonNull String powerThrottlingDataId,
                @NonNull DisplayDeviceConfig displayDeviceConfig,
                int width,
                int height,
                IBinder displayToken,
                int displayId) {
            mUniqueDisplayId = uniqueDisplayId;
            mThermalThrottlingDataId = thermalThrottlingDataId;
            mPowerThrottlingDataId = powerThrottlingDataId;
            mDisplayDeviceConfig = displayDeviceConfig;
            mWidth = width;
            mHeight = height;
            mDisplayToken = displayToken;
            mDisplayId = displayId;
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
        @Override
        public SensorData getTempSensor() {
            return mDisplayDeviceConfig.getTempSensor();
        }

        @NonNull
        SensorData getAmbientLightSensor() {
            return mDisplayDeviceConfig.getAmbientLightSensor();
        }

        int getDisplayId() {
            return mDisplayId;
        }
    }

    /**
     * Stateful modifier should implement this interface and modify aggregatedState.
     * AggregatedState is used by Controller to determine if updatePowerState call is needed
     * to correctly adjust brightness
     */
    interface StatefulModifier {
        void applyStateChange(ModifiersAggregatedState aggregatedState);
    }

    /**
     * A clamper/modifier should implement this interface if it reads user-specific settings
     */
    interface UserSwitchListener {
        void onSwitchUser();
    }

    /**
     * Modifier should implement this interface in order to receive device config updates
     */
    interface DeviceConfigListener {
        void onDeviceConfigChanged();
    }

    /**
     * StatefulModifiers contribute to AggregatedState, that is used to decide if brightness
     * adjustment is needed
     */
    public static class ModifiersAggregatedState {
        float mMaxDesiredHdrRatio = HdrBrightnessModifier.DEFAULT_MAX_HDR_SDR_RATIO;
        float mMaxHdrBrightness = PowerManager.BRIGHTNESS_MAX;
        @Nullable
        Spline mSdrHdrRatioSpline = null;
        @BrightnessInfo.BrightnessMaxReason
        int mMaxBrightnessReason = BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE;
        float mMaxBrightness = PowerManager.BRIGHTNESS_MAX;
    }
}
