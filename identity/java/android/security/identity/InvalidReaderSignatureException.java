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
 * Thrown if the reader signature is invalid, or it doesn't contain a certificate chain, or if the
 * signature failed to validate.
 */
public class InvalidReaderSignatureException extends IdentityCredentialException {
    /**
     * Constructs a new {@link InvalidReaderSignatureException} exception.
     *
     * @param message the detail message.
     */
    public InvalidReaderSignatureException(@NonNull String message) {
        super(message);
    }


    /**
     * Constructs a new {@link InvalidReaderSignatureException} exception.
     *
     * @param message the detail message.
     * @param cause   the cause.
     */
    public InvalidReaderSignatureException(@NonNull String message,
            @NonNull Throwable cause) {
        super(message, cause);
    }
}
