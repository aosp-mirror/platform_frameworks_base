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

import static android.Manifest.permission.CREDENTIAL_MANAGER_QUERY_CANDIDATE_CREDENTIALS;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Context;
import android.content.IntentSender;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.OutcomeReceiver;
import android.util.Log;

import java.util.concurrent.Executor;


/**
 * A response object that prefetches user app credentials and provides metadata about them. It can
 * then be used to issue the full credential retrieval flow via the
 * {@link CredentialManager#getCredential(Context, PendingGetCredentialHandle, CancellationSignal,
 * Executor, OutcomeReceiver)} method to perform the remaining flows such as consent collection
 * and credential selection, to officially retrieve a credential.
 */
public final class PrepareGetCredentialResponse {

    private static final Bundle OPTIONS_SENDER_BAL_OPTIN = ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle();

    /**
     * A handle that represents a pending get-credential operation. Pass this handle to {@link
     * CredentialManager#getCredential(Context, PendingGetCredentialHandle, CancellationSignal,
     * Executor, OutcomeReceiver)} to perform the remaining flows to officially retrieve a
     * credential.
     */
    public static final class PendingGetCredentialHandle {
        @NonNull
        private final CredentialManager.GetCredentialTransportPendingUseCase
                mGetCredentialTransport;
        /**
         * The pending intent to be launched to finalize the user credential. If null, the callback
         * will fail with {@link GetCredentialException#TYPE_NO_CREDENTIAL}.
         */
        @Nullable
        private final PendingIntent mPendingIntent;

        /** @hide */
        PendingGetCredentialHandle(
                @NonNull CredentialManager.GetCredentialTransportPendingUseCase transport,
                @Nullable PendingIntent pendingIntent) {
            mGetCredentialTransport = transport;
            mPendingIntent = pendingIntent;
        }

        /** @hide */
        void show(@NonNull Context context, @Nullable CancellationSignal cancellationSignal,
                @CallbackExecutor @NonNull Executor executor,
                @NonNull OutcomeReceiver<GetCredentialResponse, GetCredentialException> callback) {
            if (mPendingIntent == null) {
                executor.execute(() -> callback.onError(
                        new GetCredentialException(GetCredentialException.TYPE_NO_CREDENTIAL)));
                return;
            }

            mGetCredentialTransport.setCallback(new GetPendingCredentialInternalCallback() {
                @Override
                public void onPendingIntent(PendingIntent pendingIntent) {
                    try {
                        context.startIntentSender(pendingIntent.getIntentSender(), null, 0, 0, 0,
                                OPTIONS_SENDER_BAL_OPTIN);
                    } catch (IntentSender.SendIntentException e) {
                        Log.e(TAG, "startIntentSender() failed for intent for show()", e);
                        executor.execute(() -> callback.onError(
                                new GetCredentialException(GetCredentialException.TYPE_UNKNOWN)));
                    }
                }

                @Override
                public void onResponse(GetCredentialResponse response) {
                    executor.execute(() -> callback.onResult(response));
                }

                @Override
                public void onError(String errorType, String message) {
                    executor.execute(
                            () -> callback.onError(new GetCredentialException(errorType, message)));
                }
            });

            try {
                context.startIntentSender(mPendingIntent.getIntentSender(), null, 0, 0, 0,
                        OPTIONS_SENDER_BAL_OPTIN);
            } catch (IntentSender.SendIntentException e) {
                Log.e(TAG, "startIntentSender() failed for intent for show()", e);
                executor.execute(() -> callback.onError(
                        new GetCredentialException(GetCredentialException.TYPE_UNKNOWN)));
            }
        }
    }
    private static final String TAG = "CredentialManager";

    @NonNull private final PrepareGetCredentialResponseInternal mResponseInternal;

    @NonNull private final PendingGetCredentialHandle mPendingGetCredentialHandle;

    /**
     * Returns true if the user has any candidate credentials for the given {@code credentialType},
     * and false otherwise.
     */
    @RequiresPermission(CREDENTIAL_MANAGER_QUERY_CANDIDATE_CREDENTIALS)
    public boolean hasCredentialResults(@NonNull String credentialType) {
        return mResponseInternal.hasCredentialResults(credentialType);
    }

    /**
     * Returns true if the user has any candidate authentication actions (locked credential
     * supplier), and false otherwise.
     */
    @RequiresPermission(CREDENTIAL_MANAGER_QUERY_CANDIDATE_CREDENTIALS)
    public boolean hasAuthenticationResults() {
        return mResponseInternal.hasAuthenticationResults();
    }

    /**
     * Returns true if the user has any candidate remote credential results, and false otherwise.
     */
    @RequiresPermission(CREDENTIAL_MANAGER_QUERY_CANDIDATE_CREDENTIALS)
    public boolean hasRemoteResults() {
        return mResponseInternal.hasRemoteResults();
    }

    /**
     * Returns a handle that represents this pending get-credential operation. Pass this handle to
     * {@link CredentialManager#getCredential(Context, PendingGetCredentialHandle,
     * CancellationSignal, Executor, OutcomeReceiver)} to perform the remaining flows to officially
     * retrieve a credential.
     */
    @NonNull
    public PendingGetCredentialHandle getPendingGetCredentialHandle() {
        return mPendingGetCredentialHandle;
    }

    /**
     * Constructs a {@link PrepareGetCredentialResponse}.
     *
     * @param responseInternal       whether caller has the permission to query the credential
     *                               result metadata
     * @param getCredentialTransport the transport for the operation to finalaze a credential
     * @hide
     */
    protected PrepareGetCredentialResponse(
            @NonNull PrepareGetCredentialResponseInternal responseInternal,
            @NonNull CredentialManager.GetCredentialTransportPendingUseCase
                    getCredentialTransport) {
        mResponseInternal = responseInternal;
        mPendingGetCredentialHandle = new PendingGetCredentialHandle(
                getCredentialTransport, responseInternal.getPendingIntent());
    }

    /** @hide */
    protected interface GetPendingCredentialInternalCallback {
        void onPendingIntent(@NonNull PendingIntent pendingIntent);

        void onResponse(@NonNull GetCredentialResponse response);

        void onError(@NonNull String errorType, @Nullable String message);
    }
}
