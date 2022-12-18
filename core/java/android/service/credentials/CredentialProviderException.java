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

package android.service.credentials;

import android.annotation.IntDef;
import android.annotation.NonNull;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Contains custom exceptions to be used by credential providers on failure.
 *
 * @hide
 */
public class CredentialProviderException extends Exception {
    public static final int ERROR_UNKNOWN = 0;

    /**
     * For internal use only.
     * Error code to be used when the provider request times out.
     *
     * @hide
     */
    public static final int ERROR_TIMEOUT = 1;

    /**
     * For internal use only.
     * Error code to be used when the async task is canceled internally.
     *
     * @hide
     */
    public static final int ERROR_TASK_CANCELED = 2;

    /**
     * For internal use only.
     * Error code to be used when the provider encounters a failure while processing the request.
     *
     * @hide
     */
    public static final int ERROR_PROVIDER_FAILURE = 3;

    private final int mErrorCode;

    /**
     * @hide
     */
    @IntDef(prefix = {"ERROR_"}, value = {
            ERROR_UNKNOWN,
            ERROR_TIMEOUT,
            ERROR_TASK_CANCELED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CredentialProviderError { }

    public CredentialProviderException(@CredentialProviderError int errorCode,
            @NonNull String message, @NonNull Throwable cause) {
        super(message, cause);
        mErrorCode = errorCode;
    }

    public CredentialProviderException(@CredentialProviderError int errorCode,
            @NonNull String message) {
        super(message);
        mErrorCode = errorCode;
    }

    public CredentialProviderException(@CredentialProviderError int errorCode,
            @NonNull Throwable cause) {
        super(cause);
        mErrorCode = errorCode;
    }

    public CredentialProviderException(@CredentialProviderError int errorCode) {
        super();
        mErrorCode = errorCode;
    }

    public @CredentialProviderError int getErrorCode() {
        return mErrorCode;
    }
}
