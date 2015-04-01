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
 * Indicates that a cryptographic operation could not be performed because the key used by the
 * operation is permanently invalid because a new fingerprint was enrolled.
 *
 * @hide
 */
public class NewFingerprintEnrolledException extends CryptoOperationException {

    /**
     * Constructs a new {@code NewFingerprintEnrolledException} without detail message and cause.
     */
    public NewFingerprintEnrolledException() {
        super("Invalid key: new fingerprint enrolled");
    }

    /**
     * Constructs a new {@code NewFingerprintEnrolledException} with the provided detail message and
     * no cause.
     */
    public NewFingerprintEnrolledException(String message) {
        super(message);
    }
}
