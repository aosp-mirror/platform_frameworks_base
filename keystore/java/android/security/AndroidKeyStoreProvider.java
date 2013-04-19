/*
 * Copyright (C) 2012 The Android Open Source Project
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

import java.security.Provider;

/**
 * A provider focused on providing JCA interfaces for the Android KeyStore.
 *
 * @hide
 */
public class AndroidKeyStoreProvider extends Provider {
    public static final String PROVIDER_NAME = "AndroidKeyStore";

    public AndroidKeyStoreProvider() {
        super(PROVIDER_NAME, 1.0, "Android KeyStore security provider");

        // java.security.KeyStore
        put("KeyStore." + AndroidKeyStore.NAME, AndroidKeyStore.class.getName());

        // java.security.KeyPairGenerator
        put("KeyPairGenerator.RSA", AndroidKeyPairGenerator.class.getName());
    }
}
