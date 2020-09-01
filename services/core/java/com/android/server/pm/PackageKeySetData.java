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

import android.util.ArrayMap;

import com.android.internal.util.ArrayUtils;

import java.util.Map;

public class PackageKeySetData {

    static final long KEYSET_UNASSIGNED = -1;

    /* KeySet containing all signing keys - superset of the others */
    private long mProperSigningKeySet;

    private long[] mUpgradeKeySets;

    private final ArrayMap<String, Long> mKeySetAliases = new ArrayMap<String, Long>();

    PackageKeySetData() {
        mProperSigningKeySet = KEYSET_UNASSIGNED;
    }

    PackageKeySetData(PackageKeySetData original) {
        mProperSigningKeySet = original.mProperSigningKeySet;
        mUpgradeKeySets = ArrayUtils.cloneOrNull(original.mUpgradeKeySets);
        mKeySetAliases.putAll(original.mKeySetAliases);
    }

    protected void setProperSigningKeySet(long ks) {
        mProperSigningKeySet = ks;
        return;
    }

    protected long getProperSigningKeySet() {
        return mProperSigningKeySet;
    }

    protected void addUpgradeKeySet(String alias) {
        if (alias == null) {
            return;
        }

        /* must have previously been defined */
        Long ks = mKeySetAliases.get(alias);
        if (ks != null) {
            mUpgradeKeySets = ArrayUtils.appendLong(mUpgradeKeySets, ks);
        } else {
            throw new IllegalArgumentException("Upgrade keyset alias " + alias
                    + "does not refer to a defined keyset alias!");
        }
    }

    /*
     * Used only when restoring keyset data from persistent storage.  Must
     * correspond to a defined-keyset.
     */

    protected void addUpgradeKeySetById(long ks) {
        mUpgradeKeySets = ArrayUtils.appendLong(mUpgradeKeySets, ks);
    }

    protected void removeAllUpgradeKeySets() {
        mUpgradeKeySets = null;
        return;
    }

    protected long[] getUpgradeKeySets() {
        return mUpgradeKeySets;
    }

    protected ArrayMap<String, Long> getAliases() {
        return mKeySetAliases;
    }

    /*
     * Replace defined keysets with new ones.
     */
    protected void setAliases(Map<String, Long> newAliases) {

        /* remove old aliases */
        removeAllDefinedKeySets();

        /* add new ones */
        mKeySetAliases.putAll(newAliases);
    }

    protected void addDefinedKeySet(long ks, String alias) {
        mKeySetAliases.put(alias, ks);
    }

    protected void removeAllDefinedKeySets() {
        mKeySetAliases.erase();
    }

    protected boolean isUsingDefinedKeySets() {

        /* should never be the case that mUpgradeKeySets.length == 0 */
        return (mKeySetAliases.size() > 0);
    }

    protected boolean isUsingUpgradeKeySets() {

        /* should never be the case that mUpgradeKeySets.length == 0 */
        return (mUpgradeKeySets != null && mUpgradeKeySets.length > 0);
    }
}
