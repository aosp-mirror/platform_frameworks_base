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

package com.android.server.display;

import android.content.res.Resources;
import android.hardware.display.BrightnessInfo;
import android.os.IBinder;
import android.provider.DeviceConfigInterface;

import com.android.internal.R;
import com.android.server.display.feature.DeviceConfigParameterProvider;

import java.io.PrintWriter;
import java.util.function.BooleanSupplier;

class BrightnessRangeController {

    private final HighBrightnessModeController mHbmController;
    private final NormalBrightnessModeController mNormalBrightnessModeController =
            new NormalBrightnessModeController();

    private final Runnable mModeChangeCallback;
    private final boolean mUseNbmController;
    private Resources mResources;


    BrightnessRangeController(HighBrightnessModeController hbmController,
            Runnable modeChangeCallback, DisplayDeviceConfig displayDeviceConfig,
            Resources resources) {
        this(hbmController, modeChangeCallback, displayDeviceConfig,
                new DeviceConfigParameterProvider(DeviceConfigInterface.REAL), resources);
    }

    BrightnessRangeController(HighBrightnessModeController hbmController,
            Runnable modeChangeCallback, DisplayDeviceConfig displayDeviceConfig,
            DeviceConfigParameterProvider configParameterProvider, Resources resources) {
        mHbmController = hbmController;
        mModeChangeCallback = modeChangeCallback;
        mResources = resources;
        mUseNbmController = configParameterProvider.isNormalBrightnessControllerFeatureEnabled() ||
            resources.getBoolean(R.bool.config_allowNormalBrightnessControllerFeature);
        mNormalBrightnessModeController.resetNbmData(displayDeviceConfig.getLuxThrottlingData());
    }

    void dump(PrintWriter pw) {
        pw.println("BrightnessRangeController:");
        pw.println("  mUseNormalBrightnessController=" + mUseNbmController);
        mHbmController.dump(pw);
        mNormalBrightnessModeController.dump(pw);

    }

    void onAmbientLuxChange(float ambientLux) {
        applyChanges(
                () -> mNormalBrightnessModeController.onAmbientLuxChange(ambientLux),
                () -> mHbmController.onAmbientLuxChange(ambientLux)
        );
    }

    float getNormalBrightnessMax() {
        return mHbmController.getNormalBrightnessMax();
    }

    void loadFromConfig(HighBrightnessModeMetadata hbmMetadata, IBinder token,
            DisplayDeviceInfo info, DisplayDeviceConfig displayDeviceConfig) {
        applyChanges(
                () -> mNormalBrightnessModeController.resetNbmData(
                        displayDeviceConfig.getLuxThrottlingData()),
                () -> {
                    mHbmController.setHighBrightnessModeMetadata(hbmMetadata);
                    mHbmController.resetHbmData(info.width, info.height, token, info.uniqueId,
                            displayDeviceConfig.getHighBrightnessModeData(),
                            displayDeviceConfig::getHdrBrightnessFromSdr);
                }
        );
    }

    void stop() {
        mHbmController.stop();
    }

    void setAutoBrightnessEnabled(int state) {
        applyChanges(
                () -> mNormalBrightnessModeController.setAutoBrightnessState(state),
                () ->  mHbmController.setAutoBrightnessEnabled(state)
        );
    }

    void onBrightnessChanged(float brightness, float unthrottledBrightness,
            @BrightnessInfo.BrightnessMaxReason int throttlingReason) {
        mHbmController.onBrightnessChanged(brightness, unthrottledBrightness, throttlingReason);
    }

    float getCurrentBrightnessMin() {
        return mHbmController.getCurrentBrightnessMin();
    }


    float getCurrentBrightnessMax() {
        if (mUseNbmController && mHbmController.getHighBrightnessMode()
                == BrightnessInfo.HIGH_BRIGHTNESS_MODE_OFF) {
            return Math.min(mHbmController.getCurrentBrightnessMax(),
                    mNormalBrightnessModeController.getCurrentBrightnessMax());
        }
        return mHbmController.getCurrentBrightnessMax();
    }

    int getHighBrightnessMode() {
        return mHbmController.getHighBrightnessMode();
    }

    float getHdrBrightnessValue() {
        return mHbmController.getHdrBrightnessValue();
    }

    float getTransitionPoint() {
        return mHbmController.getTransitionPoint();
    }

    private void applyChanges(BooleanSupplier nbmChangesFunc, Runnable hbmChangesFunc) {
        if (mUseNbmController) {
            boolean nbmTransitionChanged = nbmChangesFunc.getAsBoolean();
            hbmChangesFunc.run();
            // if nbm transition changed - trigger callback
            // HighBrightnessModeController handles sending changes itself
            if (nbmTransitionChanged) {
                mModeChangeCallback.run();
            }
        } else {
            hbmChangesFunc.run();
        }
    }
}
