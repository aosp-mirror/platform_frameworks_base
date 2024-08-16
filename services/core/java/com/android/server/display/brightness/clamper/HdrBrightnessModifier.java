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
import android.content.Context;
import android.database.ContentObserver;
import android.hardware.display.DisplayManagerInternal;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
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

    private final Uri mLowPowerModeSetting = Settings.Global.getUriFor(
            Settings.Global.LOW_POWER_MODE);

    private final ContentObserver mContentObserver;

    private final SurfaceControlHdrLayerInfoListener mHdrListener =
            new SurfaceControlHdrLayerInfoListener() {
                @Override
                public void onHdrInfoChanged(IBinder displayToken, int numberOfHdrLayers, int maxW,
                        int maxH, int flags, float maxDesiredHdrSdrRatio) {
                    boolean hdrLayerPresent = numberOfHdrLayers > 0;
                    mHandler.post(() -> HdrBrightnessModifier.this.onHdrInfoChanged(
                            hdrLayerPresent ? (float) (maxW * maxH) : DEFAULT_HDR_LAYER_SIZE,
                            hdrLayerPresent ? Math.max(maxDesiredHdrSdrRatio,
                                    DEFAULT_MAX_HDR_SDR_RATIO) : DEFAULT_MAX_HDR_SDR_RATIO));
                }
            };

    private final Handler mHandler;
    private final BrightnessClamperController.ClamperChangeListener mClamperChangeListener;
    private final Injector mInjector;
    private final Runnable mDebouncer;

    private IBinder mRegisteredDisplayToken;
    private boolean mContentObserverRegistered = false;

    private DisplayDeviceConfig mDisplayDeviceConfig;
    @Nullable
    private HdrBrightnessData mHdrBrightnessData;
    private float mScreenSize;

    private float mMaxDesiredHdrRatio = DEFAULT_MAX_HDR_SDR_RATIO;
    private float mHdrLayerSize = DEFAULT_HDR_LAYER_SIZE;

    private float mAmbientLux = INVALID_LUX;

    private boolean mLowPowerMode = false;

    private Mode mMode = Mode.NO_HDR;
    // The maximum brightness allowed for current lux
    private float mMaxBrightness = PowerManager.BRIGHTNESS_MAX;
    private float mPendingMaxBrightness = PowerManager.BRIGHTNESS_MAX;
    // brightness change speed, in units per seconds. Applied only on ambient lux changes
    private float mTransitionRate = CUSTOM_ANIMATION_RATE_NOT_SET;
    private float mPendingTransitionRate = CUSTOM_ANIMATION_RATE_NOT_SET;

    HdrBrightnessModifier(Handler handler, Context context,
            BrightnessClamperController.ClamperChangeListener clamperChangeListener,
            BrightnessClamperController.DisplayDeviceData displayData) {
        this(new Handler(handler.getLooper()), clamperChangeListener,
                new Injector(context), displayData);
    }

    @VisibleForTesting
    HdrBrightnessModifier(Handler handler,
            BrightnessClamperController.ClamperChangeListener clamperChangeListener,
            Injector injector, BrightnessClamperController.DisplayDeviceData displayData) {
        mHandler = handler;
        mClamperChangeListener = clamperChangeListener;
        mInjector = injector;
        mDebouncer = () -> {
            mTransitionRate = mPendingTransitionRate;
            mMaxBrightness = mPendingMaxBrightness;
            mClamperChangeListener.onChanged();
        };
        mContentObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                onLowPowerModeChange();
            }
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
        pw.println("  mLowPowerMode=" + mLowPowerMode);
        pw.println("  mMode=" + mMode);
        pw.println("  mMaxBrightness=" + mMaxBrightness);
        pw.println("  mPendingMaxBrightness=" + mPendingMaxBrightness);
        pw.println("  mTransitionRate=" + mTransitionRate);
        pw.println("  mPendingTransitionRate=" + mPendingTransitionRate);
        pw.println("  mHdrListener registered=" + (mRegisteredDisplayToken != null));
        pw.println("  mContentObserverRegistered=" + mContentObserverRegistered);
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
        if (data == null || data.allowInLowPowerMode) {
            unregisterContentObserver();
        } else {
            registerContentObserver();
        }

        Mode newMode = recalculateMode(data);
        // mode changed, or mode was HDR  and HdrBrightnessData changed
        boolean needToNotifyChange = mMode != newMode
                || (mMode != HdrBrightnessModifier.Mode.NO_HDR && data != mHdrBrightnessData);
        mMode = newMode;
        mHdrBrightnessData = data;
        mMaxBrightness = findBrightnessLimit(mHdrBrightnessData, mAmbientLux);

        if (needToNotifyChange) {
            // data changed, reset custom transition rate
            mTransitionRate = CUSTOM_ANIMATION_RATE_NOT_SET;
            mClamperChangeListener.onChanged();
        }
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
    private Mode recalculateMode(@Nullable HdrBrightnessData data) {
        // no config
        if (data == null) {
            return Mode.NO_HDR;
        }
        // no HDR layer present
        if (mHdrLayerSize == DEFAULT_HDR_LAYER_SIZE) {
            return Mode.NO_HDR;
        }
        // low power mode and not allowed in low power mode
        if (!data.allowInLowPowerMode && mLowPowerMode) {
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

    private void onLowPowerModeChange() {
        mLowPowerMode = mInjector.isLowPowerMode();
        Mode newMode = recalculateMode(mHdrBrightnessData);
        if (newMode != mMode) {
            mMode = newMode;
            mTransitionRate = CUSTOM_ANIMATION_RATE_NOT_SET;
            mClamperChangeListener.onChanged();
        }
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
    private float findBrightnessLimit(@Nullable HdrBrightnessData data, float ambientLux) {
        if (data == null) {
            return PowerManager.BRIGHTNESS_MAX;
        }
        if (ambientLux == INVALID_LUX) {
            return PowerManager.BRIGHTNESS_MAX;
        }
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
        Mode newMode = recalculateMode(mHdrBrightnessData);
        // mode changed, or mode was HDR  and maxDesiredHdrRatio changed
        boolean needToNotifyChange = mMode != newMode
                || (mMode != HdrBrightnessModifier.Mode.NO_HDR
                && !BrightnessSynchronizer.floatEquals(mMaxDesiredHdrRatio, maxDesiredHdrSdrRatio));
        mMode = newMode;
        mMaxDesiredHdrRatio = maxDesiredHdrSdrRatio;
        if (needToNotifyChange) {
            mTransitionRate = CUSTOM_ANIMATION_RATE_NOT_SET;
            mClamperChangeListener.onChanged();
        }
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

    // Called in DisplayControllerHandler
    private void registerContentObserver() {
        if (!mContentObserverRegistered) {
            mInjector.registerContentObserver(mContentObserver, mLowPowerModeSetting);
            mContentObserverRegistered = true;
            mLowPowerMode = mInjector.isLowPowerMode();
        }
    }

    // Called in DisplayControllerHandler
    private void unregisterContentObserver() {
        if (mContentObserverRegistered) {
            mInjector.unregisterContentObserver(mContentObserver);
            mContentObserverRegistered = false;
            mLowPowerMode = false;
        }
    }

    private enum Mode {
        NO_HDR, NBM_HDR, HBM_HDR
    }

    @SuppressLint("MissingPermission")
    static class Injector {
        private final Context mContext;

        Injector(Context context) {
            mContext = context;
        }

        void registerHdrListener(SurfaceControlHdrLayerInfoListener listener, IBinder token) {
            listener.register(token);
        }

        void unregisterHdrListener(SurfaceControlHdrLayerInfoListener listener, IBinder token) {
            listener.unregister(token);
        }

        void registerContentObserver(ContentObserver observer, Uri uri) {
            mContext.getContentResolver().registerContentObserver(uri, false,
                    observer, UserHandle.USER_ALL);
        }

        void unregisterContentObserver(ContentObserver observer) {
            mContext.getContentResolver().unregisterContentObserver(observer);
        }

        boolean isLowPowerMode() {
            return Settings.Global.getInt(
                    mContext.getContentResolver(), Settings.Global.LOW_POWER_MODE, 0) != 0;
        }
    }
}
