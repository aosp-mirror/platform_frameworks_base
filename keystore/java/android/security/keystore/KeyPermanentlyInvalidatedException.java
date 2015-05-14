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
 * Indicates that the key can no longer be used because it has been permanently invalidated.
 *
 * <p>This can currently occur only for keys that require user authentication. Such keys are
 * permanently invalidated once the secure lock screen is disabled (i.e., reconfigured to None,
 * Swipe or other mode which does not authenticate the user) or when the secure lock screen is
 * forcibly reset (e.g., by Device Admin). Additionally, keys configured to require user
 * authentication for every use of the key are also permanently invalidated once a new fingerprint
 * is enrolled or once no more fingerprints are enrolled.
 */
public class KeyPermanentlyInvalidatedException extends InvalidKeyException {

    /**
     * Constructs a new {@code KeyPermanentlyInvalidatedException} without detail message and cause.
     */
    public KeyPermanentlyInvalidatedException() {
        super("Key permanently invalidated");
    }

    /**
     * Constructs a new {@code KeyPermanentlyInvalidatedException} with the provided detail message
     * and no cause.
     */
    public KeyPermanentlyInvalidatedException(String message) {
        super(message);
    }

    /**
     * Constructs a new {@code KeyPermanentlyInvalidatedException} with the provided detail message
     * and cause.
     */
    public KeyPermanentlyInvalidatedException(String message, Throwable cause) {
        super(message, cause);
    }
}
