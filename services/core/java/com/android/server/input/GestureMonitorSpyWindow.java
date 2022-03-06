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

package com.android.server.input;

import static android.os.InputConstants.DEFAULT_DISPATCHING_TIMEOUT_MILLIS;

import android.os.IBinder;
import android.os.InputConfig;
import android.view.InputApplicationHandle;
import android.view.InputChannel;
import android.view.InputMonitor;
import android.view.InputWindowHandle;
import android.view.SurfaceControl;
import android.view.WindowManager;

/**
 * An internal implementation of an {@link InputMonitor} that uses a spy window.
 *
 * This spy window is a layer container in the SurfaceFlinger hierarchy that does not have any
 * graphical buffer, but can still receive input. It is parented to the DisplayContent so
 * that it can spy on any pointer events that start in the DisplayContent bounds. When the
 * object is constructed, it will add itself to SurfaceFlinger.
 */
class GestureMonitorSpyWindow {
    final InputApplicationHandle mApplicationHandle;
    final InputWindowHandle mWindowHandle;

    // The token, InputChannel, and SurfaceControl are owned by this object.
    final IBinder mMonitorToken;
    final InputChannel mClientChannel;
    final SurfaceControl mInputSurface;

    GestureMonitorSpyWindow(IBinder token, String name, int displayId, int pid, int uid,
            SurfaceControl sc, InputChannel inputChannel) {
        mMonitorToken = token;
        mClientChannel = inputChannel;
        mInputSurface = sc;

        mApplicationHandle = new InputApplicationHandle(null, name,
                DEFAULT_DISPATCHING_TIMEOUT_MILLIS);
        mWindowHandle = new InputWindowHandle(mApplicationHandle, displayId);

        mWindowHandle.name = name;
        mWindowHandle.token = mClientChannel.getToken();
        mWindowHandle.layoutParamsType = WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY;
        mWindowHandle.dispatchingTimeoutMillis = DEFAULT_DISPATCHING_TIMEOUT_MILLIS;
        mWindowHandle.ownerPid = pid;
        mWindowHandle.ownerUid = uid;
        mWindowHandle.scaleFactor = 1.0f;
        mWindowHandle.replaceTouchableRegionWithCrop(null /* use this surface's bounds */);
        mWindowHandle.inputConfig =
                InputConfig.NOT_FOCUSABLE | InputConfig.SPY | InputConfig.TRUSTED_OVERLAY;

        final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        t.setInputWindowInfo(mInputSurface, mWindowHandle);
        t.setLayer(mInputSurface, Integer.MAX_VALUE);
        t.setPosition(mInputSurface, 0, 0);
        t.setCrop(mInputSurface, null /* crop to parent surface */);
        t.show(mInputSurface);

        t.apply();
    }

    void remove() {
        final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        t.hide(mInputSurface);
        t.remove(mInputSurface);
        t.apply();

        mClientChannel.dispose();
    }

    String dump() {
        return "name='" + mWindowHandle.name + "', inputChannelToken="
                + mClientChannel.getToken() + " displayId=" + mWindowHandle.displayId;
    }
}
