/**
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

package com.android.server.broadcastradio.hal2;

import android.annotation.NonNull;
import android.hardware.broadcastradio.V2_0.ITunerCallback;
import android.hardware.broadcastradio.V2_0.ProgramInfo;
import android.hardware.broadcastradio.V2_0.ProgramListChunk;
import android.hardware.broadcastradio.V2_0.ProgramSelector;
import android.hardware.broadcastradio.V2_0.VendorKeyValue;
import android.os.RemoteException;
import android.util.Slog;

import java.util.ArrayList;
import java.util.Objects;

class TunerCallback extends ITunerCallback.Stub {
    private static final String TAG = "BcRadio2Srv.cb";

    final android.hardware.radio.ITunerCallback mClientCb;

    interface RunnableThrowingRemoteException {
        void run() throws RemoteException;
    }

    TunerCallback(@NonNull android.hardware.radio.ITunerCallback clientCallback) {
        mClientCb = Objects.requireNonNull(clientCallback);
    }

    static void dispatch(RunnableThrowingRemoteException func) {
        try {
            func.run();
        } catch (RemoteException ex) {
            Slog.e(TAG, "callback call failed", ex);
        }
    }

    @Override
    public void onTuneFailed(int result, ProgramSelector selector) {
        dispatch(() -> mClientCb.onTuneFailed(result, Convert.programSelectorFromHal(selector)));
    }

    @Override
    public void onCurrentProgramInfoChanged(ProgramInfo info) {
        dispatch(() -> mClientCb.onCurrentProgramInfoChanged(Convert.programInfoFromHal(info)));
    }

    @Override
    public void onProgramListUpdated(ProgramListChunk chunk) {
        dispatch(() -> mClientCb.onProgramListUpdated(Convert.programListChunkFromHal(chunk)));
    }

    @Override
    public void onAntennaStateChange(boolean connected) {
        dispatch(() -> mClientCb.onAntennaState(connected));
    }

    @Override
    public void onParametersUpdated(ArrayList<VendorKeyValue> parameters) {
        dispatch(() -> mClientCb.onParametersUpdated(Convert.vendorInfoFromHal(parameters)));
    }
}
