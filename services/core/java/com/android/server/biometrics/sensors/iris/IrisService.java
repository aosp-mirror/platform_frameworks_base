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
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_IRIS;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.IBiometricService;
import android.hardware.biometrics.SensorPropertiesInternal;
import android.hardware.iris.IIrisService;
import android.os.Handler;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Slog;

import com.android.server.ServiceThread;
import com.android.server.SystemService;
import com.android.server.biometrics.Utils;

import java.util.List;

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

    private final IrisServiceWrapper mServiceWrapper;

    /**
     * Receives the incoming binder calls from IrisManager.
     */
    private final class IrisServiceWrapper extends IIrisService.Stub {
        @Override // Binder call
        public void registerAuthenticators(@NonNull List<SensorPropertiesInternal> hidlSensors) {
            Utils.checkPermission(getContext(), USE_BIOMETRIC_INTERNAL);

            // Some HAL might not be started before the system service and will cause the code below
            // to wait, and some of the operations below might take a significant amount of time to
            // complete (calls to the HALs). To avoid blocking the rest of system server we put
            // this on a background thread.
            final ServiceThread thread = new ServiceThread(TAG, Process.THREAD_PRIORITY_BACKGROUND,
                    true /* allowIo */);
            thread.start();
            final Handler handler = new Handler(thread.getLooper());

            handler.post(() -> {
                final IBiometricService biometricService = IBiometricService.Stub.asInterface(
                        ServiceManager.getService(Context.BIOMETRIC_SERVICE));

                for (SensorPropertiesInternal hidlSensor : hidlSensors) {
                    final int sensorId = hidlSensor.sensorId;
                    final @BiometricManager.Authenticators.Types int strength =
                            Utils.propertyStrengthToAuthenticatorStrength(
                                    hidlSensor.sensorStrength);
                    final IrisAuthenticator authenticator = new IrisAuthenticator(mServiceWrapper,
                            sensorId);
                    try {
                        biometricService.registerAuthenticator(sensorId, TYPE_IRIS, strength,
                                authenticator);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Remote exception when registering sensorId: " + sensorId);
                    }
                }
            });
        }
    }

    public IrisService(@NonNull Context context) {
        super(context);
        mServiceWrapper = new IrisServiceWrapper();
    }

    @Override
    public void onStart() {
        publishBinderService(Context.IRIS_SERVICE, mServiceWrapper);
    }
}
