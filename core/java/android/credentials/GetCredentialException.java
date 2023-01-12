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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.os.CancellationSignal;
import android.os.OutcomeReceiver;

import com.android.internal.util.Preconditions;

import java.util.concurrent.Executor;

/**
 * Represents an error encountered during the
 * {@link CredentialManager#executeGetCredential(GetCredentialRequest,
 * Activity, CancellationSignal, Executor, OutcomeReceiver)} operation.
 */
public class GetCredentialException extends Exception {
    /**
     * The error type value for when the given operation failed due to an unknown reason.
     */
    @NonNull
    public static final String TYPE_UNKNOWN =
            "android.credentials.GetCredentialException.TYPE_UNKNOWN";

    /**
     * The error type value for when no credential is found available for the given {@link
     * CredentialManager#executeGetCredential(GetCredentialRequest, Activity, CancellationSignal,
     * Executor, OutcomeReceiver)} request.
     */
    @NonNull
    public static final String TYPE_NO_CREDENTIAL =
            "android.credentials.GetCredentialException.TYPE_NO_CREDENTIAL";
    /**
     * The error type value for when the user intentionally cancelled the request.
     *
     * <p>This is a strong indicator that your app should refrain from making the same api call for
     * a certain amount of time to provide a better user experience.
     */
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
     * Constructs a {@link GetCredentialException}.
     *
     * @throws IllegalArgumentException If type is empty.
     */
    public GetCredentialException(@NonNull String type, @Nullable String message) {
        this(type, message, null);
    }

    /**
     * Constructs a {@link GetCredentialException}.
     *
     * @throws IllegalArgumentException If type is empty.
     */
    public GetCredentialException(
            @NonNull String type, @Nullable String message, @Nullable Throwable cause) {
        super(message, cause);
        this.mType = Preconditions.checkStringNotEmpty(type,
                "type must not be empty");
    }

    /**
     * Constructs a {@link GetCredentialException}.
     *
     * @throws IllegalArgumentException If type is empty.
     */
    public GetCredentialException(@NonNull String type, @Nullable Throwable cause) {
        this(type, null, cause);
    }

    /**
     * Constructs a {@link GetCredentialException}.
     *
     * @throws IllegalArgumentException If type is empty.
     */
    public GetCredentialException(@NonNull String type) {
        this(type, null, null);
    }
}
