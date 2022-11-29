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

    @NonNull
    public final String errorType;

    /**
     * Constructs a {@link GetCredentialException}.
     *
     * @throws IllegalArgumentException If errorType is empty.
     */
    public GetCredentialException(@NonNull String errorType, @Nullable String message) {
        this(errorType, message, null);
    }

    /**
     * Constructs a {@link GetCredentialException}.
     *
     * @throws IllegalArgumentException If errorType is empty.
     */
    public GetCredentialException(
            @NonNull String errorType, @Nullable String message, @Nullable Throwable cause) {
        super(message, cause);
        this.errorType = Preconditions.checkStringNotEmpty(errorType,
                "errorType must not be empty");
    }

    /**
     * Constructs a {@link GetCredentialException}.
     *
     * @throws IllegalArgumentException If errorType is empty.
     */
    public GetCredentialException(@NonNull String errorType, @Nullable Throwable cause) {
        this(errorType, null, cause);
    }

    /**
     * Constructs a {@link GetCredentialException}.
     *
     * @throws IllegalArgumentException If errorType is empty.
     */
    public GetCredentialException(@NonNull String errorType) {
        this(errorType, null, null);
    }
}
