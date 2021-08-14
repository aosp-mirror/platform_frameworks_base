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
import android.security.keystore.ArrayUtils;
import android.system.keystore2.KeyDescriptor;
import android.system.keystore2.KeyMetadata;

import java.security.PublicKey;
import java.util.Objects;

/**
 * {@link PublicKey} backed by Android Keystore.
 *
 * @hide
 */
public abstract class AndroidKeyStorePublicKey extends AndroidKeyStoreKey implements PublicKey {
    private final byte[] mCertificate;
    private final byte[] mCertificateChain;
    private final byte[] mEncoded;

    public AndroidKeyStorePublicKey(@NonNull KeyDescriptor descriptor,
            @NonNull KeyMetadata metadata, @NonNull byte[] x509EncodedForm,
            @NonNull String algorithm, @NonNull KeyStoreSecurityLevel securityLevel) {
        super(descriptor, metadata.key.nspace, metadata.authorizations, algorithm, securityLevel);
        mCertificate = metadata.certificate;
        mCertificateChain = metadata.certificateChain;
        mEncoded = x509EncodedForm;
    }

    abstract AndroidKeyStorePrivateKey getPrivateKey();

    @Override
    public String getFormat() {
        return "X.509";
    }

    @Override
    public byte[] getEncoded() {
        return ArrayUtils.cloneIfNotEmpty(mEncoded);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;

        result = prime * result + super.hashCode();
        result = prime * result + ((mCertificate == null) ? 0 : mCertificate.hashCode());
        result = prime * result + ((mCertificateChain == null) ? 0 : mCertificateChain.hashCode());

        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!super.equals(obj)) {
            return false;
        }

        /*
         * getClass().equals(ojb.getClass()) is implied by the call to super.equals() above. This
         * means we can cast obj to AndroidKeyStorePublicKey here.
         */
        final AndroidKeyStorePublicKey other = (AndroidKeyStorePublicKey) obj;

        return Objects.equals(mCertificate, other.mCertificate) && Objects.equals(mCertificateChain,
                other.mCertificateChain);
    }
}
