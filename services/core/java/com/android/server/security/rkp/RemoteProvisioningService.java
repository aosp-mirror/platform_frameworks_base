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

package com.android.server.security.rkp;

import android.content.Context;
import android.os.Binder;
import android.os.OutcomeReceiver;
import android.os.RemoteException;
import android.security.rkp.IGetKeyCallback;
import android.security.rkp.IGetRegistrationCallback;
import android.security.rkp.IRegistration;
import android.security.rkp.IRemoteProvisioning;
import android.security.rkp.service.RegistrationProxy;
import android.util.Log;

import com.android.server.SystemService;

import java.time.Duration;

/**
 * Implements the remote provisioning system service. This service is backed by a mainline
 * module, allowing the underlying implementation to be updated. The code here is a thin
 * proxy for the code in android.security.rkp.service.
 *
 * @hide
 */
public class RemoteProvisioningService extends SystemService {
    public static final String TAG = "RemoteProvisionSysSvc";
    private static final Duration CREATE_REGISTRATION_TIMEOUT = Duration.ofSeconds(10);
    private final RemoteProvisioningImpl mBinderImpl = new RemoteProvisioningImpl();

    /** @hide */
    public RemoteProvisioningService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.REMOTE_PROVISIONING_SERVICE, mBinderImpl);
    }

    private final class RemoteProvisioningImpl extends IRemoteProvisioning.Stub {

        final class RegistrationBinder extends IRegistration.Stub {
            static final String TAG = RemoteProvisioningService.TAG;
            private final RegistrationProxy mRegistration;

            RegistrationBinder(RegistrationProxy registration) {
                mRegistration = registration;
            }

            @Override
            public void getKey(int keyId, IGetKeyCallback callback) {
                Log.e(TAG, "RegistrationBinder.getKey NOT YET IMPLEMENTED");
            }

            @Override
            public void cancelGetKey(IGetKeyCallback callback) {
                Log.e(TAG, "RegistrationBinder.cancelGetKey NOT YET IMPLEMENTED");
            }

            @Override
            public void storeUpgradedKey(byte[] oldKeyBlob, byte[] newKeyBlob) {
                Log.e(TAG, "RegistrationBinder.storeUpgradedKey NOT YET IMPLEMENTED");
            }
        }

        @Override
        public void getRegistration(String irpcName, IGetRegistrationCallback callback)
                throws RemoteException {
            final int callerUid = Binder.getCallingUidOrThrow();
            final long callingIdentity = Binder.clearCallingIdentity();
            try {
                Log.i(TAG, "getRegistration(" + irpcName + ")");
                RegistrationProxy.createAsync(
                        getContext(),
                        callerUid,
                        irpcName,
                        CREATE_REGISTRATION_TIMEOUT,
                        getContext().getMainExecutor(),
                        new OutcomeReceiver<>() {
                            @Override
                            public void onResult(RegistrationProxy registration) {
                                try {
                                    callback.onSuccess(new RegistrationBinder(registration));
                                } catch (RemoteException e) {
                                    Log.e(TAG, "Error calling success callback", e);
                                }
                            }

                            @Override
                            public void onError(Exception error) {
                                try {
                                    callback.onError(error.toString());
                                } catch (RemoteException e) {
                                    Log.e(TAG, "Error calling error callback", e);
                                }
                            }
                        });
            } finally {
                Binder.restoreCallingIdentity(callingIdentity);
            }
        }

        @Override
        public void cancelGetRegistration(IGetRegistrationCallback callback)
                throws RemoteException {
            Log.i(TAG, "cancelGetRegistration()");
            callback.onError("cancelGetRegistration not yet implemented");
        }
    }
}
