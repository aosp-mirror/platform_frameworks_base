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

import android.hardware.display.BrightnessInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.display.brightness.clamper.HdrClamper;
import com.android.server.display.feature.DisplayManagerFlags;

import java.io.PrintWriter;
import java.util.function.BooleanSupplier;

class BrightnessRangeController {

    private final HighBrightnessModeController mHbmController;
    private final NormalBrightnessModeController mNormalBrightnessModeController;

    private final HdrClamper mHdrClamper;

    private final Runnable mModeChangeCallback;
    private final boolean mUseNbmController;

    private final boolean mUseHdrClamper;


    BrightnessRangeController(HighBrightnessModeController hbmController,
            Runnable modeChangeCallback, DisplayDeviceConfig displayDeviceConfig, Handler handler,
            DisplayManagerFlags flags, IBinder displayToken, DisplayDeviceInfo info) {
        this(hbmController, modeChangeCallback, displayDeviceConfig,
                new NormalBrightnessModeController(),
                new HdrClamper(modeChangeCallback::run, new Handler(handler.getLooper())), flags,
                displayToken, info);
    }

    @VisibleForTesting
    BrightnessRangeController(HighBrightnessModeController hbmController,
            Runnable modeChangeCallback, DisplayDeviceConfig displayDeviceConfig,
            NormalBrightnessModeController normalBrightnessModeController,
            HdrClamper hdrClamper, DisplayManagerFlags flags, IBinder displayToken,
            DisplayDeviceInfo info) {
        mHbmController = hbmController;
        mModeChangeCallback = modeChangeCallback;
        mHdrClamper = hdrClamper;
        mNormalBrightnessModeController = normalBrightnessModeController;
        mUseHdrClamper = flags.isHdrClamperEnabled();
        mUseNbmController = flags.isNbmControllerEnabled();
        if (mUseNbmController) {
            mNormalBrightnessModeController.resetNbmData(
                    displayDeviceConfig.getLuxThrottlingData());
        }
        updateHdrClamper(info, displayToken, displayDeviceConfig);
    }

    void dump(PrintWriter pw) {
        pw.println("BrightnessRangeController:");
        pw.println("  mUseNormalBrightnessController=" + mUseNbmController);
        pw.println("  mUseHdrClamper=" + mUseHdrClamper);
        mHbmController.dump(pw);
        mNormalBrightnessModeController.dump(pw);
        mHdrClamper.dump(pw);
    }

    void onAmbientLuxChange(float ambientLux) {
        applyChanges(
                () -> mNormalBrightnessModeController.onAmbientLuxChange(ambientLux),
                () -> mHbmController.onAmbientLuxChange(ambientLux)
        );
        if (mUseHdrClamper) {
            mHdrClamper.onAmbientLuxChange(ambientLux);
        }
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
        updateHdrClamper(info, token, displayDeviceConfig);
    }

    void stop() {
        mHbmController.stop();
        mHdrClamper.stop();
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
        // nbmController might adjust maxBrightness only if device does not support HBM or
        // hbm is currently not allowed
        if (mUseNbmController
                && (!mHbmController.deviceSupportsHbm()
                || !mHbmController.isHbmCurrentlyAllowed())) {
            return Math.min(mHbmController.getCurrentBrightnessMax(),
                    mNormalBrightnessModeController.getCurrentBrightnessMax());
        }
        return mHbmController.getCurrentBrightnessMax();
    }

    int getHighBrightnessMode() {
        return mHbmController.getHighBrightnessMode();
    }

    float getHdrBrightnessValue() {
        float hdrBrightness = mHbmController.getHdrBrightnessValue();
        float brightnessMax = mUseHdrClamper ? mHdrClamper.getMaxBrightness()
                : PowerManager.BRIGHTNESS_MAX;
        return Math.min(hdrBrightness, brightnessMax);
    }

    float getTransitionPoint() {
        return mHbmController.getTransitionPoint();
    }

    private void updateHdrClamper(DisplayDeviceInfo info, IBinder token,
            DisplayDeviceConfig displayDeviceConfig) {
        if (mUseHdrClamper) {
            DisplayDeviceConfig.HighBrightnessModeData hbmData =
                    displayDeviceConfig.getHighBrightnessModeData();
            float minimumHdrPercentOfScreen =
                    hbmData == null ? -1f : hbmData.minimumHdrPercentOfScreen;
            mHdrClamper.resetHdrConfig(displayDeviceConfig.getHdrBrightnessData(), info.width,
                    info.height, minimumHdrPercentOfScreen, token);
        }
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

    public float getHdrTransitionRate() {
        return mUseHdrClamper ? mHdrClamper.getTransitionRate() : -1;
    }
}
