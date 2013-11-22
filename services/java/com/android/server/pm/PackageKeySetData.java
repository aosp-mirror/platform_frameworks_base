/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server.pm;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class PackageKeySetData {

    private long[] mSigningKeySets;

    private long[] mDefinedKeySets;

    private final Map<String, Long> mKeySetAliases;

    PackageKeySetData() {
        mSigningKeySets = new long[0];
        mDefinedKeySets = new long[0];
        mKeySetAliases =  new HashMap<String, Long>();
    }

    PackageKeySetData(PackageKeySetData original) {
        mSigningKeySets = original.getSigningKeySets().clone();
        mDefinedKeySets = original.getDefinedKeySets().clone();
        mKeySetAliases = new HashMap<String, Long>();
        mKeySetAliases.putAll(original.getAliases());
    }

    public void addSigningKeySet(long ks) {
        // deduplicate
        for (long knownKeySet : mSigningKeySets) {
            if (ks == knownKeySet) {
                return;
            }
        }
        int end = mSigningKeySets.length;
        mSigningKeySets = Arrays.copyOf(mSigningKeySets, end + 1);
        mSigningKeySets[end] = ks;
    }

    public void removeSigningKeySet(long ks) {
        if (packageIsSignedBy(ks)) {
            long[] keysets = new long[mSigningKeySets.length - 1];
            int index = 0;
            for (long signingKeySet : mSigningKeySets) {
                if (signingKeySet != ks) {
                    keysets[index] = signingKeySet;
                    index += 1;
                }
            }
            mSigningKeySets = keysets;
        }
    }

    public void addDefinedKeySet(long ks, String alias) {
        // deduplicate
        for (long knownKeySet : mDefinedKeySets) {
            if (ks == knownKeySet) {
                return;
            }
        }
        int end = mDefinedKeySets.length;
        mDefinedKeySets = Arrays.copyOf(mDefinedKeySets, end + 1);
        mDefinedKeySets[end] = ks;
        mKeySetAliases.put(alias, ks);
    }

    public void removeDefinedKeySet(long ks) {
        if (mKeySetAliases.containsValue(ks)) {
            long[] keysets = new long[mDefinedKeySets.length - 1];
            int index = 0;
            for (long definedKeySet : mDefinedKeySets) {
                if (definedKeySet != ks) {
                    keysets[index] = definedKeySet;
                    index += 1;
                }
            }
            mDefinedKeySets = keysets;
            for (String alias : mKeySetAliases.keySet()) {
                if (mKeySetAliases.get(alias) == ks) {
                    mKeySetAliases.remove(alias);
                    break;
                }
            }
        }
    }

    public boolean packageIsSignedBy(long ks) {
        for (long signingKeySet : mSigningKeySets) {
            if (ks == signingKeySet) {
                return true;
            }
        }
        return false;
    }

    public long[] getSigningKeySets() {
        return mSigningKeySets;
    }

    public long[] getDefinedKeySets() {
        return mDefinedKeySets;
    }

    public Map<String, Long> getAliases() {
        return mKeySetAliases;
    }
}