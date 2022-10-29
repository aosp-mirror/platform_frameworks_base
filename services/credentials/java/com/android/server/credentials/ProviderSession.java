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
import android.content.ComponentName;
import android.credentials.ui.ProviderData;
import android.service.credentials.CredentialProviderException;
import android.service.credentials.CredentialProviderInfo;

/**
 * Provider session storing the state of provider response and ui entries.
 * @param <T> The request type expected from the remote provider, for a given request session.
 */
public abstract class ProviderSession<T> implements RemoteCredentialService.ProviderCallbacks<T> {
    // Key to be used as the entry key for an action entry
    protected static final String ACTION_ENTRY_KEY = "action_key";

    @NonNull protected final ComponentName mComponentName;
    @NonNull protected final CredentialProviderInfo mProviderInfo;
    @NonNull protected final RemoteCredentialService mRemoteCredentialService;
    @NonNull protected final int mUserId;
    @NonNull protected Status mStatus = Status.NOT_STARTED;
    @NonNull protected final ProviderInternalCallback mCallbacks;

    /**
     * Interface to be implemented by any class that wishes to get a callback when a particular
     * provider session's status changes. Typically, implemented by the {@link RequestSession}
     * class.
     */
    public interface ProviderInternalCallback {
        /**
         * Called when status changes.
         */
        void onProviderStatusChanged(Status status, ComponentName componentName);
    }

    protected ProviderSession(@NonNull CredentialProviderInfo info,
            @NonNull ProviderInternalCallback callbacks,
            @NonNull int userId,
            @NonNull RemoteCredentialService remoteCredentialService) {
        mProviderInfo = info;
        mCallbacks = callbacks;
        mUserId = userId;
        mComponentName = info.getServiceInfo().getComponentName();
        mRemoteCredentialService = remoteCredentialService;
    }

    /** Update the response state stored with the provider session. */
    protected abstract void updateResponse (T response);

    /** Update the response state stored with the provider session. */
    protected abstract T getResponse ();

    /** Should be overridden to prepare, and stores state for {@link ProviderData} to be
     * shown on the UI. */
    protected abstract ProviderData prepareUiData();

    /** Provider status at various states of the request session. */
    enum Status {
        NOT_STARTED,
        PENDING,
        REQUIRES_AUTHENTICATION,
        COMPLETE,
        SERVICE_DEAD,
        CANCELED
    }

    protected void setStatus(@NonNull Status status) {
        mStatus = status;
    }

    @NonNull
    protected Status getStatus() {
        return mStatus;
    }

    @NonNull
    protected ComponentName getComponentName() {
        return mComponentName;
    }

    /** Updates the status .*/
    protected void updateStatusAndInvokeCallback(@NonNull Status status) {
        setStatus(status);
        mCallbacks.onProviderStatusChanged(status, mComponentName);
    }

    @NonNull
    public static Status toStatus(
            @CredentialProviderException.CredentialProviderError int errorCode) {
        // TODO : Add more mappings as more flows are supported
        return Status.CANCELED;
    }

    /**
     * Returns true if the given status means that the provider session must be terminated.
     */
    public static boolean isTerminatingStatus(Status status) {
        return status == Status.CANCELED || status == Status.SERVICE_DEAD;
    }

    /**
     * Returns true if the given status means that the provider is done getting the response,
     * and is ready for user interaction.
     */
    public static boolean isCompletionStatus(Status status) {
        return status == Status.COMPLETE || status == Status.REQUIRES_AUTHENTICATION;
    }
}
