/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.annotation.SystemApi;
import android.annotation.TestApi;

/**
 * Thrown when {@link AttestationUtils} is unable to attest the given device ids.
 *
 * @hide
 */
@SystemApi
@TestApi
public class DeviceIdAttestationException extends Exception {
    /**
     * Constructs a new {@code DeviceIdAttestationException} with the current stack trace and the
     * specified detail message.
     *
     * @param detailMessage the detail message for this exception.
     */
    public DeviceIdAttestationException(String detailMessage) {
        super(detailMessage);
    }

    /**
     * Constructs a new {@code DeviceIdAttestationException} with the current stack trace, the
     * specified detail message and the specified cause.
     *
     * @param message the detail message for this exception.
     * @param cause the cause of this exception, may be {@code null}.
     */
    public DeviceIdAttestationException(String message, Throwable cause) {
        super(message, cause);
    }
}
