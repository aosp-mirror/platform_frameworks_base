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

import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.Context;
import android.credentials.ClearCredentialStateException;
import android.credentials.CredentialProviderInfo;
import android.credentials.ui.ProviderData;
import android.credentials.ui.ProviderPendingIntentResponse;
import android.os.ICancellationSignal;
import android.service.credentials.CallingAppInfo;
import android.service.credentials.ClearCredentialStateRequest;
import android.util.Slog;

/**
 * Central provider session that listens for provider callbacks, and maintains provider state.
 *
 * @hide
 */
public final class ProviderClearSession extends ProviderSession<ClearCredentialStateRequest,
        Void>
        implements
        RemoteCredentialService.ProviderCallbacks<Void> {
    private static final String TAG = "ProviderClearSession";

    private ClearCredentialStateException mProviderException;

    /** Creates a new provider session to be used by the request session. */
    @Nullable
    public static ProviderClearSession createNewSession(
            Context context,
            @UserIdInt int userId,
            CredentialProviderInfo providerInfo,
            ClearRequestSession clearRequestSession,
            RemoteCredentialService remoteCredentialService) {
        ClearCredentialStateRequest providerRequest =
                createProviderRequest(
                        clearRequestSession.mClientRequest,
                        clearRequestSession.mClientAppInfo);
        return new ProviderClearSession(context, providerInfo, clearRequestSession, userId,
                remoteCredentialService, providerRequest);
    }

    @Nullable
    private static ClearCredentialStateRequest createProviderRequest(
            android.credentials.ClearCredentialStateRequest clientRequest,
            CallingAppInfo callingAppInfo
    ) {
        return new ClearCredentialStateRequest(
                callingAppInfo,
                clientRequest.getData());
    }

    public ProviderClearSession(Context context,
            CredentialProviderInfo info,
            ProviderInternalCallback callbacks,
            int userId, RemoteCredentialService remoteCredentialService,
            ClearCredentialStateRequest providerRequest) {
        super(context, providerRequest, callbacks, info.getComponentName(),
                userId, remoteCredentialService);
        setStatus(Status.PENDING);
    }

    @Override
    public void onProviderResponseSuccess(@Nullable Void response) {
        Slog.i(TAG, "Remote provider responded with a valid response: " + mComponentName);
        mProviderResponseSet = true;
        updateStatusAndInvokeCallback(Status.COMPLETE,
                /*source=*/ CredentialsSource.REMOTE_PROVIDER);
    }

    /** Called when the provider response resulted in a failure. */
    @Override // Callback from the remote provider
    public void onProviderResponseFailure(int errorCode, Exception exception) {
        if (exception instanceof ClearCredentialStateException) {
            mProviderException = (ClearCredentialStateException) exception;
            // TODO(b/271135048) : Decide on exception type length
            mProviderSessionMetric.collectCandidateFrameworkException(mProviderException.getType());
        }
        mProviderSessionMetric.collectCandidateExceptionStatus(/*hasException=*/true);
        updateStatusAndInvokeCallback(Status.CANCELED,
                /*source=*/ CredentialsSource.REMOTE_PROVIDER);
    }

    /** Called when provider service dies. */
    @Override // Callback from the remote provider
    public void onProviderServiceDied(RemoteCredentialService service) {
        if (service.getComponentName().equals(mComponentName)) {
            updateStatusAndInvokeCallback(Status.SERVICE_DEAD,
                    /*source=*/ CredentialsSource.REMOTE_PROVIDER);
        } else {
            Slog.w(TAG, "Component names different in onProviderServiceDied - "
                    + "this should not happen");
        }
    }

    @Override
    public void onProviderCancellable(ICancellationSignal cancellation) {
        mProviderCancellationSignal = cancellation;
    }

    @Nullable
    @Override
    protected ProviderData prepareUiData() {
        //Not applicable for clearCredential as response is not picked by the user
        return null;
    }

    @Override
    protected void onUiEntrySelected(String entryType, String entryId,
            ProviderPendingIntentResponse providerPendingIntentResponse) {
        //Not applicable for clearCredential as response is not picked by the user
    }

    @Override
    protected void invokeSession() {
        if (mRemoteCredentialService != null) {
            startCandidateMetrics();
            mRemoteCredentialService.setCallback(this);
            mRemoteCredentialService.onClearCredentialState(mProviderRequest);
        }
    }
}
