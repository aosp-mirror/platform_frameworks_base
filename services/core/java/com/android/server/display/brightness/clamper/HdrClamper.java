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

import android.os.Handler;
import android.os.PowerManager;

import com.android.internal.annotations.VisibleForTesting;

import java.util.HashMap;
import java.util.Map;

public class HdrClamper {

    private final Configuration mConfiguration = new Configuration();

    private final BrightnessClamperController.ClamperChangeListener mClamperChangeListener;

    private final Handler mHandler;

    private final Runnable mDebouncer;

    private float mMaxBrightness = PowerManager.BRIGHTNESS_MAX;

    // brightness change speed, in units per seconds,
    private float mTransitionRate = -1f;

    private float mDesiredMaxBrightness = PowerManager.BRIGHTNESS_MAX;

    private float mDesiredTransitionDuration = -1; // in seconds

    public HdrClamper(BrightnessClamperController.ClamperChangeListener clamperChangeListener,
            Handler handler) {
        mClamperChangeListener = clamperChangeListener;
        mHandler = handler;
        mDebouncer = () -> {
            mTransitionRate = Math.abs((mMaxBrightness - mDesiredMaxBrightness)
                    / mDesiredTransitionDuration);
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
        float expectedMaxBrightness = findBrightnessLimit(ambientLux);
        if (mMaxBrightness == expectedMaxBrightness) {
            mDesiredMaxBrightness = mMaxBrightness;
            mDesiredTransitionDuration = -1;
            mTransitionRate = -1f;
            mHandler.removeCallbacks(mDebouncer);
        } else if (mDesiredMaxBrightness != expectedMaxBrightness) {
            mDesiredMaxBrightness = expectedMaxBrightness;
            long debounceTime;
            if (mDesiredMaxBrightness > mMaxBrightness) {
                debounceTime = mConfiguration.mIncreaseConfig.mDebounceTimeMillis;
                mDesiredTransitionDuration =
                        (float) mConfiguration.mIncreaseConfig.mTransitionTimeMillis / 1000;
            } else {
                debounceTime = mConfiguration.mDecreaseConfig.mDebounceTimeMillis;
                mDesiredTransitionDuration =
                        (float) mConfiguration.mDecreaseConfig.mTransitionTimeMillis / 1000;
            }

            mHandler.removeCallbacks(mDebouncer);
            mHandler.postDelayed(mDebouncer, debounceTime);
        }
    }

    @VisibleForTesting
    Configuration getConfiguration() {
        return mConfiguration;
    }

    private float findBrightnessLimit(float ambientLux) {
        float foundAmbientBoundary = Float.MAX_VALUE;
        float foundMaxBrightness = PowerManager.BRIGHTNESS_MAX;
        for (Map.Entry<Float, Float> brightnessPoint :
                mConfiguration.mMaxBrightnessLimits.entrySet()) {
            float ambientBoundary = brightnessPoint.getKey();
            // find ambient lux upper boundary closest to current ambient lux
            if (ambientBoundary > ambientLux && ambientBoundary < foundAmbientBoundary) {
                foundMaxBrightness = brightnessPoint.getValue();
                foundAmbientBoundary = ambientBoundary;
            }
        }
        return foundMaxBrightness;
    }

    @VisibleForTesting
    static class Configuration {
        final Map<Float, Float> mMaxBrightnessLimits = new HashMap<>();
        final TransitionConfiguration mIncreaseConfig = new TransitionConfiguration();

        final TransitionConfiguration mDecreaseConfig = new TransitionConfiguration();
    }

    @VisibleForTesting
    static class TransitionConfiguration {
        long mDebounceTimeMillis;

        long mTransitionTimeMillis;
    }
}
