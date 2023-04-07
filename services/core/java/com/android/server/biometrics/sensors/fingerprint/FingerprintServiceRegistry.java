/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.biometrics.sensors.fingerprint;

import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FINGERPRINT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.biometrics.IBiometricService;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IFingerprintAuthenticatorsRegisteredCallback;
import android.hardware.fingerprint.IFingerprintService;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.sensors.BiometricServiceRegistry;

import java.util.List;
import java.util.function.Supplier;

/** Registry for {@link IFingerprintService} providers. */
public class FingerprintServiceRegistry extends BiometricServiceRegistry<ServiceProvider,
        FingerprintSensorPropertiesInternal, IFingerprintAuthenticatorsRegisteredCallback> {

    private static final String TAG = "FingerprintServiceRegistry";

    @NonNull
    private final IFingerprintService mService;

    /** Creates a new registry tied to the given service. */
    public FingerprintServiceRegistry(@NonNull IFingerprintService service,
            @Nullable Supplier<IBiometricService> biometricSupplier) {
        super(biometricSupplier);
        mService = service;
    }

    @Override
    protected void registerService(@NonNull IBiometricService service,
            @NonNull FingerprintSensorPropertiesInternal props) {
        try {
            service.registerAuthenticator(TYPE_FINGERPRINT, props,
                    new FingerprintAuthenticator(mService, props.sensorId));
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception when registering sensorId: " + props.sensorId);
        }
    }

    @Override
    protected void invokeRegisteredCallback(
            @NonNull IFingerprintAuthenticatorsRegisteredCallback callback,
            @NonNull List<FingerprintSensorPropertiesInternal> allProps) throws RemoteException {
        callback.onAllAuthenticatorsRegistered(allProps);
    }
}
