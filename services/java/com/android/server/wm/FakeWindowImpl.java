/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server.wm;

import com.android.server.input.InputApplicationHandle;
import com.android.server.input.InputWindowHandle;

import android.os.Looper;
import android.os.Process;
import android.util.Slog;
import android.view.Display;
import android.view.InputChannel;
import android.view.InputEventReceiver;
import android.view.InputQueue;
import android.view.WindowManagerPolicy;

public final class FakeWindowImpl implements WindowManagerPolicy.FakeWindow {
    final WindowManagerService mService;
    final InputChannel mServerChannel, mClientChannel;
    final InputApplicationHandle mApplicationHandle;
    final InputWindowHandle mWindowHandle;
    final InputEventReceiver mInputEventReceiver;
    final int mWindowLayer;

    boolean mTouchFullscreen;

    public FakeWindowImpl(WindowManagerService service,
            Looper looper, InputEventReceiver.Factory inputEventReceiverFactory,
            String name, int windowType, int layoutParamsFlags, boolean canReceiveKeys,
            boolean hasFocus, boolean touchFullscreen) {
        mService = service;

        InputChannel[] channels = InputChannel.openInputChannelPair(name);
        mServerChannel = channels[0];
        mClientChannel = channels[1];
        mService.mInputManager.registerInputChannel(mServerChannel, null);

        mInputEventReceiver = inputEventReceiverFactory.createInputEventReceiver(
                mClientChannel, looper);

        mApplicationHandle = new InputApplicationHandle(null);
        mApplicationHandle.name = name;
        mApplicationHandle.dispatchingTimeoutNanos =
                WindowManagerService.DEFAULT_INPUT_DISPATCHING_TIMEOUT_NANOS;

        mWindowHandle = new InputWindowHandle(mApplicationHandle, null, Display.DEFAULT_DISPLAY);
        mWindowHandle.name = name;
        mWindowHandle.inputChannel = mServerChannel;
        mWindowLayer = getLayerLw(windowType);
        mWindowHandle.layer = mWindowLayer;
        mWindowHandle.layoutParamsFlags = layoutParamsFlags;
        mWindowHandle.layoutParamsType = windowType;
        mWindowHandle.dispatchingTimeoutNanos =
                WindowManagerService.DEFAULT_INPUT_DISPATCHING_TIMEOUT_NANOS;
        mWindowHandle.visible = true;
        mWindowHandle.canReceiveKeys = canReceiveKeys;
        mWindowHandle.hasFocus = hasFocus;
        mWindowHandle.hasWallpaper = false;
        mWindowHandle.paused = false;
        mWindowHandle.ownerPid = Process.myPid();
        mWindowHandle.ownerUid = Process.myUid();
        mWindowHandle.inputFeatures = 0;
        mWindowHandle.scaleFactor = 1.0f;

        mTouchFullscreen = touchFullscreen;
    }

    void layout(int dw, int dh) {
        if (mTouchFullscreen) {
            mWindowHandle.touchableRegion.set(0, 0, dw, dh);
        } else {
            mWindowHandle.touchableRegion.setEmpty();
        }
        mWindowHandle.frameLeft = 0;
        mWindowHandle.frameTop = 0;
        mWindowHandle.frameRight = dw;
        mWindowHandle.frameBottom = dh;
    }

    @Override
    public void dismiss() {
        synchronized (mService.mWindowMap) {
            if (mService.removeFakeWindowLocked(this)) {
                mInputEventReceiver.dispose();
                mService.mInputManager.unregisterInputChannel(mServerChannel);
                mClientChannel.dispose();
                mServerChannel.dispose();
            }
        }
    }

    private int getLayerLw(int windowType) {
        return mService.mPolicy.windowTypeToLayerLw(windowType)
                * WindowManagerService.TYPE_LAYER_MULTIPLIER
                + WindowManagerService.TYPE_LAYER_OFFSET;
    }
}
