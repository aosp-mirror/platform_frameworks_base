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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.app.Activity;
import android.app.PendingIntent;
import android.os.CancellationSignal;
import android.os.OutcomeReceiver;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArraySet;

import java.util.Set;
import java.util.concurrent.Executor;


/**
 * An internal response object that prefetches user app credentials and provides metadata about
 * them.
 *
 * @hide
 */
public final class PrepareGetCredentialResponseInternal implements Parcelable {
    private static final String TAG = "CredentialManager";

    private final boolean mHasQueryApiPermission;
    @Nullable
    private final ArraySet<String> mCredentialResultTypes;
    private final boolean mHasAuthenticationResults;
    private final boolean mHasRemoteResults;
    /**
     * The pending intent to be launched to finalize the user credential. If null, the callback
     * will fail with {@link GetCredentialException#TYPE_NO_CREDENTIAL}.
     */
    @Nullable
    private final PendingIntent mPendingIntent;

    @Nullable
    public PendingIntent getPendingIntent() {
        return mPendingIntent;
    }

    /**
     * Returns true if the user has any candidate credentials for the given {@code credentialType},
     * and false otherwise.
     */
    @RequiresPermission(CREDENTIAL_MANAGER_QUERY_CANDIDATE_CREDENTIALS)
    public boolean hasCredentialResults(@NonNull String credentialType) {
        if (!mHasQueryApiPermission) {
            throw new SecurityException(
                    "caller doesn't have the permission to query credential results");
        }
        if (mCredentialResultTypes == null) {
            return false;
        }
        return mCredentialResultTypes.contains(credentialType);
    }

    /**
     * Returns true if the user has any candidate authentication actions (locked credential
     * supplier), and false otherwise.
     */
    @RequiresPermission(CREDENTIAL_MANAGER_QUERY_CANDIDATE_CREDENTIALS)
    public boolean hasAuthenticationResults() {
        if (!mHasQueryApiPermission) {
            throw new SecurityException(
                    "caller doesn't have the permission to query authentication results");
        }
        return mHasAuthenticationResults;
    }

    /**
     * Returns true if the user has any candidate remote credential results, and false otherwise.
     */
    @RequiresPermission(CREDENTIAL_MANAGER_QUERY_CANDIDATE_CREDENTIALS)
    public boolean hasRemoteResults() {
        if (!mHasQueryApiPermission) {
            throw new SecurityException(
                    "caller doesn't have the permission to query remote results");
        }
        return mHasRemoteResults;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeBoolean(mHasQueryApiPermission);
        dest.writeArraySet(mCredentialResultTypes);
        dest.writeBoolean(mHasAuthenticationResults);
        dest.writeBoolean(mHasRemoteResults);
        dest.writeTypedObject(mPendingIntent, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Constructs a {@link PrepareGetCredentialResponseInternal}.
     *
     * @param hasQueryApiPermission    whether caller has the permission to query the credential
     *                                 result metadata
     * @param credentialResultTypes    a set of credential types that each has candidate credentials
     *                                 found, or null if the caller doesn't have the permission to
     *                                 this information
     * @param hasAuthenticationResults whether the user has any candidate authentication actions, or
     *                                 false if the caller doesn't have the permission to this
     *                                 information
     * @param hasRemoteResults         whether the user has any candidate remote options, or false
     *                                 if the caller doesn't have the permission to this information
     * @param pendingIntent            the pending intent to be launched during
     *                                 {@link #show(Activity, CancellationSignal, Executor,
     *                                 OutcomeReceiver)}} to
     *                                 finalize the user credential
     * @hide
     */
    public PrepareGetCredentialResponseInternal(boolean hasQueryApiPermission,
            @Nullable Set<String> credentialResultTypes,
            boolean hasAuthenticationResults, boolean hasRemoteResults,
            @Nullable PendingIntent pendingIntent) {
        mHasQueryApiPermission = hasQueryApiPermission;
        mCredentialResultTypes = new ArraySet<>(credentialResultTypes);
        mHasAuthenticationResults = hasAuthenticationResults;
        mHasRemoteResults = hasRemoteResults;
        mPendingIntent = pendingIntent;
    }

    private PrepareGetCredentialResponseInternal(@NonNull Parcel in) {
        mHasQueryApiPermission = in.readBoolean();
        mCredentialResultTypes = (ArraySet<String>) in.readArraySet(null);
        mHasAuthenticationResults = in.readBoolean();
        mHasRemoteResults = in.readBoolean();
        mPendingIntent = in.readTypedObject(PendingIntent.CREATOR);
    }

    public static final @NonNull Creator<PrepareGetCredentialResponseInternal> CREATOR =
            new Creator<>() {
        @Override
        public PrepareGetCredentialResponseInternal[] newArray(int size) {
            return new PrepareGetCredentialResponseInternal[size];
        }

        @Override
        public PrepareGetCredentialResponseInternal createFromParcel(@NonNull Parcel in) {
            return new PrepareGetCredentialResponseInternal(in);
        }
    };
}
