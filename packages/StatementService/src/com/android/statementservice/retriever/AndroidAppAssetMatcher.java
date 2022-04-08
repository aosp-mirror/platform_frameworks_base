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

package com.android.statementservice.retriever;

import java.util.HashSet;
import java.util.Set;

/**
 * Match assets that have the same 'package_name' field and have at least one common certificate
 * fingerprint in 'sha256_cert_fingerprints' field.
 */
/* package private */ final class AndroidAppAssetMatcher extends AbstractAssetMatcher {

    private final AndroidAppAsset mQuery;

    public AndroidAppAssetMatcher(AndroidAppAsset query) {
        mQuery = query;
    }

    @Override
    public boolean matches(AbstractAsset asset) {
        if (asset instanceof AndroidAppAsset) {
            AndroidAppAsset androidAppAsset = (AndroidAppAsset) asset;
            if (!androidAppAsset.getPackageName().equals(mQuery.getPackageName())) {
                return false;
            }

            Set<String> certs = new HashSet<String>(mQuery.getCertFingerprints());
            for (String cert : androidAppAsset.getCertFingerprints()) {
                if (certs.contains(cert)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public int getMatchedLookupKey() {
        return mQuery.lookupKey();
    }
}
