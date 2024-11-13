/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.display.brightness.strategy;

import static android.hardware.display.DisplayManagerInternal.DisplayPowerRequest.POLICY_DOZE;
import static android.hardware.display.DisplayManagerInternal.DisplayPowerRequest.POLICY_OFF;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.SystemClock;
import android.util.IndentingPrintWriter;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.display.BrightnessMappingStrategy;
import com.android.server.display.DisplayBrightnessState;
import com.android.server.display.DisplayDeviceConfig;
import com.android.server.display.ScreenOffBrightnessSensorController;
import com.android.server.display.brightness.BrightnessReason;
import com.android.server.display.brightness.BrightnessUtils;
import com.android.server.display.brightness.StrategyExecutionRequest;
import com.android.server.display.brightness.StrategySelectionNotifyRequest;
import com.android.server.display.layout.Layout;
import com.android.server.display.utils.SensorUtils;

import java.io.PrintWriter;

/**
 * This strategy is used when the screen has just turned ON, with auto-brightness ON but there is
 * no valid lux values available yet. In such a case, if configured, we set the brightness state
 * from this
 */
public final class AutoBrightnessFallbackStrategy implements DisplayBrightnessStrategy {

    @Nullable
    private ScreenOffBrightnessSensorController mScreenOffBrightnessSensorController;
    @VisibleForTesting
    @Nullable
    Sensor mScreenOffBrightnessSensor;

    // Indicates if the associated LogicalDisplay is enabled or not.
    private boolean mIsDisplayEnabled;

    // Represents if the associated display is a lead display or not. If not, the variable
    // represents the lead display ID
    private int mLeadDisplayId;

    @NonNull
    private final Injector mInjector;

    public AutoBrightnessFallbackStrategy(Injector injector) {
        mInjector = (injector == null) ? new RealInjector() : injector;
    }

    @Override
    public DisplayBrightnessState updateBrightness(
            StrategyExecutionRequest strategyExecutionRequest) {
        assert mScreenOffBrightnessSensorController != null;
        float brightness = mScreenOffBrightnessSensorController.getAutomaticScreenBrightness();
        BrightnessReason brightnessReason = new BrightnessReason();
        brightnessReason.setReason(BrightnessReason.REASON_SCREEN_OFF_BRIGHTNESS_SENSOR);
        return new DisplayBrightnessState.Builder()
                .setBrightness(brightness)
                .setBrightnessReason(brightnessReason)
                .setDisplayBrightnessStrategyName(getName())
                .setShouldUpdateScreenBrightnessSetting(brightness
                        != strategyExecutionRequest.getCurrentScreenBrightness())
                .build();
    }

    @NonNull
    @Override
    public String getName() {
        return "AutoBrightnessFallbackStrategy";
    }

    @Override
    public int getReason() {
        return BrightnessReason.REASON_SCREEN_OFF_BRIGHTNESS_SENSOR;
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("AutoBrightnessFallbackStrategy:");
        writer.println("  mLeadDisplayId=" + mLeadDisplayId);
        writer.println("  mIsDisplayEnabled=" + mIsDisplayEnabled);
        writer.println("");
        if (mScreenOffBrightnessSensorController != null) {
            IndentingPrintWriter ipw = new IndentingPrintWriter(writer, " ");
            mScreenOffBrightnessSensorController.dump(ipw);
        }
    }

    @Override
    public void strategySelectionPostProcessor(
            StrategySelectionNotifyRequest strategySelectionNotifyRequest) {
        if (mScreenOffBrightnessSensorController != null) {
            int policy = strategySelectionNotifyRequest.getDisplayPowerRequest().policy;
            mScreenOffBrightnessSensorController.setLightSensorEnabled(
                    strategySelectionNotifyRequest.isAutoBrightnessEnabled() && mIsDisplayEnabled
                            && (policy == POLICY_OFF || (policy == POLICY_DOZE
                            && !strategySelectionNotifyRequest
                            .isAllowAutoBrightnessWhileDozingConfig()))
                            && mLeadDisplayId == Layout.NO_LEAD_DISPLAY);
        }
    }

    /**
     * Gets the associated ScreenOffBrightnessSensorController, controlling the brightness when
     * auto-brightness is enabled, but the lux is not valid yet.
     */
    public ScreenOffBrightnessSensorController getScreenOffBrightnessSensorController() {
        return mScreenOffBrightnessSensorController;
    }

    /**
     * Sets up the auto brightness fallback sensor
     */
    public void setupAutoBrightnessFallbackSensor(SensorManager sensorManager,
            DisplayDeviceConfig displayDeviceConfig, Handler handler,
            BrightnessMappingStrategy brightnessMappingStrategy, boolean isDisplayEnabled,
            int leadDisplayId) {
        mIsDisplayEnabled = isDisplayEnabled;
        mLeadDisplayId = leadDisplayId;
        if (mScreenOffBrightnessSensorController != null) {
            mScreenOffBrightnessSensorController.stop();
            mScreenOffBrightnessSensorController = null;
        }
        loadScreenOffBrightnessSensor(sensorManager, displayDeviceConfig);
        int[] sensorValueToLux = displayDeviceConfig.getScreenOffBrightnessSensorValueToLux();
        if (mScreenOffBrightnessSensor != null && sensorValueToLux != null) {
            mScreenOffBrightnessSensorController =
                    mInjector.getScreenOffBrightnessSensorController(
                            sensorManager,
                            mScreenOffBrightnessSensor,
                            handler,
                            SystemClock::uptimeMillis,
                            sensorValueToLux,
                            brightnessMappingStrategy);
        }
    }

    /**
     * Stops the associated ScreenOffBrightnessSensorController responsible for managing the
     * brightness when this strategy is selected
     */
    public void stop() {
        if (mScreenOffBrightnessSensorController != null) {
            mScreenOffBrightnessSensorController.stop();
        }
    }

    /**
     * Checks if the strategy is valid, based on its internal state. Note that there can still be
     * external factors like auto-brightness not being enabled because of which this strategy is not
     * selected
     */
    public boolean isValid() {
        return mScreenOffBrightnessSensorController != null
                && BrightnessUtils.isValidBrightnessValue(
                mScreenOffBrightnessSensorController.getAutomaticScreenBrightness());
    }

    private void loadScreenOffBrightnessSensor(SensorManager sensorManager,
            DisplayDeviceConfig displayDeviceConfig) {
        mScreenOffBrightnessSensor = mInjector.getScreenOffBrightnessSensor(sensorManager,
                displayDeviceConfig);
    }


    @VisibleForTesting
    interface Injector {
        Sensor getScreenOffBrightnessSensor(SensorManager sensorManager,
                DisplayDeviceConfig displayDeviceConfig);

        ScreenOffBrightnessSensorController getScreenOffBrightnessSensorController(
                SensorManager sensorManager,
                Sensor lightSensor,
                Handler handler,
                ScreenOffBrightnessSensorController.Clock clock,
                int[] sensorValueToLux,
                BrightnessMappingStrategy brightnessMapper);
    }

    static class RealInjector implements Injector {
        @Override
        public Sensor getScreenOffBrightnessSensor(SensorManager sensorManager,
                DisplayDeviceConfig displayDeviceConfig) {
            return SensorUtils.findSensor(sensorManager,
                    displayDeviceConfig.getScreenOffBrightnessSensor(), SensorUtils.NO_FALLBACK);
        }

        @Override
        public ScreenOffBrightnessSensorController getScreenOffBrightnessSensorController(
                SensorManager sensorManager, Sensor lightSensor, Handler handler,
                ScreenOffBrightnessSensorController.Clock clock, int[] sensorValueToLux,
                BrightnessMappingStrategy brightnessMapper) {
            return new ScreenOffBrightnessSensorController(
                    sensorManager, lightSensor, handler, clock, sensorValueToLux, brightnessMapper);
        }
    }
}
