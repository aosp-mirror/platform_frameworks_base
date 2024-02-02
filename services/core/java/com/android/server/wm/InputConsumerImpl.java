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

import static android.os.InputConstants.DEFAULT_DISPATCHING_TIMEOUT_MILLIS;

import android.graphics.Point;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.os.InputConfig;
import android.os.RemoteException;
import android.os.UserHandle;
import android.view.InputApplicationHandle;
import android.view.InputChannel;
import android.view.InputWindowHandle;
import android.view.SurfaceControl;
import android.view.WindowManager;

import java.io.PrintWriter;

class InputConsumerImpl implements IBinder.DeathRecipient {
    final WindowManagerService mService;
    final InputChannel mClientChannel;
    final InputApplicationHandle mApplicationHandle;
    final InputWindowHandle mWindowHandle;

    final IBinder mToken;
    final String mName;
    final int mClientPid;
    final UserHandle mClientUser;

    final SurfaceControl mInputSurface;
    Rect mTmpClipRect = new Rect();
    private final Rect mTmpRect = new Rect();
    private final Point mOldPosition = new Point();
    private final Rect mOldWindowCrop = new Rect();

    InputConsumerImpl(WindowManagerService service, IBinder token, String name,
            InputChannel inputChannel, int clientPid, UserHandle clientUser, int displayId,
            SurfaceControl.Transaction t) {
        mService = service;
        mToken = token;
        mName = name;
        mClientPid = clientPid;
        mClientUser = clientUser;

        mClientChannel = mService.mInputManager.createInputChannel(name);
        if (inputChannel != null) {
            mClientChannel.copyTo(inputChannel);
        }

        mApplicationHandle = new InputApplicationHandle(new Binder(), name,
                DEFAULT_DISPATCHING_TIMEOUT_MILLIS);

        mWindowHandle = new InputWindowHandle(mApplicationHandle, displayId);
        mWindowHandle.name = name;
        mWindowHandle.token = mClientChannel.getToken();
        mWindowHandle.layoutParamsType = WindowManager.LayoutParams.TYPE_INPUT_CONSUMER;
        mWindowHandle.dispatchingTimeoutMillis = DEFAULT_DISPATCHING_TIMEOUT_MILLIS;
        mWindowHandle.ownerPid = WindowManagerService.MY_PID;
        mWindowHandle.ownerUid = WindowManagerService.MY_UID;
        mWindowHandle.scaleFactor = 1.0f;
        mWindowHandle.inputConfig = InputConfig.NOT_FOCUSABLE;

        mInputSurface = mService.makeSurfaceBuilder(
                        mService.mRoot.getDisplayContent(displayId).getSession())
                .setContainerLayer()
                .setName("Input Consumer " + name)
                .setCallsite("InputConsumerImpl")
                .build();
        mWindowHandle.setTrustedOverlay(t, mInputSurface, true);
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
        mTmpRect.set(0, 0, dw, dh);
        layout(t, mTmpRect);
    }

    void layout(SurfaceControl.Transaction t, Rect r) {
        mTmpClipRect.set(0, 0, r.width(), r.height());

        if (mOldPosition.equals(r.left, r.top) && mOldWindowCrop.equals(mTmpClipRect)) {
            return;
        }

        t.setPosition(mInputSurface, r.left, r.top);
        t.setWindowCrop(mInputSurface, mTmpClipRect);

        mOldPosition.set(r.left, r.top);
        mOldWindowCrop.set(mTmpClipRect);
    }

    void hide(SurfaceControl.Transaction t) {
        t.hide(mInputSurface);
    }

    void show(SurfaceControl.Transaction t, WindowContainer w) {
        t.show(mInputSurface);
        t.setInputWindowInfo(mInputSurface, mWindowHandle);
        t.setRelativeLayer(mInputSurface, w.getSurfaceControl(), 1 + w.getChildCount());
    }

    void show(SurfaceControl.Transaction t, int layer) {
        t.show(mInputSurface);
        t.setInputWindowInfo(mInputSurface, mWindowHandle);
        t.setLayer(mInputSurface, layer);
    }

    void reparent(SurfaceControl.Transaction t, WindowContainer wc) {
        t.reparent(mInputSurface, wc.getSurfaceControl());
    }

    void disposeChannelsLw(SurfaceControl.Transaction t) {
        mService.mInputManager.removeInputChannel(mClientChannel.getToken());
        mClientChannel.dispose();
        t.remove(mInputSurface);
        unlinkFromDeathRecipient();
    }

    @Override
    public void binderDied() {
        synchronized (mService.getWindowManagerLock()) {
            // Clean up the input consumer
            final DisplayContent dc = mService.mRoot.getDisplayContent(mWindowHandle.displayId);
            if (dc == null) {
                return;
            }
            dc.getInputMonitor().destroyInputConsumer(mToken);
            unlinkFromDeathRecipient();
        }
    }

    void dump(PrintWriter pw, String name, String prefix) {
        pw.println(prefix + "  name=" + name + " pid=" + mClientPid + " user=" + mClientUser);
    }
}
