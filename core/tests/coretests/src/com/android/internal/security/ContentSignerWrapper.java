/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.internal.security;

import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.operator.ContentSigner;

import java.io.OutputStream;

/** A wrapper class of ContentSigner */
class ContentSignerWrapper implements ContentSigner {
    private final ContentSigner mSigner;

    ContentSignerWrapper(ContentSigner wrapped) {
        mSigner = wrapped;
    }

    @Override
    public AlgorithmIdentifier getAlgorithmIdentifier() {
        return mSigner.getAlgorithmIdentifier();
    }

    @Override
    public OutputStream getOutputStream() {
        return mSigner.getOutputStream();
    }

    @Override
    public byte[] getSignature() {
        return mSigner.getSignature();
    }
}
