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
import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.view.SurfaceControlHdrLayerInfoListener;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.display.AutomaticBrightnessController;
import com.android.server.display.config.HdrBrightnessData;

import java.io.PrintWriter;
import java.util.Map;

public class HdrClamper {

    private final BrightnessClamperController.ClamperChangeListener mClamperChangeListener;

    private final Handler mHandler;

    private final Runnable mDebouncer;

    private final HdrLayerInfoListener mHdrListener;

    @Nullable
    private HdrBrightnessData mHdrBrightnessData = null;

    @Nullable
    private IBinder mRegisteredDisplayToken = null;

    private float mAmbientLux = Float.MAX_VALUE;

    private boolean mHdrVisible = false;

    private float mMaxBrightness = PowerManager.BRIGHTNESS_MAX;
    private float mDesiredMaxBrightness = PowerManager.BRIGHTNESS_MAX;

    // brightness change speed, in units per seconds,
    private float mTransitionRate = -1f;
    private float mDesiredTransitionRate = -1f;

    private boolean mAutoBrightnessEnabled = false;

    /**
     * Indicates that maxBrightness is changed, and we should use slow transition
     */
    private boolean mUseSlowTransition = false;

    public HdrClamper(BrightnessClamperController.ClamperChangeListener clamperChangeListener,
            Handler handler) {
        this(clamperChangeListener, handler, new Injector());
    }

    @VisibleForTesting
    public HdrClamper(BrightnessClamperController.ClamperChangeListener clamperChangeListener,
            Handler handler, Injector injector) {
        mClamperChangeListener = clamperChangeListener;
        mHandler = handler;
        mDebouncer = () -> {
            mTransitionRate = mDesiredTransitionRate;
            mMaxBrightness = mDesiredMaxBrightness;
            mUseSlowTransition = true;
            mClamperChangeListener.onChanged();
        };
        mHdrListener = injector.getHdrListener((visible) -> {
            mHdrVisible = visible;
            recalculateBrightnessCap(mHdrBrightnessData, mAmbientLux, mHdrVisible);
        }, handler);
    }

    /**
     * Applies clamping
     * Called in same looper: mHandler.getLooper()
     */
    public float clamp(float brightness) {
        return Math.min(brightness, mMaxBrightness);
    }

    @VisibleForTesting
    public float getMaxBrightness() {
        return mMaxBrightness;
    }

    // Called in same looper: mHandler.getLooper()
    public float getTransitionRate() {
        float expectedTransitionRate =  mUseSlowTransition ? mTransitionRate : -1;
        mUseSlowTransition = false;
        return  expectedTransitionRate;
    }

    /**
     * Updates brightness cap in response to ambient lux change.
     * Called by ABC in same looper: mHandler.getLooper()
     */
    public void onAmbientLuxChange(float ambientLux) {
        mAmbientLux = ambientLux;
        recalculateBrightnessCap(mHdrBrightnessData, ambientLux, mHdrVisible);
    }

    /**
     * Updates brightness cap config.
     * Called in same looper: mHandler.getLooper()
     */
    @SuppressLint("AndroidFrameworkRequiresPermission")
    public void resetHdrConfig(HdrBrightnessData data, int width, int height,
            float minimumHdrPercentOfScreen, IBinder displayToken) {
        mHdrBrightnessData = data;
        mHdrListener.mHdrMinPixels = (float) (width * height) * minimumHdrPercentOfScreen;
        if (displayToken != mRegisteredDisplayToken) { // token changed, resubscribe
            if (mRegisteredDisplayToken != null) { // previous token not null, unsubscribe
                mHdrListener.unregister(mRegisteredDisplayToken);
                mHdrVisible = false;
                mRegisteredDisplayToken = null;
            }
            // new token not null and hdr min % of the screen is set, subscribe.
            // e.g. for virtual display, HBM data will be missing and HdrListener
            // should not be registered
            if (displayToken != null && mHdrListener.mHdrMinPixels >= 0) {
                mHdrListener.register(displayToken);
                mRegisteredDisplayToken = displayToken;
            }
        }
        recalculateBrightnessCap(data, mAmbientLux, mHdrVisible);
    }

    /**
     * Sets state of auto brightness to temporary disabling this Clamper if auto brightness is off.
     * The issue is tracked here: b/322445088
     */
    public void setAutoBrightnessState(int state) {
        boolean isEnabled = state == AutomaticBrightnessController.AUTO_BRIGHTNESS_ENABLED;
        if (isEnabled != mAutoBrightnessEnabled) {
            mAutoBrightnessEnabled = isEnabled;
            recalculateBrightnessCap(mHdrBrightnessData, mAmbientLux, mHdrVisible);
        }
    }

    /** Clean up all resources */
    @SuppressLint("AndroidFrameworkRequiresPermission")
    public void stop() {
        if (mRegisteredDisplayToken != null) {
            mHdrListener.unregister(mRegisteredDisplayToken);
        }
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
        pw.println("  mHdrVisible=" + mHdrVisible);
        pw.println("  mHdrListener.mHdrMinPixels=" + mHdrListener.mHdrMinPixels);
        pw.println("  mHdrBrightnessData=" + (mHdrBrightnessData == null ? "null"
                : mHdrBrightnessData.toString()));
        pw.println("  mHdrListener registered=" + (mRegisteredDisplayToken != null));
        pw.println("  mAmbientLux=" + mAmbientLux);
        pw.println("  mAutoBrightnessEnabled=" + mAutoBrightnessEnabled);
    }

    private void reset() {
        if (mMaxBrightness == PowerManager.BRIGHTNESS_MAX
                && mDesiredMaxBrightness == PowerManager.BRIGHTNESS_MAX && mTransitionRate == -1f
                && mDesiredTransitionRate == -1f) { // already done reset, do nothing
            return;
        }
        mHandler.removeCallbacks(mDebouncer);
        mMaxBrightness = PowerManager.BRIGHTNESS_MAX;
        mDesiredMaxBrightness = PowerManager.BRIGHTNESS_MAX;
        mDesiredTransitionRate = -1f;
        mTransitionRate = -1f;
        mUseSlowTransition = false;
        mClamperChangeListener.onChanged();
    }

    private void recalculateBrightnessCap(HdrBrightnessData data, float ambientLux,
            boolean hdrVisible) {
        // AutoBrightnessController sends ambientLux values *only* when auto brightness enabled.
        // Temporary disabling this Clamper if auto brightness is off, to avoid capping
        // brightness based on stale ambient lux. The issue is tracked here: b/322445088
        if (data == null || !hdrVisible || !mAutoBrightnessEnabled) {
            reset();
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
            if (mDesiredMaxBrightness > mMaxBrightness) {
                debounceTime = mHdrBrightnessData.mBrightnessIncreaseDebounceMillis;
                mDesiredTransitionRate = mHdrBrightnessData.mScreenBrightnessRampIncrease;
            } else {
                debounceTime = mHdrBrightnessData.mBrightnessDecreaseDebounceMillis;
                mDesiredTransitionRate = mHdrBrightnessData.mScreenBrightnessRampDecrease;
            }

            mHandler.removeCallbacks(mDebouncer);
            mHandler.postDelayed(mDebouncer, debounceTime);
        }
        // do nothing if expectedMaxBrightness == mDesiredMaxBrightness
        // && expectedMaxBrightness != mMaxBrightness
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

    @FunctionalInterface
    interface HdrListener {
        void onHdrVisible(boolean visible);
    }

    static class HdrLayerInfoListener extends SurfaceControlHdrLayerInfoListener {
        private final HdrListener mHdrListener;

        private final Handler mHandler;

        private float mHdrMinPixels = Float.MAX_VALUE;

        HdrLayerInfoListener(HdrListener hdrListener, Handler handler) {
            mHdrListener = hdrListener;
            mHandler = handler;
        }

        @Override
        public void onHdrInfoChanged(IBinder displayToken, int numberOfHdrLayers, int maxW,
                int maxH, int flags, float maxDesiredHdrSdrRatio) {
            mHandler.post(() ->
                    mHdrListener.onHdrVisible(
                            numberOfHdrLayers > 0 && (float) (maxW * maxH) >= mHdrMinPixels));
        }
    }

    static class Injector {
        HdrLayerInfoListener getHdrListener(HdrListener hdrListener, Handler handler) {
            return new HdrLayerInfoListener(hdrListener, handler);
        }
    }
}
