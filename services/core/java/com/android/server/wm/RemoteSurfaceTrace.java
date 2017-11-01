/*
 * Copyright (C) 2016 The Android Open Source Project
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
import android.graphics.Region;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Slog;
import android.view.SurfaceControl;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.DataOutputStream;

// A surface control subclass which logs events to a FD in binary format.
// This can be used in our CTS tests to enable a pattern similar to mocking
// the surface control.
//
// See cts/hostsidetests/../../SurfaceTraceReceiver.java for parsing side.
class RemoteSurfaceTrace extends SurfaceControlWithBackground {
    static final String TAG = "RemoteSurfaceTrace";

    final FileDescriptor mWriteFd;
    final DataOutputStream mOut;

    final WindowManagerService mService;
    final WindowState mWindow;

    RemoteSurfaceTrace(FileDescriptor fd, SurfaceControlWithBackground wrapped,
            WindowState window) {
        super(wrapped);

        mWriteFd = fd;
        mOut = new DataOutputStream(new FileOutputStream(fd, false));

        mWindow = window;
        mService = mWindow.mService;
    }

    @Override
    public void setAlpha(float alpha) {
        writeFloatEvent("Alpha", alpha);
        super.setAlpha(alpha);
    }

    @Override
    public void setLayer(int zorder) {
        writeIntEvent("Layer", zorder);
        super.setLayer(zorder);
    }

    @Override
    public void setPosition(float x, float y) {
        writeFloatEvent("Position", x, y);
        super.setPosition(x, y);
    }

    @Override
    public void setGeometryAppliesWithResize() {
        writeEvent("GeometryAppliesWithResize");
        super.setGeometryAppliesWithResize();
    }

    @Override
    public void setSize(int w, int h) {
        writeIntEvent("Size", w, h);
        super.setSize(w, h);
    }

    @Override
    public void setWindowCrop(Rect crop) {
        writeRectEvent("Crop", crop);
        super.setWindowCrop(crop);
    }

    @Override
    public void setFinalCrop(Rect crop) {
        writeRectEvent("FinalCrop", crop);
        super.setFinalCrop(crop);
    }

    @Override
    public void setLayerStack(int layerStack) {
        writeIntEvent("LayerStack", layerStack);
        super.setLayerStack(layerStack);
    }

    @Override
    public void setMatrix(float dsdx, float dtdx, float dsdy, float dtdy) {
        writeFloatEvent("Matrix", dsdx, dtdx, dsdy, dtdy);
        super.setMatrix(dsdx, dtdx, dsdy, dtdy);
    }

    @Override
    public void hide() {
        writeEvent("Hide");
        super.hide();
    }

    @Override
    public void show() {
        writeEvent("Show");
        super.show();
    }

    private void writeEvent(String tag) {
        try {
            mOut.writeUTF(tag);
            mOut.writeUTF(mWindow.getWindowTag().toString());
            writeSigil();
        } catch (Exception e) {
            RemoteEventTrace.logException(e);
            mService.disableSurfaceTrace();
        }
    }

    private void writeIntEvent(String tag, int... values) {
        try {
            mOut.writeUTF(tag);
            mOut.writeUTF(mWindow.getWindowTag().toString());
            for (int value: values) {
                mOut.writeInt(value);
            }
            writeSigil();
        } catch (Exception e) {
            RemoteEventTrace.logException(e);
            mService.disableSurfaceTrace();
        }
    }

    private void writeFloatEvent(String tag, float... values) {
        try {
            mOut.writeUTF(tag);
            mOut.writeUTF(mWindow.getWindowTag().toString());
            for (float value: values) {
                mOut.writeFloat(value);
            }
            writeSigil();
        } catch (Exception e) {
            RemoteEventTrace.logException(e);
            mService.disableSurfaceTrace();
        }
    }

    private void writeRectEvent(String tag, Rect value) {
        writeFloatEvent(tag, value.left, value.top, value.right, value.bottom);
    }

    private void writeSigil() throws Exception {
        mOut.write(RemoteEventTrace.sigil, 0, 4);
    }
}
