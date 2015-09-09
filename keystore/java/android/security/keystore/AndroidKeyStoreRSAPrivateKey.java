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

import java.math.BigInteger;
import java.security.PrivateKey;
import java.security.interfaces.RSAKey;

/**
 * RSA private key (instance of {@link PrivateKey} and {@link RSAKey}) backed by keystore.
 *
 * @hide
 */
public class AndroidKeyStoreRSAPrivateKey extends AndroidKeyStorePrivateKey implements RSAKey {

    private final BigInteger mModulus;

    public AndroidKeyStoreRSAPrivateKey(String alias, int uid, BigInteger modulus) {
        super(alias, uid, KeyProperties.KEY_ALGORITHM_RSA);
        mModulus = modulus;
    }

    @Override
    public BigInteger getModulus() {
        return mModulus;
    }
}
