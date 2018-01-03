/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.util.apk;

import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;

/**
 * For legacy reasons we need to return exactly the original encoded certificate bytes, instead
 * of letting the underlying implementation have a shot at re-encoding the data.
 */
class VerbatimX509Certificate extends WrappedX509Certificate {
    private final byte[] mEncodedVerbatim;

    VerbatimX509Certificate(X509Certificate wrapped, byte[] encodedVerbatim) {
        super(wrapped);
        this.mEncodedVerbatim = encodedVerbatim;
    }

    @Override
    public byte[] getEncoded() throws CertificateEncodingException {
        return mEncodedVerbatim;
    }
}
