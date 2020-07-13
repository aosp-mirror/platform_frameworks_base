/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.biometrics.sensors.iris;

import static android.Manifest.permission.USE_BIOMETRIC_INTERNAL;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.iris.IIrisService;

import com.android.server.SystemService;
import com.android.server.biometrics.Utils;

/**
 * A service to manage multiple clients that want to access the Iris HAL API.
 * The service is responsible for maintaining a list of clients and dispatching all
 * iris-related events.
 *
 * TODO: The vendor is expected to fill in the service. See
 * {@link com.android.server.biometrics.sensors.face.FaceService}
 */
public class IrisService extends SystemService {

    private static final String TAG = "IrisService";

    /**
     * Receives the incoming binder calls from IrisManager.
     */
    private final class IrisServiceWrapper extends IIrisService.Stub {
        @Override // Binder call
        public void initializeConfiguration(int sensorId) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);
        }
    }

    public IrisService(@NonNull Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.IRIS_SERVICE, new IrisServiceWrapper());
    }
}
