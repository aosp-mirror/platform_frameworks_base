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

package android.service.credentials;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.SdkConstant;
import android.app.Service;
import android.content.Intent;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.Looper;
import android.os.OutcomeReceiver;
import android.os.RemoteException;
import android.util.Log;

import java.util.Objects;

/**
 * Service to be extended by credential providers, in order to return user credentials
 * to the framework.
 *
 * @hide
 */
public abstract class CredentialProviderService extends Service {
    private static final String TAG = "CredProviderService";

    public static final String CAPABILITY_META_DATA_KEY = "android.credentials.capabilities";

    private Handler mHandler;

    /**
     * The {@link Intent} that must be declared as handled by the service. The service must also
     * require the {android.Manifest.permission#BIND_CREDENTIAL_PROVIDER_SERVICE} permission
     * so that only the system can bind to it.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "android.service.credentials.CredentialProviderService";

    @CallSuper
    @Override
    public void onCreate() {
        super.onCreate();
        mHandler = new Handler(Looper.getMainLooper(), null, true);
    }

    @Override
    public final @NonNull IBinder onBind(@NonNull Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return mInterface.asBinder();
        }
        Log.i(TAG, "Failed to bind with intent: " + intent);
        return null;
    }

    private final ICredentialProviderService mInterface = new ICredentialProviderService.Stub() {
        @Override
        public ICancellationSignal onGetCredentials(GetCredentialsRequest request,
                IGetCredentialsCallback callback) {
            Objects.requireNonNull(request);
            Objects.requireNonNull(callback);

            ICancellationSignal transport = CancellationSignal.createTransport();

            mHandler.sendMessage(obtainMessage(
                    CredentialProviderService::onGetCredentials,
                    CredentialProviderService.this, request,
                    CancellationSignal.fromTransport(transport),
                    new OutcomeReceiver<GetCredentialsResponse, CredentialProviderException>() {
                        @Override
                        public void onResult(GetCredentialsResponse result) {
                            try {
                                callback.onSuccess(result);
                            } catch (RemoteException e) {
                                e.rethrowFromSystemServer();
                            }
                        }
                        @Override
                        public void onError(CredentialProviderException e) {
                            try {
                                callback.onFailure(e.getErrorCode(), e.getMessage());
                            } catch (RemoteException ex) {
                                ex.rethrowFromSystemServer();
                            }
                        }
                    }
            ));
            return transport;
        }

        @Override
        public ICancellationSignal onCreateCredential(CreateCredentialRequest request,
                ICreateCredentialCallback callback) {
            Objects.requireNonNull(request);
            Objects.requireNonNull(callback);

            ICancellationSignal transport = CancellationSignal.createTransport();

            mHandler.sendMessage(obtainMessage(
                    CredentialProviderService::onCreateCredential,
                    CredentialProviderService.this, request,
                    CancellationSignal.fromTransport(transport),
                    new OutcomeReceiver<CreateCredentialResponse, CredentialProviderException>() {
                        @Override
                        public void onResult(CreateCredentialResponse result) {
                            try {
                                callback.onSuccess(result);
                            } catch (RemoteException e) {
                                e.rethrowFromSystemServer();
                            }
                        }
                        @Override
                        public void onError(CredentialProviderException e) {
                            try {
                                callback.onFailure(e.getErrorCode(), e.getMessage());
                            } catch (RemoteException ex) {
                                ex.rethrowFromSystemServer();
                            }
                        }
                    }
            ));
            return transport;
        }
    };

    /**
     * Called by the android system to retrieve user credentials from the connected provider
     * service.
     * @param request the credential request for the provider to handle
     * @param cancellationSignal signal for providers to listen to any cancellation requests from
     *                           the android system
     * @param callback object used to relay the response of the credentials request
     */
    public abstract void onGetCredentials(@NonNull GetCredentialsRequest request,
            @NonNull CancellationSignal cancellationSignal,
            @NonNull OutcomeReceiver<GetCredentialsResponse, CredentialProviderException> callback);

    /**
     * Called by the android system to create a credential.
     * @param request The credential creation request for the provider to handle.
     * @param cancellationSignal Signal for providers to listen to any cancellation requests from
     *                           the android system.
     * @param callback Object used to relay the response of the credential creation request.
     */
    public abstract void onCreateCredential(@NonNull CreateCredentialRequest request,
            @NonNull CancellationSignal cancellationSignal,
            @NonNull OutcomeReceiver<CreateCredentialResponse,
                    CredentialProviderException> callback);
}
