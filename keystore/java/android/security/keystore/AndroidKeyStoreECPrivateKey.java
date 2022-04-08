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

import java.security.PrivateKey;
import java.security.interfaces.ECKey;
import java.security.spec.ECParameterSpec;

/**
 * EC private key (instance of {@link PrivateKey} and {@link ECKey}) backed by keystore.
 *
 * @hide
 */
public class AndroidKeyStoreECPrivateKey extends AndroidKeyStorePrivateKey implements ECKey {
    private final ECParameterSpec mParams;

    public AndroidKeyStoreECPrivateKey(String alias, int uid, ECParameterSpec params) {
        super(alias, uid, KeyProperties.KEY_ALGORITHM_EC);
        mParams = params;
    }

    @Override
    public ECParameterSpec getParams() {
        return mParams;
    }
}
