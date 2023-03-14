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

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.os.CancellationSignal;
import android.os.OutcomeReceiver;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.concurrent.Executor;


/**
 * A response object that prefetches user app credentials and provides metadata about them. It can
 * then be used to issue the full credential retrieval flow via the
 * {@link #show(Activity, CancellationSignal, Executor, OutcomeReceiver)} method to perform the
 * necessary flows such as consent collection and officially retrieve a credential.
 *
 * @hide
 */
public final class GetPendingCredentialResponse implements Parcelable {
    private final boolean mHasCredentialResults;
    private final boolean mHasAuthenticationResults;
    private final boolean mHasRemoteResults;

    /** Returns true if the user has any candidate credentials, and false otherwise. */
    public boolean hasCredentialResults() {
        return mHasCredentialResults;
    }

    /**
     * Returns true if the user has any candidate authentication actions (locked credential
     * supplier), and false otherwise.
     */
    public boolean hasAuthenticationResults() {
        return mHasAuthenticationResults;
    }

    /**
     * Returns true if the user has any candidate remote credential results, and false otherwise.
     */
    public boolean hasRemoteResults() {
        return mHasRemoteResults;
    }

    /**
     * Launches the necessary flows such as consent collection and credential selection to
     * officially retrieve a credential among the pending credential candidates.
     *
     * @param activity           the activity used to launch any UI needed
     * @param cancellationSignal an optional signal that allows for cancelling this call
     * @param executor           the callback will take place on this {@link Executor}
     * @param callback           the callback invoked when the request succeeds or fails
     */
    public void show(@NonNull Activity activity, @Nullable CancellationSignal cancellationSignal,
            @CallbackExecutor @NonNull Executor executor,
            @NonNull OutcomeReceiver<GetCredentialResponse, GetCredentialException> callback) {
        // TODO(b/273308895): implement
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeBoolean(mHasCredentialResults);
        dest.writeBoolean(mHasAuthenticationResults);
        dest.writeBoolean(mHasRemoteResults);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "GetCredentialResponse {" + "credential=" + mHasCredentialResults + "}";
    }

    /**
     * Constructs a {@link GetPendingCredentialResponse}.
     *
     * @param hasCredentialResults whether the user has any candidate credentials
     * @param hasAuthenticationResults whether the user has any candidate authentication actions
     * @param hasRemoteResults whether the user has any candidate remote options
     */
    public GetPendingCredentialResponse(boolean hasCredentialResults,
            boolean hasAuthenticationResults, boolean hasRemoteResults) {
        mHasCredentialResults = hasCredentialResults;
        mHasAuthenticationResults = hasAuthenticationResults;
        mHasRemoteResults = hasRemoteResults;
    }

    private GetPendingCredentialResponse(@NonNull Parcel in) {
        mHasCredentialResults = in.readBoolean();
        mHasAuthenticationResults = in.readBoolean();
        mHasRemoteResults = in.readBoolean();
    }

    public static final @NonNull Creator<GetPendingCredentialResponse> CREATOR = new Creator<>() {
        @Override
        public GetPendingCredentialResponse[] newArray(int size) {
            return new GetPendingCredentialResponse[size];
        }

        @Override
        public GetPendingCredentialResponse createFromParcel(@NonNull Parcel in) {
            return new GetPendingCredentialResponse(in);
        }
    };
}
