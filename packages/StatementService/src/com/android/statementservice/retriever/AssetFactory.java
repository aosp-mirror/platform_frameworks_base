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

import com.android.statementservice.utils.StatementUtils;

import org.json.JSONObject;

/**
 * Factory to create asset from JSON string.
 */
public final class AssetFactory {

    private static final String FIELD_NOT_STRING_FORMAT_STRING = "Expected %s to be string.";

    private AssetFactory() {}

    /**
     * Checks that the input is a valid asset with purposes.
     *
     * @throws AssociationServiceException if the asset is not well formatted.
     */
    public static AbstractAsset create(JSONObject asset)
            throws AssociationServiceException {
        String namespace = asset.optString(StatementUtils.NAMESPACE_FIELD, null);
        if (namespace == null) {
            throw new AssociationServiceException(String.format(
                    FIELD_NOT_STRING_FORMAT_STRING, StatementUtils.NAMESPACE_FIELD));
        }

        if (namespace.equals(StatementUtils.NAMESPACE_WEB)) {
            return WebAsset.create(asset);
        } else if (namespace.equals(StatementUtils.NAMESPACE_ANDROID_APP)) {
            return AndroidAppAsset.create(asset);
        } else {
            throw new AssociationServiceException("Namespace " + namespace + " is not supported.");
        }
    }
}
