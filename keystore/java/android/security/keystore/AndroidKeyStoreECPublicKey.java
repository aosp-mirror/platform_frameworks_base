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

import java.security.interfaces.ECPublicKey;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;

/**
 * {@link ECPublicKey} backed by keystore.
 *
 * @hide
 */
public class AndroidKeyStoreECPublicKey extends AndroidKeyStorePublicKey implements ECPublicKey {

    private final ECParameterSpec mParams;
    private final ECPoint mW;

    public AndroidKeyStoreECPublicKey(String alias, int uid, byte[] x509EncodedForm, ECParameterSpec params,
            ECPoint w) {
        super(alias, uid, KeyProperties.KEY_ALGORITHM_EC, x509EncodedForm);
        mParams = params;
        mW = w;
    }

    public AndroidKeyStoreECPublicKey(String alias, int uid, ECPublicKey info) {
        this(alias, uid, info.getEncoded(), info.getParams(), info.getW());
        if (!"X.509".equalsIgnoreCase(info.getFormat())) {
            throw new IllegalArgumentException(
                    "Unsupported key export format: " + info.getFormat());
        }
    }

    @Override
    public ECParameterSpec getParams() {
        return mParams;
    }

    @Override
    public ECPoint getW() {
        return mW;
    }
}