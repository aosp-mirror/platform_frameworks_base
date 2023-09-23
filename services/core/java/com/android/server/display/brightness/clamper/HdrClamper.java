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

import android.annotation.Nullable;
import android.os.Handler;
import android.os.PowerManager;

import com.android.server.display.config.HdrBrightnessData;

import java.io.PrintWriter;
import java.util.Map;

public class HdrClamper {

    private final BrightnessClamperController.ClamperChangeListener mClamperChangeListener;

    private final Handler mHandler;

    private final Runnable mDebouncer;

    @Nullable
    private HdrBrightnessData mHdrBrightnessData = null;

    private float mAmbientLux = Float.MAX_VALUE;

    private float mMaxBrightness = PowerManager.BRIGHTNESS_MAX;
    private float mDesiredMaxBrightness = PowerManager.BRIGHTNESS_MAX;

    // brightness change speed, in units per seconds,
    private float mTransitionRate = -1f;
    private float mDesiredTransitionRate = -1f;

    public HdrClamper(BrightnessClamperController.ClamperChangeListener clamperChangeListener,
            Handler handler) {
        mClamperChangeListener = clamperChangeListener;
        mHandler = handler;
        mDebouncer = () -> {
            mTransitionRate = mDesiredTransitionRate;
            mMaxBrightness = mDesiredMaxBrightness;
            mClamperChangeListener.onChanged();
        };
    }

    // Called in same looper: mHandler.getLooper()
    public float getMaxBrightness() {
        return mMaxBrightness;
    }

    // Called in same looper: mHandler.getLooper()
    public float getTransitionRate() {
        return mTransitionRate;
    }

    /**
     * Updates brightness cap in response to ambient lux change.
     * Called by ABC in same looper: mHandler.getLooper()
     */
    public void onAmbientLuxChange(float ambientLux) {
        mAmbientLux = ambientLux;
        recalculateBrightnessCap(mHdrBrightnessData, ambientLux);
    }

    /**
     * Updates brightness cap config.
     * Called in same looper: mHandler.getLooper()
     */
    public void resetHdrConfig(HdrBrightnessData data) {
        mHdrBrightnessData = data;
        recalculateBrightnessCap(data, mAmbientLux);
    }

    /**
     * Dumps the state of HdrClamper.
     */
    public void dump(PrintWriter pw) {
        pw.println("HdrClamper:");
        pw.println("  mMaxBrightness=" + mMaxBrightness);
        pw.println("  mDesiredMaxBrightness=" + mDesiredMaxBrightness);
        pw.println("  mTransitionRate=" + mTransitionRate);
        pw.println("  mDesiredTransitionRate=" + mDesiredTransitionRate);
        pw.println("  mHdrBrightnessData=" + (mHdrBrightnessData == null ? "null"
                : mHdrBrightnessData.toString()));
        pw.println("  mAmbientLux=" + mAmbientLux);
    }

    private void recalculateBrightnessCap(HdrBrightnessData data, float ambientLux) {
        if (data == null) {
            mHandler.removeCallbacks(mDebouncer);
            return;
        }
        float expectedMaxBrightness = findBrightnessLimit(data, ambientLux);

        if (mMaxBrightness == expectedMaxBrightness) {
            mDesiredMaxBrightness = mMaxBrightness;
            mDesiredTransitionRate = -1f;
            mTransitionRate = -1f;
            mHandler.removeCallbacks(mDebouncer);
        } else if (mDesiredMaxBrightness != expectedMaxBrightness) {
            mDesiredMaxBrightness = expectedMaxBrightness;
            long debounceTime;
            long transitionDuration;
            if (mDesiredMaxBrightness > mMaxBrightness) {
                debounceTime = mHdrBrightnessData.mBrightnessIncreaseDebounceMillis;
                transitionDuration = mHdrBrightnessData.mBrightnessIncreaseDurationMillis;
            } else {
                debounceTime = mHdrBrightnessData.mBrightnessDecreaseDebounceMillis;
                transitionDuration = mHdrBrightnessData.mBrightnessDecreaseDurationMillis;
            }
            mDesiredTransitionRate = Math.abs(
                    (mMaxBrightness - mDesiredMaxBrightness) * 1000f / transitionDuration);

            mHandler.removeCallbacks(mDebouncer);
            mHandler.postDelayed(mDebouncer, debounceTime);
        }
    }

    private float findBrightnessLimit(HdrBrightnessData data, float ambientLux) {
        float foundAmbientBoundary = Float.MAX_VALUE;
        float foundMaxBrightness = PowerManager.BRIGHTNESS_MAX;
        for (Map.Entry<Float, Float> brightnessPoint :
                data.mMaxBrightnessLimits.entrySet()) {
            float ambientBoundary = brightnessPoint.getKey();
            // find ambient lux upper boundary closest to current ambient lux
            if (ambientBoundary > ambientLux && ambientBoundary < foundAmbientBoundary) {
                foundMaxBrightness = brightnessPoint.getValue();
                foundAmbientBoundary = ambientBoundary;
            }
        }
        return foundMaxBrightness;
    }
}
