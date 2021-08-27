/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.security.keystore;

import android.annotation.DurationMillisLong;
import android.annotation.NonNull;

import java.security.ProviderException;

/**
 * Indicates a transient error that prevented a key operation from being created.
 * Callers should try again with a back-off period of {@link #getBackOffHintMillis()}
 * milliseconds.
 */
public class BackendBusyException extends ProviderException {

    private final long mBackOffHintMillis;

    /**
     * Constructs a new {@code BackendBusyException} without detail message and cause.
     *
     */
    public BackendBusyException(@DurationMillisLong long backOffHintMillis) {
        super("The keystore backend has no operation slots available. Retry later.");
        if (backOffHintMillis < 0) {
            throw new IllegalArgumentException("Back-off hint cannot be negative.");
        }
        mBackOffHintMillis = backOffHintMillis;
    }

    /**
     * Constructs a new {@code BackendBusyException} with the provided detail message and
     * no cause.
     */
    public BackendBusyException(@DurationMillisLong long backOffHintMillis,
            @NonNull String message) {
        super(message);
        if (backOffHintMillis < 0) {
            throw new IllegalArgumentException("Back-off hint cannot be negative.");
        }
        mBackOffHintMillis = backOffHintMillis;
    }

    /**
     * Constructs a new {@code BackendBusyException} with the provided detail message and
     * cause.
     */
    public BackendBusyException(@DurationMillisLong long backOffHintMillis,
            @NonNull String message, @NonNull Throwable cause) {
        super(message, cause);
        if (backOffHintMillis < 0) {
            throw new IllegalArgumentException("Back-off hint cannot be negative.");
        }
        mBackOffHintMillis = backOffHintMillis;
    }

    /**
     * When retrying to start a Keystore operation after receiving this exception, this can be
     * used to determine how long to wait before retrying. It is not guaranteed that the operation
     * will succeeds after this time. Multiple retries may be necessary if the system is congested.
     *
     * @return Number of milliseconds to back off before retrying.
     */
    public @DurationMillisLong long getBackOffHintMillis() {
        return mBackOffHintMillis;
    }
}
