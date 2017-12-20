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

import java.security.KeyStore.Entry;
import java.security.spec.AlgorithmParameterSpec;

/**
 * An {@link Entry} that holds a wrapped key.
 */
public class WrappedKeyEntry implements Entry {

    private final byte[] mWrappedKeyBytes;
    private final String mWrappingKeyAlias;
    private final String mTransformation;
    private final AlgorithmParameterSpec mAlgorithmParameterSpec;

    public WrappedKeyEntry(byte[] wrappedKeyBytes, String wrappingKeyAlias, String transformation,
            AlgorithmParameterSpec algorithmParameterSpec) {
        mWrappedKeyBytes = wrappedKeyBytes;
        mWrappingKeyAlias = wrappingKeyAlias;
        mTransformation = transformation;
        mAlgorithmParameterSpec = algorithmParameterSpec;
    }

    public byte[] getWrappedKeyBytes() {
        return mWrappedKeyBytes;
    }

    public String getWrappingKeyAlias() {
        return mWrappingKeyAlias;
    }

    public String getTransformation() {
        return mTransformation;
    }

    public AlgorithmParameterSpec getAlgorithmParameterSpec() {
        return mAlgorithmParameterSpec;
    }
}
