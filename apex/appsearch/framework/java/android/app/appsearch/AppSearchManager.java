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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

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
     * Sets the schema being used by documents provided to the {@link #putDocuments} method.
     *
     * <p>The schema provided here is compared to the stored copy of the schema previously supplied
     * to {@link #setSchema}, if any, to determine how to treat existing documents. The following
     * types of schema modifications are always safe and are made without deleting any existing
     * documents:
     * <ul>
     *     <li>Addition of new types
     *     <li>Addition of new
     *         {@link android.app.appsearch.AppSearchSchema.PropertyConfig#CARDINALITY_OPTIONAL
     *             OPTIONAL} or
     *         {@link android.app.appsearch.AppSearchSchema.PropertyConfig#CARDINALITY_REPEATED
     *             REPEATED} properties to a type
     *     <li>Changing the cardinality of a data type to be less restrictive (e.g. changing an
     *         {@link android.app.appsearch.AppSearchSchema.PropertyConfig#CARDINALITY_OPTIONAL
     *             OPTIONAL} property into a
     *         {@link android.app.appsearch.AppSearchSchema.PropertyConfig#CARDINALITY_REPEATED
     *             REPEATED} property.
     * </ul>
     *
     * <p>The following types of schema changes are not backwards-compatible. Supplying a schema
     * with such changes will result in this call throwing an {@link IllegalSchemaException}
     * describing the incompatibility, and the previously set schema will remain active:
     * <ul>
     *     <li>Removal of an existing type
     *     <li>Removal of a property from a type
     *     <li>Changing the data type ({@code boolean}, {@code long}, etc.) of an existing property
     *     <li>For properties of {@code Document} type, changing the schema type of
     *         {@code Document Documents} of that property
     *     <li>Changing the cardinality of a data type to be more restrictive (e.g. changing an
     *         {@link android.app.appsearch.AppSearchSchema.PropertyConfig#CARDINALITY_OPTIONAL
     *             OPTIONAL} property into a
     *         {@link android.app.appsearch.AppSearchSchema.PropertyConfig#CARDINALITY_REQUIRED
     *             REQUIRED} property).
     *     <li>Adding a
     *         {@link android.app.appsearch.AppSearchSchema.PropertyConfig#CARDINALITY_REQUIRED
     *             REQUIRED} property.
     * </ul>
     *
     * <p>If you need to make non-backwards-compatible changes as described above, instead use the
     * {@link #setSchema(List, boolean)} method with the {@code forceOverride} parameter set to
     * {@code true}.
     *
     * <p>It is a no-op to set the same schema as has been previously set; this is handled
     * efficiently.
     *
     * @param schemas The schema configs for the types used by the calling app.
     * @throws IllegalSchemaException If the provided schema is invalid, or is incompatible with the
     *     previous schema.
     *
     * @hide
     */
    public void setSchema(@NonNull AppSearchSchema... schemas) {
        setSchema(Arrays.asList(schemas), /*forceOverride=*/false);
    }

    /**
     * Sets the schema being used by documents provided to the {@link #putDocuments} method.
     *
     * <p>This method is similar to {@link #setSchema(AppSearchSchema...)}, except for the
     * {@code forceOverride} parameter. If a backwards-incompatible schema is specified but the
     * {@code forceOverride} parameter is set to {@code true}, instead of throwing an
     * {@link IllegalSchemaException}, all documents which are not compatible with the new schema
     * will be deleted and the incompatible schema will be applied.
     *
     * @param schemas The schema configs for the types used by the calling app.
     * @param forceOverride Whether to force the new schema to be applied even if there are
     *     incompatible changes versus the previously set schema. Documents which are incompatible
     *     with the new schema will be deleted.
     * @throws IllegalSchemaException If the provided schema is invalid, or is incompatible with the
     *     previous schema and the {@code forceOverride} parameter is set to {@code false}.
     *
     * @hide
     */
    public void setSchema(@NonNull List<AppSearchSchema> schemas, boolean forceOverride) {
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
            mService.setSchema(schemaBytes, forceOverride, future);
        } catch (RemoteException e) {
            future.completeExceptionally(e);
        }
        getFutureOrThrow(future);
    }

    /**
     * Index {@link android.app.appsearch.AppSearch.Document Documents} into AppSearch.
     *
     * <p>You should not call this method directly; instead, use the
     * {@code AppSearch#putDocuments()} API provided by JetPack.
     *
     * <p>Each {@link AppSearch.Document Document's} {@code schemaType} field must be set to the
     * name of a schema type previously registered via the {@link #setSchema} method.
     *
     * @param documents {@link Document Documents} that need to be indexed.
     * @return An {@link AppSearchBatchResult} mapping the document URIs to {@link Void} if they
     *     were successfully indexed, or a {@link Throwable} describing the failure if they could
     *     not be indexed.
     * @hide
     */
    public AppSearchBatchResult<String, Void> putDocuments(@NonNull List<Document> documents) {
        // TODO(b/146386470): Transmit these documents as a RemoteStream instead of sending them in
        // one big list.
        List<byte[]> documentsBytes = new ArrayList<>(documents.size());
        for (Document document : documents) {
            documentsBytes.add(document.getProto().toByteArray());
        }
        AndroidFuture<AppSearchBatchResult> future = new AndroidFuture<>();
        try {
            mService.putDocuments(documentsBytes, future);
        } catch (RemoteException e) {
            future.completeExceptionally(e);
        }
        return getFutureOrThrow(future);
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

    private static <T> T getFutureOrThrow(@NonNull AndroidFuture<T> future) {
        try {
            return future.get();
        } catch (Throwable e) {
            if (e instanceof ExecutionException) {
                e = e.getCause();
            }
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            if (e instanceof Error) {
                throw (Error) e;
            }
            throw new RuntimeException(e);
        }
    }
}
