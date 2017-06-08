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

package android.net.lowpan;

import java.util.Map;

/**
 * Describes a credential for a LoWPAN network.
 *
 * @hide
 */
// @SystemApi
public class LowpanCredential {

    public static final int UNSPECIFIED_KEY_INDEX = 0;

    private byte[] mMasterKey = null;
    private int mMasterKeyIndex = UNSPECIFIED_KEY_INDEX;

    LowpanCredential() {}

    private LowpanCredential(byte[] masterKey, int keyIndex) {
        setMasterKey(masterKey, keyIndex);
    }

    private LowpanCredential(byte[] masterKey) {
        setMasterKey(masterKey);
    }

    public static LowpanCredential createMasterKey(byte[] masterKey) {
        return new LowpanCredential(masterKey);
    }

    public static LowpanCredential createMasterKey(byte[] masterKey, int keyIndex) {
        return new LowpanCredential(masterKey, keyIndex);
    }

    public void setMasterKey(byte[] masterKey) {
        if (masterKey != null) {
            masterKey = masterKey.clone();
        }
        mMasterKey = masterKey;
    }

    public void setMasterKeyIndex(int keyIndex) {
        mMasterKeyIndex = keyIndex;
    }

    public void setMasterKey(byte[] masterKey, int keyIndex) {
        setMasterKey(masterKey);
        setMasterKeyIndex(keyIndex);
    }

    public byte[] getMasterKey() {
        if (mMasterKey != null) {
            return mMasterKey.clone();
        }
        return null;
    }

    public int getMasterKeyIndex() {
        return mMasterKeyIndex;
    }

    public boolean isMasterKey() {
        return mMasterKey != null;
    }

    void addToMap(Map<String, Object> parameters) throws LowpanException {
        if (isMasterKey()) {
            LowpanProperties.KEY_NETWORK_MASTER_KEY.putInMap(parameters, getMasterKey());
            LowpanProperties.KEY_NETWORK_MASTER_KEY_INDEX.putInMap(parameters, getMasterKeyIndex());
        } else {
            throw new LowpanException("Unsupported Network Credential");
        }
    }
}
