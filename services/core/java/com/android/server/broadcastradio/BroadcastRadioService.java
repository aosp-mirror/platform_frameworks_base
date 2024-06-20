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

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.radio.IRadioService;

import com.android.server.SystemService;

import java.util.ArrayList;

public final class BroadcastRadioService extends SystemService {
    private final IRadioService mServiceImpl;

    public BroadcastRadioService(Context context) {
        super(context);
        ArrayList<String> serviceNameList = IRadioServiceAidlImpl.getServicesNames();
        mServiceImpl = serviceNameList.isEmpty() ? new IRadioServiceHidlImpl(this)
                : new IRadioServiceAidlImpl(this, serviceNameList);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.RADIO_SERVICE, mServiceImpl.asBinder());
    }

    @SuppressLint("AndroidFrameworkRequiresPermission")
    void enforcePolicyAccess() {
        if (getContext().checkCallingPermission(Manifest.permission.ACCESS_BROADCAST_RADIO)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("ACCESS_BROADCAST_RADIO permission not granted");
        }
    }
}
