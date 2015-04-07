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

/**
 * Match assets that have the same 'site' field.
 */
/* package private */ final class WebAssetMatcher extends AbstractAssetMatcher {

    private final WebAsset mQuery;

    public WebAssetMatcher(WebAsset query) {
        mQuery = query;
    }

    @Override
    public boolean matches(AbstractAsset asset) {
        if (asset instanceof WebAsset) {
            WebAsset webAsset = (WebAsset) asset;
            return webAsset.toJson().equals(mQuery.toJson());
        }
        return false;
    }

    @Override
    public int getMatchedLookupKey() {
        return mQuery.lookupKey();
    }
}
