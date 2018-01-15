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
import android.os.StrictMode;
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
class RemoteSurfaceTrace extends SurfaceControl {
    static final String TAG = "RemoteSurfaceTrace";

    final FileDescriptor mWriteFd;
    final DataOutputStream mOut;

    final WindowManagerService mService;
    final WindowState mWindow;

    RemoteSurfaceTrace(FileDescriptor fd, SurfaceControl wrapped,
            WindowState window) {
        super(wrapped);

        mWriteFd = fd;
        mOut = new DataOutputStream(new FileOutputStream(fd, false));

        mWindow = window;
        mService = mWindow.mService;
    }

    @Override
    public void setAlpha(float alpha) {
        final StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskWrites();
        try {
            writeFloatEvent("Alpha", alpha);
        } finally {
            StrictMode.setThreadPolicy(oldPolicy);
        }
        super.setAlpha(alpha);
    }

    @Override
    public void setLayer(int zorder) {
        final StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskWrites();
        try {
            writeIntEvent("Layer", zorder);
        } finally {
            StrictMode.setThreadPolicy(oldPolicy);
        }
        super.setLayer(zorder);
    }

    @Override
    public void setPosition(float x, float y) {
        final StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskWrites();
        try {
            writeFloatEvent("Position", x, y);
        } finally {
            StrictMode.setThreadPolicy(oldPolicy);
        }
        super.setPosition(x, y);
    }

    @Override
    public void setGeometryAppliesWithResize() {
        final StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskWrites();
        try {
            writeEvent("GeometryAppliesWithResize");
        } finally {
            StrictMode.setThreadPolicy(oldPolicy);
        }
        super.setGeometryAppliesWithResize();
    }

    @Override
    public void setSize(int w, int h) {
        final StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskWrites();
        try {
            writeIntEvent("Size", w, h);
        } finally {
            StrictMode.setThreadPolicy(oldPolicy);
        }
        super.setSize(w, h);
    }

    @Override
    public void setWindowCrop(Rect crop) {
        final StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskWrites();
        try {
            writeRectEvent("Crop", crop);
        } finally {
            StrictMode.setThreadPolicy(oldPolicy);
        }
        super.setWindowCrop(crop);
    }

    @Override
    public void setFinalCrop(Rect crop) {
        final StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskWrites();
        try {
            writeRectEvent("FinalCrop", crop);
        } finally {
            StrictMode.setThreadPolicy(oldPolicy);
        }
        super.setFinalCrop(crop);
    }

    @Override
    public void setLayerStack(int layerStack) {
        final StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskWrites();
        try {
            writeIntEvent("LayerStack", layerStack);
        } finally {
            StrictMode.setThreadPolicy(oldPolicy);
        }
        super.setLayerStack(layerStack);
    }

    @Override
    public void setMatrix(float dsdx, float dtdx, float dsdy, float dtdy) {
        final StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskWrites();
        try {
            writeFloatEvent("Matrix", dsdx, dtdx, dsdy, dtdy);
        } finally {
            StrictMode.setThreadPolicy(oldPolicy);
        }
        super.setMatrix(dsdx, dtdx, dsdy, dtdy);
    }

    @Override
    public void hide() {
        final StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskWrites();
        try {
            writeEvent("Hide");
        } finally {
            StrictMode.setThreadPolicy(oldPolicy);
        }
        super.hide();
    }

    @Override
    public void show() {
        final StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskWrites();
        try {
            writeEvent("Show");
        } finally {
            StrictMode.setThreadPolicy(oldPolicy);
        }
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
