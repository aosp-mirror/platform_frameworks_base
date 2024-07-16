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

import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.hardware.display.DisplayManagerInternal;
import android.os.Handler;
import android.os.IBinder;
import android.view.SurfaceControlHdrLayerInfoListener;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.display.BrightnessSynchronizer;
import com.android.server.display.DisplayBrightnessState;
import com.android.server.display.DisplayDeviceConfig;
import com.android.server.display.config.HdrBrightnessData;

import java.io.PrintWriter;

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

    private IBinder mRegisteredDisplayToken;

    private float mScreenSize;
    private float mHdrLayerSize = DEFAULT_HDR_LAYER_SIZE;
    private HdrBrightnessData mHdrBrightnessData;
    private DisplayDeviceConfig mDisplayDeviceConfig;
    private float mMaxDesiredHdrRatio = DEFAULT_MAX_HDR_SDR_RATIO;
    private Mode mMode = Mode.NO_HDR;

    HdrBrightnessModifier(Handler handler,
            BrightnessClamperController.ClamperChangeListener clamperChangeListener,
            BrightnessClamperController.DisplayDeviceData displayData) {
        this(handler, clamperChangeListener, new Injector(), displayData);
    }

    @VisibleForTesting
    HdrBrightnessModifier(Handler handler,
            BrightnessClamperController.ClamperChangeListener clamperChangeListener,
            Injector injector,
            BrightnessClamperController.DisplayDeviceData displayData) {
        mHandler = handler;
        mClamperChangeListener = clamperChangeListener;
        mInjector = injector;
        onDisplayChanged(displayData);
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
        stateBuilder.setHdrBrightness(hdrBrightness);
    }

    @Override
    public void dump(PrintWriter printWriter) {
        // noop
    }

    // Called in DisplayControllerHandler
    @Override
    public void stop() {
        unregisterHdrListener();
    }


    @Override
    public boolean shouldListenToLightSensor() {
        return false;
    }

    @Override
    public void setAmbientLux(float lux) {
        // noop
    }

    @Override
    public void onDisplayChanged(BrightnessClamperController.DisplayDeviceData displayData) {
        mHandler.post(() -> onDisplayChanged(displayData.mDisplayToken, displayData.mWidth,
                displayData.mHeight, displayData.mDisplayDeviceConfig));
    }

    // Called in DisplayControllerHandler
    @Override
    public void applyStateChange(
            BrightnessClamperController.ModifiersAggregatedState aggregatedState) {
        if (mMode != Mode.NO_HDR) {
            aggregatedState.mMaxDesiredHdrRatio = mMaxDesiredHdrRatio;
            aggregatedState.mSdrHdrRatioSpline = mHdrBrightnessData.sdrToHdrRatioSpline;
            aggregatedState.mHdrHbmEnabled = (mMode == Mode.HBM_HDR);
        }
    }

    // Called in DisplayControllerHandler
    private void onDisplayChanged(IBinder displayToken, int width, int height,
            DisplayDeviceConfig config) {
        mDisplayDeviceConfig = config;
        mScreenSize = (float) width * height;
        HdrBrightnessData data = config.getHdrBrightnessData();
        if (data == null) {
            unregisterHdrListener();
        } else {
            registerHdrListener(displayToken);
        }
        recalculate(data, mMaxDesiredHdrRatio);
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
            mClamperChangeListener.onChanged();
        }
    }

    // Called in DisplayControllerHandler
    private Mode recalculateMode(@Nullable HdrBrightnessData data) {
        // no config
        if (data == null) {
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
