/*
 * Copyright 2019 The Android Open Source Project
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

package android.security.identity;

import android.annotation.NonNull;

/**
 * Thrown if trying to use a cipher suite which isn't supported.
 */
public class CipherSuiteNotSupportedException extends IdentityCredentialException {
    /**
     * Constructs a new {@link CipherSuiteNotSupportedException} exception.
     *
     * @param message the detail message.
     */
    public CipherSuiteNotSupportedException(@NonNull String message) {
        super(message);
    }

    /**
     * Constructs a new {@link CipherSuiteNotSupportedException} exception.
     *
     * @param message the detail message.
     * @param cause   the cause.
     */
    public CipherSuiteNotSupportedException(@NonNull String message, @NonNull Throwable cause) {
        super(message, cause);
    }
}
