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

package android.security.keystore;

import java.security.InvalidKeyException;

/**
 * Indicates the condition that a proof of user-presence was
 * requested but this proof was not presented.
 */
public class UserPresenceUnavailableException extends InvalidKeyException {
    /**
     * Constructs a {@code UserPresenceUnavailableException} without a detail message or cause.
     */
    public UserPresenceUnavailableException() {
        super("No Strong Box available.");
    }

    /**
     * Constructs a {@code UserPresenceUnavailableException} using the provided detail message
     * but no cause.
     */
    public UserPresenceUnavailableException(String message) {
        super(message);
    }

    /**
     * Constructs a {@code UserPresenceUnavailableException} using the provided detail message
     * and cause.
     */
    public UserPresenceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
