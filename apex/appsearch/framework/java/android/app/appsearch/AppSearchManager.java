/*
 * Copyright (C) 2020 The Android Open Source Project
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
package android.app.appsearch;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.SystemService;
import android.app.appsearch.AppSearch.Document;
import android.content.Context;
import android.os.RemoteException;

import com.android.internal.infra.AndroidFuture;

import com.google.android.icing.proto.SchemaProto;
import com.google.android.icing.proto.SearchResultProto;
import com.google.android.icing.proto.StatusProto;
import com.google.android.icing.protobuf.InvalidProtocolBufferException;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * This class provides access to the centralized AppSearch index maintained by the system.
 *
 * <p>Apps can index structured text documents with AppSearch, which can then be retrieved through
 * the query API.
 *
 * @hide
 */
@SystemService(Context.APP_SEARCH_SERVICE)
public class AppSearchManager {
    private final IAppSearchManager mService;

    /** @hide */
    public AppSearchManager(@NonNull IAppSearchManager service) {
        mService = service;
    }

    /**
     * Sets the schema being used by documents provided to the #put method.
     *
     * <p>This operation is performed asynchronously. On success, the provided callback will be
     * called with {@code null}. On failure, the provided callback will be called with a
     * {@link Throwable} describing the failure.
     *
     * <p>It is a no-op to set the same schema as has been previously set; this is handled
     * efficiently.
     *
     * <p>AppSearch automatically handles the following types of schema changes:
     * <ul>
     *     <li>Addition of new types (No changes to storage or index)
     *     <li>Removal of an existing type (All documents of the removed type are deleted)
     *     <li>Addition of new 'optional' property to a type (No changes to storage or index)
     *     <li>Removal of existing property of any cardinality (All documents reindexed)
     * </ul>
     *
     * <p>This method will return an error when attempting to make the following types of changes:
     * <ul>
     *     <li>Changing the type of an existing property
     *     <li>Adding a 'required' property
     * </ul>
     *
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive errors resulting from setting the schema. If the
     *                 operation succeeds, the callback will be invoked with {@code null}.
     * @param schemas The schema configs for the types used by the calling app.
     *
     * @hide
     */
    // TODO(b/143789408): linkify #put after that API is created
    // TODO(b/145635424): add a 'force' param to setSchema after the corresponding API is finalized
    //     in Icing Library
    // TODO(b/145635424): Update the documentation above once the Schema mutation APIs of Icing
    //     Library are finalized
    public void setSchema(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<? super Throwable> callback,
            @NonNull AppSearchSchema... schemas) {
        // Prepare the merged schema for transmission.
        SchemaProto.Builder schemaProtoBuilder = SchemaProto.newBuilder();
        for (AppSearchSchema schema : schemas) {
            schemaProtoBuilder.addTypes(schema.getProto());
        }

        // Serialize and send the schema.
        // TODO: This should use com.android.internal.infra.RemoteStream or another mechanism to
        //  avoid binder limits.
        byte[] schemaBytes = schemaProtoBuilder.build().toByteArray();
        AndroidFuture<Void> future = new AndroidFuture<>();
        try {
            mService.setSchema(schemaBytes, future);
        } catch (RemoteException e) {
            future.completeExceptionally(e);
        }
        future.whenCompleteAsync((noop, err) -> callback.accept(err), executor);
    }

    /**
     * Index {@link Document} to AppSearch
     *
     * <p>You should not call this method directly; instead, use the {@code AppSearch#put()} API
     * provided by JetPack.
     *
     * <p>The schema should be set via {@link #setSchema} method.
     *
     * @param documents {@link Document Documents} that need to be indexed.
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive errors resulting from setting the schema. If the
     *                 operation succeeds, the callback will be invoked with {@code null}.
     */
    public void put(@NonNull List<Document> documents,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<? super Throwable> callback) {
        AndroidFuture<Void> future = new AndroidFuture<>();
        for (Document document : documents) {
            // TODO(b/146386470) batching Document protos
            try {
                mService.put(document.getProto().toByteArray(), future);
            } catch (RemoteException e) {
                future.completeExceptionally(e);
                break;
            }
        }
        // TODO(b/147614371) Fix error report for multiple documents.
        future.whenCompleteAsync((noop, err) -> callback.accept(err), executor);
    }

    /**
     * This method searches for documents based on a given query string. It also accepts
     * specifications regarding how to search and format the results.
     *
     *<p>Currently we support following features in the raw query format:
     * <ul>
     *     <li>AND
     *     AND joins (e.g. “match documents that have both the terms ‘dog’ and
     *     ‘cat’”).
     *     Example: hello world matches documents that have both ‘hello’ and ‘world’
     *     <li>OR
     *     OR joins (e.g. “match documents that have either the term ‘dog’ or
     *     ‘cat’”).
     *     Example: dog OR puppy
     *     <li>Exclusion
     *     Exclude a term (e.g. “match documents that do
     *     not have the term ‘dog’”).
     *     Example: -dog excludes the term ‘dog’
     *     <li>Grouping terms
     *     Allow for conceptual grouping of subqueries to enable hierarchical structures (e.g.
     *     “match documents that have either ‘dog’ or ‘puppy’, and either ‘cat’ or ‘kitten’”).
     *     Example: (dog puppy) (cat kitten) two one group containing two terms.
     *     <li>Property restricts
     *      which properties of a document to specifically match terms in (e.g.
     *     “match documents where the ‘subject’ property contains ‘important’”).
     *     Example: subject:important matches documents with the term ‘important’ in the
     *     ‘subject’ property
     *     <li>Schema type restricts
     *     This is similar to property restricts, but allows for restricts on top-level document
     *     fields, such as schema_type. Clients should be able to limit their query to documents of
     *     a certain schema_type (e.g. “match documents that are of the ‘Email’ schema_type”).
     *     Example: { schema_type_filters: “Email”, “Video”,query: “dog” } will match documents
     *     that contain the query term ‘dog’ and are of either the ‘Email’ schema type or the
     *     ‘Video’ schema type.
     * </ul>
     *
     * <p> It is strongly recommended to use Jetpack APIs.
     *
     * @param queryExpression Query String to search.
     * @param searchSpec Spec for setting filters, raw query etc.
     * @param executor Executor on which to invoke the callback.
     * @param callback  Callback to receive errors resulting from the query operation. If the
     *                 operation succeeds, the callback will be invoked with {@code null}.
     * @hide
     */
    @NonNull
    public void query(
            @NonNull String queryExpression,
            @NonNull SearchSpec searchSpec,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull BiConsumer<? super SearchResults, ? super Throwable> callback) {
        AndroidFuture<byte[]> future = new AndroidFuture<>();
        future.whenCompleteAsync((searchResultBytes, err) -> {
            if (err != null) {
                callback.accept(null, err);
                return;
            }

            if (searchResultBytes != null) {
                SearchResultProto searchResultProto;
                try {
                    searchResultProto = SearchResultProto.parseFrom(searchResultBytes);
                } catch (InvalidProtocolBufferException e) {
                    callback.accept(null, e);
                    return;
                }
                if (searchResultProto.getStatus().getCode() != StatusProto.Code.OK) {
                    // TODO(sidchhabra): Add better exception handling.
                    callback.accept(
                            null,
                            new RuntimeException(searchResultProto.getStatus().getMessage()));
                    return;
                }
                SearchResults searchResults = new SearchResults(searchResultProto);
                callback.accept(searchResults, null);
                return;
            }

            // Nothing was supplied in the future at all
            callback.accept(
                    null, new IllegalStateException("Unknown failure occurred while querying"));
        }, executor);

        try {
            mService.query(queryExpression, searchSpec.getProto().toByteArray(), future);
        } catch (RemoteException e) {
            future.completeExceptionally(e);
        }
    }
}
