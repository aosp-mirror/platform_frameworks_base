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

import org.json.JSONException;

/**
 * An asset matcher that can match asset with the given query.
 */
public abstract class AbstractAssetMatcher {

    /**
     * Returns true if this AssetMatcher matches the asset.
     */
    public abstract boolean matches(AbstractAsset asset);

    /**
     * This AssetMatcher will only match Asset with {@code lookupKey()} equal to the value returned
     * by this method.
     */
    public abstract int getMatchedLookupKey();

    /**
     * Creates a new AssetMatcher from its JSON string representation.
     *
     * <p> For web namespace, {@code query} will match assets that have the same 'site' field.
     *
     * <p> For Android namespace, {@code query} will match assets that have the same
     * 'package_name' field and have at least one common certificate fingerprint in
     * 'sha256_cert_fingerprints' field.
     */
    public static AbstractAssetMatcher createMatcher(String query)
            throws AssociationServiceException, JSONException {
        return AssetMatcherFactory.create(query);
    }
}
