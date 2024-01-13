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

import android.annotation.Hide;
import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.util.Preconditions;

/**
 * Represents an error encountered during the
 * {@link CredentialManager#getCandidateCredentials} operation.
 *
 * @hide
 */
@Hide
public class GetCandidateCredentialsException extends Exception {
    /**
     * The error type value for when the given operation failed due to an unknown reason.
     */
    @NonNull
    public static final String TYPE_UNKNOWN =
            "android.credentials.GetCandidateCredentialsException.TYPE_UNKNOWN";

    /**
     * The error type value for when no credential is found available for the given {@link
     * CredentialManager#getCandidateCredentials} request.
     */
    @NonNull
    public static final String TYPE_NO_CREDENTIAL =
            "android.credentials.GetCandidateCredentialsException.TYPE_NO_CREDENTIAL";

    @NonNull
    public static final String TYPE_USER_CANCELED =
            "android.credentials.GetCredentialException.TYPE_USER_CANCELED";
    /**
     * The error type value for when the given operation failed due to internal interruption.
     * Retrying the same operation should fix the error.
     */
    @NonNull
    public static final String TYPE_INTERRUPTED =
            "android.credentials.GetCredentialException.TYPE_INTERRUPTED";

    @NonNull
    private final String mType;

    /** Returns the specific exception type. */
    @NonNull
    public String getType() {
        return mType;
    }

    /**
     * Constructs a {@link GetCandidateCredentialsException}.
     *
     * @throws IllegalArgumentException If type is empty.
     */
    public GetCandidateCredentialsException(@NonNull String type, @Nullable String message) {
        this(type, message, null);
    }

    /**
     * Constructs a {@link GetCandidateCredentialsException}.
     *
     * @throws IllegalArgumentException If type is empty.
     */
    public GetCandidateCredentialsException(
            @NonNull String type, @Nullable String message, @Nullable Throwable cause) {
        super(message, cause);
        this.mType = Preconditions.checkStringNotEmpty(type,
                "type must not be empty");
    }

    /**
     * Constructs a {@link GetCandidateCredentialsException}.
     *
     * @throws IllegalArgumentException If type is empty.
     */
    public GetCandidateCredentialsException(@NonNull String type, @Nullable Throwable cause) {
        this(type, null, cause);
    }

    /**
     * Constructs a {@link GetCandidateCredentialsException}.
     *
     * @throws IllegalArgumentException If type is empty.
     */
    public GetCandidateCredentialsException(@NonNull String type) {
        this(type, null, null);
    }
}
