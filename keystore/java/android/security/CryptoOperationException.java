/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.security;

/**
 * Base class for exceptions during cryptographic operations which cannot throw a suitable checked
 * exception.
 *
 * <p>The contract of the majority of crypto primitives/operations (e.g. {@code Cipher} or
 * {@code Signature}) is that they can throw a checked exception during initialization, but are not
 * permitted to throw a checked exception during operation. Because crypto operations can fail
 * for a variety of reasons after initialization, this base class provides type-safety for unchecked
 * exceptions that may be thrown in those cases.
 *
 * @hide
 */
public class CryptoOperationException extends RuntimeException {

    /**
     * Constructs a new {@code CryptoOperationException} without detail message and cause.
     */
    public CryptoOperationException() {
        super();
    }

    /**
     * Constructs a new {@code CryptoOperationException} with the provided detail message and no
     * cause.
     */
    public CryptoOperationException(String message) {
        super(message);
    }

    /**
     * Constructs a new {@code CryptoOperationException} with the provided detail message and cause.
     */
    public CryptoOperationException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs a new {@code CryptoOperationException} with the provided cause.
     */
    public CryptoOperationException(Throwable cause) {
        super(cause);
    }
}
