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

import static com.android.server.display.brightness.clamper.BrightnessClamper.Type;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.display.DisplayManagerInternal;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.PowerManager;
import android.provider.DeviceConfig;
import android.provider.DeviceConfigInterface;
import android.util.IndentingPrintWriter;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.display.DisplayBrightnessState;
import com.android.server.display.DisplayDeviceConfig;
import com.android.server.display.DisplayDeviceConfig.ThermalBrightnessThrottlingData;
import com.android.server.display.feature.DeviceConfigParameterProvider;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Clampers controller, all in DisplayControllerHandler
 */
public class BrightnessClamperController {

    private static final boolean THERMAL_ENABLED = false;

    private final DeviceConfigParameterProvider mDeviceConfigParameterProvider;
    private final Handler mHandler;
    private final ClamperChangeListener mClamperChangeListenerExternal;

    private final Executor mExecutor;
    private final List<BrightnessClamper<? super DisplayDeviceData>> mClampers = new ArrayList<>();

    private final List<BrightnessModifier> mModifiers = new ArrayList<>();
    private final DeviceConfig.OnPropertiesChangedListener mOnPropertiesChangedListener =
            properties -> mClampers.forEach(BrightnessClamper::onDeviceConfigChanged);
    private float mBrightnessCap = PowerManager.BRIGHTNESS_MAX;
    @Nullable
    private Type mClamperType = null;

    public BrightnessClamperController(Handler handler,
            ClamperChangeListener clamperChangeListener, DisplayDeviceData data) {
        this(new Injector(), handler, clamperChangeListener, data);
    }

    @VisibleForTesting
    BrightnessClamperController(Injector injector, Handler handler,
            ClamperChangeListener clamperChangeListener, DisplayDeviceData data) {
        mDeviceConfigParameterProvider = injector.getDeviceConfigParameterProvider();
        mHandler = handler;
        mClamperChangeListenerExternal = clamperChangeListener;
        mExecutor = new HandlerExecutor(handler);

        Runnable clamperChangeRunnableInternal = this::recalculateBrightnessCap;

        ClamperChangeListener clamperChangeListenerInternal = () -> {
            if (!mHandler.hasCallbacks(clamperChangeRunnableInternal)) {
                mHandler.post(clamperChangeRunnableInternal);
            }
        };

        if (THERMAL_ENABLED) {
            mClampers.add(
                    new BrightnessThermalClamper(handler, clamperChangeListenerInternal, data));
        }
        mModifiers.add(new BrightnessLowPowerModeModifier());
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
            float brightnessValue, boolean slowChange) {
        float cappedBrightness = Math.min(brightnessValue, mBrightnessCap);

        DisplayBrightnessState.Builder builder = DisplayBrightnessState.builder();
        builder.setIsSlowChange(slowChange);
        builder.setBrightness(cappedBrightness);

        for (int i = 0; i < mModifiers.size(); i++) {
            mModifiers.get(i).apply(request, builder);
        }
        return builder.build();
    }

    /**
     * Used to dump ClampersController state.
     */
    public void dump(PrintWriter writer) {
        writer.println("BrightnessClampersController:");
        writer.println("  mBrightnessCap: " + mBrightnessCap);
        writer.println("  mClamperType: " + mClamperType);
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
    }


    // Called in DisplayControllerHandler
    private void recalculateBrightnessCap() {
        float brightnessCap = PowerManager.BRIGHTNESS_MAX;
        Type clamperType = null;

        BrightnessClamper<?> minClamper = mClampers.stream()
                .filter(BrightnessClamper::isActive)
                .min((clamper1, clamper2) -> Float.compare(clamper1.getBrightnessCap(),
                        clamper2.getBrightnessCap())).orElse(null);

        if (minClamper != null) {
            brightnessCap = minClamper.getBrightnessCap();
            clamperType = minClamper.getType();
        }

        if (mBrightnessCap != brightnessCap || mClamperType != clamperType) {
            mBrightnessCap = brightnessCap;
            mClamperType = clamperType;
            mClamperChangeListenerExternal.onChanged();
        }
    }

    private void start() {
        if (!mClampers.isEmpty()) {
            mDeviceConfigParameterProvider.addOnPropertiesChangedListener(
                    mExecutor, mOnPropertiesChangedListener);
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
    }

    /**
     * Data for clampers
     */
    public static class DisplayDeviceData implements BrightnessThermalClamper.ThermalData {
        @NonNull
        private final String mUniqueDisplayId;
        @NonNull
        private final String mThermalThrottlingDataId;

        private final DisplayDeviceConfig mDisplayDeviceConfig;

        public DisplayDeviceData(@NonNull String uniqueDisplayId,
                @NonNull String thermalThrottlingDataId,
                @NonNull DisplayDeviceConfig displayDeviceConfig) {
            mUniqueDisplayId = uniqueDisplayId;
            mThermalThrottlingDataId = thermalThrottlingDataId;
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
    }
}
