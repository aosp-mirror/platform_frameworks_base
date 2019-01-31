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

import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.view.InputChannel;
import android.view.WindowManager;

import android.view.InputApplicationHandle;
import android.view.InputWindowHandle;
import android.view.SurfaceControl;
import android.util.Slog;

import java.io.PrintWriter;

class InputConsumerImpl implements IBinder.DeathRecipient {
    final WindowManagerService mService;
    final InputChannel mServerChannel, mClientChannel;
    final InputApplicationHandle mApplicationHandle;
    final InputWindowHandle mWindowHandle;

    final IBinder mToken;
    final String mName;
    final int mClientPid;
    final UserHandle mClientUser;

    final SurfaceControl mInputSurface;
    Rect mTmpClipRect = new Rect();

    InputConsumerImpl(WindowManagerService service, IBinder token, String name,
            InputChannel inputChannel, int clientPid, UserHandle clientUser, int displayId) {
        mService = service;
        mToken = token;
        mName = name;
        mClientPid = clientPid;
        mClientUser = clientUser;

        InputChannel[] channels = InputChannel.openInputChannelPair(name);
        mServerChannel = channels[0];
        if (inputChannel != null) {
            channels[1].transferTo(inputChannel);
            channels[1].dispose();
            mClientChannel = inputChannel;
        } else {
            mClientChannel = channels[1];
        }
        mService.mInputManager.registerInputChannel(mServerChannel, null);

        mApplicationHandle = new InputApplicationHandle(new Binder());
        mApplicationHandle.name = name;
        mApplicationHandle.dispatchingTimeoutNanos =
                WindowManagerService.DEFAULT_INPUT_DISPATCHING_TIMEOUT_NANOS;

        mWindowHandle = new InputWindowHandle(mApplicationHandle, null, displayId);
        mWindowHandle.name = name;
        mWindowHandle.token = mServerChannel.getToken();
        mWindowHandle.layoutParamsType = WindowManager.LayoutParams.TYPE_INPUT_CONSUMER;
        mWindowHandle.layer = getLayerLw(mWindowHandle.layoutParamsType);
        mWindowHandle.layoutParamsFlags = 0;
        mWindowHandle.dispatchingTimeoutNanos =
                WindowManagerService.DEFAULT_INPUT_DISPATCHING_TIMEOUT_NANOS;
        mWindowHandle.visible = true;
        mWindowHandle.canReceiveKeys = false;
        mWindowHandle.hasFocus = false;
        mWindowHandle.hasWallpaper = false;
        mWindowHandle.paused = false;
        mWindowHandle.ownerPid = Process.myPid();
        mWindowHandle.ownerUid = Process.myUid();
        mWindowHandle.inputFeatures = 0;
        mWindowHandle.scaleFactor = 1.0f;

        mInputSurface = mService.makeSurfaceBuilder(mService.mRoot.getDisplayContent(displayId)
                .getSession()).setContainerLayer(true).setName("Input Consumer " + name)
                .build();
    }

    void linkToDeathRecipient() {
        if (mToken == null) {
            return;
        }

        try {
            mToken.linkToDeath(this, 0);
        } catch (RemoteException e) {
            // Client died, do nothing
        }
    }

    void unlinkFromDeathRecipient() {
        if (mToken == null) {
            return;
        }

        mToken.unlinkToDeath(this, 0);
    }

    void layout(SurfaceControl.Transaction t, int dw, int dh) {
        t.setPosition(mInputSurface, 0, 0);

        mTmpClipRect.set(0, 0, dw, dh);
        t.setWindowCrop(mInputSurface, mTmpClipRect);
    }

    void layout(SurfaceControl.Transaction t, Rect r) {
        t.setPosition(mInputSurface, r.left, r.top);
        mTmpClipRect.set(0, 0, r.width(), r.height());
        t.setWindowCrop(mInputSurface, mTmpClipRect);
    }

    void hide(SurfaceControl.Transaction t) {
        t.hide(mInputSurface);
    }

    void show(SurfaceControl.Transaction t, WindowState w) {
        t.show(mInputSurface);
        t.setInputWindowInfo(mInputSurface, mWindowHandle);
        t.setRelativeLayer(mInputSurface, w.getSurfaceControl(), 1);
    }

    void show(SurfaceControl.Transaction t, int layer) {
        t.show(mInputSurface);
        t.setInputWindowInfo(mInputSurface, mWindowHandle);
        t.setLayer(mInputSurface, layer);
    }

    private int getLayerLw(int windowType) {
        return mService.mPolicy.getWindowLayerFromTypeLw(windowType)
                * WindowManagerService.TYPE_LAYER_MULTIPLIER
                + WindowManagerService.TYPE_LAYER_OFFSET;
    }

    void disposeChannelsLw() {
        mService.mInputManager.unregisterInputChannel(mServerChannel);
        mClientChannel.dispose();
        mServerChannel.dispose();
        unlinkFromDeathRecipient();
    }

    @Override
    public void binderDied() {
        synchronized (mService.getWindowManagerLock()) {
            // Clean up the input consumer
            final InputMonitor inputMonitor =
                    mService.mRoot.getDisplayContent(mWindowHandle.displayId).getInputMonitor();
            inputMonitor.destroyInputConsumer(mName);
            unlinkFromDeathRecipient();
        }
    }

    void dump(PrintWriter pw, String name, String prefix) {
        pw.println(prefix + "  name=" + name + " pid=" + mClientPid + " user=" + mClientUser);
    }
}
