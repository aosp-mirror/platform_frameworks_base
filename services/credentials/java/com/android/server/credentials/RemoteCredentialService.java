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

package com.android.server.credentials;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.ICancellationSignal;
import android.os.RemoteException;
import android.service.credentials.BeginCreateCredentialRequest;
import android.service.credentials.BeginCreateCredentialResponse;
import android.service.credentials.BeginGetCredentialsRequest;
import android.service.credentials.BeginGetCredentialsResponse;
import android.service.credentials.CredentialProviderException;
import android.service.credentials.CredentialProviderException.CredentialProviderError;
import android.service.credentials.CredentialProviderService;
import android.service.credentials.IBeginCreateCredentialCallback;
import android.service.credentials.IBeginGetCredentialsCallback;
import android.service.credentials.ICredentialProviderService;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.Slog;

import com.android.internal.infra.ServiceConnector;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Handles connections with the remote credential provider
 *
 * @hide
 */
public class RemoteCredentialService extends ServiceConnector.Impl<ICredentialProviderService>{

    private static final String TAG = "RemoteCredentialService";
    /** Timeout for a single request. */
    private static final long TIMEOUT_REQUEST_MILLIS = 5 * DateUtils.SECOND_IN_MILLIS;
    /** Timeout to unbind after the task queue is empty. */
    private static final long TIMEOUT_IDLE_SERVICE_CONNECTION_MILLIS =
            5 * DateUtils.SECOND_IN_MILLIS;

    private final ComponentName mComponentName;

    /**
     * Callbacks to be invoked when the provider remote service responds with a
     * success or failure.
     * @param <T> the type of response expected from the provider
     */
    public interface ProviderCallbacks<T> {
        /** Called when a successful response is received from the remote provider. */
        void onProviderResponseSuccess(@Nullable T response);
        /** Called when a failure response is received from the remote provider. */
        void onProviderResponseFailure(int errorCode, @Nullable CharSequence message);
        /** Called when the remote provider service dies. */
        void onProviderServiceDied(RemoteCredentialService service);
    }

    public RemoteCredentialService(@NonNull Context context,
            @NonNull ComponentName componentName, int userId) {
        super(context, new Intent(CredentialProviderService.SERVICE_INTERFACE)
                        .setComponent(componentName), /*bindingFlags=*/0,
                userId, ICredentialProviderService.Stub::asInterface);
        mComponentName = componentName;
    }

    /** Unbinds automatically after this amount of time. */
    @Override
    protected long getAutoDisconnectTimeoutMs() {
        return TIMEOUT_IDLE_SERVICE_CONNECTION_MILLIS;
    }

    /** Return the componentName of the service to be connected. */
    @NonNull public ComponentName getComponentName() {
        return mComponentName;
    }

    /** Destroys this remote service by unbinding the connection. */
    public void destroy() {
        unbind();
    }

    /** Main entry point to be called for executing a getCredential call on the remote
     * provider service.
     * @param request the request to be sent to the provider
     * @param callback the callback to be used to send back the provider response to the
     *                 {@link ProviderGetSession} class that maintains provider state
     */
    public void onBeginGetCredentials(@NonNull BeginGetCredentialsRequest request,
            ProviderCallbacks<BeginGetCredentialsResponse> callback) {
        Log.i(TAG, "In onGetCredentials in RemoteCredentialService");
        AtomicReference<ICancellationSignal> cancellationSink = new AtomicReference<>();
        AtomicReference<CompletableFuture<BeginGetCredentialsResponse>> futureRef =
                new AtomicReference<>();

        CompletableFuture<BeginGetCredentialsResponse> connectThenExecute = postAsync(service -> {
            CompletableFuture<BeginGetCredentialsResponse> getCredentials =
                    new CompletableFuture<>();
            ICancellationSignal cancellationSignal =
                    service.onBeginGetCredentials(request,
                            new IBeginGetCredentialsCallback.Stub() {
                        @Override
                        public void onSuccess(BeginGetCredentialsResponse response) {
                            Log.i(TAG, "In onSuccess in RemoteCredentialService");
                            getCredentials.complete(response);
                        }

                        @Override
                        public void onFailure(@CredentialProviderError int errorCode,
                                CharSequence message) {
                            Log.i(TAG, "In onFailure in RemoteCredentialService");
                            String errorMsg = message == null ? "" : String.valueOf(message);
                            getCredentials.completeExceptionally(new CredentialProviderException(
                                    errorCode, errorMsg));
                        }
                    });
            CompletableFuture<BeginGetCredentialsResponse> future = futureRef.get();
            if (future != null && future.isCancelled()) {
                dispatchCancellationSignal(cancellationSignal);
            } else {
                cancellationSink.set(cancellationSignal);
            }
            return getCredentials;
        }).orTimeout(TIMEOUT_REQUEST_MILLIS, TimeUnit.MILLISECONDS);

        futureRef.set(connectThenExecute);
        connectThenExecute.whenComplete((result, error) -> Handler.getMain().post(() ->
                handleExecutionResponse(result, error, cancellationSink, callback)));
    }

    /** Main entry point to be called for executing a beginCreateCredential call on the remote
     * provider service.
     * @param request the request to be sent to the provider
     * @param callback the callback to be used to send back the provider response to the
     *                 {@link ProviderCreateSession} class that maintains provider state
     */
    public void onCreateCredential(@NonNull BeginCreateCredentialRequest request,
            ProviderCallbacks<BeginCreateCredentialResponse> callback) {
        Log.i(TAG, "In onCreateCredential in RemoteCredentialService");
        AtomicReference<ICancellationSignal> cancellationSink = new AtomicReference<>();
        AtomicReference<CompletableFuture<BeginCreateCredentialResponse>> futureRef =
                new AtomicReference<>();

        CompletableFuture<BeginCreateCredentialResponse> connectThenExecute =
                postAsync(service -> {
            CompletableFuture<BeginCreateCredentialResponse> createCredentialFuture =
                    new CompletableFuture<>();
            ICancellationSignal cancellationSignal = service.onBeginCreateCredential(
                    request, new IBeginCreateCredentialCallback.Stub() {
                        @Override
                        public void onSuccess(BeginCreateCredentialResponse response) {
                            Log.i(TAG, "In onSuccess onBeginCreateCredential "
                                    + "in RemoteCredentialService");
                            createCredentialFuture.complete(response);
                        }

                        @Override
                        public void onFailure(@CredentialProviderError int errorCode,
                                CharSequence message) {
                            Log.i(TAG, "In onFailure in RemoteCredentialService");
                            String errorMsg = message == null ? "" : String.valueOf(message);
                            createCredentialFuture.completeExceptionally(
                                    new CredentialProviderException(errorCode, errorMsg));
                        }});
            CompletableFuture<BeginCreateCredentialResponse> future = futureRef.get();
            if (future != null && future.isCancelled()) {
                dispatchCancellationSignal(cancellationSignal);
            } else {
                cancellationSink.set(cancellationSignal);
            }
            return createCredentialFuture;
        }).orTimeout(TIMEOUT_REQUEST_MILLIS, TimeUnit.MILLISECONDS);

        futureRef.set(connectThenExecute);
        connectThenExecute.whenComplete((result, error) -> Handler.getMain().post(() ->
                handleExecutionResponse(result, error, cancellationSink, callback)));
    }

    private <T> void handleExecutionResponse(T result,
            Throwable error,
            AtomicReference<ICancellationSignal> cancellationSink,
            ProviderCallbacks<T> callback) {
        if (error == null) {
            Log.i(TAG, "In RemoteCredentialService execute error is null");
            callback.onProviderResponseSuccess(result);
        } else {
            if (error instanceof TimeoutException) {
                Log.i(TAG, "In RemoteCredentialService execute error is timeout");
                dispatchCancellationSignal(cancellationSink.get());
                callback.onProviderResponseFailure(
                        CredentialProviderException.ERROR_TIMEOUT,
                        error.getMessage());
            } else if (error instanceof CancellationException) {
                Log.i(TAG, "In RemoteCredentialService execute error is cancellation");
                dispatchCancellationSignal(cancellationSink.get());
                callback.onProviderResponseFailure(
                        CredentialProviderException.ERROR_TASK_CANCELED,
                        error.getMessage());
            } else if (error instanceof CredentialProviderException) {
                Log.i(TAG, "In RemoteCredentialService execute error is provider error");
                callback.onProviderResponseFailure(((CredentialProviderException) error)
                                .getErrorCode(),
                        error.getMessage());
            } else {
                Log.i(TAG, "In RemoteCredentialService execute error is unknown");
                callback.onProviderResponseFailure(
                        CredentialProviderException.ERROR_UNKNOWN,
                        error.getMessage());
            }
        }
    }

    private void dispatchCancellationSignal(@Nullable ICancellationSignal signal) {
        if (signal == null) {
            Slog.e(TAG, "Error dispatching a cancellation - Signal is null");
            return;
        }
        try {
            signal.cancel();
        } catch (RemoteException e) {
            Slog.e(TAG, "Error dispatching a cancellation", e);
        }
    }
}
