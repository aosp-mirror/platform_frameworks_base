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
 * {@link Key} backed by Android Keystore.
 *
 * @hide
 */
public class AndroidKeyStoreKey implements Key {
    private final String mAlias;
    private final int mUid;
    private final String mAlgorithm;

    public AndroidKeyStoreKey(String alias, int uid, String algorithm) {
        mAlias = alias;
        mUid = uid;
        mAlgorithm = algorithm;
    }

    String getAlias() {
        return mAlias;
    }

    int getUid() {
        return mUid;
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
        result = prime * result + ((mAlgorithm == null) ? 0 : mAlgorithm.hashCode());
        result = prime * result + ((mAlias == null) ? 0 : mAlias.hashCode());
        result = prime * result + mUid;
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
        if (mAlgorithm == null) {
            if (other.mAlgorithm != null) {
                return false;
            }
        } else if (!mAlgorithm.equals(other.mAlgorithm)) {
            return false;
        }
        if (mAlias == null) {
            if (other.mAlias != null) {
                return false;
            }
        } else if (!mAlias.equals(other.mAlias)) {
            return false;
        }
        if (mUid != other.mUid) {
            return false;
        }
        return true;
    }
}
