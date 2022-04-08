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

import android.security.KeyStore;
import android.security.KeyStoreException;

import java.security.ProviderException;

/**
 * Indicates that an operation could not be performed because the requested security hardware
 * is not available.
 */
public class StrongBoxUnavailableException extends ProviderException {

    public StrongBoxUnavailableException() {
        super();
    }

    public StrongBoxUnavailableException(String message) {
        super(message,
                new KeyStoreException(KeyStore.HARDWARE_TYPE_UNAVAILABLE, "No StrongBox available")
        );
    }

    public StrongBoxUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public StrongBoxUnavailableException(Throwable cause) {
        super(cause);
    }
}

