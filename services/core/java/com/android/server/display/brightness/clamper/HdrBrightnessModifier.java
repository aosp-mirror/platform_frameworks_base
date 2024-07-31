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

package com.android.server.display.brightness.clamper;

import static com.android.server.display.DisplayBrightnessState.CUSTOM_ANIMATION_RATE_NOT_SET;
import static com.android.server.display.brightness.clamper.LightSensorController.INVALID_LUX;

import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.hardware.display.DisplayManagerInternal;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.view.SurfaceControlHdrLayerInfoListener;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.display.BrightnessSynchronizer;
import com.android.server.display.DisplayBrightnessState;
import com.android.server.display.DisplayDeviceConfig;
import com.android.server.display.brightness.BrightnessReason;
import com.android.server.display.config.HdrBrightnessData;

import java.io.PrintWriter;
import java.util.Map;

public class HdrBrightnessModifier implements BrightnessStateModifier,
        BrightnessClamperController.DisplayDeviceDataListener,
        BrightnessClamperController.StatefulModifier {

    static final float DEFAULT_MAX_HDR_SDR_RATIO = 1.0f;
    private static final float DEFAULT_HDR_LAYER_SIZE = -1.0f;

    private final SurfaceControlHdrLayerInfoListener mHdrListener =
            new SurfaceControlHdrLayerInfoListener() {
                @Override
                public void onHdrInfoChanged(IBinder displayToken, int numberOfHdrLayers, int maxW,
                        int maxH, int flags, float maxDesiredHdrSdrRatio) {
                    boolean hdrLayerPresent = numberOfHdrLayers > 0;
                    mHandler.post(() -> HdrBrightnessModifier.this.onHdrInfoChanged(
                            hdrLayerPresent ? (float) (maxW * maxH) : DEFAULT_HDR_LAYER_SIZE,
                            hdrLayerPresent ? maxDesiredHdrSdrRatio : DEFAULT_MAX_HDR_SDR_RATIO));
                }
            };

    private final Handler mHandler;
    private final BrightnessClamperController.ClamperChangeListener mClamperChangeListener;
    private final Injector mInjector;
    private final Runnable mDebouncer;

    private IBinder mRegisteredDisplayToken;

    private DisplayDeviceConfig mDisplayDeviceConfig;
    @Nullable
    private HdrBrightnessData mHdrBrightnessData;
    private float mScreenSize;

    private float mMaxDesiredHdrRatio = DEFAULT_MAX_HDR_SDR_RATIO;
    private float mHdrLayerSize = DEFAULT_HDR_LAYER_SIZE;

    private float mAmbientLux = INVALID_LUX;

    private Mode mMode = Mode.NO_HDR;
    // The maximum brightness allowed for current lux
    private float mMaxBrightness = PowerManager.BRIGHTNESS_MAX;
    private float mPendingMaxBrightness = PowerManager.BRIGHTNESS_MAX;
    // brightness change speed, in units per seconds. Applied only on ambient lux changes
    private float mTransitionRate = CUSTOM_ANIMATION_RATE_NOT_SET;
    private float mPendingTransitionRate = CUSTOM_ANIMATION_RATE_NOT_SET;

    HdrBrightnessModifier(Handler handler,
            BrightnessClamperController.ClamperChangeListener clamperChangeListener,
            BrightnessClamperController.DisplayDeviceData displayData) {
        this(new Handler(handler.getLooper()), clamperChangeListener, new Injector(), displayData);
    }

    @VisibleForTesting
    HdrBrightnessModifier(Handler handler,
            BrightnessClamperController.ClamperChangeListener clamperChangeListener,
            Injector injector,
            BrightnessClamperController.DisplayDeviceData displayData) {
        mHandler = handler;
        mClamperChangeListener = clamperChangeListener;
        mInjector = injector;
        mDebouncer = () -> {
            mTransitionRate = mPendingTransitionRate;
            mMaxBrightness = mPendingMaxBrightness;
            mClamperChangeListener.onChanged();
        };
        mHandler.post(() -> onDisplayChanged(displayData));
    }

    // Called in DisplayControllerHandler
    @Override
    public void apply(DisplayManagerInternal.DisplayPowerRequest request,
            DisplayBrightnessState.Builder stateBuilder) {
        if (mHdrBrightnessData  == null) { // no hdr data
            return;
        }
        if (mMode == Mode.NO_HDR) {
            return;
        }
        float hdrBrightness = mDisplayDeviceConfig.getHdrBrightnessFromSdr(
                stateBuilder.getBrightness(), mMaxDesiredHdrRatio,
                mHdrBrightnessData.sdrToHdrRatioSpline);
        float maxBrightness = getMaxBrightness(mMode, mMaxBrightness, mHdrBrightnessData);
        hdrBrightness = Math.min(hdrBrightness, maxBrightness);

        stateBuilder.setHdrBrightness(hdrBrightness);
        stateBuilder.setCustomAnimationRate(mTransitionRate);
        stateBuilder.getBrightnessReason().addModifier(BrightnessReason.MODIFIER_HDR);

        // transition rate applied, reset
        mTransitionRate = CUSTOM_ANIMATION_RATE_NOT_SET;
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println("HdrBrightnessModifier:");
        pw.println("  mHdrBrightnessData=" + mHdrBrightnessData);
        pw.println("  mScreenSize=" + mScreenSize);
        pw.println("  mMaxDesiredHdrRatio=" + mMaxDesiredHdrRatio);
        pw.println("  mHdrLayerSize=" + mHdrLayerSize);
        pw.println("  mAmbientLux=" + mAmbientLux);
        pw.println("  mMode=" + mMode);
        pw.println("  mMaxBrightness=" + mMaxBrightness);
        pw.println("  mPendingMaxBrightness=" + mPendingMaxBrightness);
        pw.println("  mTransitionRate=" + mTransitionRate);
        pw.println("  mPendingTransitionRate=" + mPendingTransitionRate);
        pw.println("  mHdrListener registered=" + (mRegisteredDisplayToken != null));
    }

    // Called in DisplayControllerHandler
    @Override
    public void stop() {
        unregisterHdrListener();
        mHandler.removeCallbacksAndMessages(null);
    }

    // Called in DisplayControllerHandler
    @Override
    public boolean shouldListenToLightSensor() {
        return hasBrightnessLimits();
    }

    // Called in DisplayControllerHandler
    @Override
    public void setAmbientLux(float lux) {
        mAmbientLux = lux;
        if (!hasBrightnessLimits()) {
            return;
        }
        float desiredMaxBrightness = findBrightnessLimit(mHdrBrightnessData, lux);
        if (mMode == Mode.NO_HDR) {
            mMaxBrightness = desiredMaxBrightness;
        } else {
            scheduleMaxBrightnessUpdate(desiredMaxBrightness, mHdrBrightnessData);
        }
    }

    // Called in DisplayControllerHandler
    @Override
    public void onDisplayChanged(BrightnessClamperController.DisplayDeviceData displayData) {
        mDisplayDeviceConfig = displayData.mDisplayDeviceConfig;
        mScreenSize = (float) displayData.mWidth * displayData.mHeight;
        HdrBrightnessData data = mDisplayDeviceConfig.getHdrBrightnessData();
        if (data == null) {
            unregisterHdrListener();
        } else {
            registerHdrListener(displayData.mDisplayToken);
        }
        recalculate(data, mMaxDesiredHdrRatio);
    }

    // Called in DisplayControllerHandler, when any modifier state changes
    @Override
    public void applyStateChange(
            BrightnessClamperController.ModifiersAggregatedState aggregatedState) {
        if (mMode != Mode.NO_HDR && mHdrBrightnessData != null) {
            aggregatedState.mMaxDesiredHdrRatio = mMaxDesiredHdrRatio;
            aggregatedState.mSdrHdrRatioSpline = mHdrBrightnessData.sdrToHdrRatioSpline;
            aggregatedState.mMaxHdrBrightness = getMaxBrightness(
                    mMode, mMaxBrightness, mHdrBrightnessData);
        }
    }

    private boolean hasBrightnessLimits() {
        return mHdrBrightnessData != null && !mHdrBrightnessData.maxBrightnessLimits.isEmpty();
    }

    private void scheduleMaxBrightnessUpdate(float desiredMaxBrightness, HdrBrightnessData data) {
        if (mMaxBrightness == desiredMaxBrightness) {
            mPendingMaxBrightness = mMaxBrightness;
            mPendingTransitionRate = -1f;
            mTransitionRate = -1f;
            mHandler.removeCallbacks(mDebouncer);
        } else if (mPendingMaxBrightness != desiredMaxBrightness) {
            mPendingMaxBrightness = desiredMaxBrightness;
            long debounceTime;
            if (mPendingMaxBrightness > mMaxBrightness) {
                debounceTime = data.brightnessIncreaseDebounceMillis;
                mPendingTransitionRate = data.screenBrightnessRampIncrease;
            } else {
                debounceTime = data.brightnessDecreaseDebounceMillis;
                mPendingTransitionRate = data.screenBrightnessRampDecrease;
            }

            mHandler.removeCallbacks(mDebouncer);
            mHandler.postDelayed(mDebouncer, debounceTime);
        }
        // do nothing if expectedMaxBrightness == mDesiredMaxBrightness
        // && expectedMaxBrightness != mMaxBrightness
    }

    // Called in DisplayControllerHandler
    private void recalculate(@Nullable HdrBrightnessData data, float maxDesiredHdrRatio) {
        Mode newMode = recalculateMode(data);
        // if HDR mode changed, notify changed
        boolean needToNotifyChange = mMode != newMode;
        // If HDR mode is active, we need to check if other HDR params are changed
        if (mMode != HdrBrightnessModifier.Mode.NO_HDR) {
            if (!BrightnessSynchronizer.floatEquals(mMaxDesiredHdrRatio, maxDesiredHdrRatio)
                    || data != mHdrBrightnessData) {
                needToNotifyChange = true;
            }
        }

        mMode = newMode;
        mHdrBrightnessData = data;
        mMaxDesiredHdrRatio = maxDesiredHdrRatio;

        if (needToNotifyChange) {
            // data or hdr layer changed, reset custom transition rate
            mTransitionRate = CUSTOM_ANIMATION_RATE_NOT_SET;
            mClamperChangeListener.onChanged();
        }
    }

    // Called in DisplayControllerHandler
    private Mode recalculateMode(@Nullable HdrBrightnessData data) {
        // no config
        if (data == null) {
            return Mode.NO_HDR;
        }
        // no HDR layer present
        if (mHdrLayerSize == DEFAULT_HDR_LAYER_SIZE) {
            return Mode.NO_HDR;
        }
        // HDR layer < minHdr % for Nbm
        if (mHdrLayerSize < mScreenSize * data.minimumHdrPercentOfScreenForNbm) {
            return Mode.NO_HDR;
        }
        // HDR layer < minHdr % for Hbm, and HDR layer >= that minHdr % for Nbm
        if (mHdrLayerSize < mScreenSize * data.minimumHdrPercentOfScreenForHbm) {
            return Mode.NBM_HDR;
        }
        // HDR layer > that minHdr % for Hbm
        return Mode.HBM_HDR;
    }

    private float getMaxBrightness(Mode mode, float maxBrightness, HdrBrightnessData data) {
        if (mode == Mode.NBM_HDR) {
            return Math.min(data.hbmTransitionPoint, maxBrightness);
        } else if (mode == Mode.HBM_HDR) {
            return maxBrightness;
        } else {
            return PowerManager.BRIGHTNESS_MAX;
        }
    }

    // Called in DisplayControllerHandler
    private float findBrightnessLimit(HdrBrightnessData data, float ambientLux) {
        float foundAmbientBoundary = Float.MAX_VALUE;
        float foundMaxBrightness = PowerManager.BRIGHTNESS_MAX;
        for (Map.Entry<Float, Float> brightnessPoint :
                data.maxBrightnessLimits.entrySet()) {
            float ambientBoundary = brightnessPoint.getKey();
            // find ambient lux upper boundary closest to current ambient lux
            if (ambientBoundary > ambientLux && ambientBoundary < foundAmbientBoundary) {
                foundMaxBrightness = brightnessPoint.getValue();
                foundAmbientBoundary = ambientBoundary;
            }
        }
        return foundMaxBrightness;
    }

    // Called in DisplayControllerHandler
    private void onHdrInfoChanged(float hdrLayerSize, float maxDesiredHdrSdrRatio) {
        mHdrLayerSize = hdrLayerSize;
        recalculate(mHdrBrightnessData, maxDesiredHdrSdrRatio);
    }

    // Called in DisplayControllerHandler
    private void registerHdrListener(IBinder displayToken) {
        if (mRegisteredDisplayToken == displayToken) {
            return;
        }
        unregisterHdrListener();
        if (displayToken != null) {
            mInjector.registerHdrListener(mHdrListener, displayToken);
            mRegisteredDisplayToken = displayToken;
        }
    }

    // Called in DisplayControllerHandler
    private void unregisterHdrListener() {
        if (mRegisteredDisplayToken != null) {
            mInjector.unregisterHdrListener(mHdrListener, mRegisteredDisplayToken);
            mRegisteredDisplayToken = null;
            mHdrLayerSize = DEFAULT_HDR_LAYER_SIZE;
        }
    }

    private enum Mode {
        NO_HDR, NBM_HDR, HBM_HDR
    }

    @SuppressLint("MissingPermission")
    static class Injector {
        void registerHdrListener(SurfaceControlHdrLayerInfoListener listener, IBinder token) {
            listener.register(token);
        }

        void unregisterHdrListener(SurfaceControlHdrLayerInfoListener listener, IBinder token) {
            listener.unregister(token);
        }
    }
}
