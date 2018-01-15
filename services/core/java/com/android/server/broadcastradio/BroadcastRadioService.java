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

package com.android.server.broadcastradio;

import android.annotation.NonNull;
import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.radio.IRadioService;
import android.hardware.radio.ITuner;
import android.hardware.radio.ITunerCallback;
import android.hardware.radio.RadioManager;
import android.os.ParcelableException;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.SystemService;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

public class BroadcastRadioService extends SystemService {
    private static final String TAG = "BcRadioSrv";

    private final ServiceImpl mServiceImpl = new ServiceImpl();

    private final com.android.server.broadcastradio.hal1.BroadcastRadioService mHal1 =
            new com.android.server.broadcastradio.hal1.BroadcastRadioService();
    private final com.android.server.broadcastradio.hal2.BroadcastRadioService mHal2 =
            new com.android.server.broadcastradio.hal2.BroadcastRadioService();

    private final Object mLock = new Object();
    private List<RadioManager.ModuleProperties> mModules = null;

    public BroadcastRadioService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.RADIO_SERVICE, mServiceImpl);
    }

    /**
     * Finds next available index for newly loaded modules.
     */
    private static int getNextId(@NonNull List<RadioManager.ModuleProperties> modules) {
        OptionalInt max = modules.stream().mapToInt(RadioManager.ModuleProperties::getId).max();
        return max.isPresent() ? max.getAsInt() + 1 : 0;
    }

    private class ServiceImpl extends IRadioService.Stub {
        private void enforcePolicyAccess() {
            if (PackageManager.PERMISSION_GRANTED != getContext().checkCallingPermission(
                    Manifest.permission.ACCESS_BROADCAST_RADIO)) {
                throw new SecurityException("ACCESS_BROADCAST_RADIO permission not granted");
            }
        }

        @Override
        public List<RadioManager.ModuleProperties> listModules() {
            enforcePolicyAccess();
            synchronized (mLock) {
                if (mModules != null) return mModules;

                mModules = mHal1.loadModules();
                mModules.addAll(mHal2.loadModules(getNextId(mModules)));

                return mModules;
            }
        }

        @Override
        public ITuner openTuner(int moduleId, RadioManager.BandConfig bandConfig,
                boolean withAudio, ITunerCallback callback) throws RemoteException {
            Slog.i(TAG, "openTuner(" + moduleId + ", _, " + withAudio + ", _)");
            enforcePolicyAccess();
            if (callback == null) {
                throw new IllegalArgumentException("Callback must not be empty");
            }
            synchronized (mLock) {
                if (mHal2.hasModule(moduleId)) {
                    return mHal2.openSession(moduleId, bandConfig, withAudio, callback);
                } else {
                    return mHal1.openTuner(moduleId, bandConfig, withAudio, callback);
                }
            }
        }
    }
}
