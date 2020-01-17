/*
 * Copyright (C) 2019 The Android Open Source Project
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

import java.util.List;
import java.util.concurrent.Executor;
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
     * with such changes will result in the provided callback being called with a {@link Throwable}
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
     * <p>If you need to make non-backwards-compatible changes as described above, you may set the
     * {@code force} parameter to {@code true}. In this case, all documents which are not compatible
     * with the new schema will be deleted.
     *
     * <p>This operation is performed asynchronously. On success, the provided callback will be
     * called with {@code null}. On failure, the provided callback will be called with a
     * {@link Throwable} describing the failure.
     *
     * <p>It is a no-op to set the same schema as has been previously set; this is handled
     * efficiently.
     *
     * @param schemas The schema configs for the types used by the calling app.
     * @param force Whether to force the new schema to be applied even if there are incompatible
     *     changes versus the previously set schema. Documents which are incompatible with the new
     *     schema will be deleted.
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive errors resulting from setting the schema. If the
     *                 operation succeeds, the callback will be invoked with {@code null}.
     *
     * @hide
     */
    // TODO(b/143789408): linkify #put after that API is created
    public void setSchema(
            List<AppSearchSchema> schemas,
            boolean force,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<? super Throwable> callback) {
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
            mService.setSchema(schemaBytes, force, future);
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
}
