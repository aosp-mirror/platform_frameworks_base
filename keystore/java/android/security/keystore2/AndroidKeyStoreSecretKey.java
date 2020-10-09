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

package android.security.keystore2;

import android.annotation.NonNull;
import android.security.KeyStoreSecurityLevel;
import android.system.keystore2.KeyDescriptor;
import android.system.keystore2.KeyMetadata;

import javax.crypto.SecretKey;

/**
 * {@link SecretKey} backed by Android Keystore.
 *
 * @hide
 */
public class AndroidKeyStoreSecretKey extends AndroidKeyStoreKey implements SecretKey {

    public AndroidKeyStoreSecretKey(@NonNull KeyDescriptor descriptor,
            @NonNull KeyMetadata metadata, @NonNull String algorithm,
            @NonNull KeyStoreSecurityLevel securityLevel) {
        super(descriptor, metadata.key.nspace, metadata.authorizations, algorithm, securityLevel);
    }
}
