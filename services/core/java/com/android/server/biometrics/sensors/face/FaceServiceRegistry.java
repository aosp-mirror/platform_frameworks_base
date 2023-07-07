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

package com.android.server.biometrics.sensors.face;

import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FACE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.IBiometricService;
import android.hardware.face.FaceSensorPropertiesInternal;
import android.hardware.face.IFaceAuthenticatorsRegisteredCallback;
import android.hardware.face.IFaceService;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.biometrics.Utils;
import com.android.server.biometrics.sensors.BiometricServiceRegistry;

import java.util.List;
import java.util.function.Supplier;

/** Registry for {@link IFaceService} providers. */
public class FaceServiceRegistry extends BiometricServiceRegistry<ServiceProvider,
        FaceSensorPropertiesInternal, IFaceAuthenticatorsRegisteredCallback> {

    private static final String TAG = "FaceServiceRegistry";

    @NonNull
    private final IFaceService mService;

    /** Creates a new registry tied to the given service. */
    public FaceServiceRegistry(@NonNull IFaceService service,
            @Nullable Supplier<IBiometricService> biometricSupplier) {
        super(biometricSupplier);
        mService = service;
    }

    @Override
    protected void registerService(@NonNull IBiometricService service,
            @NonNull FaceSensorPropertiesInternal props) {
        @BiometricManager.Authenticators.Types final int strength =
                Utils.propertyStrengthToAuthenticatorStrength(props.sensorStrength);
        try {
            service.registerAuthenticator(props.sensorId, TYPE_FACE, strength,
                    new FaceAuthenticator(mService, props.sensorId));
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception when registering sensorId: " + props.sensorId);
        }
    }

    @Override
    protected void invokeRegisteredCallback(@NonNull IFaceAuthenticatorsRegisteredCallback callback,
            @NonNull List<FaceSensorPropertiesInternal> allProps) throws RemoteException {
        callback.onAllAuthenticatorsRegistered(allProps);
    }
}
