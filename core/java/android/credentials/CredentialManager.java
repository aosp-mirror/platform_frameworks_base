/*
 * Copyright 2022 The Android Open Source Project
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

package android.credentials;

import static java.util.Objects.requireNonNull;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemService;
import android.content.Context;
import android.os.CancellationSignal;
import android.os.ICancellationSignal;
import android.os.OutcomeReceiver;
import android.os.RemoteException;
import android.util.Log;

import java.util.concurrent.Executor;

/**
 * Manages user authentication flows.
 *
 * <p>Note that an application should call the Jetpack CredentialManager apis instead of directly
 * calling these framework apis.
 *
 * <p>The CredentialManager apis launch framework UI flows for a user to
 * register a new credential or to consent to a saved credential from supported credential
 * providers, which can then be used to authenticate to the app.
 */
@SystemService(Context.CREDENTIAL_SERVICE)
public final class CredentialManager {
    private static final String TAG = "CredentialManager";

    private final Context mContext;
    private final ICredentialManager mService;

    /**
     * @hide instantiated by ContextImpl.
     */
    public CredentialManager(Context context, ICredentialManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * Launches the necessary flows to retrieve an app credential from the user.
     *
     * <p>The execution can potentially launch UI flows to collect user consent to using a
     * credential, display a picker when multiple credentials exist, etc.
     *
     * @param request the request specifying type(s) of credentials to get from the user.
     * @param cancellationSignal an optional signal that allows for cancelling this call.
     * @param executor the callback will take place on this {@link Executor}.
     * @param callback the callback invoked when the request succeeds or fails.
     */
    public void executeGetCredential(
            @NonNull GetCredentialRequest request,
            @Nullable CancellationSignal cancellationSignal,
            @CallbackExecutor @NonNull Executor executor,
            @NonNull OutcomeReceiver<
                    GetCredentialResponse, CredentialManagerException> callback) {
        requireNonNull(request, "request must not be null");
        requireNonNull(executor, "executor must not be null");
        requireNonNull(callback, "callback must not be null");

        if (cancellationSignal != null && cancellationSignal.isCanceled()) {
            Log.w(TAG, "executeGetCredential already canceled");
            return;
        }

        ICancellationSignal cancelRemote = null;
        try {
            cancelRemote = mService.executeGetCredential(request,
                    new GetCredentialTransport(executor, callback));
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }

        if (cancellationSignal != null && cancelRemote != null) {
            cancellationSignal.setRemote(cancelRemote);
        }
    }

    /**
     * Launches the necessary flows to register an app credential for the user.
     *
     * <p>The execution can potentially launch UI flows to collect user consent to creating
     * or storing the new credential, etc.
     *
     * @param request the request specifying type(s) of credentials to get from the user.
     * @param cancellationSignal an optional signal that allows for cancelling this call.
     * @param executor the callback will take place on this {@link Executor}.
     * @param callback the callback invoked when the request succeeds or fails.
     */
    public void executeCreateCredential(
            @NonNull CreateCredentialRequest request,
            @Nullable CancellationSignal cancellationSignal,
            @CallbackExecutor @NonNull Executor executor,
            @NonNull OutcomeReceiver<
                    CreateCredentialResponse, CredentialManagerException> callback) {
        requireNonNull(request, "request must not be null");
        requireNonNull(executor, "executor must not be null");
        requireNonNull(callback, "callback must not be null");

        if (cancellationSignal != null && cancellationSignal.isCanceled()) {
            Log.w(TAG, "executeCreateCredential already canceled");
            return;
        }

        ICancellationSignal cancelRemote = null;
        try {
            cancelRemote = mService.executeCreateCredential(request,
                    new CreateCredentialTransport(executor, callback));
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }

        if (cancellationSignal != null && cancelRemote != null) {
            cancellationSignal.setRemote(cancelRemote);
        }
    }

    private static class GetCredentialTransport extends IGetCredentialCallback.Stub {
        // TODO: listen for cancellation to release callback.

        private final Executor mExecutor;
        private final OutcomeReceiver<
                GetCredentialResponse, CredentialManagerException> mCallback;

        private GetCredentialTransport(Executor executor,
                OutcomeReceiver<GetCredentialResponse, CredentialManagerException> callback) {
            mExecutor = executor;
            mCallback = callback;
        }

        @Override
        public void onResponse(GetCredentialResponse response) {
            mExecutor.execute(() -> mCallback.onResult(response));
        }

        @Override
        public void onError(int errorCode, String message) {
            mExecutor.execute(
                    () -> mCallback.onError(new CredentialManagerException(errorCode, message)));
        }
    }

    private static class CreateCredentialTransport extends ICreateCredentialCallback.Stub {
        // TODO: listen for cancellation to release callback.

        private final Executor mExecutor;
        private final OutcomeReceiver<
                CreateCredentialResponse, CredentialManagerException> mCallback;

        private CreateCredentialTransport(Executor executor,
                OutcomeReceiver<CreateCredentialResponse, CredentialManagerException> callback) {
            mExecutor = executor;
            mCallback = callback;
        }

        @Override
        public void onResponse(CreateCredentialResponse response) {
            mExecutor.execute(() -> mCallback.onResult(response));
        }

        @Override
        public void onError(int errorCode, String message) {
            mExecutor.execute(
                    () -> mCallback.onError(new CredentialManagerException(errorCode, message)));
        }
    }
}
