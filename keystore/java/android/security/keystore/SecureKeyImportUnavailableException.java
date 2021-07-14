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

import android.security.KeyStoreException;
import android.security.keymaster.KeymasterDefs;

import java.security.ProviderException;

/**
 * Indicates that the Keystore does not support securely importing wrapped keys.
 */
public class SecureKeyImportUnavailableException extends ProviderException {

    public SecureKeyImportUnavailableException() {
        super();
    }

    public SecureKeyImportUnavailableException(String message) {
        super(message, new KeyStoreException(KeymasterDefs.KM_ERROR_HARDWARE_TYPE_UNAVAILABLE,
                "Secure Key Import not available"));
    }

    public SecureKeyImportUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public SecureKeyImportUnavailableException(Throwable cause) {
        super(cause);
    }
}

