/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wm.flicker.monitor;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

/**
 * Captures Layers trace from SurfaceFlinger.
 */
public class LayersTraceMonitor extends TraceMonitor {
    private static final String TAG = "LayersTraceMonitor";
    private IBinder mSurfaceFlinger = ServiceManager.getService("SurfaceFlinger");

    public LayersTraceMonitor() {
        traceFileName = "layers_trace.pb";
    }

    @Override
    public void start() {
        setEnabled(true);
    }

    @Override
    public void stop() {
        setEnabled(false);
    }

    @Override
    public boolean isEnabled() throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken("android.ui.ISurfaceComposer");
        mSurfaceFlinger.transact(/* LAYER_TRACE_STATUS_CODE */ 1026,
                data, reply, 0 /* flags */);
        return reply.readBoolean();
    }

    private void setEnabled(boolean isEnabled) {
        Parcel data = null;
        try {
            if (mSurfaceFlinger != null) {
                data = Parcel.obtain();
                data.writeInterfaceToken("android.ui.ISurfaceComposer");
                data.writeInt(isEnabled ? 1 : 0);
                mSurfaceFlinger.transact( /* LAYER_TRACE_CONTROL_CODE */ 1025,
                        data, null, 0 /* flags */);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Could not set layer tracing." + e.toString());
        } finally {
            if (data != null) {
                data.recycle();
            }
        }
    }
}
