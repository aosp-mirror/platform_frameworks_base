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

import java.security.PublicKey;

/**
 * {@link PublicKey} backed by Android Keystore.
 *
 * @hide
 */
public class AndroidKeyStorePublicKey extends AndroidKeyStoreKey implements PublicKey {

    private final byte[] mEncoded;

    public AndroidKeyStorePublicKey(String alias, String algorithm, byte[] x509EncodedForm) {
        super(alias, algorithm);
        mEncoded = ArrayUtils.cloneIfNotEmpty(x509EncodedForm);
    }

    @Override
    public String getFormat() {
        return "X.509";
    }

    @Override
    public byte[] getEncoded() {
        return ArrayUtils.cloneIfNotEmpty(mEncoded);
    }
}
