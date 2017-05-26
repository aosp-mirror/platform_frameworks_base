/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.connectivity.tethering;

import android.hardware.tetheroffload.control.V1_0.IOffloadControl;
import android.hardware.tetheroffload.control.V1_0.ITetheringOffloadCallback;
import android.hardware.tetheroffload.control.V1_0.NatTimeoutUpdate;
import android.os.Handler;
import android.os.RemoteException;
import android.net.util.SharedLog;


/**
 * Capture tethering dependencies, for injection.
 *
 * @hide
 */
public class OffloadHardwareInterface {
    private static final String TAG = OffloadHardwareInterface.class.getSimpleName();

    private static native boolean configOffload();

    private final Handler mHandler;
    private final SharedLog mLog;
    private IOffloadControl mOffloadControl;
    private TetheringOffloadCallback mTetheringOffloadCallback;
    private ControlCallback mControlCallback;

    public static class ControlCallback {
        public void onOffloadEvent(int event) {}

        public void onNatTimeoutUpdate(int proto,
                                       String srcAddr, int srcPort,
                                       String dstAddr, int dstPort) {}
    }

    public OffloadHardwareInterface(Handler h, SharedLog log) {
        mHandler = h;
        mLog = log.forSubComponent(TAG);
    }

    public boolean initOffloadConfig() {
        return configOffload();
    }

    public boolean initOffloadControl(ControlCallback controlCb) {
        mControlCallback = controlCb;

        if (mOffloadControl == null) {
            try {
                mOffloadControl = IOffloadControl.getService();
            } catch (RemoteException e) {
                mLog.e("tethering offload control not supported: " + e);
                return false;
            }
        }

        mTetheringOffloadCallback = new TetheringOffloadCallback(mHandler, mControlCallback);
        final CbResults results = new CbResults();
        try {
            mOffloadControl.initOffload(
                    mTetheringOffloadCallback,
                    (boolean success, String errMsg) -> {
                        results.success = success;
                        results.errMsg = errMsg;
                    });
        } catch (RemoteException e) {
            mLog.e("failed to initOffload: " + e);
            return false;
        }

        if (!results.success) mLog.e("initOffload failed: " + results.errMsg);
        return results.success;
    }

    public void stopOffloadControl() {
        if (mOffloadControl != null) {
            try {
                mOffloadControl.stopOffload(
                        (boolean success, String errMsg) -> {
                            if (!success) mLog.e("stopOffload failed: " + errMsg);
                        });
            } catch (RemoteException e) {
                mLog.e("failed to stopOffload: " + e);
            }
        }
        mOffloadControl = null;
        mTetheringOffloadCallback = null;
        mControlCallback = null;
    }

    private static class TetheringOffloadCallback extends ITetheringOffloadCallback.Stub {
        public final Handler handler;
        public final ControlCallback controlCb;

        public TetheringOffloadCallback(Handler h, ControlCallback cb) {
            handler = h;
            controlCb = cb;
        }

        @Override
        public void onEvent(int event) {
            handler.post(() -> { controlCb.onOffloadEvent(event); });
        }

        @Override
        public void updateTimeout(NatTimeoutUpdate params) {
            handler.post(() -> {
                    controlCb.onNatTimeoutUpdate(
                        params.proto,
                        params.src.addr, params.src.port,
                        params.dst.addr, params.dst.port);
            });
        }
    }

    private static class CbResults {
        boolean success;
        String errMsg;
    }
}
