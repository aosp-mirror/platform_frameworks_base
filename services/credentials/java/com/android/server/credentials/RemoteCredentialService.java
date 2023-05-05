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
import android.credentials.ClearCredentialStateException;
import android.credentials.CreateCredentialException;
import android.credentials.GetCredentialException;
import android.os.Binder;
import android.os.Handler;
import android.os.ICancellationSignal;
import android.os.RemoteException;
import android.service.credentials.BeginCreateCredentialRequest;
import android.service.credentials.BeginCreateCredentialResponse;
import android.service.credentials.BeginGetCredentialRequest;
import android.service.credentials.BeginGetCredentialResponse;
import android.service.credentials.ClearCredentialStateRequest;
import android.service.credentials.CredentialProviderErrors;
import android.service.credentials.CredentialProviderService;
import android.service.credentials.IBeginCreateCredentialCallback;
import android.service.credentials.IBeginGetCredentialCallback;
import android.service.credentials.IClearCredentialStateCallback;
import android.service.credentials.ICredentialProviderService;
import android.text.format.DateUtils;
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
public class RemoteCredentialService extends ServiceConnector.Impl<ICredentialProviderService> {

    private static final String TAG = "RemoteCredentialService";
    /** Timeout for a single request. */
    private static final long TIMEOUT_REQUEST_MILLIS = 3 * DateUtils.SECOND_IN_MILLIS;
    /** Timeout to unbind after the task queue is empty. */
    private static final long TIMEOUT_IDLE_SERVICE_CONNECTION_MILLIS =
            5 * DateUtils.SECOND_IN_MILLIS;

    private final ComponentName mComponentName;

    /**
     * Callbacks to be invoked when the provider remote service responds with a
     * success or failure.
     *
     * @param <T> the type of response expected from the provider
     */
    public interface ProviderCallbacks<T> {
        /** Called when a successful response is received from the remote provider. */
        void onProviderResponseSuccess(@Nullable T response);

        /** Called when a failure response is received from the remote provider. */
        void onProviderResponseFailure(int internalErrorCode, @Nullable Exception e);

        /** Called when the remote provider service dies. */
        void onProviderServiceDied(RemoteCredentialService service);

        /** Called to set the cancellation transport from the remote provider service. */
        void onProviderCancellable(ICancellationSignal cancellation);
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
    @NonNull
    public ComponentName getComponentName() {
        return mComponentName;
    }

    /** Destroys this remote service by unbinding the connection. */
    public void destroy() {
        unbind();
    }

    /**
     * Main entry point to be called for executing a getCredential call on the remote
     * provider service.
     *
     * @param request  the request to be sent to the provider
     * @param callback the callback to be used to send back the provider response to the
     *                 {@link ProviderGetSession} class that maintains provider state
     */
    public void onBeginGetCredential(@NonNull BeginGetCredentialRequest request,
            ProviderCallbacks<BeginGetCredentialResponse> callback) {
        AtomicReference<ICancellationSignal> cancellationSink = new AtomicReference<>();
        AtomicReference<CompletableFuture<BeginGetCredentialResponse>> futureRef =
                new AtomicReference<>();


        CompletableFuture<BeginGetCredentialResponse> connectThenExecute = postAsync(service -> {
            CompletableFuture<BeginGetCredentialResponse> getCredentials =
                    new CompletableFuture<>();
            final long originalCallingUidToken = Binder.clearCallingIdentity();
            try {
                service.onBeginGetCredential(request,
                        new IBeginGetCredentialCallback.Stub() {
                            @Override
                            public void onSuccess(BeginGetCredentialResponse response) {
                                getCredentials.complete(response);
                            }

                            @Override
                            public void onFailure(String errorType, CharSequence message) {
                                String errorMsg = message == null ? "" : String.valueOf(
                                        message);
                                getCredentials.completeExceptionally(
                                        new GetCredentialException(errorType, errorMsg));
                            }

                            @Override
                            public void onCancellable(ICancellationSignal cancellation) {
                                CompletableFuture<BeginGetCredentialResponse> future =
                                        futureRef.get();
                                if (future != null && future.isCancelled()) {
                                    dispatchCancellationSignal(cancellation);
                                } else {
                                    cancellationSink.set(cancellation);
                                    callback.onProviderCancellable(cancellation);
                                }
                            }
                        });
                return getCredentials;
            } finally {
                Binder.restoreCallingIdentity(originalCallingUidToken);
            }
        }).orTimeout(TIMEOUT_REQUEST_MILLIS, TimeUnit.MILLISECONDS);
        futureRef.set(connectThenExecute);

        connectThenExecute.whenComplete((result, error) -> Handler.getMain().post(() ->
                handleExecutionResponse(result, error, cancellationSink, callback)));
    }

    /**
     * Main entry point to be called for executing a beginCreateCredential call on the remote
     * provider service.
     *
     * @param request  the request to be sent to the provider
     * @param callback the callback to be used to send back the provider response to the
     *                 {@link ProviderCreateSession} class that maintains provider state
     */
    public void onBeginCreateCredential(@NonNull BeginCreateCredentialRequest request,
            ProviderCallbacks<BeginCreateCredentialResponse> callback) {
        AtomicReference<ICancellationSignal> cancellationSink = new AtomicReference<>();
        AtomicReference<CompletableFuture<BeginCreateCredentialResponse>> futureRef =
                new AtomicReference<>();

        CompletableFuture<BeginCreateCredentialResponse> connectThenExecute =
                postAsync(service -> {
                    CompletableFuture<BeginCreateCredentialResponse> createCredentialFuture =
                            new CompletableFuture<>();
                    final long originalCallingUidToken = Binder.clearCallingIdentity();
                    try {
                        service.onBeginCreateCredential(
                                request, new IBeginCreateCredentialCallback.Stub() {
                                    @Override
                                    public void onSuccess(BeginCreateCredentialResponse response) {
                                        createCredentialFuture.complete(response);
                                    }

                                    @Override
                                    public void onFailure(String errorType, CharSequence message) {
                                        String errorMsg = message == null ? "" : String.valueOf(
                                                message);
                                        createCredentialFuture.completeExceptionally(
                                                new CreateCredentialException(errorType, errorMsg));
                                    }

                                    @Override
                                    public void onCancellable(ICancellationSignal cancellation) {
                                        CompletableFuture<BeginCreateCredentialResponse> future =
                                                futureRef.get();
                                        if (future != null && future.isCancelled()) {
                                            dispatchCancellationSignal(cancellation);
                                        } else {
                                            cancellationSink.set(cancellation);
                                            callback.onProviderCancellable(cancellation);
                                        }
                                    }
                                });
                        return createCredentialFuture;
                    } finally {
                        Binder.restoreCallingIdentity(originalCallingUidToken);
                    }
                }).orTimeout(TIMEOUT_REQUEST_MILLIS, TimeUnit.MILLISECONDS);
        futureRef.set(connectThenExecute);

        connectThenExecute.whenComplete((result, error) -> Handler.getMain().post(() ->
                handleExecutionResponse(result, error, cancellationSink, callback)));
    }

    /**
     * Main entry point to be called for executing a clearCredentialState call on the remote
     * provider service.
     *
     * @param request  the request to be sent to the provider
     * @param callback the callback to be used to send back the provider response to the
     *                 {@link ProviderClearSession} class that maintains provider state
     */
    public void onClearCredentialState(@NonNull ClearCredentialStateRequest request,
            ProviderCallbacks<Void> callback) {
        AtomicReference<ICancellationSignal> cancellationSink = new AtomicReference<>();
        AtomicReference<CompletableFuture<Void>> futureRef = new AtomicReference<>();

        CompletableFuture<Void> connectThenExecute =
                postAsync(service -> {
                    CompletableFuture<Void> clearCredentialFuture =
                            new CompletableFuture<>();
                    final long originalCallingUidToken = Binder.clearCallingIdentity();
                    try {
                        service.onClearCredentialState(
                                request, new IClearCredentialStateCallback.Stub() {
                                    @Override
                                    public void onSuccess() {
                                        clearCredentialFuture.complete(null);
                                    }

                                    @Override
                                    public void onFailure(String errorType, CharSequence message) {
                                        String errorMsg = message == null ? "" :
                                                String.valueOf(message);
                                        clearCredentialFuture.completeExceptionally(
                                                new ClearCredentialStateException(errorType,
                                                        errorMsg));
                                    }

                                    @Override
                                    public void onCancellable(ICancellationSignal cancellation) {
                                        CompletableFuture<Void> future = futureRef.get();
                                        if (future != null && future.isCancelled()) {
                                            dispatchCancellationSignal(cancellation);
                                        } else {
                                            cancellationSink.set(cancellation);
                                            callback.onProviderCancellable(cancellation);
                                        }
                                    }
                                });
                        return clearCredentialFuture;
                    } finally {
                        Binder.restoreCallingIdentity(originalCallingUidToken);
                    }
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
            callback.onProviderResponseSuccess(result);
        } else {
            if (error instanceof TimeoutException) {
                Slog.i(TAG, "Remote provider response timed tuo for: " + mComponentName);
                dispatchCancellationSignal(cancellationSink.get());
                callback.onProviderResponseFailure(
                        CredentialProviderErrors.ERROR_TIMEOUT,
                        null);
            } else if (error instanceof CancellationException) {
                Slog.i(TAG, "Cancellation exception for remote provider: " + mComponentName);
                dispatchCancellationSignal(cancellationSink.get());
                callback.onProviderResponseFailure(
                        CredentialProviderErrors.ERROR_TASK_CANCELED,
                        null);
            } else if (error instanceof GetCredentialException) {
                callback.onProviderResponseFailure(
                        CredentialProviderErrors.ERROR_PROVIDER_FAILURE,
                        (GetCredentialException) error);
            } else if (error instanceof CreateCredentialException) {
                callback.onProviderResponseFailure(
                        CredentialProviderErrors.ERROR_PROVIDER_FAILURE,
                        (CreateCredentialException) error);
            } else {
                callback.onProviderResponseFailure(
                        CredentialProviderErrors.ERROR_UNKNOWN,
                        (Exception) error);
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
