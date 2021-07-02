/*
 * Copyright 2021 The Android Open Source Project
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

package com.android.server.appsearch.external.localstorage;


/**
 * In Jetpack, AppSearch doesn't enforce artificial limits on number of documents or size of
 * documents, since the app is the only user of the Icing instance. Icing still enforces a docid
 * limit of 1M docs.
 *
 * @hide
 */
public class UnlimitedLimitConfig implements LimitConfig {
    @Override
    public int getMaxDocumentSizeBytes() {
        return Integer.MAX_VALUE;
    }

    @Override
    public int getMaxDocumentCount() {
        return Integer.MAX_VALUE;
    }
}
