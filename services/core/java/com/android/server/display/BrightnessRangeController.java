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
import android.os.IBinder;

import java.io.PrintWriter;

class BrightnessRangeController {

    private static final boolean NBM_FEATURE_FLAG = false;

    private final HighBrightnessModeController mHbmController;
    private final NormalBrightnessModeController mNormalBrightnessModeController =
            new NormalBrightnessModeController();

    private final Runnable mModeChangeCallback;

    BrightnessRangeController(HighBrightnessModeController hbmController,
            Runnable modeChangeCallback) {
        mHbmController = hbmController;
        mModeChangeCallback = modeChangeCallback;
    }


    void dump(PrintWriter pw) {
        mHbmController.dump(pw);
    }

    void onAmbientLuxChange(float ambientLux) {
        if (NBM_FEATURE_FLAG) {
            boolean nbmTransitionChanged = mNormalBrightnessModeController.onAmbientLuxChange(
                    ambientLux);
            int previousHbm = mHbmController.getHighBrightnessMode();
            mHbmController.onAmbientLuxChange(ambientLux);
            int nextHbm = mHbmController.getHighBrightnessMode();
            // if hbm changed - callback was triggered in mHbmController.onAmbientLuxChange
            // if nbm transition not changed - no need to trigger callback
            if (previousHbm == nextHbm && nbmTransitionChanged) {
                mModeChangeCallback.run();
            }
        } else {
            mHbmController.onAmbientLuxChange(ambientLux);
        }
    }

    float getNormalBrightnessMax() {
        return mHbmController.getNormalBrightnessMax();
    }

    void loadFromConfig(HighBrightnessModeMetadata hbmMetadata, IBinder token,
            DisplayDeviceInfo info, DisplayDeviceConfig displayDeviceConfig) {
        mHbmController.setHighBrightnessModeMetadata(hbmMetadata);
        mHbmController.resetHbmData(info.width, info.height, token, info.uniqueId,
                displayDeviceConfig.getHighBrightnessModeData(),
                displayDeviceConfig::getHdrBrightnessFromSdr);
    }

    void stop() {
        mHbmController.stop();
    }

    void setAutoBrightnessEnabled(int state) {
        mHbmController.setAutoBrightnessEnabled(state);
    }

    void onBrightnessChanged(float brightness, float unthrottledBrightness,
            @BrightnessInfo.BrightnessMaxReason int throttlingReason) {
        mHbmController.onBrightnessChanged(brightness, unthrottledBrightness, throttlingReason);
    }

    float getCurrentBrightnessMin() {
        return mHbmController.getCurrentBrightnessMin();
    }


    float getCurrentBrightnessMax() {
        if (NBM_FEATURE_FLAG && mHbmController.getHighBrightnessMode()
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
}
