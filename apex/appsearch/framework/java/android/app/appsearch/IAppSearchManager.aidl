/**
 * Copyright 2020, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.app.appsearch;

import com.android.internal.infra.AndroidFuture;

parcelable AppSearchResult;
parcelable AppSearchBatchResult;

/** {@hide} */
interface IAppSearchManager {
    /**
     * Sets the schema.
     *
     * @param schemaBytes Serialized SchemaProto.
     * @param forceOverride Whether to apply the new schema even if it is incompatible. All
     *     incompatible documents will be deleted.
     * @param callback {@link AndroidFuture}&lt;{@link AppSearchResult}&lt;{@link Void}&gt&gt;.
     *     The results of the call.
     */
    void setSchema(
        in byte[] schemaBytes, boolean forceOverride, in AndroidFuture<AppSearchResult> callback);

    /**
     * Inserts documents into the index.
     *
     * @param documentsBytes {@link List}&lt;byte[]&gt; of serialized DocumentProtos.
     * @param callback
     *     {@link AndroidFuture}&lt;{@link AppSearchBatchResult}&lt;{@link String}, {@link Void}&gt;&gt;.
     *     If the call fails to start, {@code callback} will be completed exceptionally. Otherwise,
     *     {@code callback} will be completed with an
     *     {@link AppSearchBatchResult}&lt;{@link String}, {@link Void}&gt;
     *     where the keys are document URIs, and the values are {@code null}.
     */
    void putDocuments(in List documentsBytes, in AndroidFuture<AppSearchBatchResult> callback);

    /**
     * Retrieves documents from the index.
     *
     * @param uris The URIs of the documents to retrieve
     * @param callback
     *     {@link AndroidFuture}&lt;{@link AppSearchBatchResult}&lt;{@link String}, {@link byte[]}&gt;&gt;.
     *     If the call fails to start, {@code callback} will be completed exceptionally. Otherwise,
     *     {@code callback} will be completed with an
     *     {@link AppSearchBatchResult}&lt;{@link String}, {@link byte[]}&gt;
     *     where the keys are document URIs, and the values are serialized Document protos.
     */
    void getDocuments(in List<String> uris, in AndroidFuture<AppSearchBatchResult> callback);

    /**
     * Searches a document based on a given specifications.
     *
     * @param searchSpecBytes Serialized SearchSpecProto.
     * @param resultSpecBytes Serialized SearchResultsProto.
     * @param scoringSpecBytes Serialized ScoringSpecProto.
     * @param callback {@link AndroidFuture}&lt;{@link AppSearchResult}&lt;{@link byte[]}&gt;&gt;
     *     Will be completed with a serialized {@link SearchResultsProto}.
     */
    void query(
        in byte[] searchSpecBytes, in byte[] resultSpecBytes, in byte[] scoringSpecBytes,
        in AndroidFuture<AppSearchResult> callback);

    /**
     * Deletes all documents belonging to the calling app.
     *
     * @param callback {@link AndroidFuture}&lt;{@link AppSearchResult}&lt;{@link Void}&gt;&gt;.
     *     Will be completed with the result of the call.
     */
    void deleteAll(in AndroidFuture<AppSearchResult> callback);
}
