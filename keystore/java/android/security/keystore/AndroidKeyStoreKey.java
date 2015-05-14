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

import java.security.Key;

/**
 * {@link Key} backed by AndroidKeyStore.
 *
 * @hide
 */
public class AndroidKeyStoreKey implements Key {
    private final String mAlias;
    private final String mAlgorithm;

    public AndroidKeyStoreKey(String alias, String algorithm) {
        mAlias = alias;
        mAlgorithm = algorithm;
    }

    String getAlias() {
        return mAlias;
    }

    @Override
    public String getAlgorithm() {
        return mAlgorithm;
    }

    @Override
    public String getFormat() {
        // This key does not export its key material
        return null;
    }

    @Override
    public byte[] getEncoded() {
        // This key does not export its key material
        return null;
    }
}
