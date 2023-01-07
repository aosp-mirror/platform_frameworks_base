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
 * {@link CredentialManager#executeCreateCredential(CreateCredentialRequest,
 * Activity, CancellationSignal, Executor, OutcomeReceiver)} operation.
 */
public class CreateCredentialException extends Exception {
    /**
     * The error type value for when no credential is available for the given {@link
     * CredentialManager#executeCreateCredential(CreateCredentialRequest, Activity,
     * CancellationSignal, Executor, OutcomeReceiver)} request.
     */
    @NonNull
    public static final String TYPE_NO_CREDENTIAL =
            "android.credentials.CreateCredentialException.TYPE_NO_CREDENTIAL";

    @NonNull
    private final String mType;

    /** Returns the specific exception type. */
    @NonNull
    public String getType() {
        return mType;
    }

    /**
     * Constructs a {@link CreateCredentialException}.
     *
     * @throws IllegalArgumentException If type is empty.
     */
    public CreateCredentialException(@NonNull String type, @Nullable String message) {
        this(type, message, null);
    }

    /**
     * Constructs a {@link CreateCredentialException}.
     *
     * @throws IllegalArgumentException If type is empty.
     */
    public CreateCredentialException(
            @NonNull String type, @Nullable String message, @Nullable Throwable cause) {
        super(message, cause);
        this.mType = Preconditions.checkStringNotEmpty(type,
                "type must not be empty");
    }

    /**
     * Constructs a {@link CreateCredentialException}.
     *
     * @throws IllegalArgumentException If type is empty.
     */
    public CreateCredentialException(@NonNull String type, @Nullable Throwable cause) {
        this(type, null, cause);
    }

    /**
     * Constructs a {@link CreateCredentialException}.
     *
     * @throws IllegalArgumentException If type is empty.
     */
    public CreateCredentialException(@NonNull String type) {
        this(type, null, null);
    }
}
