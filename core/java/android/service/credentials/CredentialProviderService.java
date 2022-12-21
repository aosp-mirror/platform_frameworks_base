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
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.credentials.ClearCredentialStateException;
import android.credentials.CreateCredentialException;
import android.credentials.GetCredentialException;
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
 */
public abstract class CredentialProviderService extends Service {
    /**
     * Intent extra: The {@link android.credentials.CreateCredentialRequest} attached with
     * the {@code pendingIntent} that is invoked when the user selects a {@link CreateEntry}
     * returned as part of the {@link BeginCreateCredentialResponse}
     *
     * <p>
     * Type: {@link android.service.credentials.CreateCredentialRequest}
     */
    public static final String EXTRA_CREATE_CREDENTIAL_REQUEST =
            "android.service.credentials.extra.CREATE_CREDENTIAL_REQUEST";

    /**
     * Intent extra: The {@link GetCredentialRequest} attached with
     * the {@code pendingIntent} that is invoked when the user selects a {@link CredentialEntry}
     * returned as part of the {@link BeginGetCredentialResponse}
     *
     * <p>
     * Type: {@link GetCredentialRequest}
     */
    public static final String EXTRA_GET_CREDENTIAL_REQUEST =
            "android.service.credentials.extra.GET_CREDENTIAL_REQUEST";

    /**
     * Intent extra: The result of a create flow operation, to be set on finish of the
     * {@link android.app.Activity} invoked through the {@code pendingIntent} set on
     * a {@link CreateEntry}.
     *
     * <p>
     * Type: {@link android.credentials.CreateCredentialResponse}
     */
    public static final String EXTRA_CREATE_CREDENTIAL_RESPONSE =
            "android.service.credentials.extra.CREATE_CREDENTIAL_RESPONSE";

    /**
     * Intent extra: The result of a get credential flow operation, to be set on finish of the
     * {@link android.app.Activity} invoked through the {@code pendingIntent} set on
     * a {@link CredentialEntry}.
     *
     * <p>
     * Type: {@link android.credentials.GetCredentialResponse}
     */
    public static final String EXTRA_GET_CREDENTIAL_RESPONSE =
            "android.service.credentials.extra.GET_CREDENTIAL_RESPONSE";

    /**
     * Intent extra: The result of an authentication flow, to be set on finish of the
     * {@link android.app.Activity} invoked through the {@link android.app.PendingIntent} set on
     * a {@link BeginGetCredentialResponse}. This result should contain the actual content,
     * including credential entries and action entries, to be shown on the selector.
     *
     * <p>
     * Type: {@link CredentialsResponseContent}
     */
    public static final String EXTRA_CREDENTIALS_RESPONSE_CONTENT =
            "android.service.credentials.extra.CREDENTIALS_RESPONSE_CONTENT";

    /**
     * Intent extra: The failure exception set at the final stage of a get flow.
     * This exception is set at the finishing result of the {@link android.app.Activity}
     * invoked by the {@link PendingIntent} , when a user selects the {@link CredentialEntry}
     * that contained the {@link PendingIntent} in question.
     *
     * <p>The result must be set through {@link android.app.Activity#setResult} as an intent extra
     *
     * <p>
     * Type: {@link android.credentials.GetCredentialException}
     */
    public static final String EXTRA_GET_CREDENTIAL_EXCEPTION =
            "android.service.credentials.extra.GET_CREDENTIAL_EXCEPTION";

    /**
     * Intent extra: The failure exception set at the final stage of a create flow.
     * This exception is set at the finishing result of the {@link android.app.Activity}
     * invoked by the {@link PendingIntent} , when a user selects the {@link CreateEntry}
     * that contained the {@link PendingIntent} in question.
     *
     * <p>
     * Type: {@link android.credentials.CreateCredentialException}
     */
    public static final String EXTRA_CREATE_CREDENTIAL_EXCEPTION =
            "android.service.credentials.extra.CREATE_CREDENTIAL_EXCEPTION";

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

    /**
     * The {@link Intent} that must be declared as handled by a system credential provider
     * service.
     *
     * <p>The service must also require the
     * {android.Manifest.permission#BIND_CREDENTIAL_PROVIDER_SERVICE} permission
     * so that only the system can bind to it.
     *
     * @hide
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SYSTEM_SERVICE_INTERFACE =
            "android.service.credentials.system.CredentialProviderService";

    @CallSuper
    @Override
    public void onCreate() {
        super.onCreate();
        mHandler = new Handler(Looper.getMainLooper(), null, true);
    }

    @Override
    @NonNull public final IBinder onBind(@NonNull Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return mInterface.asBinder();
        }
        Log.i(TAG, "Failed to bind with intent: " + intent);
        return null;
    }

    private final ICredentialProviderService mInterface = new ICredentialProviderService.Stub() {
        public ICancellationSignal onBeginGetCredential(BeginGetCredentialRequest request,
                IBeginGetCredentialCallback callback) {
            Objects.requireNonNull(request);
            Objects.requireNonNull(callback);

            ICancellationSignal transport = CancellationSignal.createTransport();

            mHandler.sendMessage(obtainMessage(
                    CredentialProviderService::onBeginGetCredential,
                    CredentialProviderService.this, request,
                    CancellationSignal.fromTransport(transport),
                    new OutcomeReceiver<BeginGetCredentialResponse,
                            GetCredentialException>() {
                        @Override
                        public void onResult(BeginGetCredentialResponse result) {
                            try {
                                callback.onSuccess(result);
                            } catch (RemoteException e) {
                                e.rethrowFromSystemServer();
                            }
                        }
                        @Override
                        public void onError(GetCredentialException e) {
                            try {
                                callback.onFailure(e.errorType, e.getMessage());
                            } catch (RemoteException ex) {
                                ex.rethrowFromSystemServer();
                            }
                        }
                    }
            ));
            return transport;
        }

        @Override
        public ICancellationSignal onBeginCreateCredential(BeginCreateCredentialRequest request,
                IBeginCreateCredentialCallback callback) {
            Objects.requireNonNull(request);
            Objects.requireNonNull(callback);

            ICancellationSignal transport = CancellationSignal.createTransport();

            mHandler.sendMessage(obtainMessage(
                    CredentialProviderService::onBeginCreateCredential,
                    CredentialProviderService.this, request,
                    CancellationSignal.fromTransport(transport),
                    new OutcomeReceiver<
                            BeginCreateCredentialResponse, CreateCredentialException>() {
                        @Override
                        public void onResult(BeginCreateCredentialResponse result) {
                            try {
                                callback.onSuccess(result);
                            } catch (RemoteException e) {
                                e.rethrowFromSystemServer();
                            }
                        }
                        @Override
                        public void onError(CreateCredentialException e) {
                            try {
                                callback.onFailure(e.errorType, e.getMessage());
                            } catch (RemoteException ex) {
                                ex.rethrowFromSystemServer();
                            }
                        }
                    }
            ));
            return transport;
        }

        @Override
        public ICancellationSignal onClearCredentialState(ClearCredentialStateRequest request,
                IClearCredentialStateCallback callback) {
            Objects.requireNonNull(request);
            Objects.requireNonNull(callback);

            ICancellationSignal transport = CancellationSignal.createTransport();

            mHandler.sendMessage(obtainMessage(
                    CredentialProviderService::onClearCredentialState,
                    CredentialProviderService.this, request,
                    CancellationSignal.fromTransport(transport),
                    new OutcomeReceiver<Void, ClearCredentialStateException>() {
                        @Override
                        public void onResult(Void result) {
                            try {
                                callback.onSuccess();
                            } catch (RemoteException e) {
                                e.rethrowFromSystemServer();
                            }
                        }
                        @Override
                        public void onError(ClearCredentialStateException e) {
                            try {
                                callback.onFailure(e.errorType, e.getMessage());
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
     *
     * <p>This API denotes a query stage request for getting user's credentials from a given
     * credential provider. The request contains a list of
     * {@link android.credentials.GetCredentialOption} that have parameters to be used for
     * populating candidate credentials, as a list of {@link CredentialEntry} to be set
     * on the {@link BeginGetCredentialResponse}. This list is then shown to the user on a
     * selector.
     *
     * <p>If a {@link PendingIntent} is set on a {@link CredentialEntry}, and the user selects that
     * entry, a {@link GetCredentialRequest} with all parameters needed to get the actual
     * {@link android.credentials.Credential} will be sent as part of the {@link Intent} fired
     * through the {@link PendingIntent}.
     * @param request the request for the provider to handle
     * @param cancellationSignal signal for providers to listen to any cancellation requests from
     *                           the android system
     * @param callback object used to relay the response of the credentials request
     */
    public abstract void onBeginGetCredential(@NonNull BeginGetCredentialRequest request,
            @NonNull CancellationSignal cancellationSignal,
            @NonNull OutcomeReceiver<
                    BeginGetCredentialResponse, GetCredentialException> callback);

    /**
     * Called by the android system to create a credential.
     * @param request The credential creation request for the provider to handle.
     * @param cancellationSignal Signal for providers to listen to any cancellation requests from
     *                           the android system.
     * @param callback Object used to relay the response of the credential creation request.
     */
    public abstract void onBeginCreateCredential(@NonNull BeginCreateCredentialRequest request,
            @NonNull CancellationSignal cancellationSignal,
            @NonNull OutcomeReceiver<BeginCreateCredentialResponse,
                    CreateCredentialException> callback);

    /**
     * Called by the android system to clear the credential state.
     *
     * This api isinvoked by developers after users sign out of an app, with an intention to
     * clear any stored credential session that providers have retained.
     *
     * As a provider, you must clear any credential state, if maintained. For e.g. a provider may
     * have stored an active credential session that is used to limit or rank sign-in options for
     * future credential retrieval flows. When a user signs out of the app, such state should be
     * cleared and an exhaustive list of credentials must be presented to the user on subsequent
     * credential retrieval flows.
     *
     * @param request The clear credential request for the provider to handle.
     * @param cancellationSignal Signal for providers to listen to any cancellation requests from
     *                           the android system.
     * @param callback Object used to relay the result of the request.
     */
    public abstract void onClearCredentialState(@NonNull ClearCredentialStateRequest request,
            @NonNull CancellationSignal cancellationSignal,
            @NonNull OutcomeReceiver<Void,
                    ClearCredentialStateException> callback);
}
