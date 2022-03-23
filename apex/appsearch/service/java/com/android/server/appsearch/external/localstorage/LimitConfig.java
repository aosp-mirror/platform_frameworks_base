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
 * Defines limits placed on users of AppSearch and enforced by {@link AppSearchImpl}.
 *
 * @hide
 */
public interface LimitConfig {
    /**
     * The maximum number of bytes a single document is allowed to be.
     *
     * <p>Enforced at the time of serializing the document into a proto.
     *
     * <p>This limit has two purposes:
     *
     * <ol>
     *   <li>Prevent the system service from using too much memory during indexing or querying by
     *       capping the size of the data structures it needs to buffer
     *   <li>Prevent apps from using a very large amount of data by storing exceptionally large
     *       documents.
     * </ol>
     */
    int getMaxDocumentSizeBytes();

    /**
     * The maximum number of documents a single app is allowed to index.
     *
     * <p>Enforced at indexing time.
     *
     * <p>This limit has two purposes:
     *
     * <ol>
     *   <li>Protect icing lib's docid space from being overwhelmed by a single app. The overall
     *       docid limit is currently 2^20 (~1 million)
     *   <li>Prevent apps from using a very large amount of data on the system by storing too many
     *       documents.
     * </ol>
     */
    int getMaxDocumentCount();
}
