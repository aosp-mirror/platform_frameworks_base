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
import android.graphics.Rect;
import android.os.Process;
import android.view.InputApplicationHandle;
import android.view.InputChannel;
import android.view.InputWindowHandle;
import android.view.SurfaceControl;
import android.view.WindowManager;


final class HandwritingEventReceiverSurface {

    public static final String TAG = HandwritingEventReceiverSurface.class.getSimpleName();
    static final boolean DEBUG = HandwritingModeController.DEBUG;

    private final int mClientPid;
    private final int mClientUid;

    private final InputApplicationHandle mApplicationHandle;
    private final InputWindowHandle mWindowHandle;
    private final InputChannel mClientChannel;
    private final SurfaceControl mInputSurface;
    private boolean mIsIntercepting;

    HandwritingEventReceiverSurface(String name, int displayId, @NonNull SurfaceControl sc,
            @NonNull InputChannel inputChannel) {
        // Initialized the window as being owned by the system.
        mClientPid = Process.myPid();
        mClientUid = Process.myUid();
        mApplicationHandle = new InputApplicationHandle(null, name,
                DEFAULT_DISPATCHING_TIMEOUT_MILLIS);

        mClientChannel = inputChannel;
        mInputSurface = sc;

        mWindowHandle = new InputWindowHandle(mApplicationHandle, displayId);
        mWindowHandle.name = name;
        mWindowHandle.token = mClientChannel.getToken();
        mWindowHandle.layoutParamsType = WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY;
        mWindowHandle.layoutParamsFlags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        mWindowHandle.dispatchingTimeoutMillis = DEFAULT_DISPATCHING_TIMEOUT_MILLIS;
        mWindowHandle.visible = true;
        mWindowHandle.focusable = false;
        mWindowHandle.hasWallpaper = false;
        mWindowHandle.paused = false;
        mWindowHandle.ownerPid = mClientPid;
        mWindowHandle.ownerUid = mClientUid;
        mWindowHandle.inputFeatures = WindowManager.LayoutParams.INPUT_FEATURE_SPY
                | WindowManager.LayoutParams.INPUT_FEATURE_INTERCEPTS_STYLUS;
        mWindowHandle.scaleFactor = 1.0f;
        mWindowHandle.trustedOverlay = true;
        mWindowHandle.replaceTouchableRegionWithCrop(null /* use this surface as crop */);

        final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        t.setInputWindowInfo(mInputSurface, mWindowHandle);
        t.setLayer(mInputSurface, Integer.MAX_VALUE);
        t.setPosition(mInputSurface, 0, 0);
        // Use an arbitrarily large crop that is positioned at the origin. The crop determines the
        // bounds and the coordinate space of the input events, so it must start at the origin to
        // receive input in display space.
        // TODO(b/210039666): fix this in SurfaceFlinger and avoid the hack.
        t.setCrop(mInputSurface, new Rect(0, 0, 10000, 10000));
        t.show(mInputSurface);
        t.apply();

        mIsIntercepting = false;
    }

    void startIntercepting() {
        // TODO(b/210978621): Update the spy window's PID and UID to be associated with the IME so
        //  that ANRs are correctly attributed to the IME.
        final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        mWindowHandle.inputFeatures &= ~WindowManager.LayoutParams.INPUT_FEATURE_SPY;
        t.setInputWindowInfo(mInputSurface, mWindowHandle);
        t.apply();
        mIsIntercepting = true;
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
}
