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
import android.system.keystore2.Authorization;
import android.system.keystore2.Domain;
import android.system.keystore2.KeyDescriptor;

import com.android.internal.annotations.VisibleForTesting;

import java.security.Key;

/**
 * {@link Key} backed by Android Keystore.
 *
 * @hide
 */
public class AndroidKeyStoreKey implements Key {
    // This is the original KeyDescriptor by which the key was loaded from
    // with alias and domain.
    private final KeyDescriptor mDescriptor;
    // The key id can be used make certain manipulations to the keystore database
    // assuring that the manipulation is made to the exact key that was loaded
    // from the database. Alias based manipulations can not assure this, because
    // aliases can be rebound to other keys at any time.
    private final long mKeyId;
    private final Authorization[] mAuthorizations;
    // TODO extract algorithm string from metadata.
    private final String mAlgorithm;

    // This is the security level interface, that this key is associated with.
    // We do not include this member in comparisons.
    private final KeyStoreSecurityLevel mSecurityLevel;

    /**
     * @hide
     */
    @VisibleForTesting
    public AndroidKeyStoreKey(@NonNull KeyDescriptor descriptor,
            long keyId,
            @NonNull Authorization[] authorizations,
            @NonNull String algorithm,
            @NonNull KeyStoreSecurityLevel securityLevel) {
        mDescriptor = descriptor;
        mKeyId = keyId;
        mAuthorizations = authorizations;
        mAlgorithm = algorithm;
        mSecurityLevel = securityLevel;
    }

    KeyDescriptor getUserKeyDescriptor() {
        return mDescriptor;
    }

    KeyDescriptor getKeyIdDescriptor() {
        KeyDescriptor descriptor = new KeyDescriptor();
        descriptor.nspace = mKeyId;
        descriptor.domain = Domain.KEY_ID;
        descriptor.alias = null;
        descriptor.blob = null;
        return descriptor;
    }

    Authorization[] getAuthorizations() {
        return mAuthorizations;
    }

    KeyStoreSecurityLevel getSecurityLevel() {
        return mSecurityLevel;
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

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;

        result = prime * result + getClass().hashCode();
        result = prime * result + (int) (mKeyId >>> 32);
        result = prime * result + (int) (mKeyId & 0xffffffff);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        AndroidKeyStoreKey other = (AndroidKeyStoreKey) obj;
        return mKeyId == other.mKeyId;
    }
}
