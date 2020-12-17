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

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Factory to create asset matcher from JSON string.
 */
/* package private */ final class AssetMatcherFactory {

    private static final String FIELD_NOT_STRING_FORMAT_STRING = "Expected %s to be string.";
    private static final String NAMESPACE_NOT_SUPPORTED_STRING = "Namespace %s is not supported.";

    public static AbstractAssetMatcher create(String query) throws AssociationServiceException,
            JSONException {
        JSONObject queryObject = new JSONObject(query);

        String namespace = queryObject.optString(StatementUtils.NAMESPACE_FIELD, null);
        if (namespace == null) {
            throw new AssociationServiceException(String.format(
                    FIELD_NOT_STRING_FORMAT_STRING, StatementUtils.NAMESPACE_FIELD));
        }

        if (namespace.equals(StatementUtils.NAMESPACE_WEB)) {
            return new WebAssetMatcher(WebAsset.create(queryObject));
        } else if (namespace.equals(StatementUtils.NAMESPACE_ANDROID_APP)) {
            return new AndroidAppAssetMatcher(AndroidAppAsset.create(queryObject));
        } else {
            throw new AssociationServiceException(
                    String.format(NAMESPACE_NOT_SUPPORTED_STRING, namespace));
        }
    }
}
