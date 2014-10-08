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

import com.android.internal.util.ArrayUtils;

import java.util.HashMap;
import java.util.Map;

public class PackageKeySetData {

    static final long KEYSET_UNASSIGNED = -1;

    /* KeySet containing all signing keys - superset of the others */
    private long mProperSigningKeySet;

    private long[] mSigningKeySets;

    private long[] mUpgradeKeySets;

    private long[] mDefinedKeySets;

    private final Map<String, Long> mKeySetAliases = new HashMap<String, Long>();

    PackageKeySetData() {
        mProperSigningKeySet = KEYSET_UNASSIGNED;
    }

    PackageKeySetData(PackageKeySetData original) {
        mProperSigningKeySet = original.mProperSigningKeySet;
        mSigningKeySets = ArrayUtils.cloneOrNull(original.mSigningKeySets);
        mUpgradeKeySets = ArrayUtils.cloneOrNull(original.mUpgradeKeySets);
        mDefinedKeySets = ArrayUtils.cloneOrNull(original.mDefinedKeySets);
        mKeySetAliases.putAll(original.mKeySetAliases);
    }

    protected void setProperSigningKeySet(long ks) {
        if (ks == mProperSigningKeySet) {

            /* nothing to change */
            return;
        }

        /* otherwise, our current signing keysets are likely invalid */
        removeAllSigningKeySets();
        mProperSigningKeySet = ks;
        addSigningKeySet(ks);
        return;
    }

    protected long getProperSigningKeySet() {
        return mProperSigningKeySet;
    }

    protected void addSigningKeySet(long ks) {
        mSigningKeySets = ArrayUtils.appendLong(mSigningKeySets, ks);
    }

    protected void removeSigningKeySet(long ks) {
        mSigningKeySets = ArrayUtils.removeLong(mSigningKeySets, ks);
    }

    protected void addUpgradeKeySet(String alias) {

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
        mSigningKeySets = ArrayUtils.appendLong(mSigningKeySets, ks);
    }

    protected void addDefinedKeySet(long ks, String alias) {
        mDefinedKeySets = ArrayUtils.appendLong(mDefinedKeySets, ks);
        mKeySetAliases.put(alias, ks);
    }

    protected void removeAllSigningKeySets() {
        mProperSigningKeySet = KEYSET_UNASSIGNED;
        mSigningKeySets = null;
        return;
    }

    protected void removeAllUpgradeKeySets() {
        mUpgradeKeySets = null;
        return;
    }

    protected void removeAllDefinedKeySets() {
        mDefinedKeySets = null;
        mKeySetAliases.clear();
        return;
    }

    protected boolean packageIsSignedBy(long ks) {
        return ArrayUtils.contains(mSigningKeySets, ks);
    }

    protected long[] getSigningKeySets() {
        return mSigningKeySets;
    }

    protected long[] getUpgradeKeySets() {
        return mUpgradeKeySets;
    }

    protected long[] getDefinedKeySets() {
        return mDefinedKeySets;
    }

    protected Map<String, Long> getAliases() {
        return mKeySetAliases;
    }

    protected boolean isUsingDefinedKeySets() {

        /* should never be the case that mDefinedKeySets.length == 0 */
        return (mDefinedKeySets != null && mDefinedKeySets.length > 0);
    }

    protected boolean isUsingUpgradeKeySets() {

        /* should never be the case that mUpgradeKeySets.length == 0 */
        return (mUpgradeKeySets != null && mUpgradeKeySets.length > 0);
    }
}
