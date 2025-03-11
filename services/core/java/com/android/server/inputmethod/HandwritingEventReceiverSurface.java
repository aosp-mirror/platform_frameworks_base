/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.inputmethod;

import static android.os.InputConstants.DEFAULT_DISPATCHING_TIMEOUT_MILLIS;

import android.annotation.NonNull;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.InputConfig;
import android.os.Process;
import android.util.Slog;
import android.view.InputApplicationHandle;
import android.view.InputChannel;
import android.view.InputWindowHandle;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.inputmethod.Flags;

import com.android.server.input.InputManagerService;

final class HandwritingEventReceiverSurface {

    public static final String TAG = HandwritingEventReceiverSurface.class.getSimpleName();
    static final boolean DEBUG = HandwritingModeController.DEBUG;

    private final InputWindowHandle mWindowHandle;
    private final InputChannel mClientChannel;
    private final SurfaceControl mInputSurface;
    private boolean mIsIntercepting;

    HandwritingEventReceiverSurface(Context context, String name, int displayId,
            @NonNull SurfaceControl sc, @NonNull InputChannel inputChannel) {
        mClientChannel = inputChannel;
        mInputSurface = sc;

        mWindowHandle = new InputWindowHandle(new InputApplicationHandle(null, name,
                DEFAULT_DISPATCHING_TIMEOUT_MILLIS), displayId);
        mWindowHandle.name = name;
        mWindowHandle.token = mClientChannel.getToken();
        mWindowHandle.layoutParamsType = WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY;
        mWindowHandle.dispatchingTimeoutMillis = DEFAULT_DISPATCHING_TIMEOUT_MILLIS;
        mWindowHandle.ownerPid = Process.myPid();
        mWindowHandle.ownerUid = Process.myUid();
        mWindowHandle.scaleFactor = 1.0f;
        mWindowHandle.inputConfig =
                InputConfig.NOT_FOCUSABLE
                        | InputConfig.NOT_TOUCHABLE
                        | InputConfig.SPY
                        | InputConfig.INTERCEPTS_STYLUS;

        Rect bounds = null;
        if (Flags.adaptiveHandwritingBounds()) {
            mWindowHandle.setTouchableRegionCrop(mInputSurface);
            // Default touchable area to getMaximumWindowMetrics()
            WindowMetrics windowMetrics =  context.getSystemService(WindowManager.class)
                    .getMaximumWindowMetrics();
            bounds = windowMetrics.getBounds();
            if (DEBUG) Slog.d(TAG, "initial handwriting touchable bounds: " + bounds);
            mWindowHandle.setTouchableRegion(windowMetrics.getBounds());
        } else {
            // Configure the surface to receive stylus events across the entire display.
            mWindowHandle.replaceTouchableRegionWithCrop(null /* use this surface's bounds */);
        }

        final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        mWindowHandle.setTrustedOverlay(t, mInputSurface, true);
        t.setInputWindowInfo(mInputSurface, mWindowHandle);
        t.setLayer(mInputSurface, InputManagerService.INPUT_OVERLAY_LAYER_HANDWRITING_SURFACE);
        t.setPosition(mInputSurface, 0, 0);
        if (Flags.adaptiveHandwritingBounds()) {
            // crop to parent surface if null, else bounds.
            t.setCrop(mInputSurface, bounds);
        } else {
            t.setCrop(mInputSurface, null /* crop to parent surface */);
        }
        t.show(mInputSurface);
        t.apply();

        mIsIntercepting = false;
    }

    void startIntercepting(int imePid, int imeUid) {
        mWindowHandle.ownerPid = imePid;
        mWindowHandle.ownerUid = imeUid;
        mWindowHandle.inputConfig &= ~InputConfig.SPY;

        if (Flags.adaptiveHandwritingBounds()) {
            // watch outside touch to finish handwriting.
            mWindowHandle.inputConfig |= InputConfig.WATCH_OUTSIDE_TOUCH;
        }
        new SurfaceControl.Transaction()
                .setInputWindowInfo(mInputSurface, mWindowHandle)
                .apply();
        mIsIntercepting = true;
    }

    void setTouchableRegion(Region touchableRegion) {
        mWindowHandle.touchableRegion.set(touchableRegion);
        new SurfaceControl.Transaction()
                .setInputWindowInfo(mInputSurface, mWindowHandle)
                .apply();
    }

    void setNotTouchable(boolean notTouchable) {
        if (notTouchable) {
            mWindowHandle.inputConfig |= InputConfig.NOT_TOUCHABLE;
        } else {
            mWindowHandle.inputConfig &=  ~InputConfig.NOT_TOUCHABLE;
        }
        new SurfaceControl.Transaction()
                .setInputWindowInfo(mInputSurface, mWindowHandle)
                .apply();
    }

    boolean isIntercepting() {
        return mIsIntercepting;
    }

    void remove() {
        final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        t.remove(mInputSurface);
        t.apply();
    }

    InputChannel getInputChannel() {
        return mClientChannel;
    }

    SurfaceControl getSurface() {
        return mInputSurface;
    }

    InputWindowHandle getInputWindowHandle() {
        return mWindowHandle;
    }
}
